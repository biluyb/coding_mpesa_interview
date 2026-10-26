package com.bililign.mpesaWallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class WalletStatusRequest {
    @NotBlank(message = "status is required")
    @Pattern(regexp = "ACTIVE|FROZEN", message = "status must be ACTIVE or FROZEN")
    private String status;

    private String reason;

    public WalletStatusRequest() {}

    public WalletStatusRequest(String status, String reason) {
        this.status = status;
        this.reason = reason;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
