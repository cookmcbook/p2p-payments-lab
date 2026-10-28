# ADR 0001 — Begin with a modular monolith

**Status:** Accepted

## Context

The project teaches enterprise payment engineering. Multiple services at the start would introduce network and deployment complexity before core ledger correctness is understood.

## Decision

Use one Spring Boot application in a monorepo, with clear API/application/domain/infrastructure boundaries as features grow. PostgreSQL is the initial durable store. Kafka will be introduced with the transactional outbox in a later chapter.

## Alternatives

Microservices from day one: realistic distribution, but premature operational complexity. In-memory only: quick, but poor training for transactions and migrations.

## Consequences

Fast local iteration and simple debugging. Later chapters must explicitly address boundaries and distributed failure modes; a monolith alone cannot simulate all interbank behavior.
