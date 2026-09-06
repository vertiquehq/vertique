<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# DB Core Module

> **Status:** Implemented
> **Package:** `dev.vertique.db` (+ `.exception`, `.query`)
> **Artifact:** `vertique-db-core`
> **Depends on:** core

`vertique-db-core` is the vendor-neutral data-access surface for Vertique. It supplies the
repository base types, the fluent query and pagination builders, the typed `DataAccessException`
hierarchy with its hierarchy-aware translator, transaction and savepoint semantics, connection-pool
configuration, and the migration contract — all against `io.vertx:vertx-sql-client` interfaces with
no driver dependency.

It is not a runnable data layer on its own. A vendor artifact such as
`dev.vertique:vertique-db-postgresql` supplies the driver, the `Pool`, the dialect-specific query
builders, and the SQL-state translations; `dev.vertique:vertique-db-flyway` supplies a
`MigrationRunner`. Application repositories extend the vendor's repository base class, not the one
here.

---

## When To Use It

Install `DbModule` alongside a vendor module whenever the application talks to a SQL database
through the Vert.x SQL client.

| Pairing | Supplies |
|---|---|
| `dev.vertique:vertique-db-postgresql` | The PostgreSQL driver, `Pool`, `PgSqlRepository`, `PgDbExceptionMapper`, `PgLockMode` |
| `dev.vertique:vertique-db-flyway` | A Flyway-backed `MigrationRunner` |

Depend on this artifact alone — without a vendor module — when a module needs only the DB-agnostic
types: the exception hierarchy, `PageCursor`/`PagedResult`, `OrderKey`, or the `MigrationRunner`
interface. It pulls in no driver and opens no connections.

---

## Core Concepts

**Repositories are the unit of composition.** `SqlRepository` exposes the pool, the exception
mapper, three query-builder factories, and the transaction entry point. An application repository
extends the vendor's `AbstractSqlRepository` subclass, injects a `Pool` and a vendor
`DbExceptionMapper`, and exposes domain methods returning `Future<T>`.

**Every builder terminal translates its own failures.** The query, pagination, transaction, and
`withConnection` paths all route driver exceptions through the repository's `DbExceptionMapper`
before failing the future. Application code sees typed `DataAccessException` subtypes — or whatever
a custom translator returns — never a raw `PgException`.

**Two pagination models, deliberately not interchangeable.** Keyset (`PagedQuery`) seeks by sort-key
comparison and is O(1) at any depth, but only supports next/previous navigation. Offset
(`OffsetPagedQuery`) supports random page access and reports a total count, at the cost of a
`COUNT(*)` and degrading performance at deep offsets. Both refuse to accept `ORDER BY`, `LIMIT`, or
`OFFSET` in the base SQL — the builder appends them.

**Cursor tokens are opaque, not secret.** A `PageCursor` token is Base64URL-encoded JSON carrying
the boundary row's keyset values, page size, and direction. It is neither signed nor encrypted, so
anything you sort by is readable by the client. Do not put confidential values in an order key.
(`dev.vertique:vertique-rest-core` offers an HMAC-signed `CursorCodec` for its own REST-level cursor
types when tamper-evidence is required.)

**Identifiers are validated, values are bound.** Parameters always travel as `Tuple` placeholders.
Column names — the one place a string reaches SQL — go through `SqlIdentifier` at construction time
and are quoted again by vendor builders during composition.

---

## Key Classes

### `SqlRepository`

The repository contract.

```java
public interface SqlRepository {

    Pool pool();
    DbExceptionMapper exceptionMapper();

    <T> Query.Builder<T, ?> query();
    default <T> Query.Builder<T, ?> query(String sql);

    <T> PagedQuery.Builder<T, ?> pagedQuery();
    default <T> PagedQuery.Builder<T, ?> pagedQuery(String sql);

    <T> OffsetPagedQuery.Builder<T, ?> offsetPagedQuery();
    default <T> OffsetPagedQuery.Builder<T, ?> offsetPagedQuery(String sql);

    TransactionBuilder transaction();

    <T> Future<T> withConnection(Function<SqlClient, Future<T>> fn);
}
```

`AbstractSqlRepository` implements everything except the three builder factories, which a vendor
subclass supplies so the builders emit the right placeholder and row-comparison syntax. Extend the
vendor subclass:

```java
@Singleton
public class ItemRepository extends PgSqlRepository {

    @Inject
    public ItemRepository(Pool pool, PgDbExceptionMapper exceptionMapper) {
        super(pool, exceptionMapper);
    }

    public Future<Optional<Item>> findById(UUID id) {
        return this.<Item>query("SELECT id, name, description FROM items WHERE id = $1")
                .params(Tuple.of(id))
                .mapping(Item::fromRow)
                .one();
    }
}
```

### `Query<T>`

Fluent builder and executor for non-paginated statements. Configuration methods return the builder;
terminal methods build and execute in one step.

| Builder method | Effect |
|---|---|
| `.sql(String)` | The statement |
| `.on(SqlClient)` | Run on a specific connection instead of the pool — required inside a transaction |
| `.params(Tuple)` | Prepared-statement parameters |
| `.mapping(RowMapper<T>)` | Row mapper; required by `one`, `list`, `returning`, `returningOptional`, `stream` |
| `.queryClause(QueryClause)` | Append a vendor clause after the SQL |
| `.batch(List<Tuple>)` | Batch parameters for `execute()` |

| Terminal method | Returns | Notes |
|---|---|---|
| `.one()` | `Future<Optional<T>>` | **At most one row.** Zero rows is empty; two or more fail with `IncorrectResultSizeDataAccessException` |
| `.list()` | `Future<List<T>>` | All rows; empty list when none match |
| `.execute()` | `Future<Integer>` | Affected rows; for a batch, the total across all entries |
| `.returning()` | `Future<T>` | **Exactly one row.** Zero fails with `DataAccessException`, two or more with `IncorrectResultSizeDataAccessException` |
| `.returningOptional()` | `Future<Optional<T>>` | **At most one row.** Zero rows is a valid outcome; two or more fail with `IncorrectResultSizeDataAccessException` |
| `.count()` | `Future<Long>` | Single-column result; `0L` when no rows; a multi-column row fails with `InvalidDataAccessUsageException` |
| `.rows()` | `Future<RowSet<Row>>` | Unmapped escape hatch, still exception-translated |
| `.stream(int fetchSize)` | `Future<ReadStream<T>>` | Server-side cursor; requires a `SqlConnection` |

`.one()`, `.list()`, `.returning()`, `.returningOptional()`, and `.stream(...)` throw
`IllegalStateException` **synchronously** when no `.mapping(...)` was configured — that is a wiring
error, not a runtime failure, so it does not travel as a failed future.

```java
// Mutation with RETURNING — exactly one row expected
this.<Item>query("INSERT INTO items (id, name) VALUES ($1, $2) RETURNING *")
        .params(Tuple.of(id, name))
        .mapping(Item::fromRow)
        .returning();

// INSERT ... ON CONFLICT DO NOTHING — zero rows is fine
this.<Item>query("INSERT INTO items (id, name) VALUES ($1, $2) ON CONFLICT DO NOTHING RETURNING *")
        .params(Tuple.of(id, name))
        .mapping(Item::fromRow)
        .returningOptional();

// Batch insert
var tuples = items.stream().map(i -> Tuple.of(i.id(), i.name())).toList();
this.<Void>query("INSERT INTO items (id, name) VALUES ($1, $2)")
        .batch(tuples)
        .execute();

// Locked read inside a transaction
transaction().execute(conn ->
        this.<Item>query("SELECT id, name FROM items WHERE id = $1")
                .on(conn)
                .params(Tuple.of(id))
                .mapping(Item::fromRow)
                .queryClause(PgLockMode.FOR_UPDATE)
                .one());
```

`.batch(...)` and `.queryClause(...)` are mutually exclusive — combining them fails with
`InvalidDataAccessUsageException`.

**Streaming.** `.stream(fetchSize)` builds a Vert.x row stream over a prepared statement and needs a
real connection, so `.on(conn)` inside `transaction().execute(...)` or `withConnection(...)` is
mandatory; a pool client fails with `InvalidDataAccessUsageException`. The statement is closed when
the stream ends or errors, so set both a `handler` and an `endHandler` (or call `close()`).

```java
repository.transaction().execute(conn ->
        repository.<Item>query("SELECT id, name FROM items")
                .on(conn)
                .mapping(Item::fromRow)
                .stream(100)
                .compose(stream -> {
                    Promise<Void> done = Promise.promise();
                    stream.handler(this::process)
                          .endHandler(done::complete)
                          .exceptionHandler(done::fail);
                    return done.future();
                }));
```

### `PagedQuery<T>`

Keyset pagination using fetch-N+1: the builder requests `pageSize + 1` rows to decide whether a next
page exists, without a `COUNT(*)`.

| Builder method | Effect |
|---|---|
| `.sql(String)` | Base SQL — no `ORDER BY`, `LIMIT`, or `OFFSET` |
| `.on(SqlClient)` | Run on a specific connection |
| `.params(Tuple)` | Base WHERE parameters; keyset parameters are appended automatically |
| `.mapping(RowMapper<T>)` | Required |
| `.orderBy(String...)` | Columns in sort priority order — all `ASC`, nulls `DISALLOW` |
| `.orderBy(OrderDirection, String...)` | Uniform direction, nulls `DISALLOW` |
| `.orderBy(OrderDirection, NullHandling, String...)` | Uniform direction and null policy |
| `.orderBy(OrderKey, OrderKey...)` | Per-column direction and null policy |
| `.uniqueKey(String...)` | Declares which order columns form a unique key |
| `.pageSize(int)` | Default page size when the cursor carries none (default `20`) |
| `.queryClause(QueryClause)` | Vendor clause appended after `LIMIT` |

| Terminal method | Returns |
|---|---|
| `.page()` | `Future<PagedResult<T>>` — first page at the default size |
| `.page(PageCursor)` | `Future<PagedResult<T>>` — the page the cursor addresses |

```java
Future<PagedResult<Item>> first = this.<Item>pagedQuery(
                "SELECT id, name, created_at FROM items WHERE status = $1")
        .params(Tuple.of("active"))
        .mapping(Item::fromRow)
        .orderBy("created_at", "id")
        .uniqueKey("id")
        .pageSize(20)
        .page();

PageCursor next = PageCursor.fromToken(first.result().nextCursorToken());
Future<PagedResult<Item>> second = this.<Item>pagedQuery(
                "SELECT id, name, created_at FROM items WHERE status = $1")
        .params(Tuple.of("active"))
        .mapping(Item::fromRow)
        .orderBy("created_at", "id")
        .uniqueKey("id")
        .page(next);
```

**At least one order key is required** — building without `orderBy(...)` throws
`IllegalArgumentException`. The framework emits row-value comparison syntax such as
`(created_at, id) > ($2, $3)`, so every order column must appear in the `SELECT` list and be
readable by name from the returned `Row`.

**Declare a unique tiebreaker.** When the leading order column is not unique, append a unique column
(typically `id`) and name it with `.uniqueKey(...)`. Without `.uniqueKey(...)` the query logs a
one-time WARN that pagination may skip or duplicate rows. A `uniqueKey` column that is not among the
`orderBy` columns fails construction with `IllegalArgumentException`.

**Backward navigation** uses `PagedResult.previousCursorToken()`: each order key's direction is
reversed for the fetch and the results are reversed back in memory, so display order is preserved.

**Null keyset values** are rejected unless the corresponding `OrderKey` allows them. A cursor
carrying a null for a `DISALLOW` column fails the future with `IllegalArgumentException`; a *result
row* with a null in such a column fails with `IllegalStateException`. Use `.nullsFirst()` or
`.nullsLast()` on the `OrderKey` when nulls are expected.

**Cardinality** is checked at execution: a cursor whose keyset value count differs from the order-key
count fails the future with `IllegalArgumentException`.

### `PageCursor`

Stateless, opaque cursor encoding keyset values, page size, and direction. Sort direction itself is
owned by the `PagedQuery`, not by the cursor.

| Method | Purpose |
|---|---|
| `PageCursor.first(int pageSize)` | First-page cursor; `pageSize` must be positive |
| `PageCursor.of(List<Object>, int, boolean)` | Explicit construction |
| `PageCursor.of(List<Object>, int, boolean, CursorCodecs)` | …with an explicit codec set |
| `PageCursor.fromToken(String)` | Decode a token |
| `PageCursor.fromToken(String, CursorCodecs)` | …with an explicit codec set |
| `toToken()` / `toToken(CursorCodecs)` | Encode to a Base64URL token |
| `isFirstPage()`, `keysetValues()`, `pageSize()`, `backward()` | Accessors |
| `withPageSize(int)` | Copy with a different page size |
| `withPageSize(int, int min, int max)` | …validating the range first |
| `validatePageSize(int min, int max)` | Range check only |

`withPageSize(int, int, int)` and `validatePageSize(int, int)` throw
`PageSizeConstraintViolationException` when the requested size falls outside `[min, max]`. That
exception extends `DbValidationException` → `ValidationException`, so an unclamped client page size
surfaces as HTTP 400 rather than a 500. Nothing clamps silently — call one of these explicitly at
the API boundary if you accept a client-supplied page size.

`fromToken` throws `IllegalArgumentException` for a null, empty, malformed, or unknown-prefix token.

**Keyset value types** are preserved across encode/decode by a type prefix:

| Type | Prefix | Type | Prefix |
|---|---|---|---|
| `null` | `null:` | `String` | `str:` |
| `java.util.UUID` | `uuid:` | `Integer` | `int:` |
| `java.time.Instant` | `instant:` | `Long` | `long:` |
| `java.time.OffsetDateTime` | `odt:` | `Short` | `short:` |
| `java.time.LocalDateTime` | `ldt:` | `Double` | `double:` |
| `java.time.LocalDate` | `date:` | `Float` | `float:` |
| `java.math.BigDecimal` | `bigdec:` | `Boolean` | `bool:` |

Anything else needs a `CursorValueCodec` — see [Extension Points](#extension-points).

### `PagedResult<T>`

```java
public record PagedResult<T>(List<T> items, String nextCursorToken, String previousCursorToken)
```

| Member | Type | Meaning |
|---|---|---|
| `items()` | `List<T>` | Current page; never null, may be empty |
| `nextCursorToken()` | `String` | Forward token, or `null` on the last page |
| `previousCursorToken()` | `String` | Backward token, or `null` on the first page |
| `hasMore()` | `boolean` | `nextCursorToken != null` |
| `hasPrevious()` | `boolean` | `previousCursorToken != null` |
| `size()` | `int` | Item count on this page |
| `PagedResult.empty()` | static | Empty page with both tokens `null` |

Annotated `@JsonInclude(NON_NULL)`, so null tokens are omitted from JSON.

### `OffsetPagedQuery<T>`

Offset pagination. Runs the `COUNT(*)` and the data query in parallel via `Future.all(...)` and
returns both the page and the total.

Builder methods match `PagedQuery` except that there is no `uniqueKey(...)` and `queryClause` is
appended after `OFFSET`. Terminals are `.page(int page)` (default page size) and
`.page(int page, int pageSize)`. A negative page or a page size below 1 fails the future with
`IllegalArgumentException`.

```java
offsetPagedQuery("SELECT id, name, created_at FROM items WHERE status = $1")
        .params(Tuple.of("active"))
        .mapping(Item::fromRow)
        .orderBy("created_at", "id")
        .pageSize(20)
        .page(0);

offsetPagedQuery("SELECT id, name FROM items")
        .mapping(Item::fromRow)
        .orderBy(OrderKey.desc("created_at"), OrderKey.asc("id"))
        .page(2, 15);
```

### `OffsetPagedResult<T>`

```java
public record OffsetPagedResult<T>(List<T> items, long totalItems, int page, int pageSize)
```

| Member | Type | Meaning |
|---|---|---|
| `items()` | `List<T>` | Defensive copy; never null |
| `totalItems()` | `long` | Total matching rows |
| `page()` / `pageSize()` | `int` | Zero-based page number and page size |
| `totalPages()` | `int` | `ceil(totalItems / pageSize)`; 0 when empty, clamped to `Integer.MAX_VALUE` |
| `first()` / `last()` | `boolean` | `page == 0` / `page >= totalPages - 1` |
| `size()` / `isEmpty()` | `int` / `boolean` | Item count on this page |
| `OffsetPagedResult.empty(int page, int pageSize)` | static | Empty page |

The compact constructor rejects a negative `totalItems` or `page` and a `pageSize` below 1 with
`IllegalArgumentException`. Annotated `@JsonInclude(NON_NULL)`.

### Ordering types

`OrderKey` is `(String column, OrderDirection direction, NullHandling nullHandling)`. Construct with
`OrderKey.asc(col)`, `OrderKey.desc(col)`, or `OrderKey.of(col, direction)`, then refine with
`.nullsFirst()`, `.nullsLast()`, or `.disallowNulls()`. `reverse()` flips the direction. The column
name is validated through `SqlIdentifier` in the compact constructor.

| `OrderDirection` | SQL | Keyset operator |
|---|---|---|
| `ASC` | `ORDER BY col ASC` | `>` |
| `DESC` | `ORDER BY col DESC` | `<` |

| `NullHandling` | Meaning |
|---|---|
| `DISALLOW` | Nulls are a programming error in this column — the default |
| `NULLS_FIRST` | Nulls sort first |
| `NULLS_LAST` | Nulls sort last |

### Transactions

`transaction()` returns a single-use `TransactionBuilder`. It auto-commits on success, auto-rolls
back on failure, and translates the failure through the repository's exception mapper.

```java
// Database-default isolation, read-write
repository.transaction().execute(conn -> doWork(conn));

// Serializable
repository.transaction().serializable().execute(conn -> doWork(conn));

// Read-only, repeatable read
repository.transaction().repeatableRead().readOnly().execute(conn -> readWork(conn));

// Named for diagnostics — the name becomes the exception-translation context
repository.transaction().execute("items archiveBefore", conn -> archive(conn, cutoff));
```

| Method | Effect |
|---|---|
| `.isolationLevel(IsolationLevel)` | `READ_COMMITTED`, `REPEATABLE_READ`, or `SERIALIZABLE` |
| `.serializable()` / `.repeatableRead()` | Shorthands |
| `.readOnly()` | Issues `SET TRANSACTION READ ONLY` |
| `.execute(fn)` | Runs with the context `"Transaction failed"` |
| `.execute(operationName, fn)` | Runs with `operationName` as the translation context |

Every statement inside the lambda must be bound to the transactional connection with `.on(conn)`;
a builder left on the pool silently runs outside the transaction.

`Savepoint.execute(conn, name, fn)` gives partial rollback inside an open transaction: on success the
savepoint is released, on failure it is rolled back to and the original error propagates, and the
surrounding transaction stays open either way. The name must be a valid SQL identifier.

```java
repository.transaction().serializable().execute(conn ->
        doPartOne(conn)
                .compose(v -> Savepoint.execute(conn, "sp1", c -> doRiskyWork(c)))
                .recover(err -> doFallback(conn)));
```

`withConnection(fn)` runs on a pooled connection with **no** transaction, translating failures the
same way. Use it for read paths and for `stream(...)`.

### `RowMapper<T>` and `Rows`

```java
@FunctionalInterface
public interface RowMapper<T> {
    T map(Row row);
}
```

`Rows` holds null-safe column extractors; each returns `null` or `Optional.empty()` for SQL NULL.

| Method | Returns |
|---|---|
| `enumValue(row, column, enumType)` | `E` |
| `optionalEnum(row, column, enumType)` | `Optional<E>` |
| `uuidOrNull(row, column)` | `UUID` |
| `optionalUuid(row, column)` | `Optional<UUID>` |
| `offsetDateTimeOrNull(row, column)` | `OffsetDateTime` |
| `instantOrNull(row, column)` | `Instant` |
| `localDateOrNull(row, column)` | `LocalDate` |
| `integerOrNull(row, column)` | `Integer` |
| `longOrNull(row, column)` | `Long` |
| `booleanOrNull(row, column)` | `Boolean` |
| `jsonObjectOrNull(row, column)` | `JsonObject` |
| `optionalJson(row, column)` | `Optional<JsonObject>` |
| `jsonOrNull(row, column, type)` | `T` — `JsonObject` mapped to `type` |

### `SqlIdentifier`

The only sanctioned way to put a caller-supplied name into SQL.

```java
// validate() — fail fast at entry points. Letters, digits, underscores; first character a letter
// or underscore; up to three dot-separated segments. Returns the input for fluent chaining.
SqlIdentifier.validate("created_at");        // ok
SqlIdentifier.validate("id; DROP TABLE--");  // throws InvalidDataAccessUsageException

// quote() — defence in depth at composition sites; doubles embedded quotes.
SqlIdentifier.quote("name");    // "name"
SqlIdentifier.quote("t.name");  // "t"."name"
SqlIdentifier.quote("a\"b");    // "a""b"

// validateColumnNames() — bulk check used by both pagination builders.
SqlIdentifier.validateColumnNames("created_at", "id");
```

`OrderKey` validates at construction; `PagedQuery` and `OffsetPagedQuery` validate their `orderBy`
columns; vendor builders quote every column during composition.

### `DbPoolConfig`

The typed `db` config section — see [Configuration](#configuration) for every key.
`DbPoolConfig.validate()` returns a list of warning strings for likely misconfigurations — an unset
host, a non-positive `maxPoolSize`, a trust store configured while `sslMode` is `DISABLE`. It never
throws and nothing calls it automatically; invoke it yourself and log the result if you want that
diagnostic at startup.

### Migration contract

```java
public interface MigrationRunner {
    Future<MigrationResult> migrate(Vertx vertx);
}

public record MigrationResult(int migrationsApplied, String targetVersion) {}
```

`targetVersion` is `null` when nothing was applied. Implementations wrap vendor failures in
`MigrationException` (extends `dev.vertique.core.exception.VertiqueException`), which may carry a
partial `MigrationResult` via `partialResult()` when the engine reported progress before failing.

Run migrations before deploying verticles:

```java
migrationRunner.migrate(vertx)
        .compose(result -> vertx.deployVerticle(httpVerticle));
```

### `DbModule`

Provides `DbPoolConfig` as a `@Singleton`, parsed from the `db` config section. Include it alongside
a vendor module.

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

## Exceptions

### Hierarchy

`DataAccessException` extends `dev.vertique.core.exception.TechnicalException`, so an untranslated
database failure reaching the REST layer renders as HTTP 500.

```
TechnicalException (core.exception)
└── DataAccessException
    ├── TransientDataAccessException            — may succeed on retry
    │   ├── ConnectionException                 — pool exhaustion, network, SQL state class 08
    │   ├── QueryTimeoutException               — statement exceeded its time limit
    │   └── DeadlockException                   — deadlock detected
    ├── DataIntegrityViolationException         — constraint violation
    │   ├── UniqueConstraintViolationException  — unique / primary key
    │   └── ForeignKeyViolationException        — foreign key
    ├── ConcurrencyFailureException
    │   ├── OptimisticLockingFailureException   — serialization failure
    │   └── PessimisticLockingFailureException  — lock not available
    └── InvalidDataAccessUsageException         — programming errors: bad SQL, bad permissions
        └── IncorrectResultSizeDataAccessException — expectedSize() / actualSize()
```

```
ValidationException (core.exception)
└── DbValidationException
    └── PageSizeConstraintViolationException    — requestedPageSize(), minPageSize(), maxPageSize()
```

Every `DataAccessException` carries three nullable context fields populated by vendor translators:

| Accessor | Example |
|---|---|
| `sqlState()` | `"23505"` |
| `constraintName()` | `"uk_users_email"` |
| `tableName()` | `"users"` |

**Translate at the API boundary.** This module deliberately installs no bridge into the REST or
services pipelines: a `DataIntegrityViolationException` that escapes a repository becomes a 500.
Catch it where the operation has business meaning and rethrow a core semantic type — usually
`ConflictException` for a unique violation, which the REST pipeline renders as 409.

`TransientDataAccessException` is the retry-safe root:

```java
@Retry(retryOn = {TransientDataAccessException.class})
Future<Item> findById(UUID id);
```

### `DbExceptionMapper`

The hierarchy-aware translator every repository holds. It extends
`dev.vertique.core.failure.FailureMapper`, inheriting the superclass walk, the lookup cache, and both
`on(...)` overloads with covariant return types for chaining.

```java
var mapper = new DbExceptionMapper();

// A typed DataAccessException subtype
mapper.on(DatabaseException.class, (e, ctx) -> new DataAccessException(ctx, e));

// Or an application business exception — translate() returns Throwable, not DataAccessException
mapper.on(EmailAlreadyUsedException.class, (e, ctx) -> new EmailAlreadyInUseException(e.getEmail()));

Throwable translated = mapper.translate(rawException, "Failed to save user");
Throwable fallbackContext = mapper.translate(rawException); // context defaults to getMessage()
```

Two behaviours come from the base class and are inherited by every vendor mapper:

- The **constructor registers a `DataAccessException` pass-through**, so an already-typed exception
  travels unchanged through any number of translation layers.
- The **`fallback` override wraps every unmapped throwable in `DataAccessException`**. This is an
  override, not a registered translator, so it cannot be selectively removed.

`dev.vertique:vertique-db-postgresql` extends this class as `PgDbExceptionMapper`, adding SQL-state
translations on top; it does not re-register the pass-through and does not override `fallback`. A
subclass of your own must not either — re-registering the pass-through would shadow the base entry,
and overriding `fallback` would change the catch-all contract for every repository using the mapper.

Because `translate(...)` returns `Throwable`, a caller cannot assume a `DataAccessException` came
back. The `recover(...)` pattern in repositories works regardless of which type a translator
produced.

---

## Configuration

`DbModule` parses the `db` section into `DbPoolConfig`.

```json
{
  "db": {
    "host": "localhost",
    "port": 5432,
    "database": "mydb",
    "user": "app",
    "password": "secret",
    "maxPoolSize": 10,
    "sslMode": "VERIFY_FULL",
    "trustStorePath": "/etc/certs/db-ca.pem"
  }
}
```

| Key | Type | Default | Meaning |
|---|---|---|---|
| `host` | string | — | Database host |
| `port` | int | — | Database port; the vendor module applies its own default |
| `database` | string | — | Database name |
| `user` | string | — | Database user |
| `password` | string | — | Database password |
| `maxPoolSize` | int | `5` | Maximum pooled connections |
| `maxWaitQueueSize` | int | `-1` | Requests allowed to wait for a connection; `-1` is unbounded |
| `eventLoopSize` | int | `0` | Event-loop threads for the pool; `0` uses the Vert.x default |
| `connectionTimeoutMs` | int | `30000` | Maximum wait for a pooled connection |
| `idleTimeoutMs` | int | `0` | Idle time before eviction; `0` disables |
| `maxLifetimeMs` | int | `0` | Maximum connection lifetime; `0` is unlimited |
| `poolCleanerPeriodMs` | int | `1000` | Pool cleaner interval |
| `cachePreparedStatements` | boolean | `false` | Cache prepared statements per connection |
| `preparedStatementCacheMaxSize` | int | `256` | Prepared-statement cache size per connection |
| `reconnectAttempts` | int | `0` | Reconnect attempts; `0` disables |
| `reconnectIntervalMs` | long | `1000` | Delay between reconnect attempts |
| `properties` | map | `{}` | Vendor-specific connection properties |
| `sslMode` | string | `"DISABLE"` | Vendor SSL mode; PostgreSQL accepts `DISABLE`, `ALLOW`, `PREFER`, `REQUIRE`, `VERIFY_CA`, `VERIFY_FULL` |
| `trustAll` | boolean | `false` | Skip certificate verification — development only |
| `trustStorePath` | string | — | Trust store file (PEM, JKS, PKCS12); type inferred from the extension |
| `trustStorePassword` | string | — | Required for JKS and PKCS12, ignored for PEM |
| `trustStoreType` | string | — | Explicit `PEM`, `JKS`, or `PKCS12` |
| `keyPath` | string | — | Client private key (PEM) for mutual TLS |
| `certPath` | string | — | Client certificate (PEM) for mutual TLS |

Unknown keys are ignored. `keyPath` and `certPath` are a pair — mutual TLS needs both.

`password`, `trustStorePassword`, and `properties` are write-only: they are read from configuration
but never serialized back out by Jackson, and `toString()` renders them as `<redacted>`.

---

## Extension Points

### Custom exception translation

Register translators on a `DbExceptionMapper` with `on(...)`. A translator is a
`dev.vertique.core.failure.ContextAwareFailureTranslator` — `(exception, context) -> Throwable` —
where `context` names the failing operation. It may return a typed `DataAccessException` subtype or
an application exception.

```java
public class MyPgExceptionMapper extends PgDbExceptionMapper {

    public MyPgExceptionMapper() {
        super();
        on(MyVendorException.class, (e, ctx) ->
                new UniqueConstraintViolationException(ctx, e, e.getSqlState(), e.getConstraint(), null));
        on(MyEmailConflictException.class, (e, ctx) ->
                new EmailAlreadyInUseException(e.getEmail()));
    }
}
```

Do not re-register the `DataAccessException` pass-through and do not override `fallback` — both come
from `DbExceptionMapper`.

### `PoolConnectHandler`

`PoolConnectHandler extends Handler<SqlConnection>` runs once per newly established physical
connection, before the connection is admitted to the pool. **The handler must call `conn.close()`
when it finishes** — that call is the admission signal, and omitting it stalls pool growth.

```java
@Provides
static PoolConnectHandler connectHandler() {
    return conn -> conn.query("SET TIME ZONE 'UTC'").execute()
            .compose(v -> conn.query("SET application_name = 'my-service'").execute())
            .onComplete(ar -> conn.close());
}
```

### `MigrationRunner`

Implement it to replace the Flyway default:

```java
@Provides @Singleton
static MigrationRunner migrationRunner(LiquibaseMigrationRunner runner) {
    return runner;
}
```

### `CursorValueCodec`

Teach `PageCursor` a keyset type outside the built-in table.

```java
public interface CursorValueCodec<T> {
    String typePrefix();
    Class<T> type();
    String serialize(T value);
    T deserialize(String raw);

    static <T> CursorValueCodec<T> of(
            String prefix, Class<T> type, Function<T, String> serializer, Function<String, T> deserializer) { ... }
}
```

```java
// Global — affects every cursor built from the default codec set.
PageCursor.registerCodec(CursorValueCodec.of(
        "tenant", TenantId.class, TenantId::toString, TenantId::parse));

// Or scoped, without touching global state.
CursorCodecs codecs = CursorCodecs.defaults()
        .with(CursorValueCodec.of("tenant", TenantId.class, TenantId::toString, TenantId::parse));
PageCursor cursor = PageCursor.fromToken(token, codecs);
```

`registerCodec` is synchronized and additive; registering a prefix or type that is already known
throws `IllegalArgumentException`. `CursorCodecs.with(...)` returns a new instance and leaves the
receiver untouched, so prefer it when a codec should not become process-global.

### `QueryClause`

```java
public interface QueryClause {
    String sql(); // e.g. "FOR UPDATE SKIP LOCKED"
}
```

Implement it — typically as an enum, like the vendor's `PgLockMode` — to append a trailing SQL
fragment to a query. **`sql()` is interpolated verbatim with no escaping**, so it must return a
compile-time constant or a developer-controlled string, never anything derived from user input.

---

## Failures, Constraints, and Common Mistakes

| Symptom | Cause |
|---|---|
| `IllegalArgumentException: At least one order key is required` | `PagedQuery` built without `orderBy(...)` |
| `IllegalArgumentException: uniqueKey column '…' does not appear in the orderBy columns` | `.uniqueKey(...)` names a column not in `orderBy` |
| WARN "no declared unique tiebreaker column" | `PagedQuery` executed without `.uniqueKey(...)`; pagination may skip or duplicate rows |
| `IllegalArgumentException: Cursor keyset values count (…) does not match order keys count (…)` | Cursor reused against a query whose `orderBy` changed |
| `IllegalStateException: Null value in column '…' … null handling is DISALLOW` | A result row has a null order-key value; use `.nullsFirst()` / `.nullsLast()` |
| `IllegalArgumentException: Invalid cursor token` | Malformed, truncated, or foreign token from the client |
| `IllegalArgumentException: Unsupported keyset value type prefix: …` | Cursor produced with a codec set the decoder does not have |
| `PageSizeConstraintViolationException` (→ HTTP 400) | Client page size outside `[min, max]` at a `validatePageSize` / `withPageSize` call |
| `InvalidDataAccessUsageException: stream() requires a SqlConnection` | `.stream(...)` on a pool client instead of `.on(conn)` |
| `InvalidDataAccessUsageException: queryClause cannot be combined with batch execution` | `.batch(...)` and `.queryClause(...)` on the same query |
| `InvalidDataAccessUsageException: count() expects a single-column result` | `.count()` on a multi-column `SELECT` |
| `IncorrectResultSizeDataAccessException` from `.one()` / `.returning()` / `.returningOptional()` | The statement matched more than one row; use `.list()` or narrow the `WHERE` |
| `IllegalStateException: A mapper must be configured via .mapping() …` | A row-mapping terminal called without `.mapping(...)` |
| Statement runs outside its transaction | A builder inside `transaction().execute(...)` missing `.on(conn)` |
| Pool never grows past the first connection | A `PoolConnectHandler` that does not call `conn.close()` |
| Unique-violation surfaces as HTTP 500 | No boundary translation; catch `UniqueConstraintViolationException` and rethrow `ConflictException` |
| Syntax error after `ORDER BY` | Base SQL for a paginated query already contained `ORDER BY`, `LIMIT`, or `OFFSET` |

---

## Dependencies

| Dependency | Why |
|---|---|
| `dev.vertique:vertique-core` | `@VertxConfig`, `ConfigParser`, `FailureMapper`, `ContextAwareFailureTranslator`, the core exception roots |
| `io.vertx:vertx-core` | `Vertx`, `Future`, `ReadStream` |
| `io.vertx:vertx-sql-client` | `Pool`, `SqlClient`, `SqlConnection`, `Row`, `RowSet`, `Tuple` |
| `com.google.dagger:dagger` | `DbModule` |
| `jakarta.inject:jakarta.inject-api` | `@Singleton` |
| `com.fasterxml.jackson.core:jackson-databind` | `DbPoolConfig` deserialization |
| `org.slf4j:slf4j-api` | Pagination diagnostics |
| `org.projectlombok:lombok` | Compile-time only |
