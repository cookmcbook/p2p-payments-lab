# Chapter 1 — From empty repository to runnable service

## Learning objectives

Understand a monorepo, a Spring Boot entry point, the difference between API readiness and business readiness, database migrations, container build stages, and CI checks.

## Mental model

An HTTP API is a process that listens for requests, maps them to methods, and returns responses. Spring Boot wires this process together. Docker packages the application with its runtime. Compose starts the application alongside PostgreSQL. Flyway version-controls schema changes. CI independently checks whether code can be built and tested.

## Code reading route

Start at `PaymentApiApplication.java`: `@SpringBootApplication` enables auto-configuration and component scanning. Open `CapabilitiesController.java`: `@RestController` exposes a JSON response, and `@GetMapping` binds a URL to a method. Read `application.yml`: environment variables provide connection settings. Inspect `V1__baseline.sql`: Flyway applies this migration once per database. Read the `Dockerfile` from top to bottom: a build stage produces a JAR; a separate runtime stage runs it with fewer tools and privileges. Finally, inspect `ci.yml` to see what a pull request must pass.

## Run the experiment

Run `docker compose up --build`. Then request `/actuator/health` and `/api/v1/payments/capabilities`. The latter should report `paymentProcessingEnabled: false`. Try POST `/api/v1/payments` and observe that no transfer endpoint exists. This is intentional: scaffolding is not payment processing.

## Failure laboratory

Stop the PostgreSQL container and restart the API. Observe datasource/Flyway startup failure. Explain why a container image building successfully is not the same as the service starting successfully. Restore the database and confirm health returns.

## Exercises

Explain the difference between a Dockerfile and Compose. Identify which file controls the database schema. Change the capabilities response to include a new safe field, update its test and `docs/ARCHITECTURE.md`, run tests, and commit the change. Do not implement money movement yet.

## Review questions

What happens before the first HTTP request? Why does a migration need a version? Why use separate build and runtime stages? What is the risk of marking a nonfunctional payments endpoint as ready?

## Chapter completion evidence

Run the container stack and Maven tests in an environment with Docker/Maven. Record exact results and commit SHA in your own work log. The initial scaffold was generated without claiming these runtime checks passed.
