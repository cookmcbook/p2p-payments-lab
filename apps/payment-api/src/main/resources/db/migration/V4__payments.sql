-- Chapter 4: payments, holds and idempotency keys (ADR 0008, ADR 0009).

-- A hold reserves money for an in-flight payment. available = SUM(postings) - SUM(ACTIVE holds).
-- Unlike postings, a hold changes state once: ACTIVE -> CAPTURED (its journal entry was posted) or RELEASED.
-- It is placed only while the account row is locked FOR NO KEY UPDATE, like a debit (ADR 0006).
CREATE TABLE ledger_hold (
    id               UUID PRIMARY KEY,
    account_id       UUID        NOT NULL REFERENCES account (id),
    amount_minor     BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency         VARCHAR(3)  NOT NULL CHECK (currency = 'USD'),
    status           VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'CAPTURED', 'RELEASED')),
    -- Capture marks the hold before the entry's INSERTs are flushed, so this reference is checked at COMMIT.
    journal_entry_id UUID UNIQUE REFERENCES journal_entry (id) DEFERRABLE INITIALLY DEFERRED,
    created_at       TIMESTAMPTZ NOT NULL,
    closed_at        TIMESTAMPTZ,
    -- CAPTURED <=> it points at the entry that moved the money; ACTIVE <=> not closed yet.
    CHECK ((status = 'CAPTURED') = (journal_entry_id IS NOT NULL)),
    CHECK ((status = 'ACTIVE') = (closed_at IS NULL))
);
-- Only active holds are summed, so index only those.
CREATE INDEX ledger_hold_active_idx ON ledger_hold (account_id) WHERE status = 'ACTIVE';

CREATE TABLE payment (
    id                UUID PRIMARY KEY,
    payer_account_id  UUID        NOT NULL REFERENCES account (id),
    payee_account_id  UUID        NOT NULL REFERENCES account (id),
    amount_minor      BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency          VARCHAR(3)  NOT NULL CHECK (currency = 'USD'),
    status            VARCHAR(16) NOT NULL
        CHECK (status IN ('ACCEPTED', 'PROCESSING', 'COMPLETED', 'REJECTED', 'INDETERMINATE')),
    rejection_reason  VARCHAR(32),
    hold_id           UUID UNIQUE REFERENCES ledger_hold (id),
    journal_entry_id  UUID UNIQUE REFERENCES journal_entry (id),
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    CHECK (payer_account_id <> payee_account_id),
    -- Each state carries exactly the facts that state implies.
    CHECK ((status = 'REJECTED') = (rejection_reason IS NOT NULL)),
    CHECK ((status = 'COMPLETED') = (journal_entry_id IS NOT NULL)),
    CHECK (status = 'REJECTED' OR hold_id IS NOT NULL)
);

-- One row per (payer, key): the primary key is what makes two concurrent retries produce one payment.
-- The payment is inserted after the key in the same transaction, so the foreign key is checked at COMMIT.
CREATE TABLE idempotency_key (
    payer_account_id    UUID        NOT NULL REFERENCES account (id),
    idempotency_key     VARCHAR(64) NOT NULL,
    request_fingerprint CHAR(64)    NOT NULL,
    payment_id          UUID        NOT NULL REFERENCES payment (id) DEFERRABLE INITIALLY DEFERRED,
    created_at          TIMESTAMPTZ NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (payer_account_id, idempotency_key),
    CHECK (expires_at > created_at)
);
CREATE INDEX idempotency_key_expires_at_idx ON idempotency_key (expires_at);

ALTER TABLE journal_entry DROP CONSTRAINT journal_entry_kind_check;
ALTER TABLE journal_entry ADD CONSTRAINT journal_entry_kind_check CHECK (kind IN ('FUNDING', 'TRANSFER', 'PAYMENT'));
