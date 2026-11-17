# 12 - Code Review Checklist

Use this when reviewing a PR, a diff, or the agent's own output before presenting it.

## Contents
- How to review
- Severity levels
- Checklist by area
- Review output format

## How to review

1. Read the PR description and the ticket first: is the change the right thing?
2. Review tests before implementation: do they prove the behavior?
3. Walk the checklist below, area by area, against the diff (not the whole repo).
4. Report findings grouped by severity with file/line, the rule violated, and a concrete fix.
5. Be specific and kind; critique the code, not the person. Praise what is genuinely good.

## Severity levels

| Level | Meaning | Action |
|---|---|---|
| **Blocker** | Security flaw, data loss/corruption, broken behavior, violates a Non-negotiable rule, missing migration | Must fix before merge |
| **Major** | Likely bug, N+1, wrong transaction boundary, missing tests for new logic, API contract break | Fix before merge unless justified |
| **Minor** | Style, naming, small readability issues, missing Javadoc | Fix now or follow-up |
| **Nit** | Optional preference | Author's call |

## Checklist by area

### Design and layering
- [ ] Code is in the right feature and layer; dependency direction `web -> service -> repository -> domain`
- [ ] Controller is thin; no business logic, repository, or `@Transactional` in it
- [ ] Feature `web` contains controllers only; shared advice is in `common.exception.handler`, response factories in `common.web`, security handlers in `security.handler`
- [ ] Public feature service has interface + `Impl`; callers use the interface; DTOs live in this service's `common.dto.<feature>`, wrappers in `common.dto`
- [ ] Entities do not leak into controllers, DTOs, events, or other features
- [ ] Entity-to-DTO property mappings use MapStruct with Spring component model, strict unmapped targets and the necessary annotation processors/Lombok binding
- [ ] Local features use interfaces/events; remote services use HTTP/message adapters and independent local DTOs; no shared entities/repositories/database access
- [ ] No god class, no utility dumping ground, no duplicated logic that belongs in one place

### Correctness
- [ ] Requirements and edge cases covered (null/empty, boundaries, duplicates, concurrency, time zones)
- [ ] State transitions validated; invariants enforced in the entity/service
- [ ] Idempotency for retried operations; unique constraints back up uniqueness checks
- [ ] Money uses `BigDecimal` with explicit rounding; time uses `java.time` + injected `Clock`
- [ ] No resource leaks (streams, connections, executors); try-with-resources used

### API
- [ ] URL/verb/status codes follow `03-rest-api.md`; `201` + `Location` on create
- [ ] Request/response are DTO records; no entity exposure; no mass-assignment
- [ ] Collections paginated with max size; sortable fields whitelisted
- [ ] Errors are `ProblemDetail` with a stable `code`; no internal details leaked
- [ ] Backward compatibility preserved or version bumped; OpenAPI updated

### Persistence
- [ ] Schema change has a Flyway/Liquibase migration (immutable, one concern, constraints and indexes included)
- [ ] All associations `LAZY`; no N+1 (check list endpoints); fetch strategy explicit
- [ ] `@Version` on mutable aggregates; `equals/hashCode` per rules; no `@Data` on entities
- [ ] Queries parameterized; no `findAll()` unbounded; projections for read models
- [ ] Local transactions use the correct manager/runtime; remote orchestrators stay outside transactions; proxy/self-invocation and cancellation/context behavior understood

### Security
- [ ] New endpoints authenticated/authorized; ownership checked in the service including creation; caller identity is verified; on-behalf-of actions require permission
- [ ] No secrets, tokens, or PII in code, config, logs, or error messages
- [ ] Input validated at the boundary; SQL/JPQL parameterized; file/URL inputs constrained
- [ ] CORS, CSRF, headers consistent with `07-config-and-security.md`
- [ ] Dependencies are BOM-managed; no unreviewed new dependency

### Exceptions and logging
- [ ] Business errors default to shared `BusinessException` with enum-based error codes; subclasses require type-dependent handling, transaction rules or typed error data, not just a different code/message/status; none swallowed
- [ ] Existing business error codes, HTTP mappings and response format are preserved through the existing global handler; technical exception handling is unchanged
- [ ] Every error enum constant has an explicit stable identifier (e.g. `ANF01`), nonblank and unique across the service's error enums; released identifiers are not changed or reused
- [ ] Logged once, parameterized, correct level; no `System.out`; no log-and-rethrow
- [ ] Loggers use `@Slf4j` when Lombok is configured; no redundant manual logger declaration
- [ ] Expected 4xx are not logged as errors with stack traces

### Java style
- [ ] Constructor injection, `final` fields, no field injection
- [ ] Dependency fields, constructor parameters and test doubles use full type-based names (`currencyRepository`, `customerGateway`, `transactionTemplate`), with meaningful qualifiers when the same type occurs more than once
- [ ] Records for DTOs/properties/events; `Optional` only as return type; no null collections
- [ ] Naming follows the cheat sheet; methods short and focused; guard clauses over nesting
- [ ] No commented-out code, dead code, unused imports; `TODO` has a ticket id
- [ ] Formatter clean; no new compiler/analysis warnings

### Configuration and operations
- [ ] New settings in typed `@ConfigurationProperties` with validation and safe defaults
- [ ] Timeouts on all outbound calls; retries only for idempotent calls
- [ ] Observability: meaningful logs, metrics/trace for new critical paths
- [ ] Backward-compatible migration/config for rolling deployments

### Tests
- [ ] New logic covered: happy path, each business rule, errors, boundaries
- [ ] Right level: unit for logic, slice for web/JPA, integration for flows; real DB via Testcontainers
- [ ] Deterministic (fixed `Clock`, no sleeps); descriptive names; AssertJ
- [ ] Tests actually discovered by configured runners (`*IT` through Failsafe `verify`); configured ArchUnit/coverage gates pass; absent/unrun checks reported honestly
- [ ] Bug fix includes a regression test

## Review output format

```
## Summary
<one or two sentences: what the change does and overall verdict>

## Blockers
- `OrderController.java:42` - business logic in controller (rule 2). Move the discount calculation into `OrderServiceImpl.create`.

## Major
- `OrderRepository.java:18` - `findAll()` returned to a list endpoint: unbounded and N+1 on `items`. Use a paginated query with `@EntityGraph`.

## Minor / Nits
- ...

## Positives
- Good use of a fixed Clock in tests.
```

If there are no findings at a level, omit that section. End with an explicit verdict: **Approve**, **Approve with comments**, or **Request changes**.
For reactive/gateway changes also check: no blocking/manual subscription in event-loop paths; reactive security/error handlers; trusted-header sanitization; Reactor-aware identity/tracing; bounded requests/timeouts. Apply JPA/SQL checklist items only when that service uses them.
