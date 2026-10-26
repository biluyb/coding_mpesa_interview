package com.bililign.mpesaWallet.dto;

public class WalletResponse {
    private String wallet_id;
    private String status;
    private long balance;
    private long reserved;
    private long available;

    public WalletResponse() {}

    public WalletResponse(String wallet_id, String status, long balance, long reserved, long available) {
        this.wallet_id = wallet_id;
        this.status = status;
        this.balance = balance;
        this.reserved = reserved;
        this.available = available;
    }

    public String getWallet_id() { return wallet_id; }
    public void setWallet_id(String wallet_id) { this.wallet_id = wallet_id; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getBalance() { return balance; }
    public void setBalance(long balance) { this.balance = balance; }

    public long getReserved() { return reserved; }
    public void setReserved(long reserved) { this.reserved = reserved; }

    public long getAvailable() { return available; }
    public void setAvailable(long available) { this.available = available; }
}
