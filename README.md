# P2P Payments Lab

An educational, **fictional** Zelle-style payment platform. No connection to Zelle, Early Warning Services, Bank of America, or real funds.

## Start here

Open the **interactive textbook** at [docs/book/index.html](docs/book/index.html) in a browser. It has annotated code tours, animated request traces, playgrounds and quizzes. The plain-text chapters are in [docs/chapters/](docs/chapters/), and [the learning guide](docs/LEARNING_GUIDE.md) explains how to study. Follow [AGENTS.md](AGENTS.md) when working with a coding agent.

## Prerequisites

Docker Desktop and Docker Compose. Java 21 and Maven 3.9+ are optional for local development without Docker.

## Run

```bash
docker compose up --build
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/v1/payments/capabilities
curl -X POST http://localhost:8080/api/v1/accounts -H 'Content-Type: application/json' \
     -d '{"displayName":"Alice Example","currency":"USD"}'
curl http://localhost:8080/api/v1/accounts/<id>/balance
```

To move simulated money, start the local-only lab overlay, which enables `POST /lab/ledger/funding` and `/lab/ledger/transfers` (no authentication, never deploy it). Then fire a burst of concurrent transfers and check the ledger's invariants:

```bash
docker compose -f compose.yaml -f infra/compose.lab.yaml up --build
node infra/lab/race.mjs --requests 100
```

To watch what Spring, Hibernate and the transaction proxy do inside each request, use the local-only debug overlay: `docker compose -f compose.yaml -f infra/compose.debug.yaml up --build`. Add `-f infra/compose.lab.yaml` to combine it with the lab overlay. It logs SQL parameters, including names, so never use it outside your own machine. The book's Under the hood section explains the output.

## Test

```bash
cd apps/payment-api && mvn verify
```

Integration tests use Testcontainers and require a running Docker daemon. Without a local JDK, see [Chapter 2](docs/chapters/02-accounts.md#run-the-experiment) for running Maven in a container.

The system currently supports creating accounts, a double-entry ledger whose balances are derived from immutable postings, and lab-only endpoints that move simulated money safely under concurrency. **There is no payment API yet**: no authentication, no idempotency, no payment lifecycle. Subsequent chapters introduce those, then outbox events and asynchronous processing.
