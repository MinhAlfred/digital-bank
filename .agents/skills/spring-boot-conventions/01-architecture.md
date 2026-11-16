# 01 - Architecture and Package Structure

## Contents
- Style: modular monolith, package-by-feature
- Directory layout
- Layer responsibilities
- Dependency rules
- Cross-feature communication
- The `common` module
- Application class and component scanning
- Null-safety package-info
- When to split into microservices

## Style: modular monolith, package-by-feature

Organise by **business feature**, not by technical layer. A feature (`order`, `customer`, `payment`) is a self-contained slice that can be understood, tested, and eventually extracted on its own. Do not create top-level `controller/`, `service/`, `repository/` packages for the whole application.

Default to a **modular monolith**. Only introduce a separate deployable service when there is an explicit reason (independent scaling, team ownership boundary, different runtime). Never introduce microservices "because best practice".

## Directory layout

```
com.acme.shop
├── ShopApplication.java                 # @SpringBootApplication + @ConfigurationPropertiesScan, in the ROOT package
├── common/                              # shared kernel (keep SMALL, see below)
│   ├── exception/   BusinessException, ErrorCode, ResourceNotFoundException, ConflictException
│   ├── web/         GlobalExceptionHandler, PageResponse, ApiPaths
│   ├── persistence/ AuditableEntity
│   └── util/        (static helpers, only when genuinely generic)
├── config/                              # cross-cutting @Configuration: SecurityConfig, JacksonConfig, ClockConfig, AsyncConfig
└── order/                               # ONE FEATURE
    ├── web/              OrderController
    ├── service/          OrderService                (interface = the feature's public API)
    │   └── impl/         OrderServiceImpl
    ├── repository/       OrderRepository
    ├── domain/           Order, OrderItem, OrderStatus, OrderCancelledEvent
    ├── dto/              CreateOrderRequest, OrderResponse, OrderSummary, OrderSearchCriteria
    ├── mapper/           OrderMapper
    └── package-info.java (@NullMarked)
```

Test sources mirror the main package tree exactly.

A tiny feature may omit packages it does not need (e.g. no `mapper`), but must keep `service` + `service.impl`.

## Layer responsibilities

| Layer | Does | Must NOT |
|---|---|---|
| `web` | HTTP binding, `@Valid`, calls one service method, builds `ResponseEntity`/status, maps nothing complex | business rules, repository calls, transactions, entity references |
| `service` (interface) | Defines the feature's public contract in business terms, documented with Javadoc | contain implementation, mention HTTP types (`ResponseEntity`, `HttpServletRequest`) |
| `service.impl` | Business logic, orchestration, transaction boundaries, authorization-by-ownership, publishes events | return entities, build HTTP responses |
| `repository` | Persistence queries only | contain business decisions |
| `domain` | Entities, value objects, enums, domain events, invariants enforced in constructors/methods | depend on `web`, `dto`, or Spring MVC |
| `dto` | Immutable records crossing the service boundary, Bean Validation annotations | contain behavior beyond trivial convenience |
| `mapper` | Entity -> DTO conversion (MapStruct) | contain business logic, call repositories |

## Dependency rules

Allowed direction: `web -> service -> repository -> domain`, and everything may use `dto`, `domain`, and `common`.

Forbidden (ArchUnit enforces these, see `08-testing.md`):
- `web` -> `repository`, `web` -> `service.impl`, `web` -> JPA entities
- `service` / `service.impl` -> `web`
- `domain` -> `web`, `service`, `repository`
- Cycles between features (`order` <-> `customer`)
- A feature using another feature's `repository`, `domain` entities, or `service.impl`

## Cross-feature communication

1. **Synchronous, needs an answer now:** inject the other feature's `service` interface and call it. Pass and return DTOs or IDs, never entities.
2. **Asynchronous / side effects:** publish a domain event (`ApplicationEventPublisher`) and consume it with `@TransactionalEventListener(phase = AFTER_COMMIT)`. Events are immutable records named in past tense. Use the transactional outbox pattern when the event must survive a crash or leave the process (see `04-service-layer.md`).
3. Prefer events when feature A should not know feature B exists.
4. If the project uses **Spring Modulith**, keep the same layout and let `ApplicationModules.verify()` replace the hand-written feature-cycle ArchUnit rule.

Reference other features' data by **ID**, not by JPA association, when the features are meant to be separable (`customerId` as `UUID`, no `@ManyToOne Customer` across feature boundaries).

## The `common` module

`common` is a shared kernel. It may contain only: base exception types, the global exception handler, pagination wrapper, auditing base entity, tiny generic utilities. Business concepts never go in `common`. When tempted to add a class there, first ask whether it belongs to a feature. If a utility is used by one feature only, it stays in that feature.

Avoid classes named `Utils`, `Helper`, `Manager`, `Handler` without a precise responsibility. Prefer a named collaborator (`OrderPriceCalculator`) or a method on the domain object.

## Application class and component scanning

- `@SpringBootApplication` class sits in the **root package** (`com.acme.shop`) so every feature is scanned automatically. Do not use `scanBasePackages` hacks.
- Put `@ConfigurationPropertiesScan` on it so `@ConfigurationProperties` records register automatically.
- Do not put `@EnableXxx` annotations on the application class; put them on the dedicated `@Configuration` class in `config/`.

## Null-safety package-info

Add to each feature root (and `common`):

```java
@NullMarked
package com.acme.shop.order;

import org.jspecify.annotations.NullMarked;
```

Within a `@NullMarked` package, everything is non-null unless annotated `@Nullable` (`org.jspecify.annotations.Nullable`). Spring Framework 7 itself uses JSpecify, so IDEs and NullAway understand it.

## When to split into microservices

Only with a written justification (ADR): measurable scaling need, hard team boundary, or a distinct failure domain. When splitting, the existing feature package becomes the service; its `service` interface becomes the API contract, and cross-feature events become messages. This is why features must not share entities or repositories.