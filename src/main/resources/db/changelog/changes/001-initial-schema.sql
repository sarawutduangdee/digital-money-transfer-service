-- liquibase formatted sql

-- changeset sarawut:1
CREATE TABLE account (
                         id BIGINT IDENTITY(1,1) NOT NULL,
                         account_number VARCHAR(20) NOT NULL,
                         owner_name NVARCHAR(120) NOT NULL,
                         currency CHAR(3) NOT NULL,
                         balance DECIMAL(19,4) NOT NULL CONSTRAINT DF_account_balance DEFAULT (0),
                         status VARCHAR(10) NOT NULL CONSTRAINT DF_account_status DEFAULT ('ACTIVE'),
                         created_at DATETIME2(3) NOT NULL CONSTRAINT DF_account_created DEFAULT (SYSUTCDATETIME()),
                         updated_at DATETIME2(3) NOT NULL CONSTRAINT DF_account_updated DEFAULT (SYSUTCDATETIME()),
                         CONSTRAINT PK_account PRIMARY KEY (id),
                         CONSTRAINT UQ_account_number UNIQUE (account_number),
                         CONSTRAINT CK_account_balance CHECK (balance >= 0),
                         CONSTRAINT CK_account_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

-- changeset sarawut:2
CREATE TABLE transfer (
                          id BIGINT IDENTITY(1,1) NOT NULL,
                          idempotency_key VARCHAR(64) NOT NULL,
                          from_account_id BIGINT NOT NULL,
                          to_account_id BIGINT NOT NULL,
                          amount DECIMAL(19,4) NOT NULL,
                          currency CHAR(3) NOT NULL,
                          status VARCHAR(12) NOT NULL, -- PENDING / COMPLETED / FAILED
                          request_hash VARCHAR(64) NOT NULL,
                          failure_reason NVARCHAR(255) NULL,
                          created_at DATETIME2(3) NOT NULL CONSTRAINT DF_transfer_created DEFAULT (SYSUTCDATETIME()),
                          CONSTRAINT PK_transfer PRIMARY KEY (id),
                          CONSTRAINT UQ_transfer_idem UNIQUE (idempotency_key),
                          CONSTRAINT FK_transfer_from FOREIGN KEY (from_account_id) REFERENCES account(id),
                          CONSTRAINT FK_transfer_to FOREIGN KEY (to_account_id) REFERENCES account(id),
                          CONSTRAINT CK_transfer_amount CHECK (amount > 0),
                          CONSTRAINT CK_transfer_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

-- changeset sarawut:3
CREATE TABLE ledger_entry (
                              id BIGINT IDENTITY(1,1) NOT NULL,
                              account_id BIGINT NOT NULL,
                              transfer_id BIGINT NULL,
                              entry_type VARCHAR(6) NOT NULL, -- DEBIT / CREDIT
                              amount DECIMAL(19,4) NOT NULL,
                              balance_after DECIMAL(19,4) NOT NULL,
                              created_at DATETIME2(3) NOT NULL CONSTRAINT DF_ledger_created DEFAULT (SYSUTCDATETIME()),
                              CONSTRAINT PK_ledger PRIMARY KEY (id),
                              CONSTRAINT FK_ledger_account FOREIGN KEY (account_id) REFERENCES account(id),
                              CONSTRAINT FK_ledger_transfer FOREIGN KEY (transfer_id) REFERENCES transfer(id),
                              CONSTRAINT CK_ledger_type CHECK (entry_type IN ('DEBIT', 'CREDIT')),
                              CONSTRAINT CK_ledger_amount CHECK (amount > 0)
);

CREATE INDEX IX_ledger_account ON ledger_entry (account_id, created_at DESC);

-- changeset sarawut:4
CREATE TABLE outbox_event (
                              id BIGINT IDENTITY(1,1) NOT NULL,
                              aggregate_type VARCHAR(50) NOT NULL,
                              aggregate_id VARCHAR(50) NOT NULL,
                              event_type VARCHAR(50) NOT NULL,
                              payload NVARCHAR(MAX) NOT NULL,
                              status VARCHAR(10) NOT NULL CONSTRAINT DF_outbox_status DEFAULT ('PENDING'),
                              created_at DATETIME2(3) NOT NULL CONSTRAINT DF_outbox_created DEFAULT (SYSUTCDATETIME()),
                              published_at DATETIME2(3) NULL,
                              CONSTRAINT PK_outbox PRIMARY KEY (id),
                              CONSTRAINT CK_outbox_st CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

-- changeset sarawut:5
-- Seed Data เพื่อให้มีบัญชีไว้ทดสอบตั้งแต่เริ่มรัน
INSERT INTO account (account_number, owner_name, currency, balance, status)
VALUES
    ('0000001001', 'สมชาย ใจดี', 'THB', 5000.00, 'ACTIVE'),
    ('0000002002', 'สมหญิง รักดี', 'THB', 1000.00, 'ACTIVE');

INSERT INTO ledger_entry (account_id, transfer_id, entry_type, amount, balance_after)
VALUES
    (1, NULL, 'CREDIT', 5000.00, 5000.00),
    (2, NULL, 'CREDIT', 1000.00, 1000.00);