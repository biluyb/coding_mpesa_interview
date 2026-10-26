package com.bililign.mpesaWallet.exception;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String error;

    public ApiException(HttpStatus status, String error, String message) {
        super(message);
        this.status = status;
        this.error = error;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public static ApiException validationFailed(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    public static ApiException walletNotFound(String walletId) {
        return new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet " + walletId + " not found");
    }

    public static ApiException transferNotFound(String transferId) {
        return new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer " + transferId + " not found");
    }

    public static ApiException payoutNotFound(String payoutId) {
        return new ApiException(HttpStatus.NOT_FOUND, "PAYOUT_NOT_FOUND", "Payout " + payoutId + " not found");
    }

    public static ApiException idempotencyConflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", message);
    }

    public static ApiException requestInProgress() {
        return new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "Request is currently in progress");
    }

    public static ApiException alreadyReversed(String transferId) {
        return new ApiException(HttpStatus.CONFLICT, "ALREADY_REVERSED", "Transfer " + transferId + " already reversed");
    }

    public static ApiException sameWallet() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SAME_WALLET", "Source and destination wallets must be different");
    }

    public static ApiException walletNotActive(String walletId) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WALLET_NOT_ACTIVE", "Wallet " + walletId + " is not active");
    }

    public static ApiException insufficientFunds(String walletId, long available, long requested) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
                walletId + " available " + available + ", requested " + requested);
    }

    public static ApiException notReversible(String transferId) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_REVERSIBLE", "Transfer " + transferId + " is not reversible");
    }

    public static ApiException serviceUnavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", message);
    }
}
