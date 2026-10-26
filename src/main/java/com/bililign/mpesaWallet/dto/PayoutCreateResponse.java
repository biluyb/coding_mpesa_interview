package com.bililign.mpesaWallet.dto;

public class PayoutCreateResponse {
    private String payout_id;
    private String status;

    public PayoutCreateResponse() {}

    public PayoutCreateResponse(String payout_id, String status) {
        this.payout_id = payout_id;
        this.status = status;
    }

    public String getPayout_id() { return payout_id; }
    public void setPayout_id(String payout_id) { this.payout_id = payout_id; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
