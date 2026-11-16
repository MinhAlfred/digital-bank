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
└── BusinessException            (abstract, carries an ErrorCode)
    ├── ResourceNotFoundException   -> 404
    ├── ConflictException           -> 409 (duplicate, illegal state, version clash)
    ├── ValidationException         -> 400/422 (business-level validation, not Bean Validation)
    ├── ForbiddenOperationException -> 403
    └── <Feature>-specific exceptions, e.g. OrderAlreadyCancelledException extends ConflictException
```

Rules:
- Business problems are unchecked and extend `BusinessException`. Do not throw `RuntimeException`, `IllegalStateException`, or `Exception` for business situations.
- Each exception carries an `ErrorCode` with a stable string `code`, an HTTP status, and a message template. Messages are in English, free of sensitive data.
- Create a feature-specific exception when a client or caller needs to distinguish the case; otherwise use the generic ones with a specific `ErrorCode`.
- Programming errors (`IllegalArgumentException` for violated preconditions, `NullPointerException`) are not business exceptions; they surface as `500`.
- Include context in the exception fields (ids), not only in the message string, so the handler and logs can use it.
- Always preserve the cause when wrapping.

`ErrorCode` is an interface implemented per feature by an enum:

```java
public enum OrderErrorCode implements ErrorCode {
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND),
    ORDER_ALREADY_CANCELLED(HttpStatus.CONFLICT);
    // code() returns name(); httpStatus() returns the status
}
```

Codes are `UPPER_SNAKE_CASE`, globally unique, never reused, never renamed once released (clients depend on them).

## Global exception handler

Exactly **one** `@RestControllerAdvice` (`common/web/GlobalExceptionHandler`) extends `ResponseEntityExceptionHandler` and produces `ProblemDetail` for every error. See `13-code-templates.md` for the template. It must:

1. Map `BusinessException` -> its status, `code`, `detail`, plus `traceId`.
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

- Facade: **SLF4J** (`@Slf4j` or `LoggerFactory.getLogger(X.class)`); backend Logback (Boot default). No `System.out`, `printStackTrace()`, `java.util.logging`, or commons-logging calls.
- **Parameterized messages:** `log.info("Order cancelled id={} by={}", orderId, userId)`. Never string concatenation or `String.format`. Guard only genuinely expensive argument construction.
- Use `key=value` pairs in messages for greppability.
- **Log once.** Either handle an exception or rethrow it, never both. The global handler is where unexpected exceptions get logged with stack trace; lower layers do not log-and-rethrow.
- Exceptions go as the **last argument** without a placeholder: `log.error("Payment capture failed orderId={}", orderId, e)`.
- Log at boundaries and state changes: request outcome (access log), start/end of jobs, external calls (duration + outcome), business state transitions. Do not log inside tight loops.
- Never use logs as control flow or an audit trail. Audit events that must be durable go to an audit table/stream.
- Logger fields: `private static final Logger log` (Lombok `@Slf4j` equivalent). No logger passed around.

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

- Add business context to the MDC when useful (`orderId`, `customerId`, never PII) and clear it in `finally` (`try (var ignored = MDC.putCloseable("orderId", id))`).
- Local profile may use plain text output.
- Log levels are set per package in `application.yml`; framework noise is kept at `WARN`.

## Sensitive data

Never log: passwords, tokens, API keys, session ids, full card numbers, national ids, full request/response bodies of auth or payment endpoints, personal data (email, phone, address) unless masked. When an identifier is needed, log the internal id.

- Mask in the DTO's `toString()` (records print every field: override or avoid logging them wholesale).
- Do not log `Authorization` headers; configure any request-logging filter to exclude headers and bodies by default.
- Exception messages must not embed secrets or personal data; they may be returned to clients and stored in log systems.
- Log injection: parameterized logging plus never logging raw user input in a way that allows newline forging (structured JSON output handles this).