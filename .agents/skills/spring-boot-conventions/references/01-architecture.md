# 01 - System Architecture and Service Structure

## Contents
- Establish the system context
- Service responsibilities and data ownership
- Contracts and distributed consistency
- Runtime choices
- Structure inside one service
- Local common and dependency rules
- Component scanning and nullness
- Evolving boundaries

## Establish the system context

Support microservice systems and modular monoliths; preserve existing deployment boundaries. Package-by-feature organizes an application internally. It does not require a monolith or one deployable per feature.

Before a new service or cross-service flow, identify:

| Question | Inspect or record |
|---|---|
| Who owns the use case/data? | Domain boundary, invariant and authoritative database owner |
| How does work enter? | Gateway, direct API, message or job; caller/tenant context |
| Which collaborators are remote? | Local interfaces versus HTTP/message contracts |
| What must be atomic? | Local transaction, eventual consistency, compensation |
| What happens on duplicate/partial failure? | Deadline, replay safety, outbox/inbox, recovery |
| Which runtime does each participant use? | MVC/JPA, WebFlux/reactive driver, gateway or worker |
| How is it built/deployed? | Independent wrapper/pipeline or existing aggregator; configuration owner |

Use a short flow description/diagram for multi-service work. Do not add discovery/config servers, brokers, service meshes or an aggregator simply because microservices exist.

## Service responsibilities and data ownership

```text
Client -> Gateway -> Owning business service -> Its database
                          |
                          +-> Remote API (bounded synchronous dependency)
                          +-> Outbox relay -> Broker -> Consumer / its database
```

- Gateways handle routing and edge policies: authentication, header sanitization, limits and tracing. Business validation/persistence belong in the owner service.
- A business service owns its invariants/data. Others use its API or event-driven read models, never its repositories/tables.
- Workers own idempotent processing, acknowledgment/redelivery policy and local state. They need web/JPA starters only when their responsibilities require them.
- Prefer separate databases or schemas/credentials enforcing ownership. Sharing a database instance locally does not authorize cross-service joins/writes.
- Backends authenticate and authorize independently. Externally supplied `X-User-*`/internal-secret headers require a verified trust boundary.

## Contracts and distributed consistency

| Boundary | Mechanism | Contract ownership |
|---|---|---|
| Local features | Service interface or local domain event | This application's DTOs/event types |
| Remote service; immediate answer | HTTP through adapter/port | Provider owns wire API; consumer owns local mapping |
| Remote asynchronous side effect | Versioned integration event | Producer owns event schema; consumer handles compatible evolution |

- Each deployable defines its own DTOs under its own `common.dto`. Do not create a system-wide common jar of business DTOs/entities/repositories. Identical wire fields do not imply shared Java classes.
- Version published OpenAPI/AsyncAPI/schema artifacts. Generated clients, if adopted, stay behind consumer-owned adapters; provider implementation/domain internals do not leak.
- Event envelopes carry event id, type/schema version, timestamp and required correlation metadata. Payloads carry minimal facts/IDs and avoid confidential data.
- Local DB transactions cannot atomically commit another service's state. Model partial failure explicitly: outbox for durable publication, consumer deduplication/inbox for replay safety, saga/compensation when the business flow requires it.
- Scope idempotency keys by caller/tenant/operation; persist request identity and outcome atomically. Reject reuse with a different payload. Never blindly retry payment/state-changing calls.
- Set deadlines, timeouts and bounded retries; avoid deep synchronous call chains. Propagate tracing and validated caller context through the established mechanism.

## Runtime choices

- **MVC + JPA/JDBC:** blocking request path; short imperative transactions; `RestClient` outbound. Optional virtual threads do not remove DB pool/concurrency limits.
- **WebFlux:** preserve `Mono`/`Flux`, cancellation and Reactor context; use `WebClient` and reactive persistence when selected. Use `ReactiveTransactionManager`/`TransactionalOperator`. JPA remains blocking behind reactive return types.
- **Gateway WebFlux:** return composed filter chains. No `block()`, manual `subscribe()`, blocking DB/HTTP work in filters. Strip incoming trusted headers before adding verified identity.
- **Workers:** follow the configured listener/persistence model; define acknowledgment, retry/dead-letter, transaction and shutdown behavior.
- A necessary blocking adapter within WebFlux isolates work on a bounded scheduler and owns its imperative transaction inside that work. Document/test the boundary. Prefer a consistent runtime for new services.

Do not switch existing runtimes as part of unrelated feature work.

## Structure inside one service

Example for `order-service`; adapt the root package:

```text
order-service/
  pom.xml, mvnw, mvnw.cmd
  src/main/java/com/acme/shop/
    OrderApplication.java
    common/
      dto/
        PageResponse.java
        order/       CreateOrderRequest, OrderResponse, OrderSummary
        external/    customer/CustomerResponse (consumer-owned wire DTO)
      event/         IntegrationEventEnvelope (when needed)
      exception/     BusinessException, ErrorCode
      web/           GlobalExceptionHandler (runtime-specific), ApiPaths
      persistence/   AuditableEntity (JPA only)
    config/          SecurityConfig, ClockConfig, client/broker configuration
    order/
      web/           OrderController
      service/       OrderService
        impl/        OrderServiceImpl
      domain/        Order, OrderStatus, local domain events
      repository/    OrderRepository
      mapper/        OrderMapper
      integration/   Remote client/adapter and outbound event relay
  src/main/resources/db/migration/    (when SQL data is owned here)
  src/test/java/                     (mirrors production packages)
```

Omit unused packages. Gateways need no empty domain/repository/persistence helpers. Preserve existing layer-based layouts unless reorganization is in scope.

## Local common and dependency rules

`common` means shared **within this deployable**. Feature DTOs retain ownership via `common.dto.<feature>`; generic wrappers use `common.dto`. Keep entities/repositories/business logic in their feature. Never place service return DTOs in `common.web`.

| Package | Responsibility | Forbidden dependencies |
|---|---|---|
| `web` | HTTP binding, principal mapping, validation, response | repository, service implementation, entity |
| `service` | Public feature contract using DTOs/IDs | HTTP request/response types, web, implementation |
| `service.impl` | Use cases, ownership, transactions, orchestration | web, another feature's repository/entity |
| `domain` | Entities/value types, local invariants/events | web, service, repository, transport DTOs |
| `repository` | Persistence/query | web/business orchestration |
| `common.dto` | Local immutable contracts | web, service, repository, JPA entities |
| `integration` | External protocol/error/DTO translation | another service's implementation/entities |

DTOs may use an intentional stable local domain enum/value type, never a persistence entity. `common` is not an unrestricted dependency exception. Exclude common/config from feature cycle slicing, then enforce their own dependency rules. Local interfaces stay local; remote services use explicit wire contracts.

## Component scanning and nullness

- Put `@SpringBootApplication` in this application's root package. Use `@ConfigurationPropertiesScan` where adopted; dedicated enable/configuration annotations belong in config classes.
- With JSpecify, apply `@NullMarked` to **each package** needing a default, or use a supported module default. Feature-root package annotations do not cover `service`, `domain`, `common.dto.order` or other subpackages. Mark optional values `@Nullable`.
- Scope tests/ArchUnit to this service; never scan sibling applications accidentally.

## Evolving boundaries

Preserve existing microservices. Choose a new system's deployment model from business ownership, operational needs and failure domains. Record significant boundary changes using the repository's ADR convention.

Extracting a feature requires a deployable, data ownership, network/message contracts, migration/recovery plans, security, observability and independent tests. Moving a package or exporting its Java interface is insufficient.

References: [Spring WebFlux](https://docs.spring.io/spring-framework/reference/web/webflux/new-framework.html), [Spring transactions](https://docs.spring.io/spring-framework/reference/data-access/transaction.html), [JSpecify](https://jspecify.dev/docs/user-guide/).
