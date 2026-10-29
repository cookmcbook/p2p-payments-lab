# Architecture map (living document)

**Last updated:** Chapter 2 (accounts). **Implemented:** one Spring Boot application with PostgreSQL persistence, Flyway migrations V1–V2, account creation and read-only account/balance endpoints, RFC 9457 problem responses, Testcontainers integration tests, Docker build, Compose, CI. **Not implemented:** ledger/postings (so all balances are zero), transfers, idempotency, authentication, correlation IDs, Kafka.

## System boundary

`HTTP client → payment-api (Spring MVC) → account module (api → application → domain ← persistence) → PostgreSQL`.

At startup, Flyway migrates PostgreSQL, then Hibernate *validates* (never generates) that JPA mappings match the migrated schema. Actuator serves health. No money moves in this version.

## Module layout

Packages under `apps/payment-api/src/main/java/dev/p2plab/payments/`. Dependencies point inward: `api → application → domain`; `persistence` implements a `domain` interface. `domain` imports no Spring or JPA types.

| Package | Layer | Rule |
| --- | --- | --- |
| `account.api` | HTTP adapter | Validate and translate DTOs; no business rules, no repositories |
| `account.application` | Use cases | Own transaction boundaries; orchestrate domain + repository |
| `account.domain` | Domain | Invariants in plain Java; unit-testable without Spring |
| `account.persistence` | Infrastructure | JPA mapping; package-private so only the port is visible |
| `money` | Shared domain | Currency and amount value types reused by the future ledger |
| `config` | Infrastructure | Cross-cutting beans (clock) |

## Important files and responsibilities

| File | Purpose | Runtime relationship / failure modes |
| --- | --- | --- |
| `apps/payment-api/pom.xml` | Java 21, Spring Boot 3.4.5, Testcontainers 1.21.4 (overridden for Docker Engine 29+ compatibility) | Maven builds executable JAR; `mvn verify` runs all tests |
| `.../PaymentApiApplication.java` | Spring Boot entry point | Starts component scan and embedded web server |
| `.../api/CapabilitiesController.java` | Safe discovery endpoint | GET `/api/v1/payments/capabilities`; reports `chapter: 2`, `accountsEnabled: true`, `paymentProcessingEnabled: false` |
| `.../config/TimeConfig.java` | Provides UTC `Clock` bean | Injected into `AccountService` so tests can fix time |
| `.../money/Currency.java` | Supported currencies (`USD` only) | Used by domain, entity mapping and API conversion |
| `.../money/Money.java` | Amount in integer minor units + currency ([ADR 0002](adr/0002-money-and-balance-representation.md)) | Used by `AccountBalance`; never floating point |
| `.../account/domain/Account.java` | Account record and invariants: non-blank display name ≤ 80 chars (stripped), currency required, creation time truncated to microseconds | Created by `Account.open`; rehydrated by `AccountEntity.toDomain`; throws `IllegalArgumentException`/`NullPointerException` on invalid state |
| `.../account/domain/AccountRepository.java` | Persistence port: `add`, `findById` | Called by `AccountService`; implemented by `JpaAccountRepository` |
| `.../account/application/AccountService.java` | Use cases `open`, `get`, `balance`; `@Transactional` boundaries; logs account id only | Called by `AccountController`; throws `AccountNotFoundException`; balance is zero until Chapter 3 |
| `.../account/application/AccountBalance.java` | Balance read model: `ledger` and `available` `Money` | Returned by `AccountService.balance` |
| `.../account/application/AccountNotFoundException.java` | Unknown account id | Mapped to 404 by `AccountExceptionHandler` |
| `.../account/persistence/AccountEntity.java` | JPA mapping of `account` table | Package-private; converts to/from domain `Account` |
| `.../account/persistence/JpaAccountRepository.java` | `EntityManager`-based port implementation (`persist`, `find`) | Requires caller's transaction; DB constraint violations surface as `DataIntegrityViolationException` |
| `.../account/api/AccountController.java` | `POST /api/v1/accounts`, `GET /api/v1/accounts/{id}`, `GET /api/v1/accounts/{id}/balance` | Bean Validation → 400; malformed UUID → 400; no list endpoint (enumeration risk without auth) |
| `.../account/api/CreateAccountRequest.java` | Request DTO with `@NotBlank @Size(max=80)` name and `@Pattern("USD")` currency | Rejected before the service is called |
| `.../account/api/AccountResponse.java`, `BalanceResponse.java` | Response DTOs; balances as `ledgerMinor`/`availableMinor` integers | Decouple JSON shape from domain types |
| `.../account/api/AccountExceptionHandler.java` | Maps `AccountNotFoundException` → 404 `ProblemDetail` | Scoped to `AccountController` |
| `apps/payment-api/src/main/resources/application.yml` | Datasource, Flyway, `ddl-auto: validate`, `spring.mvc.problemdetails.enabled`, Actuator | Env vars override DB settings |
| `.../db/migration/V1__baseline.sql` | Chapter 1 schema marker | Applied once by Flyway |
| `.../db/migration/V2__accounts.sql` | `account` table: `id UUID PK`, `display_name VARCHAR(80)` non-blank `CHECK`, `currency VARCHAR(3) CHECK = 'USD'`, `created_at TIMESTAMPTZ`; **no balance column** | Applied on startup; constraints defend invariants even if application code is bypassed |
| `.../test/.../account/domain/AccountTest.java` | Unit tests of domain invariants | No Spring context |
| `.../test/.../account/api/AccountControllerTest.java` | `@WebMvcTest` contract tests with mocked service | Status codes, validation, problem JSON, response shape |
| `.../test/.../account/AccountPersistenceIntegrationTest.java` | Full stack over real PostgreSQL 16 via Testcontainers | Requires Docker; verifies Flyway V2, round trip, zero balance, DB `CHECK` constraints |
| `.../test/.../api/CapabilitiesControllerTest.java` | Capabilities contract | No DB |
| `apps/payment-api/Dockerfile` | Multi-stage Maven/JRE image | Builds JAR (tests skipped; CI runs them) and runs as non-root |
| `compose.yaml` | Local API + PostgreSQL 16 | Waits for DB health before starting API |
| `.github/workflows/ci.yml` | CI | `mvn verify` (GitHub runners provide Docker for Testcontainers) and Docker image build |
| `docs/adr/0001-modular-monolith.md` | Monolith rationale | — |
| `docs/adr/0002-money-and-balance-representation.md` | Minor units; derived balances; no seed funds | — |
| `docs/chapters/02-accounts.md` | Chapter 2 textbook | Walkthrough of this slice |

## Request traces

**Create account** — `POST /api/v1/accounts {"displayName","currency"}` → Tomcat → `DispatcherServlet` → Jackson deserializes `CreateAccountRequest` (malformed JSON → 400) → `@Valid` Bean Validation (failure → 400 problem JSON, service never called) → `AccountController.open` → `AccountService.open` opens a transaction → `Account.open` generates UUID, strips name, timestamps from `Clock` → `JpaAccountRepository.add` → `EntityManager.persist` → commit flushes `INSERT INTO account` (DB `CHECK` is the last line of defense) → log `account opened accountId=… currency=USD` → `201 Created`, `Location: /api/v1/accounts/{id}`, body `AccountResponse`.

**Read account** — `GET /api/v1/accounts/{id}` → path variable converted to `UUID` (failure → 400) → `AccountService.get` (read-only transaction) → `EntityManager.find` → `AccountEntity.toDomain` → 200, or `AccountNotFoundException` → `AccountExceptionHandler` → 404 problem JSON.

**Read balance** — `GET /api/v1/accounts/{id}/balance` → `AccountService.balance` → loads account (404 if absent) → returns `Money.zero(currency)` for ledger and available → `BalanceResponse` `{accountId, currency, ledgerMinor: 0, availableMinor: 0}`.

**Capabilities** — `GET /api/v1/payments/capabilities` → `CapabilitiesController.capabilities()` → static JSON; payment processing remains disabled.

## Data lifecycle

Flyway applies `V1__baseline.sql` and `V2__accounts.sql` in order and records them in `flyway_schema_history`. Accounts are inserted once and never updated or deleted by the application. No balances, postings or payments are persisted. Display names are user-supplied and treated as personal data: they are stored and returned, but not logged.

## Architectural invariants

Implemented: money is integer minor units with explicit currency; currency is USD in API, domain and DB; no code path creates funds (balances are derived and currently zero); schema changes only through Flyway; Hibernate validates rather than generates schema.

Future requirements: all mock funds movements must balance; ledger entries are immutable; transfer acceptance and final settlement are separate concepts; repeated idempotent requests cannot cause repeated debits (note: `POST /api/v1/accounts` is **not** idempotent yet — a retried request creates a second account); outbox events must be durable before publication; external transfer uncertainty requires reconciliation; correlation IDs on every request.

## Updating this map

For every new important class or file, add a row stating path, purpose, callers, dependencies, and failure modes where relevant. Update the request trace, data flow, implemented/not-implemented boundaries, and chapter cross-references. Remove obsolete entries immediately.
