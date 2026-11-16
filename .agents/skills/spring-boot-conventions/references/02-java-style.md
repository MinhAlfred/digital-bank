# 02 - Java Coding Style

## Contents
- Formatting
- Naming
- Language features
- Null handling and Optional
- Immutability, records, equality
- Dependency injection and Lombok policy
- Collections and streams
- Exceptions (language level)
- Money, time, identifiers
- Size and complexity limits
- Comments and Javadoc
- Anti-patterns to reject

## Formatting

- Use the configured formatter and its settings. For new services, Spotless with google-java-format AOSP is an option; configure it before invoking it. If no formatter exists, preserve surrounding style and do not introduce repository-wide formatting changes.
- Prefer UTF-8, LF and a final newline; honor existing `.editorconfig` settings when present.
- No wildcard imports. Import order is whatever the formatter produces. Static imports are allowed only for test libraries (AssertJ, Mockito, JUnit) and constants of the same domain.
- One top-level type per file. Braces always, even for single-line `if`.
- Do not hand-align code or use trailing whitespace tricks; the formatter owns whitespace.

## Naming

- Classes/records/enums `UpperCamelCase`; methods/fields/variables `lowerCamelCase`; constants `UPPER_SNAKE_CASE`; packages lowercase without underscores.
- Names reveal intent. `remainingStockQuantity`, not `qty2`. No Hungarian notation, no `I` prefix on interfaces, no `Abstract` prefix unless truly abstract-only.
- Booleans read as questions: `isActive`, `hasPendingPayment`, `canCancel`. Avoid negative names (`isNotEnabled`).
- Methods are verbs; service verbs are standardized: `create`, `getById` (throws if missing), `findById` (returns `Optional`), `search`, `update`, `delete`/`cancel`, `existsBy...`.
- Acronyms are words: `HttpClient`, `UserId`, `parseJson`, not `HTTPClient`.
- Generic type parameters: `T`, `E`, `K`, `V`, or descriptive `ResponseT` when several.

## Language features (Java 21+)

- DTOs, config properties, events, small value objects: **`record`**.
- Closed hierarchies (payment methods, result types): **`sealed interface` + records**, consumed with exhaustive `switch`.
- Use **pattern matching** (`instanceof Foo f`, `switch` patterns) instead of cast chains.
- Use **switch expressions** (`->`) rather than statement switches with `break`.
- Use **text blocks** for multi-line SQL/JSON in tests.
- `var` only when the type is obvious from the right-hand side (`var orders = new ArrayList<Order>();`). Never for method-call results whose type is not self-evident.
- `Stream.toList()` instead of `collect(Collectors.toList())`. `List.of/Set.of/Map.of` for immutable literals.
- Virtual threads are enabled via configuration, not by hand-creating threads (see `09-observability-and-ops.md`).

## Null handling and Optional

- When JSpecify is adopted, annotate each relevant package with `@NullMarked`; parent packages do not cover subpackages. Mark optional fields/components with `@Nullable` (see `01-architecture.md`).
- Never return `null` from a method returning a collection, array, or `Optional`. Return empty.
- `Optional` is for **return types only**. Never as a field, a method parameter, or a record component. Never call `Optional.get()` without a check; prefer `orElseThrow(...)`, `map`, `ifPresentOrElse`.
- Validate arguments early: `Objects.requireNonNull(x, "x")` in constructors of non-record types; Bean Validation at the boundary.
- Do not use `Optional` to hide control flow (`opt.isPresent() ? a : b` is fine to write as `map(...).orElse(...)`).

## Immutability, records, equality

- Fields are `private final` unless mutation is the point (JPA entities, builders).
- No public mutable fields. Expose behavior, not setters, on domain objects.
- Return unmodifiable views or copies from getters of collections (`List.copyOf`).
- Records already implement `equals/hashCode`; do not override them without reason.
- For non-record value types override `equals`/`hashCode` together, based on the same fields. JPA entities follow the special rule in `05-persistence-jpa.md`.

## Dependency injection and Lombok policy

- **Constructor injection only.** A class with a single constructor needs no `@Autowired`.
- Prefer explicit constructors or `@RequiredArgsConstructor` (Lombok) on classes with only `final` dependencies.
- No circular dependencies. If two beans need each other, extract a third collaborator or use an event. Never enable `spring.main.allow-circular-references`.
- Lombok is allowed in this limited set: `@RequiredArgsConstructor`, `@Getter`, `@Slf4j`, `@NoArgsConstructor(access = PROTECTED)` (entities), `@Builder` on non-entity classes with many optional fields.
- **Banned:** `@Data`, `@Value` (use records), `@EqualsAndHashCode`/`@ToString` on entities, `@SneakyThrows`, `@Setter` on entities, `@Builder` on JPA entities, `@AllArgsConstructor` on entities.
- If the project does not use Lombok, write the constructors by hand; do not add Lombok.

## Collections and streams

- Declare variables and parameters with interface types (`List`, `Map`), not implementations.
- Choose the right structure: `EnumMap`/`EnumSet` for enum keys, `LinkedHashMap` when order matters.
- Streams are for transformations, not for side effects. No `forEach` that mutates external state; no streams longer than ~5 chained operations without extracting a named method.
- Do not use `parallelStream()` in request-handling code.
- Prefer a loop when it is clearer than a stream. Readability beats cleverness.

## Exceptions (language level)

- Use unchecked exceptions for business and programming errors. Avoid checked exceptions in service signatures.
- Catch the most specific type. Never `catch (Exception e)` except at a boundary (global handler, scheduler entry point, message listener) that logs and converts.
- Never swallow: an empty `catch` is a bug. If ignoring is intentional, name the variable `ignored` and comment why.
- Wrap with context and preserve the cause: `throw new PaymentFailedException(orderId, e)`.
- Use try-with-resources for every `AutoCloseable`.
- Do not use exceptions for normal control flow.
- Application exception design is in `06-exceptions-and-logging.md`.

## Money, time, identifiers

- **Money:** `BigDecimal` plus a currency code, or a `Money` value record. Construct from `String` or `BigDecimal.valueOf`, never `new BigDecimal(double)`. Compare with `compareTo`, not `equals`. Specify scale and `RoundingMode` for operations that may round; reject unsupported fractional currency units at the boundary when the contract requires exact amounts. Match `NUMERIC/DECIMAL` precision/scale in schema and entity. Test rounding boundaries and currency mismatches.
- **Time:** `Instant` for points in time (stored UTC, column `timestamptz`), `LocalDate` for calendar dates, `Duration`/`Period` for spans. No `java.util.Date`, `Calendar`, or `SimpleDateFormat`. Inject `java.time.Clock`; tests use `Clock.fixed(...)`.
- **IDs:** `UUID` for public identifiers by default (non-guessable). `Long` surrogate keys are acceptable internally. Never expose sequential IDs when enumeration is a risk.
- **Enums:** persist by name (`@Enumerated(EnumType.STRING)`), never by ordinal. Expose stable string values in the API.

## Size and complexity limits (guidelines that trigger refactoring)

| Metric | Limit |
|---|---|
| Method length | ~70 lines |
| Cyclomatic complexity | 10 |
| Parameters | 5 (introduce a record/command object beyond that) |
| Class length | ~400 lines (split by responsibility) |
| Nesting depth | 3 (use guard clauses / early return) |

Prefer guard clauses over nested `if/else`. Prefer polymorphism or sealed types + switch over long `if/else if` chains on type.

## Comments and Javadoc

- Code should explain itself; comments explain **why** (constraints, trade-offs, links to tickets/specs), never restate what.
- Public service interfaces: Javadoc with summary, `@param` when not obvious, `@throws` for each business exception.
- No commented-out code (git remembers). `TODO(PROJ-123): ...` only with a ticket id.
- No author tags or change logs in file headers.

## Anti-patterns to reject

- God services (`OrderService` with 40 methods) -> split by use case.
- Anemic everything: if a rule belongs to the entity (e.g. `order.cancel()` checks status), put it on the entity.
- `static` mutable state, singletons by hand, service locators (`ApplicationContext.getBean`).
- Primitive obsession for domain concepts that carry rules (email, money, quantity): use value types.
- Boolean parameters that switch behavior (`process(order, true)`): split methods or use an enum.
- Returning `Map<String, Object>` or `Object` from APIs.
- Magic numbers/strings: extract named constants or enums.
- `instanceof` chains instead of sealed types.
- Business logic in `@PostConstruct`, static initializers, or getters.
