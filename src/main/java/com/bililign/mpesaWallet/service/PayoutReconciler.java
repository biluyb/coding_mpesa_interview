package com.bililign.mpesaWallet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

@Component
@EnableScheduling
public class PayoutReconciler {

    private final JdbcTemplate jdbcTemplate;
    private final PayoutService payoutService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public PayoutReconciler(JdbcTemplate jdbcTemplate, PayoutService payoutService, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.payoutService = payoutService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 2000)
    public void reconcilePendingPayouts() {
        // Find payouts that have been pending for > 5 seconds
        // Use SKIP LOCKED so multiple instances or runs do not duplicate work
        List<String> pendingIds = jdbcTemplate.query(
                "SELECT id FROM payouts WHERE status = 'PENDING' AND updated_at < (CURRENT_TIMESTAMP - INTERVAL '5 SECOND') " +
                        "LIMIT 10 FOR UPDATE SKIP LOCKED",
                (rs, rowNum) -> rs.getString("id")
        );

        for (String payoutId : pendingIds) {
            checkBankStatus(payoutId);
        }
    }

    private void checkBankStatus(String payoutId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:9090/bank/payouts/" + payoutId))
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode json = objectMapper.readTree(response.body());
                String bankStatus = json.path("status").asText();

                if ("SUCCEEDED".equalsIgnoreCase(bankStatus)) {
                    payoutService.finalizeSuccess(payoutId, null);
                } else if ("FAILED".equalsIgnoreCase(bankStatus) || "REJECTED".equalsIgnoreCase(bankStatus)) {
                    payoutService.finalizeFailed(payoutId);
                } else if ("NOT_FOUND".equalsIgnoreCase(bankStatus)) {
                    // Check how old it is
                    var rows = jdbcTemplate.queryForList(
                            "SELECT created_at FROM payouts WHERE id = ?", payoutId
                    );
                    if (!rows.isEmpty()) {
                        java.sql.Timestamp created = (java.sql.Timestamp) rows.get(0).get("created_at");
                        if (System.currentTimeMillis() - created.getTime() > 20000) {
                            // Over 20s old and bank never saw it -> failed
                            payoutService.finalizeFailed(payoutId);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Will retry on next schedule
        }
    }
}
