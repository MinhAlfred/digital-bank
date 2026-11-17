# 06 - Exceptions and Logging

## Contents
- Exception hierarchy
- Error codes
- Global exception handler
- Mapping table
- Logging rules
- Log levels
- Correlation and structured logging
- Sensitive data

## Exception hierarchy

```
RuntimeException
└── BusinessException            (concrete, shared for business errors)
    └── Optional specialized type only for type-dependent handling or typed error data
```

Rules:
- Default to a shared concrete `BusinessException` for business errors handled alike. Do not throw `RuntimeException`, `IllegalStateException`, or `Exception` for business situations.
- Represent business error codes with an enum, reusing the project's existing error-code contract and HTTP mapping. If errors differ only in code, message or HTTP status, add an enum value and use the shared exception; do not create a subclass just to bind those values. Messages are in English, free of sensitive data.
- Create a subclass only when the Java type has a role in control flow: a caller must catch it separately, transaction rollback rules distinguish it by type, or the error must carry dedicated typed data. API clients distinguish ordinary business cases by stable error codes, not Java exception class names.
- Programming errors (`IllegalArgumentException` for violated preconditions, `NullPointerException`) are not business exceptions; they surface as `500`.
- Use existing structured context fields when needed; add a specialized type only when dedicated typed data is required, not merely to format an id into a message.
- Always preserve the cause when wrapping.

## Error codes

Use the enum already defined by the service. In the templates, `ErrorCode` is a shared interface implemented per feature by an enum; a service with a single `ErrorCode` enum need not adopt that extra interface:

```java
public enum OrderErrorCode implements ErrorCode {
    ORDER_NOT_FOUND("ONF01", HttpStatus.NOT_FOUND),
    ORDER_ALREADY_CANCELLED("OAC01", HttpStatus.CONFLICT);
    // Store the explicit code; code() returns it, httpStatus() returns the status.
    // See 13-code-templates.md for the complete enum.
}
```

Enum constant names are descriptive `UPPER_SNAKE_CASE`. Every constant also declares an explicit stable identifier independent of its Java name, e.g. `ACCOUNT_NOT_FOUND("ANF01", HttpStatus.NOT_FOUND, "Account not found")`. Store it in a `String code` field and expose it through the project's accessor (`getCode()` in Core). Do not derive it from `ordinal()` or infer it at runtime from the name.

Assigned identifiers are nonblank and unique within the service, including across feature enums. Never reuse or change an assigned identifier, and preserve released symbolic API codes as well. Add a test for nonblank/unique identifiers when extending the catalog. This does not require a shared business-error jar across services.

Prefer `throw new BusinessException(ErrorCode.CUSTOMER_NOT_ELIGIBLE);` when that constructor and enum exist. If the constructor also requires a message, pass it. For the template's interface plus feature enum, the equivalent is:

```java
throw new BusinessException(CustomerErrorCode.CUSTOMER_NOT_ELIGIBLE, "Customer is not active");
```

The enum value must retain the existing mapping, such as `CUSTOMER_NOT_ELIGIBLE` -> `HttpStatus.CONFLICT` (`409`). Do not introduce `CustomerNotEligibleException` solely to bind this code and status.

**Current digital-bank compatibility:** Core has an `ErrorCode` enum with the existing statuses and default messages. Use `BusinessException(ErrorCode)` or `BusinessException(ErrorCode, String message)` for new business errors:

```java
throw new BusinessException(ErrorCode.CUSTOMER_NOT_ELIGIBLE);
// Override the message when a distinct reason shares the same error code and status.
throw new BusinessException(ErrorCode.CUSTOMER_NOT_ELIGIBLE, "Customer not found");
```

The original `BusinessException(String errorCode, String message, HttpStatus status)` constructor remains for compatibility. The enum overloads adapt to it, and `getErrorCode()` still returns the symbolic name used by the existing handler and response contract. Core's assigned identifiers are `ACCOUNT_NOT_FOUND` -> `ANF01`, `CUSTOMER_NOT_ELIGIBLE` -> `CNE01`, and `UNSUPPORTED_CURRENCY` -> `UC01`. These are available through `ErrorCode.getCode()`; do not replace the current response's `errorCode` with the short identifier or add response fields without an explicit API contract change.

## Global exception handler

Reuse the existing global handler and HTTP status mappings for business errors. Preserve released error codes, statuses and the complete API response format. In Core this includes the `errorCode` and `timestamp` properties and existing ProblemDetail fields; do not rename `errorCode` to `code` or replace the handler merely to follow the template below. Technical exception handling, including database conflicts and upstream timeouts, is unchanged by this business-exception convention.

For annotated MVC controllers, use one `common/exception/handler/GlobalExceptionHandler` extending servlet `ResponseEntityExceptionHandler`. WebFlux uses the reactive class with the same simple name and different signatures/exceptions; functional routes/gateways may need `WebExceptionHandler`/`ErrorWebExceptionHandler`. Security filter-chain failures require matching entry points/access-denied handlers in `security.handler`. Shared response construction such as `ProblemDetailFactory` belongs in `common.web`; feature `web` packages contain controllers only. Keep one consistent ProblemDetail contract across these boundaries. See `13-code-templates.md` for the template. It must:

1. Map `BusinessException` through its existing error-code/status mapping and response contract; for new APIs the default is status, `code`, `detail`, plus `traceId`.
2. Override validation handling (`MethodArgumentNotValidException`, `HandlerMethodValidationException`, `ConstraintViolationException`) -> `400` with `code = VALIDATION_ERROR` and an `errors` list of field/message pairs.
3. Map `ObjectOptimisticLockingFailureException` -> `409`, `DataIntegrityViolationException` -> `409` (without leaking constraint/SQL text), `AccessDeniedException` -> `403`, `AuthenticationException` -> `401` (normally handled by the security filter chain).
4. Map unreadable body (`HttpMessageNotReadableException`) -> `400` with a generic message (never echo parser internals).
5. Catch-all `Exception` -> `500`, `code = INTERNAL_ERROR`, generic `detail` ("An unexpected error occurred"), logs the full stack trace **once**, includes `traceId` so support can find it.

Do not create additional per-controller advices unless a controller truly needs a different representation.

## Mapping table

| Cause | HTTP | `code` |
|---|---|---|
| Bean Validation failed | 400 | `VALIDATION_ERROR` |
| Malformed JSON / wrong type | 400 | `MALFORMED_REQUEST` |
| Not authenticated | 401 | `UNAUTHENTICATED` |
| Not permitted | 403 | `ACCESS_DENIED` |
| Resource missing | 404 | `<ENTITY>_NOT_FOUND` |
| Unsupported method / media type | 405 / 415 | framework default via ProblemDetail |
| Duplicate / illegal state / stale version | 409 | specific code |
| Rate limited | 429 | `RATE_LIMITED` |
| Upstream failure | 502 / 503 / 504 | `UPSTREAM_ERROR` / `UPSTREAM_TIMEOUT` |
| Anything else | 500 | `INTERNAL_ERROR` |

## Logging rules

- Facade: **SLF4J**, declared with Lombok `@Slf4j` when Lombok is configured; backend Logback (Boot default). Manual `LoggerFactory` declarations are only for projects without Lombok. No `System.out`, `printStackTrace()`, `java.util.logging`, or commons-logging calls.
- **Parameterized messages:** `log.info("Order cancelled id={} by={}", orderId, userId)`. Never string concatenation or `String.format`. Guard only genuinely expensive argument construction.
- Use `key=value` pairs in messages for greppability.
- **Log once.** Either handle an exception or rethrow it, never both. The global handler is where unexpected exceptions get logged with stack trace; lower layers do not log-and-rethrow.
- Exceptions go as the **last argument** without a placeholder: `log.error("Payment capture failed orderId={}", orderId, e)`.
- Log at boundaries and state changes: request outcome (access log), start/end of jobs, external calls (duration + outcome), business state transitions. Do not log inside tight loops.
- Never use logs as control flow or an audit trail. Audit events that must be durable go to an audit table/stream.
- Use the `log` field generated by `@Slf4j`; do not declare a second logger or pass one around.

## Log levels

| Level | Use |
|---|---|
| `ERROR` | Operation failed and requires attention; unexpected exceptions; data-loss risk. Alertable. |
| `WARN` | Unexpected but handled (retry succeeded, degraded mode, rejected suspicious input, 4xx caused by system issues). Not for normal client errors |
| `INFO` | Business milestones and lifecycle (startup, job finished, order created/cancelled). Low volume |
| `DEBUG` | Developer diagnostics. Off in production by default |
| `TRACE` | Very fine detail. Never in production |

Expected business exceptions (`404`, `409`, validation) are logged at `INFO` or `DEBUG` **without stack trace**, or not at all; they are not errors in the system. Only `5xx` produce `ERROR` with stack trace.

## Correlation and structured logging

- Every request has a trace/correlation id: use Micrometer Tracing (trace id/span id in the MDC automatically) and return it as `traceId` in error responses and an `X-Request-Id`/`traceparent` response header.
- Production output is **structured JSON** using Spring Boot's built-in support:

```yaml
logging:
  structured:
    format:
      console: ecs        # or logstash / gelf, as the platform expects
```

- In imperative code, add business context to the MDC when useful (`orderId`, `customerId`, never PII) and clear it in `finally` (`try (var ignored = MDC.putCloseable("orderId", id))`).
- Local profile may use plain text output.
- Log levels are set per package in `application.yml`; framework noise is kept at `WARN`.

## Sensitive data

Never log: passwords, tokens, API keys, session ids, full card numbers, national ids, full request/response bodies of auth or payment endpoints, personal data (email, phone, address) unless masked. When an identifier is needed, log the internal id.

- Mask in the DTO's `toString()` (records print every field: override or avoid logging them wholesale).
- Do not log `Authorization` headers; configure any request-logging filter to exclude headers and bodies by default.
- Exception messages must not embed secrets or personal data; they may be returned to clients and stored in log systems.
- Log injection: parameterized logging plus never logging raw user input in a way that allows newline forging (structured JSON output handles this).
## Reactive logging context

Use Reactor Context and configured Micrometer/context-propagation integration for WebFlux tracing. Thread-local MDC put/clear around publisher assembly does not carry context through asynchronous execution. Never log request headers/tokens while diagnosing a gateway; use trace id and sanitized outcome metadata.
