# 07 - Configuration and Security

## Contents
- Configuration files and profiles
- Typed configuration properties
- Secrets management
- Spring Security setup
- Authentication and authorization
- Web hardening
- Data and input safety
- Dependency and supply-chain security
- Security testing

## Configuration files and profiles

- Use `application.yml` (YAML) for defaults valid in all environments; `application-<profile>.yml` for overrides. Standard profiles: `local`, `test`, `prod`. Do not create ad-hoc profiles per developer.
- Production values come from **environment variables, mounted config, or a secrets manager**, never from files committed to git. Spring's relaxed binding maps `APP_PAYMENT_BASE_URL` to `app.payment.base-url`.
- `application.yml` contains safe defaults and **no secrets**. Required-but-secret values have no default so the app fails fast when missing (`${DB_PASSWORD}` without a fallback).
- Do not branch business logic on `@Profile`. Profiles select infrastructure/configuration beans only (e.g. a stub email sender in `local`).
- Property keys: `kebab-case` in YAML, custom keys under one root prefix (`app.`). Group related settings.
- Durations and sizes use Spring's types and units (`30s`, `5MB`), not raw integers with a hidden unit.
- Use `spring.config.import` for optional external config (`optional:file:./config/`, config server, Vault) rather than scripts that rewrite files.
- Commit a `.env.example`/`application-local.yml.example` with placeholder values; real local secrets stay git-ignored.

## Typed configuration properties

```java
@ConfigurationProperties(prefix = "app.payment")
@Validated
public record PaymentProperties(
        @NotBlank String baseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Min(1) @Max(10) int maxRetries) {}
```

- Records with validation; registered by `@ConfigurationPropertiesScan`. Inject `PaymentProperties` where needed.
- **No `@Value("${...}")` scattered through classes.** `@Value` is acceptable only for a single trivial value in a configuration class.
- Provide defaults with `@DefaultValue`. Add `spring-boot-configuration-processor` so IDEs get metadata.
- Never read `Environment`/`System.getenv` directly in business code.

## Secrets management

- No credentials, tokens, private keys, or connection strings with passwords in the repository, test resources, Dockerfiles, or CI logs. Rotate immediately if one leaks.
- Source of truth: platform secret store (Vault, AWS/GCP/Azure secret managers, Kubernetes secrets) surfaced as env vars or mounted files.
- Add `gitleaks`/`trufflehog` (or the CI platform's secret scanning) to the pipeline and pre-commit.
- Use distinct credentials per environment with least-privilege DB users (the app user cannot `DROP`; migrations may run with a separate privileged user).

## Spring Security setup

For servlet applications use a `SecurityFilterChain` bean with `HttpSecurity` and the lambda DSL. For WebFlux use `SecurityWebFilterChain` with `ServerHttpSecurity`, reactive authentication/context and `@EnableReactiveMethodSecurity` when method security is needed. Do not mix the two stacks. Never extend `WebSecurityConfigurerAdapter` (removed). Template in `13-code-templates.md`.

Rules:
- **Deny by default:** `.anyRequest().authenticated()` is last; every public path is listed explicitly and minimal (`/actuator/health/**`, login, public docs if intended).
- Stateless REST APIs: `SessionCreationPolicy.STATELESS`, OAuth2 Resource Server with JWT validation (issuer, audience, expiry, signature). CSRF may be disabled **only** for stateless token-authenticated APIs; any cookie/session-based flow keeps CSRF protection on.
- Browser apps using cookies: `SameSite=Lax/Strict`, `Secure`, `HttpOnly`, CSRF tokens.
- In servlet applications enable method security (`@EnableMethodSecurity`) and use `@PreAuthorize` for coarse authorization (`hasAuthority('order:read')`). Prefer **authorities/permissions** over hard-coded role names in code.
- CORS: configure the runtime-appropriate `CorsConfigurationSource` with an explicit allowlist of origins, methods, headers. Never `*` with credentials.
- Passwords: `PasswordEncoder` from `PasswordEncoderFactories.createDelegatingPasswordEncoder()` or Argon2/BCrypt explicitly; never MD5/SHA-1/plain; never roll your own.
- Do not implement your own token/session/crypto scheme. Use library support (Spring Authorization Server, Keycloak, a cloud IdP).
- Multiple chains (e.g. `/api/**` vs `/actuator/**`) use `@Order` and `securityMatcher`.

## Authentication and authorization

- Authentication answers "who"; authorization answers "may they". Both are enforced **server-side** on every request. Client-side checks are cosmetic.
- Two layers of authorization: endpoint/method (`@PreAuthorize`) and **resource ownership** inside the service (fetch by id and owner, or check after load) to prevent IDOR/BOLA. A user-controlled id in the path or body is never proof of ownership.
- Never return different errors for "does not exist" vs "not yours" if that reveals existence; prefer `404`.
- Gateways strip untrusted identity/internal headers before adding verified values. Backend JWT validation checks issuer/audience/signature/expiry and service-to-service trust independently. Map subject/tenant to local owner IDs explicitly; do not assume an arbitrary JWT subject is a customer UUID.
- Creating resources for oneself derives ownership from authentication. Creating on behalf of another customer requires explicit authorization in the service, not merely checking that the target exists.
- Admin and service-to-service access use separate scopes/clients. Principle of least privilege everywhere.
- Log authentication failures and access-denied events at `WARN` without sensitive details, so abuse is detectable.
- Rate-limit authentication and expensive endpoints (gateway, or Bucket4j/Redis in-app).

## Web hardening

- Keep Spring Security's default headers (HSTS, `X-Content-Type-Options`, `X-Frame-Options`, cache control). Add a Content-Security-Policy for any HTML-serving app.
- TLS terminates at the platform; set `server.forward-headers-strategy=framework`/`native` behind proxies and register `ForwardedHeaderFilter` when required, so redirects and links use the right scheme/host.
- Limit request sizes: `server.tomcat.max-swallow-size`, `spring.servlet.multipart.max-file-size`, `spring.servlet.multipart.max-request-size`, and bound list sizes in DTOs (`@Size(max = 100)`).
- File uploads: validate content type by sniffing (not only the header), enforce size, randomize stored names, store outside the web root/in object storage, scan if untrusted.
- Disable what you do not use: directory listings, stack traces in error pages (`server.error.include-stacktrace=never`, `include-message=never`, `include-binding-errors=never`), default error whitepage.
- Actuator: expose only `health`, `info`, `prometheus` (as needed) and protect everything but liveness/readiness (`09-observability-and-ops.md`).
- Swagger/OpenAPI UI off in production or authenticated.

## Data and input safety

- **SQL injection:** always parameterized queries (JPA/JPQL named parameters, `JdbcClient` with `:name`). Never concatenate user input into SQL/JPQL/HQL/SpEL.
- **Mass assignment:** bind requests to purpose-built DTOs; never bind to entities.
- **SSRF:** when a feature fetches user-supplied URLs, use an allowlist of hosts, resolve and block private/link-local ranges, enforce timeouts and size limits.
- **Deserialization:** no Java native serialization of untrusted data; Jackson default typing disabled.
- **XSS/HTML:** APIs return JSON; any HTML output is escaped by the template engine; never build HTML via string concatenation.
- **Path traversal:** normalize and validate file paths against a base directory.
- **Sensitive data at rest:** encrypt fields that require it (application-level or DB-level), keep keys outside the DB.
- **PII minimization:** collect, return, and log only what is needed; support deletion/retention rules.
- Constant-time comparison for secrets/tokens (`MessageDigest.isEqual`).
- Use `SecureRandom` for tokens; never `java.util.Random`/`Math.random()` for security.

## Dependency and supply-chain security

- Dependency versions come from the Spring Boot BOM; upgrade Boot patch releases promptly (security fixes ship in patch versions).
- CI runs a vulnerability scan (OWASP Dependency-Check, OSV-Scanner, or Trivy) and fails on high/critical findings with a documented exception process.
- Automate updates with Renovate or Dependabot; review changelogs, run the full test suite.
- Pin build plugins; use the Maven/Gradle wrapper; verify checksums where supported.
- Container images: minimal base, non-root user, no secrets baked in, scanned in CI.
- Do not add a dependency for something the JDK/Spring already provides.

## Security testing

- Every secured endpoint has tests for: unauthenticated -> `401`, authenticated without permission -> `403`, other user's resource -> `404`/`403`, happy path.
- MVC slices use `@WithMockUser`/`SecurityMockMvcRequestPostProcessors.jwt()` as appropriate. WebFlux uses WebTestClient with `SecurityMockServerConfigurers.mockJwt()` or runtime-appropriate helpers. Include real decoder/claim-validation tests where token validation itself is in scope.
- Include a test that fails when a new endpoint is accidentally public (e.g. iterate handler mappings, or assert unauthenticated access to a sample of routes returns `401`).
- Static analysis for security (SpotBugs + FindSecBugs, or CodeQL/Semgrep) runs in CI.
