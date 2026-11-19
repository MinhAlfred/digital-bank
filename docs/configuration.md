# Configuration and environments

All six services track `application.yml`, `application-local.yml` and
`application-prod.yml`. These files contain shared settings and environment
references, with no real passwords or signing keys. Separate
`application.yml.example` files are no longer needed.

| File | Purpose |
|---|---|
| `application.yml` | Service name, port, common settings, required environment variables; Swagger disabled |
| `application-local.yml` | Local PostgreSQL/Kafka/service URLs, development health details, Swagger enabled |
| `application-prod.yml` | Swagger disabled, restricted health exposure, automatic Flyway baselining disabled |
| `src/test/resources/application-test.yml` | Test-only configuration; not packaged with the application |

The default profile is `prod`. For development, explicitly select `local`.
Spring loads the base file and then overrides matching settings with the selected
profile. Choose one environment profile per deployment; do not combine `local`
with `prod`.

## Environment variables

Use [.env.example](../.env.example) as a reference. Spring Boot does **not**
automatically load `.env` files: set the variables in your shell, IDE run
configuration, or deployment secret store. Real `.env` and `.env.*` files
are ignored by Git; `.env.example` contains no secret values.

| Services | Required variables |
|---|---|
| All five database-backed services | `DB_USERNAME`, `DB_PASSWORD` |
| Database-backed services in production | Also `DB_URL`, `KAFKA_BOOTSTRAP_SERVERS` |
| Gateway, Core and Customer | `APP_SECURITY_JWT_SECRET`, `APP_SECURITY_JWT_ISSUER`, `APP_SECURITY_JWT_AUDIENCE` |
| Gateway | `GATEWAY_INTERNAL_SECRET` |
| Gateway in production | Also `CUSTOMER_SERVICE_URI`, `CORE_BANKING_SERVICE_URI`, `NOTIFICATION_SERVICE_URI`, `FRAUD_DETECTION_SERVICE_URI`, `ANALYSIS_SERVICE_URI` |
| Core in production | Also `APP_CUSTOMER_SERVICE_BASE_URL` |

Supply credentials separately for each service's database. Gateway, Core and
Customer must agree on the JWT signing key, issuer and audience. The HS256 key
must contain at least 32 characters. No secret has a checked-in default.
Gateway's internal secret remains a compatibility header, not backend
authorization.

Each service can override its port with `SERVER_PORT`. Core's outbound client
also accepts `APP_CUSTOMER_SERVICE_CONNECT_TIMEOUT` (default `2s`) and
`APP_CUSTOMER_SERVICE_READ_TIMEOUT` (default `30s`).

## Run locally

From the Customer Service directory, for example:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-25'
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:DB_USERNAME = '<database-user>'
$env:DB_PASSWORD = '<database-password>'
$env:APP_SECURITY_JWT_SECRET = '<shared-HS256-key-at-least-32-characters>'
$env:APP_SECURITY_JWT_ISSUER = '<trusted-issuer>'
$env:APP_SECURITY_JWT_AUDIENCE = '<expected-audience>'
.\mvnw.cmd spring-boot:run
```

Alternatively set **Active profiles** to `local` and the same environment
variables in the IDE. Other backends need their database credentials;
Gateway needs JWT settings and its internal secret.

Local URLs remain overridable using the same variables as production:

| Service | Port | Local PostgreSQL URL |
|---|---|---|
| Core Banking | 4000 | `jdbc:postgresql://localhost:5433/core_banking_db` |
| Customer | 4001 | `jdbc:postgresql://localhost:5437/customer_db` |
| Notification | 4002 | `jdbc:postgresql://localhost:5434/noti_db` |
| Fraud Detection | 4003 | `jdbc:postgresql://localhost:5435/fraud_db` |
| Analytics | 4004 | `jdbc:postgresql://localhost:5436/analysis_db` |

Gateway runs on port `3000`; its local service destinations use the ports above.
Local Kafka defaults to `localhost:9092`. Core's local Customer URL defaults
to `http://localhost:4001`.

## Production

Set `SPRING_PROFILES_ACTIVE=prod` and provide the production variables from
the table above. The base configuration requires explicit deployment database,
Kafka and service destinations; it supplies no localhost fallback outside
the local and test profiles.

For example, with environment variables already supplied:

```powershell
java -jar target/customer-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

Swagger is disabled. Database schema validation and Flyway migrations remain
enabled; production does not automatically baseline an existing database.

During the migration to tracked YAML, the original local application files were
preserved under `.local/config-backup/<service>/application.yml`. This directory
is ignored and is not loaded by Spring. Use those private backups to recover
previous connection values when setting your environment variables.
