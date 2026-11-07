# Chapter 4 — A payment you can safely send twice

The interactive version, with a code tour, a tracer of six payment scenarios (including a client that times out and retries) and a quiz, is [`docs/book/ch-04.html`](../book/ch-04.html). Every response, log line and number below was recorded from the running service, or from its tests, on 6 November 2026. Most are abridged (timestamps, package names and ids shortened), and each one says so.

## Learning objectives

By the end of this chapter you can:

- explain why a payment needs a *record* with a *state*, not just "201 or 409";
- name the five payment states, the moves allowed between them, and which state means "don't retry, reconcile";
- explain a hold, and why available balance and ledger balance differ while a payment is in flight;
- say why placing a hold needs the same row lock as a debit;
- explain how an idempotency key turns "retry and maybe pay twice" into "retry and get the original answer", even for 20 retries at once;
- define the retention policy and what it costs a client who retries too late;
- explain why the payment runs as three transactions, and why they are in a separate bean.

## Terminology

- **Payment**: a request to move money from a payer to a payee, stored in the `payment` table with its current **status**. The record exists even when the payment is refused.
- **Payment lifecycle / state machine**: the fixed set of states and the allowed moves between them (`PaymentStatus.canMoveTo`). Any other move is a bug and throws.
- **ACCEPTED**: recorded, money held, nothing moved yet. **PROCESSING**: we've started moving the money. **COMPLETED**: the journal entry is posted. Final. **REJECTED**: refused with a reason; nothing held or moved. Final. **INDETERMINATE**: an *external* system was asked to move money and we don't know whether it did. Only reconciliation resolves it (Chapter 7); no Chapter 4 code reaches it.
- **Final state**: one a payment never leaves (`COMPLETED`, `REJECTED`).
- **Hold**: money reserved on an account for an in-flight payment (`ledger_hold`). It lowers the available balance, not the ledger balance. **Capturing** a hold posts the journal entry that actually moves the money; **releasing** it gives the money back (no Chapter 4 path releases).
- **Ledger balance**: the sum of the account's postings. **Available balance**: ledger balance minus active holds.
- **Idempotent**: doing it twice has the same effect as doing it once.
- **Idempotency key**: a string the *client* chooses for one intended payment and sends as the `Idempotency-Key` header, the same on every retry.
- **Request fingerprint**: a SHA-256 hash of what the request asked for (payee, amount, currency). It detects a key being reused for a *different* payment.
- **Replay**: a request whose key was already used for the same fingerprint. It returns the original payment, marked `Idempotent-Replayed: true`.
- **Retention policy**: how long a key keeps its meaning (24 hours here). After that it may be reused.
- **Rollback-only**: a transaction that Spring has marked so that it can only roll back. It happens when an exception leaves a `@Transactional` method, even one that just joined an outer transaction.
- **Self-invocation**: a method calling another method of the same object (`this.x()`). It bypasses Spring's proxy, so `@Transactional` on the called method has no effect.

## Conceptual model

```
POST /api/v1/payments ──► PaymentService.pay  (not a transaction itself)
                            │
              tx 1: accept  │  claim (payer, key) ── taken? ──► same fingerprint: return original (replay)
                            │                                    different:        422
                            │  placeHold ── not enough? ──► REJECTED (commit, 201)
                            ▼
                         ACCEPTED   ledger 1000, available 700      (commit)
              tx 2          │
                            ▼
                         PROCESSING                                  (commit)
              tx 3          │  capture hold + post PAYMENT entry
                            ▼
                         COMPLETED  ledger 700, available 700        (commit, 201)

  PROCESSING ──(Chapter 7: external call, outcome unknown)──► INDETERMINATE ──reconcile──► COMPLETED | REJECTED
```

Three ideas carry the chapter:

1. **A committed state is a promise you can resume from.** Each step commits before the next starts. If the server dies between steps, the payment row says exactly how far it got, and a retry with the same key finishes the job. Because step 3 captures the hold *and* marks the payment `COMPLETED` in one transaction, a `PROCESSING` payment has never moved money. Resuming it is safe. That stops being true when step 3 calls a bank (Chapter 7), and that is what `INDETERMINATE` is for.
2. **A hold is a debit that hasn't happened yet.** It spends *available* money, so it races exactly like a debit (Chapter 3), and takes the same `FOR NO KEY UPDATE` lock. Every debit now checks available, not ledger, so nothing can spend held money.
3. **The database decides who owns a key.** `INSERT … ON CONFLICT DO NOTHING` on the `(payer, key)` primary key: the first transaction wins. Any concurrent one *waits* for it, then sees the key is taken and returns the winner's payment.

## Design tradeoffs

| Decision | Chosen | Alternative | Why |
| --- | --- | --- | --- |
| How a payment runs | Three transactions in the request ([ADR 0008](../adr/0008-payment-lifecycle-and-holds.md)) | One transaction; or 202 + a background worker | One transaction leaves nothing to resume and doesn't fit Chapter 7's external call. A worker adds scheduling and polling before Chapter 6 needs them |
| Insufficient funds | Stored `REJECTED`, 201 | 409, nothing stored (Chapter 3) | A stored answer is stable: a retry after a deposit must not change what the key means |
| Holds | `ledger_hold` table, in the ledger module | Postings to a "held" sub-account | Simpler queries; the append-only ledger holds only real movements |
| Key scope | `(payer, key)` primary key ([ADR 0009](../adr/0009-idempotency-keys-and-retention.md)) | Global keys | Payers can't collide with, or probe, each other's keys |
| Detecting misuse | SHA-256 fingerprint of the parsed request | Compare raw JSON | Field order and whitespace don't matter; the hash has a fixed size |
| Claiming a key | `INSERT … ON CONFLICT DO NOTHING` | `SELECT`, then `INSERT` if absent | Check-then-insert races; the loser gets a duplicate-key error instead of the original answer (mutation run) |
| Retention | 24 h, expired keys reusable, hourly purge | Forever | Bounded table; retries realistically happen within minutes |
| Who the payer is | `X-Payer-Account-Id` header | Field in the body | Chapter 5 swaps the header for the logged-in user without changing the body or the fingerprint |

## Annotated code paths

| File | Role |
| --- | --- |
| `db/migration/V4__payments.sql` | `ledger_hold`, `payment`, `idempotency_key`; `CHECK`s tying each status to its facts; two deferred foreign keys; `journal_entry.kind` gains `PAYMENT` |
| `payment/api/PaymentController.java` | `POST /api/v1/payments` (headers `X-Payer-Account-Id`, `Idempotency-Key`), `GET /api/v1/payments/{id}`. 201 final, 202 unfinished, `Idempotent-Replayed` header |
| `payment/api/CreatePaymentRequest.java`, `PaymentResponse.java`, `PaymentExceptionHandler.java` | Body validation; JSON shape; 404 / 422 / 400 problem responses |
| `payment/application/PaymentService.java` | `pay`: runs the three steps in order; a failure after step 1 is logged and the last committed state returned. Not `@Transactional` |
| `payment/application/PaymentSteps.java` | `accept`, `startProcessing`, `complete`, each `@Transactional`. A separate bean so each call goes through the proxy |
| `payment/application/IdempotencyKeyPurger.java` | `@Scheduled` hourly delete of expired keys |
| `payment/domain/Payment.java`, `PaymentStatus.java`, `RejectionReason.java` | Immutable payment; allowed transitions; the same state/fact rules as V4's `CHECK`s |
| `payment/domain/RequestFingerprint.java` | `SHA-256("v1|payee|amountMinor|currency")` |
| `payment/persistence/JpaPaymentRepository.java` | `SELECT * FROM payment WHERE id = ? FOR UPDATE`; changes written by Hibernate at commit |
| `payment/persistence/SqlIdempotencyKeyRepository.java` | `INSERT … ON CONFLICT DO NOTHING`, find, delete-if-expired, purge |
| `ledger/application/LedgerService.java` | New `placeHold` (lock, check available, insert; returns empty instead of throwing), `captureHold`, `available`, `account`. Debits now check available |
| `ledger/persistence/JpaLedgerRepository.java` | `held` (sum of active holds), `addHold`, `captureHold` (`SELECT … FOR UPDATE`, then `UPDATE … CAPTURED`) |

Two lines deserve a second look.

`LedgerService.placeHold` returns `Optional.empty()` for insufficient funds instead of throwing. `PaymentSteps.accept` calls it inside its own transaction. If an exception left `placeHold`, Spring's proxy would mark the *shared* transaction rollback-only. `accept` could catch the exception and store a `REJECTED` payment, but the commit would then fail with `UnexpectedRollbackException`. The rejection, and the key, would be lost.

`LedgerService.captureHold` marks the hold `CAPTURED` *before* it posts the entry. The debit check inside `post` subtracts active holds. If the hold were still active, the payment's own hold would be counted against it, and $10 held out of $10 could never be captured.

## Request walkthrough: `POST /api/v1/payments` (Alice → Bob, $3.00)

Recorded with `infra/compose.debug.yaml` and the lab overlay. Abridged: timestamps, thread names and SQL binding lines removed.

```
DispatcherServlet  : POST "/api/v1/payments", parameters={}
JpaTransactionManager : Creating new transaction with name [...PaymentSteps.accept]
SQL : DELETE FROM idempotency_key WHERE payer_account_id = ? AND idempotency_key = ? AND expires_at <= ?
SQL : INSERT INTO idempotency_key (...) VALUES (...) ON CONFLICT (payer_account_id, idempotency_key) DO NOTHING
SQL : SELECT currency, kind FROM account WHERE id = ? FOR NO KEY UPDATE
SQL : SELECT COALESCE(SUM(amount_minor), 0) FROM posting WHERE account_id = ?
SQL : SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_hold WHERE account_id = ? AND status = 'ACTIVE'
SQL : INSERT INTO ledger_hold (id, account_id, amount_minor, currency, status, created_at) VALUES (...)
LedgerService : hold placed holdId=daa0dfec-… amountMinor=300
PaymentSteps  : payment ACCEPTED paymentId=400e41b5-… amountMinor=300
SQL : insert into payment (...) values (...)                         ← flushed at commit of tx 1
JpaTransactionManager : Creating new transaction with name [...PaymentSteps.startProcessing]
SQL : SELECT * FROM payment WHERE id = ? FOR UPDATE
PaymentSteps  : payment PROCESSING paymentId=400e41b5-…
SQL : update payment set journal_entry_id=?,rejection_reason=?,status=?,updated_at=? where id=?
JpaTransactionManager : Creating new transaction with name [...PaymentSteps.complete]
SQL : SELECT * FROM payment WHERE id = ? FOR UPDATE
SQL : SELECT account_id, amount_minor, currency, created_at FROM ledger_hold WHERE id = ? AND status = 'ACTIVE' FOR UPDATE
SQL : UPDATE ledger_hold SET status = 'CAPTURED', journal_entry_id = ?, closed_at = ? WHERE id = ?
SQL : SELECT currency, kind FROM account WHERE id = ? FOR NO KEY UPDATE    ← Alice (payee Bob's check is unlocked)
SQL : SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_hold WHERE ... AND status = 'ACTIVE'   ← 0 now
LedgerService : journal entry posted entryId=2afac93c-… kind=PAYMENT postings=2
PaymentSteps  : payment COMPLETED paymentId=400e41b5-… entryId=2afac93c-…
SQL : insert into journal_entry ... / insert into posting ... ×2 / update payment ...
DispatcherServlet : Completed 201 CREATED
```

The response (recorded, ids shortened):

```
HTTP/1.1 201
Location: /api/v1/payments/400e41b5-…
Idempotent-Replayed: false

{"id":"400e41b5-…","payerAccountId":"…alice","payeeAccountId":"…bob","amountMinor":300,"currency":"USD",
 "status":"COMPLETED","rejectionReason":null,"holdId":"daa0dfec-…","journalEntryId":"2afac93c-…",
 "createdAt":"2026-11-06T23:38:36.676…Z","updatedAt":"2026-11-06T23:38:36.704…Z"}
```

The same request again returns the same body, `Idempotent-Replayed: true`. The log shows only the claim (0 rows), the key lookup and `payment replayed … status=COMPLETED`; no lock, no hold, no entry. The same key with `amountMinor: 400` gets `422`, `"detail":"Idempotency-Key was already used for a different request"`. A $50.00 payment from Alice's $7.00 gets `201` with `"status":"REJECTED","rejectionReason":"INSUFFICIENT_FUNDS","holdId":null`. Without the key header the response is `400`, `"detail":"Required header 'Idempotency-Key' is not present."`. Afterwards Alice's balance is `{"ledgerMinor":700,"availableMinor":700}`.

## The timeout that idempotency exists for

Recorded against the running service. In `psql`, hold Alice's row lock for 4 s (`BEGIN; SELECT … FOR NO KEY UPDATE; SELECT pg_sleep(4); COMMIT;`). Meanwhile, send a $3.00 payment with a client that gives up after 2 s:

```
pay   client gave up: TimeoutError 2017 ms
GET balance (during the lock) {"ledgerMinor":1000,"availableMinor":1000}
                                ... psql commits ...
GET balance (after)           {"ledgerMinor":700,"availableMinor":700}
retry 201 Idempotent-Replayed: true {"id":"750501fd-…","status":"COMPLETED",…}  48 ms
```

The server log (abridged) shows why. The original request waited 2.6 s at the lock, then finished on its own, and answered a client that had already left:

```
23:41:05.476 [exec-7] SELECT currency, kind FROM account WHERE id = ? FOR NO KEY UPDATE   ← waits for psql
23:41:08.111 [exec-7] hold placed … / payment ACCEPTED paymentId=750501fd-…
23:41:08.135 [exec-7] payment COMPLETED paymentId=750501fd-…
23:41:08.144 [exec-7] Completed 201 CREATED                                               ← nobody listening
23:41:08.839 [exec-3] payment replayed paymentId=750501fd-… status=COMPLETED              ← the retry
```

A balance read during the lock wasn't blocked: reads never wait for row locks in PostgreSQL (MVCC). Without the key, the client's only choices were to retry and pay $6.00, or to give up and not know.

## Many retries at once

`PaymentConcurrencyIntegrationTest` and `infra/lab/race.mjs` (recorded):

```
20 simultaneous requests with one key: payments=1 fresh=1 replayed=19 alice=700
50 concurrent $1.00 payments from a $10.00 balance: completed=10 rejected=40 alice=0 bob=1000
while T1 holds the lock: T2 done=false sessions waiting on a lock=1
```

```
$ node infra/lab/race.mjs --requests 100 --same-key
Sending 100 A→B payments of $1.00 at once, all A→B with one Idempotency-Key. A starts with $10.00.
Status codes: 201 × 100
Payments: COMPLETED × 100; 1 distinct, 99 replays
Balances: A $9.00, B $1.00, A + B $10.00
Invariants hold: no negative balance, no money created or destroyed, one key paid once.

$ node infra/lab/race.mjs --requests 100
Payments: COMPLETED × 10, REJECTED × 90; 100 distinct, 0 replays
Balances: A $0.00, B $10.00, A + B $10.00
```

(Abridged: the latency, throughput and pool lines are omitted.) Under the hood, the 19 losers ran `INSERT … ON CONFLICT DO NOTHING` while the winner's transaction was still open. PostgreSQL made each one wait on the primary key, then return 0 rows once the winner committed. Each loser then read the key, found the same fingerprint, and returned the winner's payment.

**Mutations run while writing this chapter:**

- `placeHold` using `find` (no lock) instead of `lockForDebit`: `aWaitingHoldReadsTheHoldCommittedWhileItWaited` fails ("Expecting code to raise a throwable": T2 didn't wait). The 50-payment race happened to pass in that run, which is why the deterministic test exists.
- Debits checking the ledger balance instead of available: `aHoldLowersAvailableButNotLedgerAndAnotherDebitCannotSpendIt` fails, because a direct transfer spent held money.
- Look-up-then-insert instead of `ON CONFLICT DO NOTHING`: the 20-retry test fails with `duplicate key value violates unique constraint "idempotency_key_pkey"`. No double payment (the primary key still held), but 19 clients got errors instead of their payment.

## Failure scenarios

| Scenario | What happens | Where |
| --- | --- | --- |
| Client times out, retries with the same key | Original payment returned (201, replayed) | `PaymentSteps.accept` → `replay` |
| 20 retries arrive at once | One payment; 19 wait on the key's primary key, then replay it | `SqlIdempotencyKeyRepository.claim` |
| Same key, different amount or payee | 422; nothing stored | `IdempotencyKeyReusedException` |
| Same key, different payer | Independent payments: keys are scoped per payer | `idempotency_key` primary key |
| Key used again after 24 h | New payment (by policy) | `deleteIfExpired` |
| Not enough available money | `REJECTED` stored, 201; a replay stays rejected even after funding | `placeHold` → empty |
| Server dies after step 1 or 2 | Payment stays `ACCEPTED`/`PROCESSING`, money held; a retry with the key completes it | `PaymentService.pay` |
| Step 2 or 3 throws (database down) | Warning logged; 202 with the last committed state; retry resumes | `PaymentService.pay` |
| Client never retries a stuck payment | **Its money stays held.** No sweeper yet (Chapter 7) | Limitation |
| Pay yourself / pay or from the funding account | 400; nothing stored, key still free | `PaymentSteps.checkParties` |
| Unknown payer or payee | 404; nothing stored | `LedgerService.account` |
| Two captures of one hold | The second waits on the hold's row lock, then finds it not `ACTIVE` | `captureHold` |
| Someone edits a completed payment in SQL | `CHECK` violation (`payment_check…`) | `V4__payments.sql` |

## Run the experiment

```bash
docker compose -f compose.yaml -f infra/compose.lab.yaml up --build
A=$(curl -s -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' \
     -d '{"displayName":"Alice Example","currency":"USD"}' | jq -r .id)
B=$(curl -s -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' \
     -d '{"displayName":"Bob Example","currency":"USD"}' | jq -r .id)
curl -s -X POST localhost:8080/lab/ledger/funding -H 'Content-Type: application/json' \
     -d "{\"accountId\":\"$A\",\"amountMinor\":1000,\"currency\":\"USD\"}"
curl -i -X POST localhost:8080/api/v1/payments -H 'Content-Type: application/json' \
     -H "X-Payer-Account-Id: $A" -H 'Idempotency-Key: rent-2026-11' \
     -d "{\"payeeAccountId\":\"$B\",\"amountMinor\":300,\"currency\":\"USD\"}"
# run the last command again, then again with "amountMinor":400
curl -s localhost:8080/api/v1/accounts/$A/balance
node infra/lab/race.mjs --requests 100 --same-key
```

Run the tests (Docker must be running): `cd apps/payment-api && mvn verify`. Expected: `Tests run: 83, Failures: 0, Errors: 0` and `BUILD SUCCESS`.

## Exercises (with expected observations)

1. **Predict:** send the same payment request three times with one key. How many journal entries are posted? *One. The second and third responses carry `Idempotent-Replayed: true` and the same `id`.*
2. **Predict:** pay $50.00 from $10.00 with key `k`. Fund the payer with $100.00 and retry with `k`. What comes back? *The same `REJECTED` payment. A key's answer doesn't change. Use a new key for a new attempt.*
3. **Locks are per payment:** in `psql`, lock your last payment with `BEGIN; SELECT id, status FROM payment ORDER BY created_at DESC LIMIT 1 FOR UPDATE;` and don't commit. In another terminal, send a *new* payment with a new key. Does it wait? *No. The lock is on one payment row; a new payment locks only its own.* Now replay the locked payment's key: *the replay returns immediately too. A `COMPLETED` payment is final, so `pay` never reaches steps 2 and 3, the only steps that lock the payment.* `COMMIT` in `psql` when done.
4. **See available ≠ ledger:** in `PaymentIntegrationTest`, `aHoldLowersAvailableButNotLedger…` runs only step 1. Copy it, and print `GET /balance` after `steps.accept` and again after `steps.startProcessing`. *`ledgerMinor` 1000 and `availableMinor` 400 both times; only `complete` changes the ledger.*
5. **Mutation:** make `placeHold` throw `InsufficientFundsException` instead of returning empty, and have `accept` catch it and store `REJECTED`. Run `insufficientFundsIsAStoredRejectionAndMovesNothing`. *It fails, `expected: 201 CREATED but was: 500 INTERNAL_SERVER_ERROR`, and the log shows `UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only`. Nothing is stored. (Recorded while writing this chapter.)*
6. **Predict, then try:** move `startProcessing` and `complete` into `PaymentService` as private `@Transactional` methods called as `this.complete(id)`. How many `Creating new transaction` lines does one payment log with the debug overlay, and what does `lockById`'s `FOR UPDATE` run inside? (Hint: [Under the hood, the proxy](../book/hood.html#h-proxy).)
7. **Design:** make `POST /api/v1/accounts` idempotent. Which fields go in the fingerprint? What's the scope, when there's no payer yet?
8. **Design:** add a sweeper that releases holds of payments stuck in `ACCEPTED` for more than 10 minutes. Why is it safe for `ACCEPTED`, but not for a future `PROCESSING` that called a bank?

## Review questions

1. Why is "201 with `status: REJECTED`" a better answer than 409 for insufficient funds, once payments are idempotent?
2. Which state means "don't retry automatically", and why can't Chapter 4 reach it?
3. Why does placing a hold take `FOR NO KEY UPDATE` on the account, when it doesn't insert a posting?
4. What would go wrong if `captureHold` posted the entry *before* marking the hold captured?
5. Two requests with the same new key arrive 1 ms apart. Describe what each one's `INSERT … ON CONFLICT DO NOTHING` does, in order.
6. Why is `PaymentService.pay` not `@Transactional`, and why are the steps in a separate class?
7. A client retries after 25 hours. What happens, and where is that documented for API users?
8. Why is the idempotency key not logged?
