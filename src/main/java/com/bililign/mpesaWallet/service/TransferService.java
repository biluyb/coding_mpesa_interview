package com.bililign.mpesaWallet.service;

import com.bililign.mpesaWallet.dto.ReversalRequest;
import com.bililign.mpesaWallet.dto.ReversalResponse;
import com.bililign.mpesaWallet.dto.TransferRequest;
import com.bililign.mpesaWallet.dto.TransferResponse;
import com.bililign.mpesaWallet.exception.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TransferService {

    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong transferSeq = new AtomicLong(System.currentTimeMillis() % 1000000);
    private final AtomicLong reversalSeq = new AtomicLong(System.currentTimeMillis() % 100000);

    public TransferService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static class WalletRow {
        String id;
        String status;
        long balance;
        long reserved;

        long available() {
            return balance - reserved;
        }
    }

    @Transactional
    public TransferResponse executeTransfer(TransferRequest request, String idempotencyKey) {
        String from = request.getFrom_wallet();
        String to = request.getTo_wallet();
        long amount = request.getAmount();

        if (from.equals(to)) {
            throw ApiException.sameWallet();
        }

        // Strict alphabetical lock ordering to prevent ABBA deadlocks (Rule for S1.3)
        String first = from.compareTo(to) < 0 ? from : to;
        String second = from.compareTo(to) < 0 ? to : from;

        WalletRow firstWallet = lockWallet(first);
        WalletRow secondWallet = lockWallet(second);

        if (firstWallet == null) throw ApiException.walletNotFound(first);
        if (secondWallet == null) throw ApiException.walletNotFound(second);

        WalletRow fromWallet = from.equals(first) ? firstWallet : secondWallet;
        WalletRow toWallet = to.equals(first) ? firstWallet : secondWallet;

        if (!"ACTIVE".equals(fromWallet.status)) {
            throw ApiException.walletNotActive(from);
        }
        if (!"ACTIVE".equals(toWallet.status)) {
            throw ApiException.walletNotActive(to);
        }

        if (fromWallet.available() < amount) {
            throw ApiException.insufficientFunds(from, fromWallet.available(), amount);
        }

        // Update balances
        Instant now = Instant.now();
        Timestamp ts = Timestamp.from(now);

        jdbcTemplate.update("UPDATE wallets SET balance = balance - ?, updated_at = ? WHERE id = ?", amount, ts, from);
        jdbcTemplate.update("UPDATE wallets SET balance = balance + ?, updated_at = ? WHERE id = ?", amount, ts, to);

        long fromAvailableAfter = fromWallet.available() - amount;
        long fromBalanceAfter = fromWallet.balance - amount;
        long toBalanceAfter = toWallet.balance + amount;

        String transferId = "T-" + transferSeq.incrementAndGet();

        jdbcTemplate.update(
                "INSERT INTO transfers (id, idempotency_key, from_wallet, to_wallet, amount, reference, status, from_available_after, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 'COMPLETED', ?, ?)",
                transferId, idempotencyKey, from, to, amount, request.getReference(), fromAvailableAfter, ts
        );

        // Record statement entries
        jdbcTemplate.update(
                "INSERT INTO statement_entries (entry_id, wallet_id, type, amount, balance_after, reference, created_at) " +
                        "VALUES (?, ?, 'TRANSFER_OUT', ?, ?, ?, ?)",
                "E-" + UUID.randomUUID().toString().substring(0, 8), from, -amount, fromBalanceAfter, transferId, ts
        );
        jdbcTemplate.update(
                "INSERT INTO statement_entries (entry_id, wallet_id, type, amount, balance_after, reference, created_at) " +
                        "VALUES (?, ?, 'TRANSFER_IN', ?, ?, ?, ?)",
                "E-" + UUID.randomUUID().toString().substring(0, 8), to, amount, toBalanceAfter, transferId, ts
        );

        return new TransferResponse(transferId, "COMPLETED", fromAvailableAfter, now);
    }

    @Transactional
    public ReversalResponse executeReversal(String transferId, ReversalRequest request) {
        // Lock transfer record to ensure exact-once reversal
        var transferRows = jdbcTemplate.queryForList(
                "SELECT id, from_wallet, to_wallet, amount, status FROM transfers WHERE id = ? FOR UPDATE",
                transferId
        );
        if (transferRows.isEmpty()) {
            throw ApiException.transferNotFound(transferId);
        }

        Map<String, Object> transfer = transferRows.get(0);
        String currentStatus = (String) transfer.get("status");
        if ("REVERSED".equals(currentStatus)) {
            throw ApiException.alreadyReversed(transferId);
        }
        if (!"COMPLETED".equals(currentStatus)) {
            throw ApiException.notReversible(transferId);
        }

        String origFrom = (String) transfer.get("from_wallet");
        String origTo = (String) transfer.get("to_wallet");
        long amount = ((Number) transfer.get("amount")).longValue();

        // In reversal: origTo is DEBITED, origFrom is CREDITED
        String first = origFrom.compareTo(origTo) < 0 ? origFrom : origTo;
        String second = origFrom.compareTo(origTo) < 0 ? origTo : origFrom;

        WalletRow firstWallet = lockWallet(first);
        WalletRow secondWallet = lockWallet(second);

        if (firstWallet == null) throw ApiException.walletNotFound(first);
        if (secondWallet == null) throw ApiException.walletNotFound(second);

        WalletRow debitedWallet = origTo.equals(first) ? firstWallet : secondWallet;
        WalletRow creditedWallet = origFrom.equals(first) ? firstWallet : secondWallet;

        // "The debited wallet must be ACTIVE and have enough available. The credited wallet may be FROZEN."
        if (!"ACTIVE".equals(debitedWallet.status)) {
            throw ApiException.walletNotActive(origTo);
        }
        if (debitedWallet.available() < amount) {
            throw ApiException.insufficientFunds(origTo, debitedWallet.available(), amount);
        }

        Instant now = Instant.now();
        Timestamp ts = Timestamp.from(now);

        // Debit original to_wallet, credit original from_wallet
        jdbcTemplate.update("UPDATE wallets SET balance = balance - ?, updated_at = ? WHERE id = ?", amount, ts, origTo);
        jdbcTemplate.update("UPDATE wallets SET balance = balance + ?, updated_at = ? WHERE id = ?", amount, ts, origFrom);

        String reversalId = "R-" + reversalSeq.incrementAndGet();

        try {
            jdbcTemplate.update(
                    "INSERT INTO reversals (id, transfer_id, reason, amount, created_at) VALUES (?, ?, ?, ?, ?)",
                    reversalId, transferId, request != null ? request.getReason() : null, amount, ts
            );
        } catch (DuplicateKeyException e) {
            throw ApiException.alreadyReversed(transferId);
        }

        jdbcTemplate.update("UPDATE transfers SET status = 'REVERSED' WHERE id = ?", transferId);

        // Statement entries
        jdbcTemplate.update(
                "INSERT INTO statement_entries (entry_id, wallet_id, type, amount, balance_after, reference, created_at) " +
                        "VALUES (?, ?, 'REVERSAL_OUT', ?, ?, ?, ?)",
                "E-" + UUID.randomUUID().toString().substring(0, 8), origTo, -amount, debitedWallet.balance - amount, reversalId, ts
        );
        jdbcTemplate.update(
                "INSERT INTO statement_entries (entry_id, wallet_id, type, amount, balance_after, reference, created_at) " +
                        "VALUES (?, ?, 'REVERSAL_IN', ?, ?, ?, ?)",
                "E-" + UUID.randomUUID().toString().substring(0, 8), origFrom, amount, creditedWallet.balance + amount, reversalId, ts
        );

        return new ReversalResponse(transferId, reversalId, "REVERSED", amount);
    }

    private WalletRow lockWallet(String id) {
        var rows = jdbcTemplate.query(
                "SELECT id, status, balance, reserved FROM wallets WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> {
                    WalletRow w = new WalletRow();
                    w.id = rs.getString("id");
                    w.status = rs.getString("status");
                    w.balance = rs.getLong("balance");
                    w.reserved = rs.getLong("reserved");
                    return w;
                },
                id
        );
        return rows.isEmpty() ? null : rows.get(0);
    }
}
