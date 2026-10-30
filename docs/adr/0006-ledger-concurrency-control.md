# ADR 0006 — Serialize debits per account with SELECT … FOR NO KEY UPDATE, then read the balance

**Status:** Accepted (Chapter 3)

## Context

"A customer can't spend money they don't have" is a check-then-act rule: read the balance, then insert a debit only if the balance covers it. PostgreSQL's default isolation level is READ COMMITTED. Under it, two transactions can both read the same balance before either one inserts. Both checks pass, and the account goes negative.

No single-row constraint can catch this. Each entry is balanced on its own; the problem is only visible across the two transactions together. We reproduced both outcomes against PostgreSQL 16 in `LedgerConcurrencyIntegrationTest`:

- **Plain SQL with a forced interleaving.** Two transactions each read $10.00, and each spends $10.00. The result is −$10.00, with no database error.
- **The real service with the lock removed.** Of 50 simultaneous $1.00 transfers from a $10.00 balance, 12 succeeded, and the balance ended at −$2.00.

## Decision

1. Before checking a debit, `LedgerService` locks the debited account's `account` row:

   ```sql
   SELECT currency, kind FROM account WHERE id = ? FOR NO KEY UPDATE
   ```

   The lock is held until the transaction commits or rolls back. Debits of the same account therefore run one at a time; debits of different accounts still run in parallel.
2. The balance is read **after** the lock is granted. Under READ COMMITTED each statement takes a fresh snapshot, so this read includes every debit that committed while we waited.
3. When an entry debits several accounts, they are locked in ascending id order. Two entries can then never wait for each other in a cycle.
4. Credits don't take the lock. A credit can only increase a balance, so a debit that reads a slightly old balance only errs on the safe side.
5. **`FOR NO KEY UPDATE`, not `FOR UPDATE`.** Inserting a `posting` makes PostgreSQL take a `FOR KEY SHARE` lock on the referenced `account` row for the foreign key check. `FOR UPDATE` conflicts with that lock; `FOR NO KEY UPDATE` doesn't.

   With `FOR UPDATE`, transfers A→B and B→A in parallel deadlock. Each holds its own debit lock and waits for the other's account to insert its credit posting. We reproduced this: PostgreSQL answered `ERROR: deadlock detected … while locking tuple (0,8) in relation "account"` (SQLSTATE 40P01).
6. The isolation level stays at the default, READ COMMITTED.

## Alternatives

- **SERIALIZABLE isolation with retry on SQLSTATE 40001.** It is correct without explicit locks, and PostgreSQL detects the dangerous read-write pattern. But every caller needs a retry loop, failures appear only under load, and long read-only queries can cause aborts. It is worth revisiting if the rules come to span many rows.
- **Optimistic locking: a stored balance with a version column.** Each debit runs `UPDATE … SET balance = ?, version = version + 1 WHERE version = ?`. This needs a stored balance, which ADR 0002 rejects for now. Contended accounts also fail and retry instead of queueing.
- **`UPDATE account SET balance = balance - ? WHERE balance >= ?`.** It is a single atomic statement and very fast. But it needs the stored balance, and the cached balance column, kept in step with postings, would need its own ADR.
- **Advisory locks (`pg_advisory_xact_lock`).** These also serialize per account, but the lock isn't tied to a row, and every writer must remember to take it.
- **Locking in the JVM (`synchronized`, or a per-account `ReentrantLock`).** It only works for one instance of the app. A second replica, or psql, ignores it.

## Consequences

- Concurrent debits of one account queue up in PostgreSQL. The waits show as `wait_event_type = 'Lock'` in `pg_stat_activity`, and `LedgerConcurrencyIntegrationTest` asserts this.
- A **hot account** is a throughput ceiling: at most one debit commits at a time. The funding account is one, since every funding entry debits it, as are popular merchants. Chapter 8 measures this; the options are sharding the account into sub-accounts or batching.
- There is no lock timeout yet. A transaction that holds a lock and then stalls blocks every other debit of that account. A stall like that happens if something slow is called inside the transaction, which is one more reason Chapter 7 calls external systems *outside* transactions. Setting `lock_timeout` or a statement timeout is an exercise in the chapter.
- While waiting, each request still holds a HikariCP connection (10 in total). With 100 simultaneous requests for one account, 10 hold connections, of which 1 works and 9 wait on the lock, and 90 wait for a connection. Chapter 8 measures the effect on latency.
- The tests cover the lock: removing it, or switching to `FOR UPDATE`, makes `LedgerConcurrencyIntegrationTest` fail. Both mutations were run while writing this ADR.
