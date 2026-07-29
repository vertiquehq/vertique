<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# DB Flyway Module

> **Status:** Implemented
> **Package:** `dev.vertique.db.flyway`
> **Artifact:** `db-flyway`
> **Depends on:** db-core, core

Flyway-backed implementation of the `MigrationRunner` contract from `db-core`. Supports three operation modes: MIGRATE (apply pending migrations), VALIDATE (verify schema matches migrations), and DISABLED (no-op). Designed for CI/CD pipelines that use a privileged DDL user for migrations and a restricted user for runtime.

When used with the framework lifecycle runner (`@VertiqueApp` + `VertiqueApplicationBootstrap`), `DbFlywayModule` also contributes a `MIGRATE`-phase `ApplicationStartupStep` (`FlywayMigrationStartupStep`) that runs migrations automatically — no manual `migrationRunner().migrate(vertx)` call is needed. See [`FlywayMigrationStartupStep` and `DbFlywayModule`](#flywaymigrationstartupstep-and-dbflywaymodule) below.

---

## Key Classes

### `FlywayMode`

Enum controlling Flyway operation mode.

| Value | Description |
|-------|-------------|
| `MIGRATE` | Run `flyway.migrate()` — applies pending migrations (CI/CD with DDL user) |
| `VALIDATE` | Run `flyway.validate()` — fails if schema diverges from migrations (app runtime) |
| `DISABLED` | No-op — returns `MigrationResult(0, null)` immediately |

### `FlywayConfig`

Lombok `@Builder` configuration value object. Deserialized from the `"flyway"` section of the application config.

```json
{
  "flyway": {
    "mode": "VALIDATE",
    "jdbcUrl": "jdbc:postgresql://localhost:5432/mydb",
    "user": "ddl_user",
    "password": "ddl_secret",
    "locations": ["classpath:db/migration", "classpath:db/migration/extra"],
    "target": null,
    "schemas": ["public"],
    "placeholders": { "schema": "public" },
    "outOfOrder": false,
    "cleanDisabled": true,
    "baselineOnMigrate": false,
    "baselineVersion": "1",
    "validateOnMigrate": true
  }
}
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `mode` | `FlywayMode` | `MIGRATE` | Operation mode |
| `jdbcUrl` | `String` | — | JDBC URL for migration connection; if null, uses pool config host/port/database |
| `user` | `String` | — | DDL user for migrations; falls back to `DbPoolConfig.user()` if null |
| `password` | `String` | — | DDL password; falls back to `DbPoolConfig.password()` if null |
| `locations` | `List<String>` | `["classpath:db/migration"]` | Classpath or filesystem locations for migration scripts |
| `target` | `String` | `null` (latest) | Target migration version; `null` means migrate to latest |
| `schemas` | `List<String>` | `[]` | Schemas managed by Flyway; empty = default schema of the connection |
| `placeholders` | `Map<String, String>` | `{}` | Key-value substitutions for `${key}` in migration scripts |
| `outOfOrder` | `boolean` | `false` | Allow migrations with lower version than already-applied ones |
| `cleanDisabled` | `boolean` | `true` | Disable `flyway clean` — defaults to `true` as a production safety net |
| `baselineOnMigrate` | `boolean` | `false` | Apply baseline to existing DB on first migration |
| `baselineVersion` | `String` | `"1"` | Baseline version applied when `baselineOnMigrate` is true |
| `validateOnMigrate` | `boolean` | `true` | Validate applied migrations on each migrate run |

**Credential fallback:** When `user` or `password` are not set in `FlywayConfig`, `FlywayMigrationRunner` falls back to the pool credentials from `DbPoolConfig`. This lets applications use the same credentials for both migrations and runtime when a separate DDL user is not needed.

### `FlywayMigrationRunner`

`@Singleton` implementation of `MigrationRunner` backed by Flyway. Flyway is JDBC-based (blocking), so `migrate()` executes inside `Vertx.executeBlocking()` to avoid blocking the event loop.

When using `@VertiqueApp` + `CoreLifecycleStepsModule` + `DbFlywayModule`, `FlywayMigrationStartupStep` calls `migrate()` automatically in the `MIGRATE` phase — no manual call is needed.

**Behavior by mode:**

- `MIGRATE` — calls `flyway.migrate()`, logs the number of applied migrations and target version
- `VALIDATE` — calls `flyway.validate()`, fails the future if schema diverges
- `DISABLED` — returns `Future.succeededFuture(new MigrationResult(0, null))` immediately without creating a Flyway instance

**Exception wrapping:** Any `FlywayException` thrown during `migrate()` or `validate()` is caught and re-thrown as a `MigrationException` (from `db-core`). This prevents Flyway vendor types from leaking into calling code.

### `MigrationException` (db-core)

Runtime exception wrapping vendor-specific migration failures. Extends `VertiqueException` and optionally carries a partial `MigrationResult` if the migration engine reported progress before failing.

```java
try {
    migrationRunner.migrate(vertx).await();
} catch (MigrationException e) {
    log.error("Migration failed", e);
    MigrationResult partial = e.partialResult(); // may be null
}
```

### `FlywayMigrationStartupStep` and `DbFlywayModule`

`DbFlywayModule` is the Dagger `@Module` that provides `FlywayConfig @Singleton`, binds
`FlywayMigrationRunner` as the `MigrationRunner` implementation, and — when the application uses
the framework lifecycle runner — contributes `FlywayMigrationStartupStep` as a
`MIGRATE`-phase `ApplicationStartupStep` via `@IntoSet`.

`FlywayMigrationStartupStep` is a `@Singleton` `ApplicationStartupStep` for the `MIGRATE` phase.
Its `start()` method calls `migrationRunner.migrate(vertx).mapEmpty()`. The runner invokes it
automatically before the `INFRA`, `SERVICES`, and `EDGE` phases, so the schema is migrated (or
validated) before any verticle that depends on it is brought up.

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    CoreLifecycleStepsModule.class,  // CONFIGURE + VALIDATE steps
    RestModule.class,
    DbModule.class,
    DbPostgresqlModule.class,
    DbFlywayModule.class,            // provides FlywayConfig, MigrationRunner, MIGRATE step
    AppModule.class,
    ResourceModule.class
})
interface AppComponent extends VertiqueApplicationComponent {}
```

With `@VertiqueApp` and `VertiqueApplicationBootstrap`, no manual `migrationRunner().migrate(vertx)`
call is needed — the runner drives `FlywayMigrationStartupStep` automatically in phase order.

**Bindings provided:**

| Type | Scope | Description |
|------|-------|-------------|
| `FlywayConfig` | `@Singleton` | Deserialized from `"flyway"` config section |
| `MigrationRunner` | `@Singleton` | Bound to `FlywayMigrationRunner` |
| `ApplicationStartupStep` (`FlywayMigrationStartupStep`) | `@Singleton @IntoSet` | MIGRATE-phase step contributed to the lifecycle runner |

#### Single-history-table limitation

`FlywayMigrationStartupStep` performs **one Flyway run** with the configured `flyway.locations` and
a single Flyway schema history table (`flyway_schema_history` by default). This is sufficient for
applications whose migration scripts live in one logical namespace.

Applications that need **multiple independent migration locations with separate history tables** —
for example, an application that manages both a business schema and a workflow/inbox-outbox schema,
where each must have its own history table — cannot use `FlywayMigrationStartupStep`. Those
applications must:

1. Set `flyway.mode=DISABLED` to disable the automatic step.
2. Run each migration location manually, with separate `Flyway` instances configured for each
   history table, by contributing their own `MIGRATE`-phase `ApplicationStartupStep` instances so
   the runner still sequences them before `INFRA`, `SERVICES`, and `EDGE` deploy.

The single-run limitation is by design — multi-schema migration with separate history tables
requires application-level coordination that a generic framework step cannot safely abstract.

---

## SSL-Aware JDBC URL Fallback

When `flyway.jdbcUrl` is not set, `FlywayMigrationRunner` constructs a PostgreSQL JDBC URL from the pool configuration. When `DbPoolConfig.sslMode()` is not `DISABLE`, SSL query parameters are appended automatically:

| `DbPoolConfig` field | JDBC parameter appended |
|----------------------|------------------------|
| `sslMode` (e.g., `REQUIRE`) | `?sslmode=require` |
| `trustStorePath` | `&sslrootcert=<path>` |
| `certPath` | `&sslcert=<path>` |
| `keyPath` | `&sslkey=<path>` |

Example URL produced for `sslMode=VERIFY_FULL` with all cert paths set:

```
jdbc:postgresql://db.example.com:5432/mydb?sslmode=verify_full&sslrootcert=/etc/ssl/ca.pem&sslcert=/etc/ssl/client.crt&sslkey=/etc/ssl/client.key
```

The `sslMode` value is lowercased for JDBC compatibility.

---

## Deployment Patterns

### MIGRATE mode (CI/CD pipeline or test environments)

```json
{
  "flyway": {
    "mode": "MIGRATE",
    "user": "ddl_user",
    "password": "ddl_secret"
  }
}
```

Runs all pending migrations at startup. Safe to run repeatedly — Flyway is idempotent.

### VALIDATE mode (production runtime)

```json
{
  "flyway": {
    "mode": "VALIDATE"
  }
}
```

Verifies the deployed schema matches the migrations packaged in the application. Fails fast if the schema was modified outside of Flyway. Uses the runtime credentials (no DDL user needed).

### DISABLED mode (testing without migrations, or multi-schema apps)

```json
{
  "flyway": {
    "mode": "DISABLED"
  }
}
```

Skips Flyway entirely. Useful in two scenarios:
- A test container has already run migrations via `DatabaseContainer.withMigration()`.
- The application manages multiple migration locations with separate history tables (see the
  [single-history-table limitation](#single-history-table-limitation) above) and handles migration
  manually via custom `MIGRATE`-phase steps.

### Multiple migration locations

```json
{
  "flyway": {
    "locations": ["classpath:db/migration", "classpath:db/migration/extra"]
  }
}
```

### Schema placeholder substitution

```json
{
  "flyway": {
    "placeholders": { "schema": "public", "env": "prod" }
  }
}
```

Migration scripts can reference `${schema}` and `${env}` and Flyway replaces them at runtime.

---

## Dependencies

- `dev.vertique:db-core` — `MigrationRunner`, `MigrationResult`, `MigrationException`, `DbPoolConfig`
- `dev.vertique:core` — `VertxConfig`, `VertiqueException`
- `io.vertx:vertx-core` — `Vertx.executeBlocking()`
- `org.flywaydb:flyway-core`
- `org.flywaydb:flyway-database-postgresql`
- `org.postgresql:postgresql` — JDBC driver
- `com.google.dagger:dagger`
- `jakarta.inject:jakarta.inject-api`
- `com.fasterxml.jackson.core:jackson-databind` — `FlywayConfig` deserialization
- `org.slf4j:slf4j-api`
- `org.projectlombok:lombok` (provided)
