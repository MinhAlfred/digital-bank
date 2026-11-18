# Account rules — Step 6

Step 6 verifies the existing account rules at domain, service and PostgreSQL levels. It adds no endpoints or migrations and does not change the balance unit or API contracts.

## Verified rules

| Rule | Evidence |
|---|---|
| CHECKING/SAVINGS accounts in VND/USD start ACTIVE, owned by the resolved customer, with balance zero and no closed timestamp | `AccountRulesTest`, service and persistence tests |
| Only ACTIVE accounts satisfy `isActive()`; FROZEN/CLOSED return false | `AccountRulesTest` |
| An owner can read account details and balance across all nine combinations of Customer ACTIVE/SUSPENDED/CLOSED and Account ACTIVE/FROZEN/CLOSED | `AccountApiPersistenceTest` |
| Missing customer/account, SYSTEM account and another owner's account all return `404 ACCOUNT_NOT_FOUND` for details and balance | `AccountApiPersistenceTest` |
| SUSPENDED/CLOSED customers cannot open accounts; no account/history is persisted | `AccountApiPersistenceTest` |
| Customer accounts require an owner; SYSTEM accounts must have no owner | PostgreSQL `ck_owner` checks in `AccountSchemaTest` |
| Customer accounts cannot hold negative balances; SYSTEM accounts may hold negative balances | PostgreSQL `ck_balance_non_negative` checks |
| CLOSED requires `closed_at`; ACTIVE/FROZEN require it to be null | PostgreSQL `ck_closed` checks |
| Currency must exist; account numbers must be unique | Foreign-key and unique-constraint checks |
| VND has minor unit 0 and USD has minor unit 2; large BIGINT balances retain exact string values | Schema, mapper and persistence tests |
| Creation writes audited timestamps and history; a history constraint failure rolls back the account | `AccountApiPersistenceTest` |
| Customer lookup runs outside Core's local database transactions | Assertions in the persistence test's Customer lookup stub |

The old owner/negative-balance schema test placeholders now execute real SQL and assert the specific rejected constraint. Schema tests import JPA auditing and use the shared Spring-managed PostgreSQL test configuration, with no static container field. Each schema test rolls back its transaction; API persistence tests explicitly clean their isolated test database between cases.

## Verification

With JDK 25 and Docker Engine running, from `core-banking-service`:

```powershell
.\mvnw.cmd "-Dtest=AccountRulesTest,AccountSchemaTest,AccountApiPersistenceTest,AccountServiceImplTest,AccountMapperTest" test
```

This selection ran successfully on PostgreSQL 15 using Testcontainers and the real Flyway migrations: **74 tests, 0 failures, 0 errors, 0 skipped**. It also completes the PostgreSQL verification that was pending after Step 5.

## Remaining work

`isActive()` is a state predicate, not transaction-flow enforcement. When deposits, withdrawals or transfers are introduced, those service/domain operations must check account eligibility and implement their own concurrency/idempotency rules and tests. No transaction or status-change endpoint exists in this step.

Step 7 adds the Account gateway route, aligns JWT keys/issuer/audience across services and standardizes security-filter responses. Step 8 verifies the deployed multi-service flow. The current persistence tests stub Customer HTTP responses and do not establish end-to-end authentication across services.
