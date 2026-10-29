# ADR 0004 — Teach framework internals from recorded evidence, using a local debug overlay

**Status:** Accepted

## Context

Learners new to Java, Spring and Docker found the book's one-line descriptions ("the proxy begins a transaction", "Hibernate flushes at commit") hard to trust or remember. They wanted to see what the frameworks actually do. Explaining internals from memory risks teaching folklore. While preparing this material, the real logs showed details the book had flattened: Tomcat is created *before* Flyway runs and only starts listening at the end; with PostgreSQL stopped, the root cause is `UnknownHostException: postgres`, below the Flyway error the book quoted. They also showed that the Chapter 2 tracer's log lines were paraphrases (`BEGIN`, `SELECT ... → 0 rows`) rather than real output.

Seeing internals needs extra logging and actuator endpoints that must never be enabled outside a developer's machine: `org.hibernate.orm.jdbc.bind=TRACE` prints every SQL parameter, including display names, and `/actuator/beans` describes the application's internals.

## Decision

1. Add `infra/compose.debug.yaml`, a Compose overlay used as `docker compose -f compose.yaml -f infra/compose.debug.yaml up`. It raises log levels for Spring MVC, the transaction infrastructure and Hibernate SQL/binding, and exposes the `beans`, `mappings` and `conditions` actuator endpoints. It is not referenced by `compose.yaml`, CI or the Dockerfile, and its header says it is for local use only.
2. Log levels are set through `SPRING_APPLICATION_JSON`, not `LOGGING_LEVEL_*` environment variables. Spring lowercases logger names from environment variables, which silently misses class-named loggers such as `JpaTransactionManager`.
3. Add an **Under the hood** companion view to the textbook (`#hood`) and a plain-text companion chapter. Every log line, error message and number in them is recorded from the running service with the overlay, or from a deliberately broken *copy* of the code (missing bean, duplicate bean, mismatched entity), never written from memory. Outputs may be abridged and must say so.
4. Tracer steps gain an optional `deep` field, shown in an "Under the hood" panel, so each chapter can offer depth without lengthening the main path.

## Alternatives

- **Describe internals in prose only:** cheaper, but unverifiable, and it already produced inaccuracies.
- **Turn on debug logging in `application.yml` under a `debug` Spring profile:** keeps everything in one file, but ships the PII-leaking configuration inside the production JAR, one environment variable away from being enabled.
- **Attach a debugger and screenshot breakpoints:** shows the most, but can't be reproduced with one command and depends on an IDE.

## Consequences

- Readers can reproduce every recorded output with one command.
- Recorded output goes stale when dependencies change (for example, Hibernate's generated aliases, or the bean count). Re-record when upgrading Spring Boot, and the book states the recording date.
- The overlay leaks display names into logs by design. It is documented as local-only; real deployments (Chapter 9) must not include it.
