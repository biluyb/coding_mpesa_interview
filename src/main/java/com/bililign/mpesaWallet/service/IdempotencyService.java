package com.bililign.mpesaWallet.service;

import com.bililign.mpesaWallet.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Supplier;

@Service
public class IdempotencyService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ResponseEntity<?> execute(String key, String endpoint, Object requestBody, Supplier<ResponseEntity<?>> execution) {
        if (key == null || key.isBlank() || key.length() > 64) {
            throw ApiException.validationFailed("Idempotency-Key must be 1 to 64 characters");
        }

        String bodyString = "";
        if (requestBody != null) {
            if (requestBody instanceof String s) {
                bodyString = s;
            } else {
                try {
                    bodyString = objectMapper.writeValueAsString(requestBody);
                } catch (Exception e) {
                    bodyString = requestBody.toString();
                }
            }
        }

        String requestHash = sha256(endpoint + ":" + bodyString.replaceAll("\\s+", ""));

        // Try insert IN_PROGRESS
        try {
            jdbcTemplate.update(
                    "INSERT INTO idempotency_keys (key, request_hash, status, created_at, updated_at) " +
                            "VALUES (?, ?, 'IN_PROGRESS', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    key, requestHash
            );
        } catch (DuplicateKeyException e) {
            // Already seen: check if identical body and if finished or in progress
            return handleExistingKey(key, requestHash);
        }

        // Execute action
        try {
            ResponseEntity<?> response = execution.get();
            String responseBodyJson = null;
            if (response.getBody() != null) {
                if (response.getBody() instanceof String s) {
                    responseBodyJson = s;
                } else {
                    responseBodyJson = objectMapper.writeValueAsString(response.getBody());
                }
            }

            jdbcTemplate.update(
                    "UPDATE idempotency_keys SET status = 'COMPLETED', response_status = ?, response_body = ?, updated_at = CURRENT_TIMESTAMP " +
                            "WHERE key = ?",
                    response.getStatusCode().value(), responseBodyJson, key
            );

            return response;
        } catch (Exception ex) {
            // If it failed before completion, delete key or keep state depending on exception
            if (!(ex instanceof ApiException)) {
                jdbcTemplate.update("DELETE FROM idempotency_keys WHERE key = ?", key);
            }
            throw ex;
        }
    }

    private ResponseEntity<?> handleExistingKey(String key, String expectedHash) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 2500) {
            var rows = jdbcTemplate.queryForList(
                    "SELECT request_hash, status, response_status, response_body FROM idempotency_keys WHERE key = ?",
                    key
            );

            if (rows.isEmpty()) {
                throw ApiException.idempotencyConflict("Idempotency conflict");
            }

            Map<String, Object> row = rows.get(0);
            String existingHash = (String) row.get("request_hash");
            if (!expectedHash.equals(existingHash)) {
                throw ApiException.idempotencyConflict("Idempotency key reused with different request body");
            }

            String status = (String) row.get("status");
            if ("COMPLETED".equals(status)) {
                int code = (Integer) row.get("response_status");
                String cachedBody = (String) row.get("response_body");
                return ResponseEntity.status(code)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(cachedBody);
            }

            // Still IN_PROGRESS, wait briefly
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        throw ApiException.requestInProgress();
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
