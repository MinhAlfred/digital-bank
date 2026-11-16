---
name: spring-boot-conventions
description: Mandatory engineering conventions for Java / Spring Boot projects (Spring Boot 4.x, Spring Framework 7, Java 21+). Use this skill whenever the task involves writing, modifying, reviewing, refactoring or testing Java code in a Spring project - controllers, services (interface + impl), repositories, JPA entities, DTOs and mappers, exception handling, configuration properties, Spring Security, Flyway/Liquibase migrations, logging, REST API design, unit/integration tests, pom.xml/build.gradle. Also use it when the user asks about best practices, project structure, naming, layering, code review or "how should I write this" for Spring Boot, even if they never say "convention". Consult it BEFORE generating any Spring Boot code.
---

# Spring Boot Conventions

The single source of truth for how code is written in this project. Follow it for all new and modified code.

## Workflow

1. **Detect context first.** Read `pom.xml` / `build.gradle(.kts)` to learn the Java version, Spring Boot version, build tool, and which libraries are present (Lombok, MapStruct, Flyway/Liquibase, Spring Security, Testcontainers). Skim 2-3 existing classes in the same feature to see the current layout. Never upgrade Java/Spring Boot or add a major dependency unless asked.
2. **Route to the references** (table below). Read only the files relevant to the task; do not load all of them.
3. **Write the code** obeying the Non-negotiable rules. Start from `references/13-code-templates.md` for new classes.
4. **Verify.** Run the build with tests (`./mvnw -q verify` or `./gradlew build`) when a shell is available, run the formatter (`spotless:apply`) if configured, then walk the Definition of Done at the bottom.
5. **Report deviations.** If existing code violates a rule, follow the rule for the code you write or touch, do not mass-refactor unrelated files, and mention the violation to the user in one line.

If a rule cannot be followed for a good reason, do not silently break it: add a short comment explaining why and tell the user.

## Baseline

| Item | Default |
|---|---|
| Java | 21+ (25 LTS preferred for new projects). Boot 4 accepts Java 17 as a minimum, but write 21+ idioms when the project allows it |
| Spring Boot | 4.x line (Spring Framework 7, Spring Security 7, Hibernate 7, Jackson 3, JUnit 6). 3.x is end-of-life: see `references/11-spring-boot-4-notes.md` |
| Build | Maven Wrapper (`./mvnw`) or Gradle Wrapper. Respect whichever the project uses |
| DB access | Spring Data JPA + Flyway. PostgreSQL by default |
| API style | REST + JSON, errors as RFC 9457 `ProblemDetail` |
| Architecture | Package-by-feature modular monolith, layered inside each feature |
| Tests | JUnit 6 + Mockito + AssertJ + Testcontainers + ArchUnit |

## Non-negotiable rules

### Architecture and layering
1. **Package-by-feature.** Each feature owns `web`, `service` (+ `service.impl`), `repository`, `domain`, `dto`. Dependencies flow `web -> service -> repository -> domain` only. Enforced by ArchUnit (`references/08-testing.md`).
2. **Controllers are thin:** bind, validate, delegate to one service call, shape the HTTP response. No business logic, no repository access, no `@Transactional`.
3. **Every service has an interface** `XxxService` in `service` and an implementation `XxxServiceImpl` in `service.impl`. Callers (controllers, other services) depend on the interface only, never on `Impl`.
4. **Never expose JPA entities** outside the service layer. APIs and service interfaces use DTOs (Java `records`). Entity to DTO mapping uses MapStruct.
5. **Features talk to each other** through the other feature's service interface or through domain events, never through its repository or entities.

### Java
6. **Constructor injection only**, with `private final` fields. No `@Autowired` on fields or setters.
7. **Modern Java:** records, `sealed` types, pattern matching, switch expressions, text blocks. No raw types. Immutability by default.
8. **Null discipline:** never return `null` for collections (return empty), use `Optional` only as a return type (never as a parameter or field), prefer `@NullMarked` packages.
9. **Money is `BigDecimal`** (never `double`/`float`). Time is `java.time`; store instants in UTC; inject `Clock` instead of calling `Instant.now()` in business code.

### Web / API
10. **REST rules** (`references/03-rest-api.md`): plural kebab-case nouns, correct status codes, `201` + `Location` on create, every collection endpoint paginated with a max page size, versioned under `/api/v1`.
11. **One global error handler** (`@RestControllerAdvice` extending `ResponseEntityExceptionHandler`) returning `ProblemDetail` with a stable `code` property. Never leak stack traces, SQL, or class names.
12. **Validate at the boundary** with Bean Validation (`@Valid`, constraints on request records). Enforce business invariants inside the service.

### Persistence
13. **Schema changes only through Flyway/Liquibase.** `spring.jpa.hibernate.ddl-auto` is `validate` or `none`. Applied migrations are immutable.
14. **All associations are `LAZY`.** `spring.jpa.open-in-view=false`. Solve N+1 explicitly (`@EntityGraph`, fetch join, projections). No `@Data` on entities; `equals/hashCode` per `references/05-persistence-jpa.md`.
15. **Transactions live on service implementation methods.** Class default `@Transactional(readOnly = true)`, writes annotated explicitly. No remote calls inside a transaction. Beware self-invocation.

### Config, security, integration
16. **Typed configuration:** `@ConfigurationProperties` records with `@Validated`. No scattered `@Value`. No secrets in the repository; override via environment variables.
17. **Secure by default:** every endpoint requires authentication unless explicitly permitted; authorization checks (including ownership/IDOR) in the service layer. Never log secrets, tokens, or personal data.
18. **Outbound HTTP** uses `RestClient` or `@HttpExchange` interfaces with explicit timeouts. Not `RestTemplate` in new code. Remote calls get retry/limit policy where idempotent.

### Quality
19. **Logging:** SLF4J with parameterized messages; never `System.out`; never log-and-rethrow the same exception.
20. **Every behavior change ships with tests:** unit tests for logic, slice tests for web/persistence, at least one integration test per feature using Testcontainers (real DB, not H2).
21. **Formatting and hygiene:** formatter-clean (Spotless), zero compiler warnings, no commented-out code, no `TODO` without a ticket id.
22. **Dependencies** come from the Spring Boot BOM. Do not pin versions of managed dependencies. New dependency = justified in the PR description.
23. **Comments explain why, not what.** Public service interfaces carry Javadoc describing contract and thrown exceptions.

## Task routing

| If the task involves... | Read |
|---|---|
| Creating a feature, choosing packages, cross-feature calls | `references/01-architecture.md` |
| Writing any Java: naming, Lombok, Optional, streams, formatting | `references/02-java-style.md` |
| Controllers, DTOs, endpoints, status codes, pagination, OpenAPI | `references/03-rest-api.md` |
| Service interface/impl, transactions, events, caching, async, outbound calls | `references/04-service-layer.md` |
| Entities, repositories, queries, migrations, SQL naming | `references/05-persistence-jpa.md` |
| Exceptions, error responses, logging | `references/06-exceptions-and-logging.md` |
| application.yml, profiles, secrets, Spring Security, hardening | `references/07-config-and-security.md` |
| Writing or fixing tests, ArchUnit, coverage | `references/08-testing.md` |
| Actuator, metrics, tracing, graceful shutdown, Docker, pool sizing | `references/09-observability-and-ops.md` |
| pom.xml/Gradle, plugins, CI, Git/commit/PR rules | `references/10-build-and-quality.md` |
| Version-specific behavior, migrating from Boot 3.x, import/package surprises | `references/11-spring-boot-4-notes.md` |
| Reviewing code or a PR | `references/12-code-review-checklist.md` |
| Starting any new class (copy-paste skeletons) | `references/13-code-templates.md` |

For a brand-new feature (e.g. "add Order module") read 01, 03, 04, 05, 06, 13, then 08 for tests.

## Naming cheat sheet

| Thing | Convention | Example |
|---|---|---|
| Package | lowercase, one word per level | `com.acme.shop.order.service.impl` |
| Controller | `<Feature>Controller` | `OrderController` |
| Service | `<Feature>Service` / `<Feature>ServiceImpl` | `OrderService`, `OrderServiceImpl` |
| Repository | `<Entity>Repository` | `OrderRepository` |
| Entity | singular noun, no suffix | `Order`, `OrderItem` |
| Request DTO | `<Action><Entity>Request` | `CreateOrderRequest` |
| Response DTO | `<Entity>Response`, list view `<Entity>Summary` | `OrderResponse`, `OrderSummary` |
| Mapper | `<Entity>Mapper` | `OrderMapper` |
| Event | past tense + `Event` | `OrderCancelledEvent` |
| Config / properties | `<Thing>Config`, `<Thing>Properties` | `PaymentProperties` |
| Exception | `<Reason>Exception` extends `BusinessException` | `OrderAlreadyCancelledException` |
| Enum | singular, constants `UPPER_SNAKE` | `OrderStatus.PENDING_PAYMENT` |
| Unit / slice test | `<Class>Test` | `OrderServiceImplTest` |
| Integration test | `<Feature>IT` (run by Failsafe) | `OrderFlowIT` |
| Test method | `method_condition_expectedResult` | `cancel_whenAlreadyShipped_throwsConflict` |
| REST path | plural, kebab-case | `/api/v1/purchase-orders/{id}` |
| DB table/column | `snake_case`, plural tables | `purchase_orders.created_at` |

## Definition of Done

Before presenting work as finished, confirm each item:

- [ ] Layering respected; controller has no logic; entity never leaves the service
- [ ] Service has interface + `Impl`; constructor injection; no field injection
- [ ] DTOs are records with validation annotations; MapStruct mapper where mapping is needed
- [ ] Error paths throw `BusinessException` subclasses and are covered by the global handler
- [ ] Migration script added for any schema change; no `ddl-auto` shortcuts
- [ ] No N+1 introduced; collections paginated; `LAZY` associations
- [ ] Transaction boundaries correct; no remote call inside a transaction
- [ ] Unit tests + slice/integration tests added and passing; ArchUnit suite still green
- [ ] No secrets/PII in code, config, or logs; new endpoints secured and authorization-tested
- [ ] Formatter run; build passes with no new warnings