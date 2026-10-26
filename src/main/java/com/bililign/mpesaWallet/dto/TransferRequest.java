package com.bililign.mpesaWallet.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class TransferRequest {
    @NotBlank(message = "from_wallet is required")
    private String from_wallet;

    @NotBlank(message = "to_wallet is required")
    private String to_wallet;

    @NotNull(message = "amount is required")
    @Min(value = 1, message = "amount must be at least 1")
    @Max(value = 5000000, message = "amount must not exceed 5,000,000")
    private Long amount;

    @NotBlank(message = "reference is required")
    @Size(min = 1, max = 64, message = "reference must be 1 to 64 characters")
    private String reference;

    public TransferRequest() {}

    public TransferRequest(String from_wallet, String to_wallet, Long amount, String reference) {
        this.from_wallet = from_wallet;
        this.to_wallet = to_wallet;
        this.amount = amount;
        this.reference = reference;
    }

    public String getFrom_wallet() { return from_wallet; }
    public void setFrom_wallet(String from_wallet) { this.from_wallet = from_wallet; }

    public String getTo_wallet() { return to_wallet; }
    public void setTo_wallet(String to_wallet) { this.to_wallet = to_wallet; }

    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
}
