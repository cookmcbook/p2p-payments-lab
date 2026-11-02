# Companion — Under the hood: Spring, JPA/Hibernate and Docker

Read alongside Chapters 1 and 2. The interactive version (`docs/book/index.html#hood`) animates everything below; this file is the plain-text reference. Every output here was recorded on 29 October 2026 from this repository, using `infra/compose.debug.yaml` ([ADR 0004](../adr/0004-under-the-hood-evidence.md)). Outputs are abridged.

## Learning objectives

- Name every process and layer that runs after `docker compose up`, and what each does.
- Explain that an annotation is a label that does nothing until something reads it.
- Describe how Spring builds beans in dependency order, and read its startup errors.
- Explain why the `AccountService` the controller calls is a generated proxy, and the consequences (commit/rollback, self-invocation).
- Say which of JPA, Hibernate, JDBC and HikariCP does what, and when SQL is actually sent.
- Predict which Docker build steps rerun after a change.

## Terminology

- **Bean:** an object created and managed by the Spring container, normally one per application, shared by all requests.
- **IoC container / dependency injection:** the container creates objects and passes collaborators into their constructors, instead of the objects creating them.
- **Auto-configuration:** Spring Boot configuration that applies only when its conditions hold, usually "this library is on the classpath".
- **Proxy:** an object that stands in for another and adds behavior around its calls.
- **JPA:** a specification (interfaces and annotations). **Hibernate:** an implementation of it. **JDBC:** Java's low-level database API. **HikariCP:** a connection pool.
- **Persistence context:** Hibernate's per-transaction map of tracked entities plus a queue of SQL still to send. **Flush:** sending that queue.
- **Image / layer / container / volume:** a read-only template made of filesystem layers; a process started from an image; storage that outlives containers.

## The running system, outside in

| Layer | What it is | Recorded evidence |
| --- | --- | --- |
| Docker Desktop | A Linux VM on Windows in which containers run | `payment-api Up 15 seconds`, `postgres Up 21 seconds (healthy)` |
| Compose network | Private network with DNS by service name | `getent hosts postgres` → `172.18.0.2 postgres` |
| payment-api container | Isolated Linux process from the image; Ubuntu userland | `id` → `uid=999(app)`; PID 1 is `java -jar /app/app.jar` |
| Fat JAR | Zip of our classes plus every library | 230 entries, 17 of our classes, 68 library JARs; `Main-Class: org.springframework.boot.loader.launch.JarLauncher`, `Start-Class: dev.p2plab.payments.PaymentApiApplication`; migrations at `BOOT-INF/classes/db/migration/` |
| Spring container | Registry of beans | 372 beans, 8 from our code at Chapter 2; 378 and 11 at Chapter 3 with the lab profile; 219 auto-configuration conditions matched, 296 did not (e.g. `KafkaAutoConfiguration: did not find required class 'org.springframework.kafka.core.KafkaTemplate'`) |
| Tomcat | Web server library; one worker thread per request | Every line of a request is tagged `[nio-8080-exec-N]` |
| HikariCP | Connection pool, default size 10 | `pg_stat_activity`: 10 idle `PostgreSQL JDBC Driver` connections |
| postgres container | PostgreSQL 16.15 | `\d account` shows `account_currency_check`, `account_display_name_check` |
| pgdata volume | Database files that survive `docker compose down` | `down -v` deletes them, and Flyway reruns V1 and V2 |

The difference between **beans** (`AccountController`, `AccountService`, `JpaAccountRepository`, `AccountExceptionHandler`, `CapabilitiesController`, `TimeConfig`, `Clock`: created once, stateless, shared) and **values** (`Account`, `Money`, the DTOs, `AccountEntity`: created per request) explains why one controller object can safely serve many threads.

## Annotations are labels

| Annotation | Read by | When | Effect |
| --- | --- | --- | --- |
| `@SpringBootApplication` | Spring Boot | Startup | Component scan + auto-configuration |
| `@RestController`, `@Service`, `@Repository`, `@Configuration` | Component scan | Startup | Register a bean |
| `@Bean` | Spring | Startup | Method result becomes a bean |
| `@Transactional` | Spring AOP | Startup, then each call | Bean is replaced by a transactional proxy |
| `@GetMapping`, `@PostMapping` | Spring MVC | Startup | Route table |
| `@Valid`, `@NotBlank`, `@Size`, `@Pattern` | Spring MVC + Hibernate Validator | Each request | 400 on failure |
| `@Entity`, `@Column` | Hibernate | Startup and each query | Class ↔ table mapping |

Consequence: `new AccountService(repo, clock).open(...)` in a unit test starts no transaction, because nothing read the label.

## Startup: beans in dependency order

Real order from the log: component scan → `Tomcat initialized with port 8080` (not yet listening) → `HikariPool-1 - Start completed` → Flyway `Schema "public" is up to date` → `Initialized JPA EntityManagerFactory` → our beans → `Tomcat started on port 8080` → `Started PaymentApiApplication in 3.35 seconds`. The pool starts before Flyway because `/actuator/beans` shows `entityManagerFactory` depending on `flywayInitializer`, which needs `flyway`, which needs `dataSource`.

Three failures, each recorded by breaking a copy of the code:

| Change | Recorded error | Where startup stopped |
| --- | --- | --- |
| Remove `@Configuration` from `TimeConfig` | `Parameter 1 of constructor in dev.p2plab.payments.account.application.AccountService required a bean of type 'java.time.Clock' that could not be found.` | After Flyway and Hibernate succeeded |
| Add a second `@Repository` implementing `AccountRepository` | `Parameter 0 of constructor in … AccountService required a single bean, but 2 were found: inMemoryAccountRepository … jpaAccountRepository` | Same place |
| `@Column(name = "name")` instead of `display_name` | `Schema-validation: missing column [name] in table [account]` | At Hibernate; our beans were never created |

With PostgreSQL stopped, the bottom of the cause chain is `java.net.UnknownHostException: postgres`: a stopped container's name leaves the network's DNS. Read Java cause chains from the bottom.

## The transaction proxy

`/actuator/beans` reports `accountService` as `AccountService$$SpringCGLIB$$0`: a subclass generated at startup that overrides each public method to begin a transaction, call the real object, then commit (normal return) or roll back (`RuntimeException` or `Error`; checked exceptions commit by default). The recorded log of one create:

```
[nio-8080-exec-5] JpaTransactionManager : Creating new transaction with name [...AccountService.open]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT
[nio-8080-exec-5] TransactionImpl       : begin
[nio-8080-exec-5] AccountService        : account opened accountId=4806a533-… currency=USD
[nio-8080-exec-5] JpaTransactionManager : Initiating transaction commit
[nio-8080-exec-5] TransactionImpl       : committing
[nio-8080-exec-5] SQL                   : insert into account (created_at,currency,display_name,id) values (?,?,?,?)
[nio-8080-exec-5] DispatcherServlet     : Completed 201 CREATED
```

The INSERT appears **after** `committing`: `persist` only queued it; Hibernate flushed at commit. Consequences: constraint violations surface at commit, after the method returned; logs can run ahead of the database; and the `?` placeholders mean values are never parsed as SQL.

**Self-invocation:** `balance()` calls `get(id)`, which is `this.get(id)` on the real object. The log contains one `Creating new transaction … balance` and no line for `get`. A `@Transactional` method called from inside its own class is not intercepted.

Since Chapter 3, `balance()` also calls `ledger.balance(...)`, which is a call on *another bean's* proxy. That call is intercepted and joins the open transaction. Re-recorded:

```
JpaTransactionManager : Found thread-bound EntityManager [SessionImpl(1255679877<open>)] for JPA transaction
JpaTransactionManager : Participating in existing transaction
TransactionInterceptor : Getting transaction for [dev.p2plab.payments.ledger.application.LedgerService.balance]
SQL : SELECT COALESCE(SUM(amount_minor), 0) FROM posting WHERE account_id = ?
```

**Privacy:** with `org.hibernate.orm.jdbc.bind=TRACE`, the log printed `binding parameter (3:VARCHAR) <- [Alice Example]`. The service never logs names, but debug logging did. That is why the overlay is local-only.

## Who sends the SQL

| Layer | Role | JAR |
| --- | --- | --- |
| JPA | Specification: `EntityManager`, `@Entity` | `jakarta.persistence-api-3.1.0` |
| Hibernate | Implementation: tracks entities, generates SQL | `hibernate-core-6.6.13.Final` |
| Spring ORM/TX | Opens an EntityManager per transaction, binds it to the thread | `spring-orm`, `spring-tx` 6.2.6 |
| JDBC | Java's low-level API | JDK `java.sql` |
| HikariCP | Pool | `HikariCP-5.1.0` |
| PostgreSQL driver | Speaks the wire protocol on port 5432 | `postgresql-42.7.5` |

`Found 0 JPA repository interfaces` in the startup log is Spring Data finding no `JpaRepository` interfaces: the repository is hand-written on purpose ([Chapter 2](02-accounts.md)).

## PostgreSQL: snapshots and row locks (Chapter 3)

**MVCC.** PostgreSQL keeps row versions tagged with the transaction that wrote them. Each statement reads a *snapshot*: the set of transactions committed when it started. Uncommitted rows are invisible to others, and readers never wait for writers. Under READ COMMITTED (the default) the snapshot is retaken for **every statement**, so a read that runs after a lock is granted sees whatever committed while you waited.

**Row locks** are written into the row header as "locked by transaction X". A conflicting request waits for X to end.

| Requested ↓ / Held → | `FOR UPDATE` | `FOR NO KEY UPDATE` | `FOR SHARE` | `FOR KEY SHARE` |
| --- | --- | --- | --- | --- |
| `FOR UPDATE` | waits | waits | waits | waits |
| `FOR NO KEY UPDATE` (our debit lock) | waits | waits | waits | ok |
| `FOR SHARE` | waits | waits | ok | ok |
| `FOR KEY SHARE` (every foreign key check) | waits | ok | ok | ok |

Two debits of one account conflict, which gives the queue we want. A credit's foreign key check doesn't conflict with a debit lock. With `FOR UPDATE` it would, and opposite-direction transfers deadlock, as reproduced in [Chapter 3](03-ledger.md).

Waiting sessions show `wait_event_type = 'Lock'`, `wait_event = 'transactionid'` in `pg_stat_activity`, and `pg_blocking_pids(pid)` names the blocker. A wait cycle is broken after `deadlock_timeout` (1 s) with SQLSTATE `40P01`.

## Docker layers and the build cache

`docker history` shows our Dockerfile adding `WORKDIR` (8.19 kB), the user (41 kB) and the JAR (56.6 MB) on top of the base's Java runtime (166 MB) and packages (58.2 MB); `USER`, `EXPOSE` and `ENTRYPOINT` are 0-byte settings. A step is reused only if its instruction, its copied files and every earlier step in the stage are unchanged. Recorded after adding one Java file:

```
[build 3/6] COPY pom.xml .                         CACHED
[build 4/6] RUN mvn -B -q dependency:go-offline    CACHED
[build 5/6] COPY src ./src                         DONE 0.0s
[build 6/6] RUN mvn -B -q -DskipTests package      DONE 4.8s
[stage-1 3/4] RUN groupadd … && useradd …          CACHED
[stage-1 4/4] COPY --from=build … /app/app.jar     DONE 0.1s
```

Copying `pom.xml` before `src` is what keeps the dependency download cached; a naive `COPY . .` would re-download on every source change.

## Run the experiment

```bash
docker compose -f compose.yaml -f infra/compose.debug.yaml up --build -d
# create an account (on Windows PowerShell, put the JSON in a file and use --data-binary @file)
docker compose logs payment-api --tail 30
docker compose exec payment-api id
docker compose exec payment-api getent hosts postgres
docker history p2p-payments-lab-payment-api
docker compose down && docker compose up -d   # back to normal logging
```

## Failure scenarios

| Scenario | Observation |
| --- | --- |
| Missing bean | `APPLICATION FAILED TO START … required a bean of type 'java.time.Clock' that could not be found` |
| Two candidate beans | `required a single bean, but 2 were found` |
| Entity/table mismatch | `Schema-validation: missing column [name] in table [account]` |
| Database container stopped | Bottom cause `UnknownHostException: postgres`; container `Exited (1)` |
| `@Transactional` on a private method, or called via `this` | Silently not transactional |
| Debug overlay used outside a laptop | Display names in logs |

## Exercises

1. Remove `@Configuration` from `TimeConfig` and start. **Expect:** the missing-`Clock` error; Flyway already ran.
2. Add a second `@Repository` implementation. **Expect:** "required a single bean, but 2 were found".
3. With the overlay, create an account. **Expect:** `insert into account` after `account opened` and after `committing`.
4. With the overlay, read a balance. **Expect:** one `Creating new transaction` line, and (since Chapter 3) one `Participating in existing transaction` for `LedgerService.balance`.
5. Stop `postgres`, restart `payment-api`. **Expect:** bottom cause `UnknownHostException: postgres`.
6. `docker compose down`, then `up`: accounts remain. `down -v`: they are gone.

## Review questions

1. Who reads `@Transactional`, and when?
2. Why does Flyway run before Hibernate validates the schema?
3. What is `jpaSharedEM_AWC_entityManagerFactory`, and why can one repository bean serve concurrent requests?
4. When is the INSERT sent, and what does that imply for where a constraint violation is thrown?
5. Why is `postgres`, not `localhost`, in `DB_URL`?
6. Which Dockerfile steps rerun after a `pom.xml` change, and why is that slow?
