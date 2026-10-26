package com.bililign.mpesaWallet.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

public class PayoutDetailsResponse {
    private String payout_id;
    private String wallet_id;
    private long amount;
    private String status;
    private String bank_ref;
    private boolean conflict;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    private Instant updated_at;

    public PayoutDetailsResponse() {}

    public PayoutDetailsResponse(String payout_id, String wallet_id, long amount, String status, String bank_ref, boolean conflict, Instant updated_at) {
        this.payout_id = payout_id;
        this.wallet_id = wallet_id;
        this.amount = amount;
        this.status = status;
        this.bank_ref = bank_ref;
        this.conflict = conflict;
        this.updated_at = updated_at;
    }

    public String getPayout_id() { return payout_id; }
    public void setPayout_id(String payout_id) { this.payout_id = payout_id; }

    public String getWallet_id() { return wallet_id; }
    public void setWallet_id(String wallet_id) { this.wallet_id = wallet_id; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getBank_ref() { return bank_ref; }
    public void setBank_ref(String bank_ref) { this.bank_ref = bank_ref; }

    public boolean isConflict() { return conflict; }
    public void setConflict(boolean conflict) { this.conflict = conflict; }

    public Instant getUpdated_at() { return updated_at; }
    public void setUpdated_at(Instant updated_at) { this.updated_at = updated_at; }
}
