# 11 - Spring Boot 4 Notes and Migration from 3.x

## Contents
- Version landscape
- What changed in Boot 4 (rules that follow)
- Cheat sheet: Boot 3.x -> 4.x
- If the project is still on 3.x
- Upgrade procedure
- How to stay accurate

## Version landscape

- Read the affected service's build file, Java toolchain and Boot/Cloud BOMs first. Different deployables may use different compatible versions.
- These examples target Boot 4 / Framework 7. Check APIs, Java compatibility, starter names and test libraries against the exact target release; never force a version change to match a template.
- Before recommending an upgrade or declaring a release unsupported, consult the current [Spring Boot project/support information](https://spring.io/projects/spring-boot). Keep dated release schedules out of reusable rules.

## What changed in Boot 4 (and the rule that follows)

| Area | Change | Rule |
|---|---|---|
| Modularization | Boot is split into smaller modules; most technologies have their own starter, each with a `-test` starter | Declare only the starters you need; add the matching test starter; do not add the old catch-all `spring-boot-starter-test` for new code |
| Starter names | `spring-boot-starter-web` -> `spring-boot-starter-webmvc`; `-json` -> `-jackson`; `-aop` -> `-aspectj`; `-web-services` -> `-webservices`; `-oauth2-client` -> `-security-oauth2-client`; new `-restclient`, `-webclient` | Use target-version names for relevant additions; migrate existing starters only when needed/in scope |
| Package moves | Many auto-configuration classes and test-slice annotations moved to per-module packages | Copy imports from existing code; do not guess from Boot 3 memory |
| Jackson 3 | `tools.jackson.*` (`JsonMapper`) is the default; `ObjectMapper` -> `JsonMapper`; annotations stay in `com.fasterxml.jackson.annotation` | New code uses Jackson 3 APIs only; keep `spring.jackson.*` properties |
| Testing | `@MockBean/@SpyBean` removed; `@MockitoBean/@MockitoSpyBean`; `RestTestClient` replaces `TestRestTemplate`; JUnit 4 removed; JUnit 6 | See `08-testing.md` |
| Null-safety | Spring Framework 7 uses JSpecify annotations | Use `@NullMarked`/`@Nullable` from `org.jspecify.annotations` |
| HTTP clients | `RestClient` is the standard blocking client; declarative `@HttpExchange` interfaces with first-class registration support | No `RestTemplate` in new code |
| API versioning | Spring Framework 7 supports API versioning natively | Use one strategy project-wide (default: URI versioning) |
| Resilience | `@Retryable`, `@ConcurrencyLimit` and `@EnableResilientMethods` in core Spring | Prefer them over adding Resilience4j in new projects |
| Embedded servers | Undertow removed (not compatible with Servlet 6.1) | Use Tomcat (default) or Jetty |
| Persistence | Jakarta EE 11, Hibernate 7.x, Jakarta Persistence 3.2 | Check HQL/JPQL and `@Type` customizations when upgrading |
| Security | Spring Security 7 | Component-based `SecurityFilterChain`; lambda DSL only |
| Removed | Spock integration, `layertools` jar mode, APIs deprecated in 3.x | Remove before upgrading |

## Cheat sheet: Boot 3.x -> 4.x

| Boot 3.x | Boot 4.x |
|---|---|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| `spring-boot-starter-test` (all-in-one) | `spring-boot-starter-<tech>-test` per technology |
| `@MockBean` / `@SpyBean` | `@MockitoBean` / `@MockitoSpyBean` |
| `TestRestTemplate` | `RestTestClient` |
| `com.fasterxml.jackson.databind.ObjectMapper` | `tools.jackson.databind.json.JsonMapper` |
| `RestTemplate` | `RestClient` / `@HttpExchange` |
| Undertow | Tomcat or Jetty |
| JUnit 4 / vintage | JUnit Jupiter |
| Spring Retry library | `@Retryable` in core (or Resilience4j) |

## If the project is still on 3.x

If the project is on 3.x, verify its exact release/support status before making a recommendation:

- Do not rewrite code to Boot 4 idioms unprompted; match the project's version (e.g. keep `@MockBean`, `spring-boot-starter-web`, Jackson 2 `ObjectMapper`).
- Everything in the other reference files that is independent of Boot 4 still applies (layering, naming, transactions, JPA, error handling, security principles, testing strategy).
- Mention an upgrade when the verified support/security status warrants it; carry out migration only when requested.

## Upgrade procedure

1. Upgrade to the **latest 3.5.x** first and fix all deprecation warnings (APIs deprecated in 3.x are removed in 4.0).
2. Switch the build to Boot 4 with classic starters (they remain as a bridge) and make the project compile. Consider the OpenRewrite Spring Boot 4 recipes to automate package/annotation changes.
3. Migrate Jackson 2 -> 3 (imports, `JsonMapper`, customizers, custom serializers/modules).
4. Migrate tests: `@MockitoBean`, `RestTestClient`, modular test starters, JUnit 6, Testcontainers 2.x.
5. Replace classic starters with the focused ones; delete unused ones.
6. Run the full suite plus ArchUnit; verify Actuator, security filter chain behavior, Flyway startup, and serialization contracts (compare JSON responses before/after).
7. Ship the upgrade as its own PR with no functional changes; update ADR/README.

Use the official *Spring Boot 4.0 Migration Guide* (GitHub wiki of spring-projects/spring-boot) and the release notes of the target minor.

## How to stay accurate

Spring evolves quickly and some names above may shift between 4.x minors. When a class, annotation, starter, or property is uncertain:

1. Look at how the repository already uses it (grep existing code and tests).
2. Check the dependency on the classpath (IDE or `./mvnw dependency:tree`).
3. Consult the official reference for the exact Boot/Framework version in the build.
4. Only then write code. Never invent artifact names, packages, or properties.
