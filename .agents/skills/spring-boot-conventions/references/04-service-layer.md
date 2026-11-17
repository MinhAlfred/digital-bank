# 04 - Service Layer, Transactions, Events

## Contents
- Interface + implementation rules
- Method design
- Transactions
- Business rules and validation
- Authorization in services
- Domain events and the outbox pattern
- Caching
- Async, scheduling, virtual threads
- Calling other services and external systems
- Common pitfalls

## Interface + implementation rules

Every service is split into a contract and an implementation:

```
order/service/OrderService.java            # public interface, the feature's API
order/service/impl/OrderServiceImpl.java   # @Service implementation
```

- Callers depend on `OrderService`, never on `OrderServiceImpl`. Mocks in tests mock the interface.
- The interface is documented with Javadoc: purpose, parameters when non-obvious, and every business exception that can be thrown.
- The interface contains **business-level** methods only; no `HttpServletRequest`, `ResponseEntity`, `Pageable` from other modules is fine, but no web types.
- One implementation per interface in production code. If a second implementation appears (strategy), name them by behavior (`StripePaymentGateway`, `MockPaymentGateway`), not `Impl2`.
- Annotate the implementation with `@Service`; class is `public` so ArchUnit and other modules' tests can see it, but nothing outside `service.impl` references it directly.
- Stateless: no mutable instance fields. Dependencies are `private final`.
- Keep one service focused on one aggregate/use-case family. When an Impl grows beyond ~400 lines or ~12 public methods, split by use case (`OrderQueryService`, `OrderCancellationService`).

Pure internal helpers (price calculators, validators) are plain `@Component` classes without an interface; they are collaborators, not feature APIs. Only the feature's **public entry points** require the interface.

## Method design

- Return **DTOs** (`OrderResponse`), never entities. Accept DTOs or primitives/IDs. Entities are an implementation detail of `service.impl`.
- Lookup naming: `getById` throws `BusinessException` with the appropriate not-found error code when absent; `findById` returns `Optional`. Retain a dedicated exception type only when handling depends on that type.
- Writes return the resulting representation (or `void` for delete/cancel).
- Query methods are side-effect free.
- Avoid boolean flags and long parameter lists; introduce a command/criteria record.
- Do not call another method of the same class that has a different `@Transactional` setting (self-invocation bypasses the proxy).

## Transactions

- Place `@Transactional` on **service implementation** classes/methods only (not controllers, not repositories unless a repository method needs special semantics).
- For DB-only services, a class-level `@Transactional(readOnly = true)` with explicit write methods is suitable. Orchestrators that make remote calls have no class-level transaction: delegate persistence to a separate proxied transactional bean or a `TransactionTemplate` block. A read-only transaction still holds resources during remote work.
- Keep transactions **short**. Do not hold a DB transaction while calling external HTTP services, sending email, or waiting on queues. Pattern: read/validate -> call remote -> short write transaction, or publish an event after commit.
- Use `Propagation.REQUIRES_NEW` rarely and deliberately (audit trails that must survive rollback).
- Rollback: unchecked exceptions roll back by default. If a checked exception must roll back, declare `rollbackFor`. Do not catch an exception inside the transaction and continue as if nothing happened.
- Never expose lazy-loaded entities beyond the transaction; map to DTOs inside it.
- Aggregates/features in the same database may share a local transaction when an invariant requires it. Across separate services/databases, use explicit eventual consistency/compensation and outbox/inbox as needed; a local `@Transactional` cannot cover remote commits.
- Proxy mode: public methods are the portable service-contract default. Since Framework 6, protected/package-visible methods can be transactional with class-based proxies; JDK interface proxies require public methods declared on the interface. Private/final methods cannot be advised by class proxies; self-invocation bypasses advice. AspectJ weaving has different interception semantics; inspect configuration before reasoning about it.
- Reactive persistence uses `ReactiveTransactionManager` and a reactive return type or `TransactionalOperator`. Participating operations must stay in the same Reactor context/pipeline; thread-local imperative transactions do not follow scheduler hops. Keep remote work outside the transactional publisher. Cancellation can cause rollback; test it.
- Concurrency on hot rows: optimistic locking (`@Version`) first; pessimistic locks (`@Lock(PESSIMISTIC_WRITE)`) only for proven contention, with a lock timeout hint.

## Business rules and validation

Two levels:

1. **Boundary validation** (Bean Validation on request DTOs) - shape, size, format.
2. **Business validation** inside the service/entity - uniqueness, state transitions, cross-field and cross-aggregate rules. Default to the shared `BusinessException` with an enum-based error code. Differences in code, message or HTTP status alone do not warrant subclasses; use one only for type-dependent handling, transaction rules or typed error data (see `06-exceptions-and-logging.md`).

Put rules on the entity when they concern its own state (`order.cancel(clock)` rejects illegal transitions). Put rules in the service when they need collaborators (repositories, other services).

Uniqueness: check in the service for a friendly error **and** enforce with a unique DB constraint; retain the existing `DataIntegrityViolationException` conflict mapping/handler as the safety net for races. Do not introduce a subclass merely to assign HTTP `409`.

## Authorization in services

- Endpoint-level rules (`@PreAuthorize("hasAuthority('order:read')")`) express *who may call*.
- Resource-level rules express *who may touch this row*; implement them in the service (fetch by `id AND ownerId`, or compare after load). This prevents IDOR.
- Creation must enforce ownership too: derive the customer/tenant from validated authentication, or verify an explicit on-behalf-of permission. Mere existence of a body-supplied customer ID is insufficient.
- Pass the acting user's id explicitly (`cancel(UUID orderId, UUID actingUserId)`) or read it from a small `CurrentUser` abstraction; do not read `SecurityContextHolder` deep in business code.
- Return `404` rather than `403` when revealing existence is itself a leak.

## Domain events and the outbox pattern

In-process events:

```java
// publisher (inside the transaction)
applicationEventPublisher.publishEvent(new OrderCancelledEvent(order.getId(), clock.instant()));

// consumer (another feature)
@Component
class InventoryOrderListener {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(OrderCancelledEvent event) { ... }
}
```

- Events are immutable records, past-tense names, carrying IDs and minimal facts (not entities).
- At `AFTER_COMMIT`, the original transaction is already committed but resources may still be bound. Writes that must persist need a separate transaction, commonly a proxied `@Transactional(propagation = REQUIRES_NEW)` collaborator. This imperative listener example is not a reactive transaction-context recipe; use the target version's reactive event support when needed.
- Listeners must be idempotent: assume an event may be delivered more than once.
- When an event must reach another process (Kafka/RabbitMQ) reliably, use the **transactional outbox**: write the event row in the same transaction as the state change, and a relay publishes it and marks it sent. Consumers deduplicate by event id. Never publish to a broker directly inside the DB transaction and hope both succeed.

## Caching

- Cache in the **service implementation** with `@Cacheable/@CacheEvict/@CachePut`, never in controllers or repositories.
- Cache only data that is read-heavy, expensive, and tolerant to staleness. Always define a TTL and max size (Caffeine locally, Redis when shared across instances).
- Explicit cache names and keys (`@Cacheable(cacheNames = "product-by-id", key = "#id")`). Never cache entities; cache immutable DTOs (must be serializable for Redis).
- Every cached read has a documented eviction path for writes.
- Per-user data needs the user id in the key. Do not cache authorization results across users.

## Async, scheduling, virtual threads

- For a blocking MVC service, consider enabling virtual threads for request handling with `spring.threads.virtual.enabled=true` on Java 21+. Then do not hand-roll thread pools for blocking I/O. Size the DB connection pool deliberately: it becomes the concurrency limit.
- `@Async` only with a named, configured executor (`@Async("mailExecutor")`) and a handler for uncaught exceptions. `@Async` methods return `void` or `CompletableFuture`, and must be called from another bean (proxy).
- No `@Async` for work that must be part of the caller's transaction.
- `@Scheduled` jobs: keep the method tiny and delegate to a service; they must be idempotent and safe to run on multiple instances. In a multi-instance deployment use a distributed lock (ShedLock) or a single-run mechanism. Configure cron via properties (`app.jobs.cleanup.cron`), not literals.
- Long-running or retriable work belongs in a queue or job framework, not in a scheduler thread.

## Calling other services and external systems

- Within the same application: call the other feature's service **interface** (see `01-architecture.md`).
- External systems: wrap in an adapter class with its own interface (`PaymentGateway`), implemented on `RestClient`/blocking `@HttpExchange` for imperative services, or `WebClient`/reactive `@HttpExchange` for WebFlux. Remote wire DTOs belong to this service's `common.dto.external.<provider>` and are translated at the adapter. The adapter owns timeouts, error translation, and retry policy.
- Resilience: Spring Framework 7 provides `@Retryable` and `@ConcurrencyLimit` (enable with `@EnableResilientMethods`); Resilience4j is acceptable if the project already uses it. Retry only idempotent operations; always cap attempts and use backoff with jitter.
- Never let remote DTOs leak into the domain; translate at the adapter.

## Common pitfalls

- Self-invocation bypasses transactional advice in proxy mode. Package-visible/protected methods are supported by class-based proxies since Framework 6; do not classify them together with private methods.
- `@Transactional(readOnly = true)` method that calls a write -> silent bugs/exceptions; keep reads and writes in separate methods.
- Returning an entity from a `readOnly` transaction and touching a lazy field in the controller -> `LazyInitializationException`. Map inside the service.
- Catching `Exception` in a service and returning `null` or `false` to signal failure.
- Doing `findAll()` then filtering in memory; push filtering into the query.
- Looping and calling `repository.save()` per element for large batches; use batch operations.
- Publishing events before commit and consuming them as if the data were durable.
## Official transaction references

- [Visibility, proxy mode and self-invocation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
- [Imperative versus reactive transaction context](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-decl-explained.html)
- [Transactional event listeners](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html)
