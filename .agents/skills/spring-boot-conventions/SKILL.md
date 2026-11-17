---
name: spring-boot-conventions
description: Apply Java and Spring Boot engineering conventions when implementing, reviewing, refactoring, or testing services, gateways, persistence, security, APIs, or Maven/Gradle builds. Supports microservice systems and modular monoliths, with separate guidance for MVC/JPA and WebFlux. Read before generating Spring code; preserve the project's versions and deployment boundaries.
---

# Spring Boot Conventions

Reusable conventions applied within each deployable application. Explicit user decisions and repository instructions take precedence. Adopting this skill does not imply an architecture migration or an unrequested tooling rollout.

## Workflow

1. **Detect context.** Read repository instructions and the affected service's build/configuration. Identify Java/Boot/Cloud versions, deployment boundaries, MVC versus WebFlux, storage, authentication, messaging, wrappers, test runners and installed quality tools. Skim nearby classes. Do not assume a root Maven aggregator exists.
2. **Choose the runtime.** Use the table below. Read [architecture](references/01-architecture.md) for a new service/boundary change, then only the relevant references. Use [templates](references/13-code-templates.md) for the matching runtime.
3. **Implement within scope.** Preserve unrelated work. Do not upgrade Java/Boot, migrate existing schemas/contracts, or add infrastructure merely to satisfy a default. Identify necessary dependency/plugin changes explicitly.
4. **Verify in the affected service.** On Windows use `.\mvnw.cmd`; on Unix use `./mvnw`. Run targeted tests during development and `verify` for a Maven service with configured integration tests; use the project's Gradle tasks otherwise. Confirm discovery and report test results/counts. Run configured formatting/quality checks. Report unavailable checks and pre-existing failures separately; never claim an unrun check passed.
5. **Review the applicable Definition of Done.** Mark irrelevant checks as not applicable. Explain deliberate technical exceptions when the explanation helps future maintainers.

## Baseline and runtime selection

For new services, prefer Java 21+ (25 LTS when supported), Spring Boot 4.x, JSON REST and RFC 9457 `ProblemDetail`. Existing services keep their declared versions. Verify APIs against the target release; see [version notes](references/11-spring-boot-4-notes.md).

| Service type | Web / clients | Persistence / transactions | Tests |
|---|---|---|---|
| Blocking business API | MVC; `RestClient` or blocking `@HttpExchange` | JPA/JDBC if selected; `PlatformTransactionManager` | MockMvcTester, persistence slices, Testcontainers |
| Reactive business API | WebFlux; `WebClient` or reactive `@HttpExchange` | Reactive driver if selected; `ReactiveTransactionManager` / `TransactionalOperator` | WebTestClient, StepVerifier, Testcontainers |
| Reactive gateway | Cloud Gateway WebFlux; non-blocking filters | Usually no business persistence | Routing, header sanitization, auth and upstream failure tests |
| Consumer / worker | Broker listeners; web only if needed | Short local transactions as required; outbox/inbox for consistency | Broker/DB Testcontainers, duplicate/retry/failure tests |

Do not mix servlet security/error-handler templates into WebFlux. Virtual threads do not make JPA or blocking HTTP reactive. Gateways handle edge policies; the owning business service enforces invariants.

## Core conventions

### Architecture and contracts

1. **Respect service boundaries.** Each microservice owns its data, migrations, configuration, DTOs and release lifecycle. Local features may call service interfaces; separate services use explicit HTTP/message contracts. Never import another service's entity/repository or query its tables.
2. **Organize by feature inside each service.** Use `web`, `service` (+ `service.impl`), `repository`, `domain`, `mapper` and adapters as needed. DTOs live under this service's `common.dto.<feature>`; generic wrappers under `common.dto`. `common` is local to a service, not a system-wide DTO jar.
3. **Keep controllers thin.** Bind, validate, identify the caller, delegate, shape the response. No business logic, repositories or transactions. Workers/listeners delegate business work too.
   Keep feature `web` packages for controllers. Shared exception advice belongs in `common.exception.handler`, HTTP response factories in `common.web`, and authentication/access-denied handlers in `security.handler`; do not collect unrelated HTTP infrastructure beside controllers.
4. **Public feature services have interfaces.** `XxxService` and `service.impl.XxxServiceImpl`; callers depend on the interface. Internal calculators/validators need no ceremonial interface.
5. **Use DTO records at service/API boundaries.** Never return persistence entities. Use MapStruct for new or changed entity-to-DTO property mappings, with Spring component model and `unmappedTargetPolicy = ERROR`. Configure its dependency and annotation processor, plus `lombok-mapstruct-binding` when mapping Lombok-generated accessors. Preserve handwritten mappers outside the task scope. DTOs do not belong in `common.web`.

### Java and values

6. Constructor injection with `private final` dependencies; no field/setter injection. Name dependency fields, constructor parameters and test doubles after their declared type in lower camel case (`CurrencyRepository currencyRepository`, not `currencies`; `TransactionTemplate transactionTemplate`, not `tx`). Prefer immutable records and modern language features supported by the project's Java version.
7. Never return null collections. `Optional` is for return types, not fields/parameters. Apply JSpecify defaults per package when used; parent-package annotations do not cover subpackages.
8. **Money uses `BigDecimal` plus a currency.** Never use `double`/`float`. Define currency-specific scale, precision and rounding explicitly; persist as `NUMERIC/DECIMAL`. Migrating a minor-unit integer schema requires a forward migration, a documented conversion factor per currency and contract/data verification. Never reinterpret existing integers silently.
9. Use `java.time`, store instants in UTC, and inject `Clock` for business time.

### API and security

10. REST defaults: versioned plural kebab-case paths, correct status codes, `201` + `Location` for creation, bounded pagination and whitelisted sorting. Preserve released contracts unless changing them is in scope.
11. Produce consistent `ProblemDetail` with stable `code`. Use the handler for the actual runtime, plus security entry points/access-denied handlers for filter-chain errors. Never expose SQL, stack traces, secrets or internal hosts.
    For business errors handled alike, default to a shared concrete `BusinessException` with enum-based error codes; add a code instead of a subclass when only the code, message or HTTP status differs. Create a subclass only when its type affects handling, transaction rules or typed error data. Reuse the existing constructor, global handler, HTTP mappings and response format; see [exception guidance](references/06-exceptions-and-logging.md). Keep technical exception handling unchanged.
    Each error enum value also declares an explicit stable identifier, e.g. `ACCOUNT_NOT_FOUND("ANF01", HttpStatus.NOT_FOUND, "Account not found")`. Keep identifiers nonblank and unique within the service, including across feature enums; never derive them from enum ordinals or silently replace an existing API error code.
12. Validate request shape at the boundary and business invariants in the service/domain. Caller/owner identity comes from validated authentication or a verified mapping from it. Client-provided owner IDs are never authorization. Creating for another owner requires explicit permission and a service-level check.
13. Authenticate unless explicitly public; enforce ownership in the owning service. Gateways sanitize caller-supplied trusted headers; backends validate user/service credentials themselves. Never log tokens, secrets or personal data.

### Persistence and integration

14. Use Flyway/Liquibase; applied migrations are immutable. For JPA use `ddl-auto=validate/none`, `open-in-view=false`, lazy associations and explicit fetch plans. Gateways/reactive services/workers do not require JPA.
15. Put transaction boundaries around local persistence work in service implementations. Read-only class defaults are for DB-only services; remote orchestrators stay outside the transaction and delegate short transactional work. Follow proxy visibility/self-invocation and reactive context rules in [service guidance](references/04-service-layer.md).
16. Use validated `@ConfigurationProperties` for grouped settings; inject secrets from environment/secret stores.
17. Outbound adapters use runtime-appropriate clients, timeouts and bounded concurrency. Retry only replay-safe operations with capped attempts/backoff. Cross-service consistency uses explicit state transitions/compensation and outbox/inbox when needed, not an assumed distributed DB transaction.

### Quality

18. SLF4J parameterized logs; use Lombok `@Slf4j` where Lombok is configured instead of declaring a manual logger. Log unexpected exceptions once at the handling boundary. Propagate tracing using runtime-aware facilities.
19. Cover changed behavior at the relevant test level. Use real infrastructure for persistence/messaging integration tests. For Maven `*IT`, configure Failsafe's `integration-test` and `verify` goals before relying on discovery; see [build guidance](references/10-build-and-quality.md).
20. Run installed formatters/quality gates; preserve surrounding style when absent. New services configure chosen tools explicitly. Never report Spotless, ArchUnit or coverage as passing when not configured/run.
21. Use the Boot BOM for managed dependencies and a compatible Cloud BOM when needed. Pin plugins not managed by a parent. Explain additions; keep unrelated upgrades separate.
22. Comments explain decisions; public service interfaces document contracts/business exceptions. Follow the repository's commit convention and keep commits focused.

## Task routing

| Task | Read |
|---|---|
| System overview, boundaries, local `common`, package layout | [01 Architecture](references/01-architecture.md) |
| Java, Lombok, nullness, money/time | [02 Java style](references/02-java-style.md) |
| MVC/WebFlux APIs, DTOs, errors, clients | [03 REST API](references/03-rest-api.md) |
| Services, transactions, events, remote orchestration | [04 Service layer](references/04-service-layer.md) |
| JPA, SQL, migrations, fetching | [05 Persistence](references/05-persistence-jpa.md) |
| Error handlers, security errors, logging/context | [06 Exceptions and logging](references/06-exceptions-and-logging.md) |
| Configuration, servlet/reactive security, secrets | [07 Configuration and security](references/07-config-and-security.md) |
| Unit, slices, integration, contracts and architecture tests | [08 Testing](references/08-testing.md) |
| Actuator, tracing, shutdown, timeouts, pools | [09 Operations](references/09-observability-and-ops.md) |
| Wrappers, Failsafe, dependencies, CI, Git | [10 Build and quality](references/10-build-and-quality.md) |
| Boot-specific APIs or migration | [11 Version notes](references/11-spring-boot-4-notes.md) |
| Reviewing changes | [12 Review checklist](references/12-code-review-checklist.md) |
| New class/test skeleton | [13 Templates](references/13-code-templates.md) |

## Definition of Done

- Service/runtime boundaries and local `common.dto` placement respected; no entity leakage.
- Identity/ownership checked, including creation; relevant authorization tests pass.
- Money/currency/rounding and time contracts explicit where applicable.
- Persistence changes have forward migrations and data/transaction tests.
- Remote work stays outside local DB transactions; reactive flows preserve cancellation/context.
- Tests are discovered and pass; configured quality checks run; unavailable checks and existing failures reported.
- Migration/configuration/API changes and material deviations explained to the user.
