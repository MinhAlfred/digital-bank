# Swagger / OpenAPI

All six services provide Swagger UI and generated OpenAPI documentation.
The five MVC backends use `springdoc-openapi-starter-webmvc-ui:3.2.0`
(Spring Boot 4.1.2); Gateway uses `springdoc-openapi-starter-webflux-ui:3.0.3`
(Spring Boot 4.0.3). These follow the
[springdoc compatibility matrix](https://springdoc.org/faq.html).

## Run locally

Keep each service's existing database, Kafka and JWT configuration. Activate the
`local` Spring profile when starting a service, either in the IDE's active
profiles field or from that service's directory:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-25'
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

Use the configured port if it differs from these defaults:

| Service | Swagger UI |
|---|---|
| Gateway | http://localhost:3000/swagger-ui.html |
| Core Banking | http://localhost:4000/swagger-ui.html |
| Customer | http://localhost:4001/swagger-ui.html |
| Notification | http://localhost:4002/swagger-ui.html |
| Fraud Detection | http://localhost:4003/swagger-ui.html |
| Analytics | http://localhost:4004/swagger-ui.html |

On the same service, `/v3/api-docs` returns JSON and `/v3/api-docs.yaml`
returns YAML. Documentation includes controller paths under `/api/**`.

Customer and Core currently have implemented HTTP operations. Notification,
Fraud Detection and Analytics have no business controllers yet, so their
specifications have no operations. Gateway routes forward requests; springdoc
does not infer downstream controllers from those routes. Its own specification
is therefore empty, and its UI does not aggregate the other services.
Open the owning service's UI to try its APIs directly.

## Authorize requests

In Customer or Core Swagger UI, click **Authorize**, paste a valid JWT
without the `Bearer ` prefix, and authorize. Swagger adds
`Authorization: Bearer <token>` to **Try it out** requests.
The JWT must match the service's configured signing secret, issuer and audience.
Swagger does not issue tokens.

Documentation endpoints are public only through the local documentation security
chain. Business endpoints continue to use the existing JWT security chain and
ownership checks. In Core, account operations also require Customer Service to
be running with matching JWT settings.

## Configuration

Swagger defaults live in each service's tracked `application.yml`.
The shared defaults are:

```yaml
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
    disable-swagger-default-url: true
  paths-to-match: /api/**
```

`application-local.yml` enables the spec and UI for the `local` profile.
`application-prod.yml` explicitly disables both. The default profile is `prod`;
select `local` through the startup command, `SPRING_PROFILES_ACTIVE`, or the IDE.
See [configuration and environments](configuration.md) for required variables.

Without `local` (including the normal production startup), both
`springdoc.api-docs.enabled` and `springdoc.swagger-ui.enabled` are false.
Do not activate `local` in production or override these flags to expose
documentation publicly.
