# ADR 0008 — Payment lifecycle in three committed steps, with holds that separate available from ledger balance

**Status:** Accepted (Chapter 4)

## Context

Chapter 3's lab transfer moved money in one transaction and answered 201 or 409. That's enough for a ledger, but not for a payment API:

1. **A client can't tell "refused" from "unknown".** A timeout or a dropped connection leaves the client not knowing whether money moved. A payment must be a record the client can look up, with a state that says how far it got.
2. **"Accepted" and "settled" are different moments.** Real rails (Chapter 7) accept a payment long before it settles. Even internally, the money a payment is about to move must not be spendable by anything else in between.
3. **AGENTS.md requires explicit states:** accepted, processing, completed, rejected and indeterminate. It also forbids automatically retrying an ambiguous external transfer.

## Decision

1. **A `payment` row is the source of truth for a request's outcome.** `V4__payments.sql` stores payer, payee, amount, `status`, `rejection_reason`, `hold_id` and `journal_entry_id`. `CHECK` constraints tie each state to the facts it implies: `REJECTED` ⇔ a reason; `COMPLETED` ⇔ a journal entry; anything but `REJECTED` ⇒ a hold. `Payment` (domain) enforces the same rules and the allowed transitions in `PaymentStatus.canMoveTo`.
2. **States and transitions.**
   - `ACCEPTED → PROCESSING → COMPLETED`.
   - `REJECTED` is chosen at acceptance (insufficient available funds). Nothing is held or moved.
   - `PROCESSING → INDETERMINATE → COMPLETED | REJECTED` is defined and unit-tested, but no Chapter 4 code path reaches `INDETERMINATE`. Internal capture is one atomic database transaction, so its outcome is never unknown. Chapter 7's mock bank adapter introduces the external call that can be. Only reconciliation will resolve it, never an automatic retry.
3. **Three transactions per payment, run in order by `PaymentService.pay`** (deliberately not `@Transactional` itself). The steps live in a separate bean, `PaymentSteps`, because a self-call would bypass the transaction proxy.
   1. `accept`: claim the idempotency key (ADR 0009), then `LedgerService.placeHold`. Commit `ACCEPTED`, or `REJECTED` with `INSUFFICIENT_FUNDS`.
   2. `startProcessing`: `SELECT … FOR UPDATE` on the payment, then `ACCEPTED → PROCESSING`. Commit.
   3. `complete`: lock the payment, then `LedgerService.captureHold`, which marks the hold `CAPTURED` and posts a `PAYMENT` journal entry. Then `PROCESSING → COMPLETED`. All of that commits together.

   Each committed state is real and resumable. If the process dies after step 1 or 2, a retry with the same idempotency key finds the payment and runs the remaining steps. The row lock in steps 2 and 3 makes a concurrent retry wait and then see the finished state. If step 2 or 3 throws, `pay` logs a warning and returns the last committed state, with HTTP 202.
4. **Holds live in the ledger module** (`ledger_hold`). A hold is `ACTIVE` until it is `CAPTURED` (pointing at its journal entry, `UNIQUE`) or `RELEASED`. **Available = SUM(postings) − SUM(ACTIVE holds).** The ledger balance doesn't change until capture.
5. **Holds race like debits, so they take the debit lock.**
   - `placeHold` runs `SELECT … FOR NO KEY UPDATE` on the account, then reads the available balance (ADR 0006).
   - Every debit now checks *available*, not ledger, so a direct `LedgerService.transfer` can't spend held money.
   - Capture marks the hold `CAPTURED` *before* its own debit check, so the hold isn't counted twice. The hold's foreign key to `journal_entry` is `DEFERRABLE INITIALLY DEFERRED` because the entry's rows are flushed after that `UPDATE`.
6. **Insufficient funds is an outcome, not an error.**
   - `placeHold` returns `Optional.empty()` rather than throwing. An exception leaving that proxied method would mark the caller's whole transaction rollback-only, and the `REJECTED` payment (and its key) could not be stored.
   - The API answers **201** for any newly final payment, `COMPLETED` or `REJECTED`, and the body's `status` says which. It answers 202 for an unfinished one. Validation failures (400), unknown accounts (404) and a reused key (422) store nothing.
7. **Payments are only between two different customer accounts.** The funding account may go negative; a payment from it would let any caller mint money. Until Chapter 5 the payer comes from the `X-Payer-Account-Id` header and is not authenticated.
8. **The lab transfer endpoint is removed.** `POST /lab/ledger/funding` remains (lab profile only), because there's still no other way to issue simulated money. `LedgerService.transfer` stays as an internal method for Chapter 3's tests.

## Alternatives

- **One transaction for the whole payment.** Simpler, and with an internal ledger it is just as safe. But no intermediate state would ever be committed, so there's nothing to resume and nothing that maps onto Chapter 7, where an external call must sit *between* transactions. Holding a transaction open across an external call would hold the account lock for the call's duration (ADR 0006).
- **Background processing:** `POST` returns 202 and a scheduled worker claims payments with `FOR UPDATE SKIP LOCKED`. It's realistic, and holds would be visible for longer. Rejected for this chapter because it adds a scheduler, polling clients and asynchronous tests before they are needed. Reconsider with Kafka in Chapter 6.
- **No `PROCESSING` state for internal payments.** Internally it's never ambiguous, so it could be skipped. It is kept so the lifecycle is the same shape before and after Chapter 7, and so a crash between steps leaves an honest record.
- **Insufficient funds as 409 with nothing stored** (as in Chapter 3). A retry after a deposit would then succeed with the same key, so one key would mean two different outcomes over time. Storing `REJECTED` makes the answer stable.
- **402 or 422 for a rejected payment.** Clients often treat any non-2xx as "nothing happened, retry". A stored rejection *is* something that happened, and the resource exists.
- **Holds as postings to a "held funds" sub-account.** It's the classic accounting form and keeps one table. But every hold and release becomes a pair of journal entries, the funding account's role gets murkier, and the append-only ledger fills with reservation noise. Revisit if holds must be reported as ledger lines.

## Consequences

- `GET /api/v1/accounts/{id}/balance` can now show `availableMinor < ledgerMinor`, while a payment sits in `ACCEPTED` or `PROCESSING`.
- A hold is released only by capture today. If a client never retries a payment stuck in `ACCEPTED`, its money stays held. No Chapter 4 path needs `RELEASED`. Chapter 7 adds release on external failure, and a sweeper for stale holds.
- Each payment costs three short transactions and five row locks (payment ×2, account ×2, hold ×1), measured in Chapter 8.
- `ledger_hold` rows are updated once. Unlike postings, they aren't append-only; the ledger's append-only guarantee still covers every amount that actually moved.
- `LedgerConcurrencyIntegrationTest` (Chapter 3) still passes unchanged. `PaymentConcurrencyIntegrationTest` adds: 50 payments from $10 → exactly 10 completed; a waiting hold sees the hold committed while it waited (`pg_stat_activity` shows the wait). Removing the lock from `placeHold`, or checking debits against the ledger balance instead of available, makes a test fail. Both mutations were run while writing this ADR.
