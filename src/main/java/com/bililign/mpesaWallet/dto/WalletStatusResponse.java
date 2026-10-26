package com.bililign.mpesaWallet.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

public class WalletStatusResponse {
    private String wallet_id;
    private String status;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    private Instant changed_at;

    public WalletStatusResponse() {}

    public WalletStatusResponse(String wallet_id, String status, Instant changed_at) {
        this.wallet_id = wallet_id;
        this.status = status;
        this.changed_at = changed_at;
    }

    public String getWallet_id() { return wallet_id; }
    public void setWallet_id(String wallet_id) { this.wallet_id = wallet_id; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getChanged_at() { return changed_at; }
    public void setChanged_at(Instant changed_at) { this.changed_at = changed_at; }
}
