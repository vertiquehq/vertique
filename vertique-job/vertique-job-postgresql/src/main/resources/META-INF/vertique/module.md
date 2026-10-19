<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Job PostgreSQL Module

> **Status:** Stable
> **Package:** `dev.vertique.job.postgresql`
> **Artifact:** `vertique-job-postgresql`
> **Depends on:** `dev.vertique:vertique-job-core`, `dev.vertique:vertique-db-postgresql`,
> `dev.vertique:vertique-core`. `dev.vertique:vertique-db-flyway` is test-scope here; the application
> supplies the migration runner.

PostgreSQL implementation of the `JobRepository` SPI defined in `job-core`. Persists job execution state, logs, cron schedule definitions, and node heartbeats. Uses `FOR UPDATE SKIP LOCKED` for concurrent-safe job claiming without external coordination. Provides a transactional `save(execution, SqlClient)` overload for outbox-pattern enqueue. Flyway migrations are bundled at `classpath:db/migration/job`. `JobMigrationsModule` declares them as the `job` migration instance for named Flyway instances, and the optional `db.schemas.job` setting places the job tables in one schema for both the migrations and the repository.

---

## Key Classes

### `PgJobRepository`

Extends `PgSqlRepository` and implements `JobRepository`. All SQL operations use the Vert.x reactive PostgreSQL client. JSONB columns (`payload`, `progress`, `parameters`) are serialized as Vert.x `JsonObject` instances, which the pg-client driver handles natively.

Generates a stable `nodeId` from the hostname and a random 8-character suffix, used as the `locked_by` value during job claiming and as the server identity for heartbeats.

**Constructors:**

```java
@Inject
public PgJobRepository(Pool pool, PgDbExceptionMapper exceptionMapper)

public PgJobRepository(Pool pool, PgDbExceptionMapper exceptionMapper, DbSchemas schemas)
```

The two-argument constructor leaves every table unqualified, so tables resolve through the connection's default schema. The three-argument constructor qualifies every job table (`job_executions`, `job_logs`, `job_schedules`, `job_server_heartbeats`) with the schema that `schemas` maps to `job`; with no `job` entry its SQL is the same as the two-argument constructor's. The mapping is validated when the repository is constructed: a schema that is not a single valid PostgreSQL identifier throws `IllegalArgumentException` before any query runs (see `PgTableNames` in `dev.vertique:vertique-db-postgresql`). SQL in this document is shown with unqualified table names.

**Transactional save overload:**

`PgJobRepository` exposes a second `save` overload not in the `JobRepository` interface, used by `DelayedJobService` for outbox-pattern enqueue:

```java
public Future<UUID> save(JobExecution execution, SqlClient client)
```

**Claim implementation:**

`claimNextJob(queue, batchSize)` runs inside a single transaction:
1. Selects eligible rows: `WHERE queue = $1 AND state = 'ENQUEUED' AND scheduled_at <= NOW() ORDER BY priority DESC, scheduled_at ASC LIMIT $2 FOR UPDATE SKIP LOCKED`
2. Updates claimed rows: `SET state = 'PROCESSING', started_at = NOW(), locked_by = <nodeId> WHERE id = ANY($2)`

This guarantees no two callers process the same execution and attributes the work to this node.

**`completeExecution()`** guards on the state *pair*, not on the source state alone: from `PROCESSING` any of `SUCCEEDED`, `FAILED`, `CANCELLED`, `ABANDONED`, `DEAD_LETTER` is accepted, and from `ABANDONED` only `SUCCEEDED`, `FAILED`, `DEAD_LETTER` — so `ABANDONED` → `ABANDONED` is rejected. Accepting `ABANDONED` as a source state, enabling late handler completions to overwrite the coordinator's ABANDONED mark. The SQL ends with `RETURNING *`; the row is mapped via `returningOptional()` to `Future<Optional<JobExecution>>`. `Optional.empty()` signals an idempotent no-op (the execution was already terminal — zero rows updated); a present value carries the persisted execution and is passed to `NotifyingJobRepository` for listener fan-out.

**`scheduleRetry()`** accepts `FAILED` or `ABANDONED` as the current state and resets `started_at`, `completed_at`, `locked_by` while incrementing the attempt counter. A zero-row update (execution not in `FAILED`/`ABANDONED`) now fails with `IllegalStateException` rather than silently no-oping, preventing a `PROCESSING`-state retry that would never schedule.

**`failAndScheduleRetry()`** and **`abandonAndScheduleRetry()`** share one private `markAndScheduleRetry(markState, …)` helper that runs both retry writes in one transaction (`transaction().execute(conn -> …)`): step 1 records `markState` (`FAILED` or `ABANDONED`) via the `RETURNING *` `SQL_COMPLETE` — subject to its state-pair guard, so it transitions only from a valid source (a `PROCESSING` row; an already-`ABANDONED` row passed to `abandonAndScheduleRetry` is rejected and yields empty) — and step 2 re-enqueues the same row via `SQL_SCHEDULE_RETRY` (`markState` → `ENQUEUED`). If step 1 transitioned no row the result is `Optional.empty()`; if step 2 unexpectedly updates no row the transaction rolls back so neither write persists. This guarantees a retryable execution is never left stranded in `FAILED` or `ABANDONED`.

**`tryInsert()`** uses `ON CONFLICT (job_id, scheduled_at) WHERE job_type = 'CRON' AND state NOT IN ('DEAD_LETTER', 'CANCELLED') DO NOTHING RETURNING id` for SINGLE_INSTANCE leader election.

**`serverHeartbeat()`** uses UPSERT to maintain one heartbeat row per server:
`INSERT INTO job_server_heartbeats ... ON CONFLICT (server_id) DO UPDATE SET last_heartbeat = NOW()`

### `JobExecutionMapper`

Package-private utility that maps `Row` objects to `JobExecution` domain objects. Handles all column extraction and null-safety for `job_executions`.

The `payload` column is returned as `JsonObject` (opaque JSONB). Callers that know the concrete type call `((JsonObject) execution.payload()).mapTo(MyType.class)`.

### `NotifyingJobRepository`

Package-private decorator that wraps the raw `PgJobRepository` and fires registered `JobExecutionStateTransitionListener`s after each confirmed persisted transition.

All `JobRepository` methods except `completeExecution`, `failAndScheduleRetry`, and `abandonAndScheduleRetry` delegate verbatim to the inner repository. Those three delegate, then — when the returned `Optional` is present — build a curated `JobExecutionStateTransitionEvent` from the persisted `JobExecution` snapshot (the `FAILED`/`ABANDONED` mark snapshot for the retry ops) and call `onStateTransition` on each listener in sequence. Listener exceptions are isolated: a `RuntimeException` thrown by one listener is caught, logged as a warning, and does not prevent subsequent listeners from running or cause the future to fail.

Instances are constructed exclusively by `JobPostgresqlModule`; no application code constructs or injects this class directly.

### `@RawJobRepository`

Dagger qualifier that marks the undecorated `PgJobRepository` binding inside `JobPostgresqlModule`. Its sole purpose is to break the self-referential cycle: the unqualified `JobRepository` binding is the `NotifyingJobRepository` decorator, which takes the `@RawJobRepository`-qualified inner repository as a constructor argument. Application code never injects `@RawJobRepository JobRepository` directly.

### `JobPostgresqlModule`

Dagger module that wires the repository decorator pattern. It includes `JobModule` to pull in `JobModule`'s `@Multibinds Set<JobExecutionStateTransitionListener>` declaration, so the listener set resolves (empty) even in standalone cron-only setups where no listeners are contributed. It also includes `JobMigrationsModule` (the `job` migration declaration) and `DbSchemasModule` (the optional `DbSchemas` binding).

The module provides three bindings:

1. `PgJobRepository` (singleton): built with the `job` mapping from `Optional<DbSchemas>`, or unqualified when no `DbSchemas` binding exists. A component that includes `DbModule` gets its placement from `db.schemas.job`.
2. `@RawJobRepository JobRepository` → the same `PgJobRepository` (singleton): the bare PostgreSQL implementation, exposed only for use by the decorator.
3. `JobRepository` (unqualified, singleton) → `NotifyingJobRepository`: the listener-firing decorator that all application code and framework components receive. It delegates to the one raw repository, so notifications and node identity stay as before.

Automatically included by `DelayedJobModule`.

**Compatibility note.** `PgJobRepository` is now provided by an explicit `@Provides` method. A component that includes this module and also provides `PgJobRepository` itself sees a duplicate binding; remove its own provider. The `@Inject` two-argument constructor remains for components that do not include this module.

```java
@Component(modules = {DbPostgresqlModule.class, DbFlywayModule.class, JobPostgresqlModule.class, ...})
public interface AppComponent { ... }
```

### `JobMigrationsModule`

Metadata-only Dagger module that contributes one `MigrationDefinition` (from `dev.vertique:vertique-db-core`) with `@IntoSet`: the instance name `job`, the location `classpath:db/migration/job`, and no dependencies. It needs no pool or other bindings, so a migration-only component can include it on its own; `JobPostgresqlModule` includes it, so an application that wires the repository contributes the declaration automatically. The name `job` is also the key of the `db.schemas.job` setting.

---

## Database Schema

Flyway migration:
- `V1__create_job_tables.sql` — initial schema (`job_executions`, `job_logs`, `job_schedules`, `job_server_heartbeats`), including `metadata JSONB` on `job_executions` for durable context propagation and the `target` column on `job_schedules` for the explicit target-reference cron model

**The `V1` schema is frozen.** `vertique-job-postgresql` is Stable, so every later schema change ships
as a new `V2+` migration file; `V1` is never edited again. A database that applied an earlier `V1`
would fail Flyway checksum validation, which is why the fold-back practice ends here.

### `job_executions`

| Column | Type | Description |
|--------|------|-------------|
| `id` | `UUID PK` | Unique execution identifier |
| `job_id` | `VARCHAR(255)` | Logical job identifier (stable across retries) |
| `job_type` | `VARCHAR(20)` | `CRON`, `DELAYED`, or `BATCH` |
| `handler` | `VARCHAR(255)` | Event bus address of the handler |
| `queue` | `VARCHAR(255)` | Logical queue name (default: `'default'`) |
| `state` | `VARCHAR(20)` | `ENQUEUED`, `PROCESSING`, `SUCCEEDED`, `FAILED`, `DEAD_LETTER`, `CANCELLED`, `ABANDONED` |
| `payload` | `JSONB` | Typed job data; null if no payload |
| `progress` | `JSONB` | Latest progress snapshot (`total`, `succeeded`, `failed`, `status`) |
| `parameters` | `JSONB` | Static parameters provided at scheduling time |
| `attempt` | `INTEGER` | Zero-based attempt counter (default: 0) |
| `max_attempts` | `INTEGER` | Maximum allowed attempts (default: 5) |
| `priority` | `INTEGER` | Higher values claimed first (default: 0) |
| `scheduled_at` | `TIMESTAMPTZ` | When this execution is eligible for claiming |
| `started_at` | `TIMESTAMPTZ` | When processing began |
| `completed_at` | `TIMESTAMPTZ` | When processing finished |
| `locked_by` | `VARCHAR(255)` | Node identity of the worker; null when not PROCESSING |
| `last_error` | `TEXT` | Human-readable error message on failure |
| `error_type` | `VARCHAR(500)` | Exception class name on failure |
| `metadata` | `JSONB` | Durable context `DurableMetadata` document persisted as a `{"context": {namespace: {...}}}` carrier; written at enqueue time by `DelayedJobService`; read at dispatch time by `DelayedJobPoller` |
| `created_at` | `TIMESTAMPTZ` | Row creation time |
| `updated_at` | `TIMESTAMPTZ` | Last update time (also serves as heartbeat timestamp) |

**Indexes:**

| Index | Columns | Partial condition | Purpose |
|-------|---------|-------------------|---------|
| `idx_job_executions_claim` | `queue, priority DESC, scheduled_at ASC` | `WHERE state = 'ENQUEUED'` | Job claiming query (poller and cron) |
| `idx_job_executions_heartbeat` | `updated_at` | `WHERE state = 'PROCESSING'` | Stale-job detection by coordinator |
| `idx_job_executions_state` | `state, created_at DESC` | — | Dashboard queries by state |
| `idx_job_executions_job_id` | `job_id, created_at DESC` | — | Dashboard queries by job |
| `idx_job_executions_cron_dedup` | `job_id, scheduled_at` (UNIQUE) | `WHERE job_type = 'CRON' AND state NOT IN ('DEAD_LETTER', 'CANCELLED')` | SINGLE_INSTANCE leader election — only one node wins the INSERT race |

### `job_logs`

Per-execution log entries written by `JobLogger`. The scheduling module (`vertique-job-cron`,
`vertique-job-delayed`) drains the in-memory buffer through a per-execution `JobLogFlusher`, which
persists batches via `JobRepository.saveLogs()`: on the periodic progress-flush tick, on every path
that ends the execution, and once more as a bounded cutoff drain at shutdown — see each scheduling
module's reference for the exact flush sites. The ending sites and the shutdown snapshot use the
flusher's bounded `drain()` rather than a single `flush()`, so a `saveLogs` call left outstanding by
the tick cannot swallow the entries appended behind it.

| Column | Type | Description |
|--------|------|-------------|
| `id` | `BIGSERIAL PK` | Auto-increment row ID |
| `execution_id` | `UUID FK → job_executions.id` | Owning execution (CASCADE DELETE) |
| `level` | `VARCHAR(5)` | `INFO`, `WARN`, `ERROR` |
| `message` | `TEXT` | Log message |
| `logged_at` | `TIMESTAMPTZ` | When the entry was logged |

Index: `idx_job_logs_execution` on `(execution_id, logged_at)`.

### `job_schedules`

Cron schedule definitions written by `JobRepository.saveSchedule()` at startup. Code is the source of truth — rows are overwritten on every deploy via UPSERT.

| Column | Type | Description |
|--------|------|-------------|
| `job_id` | `VARCHAR(255) PK` | Unique job identifier |
| `cron_expression` | `VARCHAR(100)` | 6-field cron expression string |
| `handler` | `VARCHAR(255)` | Event bus address of the handler; nullable — a `ServiceTarget` schedule leaves it null and uses `target` |
| `target` | `VARCHAR(512)` | Resolved cron target reference the scheduler reads and writes |
| `execution_mode` | `VARCHAR(20)` | `EVERY_INSTANCE` or `SINGLE_INSTANCE` |
| `timezone` | `VARCHAR(50)` | IANA zone ID |
| `enabled` | `BOOLEAN` | Whether the schedule is active |
| `overlap_policy` | `VARCHAR(20)` | `SKIP` or `QUEUE_ONE` |
| `max_attempts` | `INTEGER` | Max attempts per execution |
| `tracked` | `BOOLEAN` | Whether executions are persisted |
| `last_fired_at` | `TIMESTAMPTZ` | Last fire instant (for misfire detection) |
| `next_fire_at` | `TIMESTAMPTZ` | Next computed fire instant |
| `created_at` | `TIMESTAMPTZ` | Row creation time |
| `updated_at` | `TIMESTAMPTZ` | Last update time |

### `job_server_heartbeats`

One row per live application instance. Used by `JobCoordinator` for dead-node detection.

| Column | Type | Description |
|--------|------|-------------|
| `server_id` | `VARCHAR(255) PK` | Unique node identifier (`hostname-<suffix>`) |
| `last_heartbeat` | `TIMESTAMPTZ` | Last heartbeat update (expires after `nodeHeartbeatTimeoutMs`) |
| `started_at` | `TIMESTAMPTZ` | When this node registered |

---

## Flyway Configuration

The job migration is declared once and can be run two ways.

### Named instances

Give the job tables their own schema and history table:

```json
{
  "db": {
    "schemas": { "job": "jobs" }
  },
  "flyway": {
    "mode": "MIGRATE",
    "instances": { "job": {} }
  }
}
```

- **One setting places everything.** `db.schemas.job` is the job instance's default schema and history
  schema, and `PgJobRepository` qualifies every job table with it, so the migrations and every job
  statement use one schema. The history table is `flyway_schema_history_job` in that schema.
- **Without a `db.schemas.job` entry** the migration uses the first entry of the instance's Flyway
  `schemas` list, or the connection's default schema, and the repository stays unqualified. Configure
  the migration and runtime connections to the same default schema.
- **Include `DbModule` in the migration component** (or bind `DbSchemas` yourself). A named migration
  component without either ignores `db.schemas`, and the tables can land in a different schema than a
  runtime component with `DbModule` queries.
- **The application's own migrations** are a separate instance with a disjoint location such as
  `classpath:db/migration/app`. A broad `classpath:db/migration` location overlaps
  `classpath:db/migration/job` and is rejected.
- **A dedicated DDL user** is set with `flyway.user` and `flyway.password` (both, or neither), or per
  instance with `flyway.instances.job.user` and `flyway.instances.job.password`. Without them the
  migration uses the `db` pool credentials.

`dev.vertique:vertique-db-flyway` documents the full instance configuration, the agreement rules between
`db.schemas` and Flyway schema lists, and the failure model.

### Single Flyway (legacy) configuration

When `flyway.instances` is absent, the default `classpath:db/migration` location already includes the
bundled `db/migration/job` scripts, so no extra location is needed. Every bundled adapter ships its own `V1` script, so one history can hold only one of them (a second would fail with a duplicate version); to run several adapters, use named instances. The single-history example:

```json
{
  "flyway": {
    "mode": "MIGRATE",
    "user": "ddl_user",
    "password": "ddl_secret"
  }
}
```

If you override `locations`, list the job directory next to an application directory that does not
contain it, for example `["classpath:db/migration/job", "classpath:db/app"]`. Do not list both
`classpath:db/migration` and `classpath:db/migration/job`: the first already covers the second.

In this mode the migration lands where the legacy configuration says. If `db.schemas.job` is also
set, the repository queries that schema, so make the legacy `flyway.schemas` list (first entry) or the
connection default match it, or the repository will look in a schema the migration never filled.

---

## Dagger Wiring

`JobPostgresqlModule` must be included alongside `DbPostgresqlModule`. It automatically includes `JobModule` (for the listener multibinding) and is automatically included by `DelayedJobModule`. For cron-only setups with DB persistence, include it explicitly:

```java
@Component(modules = {
    VertxModule.class,
    DbPostgresqlModule.class,
    DbFlywayModule.class,
    JobPostgresqlModule.class,
    JobCoordinatorModule.class,
    CronPersistenceModule.class,
    AppModule.class
})
public interface AppComponent { ... }
```

---

## Dependencies

- **job-core** — `JobRepository` SPI, `JobExecution`, `JobExecutionStateTransitionListener`, `JobState`, `JobType`, `ProgressSnapshot`, `LogEntry`, `CronJobSchedule`
- **db-core** — `DbSchemas`, `DbSchemasModule`, `MigrationDefinition` (through db-postgresql)
- **db-postgresql** — `PgSqlRepository`, `PgDbExceptionMapper`, `PgTableNames`, `Pool`
- **db-flyway** — `FlywayMigrationRunner` applies the bundled migration; test-scope here, so the application supplies it
