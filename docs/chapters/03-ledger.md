# Chapter 3 — A ledger that can't lose a cent, even under a crowd

The interactive version, with tracers, the race playground and a quiz, is `docs/book/index.html#ch-03`. Every log line and number below was recorded from the running service (or its tests) on 30 October 2026; most are abridged, and say so.

## Learning objectives

By the end of this chapter you can:

- explain double-entry bookkeeping, and why "every entry sums to zero" means money is never created or destroyed;
- say what atomicity guarantees and which code provides it;
- reproduce a race condition that overdraws an account, and explain why no single-row or single-entry constraint catches it;
- fix it with a row lock, and say why the lock must come *before* the balance read;
- explain the deadlock that `FOR UPDATE` causes here, and why `FOR NO KEY UPDATE` avoids it;
- describe where 1,000 simultaneous requests queue, and what limits throughput.

## Terminology

- **Journal entry**: one atomic ledger movement, stored in `journal_entry`. It is a group of two or more postings that sum to zero. Kinds are `FUNDING` and `TRANSFER`.
- **Posting**: one line of an entry, a signed amount for one account (`posting.amount_minor`). Negative takes money out, positive puts it in. In this project a **debit** is a negative posting and a **credit** a positive one.
- **Double-entry bookkeeping**: recording every movement as postings that sum to zero, so what leaves one account always arrives in another.
- **Funding account**: the single seeded account `00000000-0000-0000-0000-00000000f00d` (kind `FUNDING`) that simulated money comes from. It may go negative; its balance is minus the total ever issued.
- **Atomicity**: all of a transaction's writes become visible together at `COMMIT`, or none do.
- **Isolation level / READ COMMITTED**: how much concurrent transactions see of each other. PostgreSQL's default, READ COMMITTED, gives each *statement* a fresh snapshot of committed data.
- **MVCC snapshot**: the set of transactions a statement treats as committed. Rows written by anyone else are invisible to it.
- **Check-then-act**: read a value, decide, then write based on the read. It is unsafe under concurrency unless something stops the value changing in between.
- **Race condition**: a bug whose outcome depends on the timing of concurrent operations.
- **Row lock**: `SELECT … FOR UPDATE / NO KEY UPDATE / SHARE / KEY SHARE`. It is held until the transaction ends, and a conflicting request waits.
- **Deadlock**: transactions waiting for each other in a cycle. PostgreSQL aborts one after `deadlock_timeout` (1 s) with SQLSTATE `40P01`.
- **Trigger / deferred constraint trigger**: a function PostgreSQL runs on a write. *Deferred* (`DEFERRABLE INITIALLY DEFERRED`) means it runs at `COMMIT` instead of after each statement.
- **Hot account**: an account debited so often that its row lock becomes the bottleneck.
- **Spring profile**: a named switch (`lab`). Beans annotated `@Profile("lab")` exist only when it is active.

## Conceptual model

```
journal_entry  (FUNDING)                        posting
  e1 ───────────────────────────────►  funding source   -1000
                                      Alice            +1000      sum = 0
journal_entry  (TRANSFER)
  e2 ───────────────────────────────►  Alice             -300
                                      Bob               +300      sum = 0

balance(Alice) = SUM(postings for Alice) = 700        SUM(all postings) = 0, always
```

Money enters only through the funding account, as an ordinary balanced entry. Because every entry sums to zero, the whole ledger sums to zero. That gives a one-line audit:

```
p2p_lab=# SELECT SUM(amount_minor) AS whole_ledger, count(*) AS postings FROM posting;
 whole_ledger | postings
--------------+----------
            0 |     3322
```

## Design tradeoffs

| Decision | Chosen | Rejected because |
| --- | --- | --- |
| Amount representation | One signed `BIGINT` column | Debit and credit columns make every query remember a sign convention. Signed amounts make "balanced" a plain `SUM = 0` ([ADR 0005](../adr/0005-double-entry-ledger.md)) |
| Where money comes from | A seeded `FUNDING` account | A seed balance or an "admin deposit" creates money from nothing |
| Balance storage | None: `SUM` per account, indexed | A stored balance is a second source of truth (ADR 0002). It may come back in Chapter 8 as a checkable cache |
| Invariant enforcement | Java *and* database triggers | Java alone can be bypassed by psql, scripts and future services |
| Overdraft protection | `SELECT … FOR NO KEY UPDATE` on the debited account, then read the balance ([ADR 0006](../adr/0006-ledger-concurrency-control.md)) | SERIALIZABLE needs retry loops everywhere. Optimistic versioning needs a stored balance. JVM locks fail with two app instances |
| Lock mode | `FOR NO KEY UPDATE` | `FOR UPDATE` deadlocks with the foreign key's `FOR KEY SHARE` (reproduced) |
| Credits | Not locked | A credit can't make a balance negative. Locking it would queue payments to popular accounts |
| How readers move money | Lab-only endpoints behind the `lab` profile | Test-only code can't be hit by a load script. The real payment API, with auth and idempotency, is Chapters 4–5 |

## Annotated code paths

| File | Role |
| --- | --- |
| `db/migration/V3__ledger.sql` | `account.kind`, the funding account row, the `journal_entry` and `posting` tables, append-only triggers, and the deferred "entry balances" constraint triggers |
| `money/Money.java` | `plus` (`Math.addExact`, currency check), `negate` (`Math.negateExact`), `isNegative` |
| `ledger/domain/JournalEntry.java` | Record whose constructor refuses fewer than two postings, a repeated account, mixed currencies, and a non-zero total. `move(...)` builds the two-posting entry |
| `ledger/domain/Posting.java` | Signed amount for one account; never zero; `isDebit()` |
| `ledger/domain/LedgerAccount.java` | `checkDebit(balance, posting)` refuses to take a customer below zero; `FUNDING_SOURCE_ID` |
| `ledger/domain/LedgerRepository.java` | Port: `find`, `lockForDebit`, `balance`, `append` |
| `ledger/application/LedgerService.java` | `fund`, `transfer`, `balance`. `post()` visits postings in account-id order, locks and checks debits, checks that credited accounts exist, then appends |
| `ledger/persistence/JpaLedgerRepository.java` | Native SQL for `FOR NO KEY UPDATE` and `SUM`; JPA `persist` for entries and postings |
| `ledger/api/LabLedgerController.java` | `POST /lab/ledger/funding` and `/lab/ledger/transfers`, only with `@Profile("lab")`; logs a WARN at startup |
| `ledger/api/LedgerExceptionHandler.java` | 409 insufficient funds, 404 unknown account, 400 rule violation |
| `account/application/AccountService.java` | `balance()` now calls `LedgerService.balance()` (joins the same transaction) |
| `infra/compose.lab.yaml`, `infra/lab/race.mjs` | Lab overlay, and a burst-and-verify load script |

The two lines that matter most, in `LedgerService.post`:

```java
LedgerAccount account = ledger.lockForDebit(id).orElseThrow(() -> new UnknownLedgerAccountException(id));
// Read the balance only AFTER taking the lock ...
account.checkDebit(ledger.balance(id, account.currency()), posting);
```

Swap them and the race returns. The exercises ask you to try it.

## Request walkthrough: `POST /lab/ledger/transfers` (Alice → Bob, 300)

The log was recorded with `infra/compose.lab.yaml` and `infra/compose.debug.yaml`, and abridged:

```
DispatcherServlet     : POST "/lab/ledger/transfers"
                        Mapped to LabLedgerController#transfer(TransferRequest)
JpaTransactionManager : Creating new transaction ... LedgerService.transfer: PROPAGATION_REQUIRED,ISOLATION_DEFAULT
SQL : SELECT currency, kind FROM account WHERE id = ?                       <- Bob (credit: exists?)
SQL : SELECT currency, kind FROM account WHERE id = ? FOR NO KEY UPDATE     <- Alice (debit: lock)
SQL : SELECT COALESCE(SUM(amount_minor), 0) FROM posting WHERE account_id = ?   <- Alice, AFTER the lock
LedgerService : journal entry posted entryId=71d2728d-... kind=TRANSFER postings=2
JpaTransactionManager : Initiating transaction commit
SQL : insert into journal_entry (created_at,kind,id) values (?,?,?)
SQL : insert into posting (...) values (?,?,?,?,?)                          <- Alice -300
SQL : insert into posting (...) values (?,?,?,?,?)                          <- Bob +300
DispatcherServlet     : Completed 201 CREATED
```

1. Bean Validation checks the request (`@Positive`, `@Max(100_000_000)`, `@Pattern("USD")`). A zero amount is a 400 before any transaction starts.
2. The `LedgerService` proxy begins a READ COMMITTED transaction.
3. `JournalEntry.move` builds Alice −300 / Bob +300. The constructor proves it sums to zero.
4. Postings are visited in `UUID.compareTo` order. That order is *signed*, so `e367…` (Bob) sorts before `31e0…` (Alice). Bob is only checked to exist. Alice is locked with `FOR NO KEY UPDATE`, and then her balance is read.
5. `checkDebit`: 1000 − 300 ≥ 0. Allowed.
6. `persist` queues three INSERTs. The log line is written *before* commit, the same caveat as in Chapter 2.
7. At commit Hibernate flushes the INSERTs. Each posting's foreign key takes `FOR KEY SHARE` on its account row. The deferred trigger sums the entry (zero), and everything becomes visible at once. Alice's lock is released at the same instant.

**Lock wait, recorded.** psql held Alice's lock for 6 s. The API's debit logged `FOR NO KEY UPDATE` at 03:52:25.120 and the following `SUM` at 03:52:29.284, and curl measured `time_total=4.184627s`. While it waited, `pg_stat_activity` showed:

```
  pid  | state  | wait_event_type |  wait_event   | query
 37497 | active | Lock            | transactionid | SELECT currency, kind FROM account WHERE id = $1 FOR NO KEY
 37593 | active | Timeout         | PgSleep       | SELECT pg_sleep(6);
```

## The race, recorded

These runs come from `LedgerConcurrencyIntegrationTest`. The first uses plain SQL with no lock, with a barrier forcing both reads to happen before either write:

```
naive transfer read balance=1000
naive transfer read balance=1000
naive transfer inserted a $10.00 debit
naive transfer inserted a $10.00 debit
after two naive $10.00 transfers from a $10.00 balance: alice=-1000 bob=2000
```

The real service, with the lock removed in a throwaway copy:

```
50 concurrent $1.00 transfers from a $10.00 balance: completed=12 insufficient=38 alice=-200 bob=1200
```

The real service as committed:

```
50 concurrent $1.00 transfers from a $10.00 balance: completed=10 insufficient=40 alice=0 bob=1000
```

`FOR UPDATE` instead of `FOR NO KEY UPDATE`, in a throwaway copy, with opposite-direction transfers:

```
ERROR: deadlock detected
  Detail: Process 71 waits for ShareLock on transaction 806; blocked by process 64.
  Process 64 waits for AccessShareLock on tuple (0,9) of relation 16399 of database 16384; blocked by process 66.
  Process 66 waits for ShareLock on transaction 807; blocked by process 62.
  Process 62 waits for AccessShareLock on tuple (0,8) of relation 16399 of database 16384; blocked by process 71.
  Where: while locking tuple (0,8) in relation "account"
```

## Many requests at once

`node infra/lab/race.mjs --requests 1000 --amount 1 --fund 500`, with the lab overlay only:

```
Status codes: 201 × 500, 409 × 500
Latency ms: min 425, median 1439, p95 1640, max 1660
Throughput: 587 requests/s over 1705 ms
Connection pool (9 samples): at most 10 connections in use, at most 189 requests waiting for one
Balances: A $0.00, B $5.00, A + B $5.00
Invariants hold: no negative balance, and no money created or destroyed.
```

The queues, narrowest last:

1. Tomcat's 200 worker threads. About 800 requests wait in front of them.
2. HikariCP's 10 connections. 189 waiting, plus 10 in use, is roughly 200 threads.
3. Alice's row lock, 1 at a time. Alice is a hot account, and this queue sets throughput.

Spreading the same load across many senders would move the bottleneck to the connection pool. Chapter 8 measures this properly.

## Failure scenarios

| Scenario | Result | Handled by |
| --- | --- | --- |
| Transfer more than the balance | 409, nothing written | `LedgerAccount.checkDebit` |
| Two debits of one account at once | Second waits, then reads the new balance | `FOR NO KEY UPDATE` + read after lock |
| A→B and B→A at once | Both proceed | `NO KEY` lock mode; sorted lock order |
| Same account on both sides | 400 "Invalid journal entry" | `JournalEntry` constructor |
| Zero, negative or > $1,000,000 | 400 before any transaction | `@Positive`, `@Max` |
| Unknown account | 404 | `UnknownLedgerAccountException` |
| Unbalanced entry via raw SQL | `ERROR: journal entry … is unbalanced` at COMMIT | Deferred constraint trigger |
| `UPDATE`/`DELETE`/`TRUNCATE` of ledger rows | `ERROR: ledger is append-only` | Statement triggers |
| Crash between posting INSERTs | Neither visible | Transaction atomicity |
| A lock holder stalls | Other debits of that account wait indefinitely | **Not yet**: exercise 6 (`lock_timeout`) |
| Client retries after a timeout | **Money moves twice** | **Not yet**: idempotency, Chapter 4 |
| Lab profile enabled outside a laptop | Anyone can move simulated money | Only `compose.lab.yaml` enables it; removed by Chapter 5 |

## Run the experiment

```bash
docker compose -f compose.yaml -f infra/compose.lab.yaml up --build
node infra/lab/race.mjs
node infra/lab/race.mjs --opposite
node infra/lab/race.mjs --requests 1000 --amount 1 --fund 500
docker compose exec postgres psql -U p2p_lab -d p2p_lab -c "SELECT SUM(amount_minor) FROM posting;"
```

Add `-f infra/compose.debug.yaml` to see the SQL. It logs display names, so use it only locally. Tests:

```bash
cd apps/payment-api && mvn verify
```

Expected: `Tests run: 51, Failures: 0, Errors: 0` and `BUILD SUCCESS`. To run Maven in a container, see Chapter 2.

## Exercises (with expected observations)

1. **Run `race.mjs` with the defaults.** Expect exactly `201 × 10, 409 × 90`. Anything above 10 means the lock is broken.
2. **Move `ledger.balance(...)` above `ledger.lockForDebit(...)` in `LedgerService.post`, then run `mvn verify`.** Expect `fiftySimultaneousDebitsNeverOverdraw` to fail with more than 10 completed and a negative balance, and `aWaitingDebitReadsTheBalanceCommittedWhileItWaited` to fail. Revert afterwards.
3. **Change `FOR NO KEY UPDATE` to `FOR UPDATE`.** Expect `oppositeDirectionTransfersDoNotDeadlock` to error with `deadlock detected` (40P01). This exact change was run while writing the chapter.
4. **Hold Alice's lock in psql** (`BEGIN; SELECT … FOR NO KEY UPDATE;`), then send money *to* Alice from another terminal. Expect an immediate 201: credits don't wait. Then send money *from* Alice: it waits until you `COMMIT`.
5. **Read the funding account's balance** (`GET /api/v1/accounts/00000000-0000-0000-0000-00000000f00d/balance`). Expect a negative number equal to minus all funding ever posted.
6. **Add a lock timeout.** Use `SET LOCAL lock_timeout = '2s'` per transaction, or HikariCP `connection-init-sql`. Map SQLSTATE `55P03` to 503 with `Retry-After`. Repeat the psql experiment: curl should fail after about 2 s. Is a timed-out transfer safe to retry? (Did anything commit?)
7. **Add `EntryKind.REVERSAL` and `LedgerService.reverse(entryId)`.** It posts the negation of an existing entry. Decide which balance checks apply: a reversal debits the original recipient. Write a new migration for the `CHECK`. Explain why `V3__ledger.sql` must not be edited.

## Review questions

1. Why is `SUM(amount_minor)` over the whole `posting` table always zero? What would have to happen for it not to be?
2. Why must the balance-check trigger be `DEFERRABLE INITIALLY DEFERRED`?
3. Two transactions each read $10.00 and each insert a −$10.00 posting. Which constraint stops it? (None. Explain why.)
4. Why must the balance be read *after* `FOR NO KEY UPDATE`, given READ COMMITTED's per-statement snapshots?
5. Why don't credits take the lock? What is the worst outcome of a debit reading a balance that misses a concurrent credit?
6. Which lock did the code never ask for, yet caused the `FOR UPDATE` deadlock?
7. With 1,000 simultaneous debits of one account, which of the three queues sets throughput? What changes if the debits are spread over 1,000 accounts?
8. The lab endpoints have no authentication and no idempotency. Which later chapters remove each risk, and what keeps them off in the meantime?
