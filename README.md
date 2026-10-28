# P2P Payments Lab

An educational, **fictional** Zelle-style payment platform. No connection to Zelle, Early Warning Services, Bank of America, or real funds.

## Start here

Read [the learning guide](docs/LEARNING_GUIDE.md), then [Chapter 1](docs/chapters/01-foundations.md). Follow [AGENTS.md](AGENTS.md) when working with a coding agent.

## Prerequisites

Docker Desktop and Docker Compose. Java 21 and Maven 3.9+ are optional for local development without Docker.

## Run

```bash
docker compose up --build
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/v1/payments/capabilities
```

## Test

```bash
cd apps/payment-api && mvn verify
```

The initial vertical slice is deliberately small: a health endpoint and a capabilities endpoint. **It does not move money yet.** Subsequent chapters introduce ledger invariants, authentication, idempotency, outbox events, and asynchronous processing.
