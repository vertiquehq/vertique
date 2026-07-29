<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# DB Test Module

> **Status:** Implemented
> **Package:** `dev.vertique.db.test`
> **Artifact:** `db-test`
> **Depends on:** db-core, db-flyway (optional)

Test utilities for database integration tests using Testcontainers. Provides a fluent container API, Flyway migration support, and a JUnit 5 extension for auto-start/stop lifecycle management. Add as a `test` scope dependency only.

---

## Key Classes

### `DatabaseContainer<SELF, C>`

Abstract base class wrapping a Testcontainers `JdbcDatabaseContainer`. Provides lifecycle management, optional Flyway migration, and a `toPoolConfig()` method for wiring Vert.x SQL client pools in tests.

```java
// Manual lifecycle:
static final PostgresContainer db = new PostgresContainer()
        .withDatabaseName("test_db")
        .withMigration();           // run classpath:db/migration at start

@BeforeAll
static void startDb() { db.start(); }

@AfterAll
static void stopDb() { db.close(); }
```

**Methods:**

| Method | Description |
|--------|-------------|
| `start()` | Starts the container; runs migrations if `withMigration()` was called |
| `withMigration()` | Enables Flyway migration from `classpath:db/migration` at start |
| `withMigration(locations)` | Enables Flyway migration from custom locations |
| `toPoolConfig()` | Returns a `DbPoolConfig` pointing to the running container |
| `jdbcUrl()` | Returns the JDBC URL for direct JDBC access |
| `username()` | Returns the database username |
| `password()` | Returns the database password |
| `getContainer()` | Returns the underlying Testcontainers container (dedicated mode only — the no-arg shared-mode `PostgresContainer` has no dedicated container and throws `UnsupportedOperationException`) |
| `runMigrations()` | Protected hook: runs Flyway against this container's *current* connection identity (`jdbcUrl()`/`username()`/`password()`) if `withMigration(...)` was configured; no-op otherwise |
| `close()` | Stops the container |

`withMigration()` requires `db-flyway` on the test classpath. It throws `IllegalStateException` at setup time if Flyway is absent.

`runMigrations()` is exposed as `protected` (rather than being called only from the base `start()`) so a subclass can establish its own connection identity first and then invoke migrations against it — e.g. `PostgresContainer`'s shared-server mode provisions a per-instance database before calling `runMigrations()`, instead of migrating against the base class's default container connection.

### `PostgresContainer`

Concrete `DatabaseContainer` for PostgreSQL, wrapping `org.testcontainers.containers.PostgreSQLContainer`. Default image: `postgres:16-alpine`.

The no-arg constructor runs in **shared-server mode**. Instead of starting a dedicated
`PostgreSQLContainer` per instance, it provisions its own database on one PostgreSQL server
container shared by every no-arg `PostgresContainer` in the same Surefire/Failsafe fork. The
server is started once per fork (lazily, on first use) and is never explicitly stopped — the
Testcontainers Ryuk sidecar reaps it when the JVM exits. Each instance's `start()` provisions a
unique per-instance database on that server (`CREATE DATABASE`, sanitized `withDatabaseName` hint
plus a monotonic counter, truncated to Postgres's 63-character identifier limit) and runs its own
Flyway migrations against it; `close()` drops only that instance's database
(`DROP DATABASE ... WITH (FORCE)`, best-effort — `SQLException`s are swallowed; an invalid database name throws `IllegalArgumentException`).

`PostgresContainer(String image)` (custom image) keeps the original **dedicated-container**
behavior unchanged — the escape hatch for image-specific tests or tests that need custom
credentials.

```java
// Default image (postgres:16-alpine), shared-server mode:
static final PostgresContainer db = new PostgresContainer()
        .withDatabaseName("test_db")
        .withMigration();

// Custom image, dedicated container:
static final PostgresContainer db = new PostgresContainer("postgres:15")
        .withDatabaseName("test_db");
```

**Fluent configuration methods:**

| Method | Description |
|--------|-------------|
| `withDatabaseName(name)` | In shared mode, records the hint used to derive the per-instance database name at `start()`. In dedicated mode, sets the container's database name directly. |
| `withUsername(username)` | Dedicated mode only — sets the container's username. Throws `UnsupportedOperationException` in shared mode. |
| `withPassword(password)` | Dedicated mode only — sets the container's password. Throws `UnsupportedOperationException` in shared mode. |
| `toPoolConfig()` | Returns `DbPoolConfig` for this instance's database — shared server host/mapped-port in shared mode, or the dedicated container's host/mapped-port in dedicated mode. |

**Invariants & Gotchas**

- **Shared-mode credentials come from the shared server** (the Testcontainers defaults, `test`/`test` superuser). Custom
  credentials are only available via the dedicated-mode constructor (`PostgresContainer(String)`);
  calling `withUsername`/`withPassword` on a shared-mode instance throws
  `UnsupportedOperationException`.
- **Connection-identity accessors require `start()` first.** In shared mode, `jdbcUrl()`,
  `username()`, `password()`, and `toPoolConfig()` all throw `IllegalStateException` if called
  before `start()` — the instance has no provisioned database yet to describe.
- **Isolation is database-level, not cluster-level.** Every no-arg `PostgresContainer` in a JVM
  fork shares one PostgreSQL server; only the database is per-instance. Migrations and tests must
  not create or depend on cluster-global objects (roles, tablespaces, cluster-level extensions) —
  those would leak across every test class sharing the server. This is a review-enforced
  invariant, not a machine-checked one.
- **`close()` never stops the shared server**, even for the last instance in a fork — the server
  is intentionally left running for Ryuk to reap at JVM exit, matching the same pattern used by
  `vertique-kafka-test`'s shared broker.

### `SharedPostgresServer`

Package-private utility owning `PostgresContainer`'s shared-mode singleton. Mirrors
`vertique-kafka-test`'s `KafkaTestContainers` pattern: a lazily-started, never-explicitly-stopped
`PostgreSQLContainer` shared by every no-arg `PostgresContainer` in the same JVM fork.

Responsibilities:

- Starts the shared `PostgreSQLContainer` (default image, Testcontainers-default `test`/`test` superuser
  credentials) on first use, guarded by double-checked locking so concurrent `start()` calls from
  different `PostgresContainer` instances converge on one server.
- `provisionDatabase(hint)` — creates a uniquely-named database on the shared server via
  `CREATE DATABASE`, deriving the name from a sanitized hint plus a monotonically increasing
  counter (truncated to Postgres's 63-character identifier limit).
- `dropDatabaseQuietly(database)` — best-effort `DROP DATABASE ... WITH (FORCE)`; swallows
  `SQLException`s (the shared server outlives every per-instance database regardless), but
  rejects an invalid database name with `IllegalArgumentException` and no-ops when no shared
  server is running.
- Exposes `host()`, `port()`, `jdbcUrl(database)`, `username()`, `password()` for
  `PostgresContainer` to build its per-instance connection identity and `DbPoolConfig`.

Not intended for direct use outside `PostgresContainer` — it has no public API and is not part of
this module's extension surface.

### `DatabaseExtension`

JUnit 5 extension that automatically starts and stops all `static DatabaseContainer` fields in a test class. Discovers fields via reflection; works with any `DatabaseContainer` subclass.

```java
@ExtendWith(DatabaseExtension.class)
class ItemRepositoryIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("test_db")
            .withMigration();

    Pool pool;

    @BeforeEach
    void setUp() {
        Vertx vertx = Vertx.vertx();
        pool = PgBuilder.pool()
                .connectingTo(new PgConnectOptions()
                        .setHost(db.toPoolConfig().getHost())
                        .setPort(db.toPoolConfig().getPort())
                        .setDatabase(db.toPoolConfig().getDatabase())
                        .setUser(db.toPoolConfig().getUser())
                        .setPassword(db.toPoolConfig().getPassword()))
                .using(vertx)
                .build();
    }
}
```

`DatabaseExtension` calls `container.start()` in `@BeforeAll` and `container.close()` in `@AfterAll`. Containers are shared across all tests in the class for performance.

### `FlywayContainerMigrationRunner`

Package-private utility called by `DatabaseContainer` when `withMigration()` is configured. Runs `flyway.migrate()` synchronously against the container's JDBC URL immediately after the container starts. Not intended for direct use.

---

## Integration Test Pattern

A complete integration test combining `DatabaseExtension`, `PostgresContainer`, and `VertxExtension`:

```java
@ExtendWith({DatabaseExtension.class, VertxExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ItemRepositoryIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("test_db")
            .withMigration();

    ItemRepository repository;

    @BeforeEach
    void setUp(Vertx vertx) {
        DbPoolConfig poolConfig = db.toPoolConfig();
        PgDbExceptionMapper exceptionMapper = new PgDbExceptionMapper();
        Pool pool = PgBuilder.pool()
                .connectingTo(new PgConnectOptions()
                        .setHost(poolConfig.getHost())
                        .setPort(poolConfig.getPort())
                        .setDatabase(poolConfig.getDatabase())
                        .setUser(poolConfig.getUser())
                        .setPassword(poolConfig.getPassword()))
                .using(vertx)
                .build();
        repository = new ItemRepository(pool, exceptionMapper);
    }

    @Test
    void shouldCreateAndFindItem(VertxTestContext ctx) {
        repository.create("Widget", "A test widget")
                .compose(created -> repository.findById(created.id()))
                .onSuccess(item -> {
                    assertThat(item).isNotNull();
                    assertThat(item.name()).isEqualTo("Widget");
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }
}
```

**Isolation contract for IT authors.** `PostgresContainer`'s no-arg (shared-server) constructor
isolates at the database level, not the container level — every no-arg instance in a JVM fork runs
against the same PostgreSQL server. When writing a new IT (or a new Flyway migration set consumed
by one), do not create or depend on cluster-global objects: roles, tablespaces, or extensions
installed at the cluster rather than the database level. Anything cluster-global would leak across
every test class sharing the server. If a test genuinely needs cluster-level isolation, use the
dedicated-mode constructor (`new PostgresContainer("postgres:15")` — any image string triggers
dedicated mode) instead.

---

## Dependencies

- `dev.vertique:db-core` — `DbPoolConfig`, `DatabaseContainer` base type
- `dev.vertique:db-flyway` — `FlywayContainerMigrationRunner` (optional, for `withMigration()`)
- `org.testcontainers:testcontainers` — `JdbcDatabaseContainer`
- `org.testcontainers:postgresql` — `PostgreSQLContainer`
- `org.junit.jupiter:junit-jupiter` — `BeforeAllCallback`, `AfterAllCallback`
- `org.slf4j:slf4j-api`
