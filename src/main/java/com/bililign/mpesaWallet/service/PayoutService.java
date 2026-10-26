package com.bililign.mpesaWallet.service;

import com.bililign.mpesaWallet.dto.PayoutCreateResponse;
import com.bililign.mpesaWallet.dto.PayoutDetailsResponse;
import com.bililign.mpesaWallet.dto.PayoutRequest;
import com.bililign.mpesaWallet.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class PayoutService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService bankDispatchExecutor = Executors.newFixedThreadPool(30);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public PayoutService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PayoutCreateResponse createPayout(PayoutRequest request, String idempotencyKey) {
        String walletId = request.getWallet_id();
        long amount = request.getAmount();

        // 1. Lock wallet row briefly to verify funds and reserve
        var rows = jdbcTemplate.queryForList(
                "SELECT id, status, balance, reserved FROM wallets WHERE id = ? FOR UPDATE",
                walletId
        );
        if (rows.isEmpty()) {
            throw ApiException.walletNotFound(walletId);
        }

        Map<String, Object> wallet = rows.get(0);
        String status = (String) wallet.get("status");
        long balance = ((Number) wallet.get("balance")).longValue();
        long reserved = ((Number) wallet.get("reserved")).longValue();
        long available = balance - reserved;

        if (!"ACTIVE".equals(status)) {
            throw ApiException.walletNotActive(walletId);
        }
        if (available < amount) {
            throw ApiException.insufficientFunds(walletId, available, amount);
        }

        // 2. Reserve funds (available drops, balance does not)
        Instant now = Instant.now();
        Timestamp ts = Timestamp.from(now);

        jdbcTemplate.update(
                "UPDATE wallets SET reserved = reserved + ?, updated_at = ? WHERE id = ?",
                amount, ts, walletId
        );

        String payoutId = "P-" + UUID.randomUUID().toString().substring(0, 8);

        jdbcTemplate.update(
                "INSERT INTO payouts (id, idempotency_key, wallet_id, bank_account, amount, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)",
                payoutId, idempotencyKey, walletId, request.getBank_account(), amount, ts, ts
        );

        // 3. Return 202 ACCEPTED immediately without waiting for bank!
        // Dispatch call to bank asynchronously on a separate thread pool so NO database connections or tomcat threads are held!
        dispatchToBankAsync(payoutId, request.getBank_account(), amount);

        return new PayoutCreateResponse(payoutId, "PENDING");
    }

    private void dispatchToBankAsync(String payoutId, String bankAccount, long amount) {
        bankDispatchExecutor.submit(() -> {
            try {
                String payload = String.format(
                        "{\"payout_id\":\"%s\",\"bank_account\":\"%s\",\"amount\":%d}",
                        payoutId, bankAccount, amount
                );

                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:9090/bank/payouts"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(8)) // 8-second cutoff per spec
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();

                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonNode json = objectMapper.readTree(response.body());
                    String result = json.path("result").asText();
                    if ("ACCEPTED".equalsIgnoreCase(result)) {
                        String bankRef = json.path("bank_ref").asText(null);
                        if (bankRef != null) {
                            jdbcTemplate.update("UPDATE payouts SET bank_ref = ? WHERE id = ? AND bank_ref IS NULL", bankRef, payoutId);
                        }
                    } else if ("REJECTED".equalsIgnoreCase(result)) {
                        finalizeFailed(payoutId);
                    }
                }
                // If 504 or other status: outcome unknown, do nothing. Reconciler handles it.
            } catch (Exception ignored) {
                // Network timeout or exception: outcome unknown, reconciler handles it.
            }
        });
    }

    @Transactional
    public void finalizeSuccess(String payoutId, String bankRef) {
        var rows = jdbcTemplate.queryForList(
                "SELECT id, wallet_id, amount, status, bank_ref, conflict FROM payouts WHERE id = ? FOR UPDATE",
                payoutId
        );
        if (rows.isEmpty()) {
            throw ApiException.payoutNotFound(payoutId);
        }

        Map<String, Object> payout = rows.get(0);
        String currentStatus = (String) payout.get("status");

        if ("SUCCEEDED".equals(currentStatus)) {
            return; // Idempotent
        }

        if ("FAILED".equals(currentStatus)) {
            // Contradiction: callback says SUCCEEDED but was already finalized as FAILED
            jdbcTemplate.update("UPDATE payouts SET conflict = true, updated_at = CURRENT_TIMESTAMP WHERE id = ?", payoutId);
            return;
        }

        // PENDING -> SUCCEEDED
        String walletId = (String) payout.get("wallet_id");
        long amount = ((Number) payout.get("amount")).longValue();

        var walletRows = jdbcTemplate.queryForList(
                "SELECT balance, reserved FROM wallets WHERE id = ? FOR UPDATE",
                walletId
        );
        long balance = ((Number) walletRows.get(0).get("balance")).longValue();

        Instant now = Instant.now();
        Timestamp ts = Timestamp.from(now);

        // Lower balance and release reservation
        jdbcTemplate.update(
                "UPDATE wallets SET balance = balance - ?, reserved = reserved - ?, updated_at = ? WHERE id = ?",
                amount, amount, ts, walletId
        );

        jdbcTemplate.update(
                "UPDATE payouts SET status = 'SUCCEEDED', bank_ref = COALESCE(?, bank_ref), updated_at = ? WHERE id = ?",
                bankRef, ts, payoutId
        );

        // Record statement entry
        jdbcTemplate.update(
                "INSERT INTO statement_entries (entry_id, wallet_id, type, amount, balance_after, reference, created_at) " +
                        "VALUES (?, ?, 'PAYOUT', ?, ?, ?, ?)",
                "E-" + UUID.randomUUID().toString().substring(0, 8), walletId, -amount, balance - amount, payoutId, ts
        );
    }

    @Transactional
    public void finalizeFailed(String payoutId) {
        var rows = jdbcTemplate.queryForList(
                "SELECT id, wallet_id, amount, status, conflict FROM payouts WHERE id = ? FOR UPDATE",
                payoutId
        );
        if (rows.isEmpty()) {
            throw ApiException.payoutNotFound(payoutId);
        }

        Map<String, Object> payout = rows.get(0);
        String currentStatus = (String) payout.get("status");

        if ("FAILED".equals(currentStatus)) {
            return; // Idempotent
        }

        if ("SUCCEEDED".equals(currentStatus)) {
            // Contradiction
            jdbcTemplate.update("UPDATE payouts SET conflict = true, updated_at = CURRENT_TIMESTAMP WHERE id = ?", payoutId);
            return;
        }

        // PENDING -> FAILED: release reservation only
        String walletId = (String) payout.get("wallet_id");
        long amount = ((Number) payout.get("amount")).longValue();

        Instant now = Instant.now();
        Timestamp ts = Timestamp.from(now);

        jdbcTemplate.update(
                "UPDATE wallets SET reserved = reserved - ?, updated_at = ? WHERE id = ?",
                amount, ts, walletId
        );

        jdbcTemplate.update(
                "UPDATE payouts SET status = 'FAILED', updated_at = ? WHERE id = ?",
                ts, payoutId
        );
    }

    public PayoutDetailsResponse getPayout(String payoutId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, wallet_id, amount, status, bank_ref, conflict, updated_at FROM payouts WHERE id = ?",
                    (rs, rowNum) -> new PayoutDetailsResponse(
                            rs.getString("id"),
                            rs.getString("wallet_id"),
                            rs.getLong("amount"),
                            rs.getString("status"),
                            rs.getString("bank_ref"),
                            rs.getBoolean("conflict"),
                            rs.getTimestamp("updated_at").toInstant()
                    ),
                    payoutId
            );
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.payoutNotFound(payoutId);
        }
    }
}
