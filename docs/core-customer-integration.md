# Core → Customer integration — Step 4

Core resolves the current customer by calling `GET /api/customers/me` with the authenticated caller's access token. The consumer-owned `CustomerInfo` contains only `id` and `status`; extra Customer response fields are ignored. No Customer database access or shared entity/DTO library is involved.

## Configuration

The adapter uses Spring Boot's `RestClient.Builder` with a JDK HTTP client. Settings are validated at startup under `app.customer-service`:

```yaml
app:
  customer-service:
    base-url: http://localhost:4001
    connect-timeout: 2s
    read-timeout: 30s
```

These are also the defaults when no overrides are provided. Merge this block into Core's existing local `application.yml`, or override it with `APP_CUSTOMER_SERVICE_BASE_URL`, `APP_CUSTOMER_SERVICE_CONNECT_TIMEOUT` and `APP_CUSTOMER_SERVICE_READ_TIMEOUT`. Replace the earlier local URL using port `8081` with port `4001`. Local configuration remains ignored by Git. Use HTTPS when traffic leaves the local development environment.

The base URL must be HTTP(S), have a host, and omit credentials, query and fragment. Timeouts must be at least 1 ms. HTTP redirects are disabled so a downstream response cannot redirect the forwarded token. The adapter adds no retries.

## Account service contract

`CustomerGateway.findCurrentCustomer(bearerToken)` receives a validated **raw access token**, without the `Bearer ` prefix. The adapter adds that prefix when sending the Authorization header. A controller must obtain the token from Spring Security's validated authentication, never from an owner ID or internal identity header.

The internal Java service signatures are now:

```java
AccountResponse openAccount(ProductType productType, String currency, String bearerToken);
AccountResponse getOwnedAccount(UUID accountId, String bearerToken);
AccountBalanceResponse getOwnedAccountBalance(UUID accountId, String bearerToken);
```

Opening an account derives its owner from `/me` and requires an ACTIVE customer. The remote lookup completes before the local transaction that creates the account and its status history. A history write failure rolls back that transaction.

Reads resolve the current customer before accessing the account repository. Missing customers, missing accounts, another owner's accounts and SYSTEM accounts all produce `404 ACCOUNT_NOT_FOUND`. Customer SUSPENDED/CLOSED and Account FROZEN/CLOSED statuses do not prevent the verified owner from reading.

## Error handling

| Customer outcome | Core adapter outcome |
|---|---|
| 200 with UUID and ACTIVE/SUSPENDED/CLOSED status | Return customer ID and status |
| 404 | Empty lookup; account creation maps to `409 CUSTOMER_NOT_ELIGIBLE`, reads to `404 ACCOUNT_NOT_FOUND` |
| 401 / 403 | `401 UNAUTHENTICATED` / `403 ACCESS_DENIED` |
| Connect/I/O failure or 5xx | `503 CUSTOMER_SERVICE_UNAVAILABLE` |
| Connect/read timeout | `504 UPSTREAM_TIMEOUT` |
| Invalid/empty body, missing/invalid ID or status, unexpected HTTP status | `502 INVALID_CUSTOMER_RESPONSE` |

Errors use Core's existing ProblemDetail contract, including `errorCode` and `timestamp`. Messages do not expose the upstream response body. Failed lookups stop account reads and writes.

## Verification and remaining steps

Use JDK 25 and the Core Maven Wrapper:

```powershell
Set-Location core-banking-service
.\mvnw.cmd "-Dtest=HttpCustomerGatewayTest,CustomerClientConfigurationTest,AccountServiceImplTest,ErrorCodeTest,GlobalExceptionHandlerTest" test
```

These checks cover wire requests, error mapping, configuration validation, a real local HTTP read timeout, redirect rejection, ownership and transaction ordering. They do not require PostgreSQL or Kafka and do not establish end-to-end integration between deployed services.

[Step 5](account-api.md) adds Account controllers and response DTOs, mapping the account and currency metadata inside local transactions. JWT key/issuer/audience alignment across Gateway, Core and Customer and the Account gateway route remain for Step 7. Core's current security configuration is unchanged by this adapter implementation.
