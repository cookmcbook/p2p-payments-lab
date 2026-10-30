-- Chapter 3: double-entry ledger (ADR 0005). Balances are SUM(posting.amount_minor) per account; nothing else.

-- CUSTOMER balances may never go negative (enforced by LedgerService under a row lock, ADR 0006).
-- The single FUNDING account is where simulated money enters: its balance is minus the total ever issued.
ALTER TABLE account
    ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'CUSTOMER' CHECK (kind IN ('CUSTOMER', 'FUNDING'));

INSERT INTO account (id, display_name, currency, created_at, kind)
VALUES ('00000000-0000-0000-0000-00000000f00d', 'Simulated funding source', 'USD', now(), 'FUNDING');

CREATE TABLE journal_entry (
    id         UUID PRIMARY KEY,
    kind       VARCHAR(16) NOT NULL CHECK (kind IN ('FUNDING', 'TRANSFER')),
    created_at TIMESTAMPTZ NOT NULL
);

-- Signed amounts: negative takes money out of the account, positive puts it in.
CREATE TABLE posting (
    id           UUID PRIMARY KEY,
    entry_id     UUID       NOT NULL REFERENCES journal_entry (id),
    account_id   UUID       NOT NULL REFERENCES account (id),
    amount_minor BIGINT     NOT NULL CHECK (amount_minor <> 0),
    currency     VARCHAR(3) NOT NULL CHECK (currency = 'USD')
);
CREATE INDEX posting_account_id_idx ON posting (account_id);
CREATE INDEX posting_entry_id_idx ON posting (entry_id);

-- Append-only: a mistake is corrected by posting a new, reversing entry, never by editing history.
CREATE FUNCTION ledger_reject_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger is append-only: % on % is not allowed', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END $$;

CREATE TRIGGER journal_entry_append_only BEFORE UPDATE OR DELETE OR TRUNCATE ON journal_entry
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_change();
CREATE TRIGGER posting_append_only BEFORE UPDATE OR DELETE OR TRUNCATE ON posting
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_change();

-- Every entry has at least two postings that sum to zero. The rows of one entry arrive one INSERT at a
-- time, so the check is DEFERRED to COMMIT, when the whole entry is visible.
CREATE FUNCTION ledger_check_entry_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    entry UUID;
    total NUMERIC;
    lines INTEGER;
BEGIN
    IF TG_TABLE_NAME = 'posting' THEN entry := NEW.entry_id; ELSE entry := NEW.id; END IF;
    SELECT COALESCE(SUM(amount_minor), 0), count(*) INTO total, lines FROM posting WHERE entry_id = entry;
    IF total <> 0 OR lines < 2 THEN
        RAISE EXCEPTION 'journal entry % is unbalanced: % postings summing to %', entry, lines, total
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER journal_entry_balanced AFTER INSERT ON journal_entry
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger_check_entry_balanced();
CREATE CONSTRAINT TRIGGER posting_entry_balanced AFTER INSERT ON posting
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger_check_entry_balanced();
