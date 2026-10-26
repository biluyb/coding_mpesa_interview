-- Schema for M-PESA Wallet Ledger

CREATE TABLE IF NOT EXISTS wallets (
    id VARCHAR(32) PRIMARY KEY,
    status VARCHAR(16) NOT NULL,
    balance BIGINT NOT NULL CHECK (balance >= 0),
    reserved BIGINT NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    is_merchant BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_balance_ge_reserved CHECK (balance >= reserved)
);

CREATE TABLE IF NOT EXISTS idempotency_keys (
    key VARCHAR(64) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL, -- 'IN_PROGRESS', 'COMPLETED'
    response_status INTEGER,
    response_body TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS transfers (
    id VARCHAR(64) PRIMARY KEY,
    idempotency_key VARCHAR(64) UNIQUE,
    from_wallet VARCHAR(32) NOT NULL REFERENCES wallets(id),
    to_wallet VARCHAR(32) NOT NULL REFERENCES wallets(id),
    amount BIGINT NOT NULL CHECK (amount > 0),
    reference VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL, -- 'COMPLETED', 'REVERSED'
    from_available_after BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS reversals (
    id VARCHAR(64) PRIMARY KEY,
    transfer_id VARCHAR(64) NOT NULL UNIQUE REFERENCES transfers(id),
    reason VARCHAR(255),
    amount BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS payouts (
    id VARCHAR(64) PRIMARY KEY,
    idempotency_key VARCHAR(64) UNIQUE,
    wallet_id VARCHAR(32) NOT NULL REFERENCES wallets(id),
    bank_account VARCHAR(64) NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    status VARCHAR(20) NOT NULL, -- 'PENDING', 'SUCCEEDED', 'FAILED'
    bank_ref VARCHAR(64),
    conflict BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS statement_entries (
    entry_id VARCHAR(64) PRIMARY KEY,
    seq BIGSERIAL NOT NULL UNIQUE,
    wallet_id VARCHAR(32) NOT NULL REFERENCES wallets(id),
    type VARCHAR(32) NOT NULL,
    amount BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    reference VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_statement_wallet_seq ON statement_entries(wallet_id, seq DESC);
CREATE INDEX IF NOT EXISTS idx_payouts_pending ON payouts(status, updated_at);
