<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# DB Flyway Module

> **Status:** Stable
> **Package:** `dev.vertique.db.flyway`
> **Artifact:** `vertique-db-flyway`
> **Depends on:** `dev.vertique:vertique-db-core`, `dev.vertique:vertique-core`

Flyway-backed implementation of the `MigrationRunner` contract from `dev.vertique:vertique-db-core`. Supports three operation modes: MIGRATE (apply pending migrations), VALIDATE (verify schema matches migrations), and DISABLED (no-op). Designed for CI/CD pipelines that use a privileged DDL user for migrations and a restricted user for runtime.

By default one Flyway run uses one history table. Opt-in **named instances** run several independent migration locations, each with its own history table, schema and version sequence, in dependency order — see [Named Instances](#named-instances).

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
| `instances` | `List<FlywayInstanceConfig>` | `null` | Named-instance overrides, bound from the keyed `flyway.instances` object. `null` (absent) keeps the legacy single Flyway; a non-null list, even empty, selects [named mode](#named-instances) |
| `lockRetryCount` | `Integer` | `null` | Number of one-second retries Flyway makes to acquire its history-table lock while another process holds it. `null` keeps Flyway's default of `50` (about 50 seconds); `-1` retries without a bound. Values below `-1` are rejected when the runner resolves named instances, or builds Flyway for a legacy `MIGRATE`/`VALIDATE` run. Applies to the legacy run and, unless an instance overrides it, to every named instance. See [Concurrent starts](#concurrent-starts) |

**Secret hygiene:** `password` and `placeholders` are write-only — read from configuration but never
serialized back out by Jackson. `toString()` renders the password as `<redacted>`, the placeholder
keys without their values, and a `password=` parameter in `jdbcUrl` masked, on the class and on its
builder. The same holds for every nested `FlywayInstanceConfig`. Prefer `user` and `password` over
credentials embedded in `jdbcUrl`.

**Credential fallback:** When `user` or `password` are not set in `FlywayConfig`, `FlywayMigrationRunner` falls back to the pool credentials from `DbPoolConfig`, field by field. This lets applications use the same credentials for both migrations and runtime when a separate DDL user is not needed. Named instances select a complete user and password pair instead; see [Instance overrides](#instance-overrides).

### `FlywayInstanceConfig`

Record holding the overrides of one named instance, bound from `flyway.instances.<name>`. Only `name` is required; every other component is `null` until resolution, and `null` means inherit. The fields are listed under [Instance overrides](#instance-overrides). `password` and `placeholders` are write-only and redacted in `toString()`.

### `FlywayMigrationRunner`

`@Singleton` implementation of `MigrationRunner` backed by Flyway. Flyway is JDBC-based (blocking), so `migrate()` executes inside `Vertx.executeBlocking()` to avoid blocking the event loop.

When using `@VertiqueApp` + `CoreLifecycleStepsModule` + `DbFlywayModule`, `FlywayMigrationStartupStep` calls `migrate()` automatically in the `MIGRATE` phase — no manual call is needed.

**Behavior by mode:**

- `MIGRATE` — calls `flyway.migrate()`, logs the number of applied migrations and target version
- `VALIDATE` — calls `flyway.validate()`, fails the future if schema diverges
- `DISABLED` — returns `Future.succeededFuture(new MigrationResult(0, null))` immediately without creating a Flyway instance

**No-op result:** a `MIGRATE` run that finds nothing pending returns `MigrationResult(0, null)`; Flyway
reports a target version only for the migrations it applied, so callers must not read
`targetVersion()` as "current schema version".

**Exception wrapping:** Any `FlywayException` thrown during `migrate()` or `validate()` is caught and re-thrown as a `MigrationException` (from `dev.vertique:vertique-db-core`). This prevents Flyway vendor types from leaking into calling code. Named mode throws the `MigrationRunException` subclass instead and keeps the native cause just the same; see [Reports and failures](#reports-and-failures).

**Named-instance API.** The runner has two further entry points, used only with `flyway.instances`:

```java
public FlywayMigrationRunner(FlywayConfig config, DbPoolConfig db,
                             Set<MigrationDefinition> definitions, DbSchemas schemas);
public List<MigrationInstanceView> instances();     // opens no connection
public Future<MigrationReport> run(Vertx vertx);    // same operation the startup step runs
```

The two-argument `@Inject` constructor is unchanged and behaves as before. The constructors only retain their inputs and open no connection. `MigrationInstanceView`, `MigrationReport`, `MigrationInstanceResult`, `MigrationStatus` and `MigrationRunException` are top-level types in `dev.vertique.db.flyway`; they are described under [Named Instances](#named-instances).

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

`DbFlywayModule` is the Dagger `@Module` that provides `FlywayConfig @Singleton`, provides the
`FlywayMigrationRunner` and binds it as the `MigrationRunner` implementation, and — when the
application uses the framework lifecycle runner — contributes `FlywayMigrationStartupStep` as a
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
| `FlywayConfig` | `@Singleton` | Deserialized from `"flyway"` config section, including `flyway.instances` |
| `FlywayMigrationRunner` | `@Singleton` | Built from the config, `Set<MigrationDefinition>` and `Optional<DbSchemas>` |
| `MigrationRunner` | `@Singleton` | Bound to `FlywayMigrationRunner` |
| `ApplicationStartupStep` (`FlywayMigrationStartupStep`) | `@Singleton @IntoSet` | MIGRATE-phase step contributed to the lifecycle runner |
| `Set<MigrationDefinition>` | `@Multibinds` | Declared, possibly empty; modules and applications contribute with `@IntoSet` |
| `Optional<DbSchemas>` | `@BindsOptionalOf` | Through `DbSchemasModule`; empty when no `DbModule` or application binding exists, so components without `DbModule` keep compiling |

**Compatibility note.** The module now provides `FlywayMigrationRunner` with an explicit `@Provides`
method. A component that provides `FlywayMigrationRunner` itself would now see a duplicate binding:
drop its provider, or inject the `MigrationRunner` the module binds. Direct construction with the
two-argument constructor remains available. Applications that bind their own `DbSchemas` must not
include `DbModule`.

#### One history table per legacy run

Without `flyway.instances`, `FlywayMigrationStartupStep` performs **one Flyway run** with the
configured `flyway.locations` and a single schema history table (`flyway_schema_history` by default).
That suits applications whose scripts live in one namespace. Applications that need several
independent locations, each with its own history table, opt in to [named instances](#named-instances)
instead of running their own Flyway instances by hand.

---

## Named Instances

Named instances give each module its own migration location, history table and version sequence in
one run. They are opt-in: without a `flyway.instances` object the runner keeps the single-Flyway
behavior described above.

### Quick start

Give each module one `db.schemas` entry, then declare one Flyway instance per module:

```json
{
  "db": {
    "schemas": { "job": "jobs", "app": "app_data" }
  },
  "flyway": {
    "mode": "MIGRATE",
    "instances": { "job": {}, "app": {} }
  }
}
```

Each instance migrates into its schema and keeps its history there (`flyway_schema_history_job`,
`flyway_schema_history_app`). Modules contribute their declaration through Dagger; the application
adds its own the same way:

```java
@Module
abstract class AppMigrationsModule {
    @Provides
    @IntoSet
    static MigrationDefinition appMigrations() {
        return new MigrationDefinition("app", List.of("classpath:db/migration/app"), List.of());
    }
}

@Component(modules = {
    VertxModule.class,
    ConfigModule.class,
    DbModule.class,
    DbFlywayModule.class,
    JobMigrationsModule.class,   // contributes the "job" declaration
    AppMigrationsModule.class
})
interface MigrationComponent {
    FlywayMigrationRunner migrationRunner();
}
```

A migration-only component needs no connection pool. The runtime component reads the same
`db.schemas` through `DbModule`, so repositories query the schemas the migrations filled (see
`dev.vertique:vertique-db-core`). Migration scripts stay unqualified: the instance's default schema
decides where their objects land.

### Selecting named mode

| `flyway.instances` | Result |
|--------------------|--------|
| absent or `null` | Legacy single Flyway, unchanged |
| `{}` | Named mode with every wired `MigrationDefinition` |
| `{ "job": {...} }` | Named mode; the object overrides that instance's settings |
| any other value | `ConfigurationException` from the config parser, before any runner exists |

- **Every wired declaration runs**, whether or not it has an override entry. An override entry whose
  name matches no wired declaration is rejected rather than registering a module.
- **Names** match `[a-z][a-z0-9-]{0,31}`; `legacy` is reserved. A name is also the key of its
  `db.schemas` entry.
- **An empty declaration set is rejected** in named mode, except when the root `mode` is `DISABLED`.
- **Unknown fields are rejected.** An instance object accepts only the override fields below; `schema`
  is not an alias of `schemas`. The `ConfigurationException` message names `flyway.instances.<name>`,
  the offending key and the supported fields, never a value. Root fields keep their existing policy:
  unknown root keys are ignored.
- **Binding failures are `ConfigurationException`.** The `flyway` section is bound in one step by the
  config parser, for both modes: `instances` that is not an object, an instance value that is not an
  object, a blank instance key, an entry whose own `name` differs from its key, an unsupported key, or
  a field of the wrong type all fail with the framework's `ConfigurationException`, not with a
  `MigrationRunException`. The message never contains a configuration value, so a password or
  placeholder value cannot reach a log through it. For a plain type error the parser keeps the
  underlying Jackson failure as the cause, and that cause can quote the offending value; do not log
  the cause chain of a binding failure.
- **Accepted residual.** A misspelled root key such as `flyway.instance` is ignored and selects legacy
  mode, and a misspelled root `mode` takes the `MIGRATE` default, the most permissive ceiling. Confirm
  the resolved instances and modes with `instances()` before deployment; in legacy mode it returns an
  empty list.

### Where an instance's schema comes from

`db.schemas.<name>` (call it S) is the instance's default schema, its history schema and its runtime
placement. `flyway.schemas` and `flyway.instances.<name>.schemas` keep their native meaning: the
ordered list of schemas Flyway manages and creates. A list never creates runtime placement, and extra
managed schemas do not become a search path.

| Configuration | Default and history schema | Managed list passed to Flyway |
|---------------|----------------------------|-------------------------------|
| `db.schemas.job: jobs`, no Flyway lists | `jobs` | `[jobs]` |
| `db.schemas.job: jobs`, root `schemas: [shared, audit]` inherited | `jobs` | `[jobs, shared, audit]` |
| `db.schemas.job: jobs`, instance `schemas: [jobs, archive]` | `jobs` | `[jobs, archive]` |
| `db.schemas.job: jobs`, instance `schemas: [other]` or `[]` | rejected before connecting | none |
| no entry, instance `schemas: [x]` | `x` | `[x]` |
| no entry, no list | the connection's default schema; the runner does not read or report it | empty (Flyway manages that one schema) |

- **No silent precedence.** An explicit instance list must start with S, and an explicit `[]` conflicts
  with an entry; both are rejected. An inherited root list contributes its other entries after S.
  A list with a blank or duplicate entry is rejected.
- **`MigrationInstanceView.schema()`** is the first schema of the managed list, or `null` when the
  instance has none. It is computed from configuration only: no connection is opened, so `null` means
  the connection's default schema applies and the runner does not say which one that is.
- **Without an entry**, runtime SQL stays unqualified. The operator must then configure the migration
  and runtime connections to the same default schema, including a separate migration role. The runner
  has no runtime pool and cannot check this. Prefer a `db.schemas` entry whenever instances live in
  different schemas.
- **Native search-path fallback.** PostgreSQL keeps fallback search-path entries, so an unqualified
  reference to an existing object may resolve outside the first schema. Qualify existing-object
  references in a script when its target must be deterministic; the runner does not rewrite SQL.
- **Scripts may name schemas explicitly.** That SQL is owned by the script, not by placement.
- **Shared lists are allowed**, but every instance needs a distinct history table in its schema.
  Instances with the same schema and table (a missing schema counts as the same schema) are rejected
  before connecting.

How the neighbouring components read `db.schemas`:

| Component | Effect |
|-----------|--------|
| Runtime component with `DbModule` and an adapter, no db-flyway | `DbSchemas` from `db.schemas` places adapter SQL; no Flyway configuration needed |
| No `DbModule`; the application binds `DbSchemas` | The application owns migration setup; adapters use its placement |
| Named migration component with neither `DbModule` nor a `DbSchemas` binding | **`db.schemas` is ignored for migration.** Defaults come from Flyway lists or the connection default, so migrations can land in a different schema than a runtime component with `DbModule` queries. Include `DbModule` or bind `DbSchemas` |
| Application binds `DbSchemas` and includes `DbModule` | Dagger reports a duplicate binding at compile time; choose one source |
| Root `DISABLED` with `db.schemas` entries | No migration connection; placement still resolves |

### Instance overrides

Fields of a `flyway.instances.<name>` object. Omitted or `null` inherits; every default below is
applied after inheritance. Lists and maps replace the inherited value in full and never merge;
`false`, `[]` and `{}` are values, not omissions.

| Field | Type | Resolution |
|-------|------|------------|
| `mode` | `FlywayMode` | Instance, else root. See the mode ceiling below. |
| `locations` | `List<String>` | Replaces the declaration's locations. Root `locations`, including its `classpath:db/migration` default, is never inherited. An empty list is invalid. |
| `schemas` | `List<String>` | Replaces the root list; `[]` clears it. Must start with the `db.schemas` entry when there is one. |
| `table` | `String` | Instance, else `flyway_schema_history_` plus the name with hyphens as underscores. |
| `target` | `String` | Instance, else root, else the latest version. An inherited numeric target applies to each independent version sequence. |
| `outOfOrder` | `Boolean` | Instance, else root (default `false`). |
| `baselineOnMigrate` | `Boolean` | Instance, else root (default `false`). |
| `baselineVersion` | `String` | Instance, else root (default `"1"`). Setting it alone does not baseline. |
| `validateOnMigrate` | `Boolean` | Instance, else root (default `true`). |
| `placeholders` | `Map<String,String>` | Replaces the root map; `{}` clears it. |
| `user`, `password` | `String` | One credential pair; see below. |
| `lockRetryCount` | `Integer` | Instance, else root, else Flyway's default of `50`. `-1` retries without a bound; values below `-1` are rejected. See [Concurrent starts](#concurrent-starts). |

`jdbcUrl`, the database and the dependency graph are not per-instance: one root JDBC destination per
runner. Root `cleanDisabled` applies to every instance, and there is no clean operation.

**Mode ceiling.** The root `mode` is a safety ceiling. Root `MIGRATE` permits `MIGRATE`, `VALIDATE` and
`DISABLED`; root `VALIDATE` permits `VALIDATE` and `DISABLED`; root `DISABLED` permits only
`DISABLED`. A more permissive instance mode is rejected before any resource or database work; it is
never raised or clamped. Root `DISABLED` makes no connection at all.

**Credentials.** Each instance uses one complete user and password pair, taken from its own
`user`/`password`, else the root `flyway.user`/`flyway.password`, else the `DbPoolConfig` pair. A
partial pair is rejected: one member set on the instance, or a root pair with one member that the
instance would inherit. Values never mix across levels (the legacy single-Flyway path still falls back
per field). Separate migration roles per
instance are supported against one database. The resolved view shows the selected `user`, never the
password.

**Locations.** A declaration's locations are Flyway locations such as `classpath:db/migration/job` or
`filesystem:/opt/migrations/job`. An empty list or a blank entry is rejected. The runner does not parse
locations or look them up: every named Flyway runs with `failOnMissingLocations(true)`, so a location
that does not exist fails that instance instead of silently migrating nothing, and Flyway's own naming
validation is on. Flyway cannot see across instances, so the runner rejects classpath locations of
different instances that are equal or nested: `classpath:db/migration/job` and
`classpath:db/migration/job-v2` coexist, but a broad `classpath:db/migration` would swallow the scripts
of both. Give application declarations a disjoint prefix such as `classpath:db/migration/app`.
Filesystem locations are not compared; keep them disjoint yourself.

### Inspecting and running

```java
FlywayMigrationRunner runner = ...;
List<MigrationInstanceView> views = runner.instances();      // configuration only: no connection, no I/O
Future<MigrationReport> report = runner.run(vertx);          // runs on a Vert.x worker
```

- **`instances()`** resolves and validates the configuration without connecting or looking up any
  location, and returns the instances in execution order. Every call validates afresh. A view carries
  the effective schema, managed list, table, locations, mode, target, baseline and validation
  settings, user and placeholder *keys*; it never carries a password or placeholder value.
- **`run(Vertx)`** repeats the same validation on a worker before opening any connection, then runs
  each enabled instance through native Flyway and reports every outcome. In legacy mode it returns a
  single entry named `legacy` with the first configured schema (or `null`) and the history table.
- **`migrate(Vertx)`** is what `FlywayMigrationStartupStep` calls. In named mode it runs the same
  operation as `run`, sums the applied counts, and returns the native target version only when the
  report has exactly one entry (a `DISABLED` entry counts); otherwise `targetVersion` is `null`.
- **A batch holds one worker thread** for its whole duration, including any wait for another process's
  history lock. A batch longer than the Vert.x worker-execute-time limit (60 seconds by default) draws
  blocked-thread warnings. Raise `VertxOptions.maxWorkerExecuteTime` or run long migrations as their
  own job.
- **Order.** Instances run in dependency order with lexicographic tie-breaking. Unknown and self
  dependencies and cycles are rejected. A `DISABLED` dependency is ordered but not executed:
  disabling it declares that the operator manages its readiness.
- **First failure stops the batch.** Earlier instances stay committed and are never rolled back; later
  ones are reported `NOT_RUN`. A rerun continues from each instance's own history. There is no
  distributed rollback, blanket retry or clean.

### Reports and failures

`MigrationReport.instances()` lists one `MigrationInstanceResult` per instance, in execution order:
the resolved view, a `MigrationStatus`, `migrationsApplied`, and `currentVersion`.

| Status | Meaning |
|--------|---------|
| `MIGRATED` | Native migrate completed |
| `VALIDATED` | Native validation passed |
| `DISABLED` | The instance mode is `DISABLED`; Flyway did not run for it |
| `FAILED` | The instance failed; `migrationsApplied` is 0 and `currentVersion` is `null` |
| `NOT_RUN` | An earlier instance failed, or the run could not start because no JDBC destination is configured |

`currentVersion` is read through Flyway after the operation; it is `null` when no versioned migration
is applied, and is not the same value as `MigrationResult.targetVersion()`. If that read fails after the
operation succeeded, the entry stays `MIGRATED` or `VALIDATED` with a `null` `currentVersion`.

A failure of a named operation is a `MigrationRunException` (a `MigrationException`):

| Accessor | Value |
|----------|-------|
| `stage()` | `Stage.CONFIGURATION` (the configuration or declarations are invalid, from `instances()` or a run), `Stage.MIGRATION` (a native migrate failed) or `Stage.VALIDATION` (a native validate failed) |
| `report()` | `null` when the failure came before the instances were resolved; otherwise the full ordered report |

- **Messages.** The message is a fixed sentence written by the framework: the stage, the instance name
  and a fixed description. For a native failure it adds the Flyway error-code name and the SQLState
  when the failure carries them. It never contains SQL text, JDBC URLs, credentials or placeholder
  values.
- **Native cause.** For `MIGRATION` and `VALIDATION` the original Flyway exception is the cause, as in
  legacy mode. **Do not log the cause chain verbatim:** Flyway messages can embed statement text and
  connection details. Log the message and `stage()`. A configuration that cannot be bound fails earlier
  with a `ConfigurationException`, which follows the parser's own policy described under
  [Selecting named mode](#selecting-named-mode).
- **Occupied schema.** When an instance's default schema already holds objects but the instance has no
  history, the message tells you to give the instance its own schema with a separate `db.schemas`
  entry, or to set `baselineOnMigrate=true` with `baselineVersion="0"` for that instance so every
  migration still runs. See the next section.
- **Lock wait exhausted.** If another process holds an instance's history lock for longer than
  `lockRetryCount` allows, Flyway fails and the instance reports `FAILED`; restart the process, or see
  [Concurrent starts](#concurrent-starts).

Native Flyway renders placeholder-substituted statements when `org.flywaydb` logs at DEBUG. Keep
placeholders free of secrets and keep `org.flywaydb` at INFO.

**Callbacks.** Callbacks keep native Flyway behavior and are trusted deployment resources that may
write, including during `VALIDATE`.

`VALIDATE` instances run native validation per history and create nothing; with runtime credentials
they need `USAGE` on the schema and `SELECT` on the history.

### Existing and shared schemas

Named mode never converts, copies or relocates an existing history. An installation whose scripts
already ran in a shared history, such as the default `flyway_schema_history`, stays in legacy mode;
the runner does not look for such a history, and an instance only ever sees its own history table.

For a fresh install, give each instance its own schema (one `db.schemas` entry each).

- **Co-located instances.** If several instances share a schema, or an instance's schema already holds
  objects, native baseline rules decide. With `baselineOnMigrate=false` (the default) a non-empty schema
  without the instance's history fails with the occupied-schema message above. With
  `baselineOnMigrate: true` and `baselineVersion: "0"` Flyway records its baseline marker and runs
  `V1`; with `true` and version `1` it skips `V1`. Versions at or below the baseline are skipped, so
  root `baselineOnMigrate: true` with the default root `baselineVersion` `1` skips `V1` in an occupied
  schema. To install independent scripts into an occupied shared schema, use `true` and `"0"` on
  every instance that shares the schema: even an initially empty shared schema is occupied once the
  first instance commits. A per-instance `false` overrides a root `true`.
- **Mixed schemas: avoid them.** A managed list that mixes an existing, occupied default schema with
  an extra schema that does not exist yet behaves exactly as it does in legacy mode: Flyway creates the
  missing schema and may record its history marker in a way that bypasses the baseline decision. The
  runner adds no check for this. Keep one schema per instance, or provision every schema of an
  instance's list before the first run.
- **Named opt-in authorizes pending scripts only.** It does not establish that existing objects match
  them; conflicts surface through Flyway and the scripts.

### Legacy mode and `db.schemas`

When `flyway.instances` is absent, adapters and applications still qualify their SQL from any
`db.schemas` entry, but legacy migrations land where the legacy configuration says (`flyway.schemas`
and `flyway.locations`, or the connection default). The runner never checks the legacy list against
`db.schemas`. **If they differ, queries look in a schema the migrations never filled.** Make the
legacy configuration match each entry, or move to named instances.

With a legacy configuration, the default `classpath:db/migration` location already includes the
subdirectories of packaged modules, such as `db/migration/job`. Listing both a broad location and a
module's location is redundant.

### Concurrent starts

Cross-process safety is Flyway's own. On PostgreSQL, Flyway takes a transactional advisory lock keyed
on the schema-qualified history table name before it reads the history, re-reads the history inside the
lock and creates the history table under the same lock.

- **Two pods starting together.** One migrates; the other waits for the lock, then finds the history up
  to date and applies nothing. Instances have different history tables and therefore different locks,
  and one process runs its instances one after another, holding one lock at a time, so dependency order
  holds and there is no deadlock.
- **Bounded wait.** A waiting process retries once a second. By default it gives up after about 50
  seconds (`lockRetryCount` 50) and the run fails, so the process must be restarted. Set
  `flyway.lockRetryCount` (or an instance's `lockRetryCount`) higher to wait longer, or to `-1` to wait
  without a bound. The setting applies to the legacy run too; when it is unset Flyway's own default is
  left untouched.
- **A waiting process blocks its worker thread** for the whole wait; a wait beyond the Vert.x
  worker-execute-time limit draws blocked-thread warnings.
- **Prefer a one-off job.** Run migrations as a deployment job in `MIGRATE` mode and start the replicas
  in `VALIDATE` or `DISABLED`; if every replica migrates at startup, raise `lockRetryCount` above the
  longest expected migration.
- **Nothing else may write.** Do not run schema DDL against the managed schemas or write to the
  history tables during a run. Migration scripts must not change their own default schema or write
  another instance's history.

**Database engines.** Each named instance is a plain Flyway run, so named mode carries no
database-specific checks. The fallback JDBC URL built from `DbPoolConfig` is a PostgreSQL URL; other
databases need `flyway.jdbcUrl`. `MigrationDefinition.locations` is interpreted by Flyway, not by
`vertique-db-core`. `PgTableNames` validates `db.schemas` values when an adapter is constructed.

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

### DISABLED mode (testing without migrations, or externally managed migrations)

```json
{
  "flyway": {
    "mode": "DISABLED"
  }
}
```

Skips Flyway entirely. Useful in two scenarios:
- A test container has already run migrations via `DatabaseContainer.withMigration()`.
- The application runs its migrations itself, for example as a separate deployment job, and the
  service starts with the schema already in place.

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

- `dev.vertique:vertique-db-core` — `MigrationRunner`, `MigrationResult`, `MigrationException`, `MigrationDefinition`, `DbSchemas`, `DbSchemasModule`, `DbPoolConfig`
- `dev.vertique:vertique-core` — `VertxConfig`, `VertiqueException`
- `io.vertx:vertx-core` — `Vertx.executeBlocking()`
- `org.flywaydb:flyway-core`
- `org.flywaydb:flyway-database-postgresql`
- `org.postgresql:postgresql` — JDBC driver
- `com.google.dagger:dagger`
- `jakarta.inject:jakarta.inject-api`
- `com.fasterxml.jackson.core:jackson-databind` — `FlywayConfig` deserialization
- `org.slf4j:slf4j-api`
- `org.projectlombok:lombok` (provided)
