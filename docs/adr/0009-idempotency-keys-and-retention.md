# ADR 0009 — Idempotency keys scoped to the payer, checked by request fingerprint, kept for 24 hours

**Status:** Accepted (Chapter 4)

## Context

Networks fail in the worst place: after the server has done the work, before the client hears about it. Chapter 4 recorded exactly this. A client gave up after 2 s while the server was still waiting for a row lock. The server then finished the payment, and nobody received the answer. A client that retries blindly pays twice; a client that never retries doesn't know whether it paid.

AGENTS.md requires: idempotency scoped to the authenticated payer and a request fingerprint. Same key and same payload returns the original result. Same key and a different payload is rejected. There is a defined retention policy.

## Decision

1. **The client sends `Idempotency-Key`** (1–64 of `[A-Za-z0-9._:-]`) on every `POST /api/v1/payments`. It is required: no key, 400.
2. **Scope: `(payer, key)`.** The primary key of `idempotency_key` is `(payer_account_id, idempotency_key)`, so two payers can't collide or probe each other's keys. Until Chapter 5 the payer is the unauthenticated `X-Payer-Account-Id` header; Chapter 5 swaps in the authenticated user without changing the scope.
3. **Fingerprint:** SHA-256 hex of `v1|payeeAccountId|amountMinor|currency`, built from the parsed values, not the raw JSON, so field order and whitespace don't matter. The `v1` prefix lets the format change without old fingerprints matching new ones by accident.
4. **Claim before work, in the same transaction as the payment** (`PaymentSteps.accept`):
   1. `DELETE` this one key if it has expired;
   2. `INSERT … ON CONFLICT (payer_account_id, idempotency_key) DO NOTHING`;
   3. inserted 1 row → the key is ours: hold money and insert the payment;
   4. inserted 0 rows → the key is taken: read it. A different fingerprint gives **422**. The same fingerprint returns the original payment, and finishes it if it isn't final (ADR 0008).

   If another transaction has inserted the same key but not committed, PostgreSQL makes our `INSERT` **wait on the primary key** until it commits (0 rows) or rolls back (1 row). This is what turns 20 simultaneous retries into one payment. The key's foreign key to `payment` is `DEFERRABLE INITIALLY DEFERRED`, because the key is written before the payment row exists.
5. **What a replay returns:** the original payment's current state, with the same status-code rule (201 final, 202 unfinished) and the header `Idempotent-Replayed: true`. A stored `REJECTED` stays rejected, even if the payer has been funded since.
6. **What isn't stored:** requests refused before the claim commits (400 validation, 404 unknown account, 400 self-payment or funding account, 422 reuse). They roll back, so the key stays free and a corrected request can use it.
7. **Retention: 24 hours** (`payments.idempotency.retention`, default `PT24H`).
   - Within the window, the key means that payment.
   - After it, the key is treated as unused: `accept` deletes the expired row and claims it again, and the same request makes a **new** payment.
   - `IdempotencyKeyPurger` deletes expired keys every `payments.idempotency.purge-interval` (default `PT1H`). Correctness doesn't depend on it. Payments are never deleted; only the key that pointed at them is.

## Alternatives

- **Keyless deduplication on the payload** ("same payer, payee and amount within N minutes"). This needs no client cooperation, but legitimate repeat payments (two $5 coffees) get swallowed, and retries outside the window still double-pay. A key states intent.
- **Global keys (not per payer).** One client's key could collide with, or reveal, another's. Scoping by payer is required.
- **Look up, then insert** (`SELECT`, and `INSERT` only if absent). Two concurrent first attempts both see "absent". The primary key still stops the second insert, but its request fails with `duplicate key value violates unique constraint "idempotency_key_pkey"` instead of returning the original result. This was run as a mutation: 20 simultaneous retries produced errors.
- **Store the full HTTP response and replay it byte-for-byte.** That's exact, but it duplicates the payment's state in a second place that can disagree. Replaying from the `payment` row returns the *current* state, which a client that timed out mid-processing actually wants.
- **Keep keys forever.** It's the simplest correctness story, but the table grows without bound, and clients rarely retry after minutes, let alone days. 24 hours matches common practice for payment APIs and comfortably covers retries with backoff. A client that "retries" after a day gets a new payment, which the API documentation must say plainly.
- **Optional key.** Clients that forget it get no protection, and nobody notices until a duplicate payment. Required is safer for a payments API.

## Consequences

- A retried `POST` with the same key and body is safe at any time within 24 hours, including concurrently. `PaymentConcurrencyIntegrationTest.twentySimultaneousRetriesWithOneKeyMakeOnePayment` and `race.mjs --same-key` (100 requests → 1 payment, 99 replays) show it.
- Concurrent first attempts with one key queue on the primary key index, each holding a database connection while it waits, as the row lock does in ADR 0006.
- The key isn't logged. It is chosen by the client and might contain anything; logs carry the payment id instead.
- `POST /api/v1/accounts` is still not idempotent. Adding it is a Chapter 4 exercise.
