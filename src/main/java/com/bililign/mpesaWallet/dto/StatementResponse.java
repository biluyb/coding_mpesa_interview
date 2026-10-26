package com.bililign.mpesaWallet.dto;

import java.util.List;

public class StatementResponse {
    private String wallet_id;
    private List<StatementEntryDto> entries;
    private String next_cursor;

    public StatementResponse() {}

    public StatementResponse(String wallet_id, List<StatementEntryDto> entries, String next_cursor) {
        this.wallet_id = wallet_id;
        this.entries = entries;
        this.next_cursor = next_cursor;
    }

    public String getWallet_id() { return wallet_id; }
    public void setWallet_id(String wallet_id) { this.wallet_id = wallet_id; }

    public List<StatementEntryDto> getEntries() { return entries; }
    public void setEntries(List<StatementEntryDto> entries) { this.entries = entries; }

    public String getNext_cursor() { return next_cursor; }
    public void setNext_cursor(String next_cursor) { this.next_cursor = next_cursor; }
}
