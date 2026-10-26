package com.bililign.mpesaWallet.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

public class StatementEntryDto {
    private String entry_id;
    private String type;
    private long amount;
    private long balance_after;
    private String reference;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    private Instant created_at;

    public StatementEntryDto() {}

    public StatementEntryDto(String entry_id, String type, long amount, long balance_after, String reference, Instant created_at) {
        this.entry_id = entry_id;
        this.type = type;
        this.amount = amount;
        this.balance_after = balance_after;
        this.reference = reference;
        this.created_at = created_at;
    }

    public String getEntry_id() { return entry_id; }
    public void setEntry_id(String entry_id) { this.entry_id = entry_id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public long getBalance_after() { return balance_after; }
    public void setBalance_after(long balance_after) { this.balance_after = balance_after; }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    public Instant getCreated_at() { return created_at; }
    public void setCreated_at(Instant created_at) { this.created_at = created_at; }
}
