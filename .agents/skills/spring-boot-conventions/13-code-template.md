# 13 - Code Templates

Copy-and-adapt skeletons for a feature called `order` in `com.acme.shop`. Replace names, packages, and fields. Check imports against the project's Spring Boot version (see `11-spring-boot-4-notes.md`); import statements are omitted for brevity except where they matter.

## Contents
1. Entity and base auditing
2. Repository
3. DTOs
4. Mapper
5. Service interface
6. Service implementation
7. Controller
8. Exceptions and error codes
9. Global exception handler
10. Pagination wrapper
11. Configuration properties
12. Security configuration
13. Flyway migration
14. Unit test (service)
15. Web slice test
16. Repository test with Testcontainers
17. Architecture test (ArchUnit)

---

## 1. Entity and base auditing

```java
// common/persistence/AuditableEntity.java
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity {

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

```java
// order/domain/Order.java
@Entity
@Table(name = "orders")
public class Order extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Version
    private long version;

    @Column(nullable = false)
    private UUID customerId;                       // reference by id across features

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private final List<OrderItem> items = new ArrayList<>();

    protected Order() {}                           // JPA only

    public static Order create(UUID customerId, List<OrderItem> items) {
        Order order = new Order();
        order.customerId = Objects.requireNonNull(customerId);
        order.status = OrderStatus.PENDING_PAYMENT;
        items.forEach(order::addItem);
        order.totalAmount = order.items.stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return order;
    }

    public void cancel() {
        if (status == OrderStatus.CANCELLED) {
            throw new OrderAlreadyCancelledException(id);
        }
        if (status == OrderStatus.SHIPPED) {
            throw new OrderNotCancellableException(id, status);
        }
        this.status = OrderStatus.CANCELLED;
    }

    private void addItem(OrderItem item) {
        item.attachTo(this);
        items.add(item);
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public OrderStatus getStatus() { return status; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public List<OrderItem> getItems() { return Collections.unmodifiableList(items); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Order other)) return false;
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() { return getClass().hashCode(); }
}
```

## 2. Repository

```java
// order/repository/OrderRepository.java
public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(UUID id);

    Optional<Order> findByIdAndCustomerId(UUID id, UUID customerId);   // ownership-safe lookup

    boolean existsByCustomerIdAndStatus(UUID customerId, OrderStatus status);

    @Query("""
            select new com.acme.shop.order.dto.OrderSummary(o.id, o.status, o.totalAmount, o.createdAt)
            from Order o
            where o.customerId = :customerId
            """)
    Page<OrderSummary> findSummariesByCustomerId(@Param("customerId") UUID customerId, Pageable pageable);
}
```

## 3. DTOs

```java
// order/dto/CreateOrderRequest.java
public record CreateOrderRequest(
        @NotNull UUID customerId,
        @NotEmpty @Size(max = 100) List<@Valid Line> lines,
        @Size(max = 500) String note) {

    public record Line(@NotNull UUID productId, @Positive int quantity) {}
}

// order/dto/OrderResponse.java
public record OrderResponse(
        UUID id,
        UUID customerId,
        OrderStatus status,
        BigDecimal totalAmount,
        List<OrderItemResponse> items,
        Instant createdAt) {}

// order/dto/OrderSummary.java  (list view, also used as JPQL constructor projection)
public record OrderSummary(UUID id, OrderStatus status, BigDecimal totalAmount, Instant createdAt) {}
```

## 4. Mapper

```java
// order/mapper/OrderMapper.java
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface OrderMapper {

    OrderResponse toResponse(Order order);

    OrderItemResponse toResponse(OrderItem item);
}
```

Entities are created through domain factories (`Order.create(...)`), not by MapStruct. MapStruct maps entity -> DTO only.

## 5. Service interface

```java
// order/service/OrderService.java
public interface OrderService {

    /**
     * Creates an order for the given customer.
     *
     * @throws ResourceNotFoundException if the customer or a product does not exist
     * @throws InsufficientStockException if a product cannot be fulfilled
     */
    OrderResponse create(CreateOrderRequest request);

    /** @throws ResourceNotFoundException if the order does not exist or is not owned by the caller */
    OrderResponse getById(UUID orderId, UUID actingUserId);

    PageResponse<OrderSummary> findMine(UUID actingUserId, Pageable pageable);

    /**
     * @throws OrderAlreadyCancelledException if already cancelled
     * @throws OrderNotCancellableException if already shipped
     */
    void cancel(UUID orderId, UUID actingUserId);
}
```

## 6. Service implementation

```java
// order/service/impl/OrderServiceImpl.java
@Service
@Transactional(readOnly = true)
public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final CustomerService customerService;          // other feature: via interface
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public OrderServiceImpl(OrderRepository orderRepository, OrderMapper orderMapper,
                            CustomerService customerService, ApplicationEventPublisher events, Clock clock) {
        this.orderRepository = orderRepository;
        this.orderMapper = orderMapper;
        this.customerService = customerService;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        customerService.assertExists(request.customerId());
        List<OrderItem> items = request.lines().stream()
                .map(line -> OrderItem.of(line.productId(), line.quantity()))
                .toList();

        Order saved = orderRepository.save(Order.create(request.customerId(), items));

        log.info("Order created id={} customerId={}", saved.getId(), saved.getCustomerId());
        events.publishEvent(new OrderCreatedEvent(saved.getId(), clock.instant()));
        return orderMapper.toResponse(saved);
    }

    @Override
    public OrderResponse getById(UUID orderId, UUID actingUserId) {
        return orderRepository.findWithItemsById(orderId)
                .filter(order -> order.getCustomerId().equals(actingUserId))   // ownership (IDOR)
                .map(orderMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException(OrderErrorCode.ORDER_NOT_FOUND, orderId));
    }

    @Override
    public PageResponse<OrderSummary> findMine(UUID actingUserId, Pageable pageable) {
        return PageResponse.from(orderRepository.findSummariesByCustomerId(actingUserId, pageable));
    }

    @Override
    @Transactional
    public void cancel(UUID orderId, UUID actingUserId) {
        Order order = orderRepository.findByIdAndCustomerId(orderId, actingUserId)
                .orElseThrow(() -> new ResourceNotFoundException(OrderErrorCode.ORDER_NOT_FOUND, orderId));
        order.cancel();
        log.info("Order cancelled id={} by={}", orderId, actingUserId);
        events.publishEvent(new OrderCancelledEvent(orderId, clock.instant()));
    }
}
```

## 7. Controller

```java
// order/web/OrderController.java
@RestController
@RequestMapping("/api/v1/orders")
@Validated
class OrderController {

    private final OrderService orderService;

    OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        OrderResponse created = orderService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{orderId}")
    OrderResponse get(@PathVariable UUID orderId, @AuthenticationPrincipal Jwt jwt) {
        return orderService.getById(orderId, UUID.fromString(jwt.getSubject()));
    }

    @GetMapping
    PageResponse<OrderSummary> listMine(@AuthenticationPrincipal Jwt jwt, Pageable pageable) {
        return orderService.findMine(UUID.fromString(jwt.getSubject()), pageable);
    }

    @PostMapping("/{orderId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable UUID orderId, @AuthenticationPrincipal Jwt jwt) {
        orderService.cancel(orderId, UUID.fromString(jwt.getSubject()));
    }
}
```

## 8. Exceptions and error codes

```java
// common/exception/ErrorCode.java
public interface ErrorCode {
    String code();
    HttpStatus httpStatus();
}

// common/exception/BusinessException.java
public abstract class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    protected BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() { return errorCode; }
}

// common/exception/ResourceNotFoundException.java
public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(ErrorCode code, Object id) {
        super(code, "Resource not found: " + id);
    }
}

// order/domain/OrderErrorCode.java
public enum OrderErrorCode implements ErrorCode {
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND),
    ORDER_ALREADY_CANCELLED(HttpStatus.CONFLICT),
    ORDER_NOT_CANCELLABLE(HttpStatus.CONFLICT);

    private final HttpStatus status;
    OrderErrorCode(HttpStatus status) { this.status = status; }

    @Override public String code() { return name(); }
    @Override public HttpStatus httpStatus() { return status; }
}

// order/domain/OrderAlreadyCancelledException.java
public class OrderAlreadyCancelledException extends BusinessException {
    public OrderAlreadyCancelledException(UUID orderId) {
        super(OrderErrorCode.ORDER_ALREADY_CANCELLED, "Order " + orderId + " is already cancelled");
    }
}
```

## 9. Global exception handler

```java
// common/web/GlobalExceptionHandler.java
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ProblemDetail handleBusiness(BusinessException ex) {
        ErrorCode code = ex.getErrorCode();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.httpStatus(), ex.getMessage());
        problem.setTitle(code.code());
        problem.setProperty("code", code.code());
        addTraceId(problem);
        return problem;
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The resource was modified by someone else. Reload and retry.");
        problem.setProperty("code", "CONCURRENT_MODIFICATION");
        addTraceId(problem);
        return problem;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation", ex);               // details stay in logs only
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The request conflicts with existing data.");
        problem.setProperty("code", "DATA_CONFLICT");
        addTraceId(problem);
        return problem;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setProperty("code", "VALIDATION_ERROR");
        problem.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage())))
                .toList());
        addTraceId(problem);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);                   // the ONE place stack traces are logged
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
        problem.setProperty("code", "INTERNAL_ERROR");
        addTraceId(problem);
        return problem;
    }

    private void addTraceId(ProblemDetail problem) {
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
    }
}
```

Also override `handleHandlerMethodValidationException` (parameter-level validation) to produce the same `VALIDATION_ERROR` shape, and handle `AccessDeniedException` -> `403` if not handled by the security chain.

## 10. Pagination wrapper

```java
// common/web/PageResponse.java
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
```

## 11. Configuration properties

```java
// config/PaymentProperties.java
@ConfigurationProperties(prefix = "app.payment")
@Validated
public record PaymentProperties(
        @NotBlank String baseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @DefaultValue("3") @Min(0) @Max(10) int maxRetries) {}
```

```yaml
app:
  payment:
    base-url: ${PAYMENT_BASE_URL}
    connect-timeout: 2s
    read-timeout: 5s
```

## 12. Security configuration

```java
// config/SecurityConfig.java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())                              // stateless token API only
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())                           // uses the CorsConfigurationSource bean
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().authenticated())                     // deny by default
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties props) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(props.allowedOrigins());                   // explicit list, never "*"
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
```

Beans: `Clock` (`Clock.systemUTC()`), `AuditorAware<String>`, and `@EnableJpaAuditing` live in `config/`.

## 13. Flyway migration

```sql
-- src/main/resources/db/migration/V202611121030__create_orders_table.sql
create table orders (
    id           uuid           primary key,
    version      bigint         not null default 0,
    customer_id  uuid           not null,
    status       varchar(30)    not null,
    total_amount numeric(19, 4) not null,
    created_at   timestamptz    not null,
    updated_at   timestamptz    not null,
    constraint ck_orders_status check (status in ('PENDING_PAYMENT', 'PAID', 'SHIPPED', 'CANCELLED'))
);

create index idx_orders_customer_id_created_at on orders (customer_id, created_at desc);
```

## 14. Unit test (service)

```java
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock OrderRepository orderRepository;
    @Mock OrderMapper orderMapper;
    @Mock CustomerService customerService;
    @Mock ApplicationEventPublisher events;

    OrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OrderServiceImpl(orderRepository, orderMapper, customerService, events, CLOCK);
    }

    @Test
    void getById_whenOrderBelongsToAnotherUser_throwsNotFound() {
        UUID orderId = UUID.randomUUID();
        Order order = anOrderOwnedBy(UUID.randomUUID());
        given(orderRepository.findWithItemsById(orderId)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> service.getById(orderId, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void cancel_whenAlreadyCancelled_throwsConflict() {
        UUID userId = UUID.randomUUID();
        Order order = aCancelledOrderOwnedBy(userId);
        given(orderRepository.findByIdAndCustomerId(any(), eq(userId))).willReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancel(UUID.randomUUID(), userId))
                .isInstanceOf(OrderAlreadyCancelledException.class);
        then(events).shouldHaveNoInteractions();
    }
}
```

## 15. Web slice test

```java
@WebMvcTest(OrderController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class OrderControllerTest {

    @Autowired MockMvcTester mvc;
    @MockitoBean OrderService orderService;

    @Test
    void create_withEmptyLines_returns400WithValidationErrors() {
        String body = """
                { "customerId": "6b1f6a4e-0d0b-4a53-9a39-3f0c4b6f6b11", "lines": [] }
                """;

        assertThat(mvc.post().uri("/api/v1/orders")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.code").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void get_withoutToken_returns401() {
        assertThat(mvc.get().uri("/api/v1/orders/{id}", UUID.randomUUID()))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
```

## 16. Repository test with Testcontainers

```java
// src/test/java/.../support/TestcontainersConfiguration.java
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {                         // adjust import/generics to the Testcontainers version
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    }
}

// OrderRepositoryTest.java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class OrderRepositoryTest {

    @Autowired OrderRepository repository;
    @Autowired TestEntityManager em;

    @Test
    void findByIdAndCustomerId_whenOwnerDiffers_returnsEmpty() {
        Order saved = em.persistAndFlush(OrderFixtures.anOrder().build());
        em.clear();

        assertThat(repository.findByIdAndCustomerId(saved.getId(), UUID.randomUUID())).isEmpty();
    }
}
```

## 17. Architecture test (ArchUnit)

```java
@AnalyzeClasses(packages = "com.acme.shop", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule controllers_do_not_touch_persistence_or_impl =
            noClasses().that().resideInAPackage("..web..")
                    .should().dependOnClassesThat().resideInAnyPackage("..repository..", "..service.impl..")
                    .because("controllers depend on service interfaces only");

    @ArchTest
    static final ArchRule controllers_do_not_use_entities =
            noClasses().that().resideInAPackage("..web..")
                    .should().dependOnClassesThat().areAnnotatedWith(Entity.class)
                    .because("entities must not leave the service layer");

    @ArchTest
    static final ArchRule services_do_not_depend_on_web =
            noClasses().that().resideInAnyPackage("..service..")
                    .should().dependOnClassesThat().resideInAPackage("..web..");

    @ArchTest
    static final ArchRule domain_is_independent =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("..web..", "..service..", "..repository..");

    @ArchTest
    static final ArchRule services_follow_interface_plus_impl =
            classes().that().areAnnotatedWith(Service.class)
                    .and().resideInAPackage("..service.impl..")
                    .should().haveSimpleNameEndingWith("ServiceImpl")
                    .andShould().implement(JavaClass.Predicates.simpleNameEndingWith("Service"));

    @ArchTest
    static final ArchRule service_impls_live_in_impl_package =
            classes().that().haveSimpleNameEndingWith("ServiceImpl")
                    .should().resideInAPackage("..service.impl..")
                    .andShould().beAnnotatedWith(Service.class);

    @ArchTest
    static final ArchRule controllers_are_named_and_annotated =
            classes().that().resideInAPackage("..web..").and().haveSimpleNameEndingWith("Controller")
                    .should().beAnnotatedWith(RestController.class);

    @ArchTest
    static final ArchRule no_field_injection = GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    @ArchTest
    static final ArchRule no_system_out = GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule no_java_util_logging = GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule features_are_free_of_cycles =
            SlicesRuleDefinition.slices().matching("com.acme.shop.(*)..").should().beFreeOfCycles();
}
```

Adjust `"com.acme.shop"` to the project's root package; keep these rules green on every build. Add a rule per new convention the team adopts, so conventions are enforced by the build, not by memory.