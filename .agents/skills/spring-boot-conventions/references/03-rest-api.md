# 03 - REST API Design

## Contents
- Controller rules
- URLs and methods
- Status codes
- Request and response DTOs
- Pagination, sorting, filtering
- Error responses (RFC 9457)
- Versioning
- Idempotency, concurrency, caching
- JSON conventions (Jackson 3)
- OpenAPI documentation
- Outbound API clients

## Controller rules

Choose MVC or WebFlux from the service runtime. Controller annotations may look similar, but clients, security, error handlers and test utilities differ.

- Annotate with `@RestController` and a class-level `@RequestMapping("/api/v1/<plural-resource>")`.
- Package-private class is fine and preferred (`class OrderController`); controllers are not called from other code.
- One controller method = one service call. Map request -> service -> response. Nothing else.
- Return DTOs, never entities. Return `ResponseEntity<T>` only when you need a non-default status or headers (`201 Created` + `Location`, `ETag`); otherwise return `T` with `@ResponseStatus`.
- Parameters: `@PathVariable`, `@RequestParam`, `@RequestBody @Valid`. For Spring MVC 6.1+, prefer built-in method validation for path/query constraints; remove class-level `@Validated` to use it. Preserve an intentional AOP validation setup only when required. Verify the target runtime/version.
- No `try/catch` in controllers; the global handler converts exceptions.
- Never read `HttpServletRequest` directly unless unavoidable. Never return `void` for creates.
- Authentication principal: inject `@AuthenticationPrincipal` or `Authentication`, pass the user id to the service. Do not trust a `userId` sent in the body or path for "my" resources.

## URLs and methods

- Resource names are **plural nouns, kebab-case, lowercase**: `/api/v1/purchase-orders/{orderId}/items`. No verbs in paths, no trailing slash, no file extensions.
- Nesting max two levels. Beyond that, address the child directly (`/order-items/{id}`).
- Path variable names are descriptive (`{orderId}`), consistent with the DTO field names.
- Methods:

| Method | Use | Idempotent | Success |
|---|---|---|---|
| `GET` | read, never mutates | yes | `200` |
| `POST` | create, or run a non-CRUD action | no | `201` (create) / `200` / `202` (async accepted) |
| `PUT` | full replace of a resource | yes | `200` / `204` |
| `PATCH` | partial update | not guaranteed | `200` |
| `DELETE` | remove | yes | `204` |

- Domain actions that do not map to CRUD are modeled as sub-resource `POST`: `POST /api/v1/orders/{id}/cancel`, not `PUT /orders/{id}?status=CANCELLED`.
- `GET` must never change state. `POST` for search is acceptable only when criteria are too large for a query string.

## Status codes

| Code | When |
|---|---|
| `200 OK` | Successful read/update returning a body |
| `201 Created` | Resource created; include `Location` header and the created representation |
| `202 Accepted` | Work accepted for async processing; return a status resource URL |
| `204 No Content` | Success with no body (delete, some updates) |
| `400 Bad Request` | Malformed JSON, failed validation, bad parameters |
| `401 Unauthorized` | Not authenticated |
| `403 Forbidden` | Authenticated but not allowed |
| `404 Not Found` | Resource missing (also used to hide existence from unauthorized callers) |
| `409 Conflict` | State conflict, duplicate unique value, optimistic-lock failure |
| `412 / 428` | Failed / missing precondition (`If-Match`) |
| `422 Unprocessable Content` | Syntactically valid but violates a business rule (use consistently or not at all; default to `409`/`400` if the project already does) |
| `429 Too Many Requests` | Rate limited (`Retry-After`) |
| `500` | Unexpected server error (generic body) |
| `502/503/504` | Upstream failure / unavailable / timeout |

Never return `200` with an error payload. Never return `500` for client mistakes.

## Request and response DTOs

- Records in this service's `common.dto.<feature>` package. Generic wrappers use `common.dto`; remote wire DTOs use `common.dto.external.<provider>`. No shared DTO jar between services. Separate request and response types; never reuse one record for create, update, and read.
- Naming: `CreateOrderRequest`, `UpdateOrderRequest`, `OrderResponse` (detail), `OrderSummary` (list item), `OrderSearchCriteria` (query object).
- Put Bean Validation on request records:

```java
public record CreateOrderRequest(
        @NotEmpty @Size(max = 100) List<@Valid OrderLine> lines,
        @Nullable @Size(max = 500) String note) {

    public record OrderLine(@NotNull UUID productId, @Positive int quantity) {}
}
```

- For self-service creation, derive the owner from the authenticated principal (or a verified local customer mapping) and pass it separately to the service. Creating for someone else requires a distinct permission and a service-level check.
- Mass-assignment protection: DTOs expose **only** the fields clients may set. `id`, `version`, `createdAt`, `status`, owner fields are never in a create/update request.
- Response DTOs contain no internal fields (password hashes, internal flags, DB ids if you expose UUIDs).
- IDs: UUID strings. Dates: ISO-8601 (`2026-11-12T10:15:30Z` for instants, `2026-11-12` for dates). Money: object `{ "amount": "19.99", "currency": "USD" }` with the amount as a string or fixed-scale decimal. Enums: stable uppercase strings.
- Booleans are `true/false`, never `"Y"/"N"` or `0/1`. Absent optional values: omit the field or return `null` consistently (project-wide setting `spring.jackson.default-property-inclusion=non_null` is allowed).
- Do not return bare arrays at the top level; wrap collections (see pagination) so fields can be added later.

## Pagination, sorting, filtering

- **Every collection endpoint is paginated.** No unbounded `findAll()` exposed.
- Query parameters: `page` (0-based by default), `size`, `sort=field,direction` (`sort=createdAt,desc`). Defaults: `size=20`, max `100`:

```yaml
spring:
  data:
    web:
      pageable:
        default-page-size: 20
        max-page-size: 100
```

- Accept `Pageable` in the controller, pass it to the service. **Whitelist sortable fields** in the service/spec (do not allow sorting by arbitrary properties or by lazy associations).
- Return the project wrapper `PageResponse<T>` (record: `items`, `page`, `size`, `totalElements`, `totalPages`), not a serialized `PageImpl`. If `Page` must be returned directly, configure `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)`.
- For very large or fast-changing data prefer keyset (cursor) pagination: `?after=<cursor>&limit=50`, response includes `nextCursor`.
- Filtering: simple equality filters as named query parameters (`status=PAID&customerId=...`) bound into a `OrderSearchCriteria` record; build queries with `Specification` or a repository method. Date ranges: `createdFrom`, `createdTo`.

## Error responses (RFC 9457)

Use `application/problem+json` consistently through the runtime-appropriate handler (`06-exceptions-and-logging.md`). Security-filter errors require their own entry point/access-denied handling. For MVC, enable framework ProblemDetail support:

```yaml
spring:
  mvc:
    problemdetails:
      enabled: true
```

Shape:

```json
{
  "type": "https://api.acme.com/problems/order-already-cancelled",
  "title": "Order already cancelled",
  "status": 409,
  "detail": "Order 6b1f... is already cancelled.",
  "instance": "/api/v1/orders/6b1f.../cancel",
  "code": "ORDER_ALREADY_CANCELLED",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

Validation failures: `status 400`, `code: "VALIDATION_ERROR"`, and an `errors` array of `{ "field": "lines[0].quantity", "message": "must be greater than 0" }`.

`code` is a **stable machine-readable identifier** that clients may switch on. `detail` is human text and may change. Never include stack traces, SQL, class names, or internal hostnames.

## Versioning

- Breaking changes require a new version. Default is **URI versioning**: `/api/v1/...`, `/api/v2/...`.
- If the project uses Spring Framework 7's built-in API versioning (`version` attribute on mapping annotations plus the `ApiVersionConfigurer`/`spring.mvc.apiversion.*` settings), keep to one strategy project-wide; do not mix.
- Non-breaking additions (new optional fields, new endpoints) do not bump the version. Removing/renaming fields, changing types or semantics does.
- Deprecate with `Deprecation` and `Sunset` response headers and a documented removal date before deleting a version.

## Idempotency, concurrency, caching

- `POST` endpoints that clients may retry (payments, order creation) accept an `Idempotency-Key` header; the service stores the key + result and returns the original response on replay.
- Optimistic concurrency: expose `version` via `ETag` and require `If-Match` on `PUT/PATCH/DELETE` of contended resources; map `ObjectOptimisticLockingFailureException` to `409`.
- Cacheable `GET`s set `Cache-Control` and `ETag` deliberately. Authenticated/personal responses default to `Cache-Control: no-store`.

## JSON conventions (Jackson 3)

- Spring Boot 4 uses **Jackson 3** (`tools.jackson.*` packages, `JsonMapper`). Annotations (`@JsonProperty`, `@JsonIgnore`, ...) remain in `com.fasterxml.jackson.annotation`. Do not mix Jackson 2 `ObjectMapper` types into new code.
- Property naming: `camelCase`. Keep `spring.jackson.*` customizations in `application.yml`; use a `JsonMapperBuilderCustomizer`-style customizer bean for code-level tweaks rather than constructing your own mapper.
- Reject unknown properties on **request** bodies when strictness is a product requirement (`spring.jackson.deserialization.fail-on-unknown-properties`); otherwise stay tolerant (be liberal in what you accept).
- Never enable default typing / polymorphic deserialization on untrusted input.

## OpenAPI documentation

- Use `springdoc-openapi` (pick the release that explicitly supports the project's Spring Boot version) to generate the spec. Annotate sparingly: `@Operation(summary=...)` and `@ApiResponse` for non-obvious responses; do not duplicate what the code already shows.
- Expose Swagger UI only in non-production profiles, or behind authentication.
- Treat the generated spec as a contract: breaking changes are caught in CI (e.g. `openapi-diff`).

## Outbound API clients

- Blocking services: use `RestClient` or `@HttpExchange` backed by it. Reactive services/gateways: use `WebClient` or a reactive HTTP interface with reactive return types. Build clients from the auto-configured builders. Do not add blocking HTTP to a reactive event loop or use `RestTemplate` in new code.
- Always set connect and response/read timeouts. Blocking auto-configured clients may use `spring.http.client.connect-timeout` / `spring.http.client.read-timeout` for the target Boot release. WebClient needs timeout/pool limits on its reactive connector; do not assume blocking-client properties configure it. Check the actual connector/version.
- Map remote errors to domain exceptions in one place (error handler / adapter class) so remote status codes do not leak through the service layer.
- Apply retry only to idempotent calls, with backoff and a cap (`@Retryable` from Spring Framework 7 core, or Resilience4j if already present). Add a bulkhead/concurrency limit for slow dependencies.
- Wrap each external system behind its own adapter/port class (`PaymentGateway`) with an interface so tests can substitute it.
## WebFlux and gateway path

- Compose `Mono`/`Flux`; return the pipeline so Spring manages subscription and cancellation. No `block()`, `blockFirst()`, manual `subscribe()` or blocking JPA/HTTP in handlers/filters.
- Use bounded reactive queries/pagination. Do not assume `spring.data.web.pageable` config for MVC will bound a custom WebFlux endpoint; validate its limit explicitly.
- For WebFlux `ProblemDetail`, use the reactive `ResponseEntityExceptionHandler` (`org.springframework.web.reactive.result.method.annotation`) and relevant reactive binding/validation exceptions. Functional routes/gateway filters may require a `WebExceptionHandler`/`ErrorWebExceptionHandler` or an explicit response writer. Do not copy servlet `WebRequest` overrides.
- Gateway filters sanitize caller-supplied identity/internal headers before forwarding verified values; preserve authentication, trace context and cancellation. Backends enforce authorization independently.
- Test with WebTestClient and StepVerifier as appropriate, including rejected headers, unauthenticated access, upstream timeout and cancellation paths.
