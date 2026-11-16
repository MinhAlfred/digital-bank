# 05 - Persistence: JPA, Repositories, Migrations

## Contents
- Required configuration
- Entity rules
- Identity, equality, auditing, versioning
- Associations and fetching (N+1)
- Repositories and queries
- Pagination and projections
- Batch and bulk operations
- Flyway/Liquibase migrations
- SQL and schema naming
- Testing persistence

## Required configuration

Apply this reference only to services using JPA/SQL. Do not add JPA/Hibernate settings to a gateway or an R2DBC-only service. Each service owns its schema/migrations; never query another service's tables.

```yaml
spring:
  jpa:
    open-in-view: false            # no lazy loading in the web layer
    hibernate:
      ddl-auto: validate           # never create/update outside throwaway local DBs
    properties:
      hibernate:
        jdbc:
          batch_size: 50
          time_zone: UTC
        order_inserts: true
        order_updates: true
        default_batch_fetch_size: 16
  flyway:
    enabled: true
```

`show-sql` stays off; use logging categories for SQL debugging in local profile only (`logging.level.org.hibernate.SQL=DEBUG`). Never log bind parameters in production.

## Entity rules

- Entities live in the feature's `domain` package. They model behavior, not just data: put state-transition rules on the entity (`order.cancel(...)`).
- Required shape: `protected` no-arg constructor (for JPA), a public constructor or static factory (`Order.create(...)`) that establishes valid state, `private` fields, getters only. **No public setters.** Change state through intention-revealing methods.
- Annotations: `@Entity`, `@Table(name = "orders")` with an explicit table name (avoid SQL reserved words like `order`, `user`), `@Column(nullable = false, length = ...)` mirroring DB constraints.
- Enums: `@Enumerated(EnumType.STRING)`. Never `ORDINAL`.
- Money: `BigDecimal` plus a currency column. Match `@Column(precision = ..., scale = ...)` and `NUMERIC/DECIMAL` to domain range/fractional precision; `(19, 4)` is only an example, not a universal currency scale. Validate/round deliberately before persistence. Existing minor-unit integers need a forward migration using the documented per-currency conversion factor; verify totals, boundaries and external contracts before rollout.
- Time: `Instant` for timestamps (`timestamptz`), `LocalDate` for dates.
- JSON columns: use Hibernate's `@JdbcTypeCode(SqlTypes.JSON)` on a record/map, sparingly.
- Large text/blobs: `@Lob` avoided; use `text` columns or object storage.
- Collections: initialize and encapsulate (`private final List<OrderItem> items = new ArrayList<>();` expose `List.copyOf` / `unmodifiableList`, mutate via `addItem`).
- No Lombok `@Data`, `@Setter`, `@ToString`, `@EqualsAndHashCode`, `@Builder`, `@AllArgsConstructor` on entities (see `02-java-style.md`).
- `toString()` (if present) never touches lazy associations.

## Identity, equality, auditing, versioning

```java
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity {
    @CreatedDate  @Column(nullable = false, updatable = false) private Instant createdAt;
    @LastModifiedDate @Column(nullable = false)               private Instant updatedAt;
    @CreatedBy    @Column(updatable = false)                   private String createdBy;
    @LastModifiedBy                                            private String updatedBy;
    // getters only
}
```

- Enable auditing with `@EnableJpaAuditing` in a `config/` class; provide an `AuditorAware<String>` bean.
- **ID strategy:** `UUID` (`@GeneratedValue(strategy = GenerationType.UUID)`) for public identifiers; or `SEQUENCE` with an `allocationSize` matching the DB sequence increment for internal tables where insert throughput matters. Avoid `IDENTITY` when batch inserts are important (it disables JDBC batching).
- **Optimistic locking:** every mutable aggregate root has `@Version private long version;`.
- **equals/hashCode for entities:** based on the identifier only, safe for transient entities and Hibernate proxies:

```java
@Override
public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Order other)) return false;
    return id != null && id.equals(other.getId());   // use getter: works with proxies
}

@Override
public int hashCode() {
    return getClass().hashCode();   // constant per class; stable across persist
}
```

Never include mutable or lazy fields in `equals/hashCode`.

## Associations and fetching (N+1)

- **Every association is `FetchType.LAZY`**, including `@ManyToOne` and `@OneToOne` (their default is EAGER: always override). No `FetchType.EAGER` anywhere.
- Prefer unidirectional associations. Add the inverse side (`mappedBy`) only when queries truly need it, and keep both sides in sync via helper methods on the owning aggregate.
- Prefer `Set` or `List` consistently; for `@OneToMany` add `orphanRemoval = true` + `cascade = ALL` only on true aggregate children (e.g. `Order -> OrderItem`), never across aggregate roots.
- No `CascadeType.REMOVE` toward shared entities. No `@ManyToMany` on a join with extra attributes: model the join entity.
- Across feature boundaries use IDs (`UUID customerId`), not entity references.
- **N+1 is a bug.** Fix per query with one of: `@EntityGraph(attributePaths = "items")`, `JOIN FETCH`, a DTO projection, or batch fetching (`default_batch_fetch_size`). Never fetch-join two collections at once (Cartesian product, `MultipleBagFetchException`); fetch one and let batch fetching load the other.
- Do not add `@Fetch(FetchMode.JOIN)` or global eager fetch to "make it work".
- Verify query counts in integration tests of hot paths (Hibernate statistics or a datasource-proxy assertion).

## Repositories and queries

- Interface extending `JpaRepository<Entity, IdType>` (or a narrower `Repository`/`ListCrudRepository` to limit exposed methods) in the feature's `repository` package.
- Derived query methods only when the name stays readable (`findByCustomerIdAndStatus`). Otherwise use `@Query` with JPQL and **named parameters** (`:status`); never concatenate strings into queries.
- Dynamic filters: `Specification<T>` (or Querydsl/jOOQ if already in the project) built in a dedicated `OrderSpecifications` class.
- Return types: `Optional<T>` for single, `List<T>`/`Page<T>`/`Slice<T>` for many. Never `null`.
- Existence checks use `existsBy...`, counts use `countBy...`, not `findAll().size()`.
- Modifying queries: `@Modifying(clearAutomatically = true, flushAutomatically = true)` with `@Query`, and be aware they bypass entity lifecycle and `@Version`.
- Native SQL only when JPQL cannot express it; keep in the repository, parameterized, with a comment on why.
- Repositories contain no business logic and are never injected into controllers or other features.

## Pagination and projections

- Paginated reads return `Page<T>`/`Slice<T>` with a `Pageable` from the service. Use `Slice` when total count is unnecessary (infinite scroll); counts are expensive on large tables.
- Whitelist sortable properties (see `03-rest-api.md`).
- For list/read endpoints return **projections** instead of loading full aggregates: interface-based or record-based (`record OrderSummaryRow(UUID id, Instant createdAt, BigDecimal total)`) through `@Query` constructor expressions or Spring Data DTO projections.
- Avoid loading entities just to map a few columns.

## Batch and bulk operations

- Batch inserts/updates rely on `hibernate.jdbc.batch_size` and `SEQUENCE` ids. Flush and clear the persistence context every batch for large imports.
- For large mass updates/deletes use set-based `@Modifying` queries or SQL, not entity-by-entity loops.
- Streaming large reads: `Stream<T>` repository methods inside a `@Transactional(readOnly = true)` method, closed with try-with-resources; or keyset pagination.

## Flyway/Liquibase migrations

- **The database schema is owned by migrations.** Hibernate only validates it.
- Flyway location: `src/main/resources/db/migration`. File name: `V<yyyyMMddHHmm>__<snake_case_description>.sql`, e.g. `V202611121030__create_orders_table.sql`. Timestamps avoid version collisions between branches. Repeatable scripts use `R__`.
- **Never edit an applied migration.** Fix forward with a new script. CI fails on checksum mismatch.
- One concern per migration; small and reviewable. Include constraints (`NOT NULL`, `UNIQUE`, `CHECK`, `FOREIGN KEY`) and indexes in the same change that introduces the column.
- Zero-downtime changes use expand/contract: add nullable column -> deploy code writing both -> backfill -> enforce constraint -> remove old column in a later release. Large backfills run in batches, not in one transaction.
- Never put reference data you do not own or environment-specific data in versioned migrations.
- Migrations are tested: integration tests run them on a Testcontainers database identical to production (same engine and major version).
- Rollbacks are forward migrations; do not depend on `undo` scripts.

## SQL and schema naming

| Element | Rule | Example |
|---|---|---|
| Tables | `snake_case`, plural | `purchase_orders` |
| Columns | `snake_case` | `created_at`, `customer_id` |
| Primary key | `id` | `id uuid primary key` |
| Foreign key column | `<singular_table>_id` | `order_id` |
| Constraints | `pk_`, `fk_`, `uq_`, `ck_` + table + columns | `fk_order_items_order_id` |
| Indexes | `idx_<table>_<columns>` | `idx_orders_customer_id_created_at` |
| Booleans | `is_`/`has_` prefix or plain adjective | `is_active` |
| Timestamps | `timestamptz`, UTC, suffix `_at` | `shipped_at` |

Spring's default physical naming strategy converts camelCase to snake_case; keep it. Add an index for every foreign key and for every column used in frequent filters or sorts. Do not rely on ORM to create indexes.

## Testing persistence

- Repository tests use the slice test (`@DataJpaTest`) against a real PostgreSQL started by Testcontainers with `@ServiceConnection`; do not substitute H2 (dialect differences hide bugs). See `08-testing.md`.
- Test every custom `@Query`, specification, and projection.
- Include at least one test per feature that persists an aggregate with its children and reads it back in a fresh transaction (catches mapping and lazy-loading mistakes).
