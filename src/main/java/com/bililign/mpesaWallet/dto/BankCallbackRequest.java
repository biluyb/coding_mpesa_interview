package com.bililign.mpesaWallet.dto;

import jakarta.validation.constraints.NotBlank;

public class BankCallbackRequest {
    @NotBlank
    private String event_id;

    @NotBlank
    private String payout_id;

    private String bank_ref;

    @NotBlank
    private String status; // SUCCEEDED or FAILED

    public BankCallbackRequest() {}

    public BankCallbackRequest(String event_id, String payout_id, String bank_ref, String status) {
        this.event_id = event_id;
        this.payout_id = payout_id;
        this.bank_ref = bank_ref;
        this.status = status;
    }

    public String getEvent_id() { return event_id; }
    public void setEvent_id(String event_id) { this.event_id = event_id; }

    public String getPayout_id() { return payout_id; }
    public void setPayout_id(String payout_id) { this.payout_id = payout_id; }

    public String getBank_ref() { return bank_ref; }
    public void setBank_ref(String bank_ref) { this.bank_ref = bank_ref; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
