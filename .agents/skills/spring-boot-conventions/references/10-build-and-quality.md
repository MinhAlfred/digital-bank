# 10 - Build, Dependencies, Quality Gates, Git

## Contents
- Build tooling
- Dependency management
- Recommended starters (Boot 4)
- Quality plugins
- CI pipeline
- Git, commits, branches, pull requests
- Documentation and decisions

## Build tooling

- Run commands from the affected deployable service. Do not assume a root aggregator; build dependent modules only when a real aggregator/dependency graph exists. On Windows use `.\mvnw.cmd` / `.\gradlew.bat`; on Unix use `./mvnw` / `./gradlew`.
- Always use the **wrapper** (`./mvnw`, `./gradlew`) committed to the repo; never rely on a locally installed build tool version.
- Maven is the default; Gradle (Kotlin DSL) is fine if the project already uses it. Do not mix.
- Java version declared once: Maven `<java.version>`/`maven.compiler.release`, or Gradle `java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }`. Use `--release`, not just `source/target`.
- Compile with `-parameters` (Boot parent does) and with warnings visible; enable `-Xlint:all` and `-Werror` for new modules when feasible.
- Builds are reproducible: pinned plugin versions, no `SNAPSHOT` dependencies on main, no `latest`/version ranges.
- Single module by default. Multi-module only for hard separations (shared API contract jar, separately deployable apps).

## Dependency management

- Inherit from `spring-boot-starter-parent` (or import `spring-boot-dependencies` BOM). **Do not declare versions** for anything the BOM manages. When an override is unavoidable, add a comment with the reason and the issue link, and remove it as soon as possible.
- Keep dependencies minimal. Before adding one: Is it needed? Is it maintained? Is it licensed compatibly? Does Spring/JDK already provide it? Document non-obvious additions in the PR.
- Remove unused dependencies (`mvn dependency:analyze` / dependency-analysis plugin).
- Upgrade cadence: apply Spring Boot **patch** releases promptly; plan minor upgrades using the official support policy for the exact release. Do not hard-code support/EOL conclusions for an entire major line. Upgrades are their own PR with no feature changes mixed in.
- Avoid accidental duplicate implementations. A deliberate blocking/reactive adapter or compatibility library may coexist when the architecture requires it; keep its types and configuration isolated.

## Recommended starters (Boot 4 naming)

Boot 4 has focused starters, each with a `-test` companion. Typical set:

| Need | Dependency |
|---|---|
| Reactive web | `spring-boot-starter-webflux` (+ matching test starter for the target Boot version) |
| Servlet web | `spring-boot-starter-webmvc` (+ `spring-boot-starter-webmvc-test`) |
| Validation | `spring-boot-starter-validation` |
| JPA | `spring-boot-starter-data-jpa` (+ `-test`) |
| Migrations | `spring-boot-starter-flyway` (or `-liquibase`) |
| Security | `spring-boot-starter-security`, `spring-boot-starter-security-oauth2-resource-server` |
| HTTP clients | `spring-boot-starter-restclient` (+ `-test`) |
| JSON | `spring-boot-starter-jackson` (pulled transitively by web) |
| Actuator | `spring-boot-starter-actuator` |
| Tracing/metrics | `micrometer-tracing-bridge-otel`, `micrometer-registry-prometheus` |
| Testcontainers | `spring-boot-testcontainers`, `org.testcontainers:*` |

Exact artifact names differ across 4.x minors: check the project's `pom.xml`/Boot reference docs rather than inventing names. Deprecated "classic" starters still resolve during migration but should be replaced.

Other libraries commonly allowed: MapStruct (+ annotation processor), Lombok (limited), Testcontainers, ArchUnit, Awaitility, WireMock, springdoc-openapi, ShedLock, Caffeine, Resilience4j (only if not using Spring's built-in resilience annotations).

For MapStruct mappings, declare `org.mapstruct:mapstruct` and `mapstruct-processor` with the same explicit version when the BOM does not manage them. Include the processor in the actual main compiler execution alongside existing processors. With Lombok-generated accessors, include `org.projectlombok:lombok-mapstruct-binding` on that processor path too. Test processors are needed only when tests define generated mappers. Keep generated mapper implementations under build output, not source control.

## Quality plugins

The table is a setup recommendation for new services, not evidence these tools are installed. Detect actual configuration first. Run existing gates; add a new tool only when needed/in scope, with dependency/plugin configuration and compatible versions. Existing projects keep their chosen thresholds and commit convention.

| Concern | Tool | Gate |
|---|---|---|
| Formatting | Spotless (google-java-format AOSP) | `spotless:check` fails the build |
| Static analysis | Error Prone (+ NullAway with JSpecify) or SpotBugs, Checkstyle for rules not covered by formatter | no new violations |
| Sonar/quality | SonarQube/SonarCloud | quality gate on new code |
| Tests | Surefire (unit) + Failsafe (`*IT`) | all pass |
| Coverage | JaCoCo | thresholds in `08-testing.md` |
| Dependencies | Enforcer (`dependencyConvergence`, banned duplicate classes), OWASP/OSV/Trivy scan | no high/critical |
| Secrets | gitleaks / secret scanning | no findings |
| Architecture | ArchUnit suite | green |
| Build info | `build-info` + git properties | included in jar |

Pre-commit hooks (formatter, secret scan) are recommended; CI is the authority.

## CI pipeline

1. Checkout, JDK setup (same version as runtime), dependency cache
2. Configured formatter check (Maven Spotless: `spotless:check`) -> compile; warnings-as-errors only when configured
3. Unit + slice tests, ArchUnit
4. Integration tests (Testcontainers; CI runner provides Docker)
5. Coverage gate, static analysis, dependency + secret scans
6. Build the image (buildpacks), scan it, publish with an immutable tag (commit sha + semver)
7. Deploy to a lower environment, smoke test (`/actuator/health`), promote the same artifact

A red `main` is fixed or reverted immediately. No merging with failing checks. No `-DskipTests` in CI.

## Git, commits, branches, pull requests

- Follow repository instructions/history first. For a new repository adopting **Conventional Commits:** `type(scope): summary` with types `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `build`, `ci`, `chore`, and `!`/`BREAKING CHANGE:` footer for breaking changes. Imperative mood, summary <= 72 chars, body explains why. Reference the ticket (`Refs: PROJ-123`).
- Branches: short-lived, `feature/PROJ-123-short-name`, `fix/...`; trunk-based or GitHub-flow with protected `main` (reviews + green CI required, no direct pushes, linear history via squash/rebase).
- Pull requests: small and focused (aim < 400 changed lines), one concern, a description with what/why/how-tested, screenshots or sample requests for API changes, migration and config changes called out. Refactors and behavior changes are separate PRs.
- Every PR is reviewed against `12-code-review-checklist.md`. Authors self-review first and keep the PR green.
- Never commit generated files, IDE settings, `target/`/`build/`, secrets, large binaries. Keep a proper `.gitignore`.

## Documentation and decisions

- `README.md`: purpose, prerequisites, run locally (`docker compose up`, `./mvnw spring-boot:run`), run tests, configuration table, how to release.
- **ADRs** (`docs/adr/NNNN-title.md`: context, decision, consequences) for significant choices: new dependency family, architecture change, deviation from these conventions.
- API contract: generated OpenAPI spec published as a build artifact; `CHANGELOG.md` for user-visible changes.
- Update docs in the same PR as the code change.
## Maven integration-test runner

Before naming a test `*IT`, add Failsafe to the affected service's active build (or inherited active build), with both goals. With `spring-boot-starter-parent`, use its managed plugin version; when only importing the dependency BOM, pin a compatible plugin version explicitly. `pluginManagement` alone does not activate a plugin.

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-failsafe-plugin</artifactId>
    <configuration>
        <includes>
            <include>**/*IT.java</include>
        </includes>
        <failIfNoTests>true</failIfNoTests>
    </configuration>
    <executions>
        <execution>
            <goals>
                <goal>integration-test</goal>
                <goal>verify</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

Place this under `<build><plugins>` for a service that has ITs. `failIfNoTests=true` detects discovery mistakes; do not activate this exact configuration in a service with no integration tests yet. Preserve/customize include patterns if existing IT naming differs. Keep integration classes out of Surefire if custom includes would run them twice.

| Command (from service directory) | Purpose |
|---|---|
| `.\mvnw.cmd test` | Surefire unit/slice tests |
| `.\mvnw.cmd verify` | Unit/slice + configured Failsafe ITs and verification |
| `.\mvnw.cmd "-Dit.test=OrderFlowIT" verify` | Select a Failsafe IT while retaining the lifecycle |

On Unix use `./mvnw`. Check effective configuration/report counts and confirm a failing IT makes `verify` fail. Do not invoke only `integration-test`: `verify` is needed for checking results after teardown. Gradle equivalents need an explicit JVM test suite/source set, `useJUnitPlatform()` and `check` dependency; match the wrapper's supported DSL.

Sources: [Failsafe usage](https://maven.apache.org/surefire/maven-failsafe-plugin/usage.html), [discovery patterns](https://maven.apache.org/surefire/maven-failsafe-plugin/examples/inclusion-exclusion.html).
