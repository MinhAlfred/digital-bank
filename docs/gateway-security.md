# Gateway routing and JWT alignment — Step 7

Gateway, Core and Customer now use the same JWT configuration contract. Each service owns its configuration and independently validates the token; Gateway identity headers do not authorize backend access.

## Configuration

Provide the same variables to all three Java applications:

| Variable | Meaning |
|---|---|
| `APP_SECURITY_JWT_SECRET` | Shared HS256 signing key as raw UTF-8 text, at least 32 characters |
| `APP_SECURITY_JWT_ISSUER` | Exact trusted `iss` value |
| `APP_SECURITY_JWT_AUDIENCE` | Required value in `aud` |

They bind to validated `app.security.jwt` properties. There is no production secret default; property records redact secrets from `toString()`.

All services require HS256, matching issuer/audience, a present expiry, valid expiry/not-before times with a 60-second clock tolerance, and a nonblank subject of at most 100 characters. Subject is opaque and case-sensitive, never a Customer UUID. Role is optional. Supply tokens through one `Authorization: Bearer <access-token>` header. Gateway no longer accepts a query-string token as an authentication fallback.

Gateway keeps JJWT and its reactive pipeline; Core and Customer use servlet Spring Security with Nimbus. No runtime upgrade or shared business library is introduced.

Gateway previously used Base64-decoded `jwt.secret`; Core used `app.security.jwt-secret` without issuer/audience checks. Both now use **`app.security.jwt.secret`, `.issuer`, `.audience`**, matching Customer. The key is raw UTF-8, not Base64-decoded. Update the applications and the trusted token issuer together; old tokens missing the required claims are rejected.

Safe examples are tracked at:

- `gateway-service/src/main/resources/application.yml.example`
- `core-banking-service/src/main/resources/application.yml.example`
- `customer-service/src/main/resources/application.yml.example`

Merge them into your local configuration while preserving unrelated settings. Actual `application.yml` files remain Git-ignored. Environment variables override legacy local values. Test signing keys are only for tests. Core's local Compose database is `localhost:5433/core_banking_db`; Customer's is `localhost:5437/customer_db`.

Gateway also needs `GATEWAY_INTERNAL_SECRET` (`gateway.internal-secret`). This compatibility header is sanitized and set by Gateway, but does not replace backend JWT validation.

## Protected routes

Routes, service destinations, filter settings, ordering and timeouts are defined in the tracked `gateway-service/src/main/resources/gateway-routes.yml`. Local `application.yml` imports it:

```yaml
spring:
  config:
    import: classpath:gateway-routes.yml
```

Keep route and service-instance definitions in this imported file, rather than duplicating them in local configuration. The application example and test profile use the same import. Java implements authentication filters but no longer declares service routes or service-specific URL properties.

| Incoming path | Default destination | Override |
|---|---|---|
| `/api/customers` and `/api/customers/**` | `lb://customer-service` → `http://localhost:4001` | `CUSTOMER_SERVICE_URI` |
| `/api/accounts`, `/api/accounts/**` and legacy `/api/core/**` | `lb://core-banking-service` → `http://localhost:4000` | `CORE_BANKING_SERVICE_URI` |

The existing notification, fraud and analysis routes are retained. Their destinations use `NOTIFICATION_SERVICE_URI`, `FRAUD_DETECTION_SERVICE_URI` and `ANALYSIS_SERVICE_URI`, with ports 4002, 4003 and 4004 as local defaults. Spring Cloud's simple discovery configuration supplies instances to the existing load balancer.

Paths, bodies and Authorization are forwarded unchanged. These routes always require authentication, even if included in legacy `public.endpoints`. Legacy public endpoint prefix matching now respects path segment boundaries.

Gateway removes client-supplied `X-User-Id`, `X-User-Role` and `X-Internal-Secret`. It can re-add compatibility identity headers from validated claims, and sets its own internal secret. Headers containing control characters are not emitted. Core still resolves the actual Customer ID through `/me` and enforces ownership itself.

Bank routes specify `metadata.connect-timeout: 2000` and `metadata.response-timeout: 35000`. Spring Cloud Gateway requires these per-route metadata values in milliseconds, giving a 2-second connect timeout and a 35-second response timeout. No retry filter is added.

To add an API prefix, edit the appropriate `Path` predicate in this YAML. To add a service, add a route and its discovery instance configuration; no Java route class or constants file is needed. Set `AuthFilter`'s `authenticationRequired: true` for protected routes. Standard YAML changes take effect after restarting Gateway; automatic configuration refresh is not configured.

## Errors and logs

Core now matches Customer's security handlers: `401 UNAUTHENTICATED` and `403 ACCESS_DENIED` use `application/problem+json`, `errorCode`, `timestamp` and generic details. A 401 includes `WWW-Authenticate: Bearer`. Ordinary business error contracts remain unchanged.

Gateway preserves its existing `application/json` body, e.g. `{"status":401,"message":"Missing token"}` or `Invalid token`. Unifying that format with backend ProblemDetail requires a separate contract change. Framework/network routing errors are not converted by this authentication filter. Clients should use HTTP status for errors rejected at Gateway.

Gateway no longer logs tokens, token fragments, claims, identity values, internal secrets or complete headers. Authentication rejection logs include only status.

## Verification

Using JDK 25 and the Maven Wrapper in each service:

```powershell
# gateway-service: actual local Gateway with stub downstream HTTP servers
.\mvnw.cmd test

# core-banking-service
.\mvnw.cmd "-Dtest=SecurityConfigTest,SecurityProblemHandlerTest,AccountControllerTest" test

# customer-service
.\mvnw.cmd "-Dtest=SecurityConfigurationTest,SecurityProblemHandlerTest,CustomerControllerTest" test
```

Results: the YAML routing refactor passed a clean Gateway build with **42 tests**, including checks for loaded route definitions, authentication settings, metadata timeouts, legacy routes, routing and header sanitization. The previous Step 7 security checks passed **53 Core** and **42 Customer** tests; those services are unchanged by this routing refactor. All three use matching test-only key/issuer/audience settings.

Step 8 still verifies actual running Gateway, Core, Customer and their databases together. Gateway tests use stub backends and controller tests mock business services; this verification does not establish deployed end-to-end behavior.
