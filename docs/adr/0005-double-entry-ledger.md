# ADR 0005 — Double-entry ledger with signed postings, a funding account, and database-enforced history

**Status:** Accepted (Chapter 3)

## Context

ADR 0002 decided that balances are derived from postings and that money must enter through an explicit funding account, never be created. Chapter 3 has to make those postings real. It must guarantee three things even when application code is wrong or bypassed:

1. a transfer can't create or destroy money: what leaves one account arrives in another, in the same atomic step;
2. history can't be rewritten: a posted amount is never edited or deleted;
3. a customer can't spend money they don't have.

Rules 1 and 2 involve one entry at a time and can be checked by the database. Rule 3 depends on every other transaction touching the account, which makes it a concurrency problem; ADR 0006 covers it.

Readers also need a way to move simulated money through the running service to observe these rules. The real payment API, with authentication and idempotency, isn't due until Chapters 4–5.

## Decision

1. **Schema (`V3__ledger.sql`).**
   - `journal_entry` (id, kind `FUNDING`/`TRANSFER`, created_at) groups postings.
   - `posting` (id, entry_id, account_id, `amount_minor BIGINT` non-zero, currency `USD`) stores **signed** amounts: negative takes money out of an account, positive puts it in.
   - A balance is `SUM(amount_minor)` for the account. There is still no balance column.
   - No free-text description is stored, so no personal data enters the ledger.
2. **Funding account.**
   - V3 adds `account.kind` (`CUSTOMER` by default, or `FUNDING`) and seeds a single `FUNDING` account, `00000000-0000-0000-0000-00000000f00d`, named "Simulated funding source".
   - Funding a customer is an ordinary balanced entry: funding −X, customer +X.
   - The funding account may go negative. Its balance is minus the total ever issued, so the sum over the whole ledger is always zero.
3. **Invariants are enforced twice.**
   - **In Java:** a `JournalEntry` record can't be constructed unless it has at least two postings, sums to zero (`Math.addExact`, so overflow throws), uses one currency, and names each account once.
   - **In PostgreSQL:** a `DEFERRABLE INITIALLY DEFERRED` constraint trigger checks the same balance rule at `COMMIT`. It has to be deferred because an entry's rows arrive one `INSERT` at a time.
   - **Append-only:** statement-level triggers reject `UPDATE`, `DELETE` and `TRUNCATE` on both tables. A mistake is corrected by posting a reversing entry.
4. **Atomicity.** `LedgerService.fund` and `transfer` are single `@Transactional` methods. The checks, the entry and both postings commit together or not at all.
5. **Lab-only endpoints.**
   - `POST /lab/ledger/funding` and `POST /lab/ledger/transfers` exist only when the `lab` Spring profile is active.
   - `infra/compose.lab.yaml` turns the profile on, and the controller logs a WARN at startup when it is active.
   - Responses: 201 on success, 409 for insufficient funds, 404 for an unknown account, 400 for a rule violation.
   - Chapter 4's payment API replaces these endpoints, and they must be gone before authentication lands in Chapter 5.

## Alternatives

- **Separate debit and credit columns, or a debit/credit flag with unsigned amounts.** This matches accounting textbooks, but every query then has to remember the sign convention, and "sums to zero" becomes "debits equal credits". Signed amounts make the invariant a plain `SUM = 0`. The chapter explains how signed amounts map to debits and credits.
- **A stored balance column updated with each posting.** Reads are faster. But it is a second source of truth (ADR 0002), and every writer must update it correctly. Revisit with measurements in Chapter 8; the outcome would be a cache that can be checked against the postings.
- **A seed balance, or an "admin deposit" that inserts a single positive posting.** It creates money from nothing and breaks rule 1. The database trigger would reject it anyway.
- **Enforcing only in Java.** Scripts, migrations and future services can bypass Java. Chapter 2 already relies on database `CHECK` constraints for the same reason.
- **Lab endpoints in test code only.** They would be safer, but readers couldn't fire real concurrent requests at the running service, which is the point of the chapter's experiments.

## Consequences

- No code path can create or destroy money without failing at commit. This holds for raw SQL inside a transaction too, unless someone disables triggers, which needs table-owner rights.
- `SELECT SUM(amount_minor) FROM posting` must always return 0, and the race script checks this on every run.
- Balances cost a `SUM` over the account's postings, indexed by `posting_account_id_idx`. That's fine at lab scale. Chapter 8 measures it, and may add snapshots or a cached balance.
- The lab controller ships in the JAR, one environment variable (`SPRING_PROFILES_ACTIVE=lab`) away from being enabled. This is the trade-off ADR 0004 avoided for debug logging. It is accepted temporarily because the money is simulated and the endpoints are removed by Chapter 5, and the startup WARN makes accidental use visible.
- The funding account is visible through `GET /api/v1/accounts/{id}`, and its balance is negative. That is the intended way to see how much money has been issued.
