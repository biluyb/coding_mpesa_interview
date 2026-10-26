package com.bililign.mpesaWallet.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/mpesa/internal")
public class ResetController {

    private final JdbcTemplate jdbcTemplate;

    public ResetController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/reset")
    @Transactional
    public ResponseEntity<Map<String, String>> resetDatabase() {
        // Clear all transactional data
        jdbcTemplate.execute("DELETE FROM statement_entries");
        jdbcTemplate.execute("DELETE FROM reversals");
        jdbcTemplate.execute("DELETE FROM transfers");
        jdbcTemplate.execute("DELETE FROM payouts");
        jdbcTemplate.execute("DELETE FROM idempotency_keys");
        jdbcTemplate.execute("DELETE FROM wallets");

        // Seed W001 - W100: ACTIVE, 1,000,000 santim
        List<Object[]> activeWallets = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            String id = String.format("W%03d", i);
            activeWallets.add(new Object[]{id, "ACTIVE", 1_000_000L, 0L, false});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO wallets (id, status, balance, reserved, is_merchant, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                activeWallets
        );

        // Seed W101 - W105: FROZEN, 1,000,000 santim
        List<Object[]> frozenWallets = new ArrayList<>();
        for (int i = 101; i <= 105; i++) {
            String id = String.format("W%03d", i);
            frozenWallets.add(new Object[]{id, "FROZEN", 1_000_000L, 0L, false});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO wallets (id, status, balance, reserved, is_merchant, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                frozenWallets
        );

        // Seed M001: ACTIVE, merchant, 50,000,000 santim
        jdbcTemplate.update(
                "INSERT INTO wallets (id, status, balance, reserved, is_merchant, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                "M001", "ACTIVE", 50_000_000L, 0L, true
        );

        return ResponseEntity.ok(Map.of("status", "RESET_COMPLETED"));
    }
}
