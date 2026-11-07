# Architecture map (living document)

**Last updated:** Chapter 4 (payment API, holds, idempotency).

**Implemented:**
- one Spring Boot application with PostgreSQL persistence and Flyway migrations V1–V4;
- account creation and read-only account and balance endpoints, with ledger and available balances derived from postings and holds;
- an internal double-entry ledger: journal entries and signed postings, a funding account, database-enforced balance and append-only rules, and per-account row locking for debits and holds;
- the payment API, `POST /api/v1/payments` and `GET /api/v1/payments/{id}`, with explicit lifecycle states, holds, and idempotency keys scoped to the payer with a request fingerprint and a 24-hour retention policy;
- a lab-only funding endpoint (`lab` profile);
- RFC 9457 problem responses, Testcontainers integration and concurrency tests, the Docker build, Compose and CI.

**Not implemented:** authentication (the payer is an unauthenticated `X-Payer-Account-Id` header), hold release and a sweeper for stuck payments, any path to `INDETERMINATE`, lock timeouts, correlation IDs, Kafka.

## System boundary

`HTTP client → payment-api (Spring MVC) → payment module (api → application → domain ← persistence) → ledger module (application → domain ← persistence) → PostgreSQL`, and `account module → ledger module` for balances. The `payment` module depends on `ledger.application` (`LedgerService`) and `ledger.domain` (`JournalEntry`, `Hold`); neither `ledger` nor `account` depends on `payment`. The `ledger` module reads the `account` table through its own native queries.

Startup order, as recorded:
1. Spring scans components and applies auto-configuration.
2. Tomcat is created, but not yet listening.
3. HikariCP opens the pool (10 connections).
4. Flyway migrates PostgreSQL.
5. Hibernate *validates*, never generates, that the JPA mappings match the migrated schema.
6. Application beans are wired. `AccountService`, `LedgerService`, `PaymentSteps`, `PaymentService` and the repositories are wrapped in CGLIB proxies.
7. With the `lab` profile, `LabLedgerController` is created and logs a WARN.
8. `@EnableScheduling` (`SchedulingConfig`) registers `IdempotencyKeyPurger.purge`, first run one `purge-interval` after startup.
9. Tomcat starts listening on 8080.

`entityManagerFactory` depends on `flywayInitializer`, which enforces migrate-before-validate. Actuator serves health. Simulated money enters only through the lab funding endpoint, and moves between customers only through the payment API.

## Module layout

Packages under `apps/payment-api/src/main/java/dev/p2plab/payments/`. Dependencies point inward: `api → application → domain`; `persistence` implements a `domain` interface. `domain` imports no Spring or JPA types.

| Package | Layer | Rule |
| --- | --- | --- |
| `account.api` | HTTP adapter | Validate and translate DTOs; no business rules, no repositories |
| `account.application` | Use cases | Own transaction boundaries; orchestrate domain + repository |
| `account.domain` | Domain | Invariants in plain Java; unit-testable without Spring |
| `account.persistence` | Infrastructure | JPA mapping; package-private so only the port is visible |
| `ledger.api` | HTTP adapter | **Lab profile only.** Funding only. Validate and translate; no business rules |
| `ledger.application` | Use cases | Own the transaction; order locks and checks; append entries; place and capture holds |
| `ledger.domain` | Domain | Double-entry, overdraft and hold rules in plain Java; persistence port |
| `ledger.persistence` | Infrastructure | JPA plus native SQL for row locks, sums and holds; package-private |
| `payment.api` | HTTP adapter | Headers and body validated; maps outcomes to 201/202 and errors to problems |
| `payment.application` | Use cases | `PaymentService` sequences the steps (no transaction); `PaymentSteps` owns one transaction per step |
| `payment.domain` | Domain | Payment states and transitions, fingerprint; persistence ports |
| `payment.persistence` | Infrastructure | JPA for `payment`, native SQL for `idempotency_key`; package-private |
| `money` | Shared domain | Currency and amount value types; exact arithmetic (`Math.addExact`) |
| `config` | Infrastructure | Cross-cutting beans (clock) |

## Important files and responsibilities

| File | Purpose | Runtime relationship / failure modes |
| --- | --- | --- |
| `apps/payment-api/pom.xml` | Java 21, Spring Boot 3.4.5, Testcontainers 1.21.4 (overridden for Docker Engine 29+ compatibility) | Maven builds executable JAR; `mvn verify` runs all tests |
| `.../PaymentApiApplication.java` | Spring Boot entry point | Starts component scan and embedded web server |
| `.../api/CapabilitiesController.java` | Safe discovery endpoint | GET `/api/v1/payments/capabilities`; reports `chapter: 4`, `paymentProcessingEnabled: true`, `idempotencyEnabled: true`, `accountsEnabled`, `ledgerEnabled`. The literal path wins over `GET /api/v1/payments/{id}` |
| `.../config/TimeConfig.java` | Provides UTC `Clock` bean | Injected into services so tests can fix time |
| `.../config/SchedulingConfig.java` | `@EnableScheduling` | Turns on `IdempotencyKeyPurger` |
| `.../money/Currency.java` | Supported currencies (`USD` only) | Used by domain, entity mapping and API conversion |
| `.../money/Money.java` | Amount in integer minor units + currency ([ADR 0002](adr/0002-money-and-balance-representation.md)); `plus` (exact, same currency), `negate`, `isNegative` | Used by `AccountBalance` and the ledger; overflow throws `ArithmeticException`; never floating point |
| `.../account/domain/Account.java` | Account record and invariants: non-blank display name ≤ 80 chars (stripped), currency required, creation time truncated to microseconds | Created by `Account.open`; rehydrated by `AccountEntity.toDomain`; throws `IllegalArgumentException`/`NullPointerException` on invalid state |
| `.../account/domain/AccountRepository.java` | Persistence port: `add`, `findById` | Called by `AccountService`; implemented by `JpaAccountRepository` |
| `.../account/application/AccountService.java` | Use cases `open`, `get`, `balance`; `@Transactional` boundaries; logs account id only | Called by `AccountController`; throws `AccountNotFoundException`; `balance` calls `LedgerService.balance` and `LedgerService.available`, which join the same read-only transaction |
| `.../account/application/AccountBalance.java` | Balance read model: `ledger` and `available` `Money` | Returned by `AccountService.balance` |
| `.../account/application/AccountNotFoundException.java` | Unknown account id | Mapped to 404 by `AccountExceptionHandler` |
| `.../account/persistence/AccountEntity.java` | JPA mapping of `account` table | Package-private; converts to/from domain `Account` |
| `.../account/persistence/JpaAccountRepository.java` | `EntityManager`-based port implementation (`persist`, `find`) | Requires caller's transaction; DB constraint violations surface as `DataIntegrityViolationException` |
| `.../account/api/AccountController.java` | `POST /api/v1/accounts`, `GET /api/v1/accounts/{id}`, `GET /api/v1/accounts/{id}/balance` | Bean Validation → 400; malformed UUID → 400; no list endpoint (enumeration risk without auth) |
| `.../account/api/CreateAccountRequest.java` | Request DTO with `@NotBlank @Size(max=80)` name and `@Pattern("USD")` currency | Rejected before the service is called |
| `.../account/api/AccountResponse.java`, `BalanceResponse.java` | Response DTOs; balances as `ledgerMinor`/`availableMinor` integers | Decouple JSON shape from domain types |
| `.../account/api/AccountExceptionHandler.java` | Maps `AccountNotFoundException` → 404 `ProblemDetail` | Scoped to `AccountController` |
| `.../ledger/domain/JournalEntry.java` | Immutable entry: ≥ 2 postings, one currency, each account once, sum = 0 (exact arithmetic); `move(kind, from, to, amount, clock)` | Built by `LedgerService`; throws `IllegalArgumentException` (→ 400) |
| `.../ledger/domain/Posting.java`, `EntryKind.java` | Signed non-zero amount for one account (negative = debit); `FUNDING` / `TRANSFER` / `PAYMENT` | — |
| `.../ledger/domain/Hold.java` | Reserved amount on one account (`Hold.place`): positive, timestamped to microseconds | Built by `LedgerService.placeHold` ([ADR 0008](adr/0008-payment-lifecycle-and-holds.md)) |
| `.../ledger/domain/LedgerAccount.java` | Ledger view of an account (`currency`, `mayGoNegative`); `checkDebit(available, posting)`, `canSpend(available, amount)`, `checkCredit`; `FUNDING_SOURCE_ID` | `checkDebit` throws `InsufficientFundsException`. Correct only while the caller holds the debit lock |
| `.../ledger/domain/LedgerRepository.java` | Port: `find`, `lockForDebit`, `balance`, `held`, `append`, `addHold`, `captureHold` | Implemented by `JpaLedgerRepository`; caller supplies the transaction |
| `.../ledger/application/LedgerService.java` | `fund`, `transfer`, `placeHold`, `captureHold` (`@Transactional`); `account`, `balance`, `available` (read-only). `post` visits postings in account-id order: lock + read **available** + check for debits, existence + currency for credits, then append. `placeHold` takes the same lock, then returns `Optional.empty()` if available can't cover the amount (never throws: that would mark the caller's transaction rollback-only). `captureHold` marks the hold `CAPTURED`, then posts a `PAYMENT` entry with a pre-generated id | Called by `LabLedgerController`, `AccountService`, `PaymentSteps`; throws `UnknownLedgerAccountException` (→ 404); `captureHold` throws `IllegalStateException` if the hold isn't active. Logs entry and hold ids |
| `.../ledger/persistence/JpaLedgerRepository.java` | Native `SELECT … FOR NO KEY UPDATE` on `account` ([ADR 0006](adr/0006-ledger-concurrency-control.md)); native sums of postings and of `ACTIVE` holds; `persist` of entry then postings; native `INSERT` of holds (immediate, so the payment row can reference it); `captureHold` = `SELECT … FOR UPDATE` on the hold, then `UPDATE … CAPTURED` | Concurrent debits and holds of one account block here. No lock timeout yet |
| `.../ledger/persistence/JournalEntryEntity.java`, `PostingEntity.java` | JPA mappings of `journal_entry` and `posting` | Insert-only; DB triggers reject updates and deletes |
| `.../ledger/api/LabLedgerController.java` | `POST /lab/ledger/funding` only (Chapter 3's `/transfers` was removed in Chapter 4); **exists only with `@Profile("lab")`**; WARN at startup | No auth ([ADR 0005](adr/0005-double-entry-ledger.md)); 404 without the profile |
| `.../ledger/api/FundingRequest.java`, `JournalEntryResponse.java` | DTOs: `@Positive @Max(100_000_000)` amount, `@Pattern("USD")`; signed postings in the response | Rejected before the service is called |
| `.../payment/api/PaymentController.java` | `POST /api/v1/payments` (headers `X-Payer-Account-Id` UUID, `Idempotency-Key` `[A-Za-z0-9._:-]{1,64}`; body `CreatePaymentRequest`); `GET /api/v1/payments/{id}`. 201 if the payment is final (`COMPLETED` or `REJECTED`), 202 if not; `Location` and `Idempotent-Replayed` headers | Missing or malformed header → 400 (`MissingRequestHeaderException`, `HandlerMethodValidationException`). No ownership check until Chapter 5 |
| `.../payment/api/CreatePaymentRequest.java`, `PaymentResponse.java` | Body `{payeeAccountId, amountMinor, currency}` with the same limits as funding; response with status, rejection reason, hold and entry ids, timestamps | Rejected before the service is called |
| `.../payment/api/PaymentExceptionHandler.java` | 404 unknown account or payment, 422 key reused for a different request, 400 rule violation | Scoped to `PaymentController`; details never echo input |
| `.../payment/application/PaymentService.java` | `pay`: `accept`, then `startProcessing` and `complete` unless the payment is final; a `RuntimeException` after step 1 is logged (WARN) and the last committed state returned. `get` (read-only). **Not `@Transactional`** | Called by `PaymentController` |
| `.../payment/application/PaymentSteps.java` | One `@Transactional` method per step ([ADR 0008](adr/0008-payment-lifecycle-and-holds.md)): `accept` (check parties, delete expired key, claim key, `placeHold`, insert `ACCEPTED` or `REJECTED`; replay or 422 if the key is taken), `startProcessing` and `complete` (lock the payment row; act only from the expected state). A separate bean so calls go through the proxy | Throws `IllegalArgumentException` (self-payment, funding account), `UnknownLedgerAccountException`, `IdempotencyKeyReusedException`, `PaymentNotFoundException`. Retention from `payments.idempotency.retention` |
| `.../payment/application/PaymentCommand.java`, `IdempotencyKeyReusedException.java`, `PaymentNotFoundException.java` | Use-case input (payer, key, payee, amount); errors | — |
| `.../payment/application/IdempotencyKeyPurger.java` | `@Scheduled` every `payments.idempotency.purge-interval` (PT1H): deletes expired keys in a `TransactionTemplate`; logs the count | Housekeeping only; correctness relies on `deleteIfExpired` in `accept` |
| `.../payment/domain/Payment.java`, `PaymentStatus.java`, `RejectionReason.java` | Immutable payment; `accepted`, `rejected`, `startProcessing`, `complete`; constructor enforces V4's state/fact rules; `canMoveTo` lists the allowed transitions (`INDETERMINATE` defined, unreachable in Chapter 4) | Illegal move → `IllegalStateException` |
| `.../payment/domain/RequestFingerprint.java` | SHA-256 hex of `v1|payee|amountMinor|currency` | Stored in `idempotency_key.request_fingerprint` ([ADR 0009](adr/0009-idempotency-keys-and-retention.md)) |
| `.../payment/domain/PaymentRepository.java`, `IdempotencyKeyRepository.java` | Ports: `add`, `findById`, `lockById`, `update`; `claim`, `find`, `deleteIfExpired`, `purgeExpired` | Caller supplies the transaction |
| `.../payment/persistence/PaymentEntity.java`, `JpaPaymentRepository.java` | JPA mapping of `payment` (immutable columns `updatable = false`); `lockById` = native `SELECT * FROM payment WHERE id = ? FOR UPDATE`; `update` copies the new state onto the managed entity (dirty checking writes it at commit) | — |
| `.../payment/persistence/SqlIdempotencyKeyRepository.java` | Native `INSERT … ON CONFLICT (payer_account_id, idempotency_key) DO NOTHING`, lookups and deletes | A concurrent claim of the same key waits on the primary key until the first commits or rolls back |
| `.../ledger/api/LedgerExceptionHandler.java` | 404 unknown account, 409 insufficient funds, 400 rule violation | Scoped to `LabLedgerController`; details never echo input |
| `apps/payment-api/src/main/resources/application.yml` | Datasource, Flyway, `ddl-auto: validate`, `spring.mvc.problemdetails.enabled`, Actuator | Env vars override DB settings |
| `.../db/migration/V1__baseline.sql` | Chapter 1 schema marker | Applied once by Flyway |
| `.../db/migration/V2__accounts.sql` | `account` table: `id UUID PK`, `display_name VARCHAR(80)` non-blank `CHECK`, `currency VARCHAR(3) CHECK = 'USD'`, `created_at TIMESTAMPTZ`; **no balance column** | Applied on startup; constraints defend invariants even if application code is bypassed |
| `.../db/migration/V4__payments.sql` | `ledger_hold` (account FK, positive amount, `ACTIVE`/`CAPTURED`/`RELEASED`, `UNIQUE` deferred FK to `journal_entry`, `CHECK`s: captured ⇔ entry, active ⇔ not closed; partial index on active holds); `payment` (payer and payee FKs, amount, five statuses, reason, `UNIQUE` hold and entry FKs; `CHECK`s: payer ≠ payee, rejected ⇔ reason, completed ⇔ entry, non-rejected ⇒ hold); `idempotency_key` (PK `(payer_account_id, idempotency_key)`, `CHAR(64)` fingerprint, deferred FK to `payment`, `expires_at` index); `journal_entry.kind` allows `PAYMENT` | The PK is what serializes concurrent first uses of a key. Contradictory states fail with `payment_check…` / `ledger_hold_check…` |
| `.../db/migration/V3__ledger.sql` | `account.kind` (`CUSTOMER` default / `FUNDING`) and the seeded funding account `…f00d`; `journal_entry` (id, kind, created_at); `posting` (id, entry_id FK, account_id FK, `amount_minor BIGINT <> 0`, currency) with indexes on account_id and entry_id; append-only statement triggers; deferred constraint triggers checking each entry has ≥ 2 postings summing to 0 | Unbalanced entries fail at COMMIT (`check_violation`); UPDATE/DELETE/TRUNCATE fail (`restrict_violation`). Posting FKs take `FOR KEY SHARE` on account rows |
| `.../test/.../account/domain/AccountTest.java` | Unit tests of domain invariants | No Spring context |
| `.../test/.../account/api/AccountControllerTest.java` | `@WebMvcTest` contract tests with mocked service | Status codes, validation, problem JSON, response shape |
| `.../test/.../account/AccountPersistenceIntegrationTest.java` | Full stack over real PostgreSQL 16 via Testcontainers | Requires Docker; verifies Flyway V2, round trip, zero balance, DB `CHECK` constraints |
| `.../test/.../api/CapabilitiesControllerTest.java` | Capabilities contract | No DB |
| `.../test/.../money/MoneyTest.java`, `ledger/domain/JournalEntryTest.java` | Unit tests: exact arithmetic and overflow; entry balance, posting count, same-account, amounts; overdraft and funding rules | No Spring context |
| `.../test/.../ledger/api/LabLedgerControllerTest.java` | `@WebMvcTest` with `@ActiveProfiles("lab")`, service mocked | Funding 201 shape, 404, 400; `/lab/ledger/transfers` is 404 |
| `.../test/.../ledger/LedgerIntegrationTest.java` | HTTP → ledger → PostgreSQL (lab profile) | Funding, unknown account writes nothing, ledger sums to 0; DB rejects unbalanced entries at COMMIT and UPDATE/DELETE |
| `.../test/.../payment/domain/PaymentTest.java` | Unit tests: transitions (every move from final states refused), state/fact rules, fingerprint stability | No Spring context |
| `.../test/.../payment/api/PaymentControllerTest.java` | `@WebMvcTest`, service mocked | 201 completed and rejected, 202 unfinished, replay header, 422, 404, 400 for missing/malformed headers and amounts, GET |
| `.../test/.../payment/PaymentIntegrationTest.java` | HTTP → payments → PostgreSQL (lab profile, for funding) | Completion captures the hold; stored rejection; replay moves money once; a replayed rejection survives funding; 422; per-payer scope; expired key reuse; purge; hold lowers available and blocks a direct transfer; retry resumes `ACCEPTED`/`PROCESSING`; invalid parties store nothing; DB `CHECK`s |
| `.../test/.../payment/PaymentConcurrencyIntegrationTest.java` | 20 simultaneous requests with one key → 1 payment, 19 replays; 50 payments of $1 from $10 → 10 completed, 40 rejected, no active holds; a waiting hold blocks (`pg_stat_activity`) and then sees the committed hold | Fails if `placeHold` drops the lock (T2 doesn't wait), or if the claim becomes look-up-then-insert (duplicate-key errors). Both mutations were run |
| `.../test/.../ledger/LedgerConcurrencyIntegrationTest.java` | 50 simultaneous debits (exactly 10 succeed); opposite-direction transfers don't deadlock; a waiting debit blocks (`pg_stat_activity`) and then reads the committed balance; deliberately unsafe SQL check-then-insert overdraws (−1000) | Fails if the lock is removed (12 succeeded, −200) or changed to `FOR UPDATE` (40P01). Both mutations were run |
| `apps/payment-api/Dockerfile` | Multi-stage Maven/JRE image | Builds JAR (tests skipped; CI runs them) and runs as non-root |
| `compose.yaml` | Local API + PostgreSQL 16 | Waits for DB health before starting API |
| `infra/compose.debug.yaml` | **Local-only** overlay ([ADR 0004](adr/0004-under-the-hood-evidence.md)): `docker compose -f compose.yaml -f infra/compose.debug.yaml up`. Sets DEBUG/TRACE for Spring MVC, transactions and Hibernate SQL/binding via `SPRING_APPLICATION_JSON`; exposes actuator `beans`, `mappings`, `conditions`, `metrics` | Used to record the book's evidence. Logs SQL parameters, including display names: never use outside a developer machine. Not used by CI or the Dockerfile |
| `infra/compose.lab.yaml` | **Local-only** overlay ([ADR 0005](adr/0005-double-entry-ledger.md)): sets `SPRING_PROFILES_ACTIVE=lab` (lab funding endpoint) and exposes actuator `metrics` | Combine with the debug overlay by listing it first. Anyone reaching port 8080 can issue simulated money |
| `infra/lab/race.mjs` | Node 18+ script, no dependencies: opens two accounts, funds A (and B with `--opposite`) through the lab endpoint, fires N simultaneous `POST /api/v1/payments` with distinct keys (`--opposite` for both directions, `--same-key` for one key on every A→B request), samples HikariCP gauges, prints status codes, payment statuses, distinct payments and replays, latency, throughput, and checks no negative balance, no money created, the expected amount paid, and one payment per key | Needs the lab overlay; exits 1 if an invariant breaks |
| `.github/workflows/ci.yml` | CI | Three jobs: `test` (`mvn verify`; GitHub runners provide Docker for Testcontainers), `textbook` (`check-snippets.mjs`), `docker` (image build) |
| `docs/book/index.html` | Interactive textbook home ([ADR 0003](adr/0003-interactive-textbook.md)): chapter list with quiz scores. Old single-page links (`index.html#ch-03`) redirect to the chapter's page | Static; open from disk or any static host |
| `docs/book/ch-01.html`, `ch-02.html`, `ch-03.html`, `ch-04.html`, `hood.html` | One page per chapter, plus the **Under the hood** companion ([ADR 0004](adr/0004-under-the-hood-evidence.md)): code tours, tracers, playgrounds, exercises, quiz. Every page carries the same contents rail | Code excerpts must match their `data-source` files; tracer logs are recorded output. Link across pages as `page.html#heading-id` |
| `docs/book/glossary.html`, `docs/book/glossary.js` | `glossary.js` is the only copy of the glossary (`window.BOOK_GLOSSARY = [[id, term, html], …]`); `glossary.html` renders it and every page's term pop-ups read it | A script, not fetched JSON, so it works from disk |
| `docs/book/book.js` | Enhances declarative components: contents rail (marks the current page, lists its sections, redirects old `#view` links), code tours, a shared explanation panel (`explainer`: beginner layers `what` and `why`, then `deep` in a "How it works underneath" fold-out; ADR 0007) used by tracer animation and playgrounds, playground narration read from the page's `data-narration` JSON (`narration`), code-tour notes with an optional "In plain words" line (`.reads`), layer explorer (`.explorer`), quizzes (`localStorage` answers per quiz, plus a saved score the rail on other pages shows), glossary page and pop-ups from `glossary.js`, and playgrounds (money, API simulator, defense in depth, microsecond bug, Docker build cache, and the two-transaction race, narrated in all three layers from `ch-03.html`, with a timeline table: one column per moment, rows T1, T2 and A's committed balance, out-of-date reads in red, lock waits striped) | Loads Prism 1.29.0 from cdnjs for highlighting; works without it |
| `docs/book/book.css` | Theme tokens (light and dark) and component styles | — |
| `docs/book/tools/check-snippets.mjs` | Node script, no dependencies: checks every page in `docs/book/` for excerpts, tour anchors, glossary links (against `glossary.js`), tracer JSON (including `deep` fields, and that a tracer using `what`/`why` has both on every step), playground narration (`what`, `why` and `deep` on every entry; every key `book.js` narrates is defined and none is unused), links between pages and to heading ids, explorer panes and quiz answers; also that the contents rail is identical on every page and its `data-total` quiz counts are right | Run by CI `textbook` job; exits 1 on any drift |
| `docs/adr/0001-modular-monolith.md` | Monolith rationale | — |
| `docs/adr/0002-money-and-balance-representation.md` | Minor units; derived balances; no seed funds | — |
| `docs/adr/0003-interactive-textbook.md` | Static HTML book verified in CI | — |
| `docs/adr/0004-under-the-hood-evidence.md` | Framework internals taught from recorded output; local-only debug overlay | — |
| `docs/adr/0005-double-entry-ledger.md` | Signed postings, funding account, DB-enforced balance and append-only rules, lab endpoints | — |
| `docs/adr/0006-ledger-concurrency-control.md` | `FOR NO KEY UPDATE` on debited accounts, read after lock, sorted lock order | — |
| `docs/adr/0008-payment-lifecycle-and-holds.md` | Payment record and states; three committed steps in a separate bean; holds in the ledger, debit lock, available = ledger − holds; insufficient funds as stored `REJECTED` (201) | — |
| `docs/adr/0009-idempotency-keys-and-retention.md` | Required `Idempotency-Key`, `(payer, key)` scope, SHA-256 fingerprint, `ON CONFLICT DO NOTHING` claim, replay semantics, 24 h retention and purge | — |
| `docs/adr/0007-beginner-explanation-layers.md` | Book explanations in layers for a new hire: `what` and `why` before `deep`, "In plain words" code notes, chapter primers; amended so playground moves use the same layers via `data-narration` | Enforced for tracers and playground narration by `check-snippets.mjs` |
| `docs/chapters/02-accounts.md` | Chapter 2 textbook | Walkthrough of this slice |
| `docs/chapters/03-ledger.md` | Chapter 3 textbook: double entry, atomicity, the race, row locks, deadlock, load | Recorded evidence from the running service and tests; its lab transfer endpoint is noted as replaced |
| `docs/chapters/04-payments.md` | Chapter 4 textbook: lifecycle, holds, idempotency keys, the timeout-and-retry recording, concurrent retries | Recorded evidence from the running service and tests |
| `docs/chapters/companion-under-the-hood.md` | Plain-text companion: running layers, bean wiring, transaction proxy, JPA/Hibernate/JDBC, Docker cache | Recorded evidence; re-record on Spring Boot upgrades |

## Request traces

**Create account** — `POST /api/v1/accounts {"displayName","currency"}` → Tomcat → `DispatcherServlet` → Jackson deserializes `CreateAccountRequest` (malformed JSON → 400) → `@Valid` Bean Validation (failure → 400 problem JSON, service never called) → `AccountController.open` → `AccountService.open` opens a transaction → `Account.open` generates UUID, strips name, timestamps from `Clock` → `JpaAccountRepository.add` → `EntityManager.persist` → commit flushes `INSERT INTO account` (DB `CHECK` is the last line of defense) → log `account opened accountId=… currency=USD` → `201 Created`, `Location: /api/v1/accounts/{id}`, body `AccountResponse`.

**Read account** — `GET /api/v1/accounts/{id}` → path variable converted to `UUID` (failure → 400) → `AccountService.get` (read-only transaction) → `EntityManager.find` → `AccountEntity.toDomain` → 200, or `AccountNotFoundException` → `AccountExceptionHandler` → 404 problem JSON.

**Read balance** — `GET /api/v1/accounts/{id}/balance` → `AccountService.balance` (read-only transaction) → loads account (404 if absent) → `LedgerService.balance` and `LedgerService.available` join the transaction → `SUM(amount_minor)` of postings, and of `ACTIVE` holds → ledger, and available = ledger − held → `BalanceResponse` `{accountId, currency, ledgerMinor, availableMinor}`. Available is lower only while a payment is `ACCEPTED` or `PROCESSING`. The funding account's balance is negative.

**Fund (lab profile only)** — `POST /lab/ledger/funding {accountId, amountMinor, currency}` (404 without the profile) → Bean Validation (positive, ≤ $1,000,000, USD; otherwise 400) → `LedgerService.fund` (READ COMMITTED transaction) → `JournalEntry.move(FUNDING, …f00d, account)` → `post` (below) → 201 with signed postings.

**`post` (every journal entry: funding, direct transfer, payment capture)** — postings in `UUID.compareTo` order. **Debit:** `SELECT currency, kind FROM account WHERE id = ? FOR NO KEY UPDATE` (waits while another debit or hold of that account is in flight), then the sums of postings and active holds, then `checkDebit(available, posting)`, which throws `InsufficientFundsException` if a customer would go below zero. **Credit:** `SELECT currency, kind` (unknown → `UnknownLedgerAccountException`). Then `persist` the entry and postings. At commit: three INSERTs; each posting's foreign key takes `FOR KEY SHARE` on its account; the deferred triggers verify the entry sums to 0; the row lock is released.

**Pay** — `POST /api/v1/payments`, headers `X-Payer-Account-Id`, `Idempotency-Key`, body `{payeeAccountId, amountMinor, currency}`:
1. Header and body validation (400, nothing stored). `PaymentController.pay` → `PaymentService.pay` (no transaction).
2. **Transaction 1, `PaymentSteps.accept`:** both accounts must exist (404), differ, and be customers (400); `DELETE` this key if expired; `INSERT … ON CONFLICT DO NOTHING` into `idempotency_key` (a concurrent claim of the same key waits here).
   - **0 rows** (key taken): read fingerprint and payment id; different fingerprint → `IdempotencyKeyReusedException`, rollback, 422; same → the original payment (`replayed = true`), go to step 5.
   - **1 row:** `LedgerService.placeHold` joins the transaction: `FOR NO KEY UPDATE` on the payer, sums of postings and active holds, then `INSERT INTO ledger_hold` and `ACCEPTED`, or no hold and `REJECTED`/`INSUFFICIENT_FUNDS`. `persist` the payment. Commit (the key's deferred FK to the payment is checked here).
3. **Transaction 2, `startProcessing`** (skipped if final): `SELECT * FROM payment WHERE id = ? FOR UPDATE`; if `ACCEPTED` → `PROCESSING`. Commit.
4. **Transaction 3, `complete`** (skipped if final): lock the payment again; if `PROCESSING`: `LedgerService.captureHold` → `SELECT … FROM ledger_hold WHERE id = ? AND status = 'ACTIVE' FOR UPDATE`, `UPDATE … CAPTURED` with a pre-generated entry id (deferred FK), then `post` a `PAYMENT` entry (payer debit, payee credit); payment → `COMPLETED` with the entry id. Commit: entry, postings, payment update.
5. If step 3 or 4 throws, `pay` logs a WARN and keeps the last committed state.
6. Response: 201 if `COMPLETED` or `REJECTED`, else 202; `Location: /api/v1/payments/{id}`; `Idempotent-Replayed: true|false`; body `PaymentResponse`.

**Read payment** — `GET /api/v1/payments/{id}` → `PaymentService.get` (read-only) → 200, or 404 problem.

**Purge** — every `payments.idempotency.purge-interval` (PT1H): `IdempotencyKeyPurger.purge` → `DELETE FROM idempotency_key WHERE expires_at <= now` in its own transaction → log `expired idempotency keys purged count=…`.

**Capabilities** — `GET /api/v1/payments/capabilities` → static JSON: `chapter: 4`, `ledgerEnabled: true`, `paymentProcessingEnabled: true`, `idempotencyEnabled: true`.

## Data lifecycle

Flyway applies `V1__baseline.sql` to `V4__payments.sql` in order and records them in `flyway_schema_history`. V3 adds `account.kind` and inserts the funding account. Accounts are inserted once and never updated or deleted by the application. Journal entries and postings are append-only, and database triggers reject `UPDATE`, `DELETE` and `TRUNCATE`; corrections will be reversing entries. Balances are never stored.

A **hold** is inserted `ACTIVE` and updated once, to `CAPTURED` (with its entry) or, from Chapter 7, `RELEASED`; it's never deleted. A **payment** is inserted `ACCEPTED` or `REJECTED`, and its status, entry and `updated_at` change only along `PaymentStatus.canMoveTo`; payments are never deleted. An **idempotency key** lives 24 hours (`expires_at`): it's deleted when its payer reuses it after expiry, or by the hourly purge; the payment it pointed to is kept.

Display names are user-supplied and treated as personal data: they are stored and returned, but not logged. The ledger and payments store no free text. Idempotency keys are client-chosen and are not logged; logs carry payment, hold and entry ids.

## Architectural invariants

**Implemented:**
- money is integer minor units with explicit currency, and currency is USD in the API, domain and database;
- every journal entry has at least two postings summing to zero, checked in Java and again by PostgreSQL at commit, so `SELECT SUM(amount_minor) FROM posting` is always 0;
- money enters only through the funding account;
- postings are immutable;
- a customer's available balance (ledger − active holds) never goes negative, even under concurrent debits and holds: the account row is locked, then postings and holds are read (ADR 0006, ADR 0008);
- acceptance and completion are separate, committed states; each state carries exactly its facts (reason, hold, entry), checked in Java and by `CHECK` constraints;
- a completed payment has exactly one journal entry and one captured hold (`UNIQUE` foreign keys);
- one `(payer, Idempotency-Key)` produces at most one payment within the 24-hour retention window, even for concurrent requests; a different request with the same key is refused (422) (ADR 0009);
- payments are only between two different customer accounts; the funding account can't pay or be paid;
- schema changes only through Flyway, and Hibernate validates rather than generates the schema.

**Future requirements:**
- the payer is authenticated, and only the payer can read their payments (Chapter 5); until then `X-Payer-Account-Id` is trusted;
- holds of payments stuck in `ACCEPTED` are released by a sweeper, and holds are released when an external transfer is refused (Chapter 7);
- uncertainty about external transfers (`INDETERMINATE`) requires reconciliation, never an automatic retry (Chapter 7);
- `POST /api/v1/accounts` is not idempotent: a retried request creates a second account;
- lock waits need a timeout;
- outbox events must be durable before publication;
- every request carries a correlation ID;
- the lab funding endpoint is removed before authentication (Chapter 5).

## Updating this map

For every new important class or file, add a row stating path, purpose, callers, dependencies, and failure modes where relevant. Update the request trace, data flow, implemented/not-implemented boundaries, and chapter cross-references. Remove obsolete entries immediately.
