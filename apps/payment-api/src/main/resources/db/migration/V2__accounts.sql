-- Chapter 2: accounts. Balances are deliberately NOT stored here; Chapter 3 derives them from ledger postings.
CREATE TABLE account (
    id           UUID PRIMARY KEY,
    display_name VARCHAR(80) NOT NULL CHECK (char_length(btrim(display_name)) > 0),
    currency     VARCHAR(3)  NOT NULL CHECK (currency = 'USD'),
    created_at   TIMESTAMPTZ NOT NULL
);
