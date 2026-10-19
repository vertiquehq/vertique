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
| `coordinationLockTimeoutMs` | `long` | `30000` | Accepted range 1 to 300000, checked in named mode. Upper bound on how long a named run that migrates waits for the database-wide [coordination lock](#coordination-lock); has no effect on legacy or `VALIDATE`-only runs |

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

**Exception wrapping:** Any `FlywayException` thrown during `migrate()` or `validate()` is caught and re-thrown as a `MigrationException` (from `dev.vertique:vertique-db-core`). This prevents Flyway vendor types from leaking into calling code. Named mode throws the `MigrationRunException` subclass instead and keeps no native cause; see [Reports and failures](#reports-and-failures).

**Named-instance API.** The runner has two further entry points, used only with `flyway.instances`:

```java
public FlywayMigrationRunner(FlywayConfig config, DbPoolConfig db,
                             Set<MigrationDefinition> definitions, DbSchemas schemas);
public List<MigrationInstanceView> instances();     // connection-free, may block
public Future<MigrationReport> run(Vertx vertx);    // same operation the startup step runs
```

The two-argument `@Inject` constructor is unchanged and behaves as before. The constructors only retain their inputs and open no connection. `MigrationInstanceView`, `MigrationReport`, `MigrationInstanceResult`, `MigrationStatus`, `CredentialSource` (`INSTANCE`, `ROOT_FLYWAY`, `DB_POOL`) and `MigrationRunException` are top-level types in `dev.vertique.db.flyway`; they are described under [Named Instances](#named-instances).

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
| any other value | `MigrationRunException` (`CONFIGURATION`, operation `configuration`) |

- **Every wired declaration runs**, whether or not it has an override entry. An override entry whose
  name matches no wired declaration is rejected rather than registering a module.
- **Names** match `[a-z][a-z0-9-]{0,31}`; `legacy` is reserved. A name is also the key of its
  `db.schemas` entry.
- **An empty declaration set is rejected** in named mode, except when the root `mode` is `DISABLED`.
- **Unknown fields are rejected.** An instance object accepts only the override fields below; `schema`
  is not an alias of `schemas`. Root fields keep their existing policy: unknown root keys are ignored.
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
| `db.schemas.job: jobs`, instance `schemas: [other]` or `[]` | rejected before connecting | — |
| no entry, instance `schemas: [x]` | `x` | `[x]` |
| no entry, no list | the default schema of the instance's own migration connection, resolved and frozen at run time | empty (Flyway manages that one schema) |

- **No silent precedence.** An explicit instance list must start with S, and an explicit `[]` conflicts
  with an entry; both are rejected. An inherited root list contributes its other entries after S.
  A list with a blank or duplicate entry is rejected.
- **Without an entry**, runtime SQL stays unqualified. The operator must then configure the migration
  and runtime connections to the same default schema, including a separate migration role. The runner
  has no runtime pool and cannot check this, and the condition holds after migrations as well as at
  startup: creating a schema that sits earlier in a runtime search path can change what unqualified
  names resolve to. Prefer a `db.schemas` entry whenever instances live in different schemas.
- **Default resolution.** When an instance has no `db.schemas` entry and an empty effective schema list,
  a run opens a short connection with that instance's own selected credential pair, reads the
  connection's `current_schema()` and uses it as the instance's default and history schema. A role
  whose search path resolves to no existing schema, or to a name that breaks the
  [identifier rules](#identifier-rules), is rejected before any write. The resolved default is **frozen
  for the invocation**: the instance's default schema is bound explicitly in Flyway, so a schema an
  earlier instance creates in a later role's search path cannot move that later history, and the
  report shows the frozen schema. The next invocation resolves afresh. Resolution reads the
  connection's default without changing its search path.
- **Native search-path fallback.** PostgreSQL keeps fallback search-path entries, so an unqualified
  reference to an existing object may resolve outside the first schema. Qualify existing-object
  references in a script when its target must be deterministic; the runner does not rewrite SQL.
- **Scripts may name schemas explicitly.** That SQL is owned by the script, not by placement.
- **Shared lists are allowed**, but every instance needs a distinct history table in its schema.
  Instances that resolve to the same schema and table are rejected, and so are history tables whose
  derived names collide (see [Identifier rules](#identifier-rules)). A schema known from configuration
  is checked before connecting; a default resolved from a role is checked at the start of the run,
  before any write.

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
instance are supported against one database. `credentialSource` in the resolved view reports which
level supplied the pair.

**Locations.** A declaration's locations are scheme-qualified directories: `classpath:` prefixes such
as `classpath:db/migration/job`, or `filesystem:` directories. Wildcards, dot segments and duplicate
entries are rejected. A location that does not exist fails with `RESOURCE` instead of silently
migrating nothing. Locations of different instances, or two of one instance, must not be equal or
nested, so `classpath:db/migration/job` and `classpath:db/migration/job-v2` coexist but a broad
`classpath:db/migration` collides with both. Give application declarations a disjoint prefix such as
`classpath:db/migration/app`. Filesystem locations are compared by real path. Flyway itself reports
duplicate versions within an instance.

### Inspecting and running

```java
FlywayMigrationRunner runner = ...;
List<MigrationInstanceView> views = runner.instances();      // blocking; call off the event loop
Future<MigrationReport> report = runner.run(vertx);          // runs on a Vert.x worker
```

- **`instances()`** resolves and validates the configuration without connecting and returns the
  instances in execution order. It looks up classpath and filesystem locations on the calling thread's
  context class loader, which can block: call it off the event loop. Every call validates afresh. A
  view carries the effective schema, managed list, table, locations, mode, target, baseline and
  validation settings, user, `credentialSource` and placeholder *keys*; it never carries a password or
  placeholder value. `schema` is `null` when the connection default applies, because `instances()` does
  not connect; a run reports the frozen default instead.
- **`run(Vertx)`** repeats the same validation on a worker before opening any connection. It then opens
  the guard session, runs the batch preflight described under [Execution scope](#execution-scope), runs
  each enabled instance through native Flyway and reports every outcome. In legacy mode it returns a
  single entry named `legacy` with the history schema and table actually used.
- **`migrate(Vertx)`** is what `FlywayMigrationStartupStep` calls. In named mode it runs the same
  operation as `run`, sums the applied counts, and returns the native target version only when the
  report has exactly one entry (a `DISABLED` entry counts); otherwise `targetVersion` is `null`.
- **A batch holds one worker thread** for its whole duration, and that includes the wait for the
  coordination lock. A batch longer than the Vert.x worker-execute-time limit (60 seconds by default)
  draws blocked-thread warnings; `coordinationLockTimeoutMs` may be set above that limit, so a long
  wait alone can trigger them. Raise `VertxOptions.maxWorkerExecuteTime` or run long migrations as
  their own job.
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
| `NOT_RUN` | An earlier instance failed, or the batch was rejected before any instance ran. In the second case every entry is `NOT_RUN`, a disabled one included |

`currentVersion` is read through Flyway after the operation; it is `null` when no versioned migration
is applied, and is not the same value as `MigrationResult.targetVersion()`. If that read fails after the
operation succeeded, the entry stays `MIGRATED` or `VALIDATED` with a `null` `currentVersion`. For an
enabled instance the view's `schema` is the frozen history schema the run used, including a default
resolved from the instance's role.

A failure of a named operation is a `MigrationRunException` (a `MigrationException`):

| Accessor | Value |
|----------|-------|
| `failureCode()` | One of `CONFIGURATION`, `RESOURCE`, `COORDINATION`, `PREFLIGHT`, `MIGRATION`, `VALIDATION` |
| `operation()` | `configuration` (config binding), `instances`, `run`, or `coordination` |
| `report()` | `null` when the failure came before the instances were resolved; otherwise the full ordered report |

What each safety failure looks like:

| Situation | `failureCode()` / `operation()` | Report |
|-----------|---------------------------------|--------|
| Schema or history table name breaks the [identifier rules](#identifier-rules), or two histories collide on names known from configuration | `CONFIGURATION` / `instances` (from `instances()`), `run` (from a run) | `instances()`: none; a run: every entry `NOT_RUN` |
| Guard connection cannot be opened, the lock is not acquired within `coordinationLockTimeoutMs`, or a guard round trip times out while the lock is requested | `COORDINATION` / `coordination` | every entry `NOT_RUN`, a disabled one included; no instance is blamed |
| Destination's `version()` does not start with `PostgreSQL`, a role's default resolves to nothing, a collision appears only after defaults resolve, the mixed-schema check or a conventional-history check rejects the batch, a history an instance keeps in place fails its validation, the catalog cannot be read or a check's round trip times out | `PREFLIGHT` / `run` | every entry `NOT_RUN` |
| The mixed-schema check or the conventional-history check rejects an instance just before it runs (an earlier instance created a schema or a conventional history) | `PREFLIGHT` / `run` | earlier results kept, that instance `FAILED` with nothing applied, later entries `NOT_RUN` |
| The guard session lost the lock between instances, does not hold it for a migrating instance, or a guard round trip times out while the lock is confirmed | `COORDINATION` / `coordination` | earlier results kept, the next instance `FAILED` with nothing applied, later entries `NOT_RUN` |

- **Safe messages.** The message is written by the framework: operation, instance name, and a fixed
  description. It may add the Flyway error-code name and the SQLState or failing migration, and never
  contains raw native messages, SQL text, JDBC URLs, credentials or placeholder values. The exception
  keeps no cause and no suppressed exception, because native exceptions can carry statements and
  connection details. This differs from legacy mode, which keeps the original `MigrationException`
  with its native cause. Named configuration-binding failures keep no Jackson cause for the same
  reason. The guard and preflight failures above use fixed text that names the instance where one is
  to blame; it carries no schema, table or URL values and no native message, only an SQLState when the
  catalog could not be read.
- **Occupied schema.** When an instance's default schema already holds objects but the instance has no
  history, the message tells you to give the instance its own schema with a separate `db.schemas`
  entry, or to set `baselineOnMigrate=true` with `baselineVersion="0"` for that instance so every
  migration still runs. See the next section.

Native Flyway renders placeholder-substituted statements when `org.flywaydb` logs at DEBUG. Keep
placeholders free of secrets and keep `org.flywaydb` at INFO.

**Callbacks.** Callbacks keep native Flyway behavior and are trusted deployment resources that may
write. Wherever this document says the runner "writes nothing" or rejects "before any write", it means
the runner's own writes (schemas, history tables, migrations) and excludes callback effects. A callback
in a location can run during a validation that ends in a rejection, and during `VALIDATE`.

`VALIDATE` instances run native validation per history. The runner creates no schema or history
table for them, even against an absent history; with runtime credentials they need `USAGE` on the
schema and `SELECT` on the history. A `VALIDATE`-only run resolves defaults and checks history
collisions exactly like a migrating run, takes no [coordination lock](#coordination-lock), and skips
the initialization checks of [Existing and shared schemas](#existing-and-shared-schemas).

### Existing and shared schemas

Named mode never converts, copies or relocates an existing history. An installation whose scripts
already ran in a shared history stays in legacy mode, and nothing in named mode converts it. The runner
performs a bounded check for the conventional shared history (below) but cannot prove that a database
has no earlier history.

For a fresh install, give each instance its own schema (one `db.schemas` entry each). If several
instances must share a schema, or an instance's managed list includes schemas that already exist:

- **Mixed-schema guard.** Before an instance without a history initializes one, the runner checks
  which of the instance's managed schemas exist. If some exist and some are missing, the batch is
  rejected before any write: provision **every** schema of that instance's managed set, or none of
  them, and run again with the same baseline settings. The check reads only whether each schema
  exists, never whether it holds objects, so an existing empty schema counts as existing. A set whose
  schemas are all missing is created by native Flyway, and a set whose schemas all exist follows
  native baseline rules. The check runs once for the batch and again immediately before each
  instance, so a schema an earlier instance's script created can fail a later instance (it reports
  `FAILED` with nothing applied and keeps the earlier results). An instance that already has its
  history is never subject to it. The reason is native behavior: when Flyway creates a missing managed
  schema beside existing ones it can record a history marker that bypasses the later baseline
  decision.
- **Shared extras.** A root `schemas` list inherited by several instances becomes the extra managed
  schemas of each. After the first instance creates the extras, a second instance whose own schema is
  still missing is rejected by the guard; create its schema (and every other schema of its set) and run
  again, or give the instances disjoint extra schemas, which need no provisioning.
- **After provisioning, the native baseline policy decides.** With `baselineOnMigrate=false` (the
  default) a non-empty schema without the instance's history fails with the message above. With
  `baselineOnMigrate: true` and `baselineVersion: "0"` Flyway records its baseline marker and runs
  `V1`; with `true` and version `1` it skips `V1`. Versions at or below the baseline are skipped, so
  root `baselineOnMigrate: true` with the default root `baselineVersion` `1` skips `V1` in an occupied
  schema. To install independent scripts into an occupied shared schema, use `true` and `"0"` on
  every instance that shares the schema: even an initially empty shared schema is occupied once the
  first instance commits. A per-instance `false` overrides a root `true`. The guard never changes
  these outcomes; it only keeps a mixed set from bypassing them.
- **Named opt-in authorizes pending scripts only.** It does not establish that existing objects match
  them; conflicts surface through Flyway and the scripts.

#### Conventional shared history detection

Before an instance initializes a missing history, the runner looks for a table named
`flyway_schema_history`, the single-Flyway default, and rejects the instance before it writes when it
finds one that no instance keeps (at batch start, the whole batch before any write). The detection is bounded, and an undetected history is not proof
that scripts ran:

- **Where it looks.** The default schema of the guard connection (the root credential pair), the first
  entry of the root `flyway.schemas` list even if every instance replaces that list, every managed
  schema of every enabled instance, and every instance's frozen default schema.
- **What it finds.** Only a table with the conventional name, by schema and table name in the catalog.
  A history with a custom name, a history in a schema outside that set, or a history created by other
  tooling is not found.
- **When it looks.** When the batch starts, and again immediately before each migrating instance
  that has no history yet. The repeat probes the same schemas and reads again from the catalog which
  instances now own a conventional history, so one that an earlier instance's script created fails
  that instance (it reports `FAILED` with nothing applied and keeps the earlier results) unless an
  enabled instance owns it. A history that becomes owned only during the run does not get the
  forced validation of the in-place exception below, which runs once, before any instance.
- **What to do.** Keep the installation in legacy mode. Changing the named schemas or removing or
  renaming a history is not a supported transition, and there is no preview, copy or conversion.
  Never point an instance at a new schema or location and assume that an undetected custom history
  proves its scripts already ran.

**Keeping an existing history in place.** One exception lets an independently managed history stay
where it is while new instances are installed beside it: configure that instance with
`table: flyway_schema_history` and the module's own locations. When some other instance is about to
initialize its history, the runner validates that history with native Flyway against the owner's
resources before any instance runs, with target `latest` and pending migrations as the only ignored state. The validation is
forced even when `validateOnMigrate` is `false`. Pending migrations of the owner pass; an applied
migration the owner's locations do not describe (such as a row another module applied), a failed
migration or a checksum mismatch rejects the whole batch before any write. This shows the history
fits the instance's resources; it does not prove that the instance owns every row. The owner must
have its explicit table `flyway_schema_history` in its default schema, and a `DISABLED` owner does
not count, so its history is treated as unowned and blocks the batch.

### Legacy mode and `db.schemas`

When `flyway.instances` is absent, adapters and applications still qualify their SQL from any
`db.schemas` entry, but legacy migrations land where the legacy configuration says (`flyway.schemas`
and `flyway.locations`, or the connection default). The runner never checks the legacy list against
`db.schemas`. **If they differ, queries look in a schema the migrations never filled.** Make the
legacy configuration match each entry, or move to named instances.

With a legacy configuration, the default `classpath:db/migration` location already includes the
subdirectories of packaged modules, such as `db/migration/job`. Listing both a broad location and a
module's location is redundant.

### Execution scope

Named execution validates configuration, then runs each enabled instance through native Flyway in
dependency order. Nothing connects until configuration and resources have validated. A run then:

1. **Opens the guard session**: a dedicated direct connection with the root credential pair (the root
   `flyway.user`/`flyway.password` when both are set, else the `DbPoolConfig` pair). It rejects a
   destination whose `version()` does not start with `PostgreSQL`, even when its URL uses the
   PostgreSQL scheme; a wire-compatible product that reports itself as PostgreSQL is admitted. Any run
   with an enabled instance opens it, including a `VALIDATE`-only run.
2. **Takes the [coordination lock](#coordination-lock)** when at least one enabled instance is `MIGRATE`.
3. **Preflights the batch**: resolves and freezes every unknown default schema, rejects history
   collisions, and, for migrating instances without a history, applies the
   [mixed-schema guard and the conventional-history detection](#existing-and-shared-schemas).
4. **Validates a kept history** (the in-place exception) natively, before any instance runs.
5. **Runs the instances in order.** Before each enabled instance the guard rechecks that it still holds
   the lock (when the run holds one) and, for a migrating instance without a history, the mixed-schema guard
   and the conventional-history detection. Then native Flyway
   migrates or validates it with the frozen default schema bound.

The guard session pins only itself: after reading its own default schema it sets its `search_path` to
`pg_catalog`, and every catalog query is schema-qualified and uses bound values, so a role's search
path cannot redirect the guard's checks. Migration sessions are never altered and keep Flyway's native
default and search-path behavior, including fallback-schema resolution of unqualified references.

**Operator exclusions.** The guard coordinates this runner only. During a run, nothing else may:

- run schema DDL against the managed schemas or write to the history tables;
- change the roles or their `search_path` configuration.

Migration scripts must not change their own default schema or write another instance's history.
Arbitrary script analysis is outside the runner. **Direct connection required:** the guard connection
and the session lock need one stable backend session, so the root JDBC URL must reach PostgreSQL
directly, not through a transaction-pooling proxy. An idle-session timeout or termination of the guard
connection (for example `idle_session_timeout` or an administrator terminating the backend) ends the
lock: the next instance fails closed, but a native migration already running continues without the
lock's protection, so keep idle timeouts for the guard role above the longest migration.

**Several replicas.** Every replica that starts in named `MIGRATE` mode runs the batch, and runs that
overlap are serialized by the lock. Prefer running migrations as a one-off deployment job in `MIGRATE`
mode and starting the replicas in `VALIDATE` or `DISABLED`. If replicas must migrate at startup, raise
`coordinationLockTimeoutMs` above the longest expected batch so that waiting replicas do not time out; a
waiting replica blocks its worker thread for the whole wait, and a wait or batch beyond the Vert.x
worker-execute-time limit draws blocked-thread warnings.

**Known limitation: disabled instances.** The run-time preflight sees enabled instances only. A
history table collision between a default schema resolved from a role at run time and a `DISABLED`
instance that already has a known schema is therefore not detected. Disabled instances never connect;
keep a disabled instance's schema and history table distinct from those of the enabled ones.

**Database engines.** Named execution runs on PostgreSQL destinations (a root JDBC URL starting with
`jdbc:postgresql:`); any other destination fails with `CONFIGURATION` before any write, and legacy
mode on another Flyway-supported database is unchanged and receives none of these checks. The
database-specific code is internal to this artifact, so supporting a second engine needs a change in
`vertique-db-flyway`, not configuration. `MigrationDefinition.locations` is interpreted by the
migration engine, not by `vertique-db-core`. `PgTableNames` validates `db.schemas` values when an
adapter is constructed.

### Identifier rules

The PostgreSQL backend validates every resolved instance in `instances()` and at the start of a run,
before any connection. All rules are case-sensitive: `Jobs` and `jobs` are different schemas.

| Name | Rule |
|------|------|
| Schema (default schema and every managed schema) | One identifier of ASCII letters, digits and underscores, not starting with a digit, at most 63 characters. Names starting `pg_` and `information_schema` are reserved |
| History table | The same syntax, at most 57 characters, so the primary-key and index relations Flyway derives from it fit the 63-character limit |
| Collisions | In one schema, no history table may equal another history table or one of its derived relation names (`<table>_pk`, `<table>_s_idx`) |

A role's resolved default schema must pass the schema rules too. The reserved prefix applies to
schemas only; a history table may start with `pg_`. `DbSchemas` itself stays vendor-neutral: the
rules run on the resolved migration instance, not when `db.schemas` is bound.

### Coordination lock

A named run that includes a `MIGRATE` instance takes one database-wide PostgreSQL advisory session
lock before any database-dependent check, on the guard connection, and holds it through every outcome
until the run ends. It covers the whole preflight and every instance, and a failing or partly failed
batch holds it to the end too. The lock serializes this runner's runs across all histories; it does
not replace Flyway's per-history locks.

- **Bounded wait.** The wait for the lock and every guard round trip are bounded: the runner polls for
  the lock for at most `coordinationLockTimeoutMs`, and each round trip of the guard connection and of
  the short default-schema connections is bounded by a 30-second network timeout. If another run, or
  any session holding the same key, still has the lock at the deadline, the run fails with
  `COORDINATION` and every entry `NOT_RUN`, a disabled one included, and writes nothing. A round trip
  that times out fails the run closed: `COORDINATION` while the lock is requested or confirmed,
  `PREFLIGHT` during a check. The network timeout bounds only a reply the runner is waiting for; it
  does not end an idle guard connection during a long native migration, and the idle-session caveat
  under [Operator exclusions](#execution-scope) still applies.
- **Lock key.** The lock is the two-integer advisory lock `(1448365393, 1296648018)`, visible in `pg_locks` with `locktype = 'advisory'` while a run holds it. Advisory locks are database-wide, so any role that can connect to the database can hold the same key; treat a `COORDINATION` timeout as either a concurrent run or a foreign holder of that key.
- **Not taken when not needed.** `VALIDATE`-only runs, legacy runs and fully disabled runs never take or
  wait for the lock.
- **Liveness.** Before each instance the guard confirms the same backend still owns the lock. If the
  guard session was ended or does not hold the lock, the next instance fails with `COORDINATION` (reported `FAILED`, nothing
  applied), earlier results are kept and later entries are `NOT_RUN`.
- **Release.** Closing the guard connection releases the lock on every exit; a connection that does not
  close cleanly is aborted.

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
