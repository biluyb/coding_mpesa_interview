package com.bililign.mpesaWallet.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

public class TransferResponse {
    private String transfer_id;
    private String status;
    private long from_available_after;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    private Instant created_at;

    public TransferResponse() {}

    public TransferResponse(String transfer_id, String status, long from_available_after, Instant created_at) {
        this.transfer_id = transfer_id;
        this.status = status;
        this.from_available_after = from_available_after;
        this.created_at = created_at;
    }

    public String getTransfer_id() { return transfer_id; }
    public void setTransfer_id(String transfer_id) { this.transfer_id = transfer_id; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getFrom_available_after() { return from_available_after; }
    public void setFrom_available_after(long from_available_after) { this.from_available_after = from_available_after; }

    public Instant getCreated_at() { return created_at; }
    public void setCreated_at(Instant created_at) { this.created_at = created_at; }
}
