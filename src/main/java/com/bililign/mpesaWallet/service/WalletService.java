package com.bililign.mpesaWallet.service;

import com.bililign.mpesaWallet.dto.WalletResponse;
import com.bililign.mpesaWallet.dto.WalletStatusResponse;
import com.bililign.mpesaWallet.exception.ApiException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

@Service
public class WalletService {

    private final JdbcTemplate jdbcTemplate;

    public WalletService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public WalletResponse getWallet(String id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, status, balance, reserved FROM wallets WHERE id = ?",
                    (rs, rowNum) -> {
                        long balance = rs.getLong("balance");
                        long reserved = rs.getLong("reserved");
                        long available = balance - reserved;
                        return new WalletResponse(
                                rs.getString("id"),
                                rs.getString("status"),
                                balance,
                                reserved,
                                available
                        );
                    },
                    id
            );
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.walletNotFound(id);
        }
    }

    @Transactional
    public WalletStatusResponse updateStatus(String id, String newStatus) {
        // Query current state with lock to prevent race condition
        var rows = jdbcTemplate.queryForList(
                "SELECT status FROM wallets WHERE id = ? FOR UPDATE",
                id
        );
        if (rows.isEmpty()) {
            throw ApiException.walletNotFound(id);
        }

        Instant now = Instant.now();
        jdbcTemplate.update(
                "UPDATE wallets SET status = ?, updated_at = ? WHERE id = ?",
                newStatus, Timestamp.from(now), id
        );

        return new WalletStatusResponse(id, newStatus, now);
    }
}
