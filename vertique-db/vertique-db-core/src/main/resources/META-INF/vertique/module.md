<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# DB Core Module

> **Status:** Implemented
> **Package:** `dev.vertique.db`
> **Artifact:** `db-core`
> **Depends on:** core

Database-agnostic abstraction layer for Vert.x SQL client access. Provides a typed exception hierarchy, a hierarchy-aware failure mapper, connection pool configuration, the repository base class pattern, and migration contract — without binding to any specific database vendor.

### Package Layout

| Package | Contents |
|---------|----------|
| `dev.vertique.db` | `DbModule`, `DbPoolConfig`, `MigrationRunner`, `MigrationResult`, `PoolConnectHandler` |
| `dev.vertique.db.exception` | `DataAccessException`, `TransientDataAccessException`, `ConnectionException`, `QueryTimeoutException`, `DeadlockException`, `DataIntegrityViolationException`, `UniqueConstraintViolationException`, `ForeignKeyViolationException`, `ConcurrencyFailureException`, `OptimisticLockingFailureException`, `PessimisticLockingFailureException`, `InvalidDataAccessUsageException`, `DbExceptionMapper`, `DbValidationException` |
| `dev.vertique.db.query` | `Query`, `PagedQuery`, `PageCursor`, `PagedResult`, `SortDirection`, `OffsetPagedQuery`, `OffsetPagedResult`, `QueryClause`, `SqlIdentifier`, `OrderKey`, `OrderDirection`, `NullHandling`, `RowMapper`, `Rows`, `AbstractSqlRepository`, `SqlRepository`, `PageSizeConstraintViolationException` |

---

## Key Classes

### `DataAccessException`

Base exception for all database failures. All framework database exceptions extend this class, enabling uniform catch-all handling at the service or resource layer.

Context fields are populated by vendor-specific translators when available; they are `null` by default.

| Field | Type | Description |
|-------|------|-------------|
| `sqlState()` | `String` | SQL state code (e.g., `"23505"`), or `null` |
| `constraintName()` | `String` | Constraint name (e.g., `"uk_users_email"`), or `null` |
| `tableName()` | `String` | Table name, or `null` |

### Exception Hierarchy

`DataAccessException` extends `TechnicalException` (from `core.exception`), placing database failures in the unified framework exception hierarchy.

```
TechnicalException (core.exception)
└── DataAccessException (db.exception, RuntimeException)
    ├── TransientDataAccessException          — retry-safe failures
    │   ├── ConnectionException               — pool exhaustion, network errors (SQL state class 08)
    │   ├── QueryTimeoutException             — query exceeded time limit
    │   └── DeadlockException                 — deadlock detected (SQL state 40P01)
    ├── DataIntegrityViolationException       — constraint violations; wrap into `ConflictException` at the API boundary to produce HTTP 409
    │   ├── UniqueConstraintViolationException — unique/PK violation (SQL state 23505)
    │   └── ForeignKeyViolationException       — FK violation (SQL state 23503)
    ├── ConcurrencyFailureException           — concurrent modification failures
    │   ├── OptimisticLockingFailureException  — optimistic lock conflict (SQL state 40001)
    │   └── PessimisticLockingFailureException — pessimistic lock failure
    └── InvalidDataAccessUsageException       — programming errors (bad SQL, bad permissions)
```

`DbValidationException` extends `ValidationException` (from `core.exception`) for validation failures in the DB layer:

```
ValidationException (core.exception)
└── DbValidationException (db.exception)
    └── PageSizeConstraintViolationException (db.query) — page size exceeds max allowed
```

`PageSizeConstraintViolationException` is thrown by `PagedQuery` when cursor-provided page sizes exceed `maxPageSize` via strict validation mode.

`TransientDataAccessException` subclasses represent conditions that may succeed on retry:

```java
// In a resilience policy:
@Retry(retryOn = {TransientDataAccessException.class})
Future<Item> findById(UUID id);
```

### `DbExceptionMapper`

Hierarchy-aware translator that maps raw exceptions to typed `DataAccessException` subclasses. Extends `core.failure.FailureMapper`, inheriting the superclass-walk, lookup cache, and two `on(...)` overloads. Its constructor pre-registers a `DataAccessException` pass-through (existing `DataAccessException`s are returned unchanged). Its `fallback` override wraps any unmapped throwable in a generic `DataAccessException`.

Translators registered via `on(...)` may return **any `Throwable`** — including application business exceptions — because `translate(...)` returns `Throwable`, not `DataAccessException`. Unmapped throwables are still wrapped in `DataAccessException` by `fallback`.

```java
var mapper = new DbExceptionMapper();
// Return a typed DataAccessException subclass
mapper.on(DatabaseException.class, (e, ctx) -> new DataAccessException(ctx, e));
// Return an application business exception — allowed because translate() returns Throwable
mapper.on(EmailAlreadyUsedException.class, (e, ctx) -> new EmailAlreadyInUseException(e.getEmail()));

// Translate (DataAccessException pass-through and Throwable catch-all come from the base):
Throwable translated = mapper.translate(rawException, "Failed to save user");
Throwable translated = mapper.translate(rawException); // context defaults to getMessage()
```

Self-contained — no dependency on the REST pipeline or services layer. Vendor modules extend this class with pre-configured translations (e.g., `PgDbExceptionMapper`).

#### Invariants & Gotchas

- The `DataAccessException` pass-through is registered in the base constructor. Subclasses must not re-register it.
- The catch-all wrapping of unrecognised throwables in `DataAccessException` is the `fallback` override, not a registered translator — it cannot be selectively removed.
- Because `translate(...)` returns `Throwable`, callers in repositories must handle both `DataAccessException` and plain application exceptions from the result. The `recover()` pipeline pattern works regardless of which type is returned.

### `DbPoolConfig`

Lombok `@Builder` configuration value object. Deserialized from the `"db"` section of the application config. Covers all Vert.x 5 `PoolOptions` and common `SqlConnectOptions` fields.

```json
{
  "db": {
    "host": "localhost",
    "port": 5432,
    "database": "mydb",
    "user": "app",
    "password": "secret",
    "maxPoolSize": 10
  }
}
```

| Field | Default | Description |
|-------|---------|-------------|
| `host` | — | Database host |
| `port` | — | Database port (vendor default applied by vendor module) |
| `database` | — | Database name |
| `user` | — | Database user |
| `password` | — | Database password |
| `maxPoolSize` | `5` | Maximum connections in pool |
| `maxWaitQueueSize` | `-1` | Max requests waiting for a connection; `-1` = unbounded |
| `eventLoopSize` | `0` | Event loop threads used by pool; `0` = Vert.x default |
| `connectionTimeoutMs` | `30000` | Max wait for connection from pool (ms) |
| `idleTimeoutMs` | `0` | Max idle time before eviction (ms); `0` = disabled |
| `maxLifetimeMs` | `0` | Max connection lifetime (ms); `0` = no limit |
| `poolCleanerPeriodMs` | `1000` | Pool cleaner run interval (ms) |
| `cachePreparedStatements` | `false` | Cache prepared statements per connection |
| `preparedStatementCacheMaxSize` | `256` | Max prepared statement cache size |
| `reconnectAttempts` | `0` | Reconnect attempts on failure; `0` = no reconnect |
| `reconnectIntervalMs` | `1000` | Delay between reconnect attempts (ms) |
| `properties` | `{}` | Vendor-specific connection properties |

### `PoolConnectHandler`

Handler interface (extends `Handler<SqlConnection>`) for initializing newly established database connections. Called once per new physical connection before pool admission. The implementation **must** call `conn.close()` when initialization completes — this is the Vert.x pool admission signal.

```java
@Provides
static PoolConnectHandler timezoneHandler() {
    return conn -> conn.query("SET TIME ZONE 'UTC'").execute()
            .onComplete(ar -> conn.close());
}
```

### `SqlRepository`

Base interface for database repositories. Exposes factory methods for fluent query builders, `pool()`, `exceptionMapper()`, `inTransaction()`, and `withConnection()`.

```java
public interface SqlRepository {
    Pool pool();
    DbExceptionMapper exceptionMapper();

    // Fluent query builder factory methods
    <T> Query.Builder<T, ?> query();
    default <T> Query.Builder<T, ?> query(String sql);
    <T> PagedQuery.Builder<T, ?> pagedQuery();
    default <T> PagedQuery.Builder<T, ?> pagedQuery(String sql);
    <T> OffsetPagedQuery.Builder<T, ?> offsetPagedQuery();
    default <T> OffsetPagedQuery.Builder<T, ?> offsetPagedQuery(String sql);

    // Fluent transaction builder
    TransactionBuilder transaction();

    // Executes fn on a pooled connection without a transaction
    <T> Future<T> withConnection(Function<SqlClient, Future<T>> fn);
}
```

`withConnection` translates exceptions via the failure mapper automatically. `transaction()` returns a `TransactionBuilder` — call `.execute(conn -> ...)` to run work inside an ACID transaction with automatic rollback on failure.

### `AbstractSqlRepository`

Base implementation of `SqlRepository`. Accepts a `Pool` and `DbExceptionMapper` at construction. Vendor subclasses (e.g., `PgSqlRepository`) implement `query()` and `pagedQuery()` returning dialect-specific builders. Extend the vendor subclass for application repositories:

```java
@Singleton
public class ItemRepository extends PgSqlRepository {

    @Inject
    public ItemRepository(Pool pool, PgDbExceptionMapper exceptionMapper) {
        super(pool, exceptionMapper);
    }

    public Future<Item> findById(UUID id) {
        return this.<Item>query("SELECT id, name, description FROM items WHERE id = $1")
                .params(Tuple.of(id))
                .mapping(Item::fromRow)
                .one()
                .map(opt -> opt.orElse(null));
    }
}
```

### `Query<T>`

Fluent query builder and executor for non-paginated database operations. Obtained via `SqlRepository.query(String)`. Builder methods set configuration; terminal methods build and execute in one step.

**Builder methods:**

| Method | Description |
|--------|-------------|
| `.sql(String)` | Set the SQL statement |
| `.on(SqlClient)` | Override pool with a specific connection (for transactions) |
| `.params(Tuple)` | Set prepared statement parameters |
| `.mapping(RowMapper<T>)` | Set row mapper (required for `one()`, `list()`, `returning()`) |
| `.queryClause(QueryClause)` | Append vendor clause (e.g., `PgLockMode.FOR_UPDATE`) |
| `.batch(List<Tuple>)` | Set batch parameters for `execute()` |

**Terminal methods:**

| Method | Return type | Description |
|--------|-------------|-------------|
| `.one()` | `Future<Optional<T>>` | First row or empty; requires mapper |
| `.list()` | `Future<List<T>>` | All rows; requires mapper |
| `.execute()` | `Future<Integer>` | Affected row count (mutation or batch) |
| `.returning()` | `Future<T>` | First RETURNING row; fails if 0 rows |
| `.returningOptional()` | `Future<Optional<T>>` | First RETURNING row or empty |
| `.count()` | `Future<Long>` | Count from `SELECT COUNT(*)` query |
| `.rows()` | `Future<RowSet<Row>>` | Raw `RowSet` escape hatch |

```java
// Query a single row
Future<Optional<Item>> item = this.<Item>query("SELECT id, name FROM items WHERE id = $1")
    .params(Tuple.of(itemId))
    .mapping(Item::fromRow)
    .one();

// Query a list
Future<List<Item>> items = this.<Item>query("SELECT id, name FROM items WHERE status = $1")
    .params(Tuple.of("active"))
    .mapping(Item::fromRow)
    .list();

// Mutation — affected row count
Future<Integer> deleted = this.<Void>query("DELETE FROM items WHERE status = $1")
    .params(Tuple.of("expired"))
    .execute();

// Mutation with RETURNING — fails if 0 rows
Future<Item> created = this.<Item>query("INSERT INTO items (id, name) VALUES ($1, $2) RETURNING *")
    .params(Tuple.of(id, name))
    .mapping(Item::fromRow)
    .returning();

// Mutation with RETURNING — optional (e.g., ON CONFLICT DO NOTHING)
Future<Optional<Item>> updated = this.<Item>query("UPDATE items SET name = $2 WHERE id = $1 RETURNING *")
    .params(Tuple.of(id, name))
    .mapping(Item::fromRow)
    .returningOptional();

// COUNT query
Future<Long> count = this.<Void>query("SELECT COUNT(*) FROM items WHERE status = $1")
    .params(Tuple.of("active"))
    .count();

// Inside a transaction with a lock clause
inTransaction(conn ->
    this.<Item>query("SELECT id, name FROM items WHERE id = $1")
        .on(conn)
        .params(Tuple.of(id))
        .mapping(Item::fromRow)
        .queryClause(PgLockMode.FOR_UPDATE)
        .one()
);

// Batch insert
var tuples = items.stream().map(i -> Tuple.of(i.id(), i.name())).toList();
Future<Integer> inserted = this.<Void>query("INSERT INTO items (id, name) VALUES ($1, $2)")
    .batch(tuples)
    .execute();
```

### `PagedQuery<T>`

Fluent builder and executor for keyset-paginated database queries. Obtained via `SqlRepository.pagedQuery(String)`. Uses the fetch-N+1 pattern to detect whether more pages exist without a separate COUNT query. The base SQL must not contain `ORDER BY`, `LIMIT`, or `OFFSET` — the framework appends these automatically.

**Builder methods:**

| Method | Description |
|--------|-------------|
| `.sql(String)` | Set the base SQL (no ORDER BY/LIMIT/OFFSET) |
| `.on(SqlClient)` | Override pool with a specific connection |
| `.params(Tuple)` | Base WHERE clause parameters; keyset params appended automatically |
| `.mapping(RowMapper<T>)` | Set row mapper (required) |
| `.keysetColumns(String...)` | Keyset column names in sort order (at least one required) |
| `.pageSize(int)` | Default page size (default: 20) |
| `.maxPageSize(int)` | Maximum allowed page size; cursor values are clamped (default: 100) |
| `.direction(SortDirection)` | Default sort direction (default: `ASC`) |
| `.queryClause(QueryClause)` | Append vendor clause after LIMIT (e.g., `PgLockMode.FOR_UPDATE`) |

**Terminal methods:**

| Method | Return type | Description |
|--------|-------------|-------------|
| `.page()` | `Future<PagedResult<T>>` | First page with default page size |
| `.page(PageCursor)` | `Future<PagedResult<T>>` | Page at the given cursor position |

```java
// First page (20 items, ascending by name then id)
Future<PagedResult<Item>> first = this.<Item>pagedQuery(
        "SELECT id, name, description FROM items WHERE status = $1")
    .params(Tuple.of("active"))
    .mapping(Item::fromRow)
    .keysetColumns("name", "id")
    .pageSize(20)
    .page();

// Subsequent page using cursor token from previous result
PageCursor cursor = PageCursor.fromToken(result.nextCursorToken());
Future<PagedResult<Item>> next = this.<Item>pagedQuery(
        "SELECT id, name, description FROM items WHERE status = $1")
    .params(Tuple.of("active"))
    .mapping(Item::fromRow)
    .keysetColumns("name", "id")
    .page(cursor);
```

**Keyset columns:** For non-unique sort columns (e.g., `created_at`), add a unique tiebreaker (e.g., `id`) as the last column. The framework generates row-value comparison syntax: `(created_at, id) > ($2, $3)`.

**Backward pagination:** `PagedResult.previousCursorToken()` produces a backward cursor. When executed, the sort direction is reversed, results are fetched, then reversed in memory to maintain original display order.

**Cardinality validation:** At runtime, the cursor's keyset values count is validated against `keysetColumns.length`. A mismatch fails the future with `IllegalArgumentException`.

**`maxPageSize`:** Cursor-provided page sizes exceeding `maxPageSize` are silently clamped to prevent resource exhaustion. Default is `PagedQuery.DEFAULT_MAX_PAGE_SIZE` (100).

### `PageCursor`

Stateless cursor encoding keyset column values, page size, and navigation direction as an opaque Base64URL token. Sort direction is owned by the `PagedQuery`, not the cursor.

**Factory methods:**

| Method | Description |
|--------|-------------|
| `PageCursor.first(int pageSize)` | First-page cursor (no keyset values) |
| `PageCursor.of(List, int, boolean)` | Explicit construction (used internally by `PagedQuery`) |
| `PageCursor.fromToken(String)` | Decode from a Base64URL token string |

**Instance methods:**

| Method | Return type | Description |
|--------|-------------|-------------|
| `toToken()` | `String` | Encode to opaque Base64URL token |
| `isFirstPage()` | `boolean` | True when no keyset values (first page) |
| `keysetValues()` | `List<Object>` | Boundary row keyset values (empty for first page) |
| `pageSize()` | `int` | Requested page size |
| `backward()` | `boolean` | True for previous-page navigation |

**Supported keyset value types:** `UUID`, `Instant`, `OffsetDateTime`, `LocalDateTime`, `String`, `Integer`, `Long`, `Double`, `Boolean`. Types are preserved across encode/decode via type prefixes (e.g., `uuid:`, `instant:`).

### `PagedResult<T>`

Result of a keyset-paginated query. Returned by `PagedQuery.Builder.page()`.

```java
// Check navigation and fetch next page
if (result.hasMore()) {
    PageCursor next = PageCursor.fromToken(result.nextCursorToken());
    PagedResult<Item> nextPage = repository.findItems(next).await();
}

if (result.hasPrevious()) {
    PageCursor prev = PageCursor.fromToken(result.previousCursorToken());
    PagedResult<Item> prevPage = repository.findItems(prev).await();
}
```

| Component | Type | Description |
|-----------|------|-------------|
| `items()` | `List<T>` | Items on the current page (never null, may be empty) |
| `nextCursorToken()` | `String` | Opaque forward cursor token, or `null` if last page |
| `previousCursorToken()` | `String` | Opaque backward cursor token, or `null` if first page |
| `hasMore()` | `boolean` | True when `nextCursorToken` is non-null |
| `hasPrevious()` | `boolean` | True when `previousCursorToken` is non-null |
| `size()` | `int` | Number of items on this page |

`PagedResult` is annotated with `@JsonInclude(NON_NULL)` — null cursor tokens are omitted from JSON responses.

### `OffsetPagedQuery<T>`

Fluent builder and executor for offset-based (`LIMIT`/`OFFSET`) paginated queries. Unlike `PagedQuery` (keyset), offset pagination allows random-access by page number but degrades at deep offsets. Obtained via `SqlRepository.offsetPagedQuery(String)`.

Runs a `COUNT(*)` query and the data query in parallel via `Future.all()`, returning an `OffsetPagedResult` with both the items and the total count. The base SQL must not contain `ORDER BY`, `LIMIT`, or `OFFSET` — the framework appends these automatically.

**Builder methods:**

| Method | Description |
|--------|-------------|
| `.sql(String)` | Set the base SQL (no ORDER BY/LIMIT/OFFSET) |
| `.on(SqlClient)` | Override pool with a specific connection |
| `.params(Tuple)` | Base WHERE clause parameters |
| `.mapping(RowMapper<T>)` | Set row mapper (required) |
| `.orderBy(String...)` | Column names, all ASC |
| `.orderBy(OrderDirection, String...)` | Column names with uniform direction |
| `.orderBy(OrderKey, OrderKey...)` | Per-column direction and null handling |
| `.pageSize(int)` | Default page size used by `page(int)` (default: 20) |
| `.queryClause(QueryClause)` | Append vendor clause after OFFSET |

**Terminal methods:**

| Method | Return type | Description |
|--------|-------------|-------------|
| `.page(int)` | `Future<OffsetPagedResult<T>>` | Page at index using configured default page size |
| `.page(int, int)` | `Future<OffsetPagedResult<T>>` | Page at index with explicit page size |

```java
// First page (page 0), default page size
offsetPagedQuery("SELECT id, name, created_at FROM items WHERE status = $1")
    .params(Tuple.of("active"))
    .mapping(Item::fromRow)
    .orderBy("created_at", "id")
    .pageSize(20)
    .page(0);

// Specific page and size
offsetPagedQuery("SELECT id, name FROM items")
    .mapping(Item::fromRow)
    .orderBy(OrderKey.desc("created_at"), OrderKey.asc("id"))
    .page(2, 15);

// Converting to REST OffsetPage
return repository.findAll(page, pageSize)
    .map(r -> OffsetPage.of(r.items(), r.totalItems(), r.page(), r.pageSize()));
```

**Ordering:** `orderBy(String...)` defaults to ASC with no null handling (`DISALLOW`). Use `OrderKey.asc("col").nullsLast()` for null-safe sorting.

### `OffsetPagedResult<T>`

Record returned by `OffsetPagedQuery`. Contains the page items, total item count, and page metadata.

```java
record OffsetPagedResult<T>(List<T> items, long totalItems, int page, int pageSize)
```

| Component | Type | Description |
|-----------|------|-------------|
| `items()` | `List<T>` | Items on this page (defensive copy, never null) |
| `totalItems()` | `long` | Total matching rows across all pages |
| `page()` | `int` | Zero-based page number |
| `pageSize()` | `int` | Max items per page |
| `totalPages()` | `int` | Derived: `ceil(totalItems / pageSize)`; 0 when totalItems is 0 |
| `first()` | `boolean` | True when `page == 0` |
| `last()` | `boolean` | True when `page >= totalPages - 1` |
| `size()` | `int` | Number of items on this page |
| `isEmpty()` | `boolean` | True when `items` is empty |
| `empty(int, int)` | `OffsetPagedResult<T>` | Static factory — empty result (0 items, totalItems 0) |

`OffsetPagedResult` is annotated with `@JsonInclude(NON_NULL)`.

**Navigation example:**

```java
OffsetPagedResult<Item> result = repository.findItems(0, 20).await();

if (!result.last()) {
    OffsetPagedResult<Item> nextPage = repository.findItems(result.page() + 1, 20).await();
}
```

### `SortDirection`

Enum controlling SQL `ORDER BY` direction and keyset comparison operator for `PagedQuery`.

| Value | SQL | Keyset operator |
|-------|-----|-----------------|
| `ASC` | `ORDER BY col ASC` | `>` (rows after cursor) |
| `DESC` | `ORDER BY col DESC` | `<` (rows before cursor) |

`SortDirection.reverse()` returns the opposite direction (used internally for backward pagination).

### `SqlIdentifier`

Utility for validating and quoting SQL identifiers to prevent SQL injection via identifier interpolation. Supports simple and dot-qualified identifiers (`table.column`, `schema.table.column`).

```java
// validate() — fail-fast at entry points (constructors, builder methods)
// Accepts letters, digits, underscores; first char must be letter or underscore.
// Dot-separated segments supported (max 3). Returns the identifier unchanged for fluent chaining.
String col = SqlIdentifier.validate("created_at");      // ok
String col = SqlIdentifier.validate("id; DROP TABLE--"); // throws InvalidDataAccessUsageException

// quote() — defense-in-depth at SQL composition sites
// Applies standard SQL double-quote escaping; doubles any embedded " characters.
String q = SqlIdentifier.quote("name");       // → "name"
String q = SqlIdentifier.quote("t.name");     // → "t"."name"
String q = SqlIdentifier.quote("a\"b");       // → "a""b"
```

`OrderKey` validates column names via `SqlIdentifier.validate()` at construction time. `PagedQuery` and `OffsetPagedQuery` both delegate column name validation to `SqlIdentifier.validateColumnNames()`. `PgPagedQuery` and `PgOffsetPagedQuery` quote all column names via `SqlIdentifier.quote()` during SQL composition.

### `QueryClause`

Interface for vendor-specific SQL clauses appended after the base SQL (or after `LIMIT` in paginated queries). Implementations are typically enums in vendor modules (e.g., `PgLockMode`).

**Security contract:** `QueryClause.sql()` must return a compile-time constant or developer-controlled string. Never pass user-controlled input as a `QueryClause` — the SQL string is interpolated directly with no further escaping.

```java
public interface QueryClause {
    String sql(); // e.g., "FOR UPDATE SKIP LOCKED"
}
```

### `RowMapper<T>`

Functional interface for mapping a database row to a domain object.

```java
@FunctionalInterface
public interface RowMapper<T> {
    T map(Row row);
}
```

### `Rows`

Null-safe row extraction helpers for common data types. All methods return `null` or `Optional.empty()` when the column value is SQL NULL.

| Method | Return type | Description |
|--------|-------------|-------------|
| `enumValue(row, column, enumType)` | `E` | Enum from string column |
| `optionalEnum(row, column, enumType)` | `Optional<E>` | Optional enum |
| `uuidOrNull(row, column)` | `UUID` | UUID or null |
| `optionalUuid(row, column)` | `Optional<UUID>` | Optional UUID |
| `offsetDateTimeOrNull(row, column)` | `OffsetDateTime` | OffsetDateTime or null |
| `instantOrNull(row, column)` | `Instant` | Instant (via OffsetDateTime) or null |
| `localDateOrNull(row, column)` | `LocalDate` | LocalDate or null |
| `integerOrNull(row, column)` | `Integer` | Integer or null |
| `longOrNull(row, column)` | `Long` | Long or null |
| `booleanOrNull(row, column)` | `Boolean` | Boolean or null |
| `jsonObjectOrNull(row, column)` | `JsonObject` | JsonObject or null |
| `optionalJson(row, column)` | `Optional<JsonObject>` | Optional JsonObject |
| `jsonOrNull(row, column, type)` | `T` | JsonObject mapped to type, or null |

### `MigrationRunner`

Interface for running database schema migrations. Implementations may use Flyway, Liquibase, or custom migration logic. Typically invoked during application startup before deploying verticles.

```java
public interface MigrationRunner {
    Future<MigrationResult> migrate(Vertx vertx);
}
```

### `MigrationResult`

Record holding the outcome of a migration run.

```java
public record MigrationResult(int migrationsApplied, String targetVersion) {}
```

### `DbModule`

Dagger `@Module` that reads the `"db"` config section and provides `DbPoolConfig @Singleton`. Include alongside a vendor module.

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    ConfigModule.class,
    RestModule.class,
    DbModule.class,
    DbPostgresqlModule.class,
    DbFlywayModule.class,
    AppModule.class,
    ResourceModule.class
})
interface AppComponent {
    HttpVerticle httpVerticle();
    MigrationRunner migrationRunner();
}
```

---

## Extension Points

### Custom Exception Translation via `ContextAwareFailureTranslator`

Register custom translators on a `DbExceptionMapper` instance using `on()`. Translators are `ContextAwareFailureTranslator` lambdas `(exception, context) -> Throwable` — the `context` string names the failed operation. Translators may return a typed `DataAccessException` subclass **or** an application business exception.

```java
var mapper = new DbExceptionMapper();
// Return a typed DataAccessException subclass
mapper.on(MyVendorException.class, (e, ctx) ->
    new UniqueConstraintViolationException(ctx, e, e.getSqlState(), e.getConstraint(), null));
// Return a business exception (the mapper's translate() returns Throwable)
mapper.on(MyEmailConflictException.class, (e, ctx) ->
    new EmailAlreadyInUseException(e.getEmail()));
```

Vendor modules subclass `DbExceptionMapper` with pre-configured translations. Applications can extend vendor mappers:

```java
public class MyPgExceptionMapper extends PgDbExceptionMapper {
    public MyPgExceptionMapper() {
        super();
        on(MyAppException.class, (e, ctx) -> new InvalidDataAccessUsageException(ctx, e));
    }
}
```

The `DataAccessException` pass-through and `Throwable` catch-all are provided by `DbExceptionMapper` — do not re-register them in subclasses.

### `PoolConnectHandler` — Connection Initialization

Provide a `PoolConnectHandler` via `@Provides` to run initialization logic on each new connection. The handler **must** call `conn.close()` when done — this is the pool admission signal.

```java
@Provides
static PoolConnectHandler connectHandler() {
    return conn -> conn.query("SET TIME ZONE 'UTC'").execute()
            .compose(v -> conn.query("SET application_name = 'my-service'").execute())
            .onComplete(ar -> conn.close());
}
```

### `MigrationRunner` — Custom Migration Backend

Implement `MigrationRunner` for custom migration logic. The default implementation is `FlywayMigrationRunner` (from `db-flyway`). Override the binding if you prefer a different backend:

```java
@Provides @Singleton
static MigrationRunner migrationRunner(LiquibaseMigrationRunner runner) {
    return runner;
}
```

---

## Version History

| Date | Change |
|------|--------|
| 2026-03 | Initial implementation — typed exception hierarchy (`DataAccessException` tree), `DbExceptionMapper`, `DbPoolConfig`, `MigrationRunner` contract, `AbstractSqlRepository`, `PoolConnectHandler` |
| 2026-03 | Added fluent `Query` and `PagedQuery` APIs with keyset pagination, `PageCursor` opaque token encoding, `PagedResult`, `SortDirection`, `Rows` helpers |
| 2026-03 | Fixed five pagination bugs in `PagedQuery`/`OrderKey`/backward cursor handling |
| 2026-04-02 | SQL injection hardening: `SqlIdentifier` utility (`validate()` + `quote()`); `OrderKey` and `PagedQuery` validate column names at construction; `PgPagedQuery` quotes all column identifiers in SQL composition; `QueryClause` security contract documented |
| 2026-04-03 | Added offset-based pagination: `OffsetPagedQuery` (abstract builder/executor, parallel COUNT+data via `Future.all()`), `OffsetPagedResult` (record with `totalPages()`, `first()`, `last()`, `empty()` factory); `SqlRepository.offsetPagedQuery()` factory methods; `SqlIdentifier.validateColumnNames()` shared validation |
| 2026-04-10 | `DbFailureMapper` renamed to `DbExceptionMapper`; `SqlRepository.failureMapper()` renamed to `exceptionMapper()`; `AbstractSqlRepository` constructor updated accordingly |
| 2026-06-15 | Unified exception mapping on `core.failure.FailureMapper`; `DbExceptionTranslator` removed (use `ContextAwareFailureTranslator` from `core.failure`); `DbExceptionMapper` now extends `FailureMapper` directly; `DbExceptionMapper.translate(...)` returns `Throwable` instead of `DataAccessException`, allowing translators to return application business exceptions (see ADR-0108) |

---

## Dependencies

- `dev.vertique:core` — `VertxConfig`, `FailureMapper`, `ContextAwareFailureTranslator`
- `io.vertx:vertx-core` — Vert.x instance, `Future`
- `io.vertx:vertx-sql-client` — `Pool`, `SqlConnection`, `Row`
- `com.google.dagger:dagger`
- `jakarta.inject:jakarta.inject-api`
- `com.fasterxml.jackson.core:jackson-databind` — `DbPoolConfig` deserialization
- `org.slf4j:slf4j-api`
- `org.projectlombok:lombok` (provided)

---

## Related ADRs

- ADR-0108: Unify exception mapping on a context-aware FailureMapper — establishes `FailureMapper` as the shared concrete registry; `DbExceptionMapper` now extends it; `DbExceptionTranslator` removed in favour of `ContextAwareFailureTranslator`; `translate(...)` return type widened to `Throwable` to allow business-exception returns.
