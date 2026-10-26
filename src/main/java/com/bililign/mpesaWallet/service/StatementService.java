package com.bililign.mpesaWallet.service;

import com.bililign.mpesaWallet.dto.StatementEntryDto;
import com.bililign.mpesaWallet.dto.StatementResponse;
import com.bililign.mpesaWallet.exception.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class StatementService {

    private final JdbcTemplate jdbcTemplate;

    public StatementService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static class EntryWithSeq {
        StatementEntryDto dto;
        long seq;
    }

    public StatementResponse getStatement(String walletId, Integer limit, String cursor) {
        // Validate wallet exists
        var wCheck = jdbcTemplate.queryForList("SELECT id FROM wallets WHERE id = ?", walletId);
        if (wCheck.isEmpty()) {
            throw ApiException.walletNotFound(walletId);
        }

        int pageSize = (limit == null || limit <= 0) ? 50 : Math.min(limit, 200);

        long snapshotMaxSeq;
        long beforeSeq = Long.MAX_VALUE;

        if (cursor == null || cursor.isBlank()) {
            // New traversal: freeze the snapshot at current max seq
            Long max = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(seq), 0) FROM statement_entries WHERE wallet_id = ?",
                    Long.class,
                    walletId
            );
            snapshotMaxSeq = max != null ? max : 0L;
            beforeSeq = snapshotMaxSeq + 1;
        } else {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                String[] parts = decoded.split(":");
                snapshotMaxSeq = Long.parseLong(parts[0]);
                beforeSeq = Long.parseLong(parts[1]);
            } catch (Exception e) {
                throw ApiException.validationFailed("Invalid cursor format");
            }
        }

        List<EntryWithSeq> rows = jdbcTemplate.query(
                "SELECT entry_id, type, amount, balance_after, reference, created_at, seq " +
                        "FROM statement_entries " +
                        "WHERE wallet_id = ? AND seq <= ? AND seq < ? " +
                        "ORDER BY seq DESC LIMIT ?",
                (rs, rowNum) -> {
                    EntryWithSeq e = new EntryWithSeq();
                    e.seq = rs.getLong("seq");
                    e.dto = new StatementEntryDto(
                            rs.getString("entry_id"),
                            rs.getString("type"),
                            rs.getLong("amount"),
                            rs.getLong("balance_after"),
                            rs.getString("reference"),
                            rs.getTimestamp("created_at").toInstant()
                    );
                    return e;
                },
                walletId, snapshotMaxSeq, beforeSeq, pageSize
        );

        List<StatementEntryDto> entries = new ArrayList<>(rows.size());
        for (EntryWithSeq r : rows) {
            entries.add(r.dto);
        }

        String nextCursor = null;
        if (rows.size() == pageSize) {
            long lastSeq = rows.get(rows.size() - 1).seq;
            // Check if there are more entries below lastSeq
            Long remaining = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM statement_entries WHERE wallet_id = ? AND seq <= ? AND seq < ?",
                    Long.class,
                    walletId, snapshotMaxSeq, lastSeq
            );
            if (remaining != null && remaining > 0) {
                String raw = snapshotMaxSeq + ":" + lastSeq;
                nextCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            }
        }

        return new StatementResponse(walletId, entries, nextCursor);
    }
}
