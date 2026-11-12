# Repository Guidelines

## Response to user
Luôn luôn trả lời bằng tiếng việt.

## Project Structure & Module Organization

This banking learning project contains six independent Maven services: `gateway-service`, `core-banking-service`, `customer-service`, `notification-service`, `fraud-detection-service`, and `analytics-service`. There is no root Maven aggregator or frontend.

Each service keeps Java sources under `src/main/java/thitkho/`, configuration under `src/main/resources/`, and tests under `src/test/java/`. Database migrations, where implemented, live in `src/main/resources/db/migration/`. Core banking separates models, repositories, services, configuration, and exceptions. Several services remain application skeletons.

## Build, Test, and Development Commands

Use JDK 25 and each service's Maven Wrapper. From the repository root:

```powershell
docker compose up -d                  # Start PostgreSQL, Kafka, and Kafka UI
Set-Location core-banking-service     # Select the service to work on
.\mvnw.cmd -DskipTests compile         # Compile production sources
.\mvnw.cmd test                       # Run service tests
.\mvnw.cmd -Dtest=AccountSchemaTest test # Run database schema tests
.\mvnw.cmd spring-boot:run             # Start this service locally
```

On Linux/macOS, substitute `./mvnw`. Compose starts infrastructure only; run Java services separately. Gateway listens on port 3000; backend services use 4000–4004.

## Coding Style & Naming Conventions

Follow surrounding formatting; prefer four-space Java indentation and avoid unrelated reformatting. Use `UpperCamelCase` classes, `lowerCamelCase` methods/fields, lowercase packages, and constructor injection. No shared formatter or lint configuration is currently defined.

Keep HTTP handling, business logic, and persistence separate. Preserve reactive behavior in gateway filters. Store monetary values as integer minor units (`long`/`BIGINT`), never floating point. Name new Flyway migrations `V<version>__<description>.sql`; preserve migrations already applied to shared databases.

## Testing Guidelines

Tests use JUnit Jupiter and Spring Boot testing; `AccountSchemaTest` uses PostgreSQL Testcontainers and requires Docker. Use `*Test` or `*Tests` class names and descriptive behavior-based method names. No coverage threshold is configured. Add meaningful assertions for changed business rules; existing comment-only tests do not establish coverage. Context tests may require local infrastructure. Report pre-existing compilation failures separately from regressions.

## Commit & Pull Request Guidelines

History uses descriptive imperative subjects, such as “Initialize customer service with PostgreSQL and Kafka configurations.” Follow that style and keep commits focused. PRs should explain the affected services, behavior changes, migration/configuration impact, test commands and results, and related issues when applicable.

## Security & Configuration

Keep secrets out of source and logs. Preserve gateway header sanitization and backend JWT validation. Check customer-service URLs and JWT settings across services before integration; current defaults are not fully aligned. Preserve unrelated working-tree changes.
