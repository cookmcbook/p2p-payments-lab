# Architecture map (living document)

**Last updated:** Chapter 3 (double-entry ledger).

**Implemented:**
- one Spring Boot application with PostgreSQL persistence and Flyway migrations V1–V3;
- account creation and read-only account and balance endpoints, with balances derived from ledger postings;
- an internal double-entry ledger: journal entries and signed postings, a funding account, database-enforced balance and append-only rules, and per-account row locking for debits;
- lab-only HTTP endpoints for funding and transfers (`lab` profile);
- RFC 9457 problem responses, Testcontainers integration and concurrency tests, the Docker build, Compose and CI.

**Not implemented:** the payment API and its lifecycle states, idempotency, authentication, holds (so available = ledger), lock timeouts, correlation IDs, Kafka.

## System boundary

`HTTP client → payment-api (Spring MVC) → account module (api → application → domain ← persistence) → ledger module (application → domain ← persistence) → PostgreSQL`. The `account` module depends on `ledger.application` for balances; the `ledger` module doesn't depend on `account` classes, and reads the `account` table through its own native queries.

Startup order, as recorded:
1. Spring scans components and applies auto-configuration.
2. Tomcat is created, but not yet listening.
3. HikariCP opens the pool (10 connections).
4. Flyway migrates PostgreSQL.
5. Hibernate *validates*, never generates, that the JPA mappings match the migrated schema.
6. Application beans are wired. `AccountService`, `LedgerService` and the repositories are wrapped in CGLIB proxies.
7. With the `lab` profile, `LabLedgerController` is created and logs a WARN.
8. Tomcat starts listening on 8080.

`entityManagerFactory` depends on `flywayInitializer`, which enforces migrate-before-validate. Actuator serves health. Simulated money moves only through the lab endpoints.

## Module layout

Packages under `apps/payment-api/src/main/java/dev/p2plab/payments/`. Dependencies point inward: `api → application → domain`; `persistence` implements a `domain` interface. `domain` imports no Spring or JPA types.

| Package | Layer | Rule |
| --- | --- | --- |
| `account.api` | HTTP adapter | Validate and translate DTOs; no business rules, no repositories |
| `account.application` | Use cases | Own transaction boundaries; orchestrate domain + repository |
| `account.domain` | Domain | Invariants in plain Java; unit-testable without Spring |
| `account.persistence` | Infrastructure | JPA mapping; package-private so only the port is visible |
| `ledger.api` | HTTP adapter | **Lab profile only.** Validate and translate; no business rules |
| `ledger.application` | Use cases | Own the transaction; order locks and checks; append entries |
| `ledger.domain` | Domain | Double-entry and overdraft rules in plain Java; persistence port |
| `ledger.persistence` | Infrastructure | JPA plus native SQL for row locks and sums; package-private |
| `money` | Shared domain | Currency and amount value types; exact arithmetic (`Math.addExact`) |
| `config` | Infrastructure | Cross-cutting beans (clock) |

## Important files and responsibilities

| File | Purpose | Runtime relationship / failure modes |
| --- | --- | --- |
| `apps/payment-api/pom.xml` | Java 21, Spring Boot 3.4.5, Testcontainers 1.21.4 (overridden for Docker Engine 29+ compatibility) | Maven builds executable JAR; `mvn verify` runs all tests |
| `.../PaymentApiApplication.java` | Spring Boot entry point | Starts component scan and embedded web server |
| `.../api/CapabilitiesController.java` | Safe discovery endpoint | GET `/api/v1/payments/capabilities`; reports `chapter: 2`, `accountsEnabled: true`, `paymentProcessingEnabled: false` |
| `.../config/TimeConfig.java` | Provides UTC `Clock` bean | Injected into `AccountService` so tests can fix time |
| `.../money/Currency.java` | Supported currencies (`USD` only) | Used by domain, entity mapping and API conversion |
| `.../money/Money.java` | Amount in integer minor units + currency ([ADR 0002](adr/0002-money-and-balance-representation.md)); `plus` (exact, same currency), `negate`, `isNegative` | Used by `AccountBalance` and the ledger; overflow throws `ArithmeticException`; never floating point |
| `.../account/domain/Account.java` | Account record and invariants: non-blank display name ≤ 80 chars (stripped), currency required, creation time truncated to microseconds | Created by `Account.open`; rehydrated by `AccountEntity.toDomain`; throws `IllegalArgumentException`/`NullPointerException` on invalid state |
| `.../account/domain/AccountRepository.java` | Persistence port: `add`, `findById` | Called by `AccountService`; implemented by `JpaAccountRepository` |
| `.../account/application/AccountService.java` | Use cases `open`, `get`, `balance`; `@Transactional` boundaries; logs account id only | Called by `AccountController`; throws `AccountNotFoundException`; `balance` calls `LedgerService.balance`, which joins the same read-only transaction |
| `.../account/application/AccountBalance.java` | Balance read model: `ledger` and `available` `Money` | Returned by `AccountService.balance` |
| `.../account/application/AccountNotFoundException.java` | Unknown account id | Mapped to 404 by `AccountExceptionHandler` |
| `.../account/persistence/AccountEntity.java` | JPA mapping of `account` table | Package-private; converts to/from domain `Account` |
| `.../account/persistence/JpaAccountRepository.java` | `EntityManager`-based port implementation (`persist`, `find`) | Requires caller's transaction; DB constraint violations surface as `DataIntegrityViolationException` |
| `.../account/api/AccountController.java` | `POST /api/v1/accounts`, `GET /api/v1/accounts/{id}`, `GET /api/v1/accounts/{id}/balance` | Bean Validation → 400; malformed UUID → 400; no list endpoint (enumeration risk without auth) |
| `.../account/api/CreateAccountRequest.java` | Request DTO with `@NotBlank @Size(max=80)` name and `@Pattern("USD")` currency | Rejected before the service is called |
| `.../account/api/AccountResponse.java`, `BalanceResponse.java` | Response DTOs; balances as `ledgerMinor`/`availableMinor` integers | Decouple JSON shape from domain types |
| `.../account/api/AccountExceptionHandler.java` | Maps `AccountNotFoundException` → 404 `ProblemDetail` | Scoped to `AccountController` |
| `.../ledger/domain/JournalEntry.java` | Immutable entry: ≥ 2 postings, one currency, each account once, sum = 0 (exact arithmetic); `move(kind, from, to, amount, clock)` | Built by `LedgerService`; throws `IllegalArgumentException` (→ 400) |
| `.../ledger/domain/Posting.java`, `EntryKind.java` | Signed non-zero amount for one account (negative = debit); `FUNDING` / `TRANSFER` | — |
| `.../ledger/domain/LedgerAccount.java` | Ledger view of an account (`currency`, `mayGoNegative`); `checkDebit` / `checkCredit`; `FUNDING_SOURCE_ID` | `checkDebit` throws `InsufficientFundsException` (→ 409). Correct only while the caller holds the debit lock |
| `.../ledger/domain/LedgerRepository.java` | Port: `find`, `lockForDebit`, `balance`, `append` | Implemented by `JpaLedgerRepository`; caller supplies the transaction |
| `.../ledger/application/LedgerService.java` | `fund`, `transfer` (`@Transactional`), `balance` (read-only). `post` visits postings in account-id order: lock + read balance + check for debits, existence + currency for credits, then append | Called by `LabLedgerController` and `AccountService`; throws `UnknownLedgerAccountException` (→ 404). Logs entry id, kind, posting count |
| `.../ledger/persistence/JpaLedgerRepository.java` | Native `SELECT … FOR NO KEY UPDATE` on `account` ([ADR 0006](adr/0006-ledger-concurrency-control.md)); native `SUM(amount_minor)`; `persist` of entry then postings | Concurrent debits of one account block here. No lock timeout yet |
| `.../ledger/persistence/JournalEntryEntity.java`, `PostingEntity.java` | JPA mappings of `journal_entry` and `posting` | Insert-only; DB triggers reject updates and deletes |
| `.../ledger/api/LabLedgerController.java` | `POST /lab/ledger/funding`, `POST /lab/ledger/transfers`; **exists only with `@Profile("lab")`**; WARN at startup | No auth, no idempotency ([ADR 0005](adr/0005-double-entry-ledger.md)); 404 without the profile |
| `.../ledger/api/FundingRequest.java`, `TransferRequest.java`, `JournalEntryResponse.java` | DTOs: `@Positive @Max(100_000_000)` amount, `@Pattern("USD")`; signed postings in the response | Rejected before the service is called |
| `.../ledger/api/LedgerExceptionHandler.java` | 404 unknown account, 409 insufficient funds, 400 rule violation | Scoped to `LabLedgerController`; details never echo input |
| `apps/payment-api/src/main/resources/application.yml` | Datasource, Flyway, `ddl-auto: validate`, `spring.mvc.problemdetails.enabled`, Actuator | Env vars override DB settings |
| `.../db/migration/V1__baseline.sql` | Chapter 1 schema marker | Applied once by Flyway |
| `.../db/migration/V2__accounts.sql` | `account` table: `id UUID PK`, `display_name VARCHAR(80)` non-blank `CHECK`, `currency VARCHAR(3) CHECK = 'USD'`, `created_at TIMESTAMPTZ`; **no balance column** | Applied on startup; constraints defend invariants even if application code is bypassed |
| `.../db/migration/V3__ledger.sql` | `account.kind` (`CUSTOMER` default / `FUNDING`) and the seeded funding account `…f00d`; `journal_entry` (id, kind, created_at); `posting` (id, entry_id FK, account_id FK, `amount_minor BIGINT <> 0`, currency) with indexes on account_id and entry_id; append-only statement triggers; deferred constraint triggers checking each entry has ≥ 2 postings summing to 0 | Unbalanced entries fail at COMMIT (`check_violation`); UPDATE/DELETE/TRUNCATE fail (`restrict_violation`). Posting FKs take `FOR KEY SHARE` on account rows |
| `.../test/.../account/domain/AccountTest.java` | Unit tests of domain invariants | No Spring context |
| `.../test/.../account/api/AccountControllerTest.java` | `@WebMvcTest` contract tests with mocked service | Status codes, validation, problem JSON, response shape |
| `.../test/.../account/AccountPersistenceIntegrationTest.java` | Full stack over real PostgreSQL 16 via Testcontainers | Requires Docker; verifies Flyway V2, round trip, zero balance, DB `CHECK` constraints |
| `.../test/.../api/CapabilitiesControllerTest.java` | Capabilities contract | No DB |
| `.../test/.../money/MoneyTest.java`, `ledger/domain/JournalEntryTest.java` | Unit tests: exact arithmetic and overflow; entry balance, posting count, same-account, amounts; overdraft and funding rules | No Spring context |
| `.../test/.../ledger/api/LabLedgerControllerTest.java` | `@WebMvcTest` with `@ActiveProfiles("lab")`, service mocked | 201 shape, 409, 404, 400 |
| `.../test/.../ledger/LedgerIntegrationTest.java` | HTTP → ledger → PostgreSQL (lab profile) | Funding and transfer, overdraft writes nothing, ledger sums to 0; DB rejects unbalanced entries at COMMIT and UPDATE/DELETE |
| `.../test/.../ledger/LedgerConcurrencyIntegrationTest.java` | 50 simultaneous debits (exactly 10 succeed); opposite-direction transfers don't deadlock; a waiting debit blocks (`pg_stat_activity`) and then reads the committed balance; deliberately unsafe SQL check-then-insert overdraws (−1000) | Fails if the lock is removed (12 succeeded, −200) or changed to `FOR UPDATE` (40P01). Both mutations were run |
| `apps/payment-api/Dockerfile` | Multi-stage Maven/JRE image | Builds JAR (tests skipped; CI runs them) and runs as non-root |
| `compose.yaml` | Local API + PostgreSQL 16 | Waits for DB health before starting API |
| `infra/compose.debug.yaml` | **Local-only** overlay ([ADR 0004](adr/0004-under-the-hood-evidence.md)): `docker compose -f compose.yaml -f infra/compose.debug.yaml up`. Sets DEBUG/TRACE for Spring MVC, transactions and Hibernate SQL/binding via `SPRING_APPLICATION_JSON`; exposes actuator `beans`, `mappings`, `conditions`, `metrics` | Used to record the book's evidence. Logs SQL parameters, including display names: never use outside a developer machine. Not used by CI or the Dockerfile |
| `infra/compose.lab.yaml` | **Local-only** overlay ([ADR 0005](adr/0005-double-entry-ledger.md)): sets `SPRING_PROFILES_ACTIVE=lab` (lab ledger endpoints) and exposes actuator `metrics` | Combine with the debug overlay by listing it first. Anyone reaching port 8080 can move simulated money |
| `infra/lab/race.mjs` | Node 18+ script, no dependencies: opens two accounts, funds one, fires N simultaneous transfers (`--opposite` for both directions), samples HikariCP gauges, prints status counts, latency, throughput, and checks no negative balance and no money created | Needs the lab overlay; exits 1 if an invariant breaks |
| `.github/workflows/ci.yml` | CI | Three jobs: `test` (`mvn verify`; GitHub runners provide Docker for Testcontainers), `textbook` (`check-snippets.mjs`), `docker` (image build) |
| `docs/book/index.html` | Interactive textbook ([ADR 0003](adr/0003-interactive-textbook.md)): all chapters plus the **Under the hood** companion (`#hood`, [ADR 0004](adr/0004-under-the-hood-evidence.md)) as hash-routed views, with code tours, tracers, playgrounds, quizzes and the glossary | Static; open from disk or any static host. Code excerpts must match their `data-source` files; tracer logs are recorded output |
| `docs/book/book.js` | Enhances declarative components: router and `data-jump` links, code tours, tracer animation with per-step "Under the hood" panel (`deep`), layer explorer (`.explorer`), quizzes (`localStorage` progress), glossary pop-ups, and playgrounds (money, API simulator, defense in depth, microsecond bug, Docker build cache) | Loads Prism 1.29.0 from cdnjs for highlighting; works without it |
| `docs/book/book.css` | Theme tokens (light and dark) and component styles | — |
| `docs/book/tools/check-snippets.mjs` | Node script, no dependencies: verifies excerpts, tour anchors, glossary links, tracer JSON (including `deep` fields), `data-jump` targets, explorer panes and quiz answers | Run by CI `textbook` job; exits 1 on any drift |
| `docs/adr/0001-modular-monolith.md` | Monolith rationale | — |
| `docs/adr/0002-money-and-balance-representation.md` | Minor units; derived balances; no seed funds | — |
| `docs/adr/0003-interactive-textbook.md` | Static HTML book verified in CI | — |
| `docs/adr/0004-under-the-hood-evidence.md` | Framework internals taught from recorded output; local-only debug overlay | — |
| `docs/adr/0005-double-entry-ledger.md` | Signed postings, funding account, DB-enforced balance and append-only rules, lab endpoints | — |
| `docs/adr/0006-ledger-concurrency-control.md` | `FOR NO KEY UPDATE` on debited accounts, read after lock, sorted lock order | — |
| `docs/chapters/02-accounts.md` | Chapter 2 textbook | Walkthrough of this slice |
| `docs/chapters/03-ledger.md` | Chapter 3 textbook: double entry, atomicity, the race, row locks, deadlock, load | Recorded evidence from the running service and tests |
| `docs/chapters/companion-under-the-hood.md` | Plain-text companion: running layers, bean wiring, transaction proxy, JPA/Hibernate/JDBC, Docker cache | Recorded evidence; re-record on Spring Boot upgrades |

## Request traces

**Create account** — `POST /api/v1/accounts {"displayName","currency"}` → Tomcat → `DispatcherServlet` → Jackson deserializes `CreateAccountRequest` (malformed JSON → 400) → `@Valid` Bean Validation (failure → 400 problem JSON, service never called) → `AccountController.open` → `AccountService.open` opens a transaction → `Account.open` generates UUID, strips name, timestamps from `Clock` → `JpaAccountRepository.add` → `EntityManager.persist` → commit flushes `INSERT INTO account` (DB `CHECK` is the last line of defense) → log `account opened accountId=… currency=USD` → `201 Created`, `Location: /api/v1/accounts/{id}`, body `AccountResponse`.

**Read account** — `GET /api/v1/accounts/{id}` → path variable converted to `UUID` (failure → 400) → `AccountService.get` (read-only transaction) → `EntityManager.find` → `AccountEntity.toDomain` → 200, or `AccountNotFoundException` → `AccountExceptionHandler` → 404 problem JSON.

**Read balance** — `GET /api/v1/accounts/{id}/balance` → `AccountService.balance` (read-only transaction) → loads account (404 if absent) → `LedgerService.balance` joins the transaction → `SELECT COALESCE(SUM(amount_minor), 0) FROM posting WHERE account_id = ?` → ledger = available (no holds yet) → `BalanceResponse` `{accountId, currency, ledgerMinor, availableMinor}`. The funding account's balance is negative.

**Fund / transfer (lab profile only)**:
1. `POST /lab/ledger/funding {accountId, amountMinor, currency}` or `POST /lab/ledger/transfers {fromAccountId, toAccountId, amountMinor, currency}`. Without the profile, both are 404.
2. Bean Validation: positive, at most $1,000,000, USD (otherwise 400).
3. `LedgerService.fund`/`transfer` opens a READ COMMITTED transaction.
4. `JournalEntry.move` builds −amount for the sender and +amount for the receiver. The sender of a funding entry is the funding account `…f00d`. Same account on both sides → 400.
5. Postings are visited in `UUID.compareTo` order:
   - **debit:** `SELECT currency, kind FROM account WHERE id = ? FOR NO KEY UPDATE` (waits while another debit of that account is in flight), then `SUM` of its postings, then `checkDebit` (customer below zero → `InsufficientFundsException`, rollback, 409);
   - **credit:** `SELECT currency, kind` (unknown → 404).
6. `persist` the entry and its postings, then log `journal entry posted`.
7. Commit: flush three INSERTs. Each posting's foreign key takes `FOR KEY SHARE` on its account. The deferred triggers verify the entry sums to 0. All rows become visible together, and the row lock is released.
8. 201 with signed postings.

**Capabilities** — `GET /api/v1/payments/capabilities` → `CapabilitiesController.capabilities()` → static JSON: `chapter: 3`, `ledgerEnabled: true`, `paymentProcessingEnabled: false`.

## Data lifecycle

Flyway applies `V1__baseline.sql`, `V2__accounts.sql` and `V3__ledger.sql` in order and records them in `flyway_schema_history`. V3 adds `account.kind` and inserts the funding account. Accounts are inserted once and never updated or deleted by the application. Journal entries and postings are append-only, and database triggers reject `UPDATE`, `DELETE` and `TRUNCATE`; corrections will be reversing entries. Balances are never stored. Display names are user-supplied and treated as personal data: they are stored and returned, but not logged. The ledger stores no free text.

## Architectural invariants

**Implemented:**
- money is integer minor units with explicit currency, and currency is USD in the API, domain and database;
- every journal entry has at least two postings summing to zero, checked in Java and again by PostgreSQL at commit, so `SELECT SUM(amount_minor) FROM posting` is always 0;
- money enters only through the funding account;
- postings are immutable;
- a customer balance never goes negative, even under concurrent debits: the debited account row is locked, then the balance is read (ADR 0006);
- schema changes only through Flyway, and Hibernate validates rather than generates the schema.

**Future requirements:**
- transfer acceptance and final settlement are separate concepts;
- repeated idempotent requests can't cause repeated debits. Neither `POST /api/v1/accounts` nor the lab transfer endpoint is idempotent yet: a retried request creates a second account or moves money twice;
- lock waits need a timeout;
- outbox events must be durable before publication;
- uncertainty about external transfers requires reconciliation;
- every request carries a correlation ID;
- the lab endpoints are removed before authentication (Chapter 5).

## Updating this map

For every new important class or file, add a row stating path, purpose, callers, dependencies, and failure modes where relevant. Update the request trace, data flow, implemented/not-implemented boundaries, and chapter cross-references. Remove obsolete entries immediately.
