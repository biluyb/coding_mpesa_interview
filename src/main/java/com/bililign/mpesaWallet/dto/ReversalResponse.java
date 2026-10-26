package com.bililign.mpesaWallet.dto;

public class ReversalResponse {
    private String transfer_id;
    private String reversal_id;
    private String status;
    private long amount;

    public ReversalResponse() {}

    public ReversalResponse(String transfer_id, String reversal_id, String status, long amount) {
        this.transfer_id = transfer_id;
        this.reversal_id = reversal_id;
        this.status = status;
        this.amount = amount;
    }

    public String getTransfer_id() { return transfer_id; }
    public void setTransfer_id(String transfer_id) { this.transfer_id = transfer_id; }

    public String getReversal_id() { return reversal_id; }
    public void setReversal_id(String reversal_id) { this.reversal_id = reversal_id; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
}
