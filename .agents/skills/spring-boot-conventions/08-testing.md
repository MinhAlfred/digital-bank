# 08 - Testing

## Contents
- Principles and the test pyramid
- Naming and structure
- Unit tests
- Web slice tests
- Persistence slice tests
- Integration tests
- Architecture tests (ArchUnit)
- Test data, time, async
- Coverage and CI
- Spring Boot 4 specifics

## Principles and the test pyramid

| Level | Scope | Tools | Speed | Share |
|---|---|---|---|---|
| Unit | one class, collaborators mocked, no Spring context | JUnit, Mockito, AssertJ | ms | most |
| Slice | one layer with a minimal Spring context | `@WebMvcTest`, `@DataJpaTest`, `@JsonTest` | 1-3 s | many |
| Integration | full app + real infrastructure | `@SpringBootTest`, Testcontainers, `RestTestClient` | seconds | few, covering key flows |
| Architecture | package and dependency rules | ArchUnit | fast | one suite |

- **Every behavior change comes with tests.** Bug fixes start with a failing test that reproduces the bug.
- Tests are deterministic, independent, and order-agnostic. No shared mutable state, no dependence on wall-clock time, randomness, or network.
- Test behavior and contracts, not implementation details. Do not assert on private methods or on call counts unless the interaction **is** the behavior.
- Prefer real objects over mocks for value types and simple collaborators; mock only boundaries (repositories in unit tests, remote adapters).
- Real infrastructure for persistence/messaging tests: **Testcontainers**, same engine and major version as production. H2 is not a substitute for PostgreSQL.
- No `Thread.sleep`. Use Awaitility (`await().atMost(...).untilAsserted(...)`) for async behavior.
- Keep `@SpringBootTest` count low and share contexts (same configuration => cached context); avoid `@DirtiesContext`.

## Naming and structure

- Class: `<ClassUnderTest>Test`; integration tests `<Feature>IT` (run by Failsafe/`integrationTest` task). Test sources mirror the main package.
- Method: `method_condition_expectedResult`, e.g. `cancel_whenAlreadyShipped_throwsConflict`. `@DisplayName` optional for readability.
- Body in **Given / When / Then** blocks separated by blank lines (or comments). One logical behavior per test; several assertions on the same outcome are fine (`assertThat(...).extracting(...)` or `SoftAssertions`).
- Assertions: **AssertJ** (`assertThat`, `assertThatThrownBy`, `assertThatCode`). Not JUnit `assertEquals`, not Hamcrest in new tests.
- Mockito BDD style: `given(repo.findById(id)).willReturn(...)`, `then(repo).should().save(any())`. Use `ArgumentCaptor` for checking what was saved.
- Parameterized tests (`@ParameterizedTest` + `@CsvSource`/`@MethodSource`) for rule tables and boundary values.
- Test classes are package-private; no `public` needed.

## Unit tests

- Services: `@ExtendWith(MockitoExtension.class)`, `@Mock` the repositories/adapters/mappers, construct the Impl (via `@InjectMocks` or explicitly). No Spring context.
- Cover: happy path, each business rule/exception, boundary values, state transitions, idempotency/duplicate handling.
- Inject a fixed `Clock` (`Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)`).
- Entities/value objects: plain unit tests on their behavior (`order.cancel()` rules).
- Mappers (MapStruct): one test per mapper verifying non-trivial mappings; or rely on `unmappedTargetPolicy = ERROR` at compile time plus a smoke test.

## Web slice tests

`@WebMvcTest(OrderController.class)` loads only MVC infrastructure; mock the service with `@MockitoBean`; use `MockMvcTester` (AssertJ style).

Verify: routing and status codes, request validation (`400` + `VALIDATION_ERROR` payload), serialization of the JSON contract, error mapping through `GlobalExceptionHandler` (import it), `Location` header on create, security rules (`401/403`) by importing the `SecurityConfig`.

Do not test business logic here.

## Persistence slice tests

- `@DataJpaTest` with a Testcontainers PostgreSQL via `@ServiceConnection`, and `@AutoConfigureTestDatabase(replace = NONE)` so the real datasource is used. Flyway runs the real migrations.
- Test every custom query, specification, projection, constraint (unique/FK violations become the expected exception), optimistic-locking behavior, and cascade/orphan removal.
- Guard N+1 on list endpoints: assert statement count (Hibernate `Statistics` or a datasource-proxy helper).
- Use a shared `TestcontainersConfiguration` class (singleton container reuse) rather than one container per class.

## Integration tests

- `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `RestTestClient` (Boot 4) or `MockMvcTester` with the full context, Testcontainers for DB/broker/cache, WireMock/MockServer (or `MockRestServiceServer`) for outbound HTTP.
- Cover each feature's **critical end-to-end flows**: create -> read -> update -> error paths, authentication, transactions rolling back correctly, events/outbox side effects.
- Clean data per test with unique ids or truncation in `@BeforeEach`; do not rely on `@Transactional` rollback in tests that cross threads/HTTP.
- Keep the suite fast; run in CI on every PR.

## Architecture tests (ArchUnit)

Conventions are enforced by machine, not by review. One `ArchitectureTest` runs in the normal build; the full class is in `13-code-templates.md`. Minimum rule set:

- `web` does not depend on `repository`, `service.impl`, or JPA entities
- `service`/`service.impl` does not depend on `web`
- `domain` does not depend on `web`, `service`, `repository`
- Classes annotated `@Service` are named `*ServiceImpl`, reside in `..service.impl..`, and implement a `*Service` interface (for feature services)
- No field injection (`GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION`)
- Controllers are annotated `@RestController` and named `*Controller`
- No cycles between features (`slices().matching("com.acme.shop.(*)..").should().beFreeOfCycles()`)
- No use of `System.out`, `java.util.logging`, `java.util.Date` (`GeneralCodingRules`)

If the project uses Spring Modulith, add `ApplicationModules.of(Application.class).verify()`.

## Test data, time, async

- Test data builders / object mothers (`OrderFixtures.anOrder().withStatus(PAID).build()`) in `src/test`; no giant JSON blobs duplicated across tests. Text blocks for JSON bodies.
- No production code with `if (testing)` branches.
- Randomness: seed it or avoid it. Unique values via `UUID.randomUUID()` are fine.
- Time: fixed `Clock`; never compare to `Instant.now()` with exact equality.
- Async/events: assert with Awaitility; for `@TransactionalEventListener` tests, commit the transaction in the test (use `TransactionTemplate`) before asserting.

## Coverage and CI

- JaCoCo reports on every build. Gates (adjust per project): **>= 80% line / 70% branch** on `service` and `domain` packages; no gate on generated code, DTO records, configuration, `Application`.
- Coverage is a floor, not a goal; mutation testing (PIT) on `service`/`domain` is encouraged for critical modules.
- Flaky tests are bugs: quarantine by fixing within the sprint, never by blanket retries.
- CI order: compile -> format check -> unit -> slice -> architecture -> integration (Failsafe) -> coverage gate -> vulnerability scan.

## Spring Boot 4 specifics

- Test starters are modular: add the companion test starter per technology (`spring-boot-starter-webmvc-test`, `spring-boot-starter-data-jpa-test`, `spring-boot-starter-restclient-test`, ...) instead of one catch-all.
- `@MockBean`/`@SpyBean` are gone: use `@MockitoBean`/`@MockitoSpyBean`.
- `TestRestTemplate` is superseded by `RestTestClient` (with `@AutoConfigureRestTestClient`); prefer `MockMvcTester` for MVC slice tests.
- JUnit 4 is gone; use JUnit Jupiter (JUnit 6 with Boot 4). No `@RunWith`, no `junit-vintage`.
- Many test-slice annotations and auto-configuration classes moved to new per-module packages in Boot 4. Copy import statements from existing tests in the repo or let the compiler/IDE resolve them; do not guess packages from Boot 3 memory.
- Testcontainers 2.x changed some artifact names and classes (for example the module artifacts are prefixed `testcontainers-` and container classes moved into module packages). Follow the version managed by the Spring Boot BOM and match existing imports.