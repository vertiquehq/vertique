<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# DB PostgreSQL Module

> **Status:** Implemented
> **Package:** `dev.vertique.db.postgresql`
> **Artifact:** `db-postgresql`
> **Depends on:** db-core, core

PostgreSQL-specific implementation of the db-core abstractions. Provides a pre-configured failure mapper with SQL state translations, a `PgSqlRepository` base class, and the Dagger module that wires the `vertx-pg-client` connection pool.

---

## Key Classes

### `PgDbExceptionMapper`

Extends `DbExceptionMapper` with PostgreSQL-specific SQL state translations pre-registered at construction.

**Pre-configured translations:**

| Condition | Mapped to |
|-----------|-----------|
| SQL state class `08` (any `08xxx`) | `ConnectionException` |
| SQL state `23505` | `UniqueConstraintViolationException` (with constraint + table name) |
| SQL state `23503` | `ForeignKeyViolationException` (with constraint + table name) |
| SQL state `40001` | `OptimisticLockingFailureException` |
| SQL state `40P01` | `DeadlockException` |
| SQL state `55P03` (lock not available) | `PessimisticLockingFailureException` |
| SQL state `57014` (statement timeout) | `QueryTimeoutException` |
| `VertxException` with message `"Timeout"` | `QueryTimeoutException` |
| `DataAccessException` | pass-through (identity) — inherited from `DbExceptionMapper` constructor |
| Any other `Throwable` | `DataAccessException` (catch-all) — inherited from `DbExceptionMapper.fallback` |

When the exception is a `PgException`, the constraint name and table name are extracted and set on the resulting exception for precise error identification.

```java
// In a resource exception mapper:
public class UniqueConstraintExceptionMapper implements ExceptionMapper<UniqueConstraintViolationException> {
    @Override
    public Response toResponse(UniqueConstraintViolationException ex) {
        return Response.status(409)
                .entity(ProblemDetail.of(409, "Conflict",
                        "A record with that value already exists: " + ex.constraintName()))
                .build();
    }
}
```

### `PgSqlRepository`

PostgreSQL-specific repository base class. Extends `AbstractSqlRepository` and implements the `query()`, `pagedQuery()`, and `offsetPagedQuery()` factory methods returning `PgQuery.Builder`, `PgPagedQuery.Builder`, and `PgOffsetPagedQuery.Builder` respectively. Application repositories extend this class:

```java
@Singleton
public class ItemRepository extends PgSqlRepository {

    @Inject
    public ItemRepository(Pool pool, PgDbExceptionMapper exceptionMapper) {
        super(pool, exceptionMapper);
    }

    public Future<Item> create(String name, String description) {
        UUID id = UUID.randomUUID();
        return this.<Item>query(
                "INSERT INTO items (id, name, description) VALUES ($1, $2, $3)"
                        + " RETURNING id, name, description")
                .params(Tuple.of(id, name, description))
                .mapping(Item::fromRow)
                .returning();
    }

    public Future<Item> findById(UUID id) {
        return this.<Item>query("SELECT id, name, description FROM items WHERE id = $1")
                .params(Tuple.of(id))
                .mapping(Item::fromRow)
                .one()
                .map(opt -> opt.orElse(null));
    }

    public Future<List<Item>> findAll() {
        return this.<Item>query("SELECT id, name, description FROM items ORDER BY name")
                .mapping(Item::fromRow)
                .list();
    }

    public Future<PagedResult<Item>> findItems(PageCursor cursor) {
        return this.<Item>pagedQuery("SELECT id, name, description FROM items")
                .mapping(Item::fromRow)
                .keysetColumns("name", "id")
                .page(cursor);
    }

    public Future<OffsetPagedResult<Item>> findItemsPage(int page, int pageSize) {
        return this.<Item>offsetPagedQuery("SELECT id, name, description FROM items")
                .mapping(Item::fromRow)
                .orderBy("name", "id")
                .page(page, pageSize);
    }
}
```

`inTransaction` and `withConnection` (inherited from `AbstractSqlRepository`) automatically translate exceptions via the `PgDbExceptionMapper`.

### `PgQuery<T>`

PostgreSQL implementation of `Query`. In addition to the base `Query` behavior, `PgQuery` validates at construction time that the base SQL does not contain inline lock clauses (`FOR UPDATE`, `FOR SHARE`, `SKIP LOCKED`, `NOWAIT`) — these must be applied via `.queryClause(PgLockMode.xxx)` instead.

```java
// Lock mode applied via queryClause in a transaction
inTransaction(conn ->
    this.<Item>query("SELECT id, name FROM items WHERE id = $1")
        .on(conn)
        .params(Tuple.of(id))
        .mapping(Item::fromRow)
        .queryClause(PgLockMode.FOR_UPDATE)
        .one()
);
```

Obtained automatically via `PgSqlRepository.query()` / `PgSqlRepository.query(String)` — do not construct directly.

### `PgPagedQuery<T>`

PostgreSQL implementation of `PagedQuery`. Composes paginated SQL with PostgreSQL-specific syntax: `$N` parameter placeholders, row-value comparison for keyset conditions, and `LIMIT` clause. Validates at construction time that the base SQL does not contain `ORDER BY`, `LIMIT`, `OFFSET`, or inline lock clauses.

**Generated SQL patterns:**

```sql
-- First page (no keyset)
SELECT id, name FROM items ORDER BY name ASC, id ASC LIMIT 21

-- Subsequent page (with keyset from previous result)
SELECT id, name FROM items WHERE (name, id) > ($1, $2) ORDER BY name ASC, id ASC LIMIT 21

-- With base WHERE clause
SELECT id, name FROM items WHERE status = $1 AND (name, id) > ($2, $3)
    ORDER BY name ASC, id ASC LIMIT 21
```

Obtained automatically via `PgSqlRepository.pagedQuery()` / `PgSqlRepository.pagedQuery(String)` — do not construct directly.

### `PgOffsetPagedQuery<T>`

PostgreSQL implementation of `OffsetPagedQuery`. Composes offset-paginated SQL with PostgreSQL-specific syntax: double-quoted identifiers, `ORDER BY`, `LIMIT`, and `OFFSET` clauses. Validates at construction time that the base SQL does not contain `ORDER BY`, `LIMIT`, `OFFSET`, or inline lock clauses.

**Generated SQL pattern:**

```sql
-- Page 0, page size 20
SELECT id, name FROM items WHERE status = $1 ORDER BY "name" ASC, "id" ASC LIMIT 20 OFFSET 0

-- Page 2, page size 15
SELECT id, name FROM items ORDER BY "created_at" DESC NULLS LAST, "id" ASC LIMIT 15 OFFSET 30
```

The count query is automatically wrapped as `SELECT COUNT(*) FROM (<base SQL>) _cnt`.

Obtained automatically via `PgSqlRepository.offsetPagedQuery()` / `PgSqlRepository.offsetPagedQuery(String)` — do not construct directly.

### `PgSqlComposer`

Package-private shared utility extracted from `PgPagedQuery` to avoid duplication between keyset and offset pagination. Provides:

| Method | Description |
|--------|-------------|
| `validateBaseSql(String)` | Rejects SQL containing `ORDER BY`, `LIMIT`, `OFFSET`, or lock clauses at the top level (outside subqueries, string literals, and quoted identifiers) using `SqlScanner` |
| `quoteColumns(List<OrderKey>)` | Pre-computes double-quoted column names for all order keys |
| `appendOrderBy(StringBuilder, String[], List<OrderKey>)` | Appends `ORDER BY` with per-column direction and `NULLS FIRST`/`NULLS LAST` |

Not part of the public API — used internally by `PgPagedQuery` and `PgOffsetPagedQuery`.

### `PgLockMode`

PostgreSQL row-level lock mode enum implementing `QueryClause`. Apply via `.queryClause(PgLockMode.xxx)` on a `Query` or `PagedQuery` builder.

**Lock strengths × wait policies:**

| Constant | SQL | Use case |
|----------|-----|----------|
| `FOR_UPDATE` | `FOR UPDATE` | Exclusive lock; waits for locked rows |
| `FOR_NO_KEY_UPDATE` | `FOR NO KEY UPDATE` | Weaker exclusive; allows FOR KEY SHARE |
| `FOR_SHARE` | `FOR SHARE` | Shared lock; blocks FOR UPDATE |
| `FOR_KEY_SHARE` | `FOR KEY SHARE` | Weakest; only blocks FOR UPDATE |
| `FOR_UPDATE_SKIP_LOCKED` | `FOR UPDATE SKIP LOCKED` | Work-queue pattern; skips locked rows |
| `FOR_NO_KEY_UPDATE_SKIP_LOCKED` | `FOR NO KEY UPDATE SKIP LOCKED` | |
| `FOR_SHARE_SKIP_LOCKED` | `FOR SHARE SKIP LOCKED` | |
| `FOR_KEY_SHARE_SKIP_LOCKED` | `FOR KEY SHARE SKIP LOCKED` | |
| `FOR_UPDATE_NOWAIT` | `FOR UPDATE NOWAIT` | Fails immediately if any row is locked |
| `FOR_NO_KEY_UPDATE_NOWAIT` | `FOR NO KEY UPDATE NOWAIT` | |
| `FOR_SHARE_NOWAIT` | `FOR SHARE NOWAIT` | |
| `FOR_KEY_SHARE_NOWAIT` | `FOR KEY SHARE NOWAIT` | |

```java
// Work-queue: lock rows, silently skip already-locked ones
this.<Job>query("SELECT id, payload FROM work_queue WHERE status = $1")
    .params(Tuple.of("pending"))
    .mapping(Job::fromRow)
    .queryClause(PgLockMode.FOR_UPDATE_SKIP_LOCKED)
    .list();
```

### `DbPostgresqlModule`

Dagger `@Module` that provides the PostgreSQL connection pool, exception mapper singleton, optional connection handler binding, and the database readiness health check.

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    ConfigModule.class,
    RestModule.class,
    DbModule.class,
    DbPostgresqlModule.class,   // provides Pool, PgDbExceptionMapper
    DbFlywayModule.class,
    AppModule.class,
    ResourceModule.class
})
interface AppComponent {
    HttpVerticle httpVerticle();
    MigrationRunner migrationRunner();
}
```

**Bindings provided:**

| Type | Scope | Description |
|------|-------|-------------|
| `Pool` | `@Singleton` | PostgreSQL connection pool built from `DbPoolConfig` |
| `PgDbExceptionMapper` | `@Singleton` | Pre-configured PostgreSQL exception mapper |
| `PoolConnectHandler` | `@BindsOptionalOf` | Optional; provide via `@Provides` to initialize new connections |
| `HealthCheck` | `@IntoSet` `@Readiness` | `DatabaseHealthCheck`, contributed automatically |

---

### `DatabaseHealthCheck`

Readiness probe that verifies database connectivity by executing `SELECT 1` against the pool. It is
contributed automatically as a `@Readiness` health check whenever `DbPostgresqlModule` is included —
no application wiring is required.

It reports under the name `database`, resolving to UP when the query succeeds and DOWN with the
failure message otherwise.

---

## Configuration

Pool configuration comes from the `"db"` config section (see `DbPoolConfig` in `dev.vertique:vertique-db-core`). The PostgreSQL default port is 5432.

```json
{
  "db": {
    "host": "localhost",
    "port": 5432,
    "database": "mydb",
    "user": "app",
    "password": "secret",
    "maxPoolSize": 10,
    "cachePreparedStatements": true,
    "properties": {
      "application_name": "my-service"
    }
  }
}
```

---

## Exception Mapper Pattern

Contribute `ExceptionMapper<T>` implementations for the database exception types you care about:

```java
// Map unique constraint violations to 409 Conflict
@Singleton
public class UniqueConstraintExceptionMapper
        implements ExceptionMapper<UniqueConstraintViolationException> {

    @Inject
    public UniqueConstraintExceptionMapper() {}

    @Override
    public Response toResponse(UniqueConstraintViolationException ex) {
        return Response.status(409)
                .entity(ProblemDetail.of(409, "Conflict", "Duplicate value"))
                .type("application/problem+json")
                .build();
    }
}

// Map connection failures to 503 Service Unavailable
@Singleton
public class ConnectionExceptionMapper implements ExceptionMapper<ConnectionException> {

    @Inject
    public ConnectionExceptionMapper() {}

    @Override
    public Response toResponse(ConnectionException ex) {
        return Response.status(503)
                .entity(ProblemDetail.of(503, "Service Unavailable", "Database unavailable"))
                .type("application/problem+json")
                .build();
    }
}

// Register in the app's ResourceModule:
@Provides @IntoSet
static ExceptionMapper<?> uniqueConstraintMapper(UniqueConstraintExceptionMapper m) { return m; }

@Provides @IntoSet
static ExceptionMapper<?> connectionMapper(ConnectionExceptionMapper m) { return m; }
```

---

## Dependencies

- `dev.vertique:db-core` — `DbExceptionMapper`, `AbstractSqlRepository`, `DbPoolConfig`, `PoolConnectHandler`
- `dev.vertique:core` — `VertxConfig`
- `io.vertx:vertx-pg-client` — `PgBuilder`, `PgConnectOptions`, `PgException`
- `com.google.dagger:dagger`
- `jakarta.inject:jakarta.inject-api`
- `org.slf4j:slf4j-api`
- `org.projectlombok:lombok` (provided)
