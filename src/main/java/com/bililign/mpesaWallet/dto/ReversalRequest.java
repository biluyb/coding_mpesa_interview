package com.bililign.mpesaWallet.dto;

public class ReversalRequest {
    private String reason;

    public ReversalRequest() {}

    public ReversalRequest(String reason) {
        this.reason = reason;
    }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
