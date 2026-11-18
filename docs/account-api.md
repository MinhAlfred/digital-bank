# Account APIs — Step 5

Core exposes three authenticated endpoints on its configured port (locally `4000`). Account ownership is resolved through Customer's `/api/customers/me` using the caller's validated token. See the [API contract](api/customer-account-contract.md) for all response fields and error mappings.

| Endpoint | Result |
|---|---|
| `POST /api/accounts` | Create an ACTIVE customer account with balance zero; return `201` and `Location` |
| `GET /api/accounts/{accountId}` | Return the owner's account details |
| `GET /api/accounts/{accountId}/balance` | Return the owner's exact balance and currency metadata |

Successful responses use `Cache-Control: no-store`. Account response records contain no JPA entities or version. `closedAt` is present with `null` for an open account. `balanceMinor` is a string representing the existing BIGINT minor-unit balance; `minorUnit` comes from Core's currencies table (VND: 0, USD: 2). No schema or stored balance conversion is performed.

## Requests and validation

Creation accepts only `productType` (`CHECKING` or `SAVINGS`) and `currency` (exactly three uppercase ASCII letters, supported by Core):

```json
{
  "productType": "CHECKING",
  "currency": "VND"
}
```

Missing/null required fields and incorrectly formatted currency return `400 VALIDATION_ERROR`, with field/message pairs. Malformed JSON, wrong types, unknown product types, malformed UUIDs, or additional request properties return `400 MALFORMED_REQUEST`. Clients cannot set owner, account number, status, balance, timestamps or version. Lowercase currency is rejected rather than normalized.

Core retains its existing business ProblemDetail shape (`errorCode`, `timestamp`, and standard fields), stable symbolic errors, and legacy `IllegalArgumentException` response. The shared handler is now in `common.exception.handler`; response construction is in `common.web`.

## Ownership and persistence

Missing customers cannot create accounts. SUSPENDED/CLOSED customers cannot create new accounts but can read accounts they already own. Missing, SYSTEM and other owners' accounts return the same `404 ACCOUNT_NOT_FOUND`. This applies equally to account details and balance.

Customer HTTP lookup finishes before the local database transaction begins. Account creation flushes the audited entity before mapping the response, and writes opening history in the same transaction. Reads map the account and currency metadata inside a read-only local transaction. MapStruct handles entity-to-response mappings with strict unmapped target checking.

## Run locally

Start the repository PostgreSQL infrastructure and Customer service. Core needs its existing local datasource/security configuration, and its [Customer integration settings](core-customer-integration.md). Use a token accepted by both backends:

```powershell
$headers = @{ Authorization = "Bearer $env:ACCESS_TOKEN" }
$request = @{ productType = 'CHECKING'; currency = 'VND' } | ConvertTo-Json
$account = Invoke-RestMethod -Method Post -Uri http://localhost:4000/api/accounts `
    -Headers $headers -ContentType application/json -Body $request
Invoke-RestMethod -Uri "http://localhost:4000/api/accounts/$($account.id)" -Headers $headers
Invoke-RestMethod -Uri "http://localhost:4000/api/accounts/$($account.id)/balance" -Headers $headers
```

Account POST has no idempotency contract at this stage. Each successful request creates a new account; do not automatically retry after an uncertain result.

## Verification

From `core-banking-service`, using JDK 25:

```powershell
.\mvnw.cmd "-Dtest=AccountControllerTest,AccountServiceImplTest,AccountMapperTest,HttpCustomerGatewayTest,CustomerClientConfigurationTest,ErrorCodeTest,GlobalExceptionHandlerTest" test
```

Focused tests cover authenticated routing, token forwarding, creation headers, exact response fields, malformed/forged input, hidden accounts, currency metadata, large integer balances, and transaction ordering/rollback requests. Mocked transactions alone do not establish database rollback.

With Docker Engine running, execute the PostgreSQL tests:

```powershell
.\mvnw.cmd "-Dtest=AccountApiPersistenceTest" test
```

These use PostgreSQL 15, real Flyway migrations, Spring-managed Testcontainers, and real local transactions. They check persisted timestamps/history, create → read, exact large balances, and rollback of the account when a real history constraint fails. They do not make real calls to Customer; the lookup is stubbed and asserts it executes outside the database transaction.

Step 6 still covers further account rule verification. Gateway routes, JWT issuer/audience alignment and consistent security-filter errors remain Step 7; full deployed end-to-end verification remains Step 8.
