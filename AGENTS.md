# Working Guidelines for digital-bank

## Communication and Scope

- Respond in the user's requested language; otherwise follow the language used in the current conversation.
- Complete work within the requested scope and preserve unrelated working-tree changes.
- This file defines repository-specific context and decisions. Detailed engineering guidance lives in the skill below. Explicit user instructions and applicable directory-specific instructions take precedence over skill defaults.
- Distinguish the current implementation from conventions for new code. Do not upgrade versions, switch runtimes, reorganize unrelated packages, or migrate data merely to match a template.

## Using the Convention Skill

Read [spring-boot-conventions/SKILL.md](.agents/skills/spring-boot-conventions/SKILL.md) before writing, modifying, reviewing, or testing Java/Spring code, designing features/APIs, or changing POMs and Spring configuration. Then read only the references relevant to the task; do not load the entire collection by default.

| Task | Reference |
|---|---|
| New services, data ownership, packages, inter-service communication | [01 Architecture](.agents/skills/spring-boot-conventions/references/01-architecture.md) |
| Java, constructor injection, Lombok, nullness, money and time | [02 Java style](.agents/skills/spring-boot-conventions/references/02-java-style.md) |
| Controller, DTO, REST, validation, pagination, HTTP client | [03 REST API](.agents/skills/spring-boot-conventions/references/03-rest-api.md) |
| Service interface/implementation, transaction, event, remote orchestration | [04 Service layer](.agents/skills/spring-boot-conventions/references/04-service-layer.md) |
| Entity, repository, SQL, fetch plan, Flyway | [05 Persistence](.agents/skills/spring-boot-conventions/references/05-persistence-jpa.md) |
| Exceptions, ProblemDetail, logging and tracing context | [06 Exceptions and logging](.agents/skills/spring-boot-conventions/references/06-exceptions-and-logging.md) |
| Properties, JWT, authorization, servlet/reactive security | [07 Configuration and security](.agents/skills/spring-boot-conventions/references/07-config-and-security.md) |
| Unit/slice/integration test, Testcontainers, ArchUnit | [08 Testing](.agents/skills/spring-boot-conventions/references/08-testing.md) |
| Actuator, metrics, timeout, pool, shutdown, container | [09 Operations](.agents/skills/spring-boot-conventions/references/09-observability-and-ops.md) |
| Maven, dependencies/BOMs, Failsafe, quality tools, CI and Git | [10 Build and quality](.agents/skills/spring-boot-conventions/references/10-build-and-quality.md) |
| Version-specific Spring Boot APIs and imports | [11 Version notes](.agents/skills/spring-boot-conventions/references/11-spring-boot-4-notes.md) |
| Reviewing changes and checking completion | [12 Review checklist](.agents/skills/spring-boot-conventions/references/12-code-review-checklist.md) |
| New classes/tests or MVC, WebFlux and orchestration skeletons | [13 Code templates](.agents/skills/spring-boot-conventions/references/13-code-templates.md) |

Templates are starting points: adapt packages, complete domain types/fixtures/configuration, and verify dependencies/imports against the actual service before use. Documentation examples are not installed repository code.

## Repository Map

This banking learning project contains six independent Maven services, with no root Maven aggregator or frontend.

| Service | Root package | Current runtime/dependencies | Default port |
|---|---|---|---|
| `gateway-service` | `thitkho.gatewayservice` | Spring Cloud Gateway WebFlux | 3000 |
| `core-banking-service` | `thitkho.corebankingservice` | MVC, JPA, PostgreSQL, Flyway, Kafka, Security | 4000 |
| `customer-service` | `thitkho.customerservice` | MVC, JPA, PostgreSQL, Flyway, Kafka | 4001 |
| `notification-service` | `thitkho.notificationservice` | MVC, PostgreSQL, Flyway, Kafka; mostly a skeleton | 4002 |
| `fraud-detection-service` | `thitkho.frauddetectionservice` | MVC, PostgreSQL, Flyway, Kafka; mostly a skeleton | 4003 |
| `analytics-service` | `thitkho.analyticsservice` | MVC, PostgreSQL, Flyway, Kafka; mostly a skeleton | 4004 |

- Java sources: `<service>/src/main/java/thitkho/`; configuration: `src/main/resources/`; tests: `src/test/java/`.
- Migrations, where implemented: `<service>/src/main/resources/db/migration/`.
- Use JDK 25 and each service's Maven Wrapper. Its `pom.xml` and runtime configuration define versions, dependencies and ports; do not assume all services use the same Boot/Cloud versions.
- Root Docker Compose starts PostgreSQL, Kafka and Kafka UI infrastructure only. Run Java applications separately.

## Workflow for Each Change

1. Check `git status --short`, identify affected services, and read applicable directory instructions.
2. Inspect the POM, relevant configuration and nearby classes/tests. Identify the actual MVC/WebFlux runtime, persistence, authentication and test runners.
3. Read the skill entrypoint and relevant references above. For multi-service flows, establish data ownership, HTTP/event contracts and partial-failure handling before editing.
4. Apply conventions to new or changed code while preserving compatibility with existing code/APIs/schemas. Handle and explain any required contract or migration changes.
5. Run appropriate checks in affected services, then review against checklist 12 and the skill's Definition of Done. Claim only checks that actually ran.

## Architecture and Packages

- Each service owns its data, migrations and DTOs. Never use another service's repositories/entities or directly query tables it owns.
- Declare application static constants in dedicated classes under each service's `common.constant` package, with immutable values and private constructors. Do not declare static fields in application components. Keep environment-specific settings in configuration properties.
- Organize new features within the service's root package, using `web`, `service`, `service.impl`, `domain`, `repository`, `mapper` and `integration` as needed. Do not create empty packages just to match a template.
- DTO records belong in `<root>.common.dto.<feature>`; generic wrappers in `<root>.common.dto`; external wire DTOs in `<root>.common.dto.external.<provider>`. Each service owns its `common` package; do not introduce a system-wide business DTO/entity jar.
- Public feature services use `XxxService` interfaces and `service.impl.XxxServiceImpl` implementations. Controllers depend on interfaces; services own business logic and transactions; repositories own persistence. Never expose entities through APIs.
- Use MapStruct when configured; declare its dependency and annotation processor when adding it. Use an explicit mapper when no new dependency is needed, as allowed by the skill.
- Core currently uses layer-based packages such as `model`, `repository`, `service`, `dto` and `common.exception`. Preserve unrelated packages; reorganize only within the task's scope and update all affected callers.
- Preserve the gateway's reactive pipelines and header sanitization. Use WebClient/reactive adapters for outbound I/O; no blocking JPA/HTTP, `block()` or manual `subscribe()` on event-loop paths. Apply blocking guidance to MVC backends.

## Banking-Specific Rules

- Money uses `BigDecimal` plus a currency, persisted as `NUMERIC`/`DECIMAL` with explicit precision, scale and rounding. Never use `double`/`float`.
- Core still stores balances as `long`/`BIGINT` minor units. Conversion requires a forward migration, documented per-currency conversion factors, data verification and API contract checks. Never silently change types or reinterpret balances.
- Name new migrations `V<version>__<description>.sql`, choosing an unused version consistent with the service's history. Applied migrations are immutable; Hibernate `update/create` is not a substitute for migrations.
- Transactions cover local persistence work. Keep remote HTTP and broker publication outside DB transactions; use orchestration, outbox/inbox and compensation when the actual flow requires them.
- Replayable payment/transfer flows need explicit idempotency, concurrency control and appropriate duplicate/rollback tests before completion.

## Security and Configuration

- Keep secrets, tokens and personal data out of source, logs and error messages. Use validated, grouped `@ConfigurationProperties` for new settings.
- Preserve gateway sanitization of client-supplied internal/identity headers. Backends independently authenticate users/services and enforce ownership; request headers or owner IDs are not proof of authorization.
- Derive ownership for self-service creation from validated identity or a trusted mapping. Acting on behalf of another owner requires explicit permission and service-level checks.
- Verify downstream URLs/ports, issuer, audience and JWT settings across participating services before integration tests; do not assume existing defaults are aligned.
- Select security chains and error handlers for the actual MVC/WebFlux runtime. New APIs follow the skill's `ProblemDetail` contract with a stable `code`; preserve compatibility when changing existing APIs.

## Build, Test and Verification

Start infrastructure from the repository root when needed, then select the affected service:

```powershell
docker compose up -d
Set-Location core-banking-service
.\mvnw.cmd -DskipTests compile
.\mvnw.cmd test
.\mvnw.cmd "-Dtest=AccountSchemaTest" test
.\mvnw.cmd spring-boot:run
```

On Linux/macOS use `./mvnw`. Compilation with `-DskipTests` does not replace running tests for behavior changes.

- Use JUnit Jupiter, meaningful behavioral assertions and appropriate test levels from reference 08. Bug fixes need regression tests; comment-only tests do not establish coverage.
- Name unit/slice tests `*Test` or `*Tests`. `AccountSchemaTest` uses PostgreSQL Testcontainers and requires Docker; other context tests may need their own infrastructure/configuration.
- Service POMs currently do not declare Failsafe. Before using `*IT`, configure both `integration-test` and `verify` goals as described in reference 10. Run `.\mvnw.cmd verify` and inspect actual test counts/reports. `test` alone does not discover these `*IT` classes under this convention.
- Run formatters, ArchUnit, JaCoCo and static analysis when configured. No repository-wide formatter/lint or coverage threshold is currently defined. Never claim absent/unrun checks passed or introduce a full tooling suite for a small change.
- Report pre-existing compilation/test failures, regressions and checks blocked by missing JDK/Docker/dependencies/configuration separately. For documentation-only changes, verify links, content and `git diff --check`; starting the full system is unnecessary.

## Commits, Pull Requests and Completion Reports

- Keep commits focused, with descriptive imperative subjects matching repository history, such as `Initialize customer service with PostgreSQL and Kafka configurations`. Skill examples do not require switching to Conventional Commits.
- PRs describe the problem/resulting behavior, affected services, migration/configuration/API impact, verification commands/results and related issues when applicable.
- Do not commit secrets, generated files, `target/`, IDE settings or unrelated changes.
- Finish with a concise response in the conversation's chosen language: what changed and why, how it was verified, and any remaining limitations or required work. Link files when useful for review.
