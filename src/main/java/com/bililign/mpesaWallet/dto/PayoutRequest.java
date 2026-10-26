package com.bililign.mpesaWallet.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class PayoutRequest {
    @NotBlank(message = "wallet_id is required")
    private String wallet_id;

    @NotBlank(message = "bank_account is required")
    private String bank_account;

    @NotNull(message = "amount is required")
    @Min(value = 1, message = "amount must be at least 1")
    @Max(value = 5000000, message = "amount must not exceed 5,000,000")
    private Long amount;

    public PayoutRequest() {}

    public PayoutRequest(String wallet_id, String bank_account, Long amount) {
        this.wallet_id = wallet_id;
        this.bank_account = bank_account;
        this.amount = amount;
    }

    public String getWallet_id() { return wallet_id; }
    public void setWallet_id(String wallet_id) { this.wallet_id = wallet_id; }

    public String getBank_account() { return bank_account; }
    public void setBank_account(String bank_account) { this.bank_account = bank_account; }

    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
}
