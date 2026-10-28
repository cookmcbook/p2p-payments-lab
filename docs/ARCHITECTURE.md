# Architecture map (living document)

**Last updated:** Chapter 1 scaffold. **Implemented:** one Spring Boot application with PostgreSQL connectivity, Flyway baseline, two safe read-only endpoints, Docker build, Compose, CI. **Not implemented:** accounts, ledger, transfers, authentication, Kafka.

## System boundary

`HTTP client → payment-api (Spring MVC) → [future application/domain/persistence modules] → PostgreSQL`.

At startup, Flyway migrates PostgreSQL. Actuator serves health. No financial operations exist in this version.

## Important files and responsibilities

| File | Purpose | Runtime relationship |
| --- | --- | --- |
| `apps/payment-api/pom.xml` | Java 21, Spring Boot dependencies and test plugins | Maven builds executable JAR |
| `apps/payment-api/src/main/java/dev/p2plab/payments/PaymentApiApplication.java` | Spring Boot entry point | Starts component scan and embedded web server |
| `apps/payment-api/src/main/java/dev/p2plab/payments/api/CapabilitiesController.java` | Safe API discovery endpoint | Maps GET `/api/v1/payments/capabilities` to JSON; no DB writes |
| `apps/payment-api/src/main/resources/application.yml` | Server, datasource, Flyway, Actuator settings | Environment variables override database configuration |
| `apps/payment-api/src/main/resources/db/migration/V1__baseline.sql` | Versioned initial database migration | Flyway applies on startup; no account tables yet |
| `apps/payment-api/src/test/java/dev/p2plab/payments/api/CapabilitiesControllerTest.java` | Isolated MVC contract test | Checks capabilities response without starting DB |
| `apps/payment-api/Dockerfile` | Multi-stage Maven/JRE image | Builds JAR and runs as non-root |
| `compose.yaml` | Local API + PostgreSQL orchestration | Waits for DB readiness before starting API |
| `.github/workflows/ci.yml` | CI checks | Runs Maven tests and Docker image build on PR/push |
| `AGENTS.md` | Coding agent operating contract | Requires docs, tests, and commits with changes |
| `docs/adr/0001-modular-monolith.md` | Initial architecture rationale | Documents decision and alternatives |

## Request trace

`GET /api/v1/payments/capabilities` → embedded Tomcat → Spring MVC dispatcher → `CapabilitiesController.capabilities()` → JSON response. The response explicitly says payment processing is not yet available.

## Data lifecycle

PostgreSQL is a real dependency for the containerized application. `V1__baseline.sql` creates an educational schema marker. No balances, customers, or payments are persisted yet.

## Architectural invariants (future requirements)

All mock funds movements must balance; ledger entries are immutable; transfer acceptance and final settlement are separate concepts; repeated idempotent requests cannot cause repeated debits; outbox events must be durable before publication; external transfer uncertainty requires reconciliation.

## Updating this map

For every new important class or file, add a row stating path, purpose, callers, dependencies, and failure modes where relevant. Update the request trace, data flow, implemented/not-implemented boundaries, and chapter cross-references. Remove obsolete entries immediately.
