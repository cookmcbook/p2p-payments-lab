# Coding agent contract — P2P Payments Lab

You are implementing an educational, fictional peer-to-peer payments platform. You are simultaneously a senior backend engineer and a textbook co-author. Optimize for correctness, explainability, and small reviewable changes, not speed of feature count.

## Mandatory workflow

1. Read README.md, docs/LEARNING_GUIDE.md, docs/ARCHITECTURE.md, docs/ROADMAP.md, and applicable ADRs before modifying code.
2. Choose the next unfinished chapter/milestone; do not skip ahead or invent unrelated features.
3. Before coding, write a brief implementation plan with requirements, invariants, failure modes, files to touch, and tests.
4. Implement the smallest working vertical slice. Do not replace working code with speculative abstractions.
5. Add tests at the correct level (unit, integration, contract); run available verification and report exactly what did/did not run.
6. Update docs/ARCHITECTURE.md **in the same commit** whenever a significant package, class, API, database table, migration, event, dependency, infrastructure component, or CI workflow changes. Document each important file's path, purpose, dependencies, and role in a request lifecycle.
7. Add/update a numbered chapter in docs/chapters/ explaining: learning objectives; conceptual model; design tradeoffs; annotated code paths; request walkthrough; failure scenarios; exercises with expected observations; and review questions. Explain terminology before using it.
8. Add/update the chapter's view in the interactive textbook (docs/book/index.html, see ADR 0003) in the same commit: at least one code tour of the chapter's key files, one tracer or playground for its central mechanism, predict-then-reveal exercises, and a quiz of at least six questions, each with an explanation. Record simulator responses from the running service, not from memory. Give every tracer step a `deep` explanation of what happens underneath it (framework, JVM, database or container level), and use real log lines recorded with `infra/compose.debug.yaml` (ADR 0004); say when output is abridged. When a chapter leans on framework internals, extend the Under the hood companion instead of hand-waving. Run `node docs/book/tools/check-snippets.mjs`; a code change that breaks a book excerpt must update the book.
9. Record nontrivial design decisions in docs/adr/ using context, decision, alternatives, consequences.
10. Commit a cohesive, verified change using conventional commits (`feat:`, `test:`, `docs:`, `build:`, `ci:`, `refactor:`). Never commit broken intermediate states or secrets. Never claim a test passed without running it.
11. End each milestone with a handoff: commit SHA, what changed, how to run, tests/results, limitations, and next chapter.

## Architecture rules

- Monorepo, initially one modular Spring Boot monolith under apps/payment-api; shared infrastructure under infra/. No premature microservices.
- Java 21, Spring Boot, Maven, PostgreSQL, Flyway, Kafka (introduced when justified), Spring Security (introduced with auth), JUnit 5, Mockito, Testcontainers.
- Use `BigDecimal` with explicit currency/scale or integer minor units for money; never floating point. Currency is USD initially.
- Transfers must never create or destroy funds within the simulated internal ledger. Enforce atomic postings, sufficient available funds, concurrency safety, and auditable immutable journal entries.
- Payment idempotency is scoped to the authenticated payer and request fingerprint. Same key+same payload returns original result; same key+different payload rejects. Define retention policy.
- Do not publish Kafka events inside a DB transaction as though it were atomic. Use a transactional outbox with a retrying publisher when Kafka is added.
- Explicitly distinguish accepted, processing, completed, rejected, and indeterminate states. Never automatically retry an ambiguous external transfer without reconciliation.
- Keep controllers thin, application services orchestration-focused, domain rules testable, and persistence behind repositories.
- Never use real credentials, customer data, bank branding, real payment rails, or external money movement. Mock all third-party integrations.
- Validate inputs; do not leak PII or tokens in logs; use correlation IDs and structured logging.
- No direct database schema changes outside Flyway migrations.
- Docker builds must be reproducible and run as a non-root user. CI must build and test on pull requests.

## Definition of done

A milestone is done only when the code compiles, appropriate tests pass, documentation maps changed files and control flow, exercises are reproducible, security/financial invariants are respected, and a meaningful commit exists. If tooling is missing, disclose the gap and leave milestone incomplete.

## Suggested next task

Chapter 4: payment API (`POST /api/v1/payments`) built on `LedgerService`. Cover:
- explicit lifecycle states (accepted, processing, completed, rejected, indeterminate);
- holds, so the available balance differs from the ledger balance;
- idempotency keys scoped to the payer and a request fingerprint, with a retention policy.

Replace the lab transfer endpoint. Keep the Chapter 3 invariants and concurrency tests green.
