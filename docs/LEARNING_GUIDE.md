# P2P Payments Lab — living textbook

**Audience:** Java developer preparing for enterprise payments engineering. **Method:** one conceptual chapter, one tested code increment, one documented commit at a time.

## How to study

Use the [interactive textbook](book/index.html) for each chapter: step through the code tours, run the tracers and playgrounds, and finish the quiz. Then, for each chapter: read the mental model; trace the named files; run the system; predict the output; run the tests; deliberately trigger a failure; explain why it failed; only then ask the coding agent to advance. Use the exercises as checkpoints rather than copying implementations blindly.

## Learning outcomes

Understand HTTP request lifecycles, layered design, dependency injection, database migrations, ACID transactions, ledgers, optimistic/pessimistic locking, idempotency, authentication/authorization, distributed messaging, the transactional outbox, reconciliation, observability, CI/CD, and safe deployment.

## Rules for keeping this book current

The coding agent must update docs/ARCHITECTURE.md alongside any important code structure, update the relevant chapter with code paths and failure modes, and create an ADR when making a consequential design choice. Every completed chapter must correspond to one or more cohesive Git commits and include a testable exercise. The code is the source of truth; fix stale explanations in the same commit that changes behavior.

## Chapters

- [01 — Foundations and first runnable slice](chapters/01-foundations.md)
- [02 — Accounts, persistence, and a balance that cannot lie](chapters/02-accounts.md)
- [Companion — Under the hood: Spring, JPA/Hibernate and Docker](chapters/companion-under-the-hood.md) (read alongside 01–02; interactive at `book/index.html#hood`)

See [the roadmap](ROADMAP.md) for future chapters. Do not treat roadmap entries as implemented functionality.
