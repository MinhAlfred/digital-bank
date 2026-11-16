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
- Upgrade cadence: apply Spring Boot **patch** releases promptly; plan minor upgrades within the OSS support window (Boot supports each minor for about 12 months; 3.x and older are end-of-life). Upgrades are their own PR with no feature changes mixed in.
- Never mix two implementations of the same concern (two JSON libraries, two HTTP clients, two logging backends).

## Recommended starters (Boot 4 naming)

Boot 4 has focused starters, each with a `-test` companion. Typical set:

| Need | Dependency |
|---|---|
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

## Quality plugins

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
2. `format:check` -> compile with warnings as errors
3. Unit + slice tests, ArchUnit
4. Integration tests (Testcontainers; CI runner provides Docker)
5. Coverage gate, static analysis, dependency + secret scans
6. Build the image (buildpacks), scan it, publish with an immutable tag (commit sha + semver)
7. Deploy to a lower environment, smoke test (`/actuator/health`), promote the same artifact

A red `main` is fixed or reverted immediately. No merging with failing checks. No `-DskipTests` in CI.

## Git, commits, branches, pull requests

- **Conventional Commits:** `type(scope): summary` with types `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `build`, `ci`, `chore`, and `!`/`BREAKING CHANGE:` footer for breaking changes. Imperative mood, summary <= 72 chars, body explains why. Reference the ticket (`Refs: PROJ-123`).
- Branches: short-lived, `feature/PROJ-123-short-name`, `fix/...`; trunk-based or GitHub-flow with protected `main` (reviews + green CI required, no direct pushes, linear history via squash/rebase).
- Pull requests: small and focused (aim < 400 changed lines), one concern, a description with what/why/how-tested, screenshots or sample requests for API changes, migration and config changes called out. Refactors and behavior changes are separate PRs.
- Every PR is reviewed against `12-code-review-checklist.md`. Authors self-review first and keep the PR green.
- Never commit generated files, IDE settings, `target/`/`build/`, secrets, large binaries. Keep a proper `.gitignore`.

## Documentation and decisions

- `README.md`: purpose, prerequisites, run locally (`docker compose up`, `./mvnw spring-boot:run`), run tests, configuration table, how to release.
- **ADRs** (`docs/adr/NNNN-title.md`: context, decision, consequences) for significant choices: new dependency family, architecture change, deviation from these conventions.
- API contract: generated OpenAPI spec published as a build artifact; `CHANGELOG.md` for user-visible changes.
- Update docs in the same PR as the code change.