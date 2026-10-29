# ADR 0002 — Integer minor units for money; balances are derived, never stored as editable state

**Status:** Accepted (Chapter 2)

## Context

Chapter 2 introduces accounts and a read-only balance view before any ledger exists (Chapter 3). Two decisions must be made now because they shape the schema and the public API: how amounts are represented, and where a balance comes from.

Floating-point types (`double`, `float`, JSON numbers parsed as floats) cannot represent most decimal fractions exactly: `0.1 + 0.2 != 0.3`. Rounding drift in money is a correctness defect, not a cosmetic one.

A mutable `balance` column on `account` is the simplest way to show a balance, but it makes the balance a second source of truth that can disagree with the journal, and any code path that writes it can create or destroy funds.

## Decision

1. Amounts are integers in the currency's **minor unit** (cents for USD): `long` in Java (`Money(amountMinor, currency)`), `BIGINT` in PostgreSQL once amount columns exist (Chapter 3), integer fields suffixed `Minor` in JSON (`ledgerMinor`, `availableMinor`).
2. Currency is explicit on every amount and is restricted to `USD`, enforced in the API (`@Pattern`), domain (`Currency` enum) and database (`CHECK (currency = 'USD')`).
3. The `account` table has **no balance column**. Balances are a read model computed from ledger postings. Until Chapter 3 adds postings, every balance is zero and there is deliberately no way to fund an account.

## Alternatives

- **`BigDecimal` with scale 2 / `NUMERIC(19,2)`:** exact and also permitted by AGENTS.md. Rejected for now because scale must be enforced everywhere (`setScale`, `equals` vs `compareTo` pitfalls), and multi-currency scales differ. Integer minor units make "no fractional cents" a type-level fact. Revisit if interest/FX calculations need sub-minor precision.
- **Stored mutable balance column:** fast reads, but a second source of truth. If a cached balance is needed later for performance it will be updated in the same transaction as postings and reconcilable against them; that would require its own ADR.
- **Temporary "seed balance" field on create:** convenient for demos, but it creates money from nothing, violating the ledger invariant, and would have to be removed.

## Consequences

- JSON clients never parse money as floating point; `12.34 USD` is `1234`.
- `long` overflows at ~9.2 × 10^16 cents (~92 quadrillion USD); arithmetic in Chapter 3 must use `Math.addExact`/`subtractExact` so overflow fails loudly.
- Accounts cannot hold funds in Chapter 2. Exercises that need money must wait for Chapter 3's ledger, which will introduce a funding source as an explicit ledger account so that funds are moved, never created.
