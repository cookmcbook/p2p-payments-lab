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
curl -X POST http://localhost:8080/api/v1/payments -H 'Content-Type: application/json' \
     -H 'X-Payer-Account-Id: <payer id>' -H 'Idempotency-Key: rent-2026-11' \
     -d '{"payeeAccountId":"<payee id>","amountMinor":300,"currency":"USD"}'
```

A payment needs money in the payer's account. The only way to issue simulated money is the local-only lab overlay, which enables `POST /lab/ledger/funding` (no authentication, never deploy it). With it you can also fire a burst of concurrent payments and check the ledger's invariants, or send one request 100 times with the same idempotency key:

```bash
docker compose -f compose.yaml -f infra/compose.lab.yaml up --build
node infra/lab/race.mjs --requests 100
node infra/lab/race.mjs --requests 100 --same-key
```

To watch what Spring, Hibernate and the transaction proxy do inside each request, use the local-only debug overlay: `docker compose -f compose.yaml -f infra/compose.debug.yaml up --build`. Add `-f infra/compose.lab.yaml` to combine it with the lab overlay. It logs SQL parameters, including names, so never use it outside your own machine. The book's Under the hood section explains the output.

## Test

```bash
cd apps/payment-api && mvn verify
```

Integration tests use Testcontainers and require a running Docker daemon. Without a local JDK, see [Chapter 2](docs/chapters/02-accounts.md#run-the-experiment) for running Maven in a container.

The system currently supports creating accounts, a double-entry ledger whose balances are derived from immutable postings, and a payment API with explicit lifecycle states, holds (so the available balance can be lower than the ledger balance) and idempotency keys. **There is no authentication yet**: the payer is whatever `X-Payer-Account-Id` says, so never expose the service. Subsequent chapters introduce authentication, then outbox events, a mock bank with reconciliation, and asynchronous processing.
