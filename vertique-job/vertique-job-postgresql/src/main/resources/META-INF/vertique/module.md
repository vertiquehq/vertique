<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Job PostgreSQL Module

> **Status:** Implemented
> **Package:** `dev.vertique.job.postgresql`
> **Artifact:** `vertique-job-postgresql`
> **Depends on:** job-core, db-postgresql, db-flyway

PostgreSQL implementation of the `JobRepository` SPI defined in `job-core`. Persists job execution state, logs, checkpoints, cron schedule definitions, and node heartbeats. Uses `FOR UPDATE SKIP LOCKED` for concurrent-safe job claiming without external coordination. Provides a transactional `save(execution, SqlClient)` overload for outbox-pattern enqueue. Flyway migrations are bundled at `classpath:db/migration/job`.

---

## Key Classes

### `PgJobRepository`

Extends `PgSqlRepository` and implements `JobRepository`. All SQL operations use the Vert.x reactive PostgreSQL client. JSONB columns (`payload`, `progress`, `parameters`, checkpoint `value`) are serialized as Vert.x `JsonObject` instances, which the pg-client driver handles natively.

Generates a stable `nodeId` from the hostname and a random 8-character suffix, used as the `locked_by` value during job claiming and as the server identity for heartbeats.

**Constructor:**

```java
@Inject
public PgJobRepository(Pool pool, PgDbExceptionMapper exceptionMapper)
```

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

**`completeExecution()`** accepts both `PROCESSING` and `ABANDONED` as the current state (`WHERE state IN ('PROCESSING', 'ABANDONED')`), enabling late handler completions to overwrite the coordinator's ABANDONED mark. The SQL ends with `RETURNING *`; the row is mapped via `returningOptional()` to `Future<Optional<JobExecution>>`. `Optional.empty()` signals an idempotent no-op (the execution was already terminal — zero rows updated); a present value carries the persisted execution and is passed to `NotifyingJobRepository` for listener fan-out.

**`scheduleRetry()`** accepts `FAILED` or `ABANDONED` as the current state and resets `started_at`, `completed_at`, `locked_by` while incrementing the attempt counter. A zero-row update (execution not in `FAILED`/`ABANDONED`) now fails with `IllegalStateException` rather than silently no-oping, preventing a `PROCESSING`-state retry that would never schedule.

**`failAndScheduleRetry()`** and **`abandonAndScheduleRetry()`** share one private `markAndScheduleRetry(markState, …)` helper that runs both retry writes in one transaction (`transaction().execute(conn -> …)`): step 1 records `markState` (`FAILED` or `ABANDONED`) via the `RETURNING *` `SQL_COMPLETE` — subject to its state-pair guard, so it transitions only from a valid source (a `PROCESSING` row; an already-`ABANDONED` row passed to `abandonAndScheduleRetry` is rejected and yields empty) — and step 2 re-enqueues the same row via `SQL_SCHEDULE_RETRY` (`markState` → `ENQUEUED`). If step 1 transitioned no row the result is `Optional.empty()`; if step 2 unexpectedly updates no row the transaction rolls back so neither write persists. This guarantees a retryable execution is never left stranded in `FAILED` or `ABANDONED`.

**`tryInsert()`** uses `ON CONFLICT (job_id, scheduled_at) WHERE job_type = 'CRON' AND state NOT IN ('DEAD_LETTER', 'CANCELLED') DO NOTHING RETURNING id` for SINGLE_INSTANCE leader election.

**`serverHeartbeat()`** uses UPSERT to maintain one heartbeat row per server:
`INSERT INTO job_server_heartbeats ... ON CONFLICT (server_id) DO UPDATE SET last_heartbeat = NOW()`

### `JobExecutionMapper`

Package-private utility that maps `Row` objects to `JobExecution` and `Checkpoint` domain objects. Handles all column extraction and null-safety for `job_executions` and `job_checkpoints`.

The `payload` column is returned as `JsonObject` (opaque JSONB). Callers that know the concrete type call `((JsonObject) execution.payload()).mapTo(MyType.class)`.

### `NotifyingJobRepository`

Package-private decorator that wraps the raw `PgJobRepository` and fires registered `JobExecutionStateTransitionListener`s after each confirmed persisted transition.

All `JobRepository` methods except `completeExecution`, `failAndScheduleRetry`, and `abandonAndScheduleRetry` delegate verbatim to the inner repository. Those three delegate, then — when the returned `Optional` is present — build a curated `JobExecutionStateTransitionEvent` from the persisted `JobExecution` snapshot (the `FAILED`/`ABANDONED` mark snapshot for the retry ops) and call `onStateTransition` on each listener in sequence. Listener exceptions are isolated: a `RuntimeException` thrown by one listener is caught, logged as a warning, and does not prevent subsequent listeners from running or cause the future to fail.

Instances are constructed exclusively by `JobPostgresqlModule`; no application code constructs or injects this class directly.

### `@RawJobRepository`

Dagger qualifier that marks the undecorated `PgJobRepository` binding inside `JobPostgresqlModule`. Its sole purpose is to break the self-referential cycle: the unqualified `JobRepository` binding is the `NotifyingJobRepository` decorator, which takes the `@RawJobRepository`-qualified inner repository as a constructor argument. Application code never injects `@RawJobRepository JobRepository` directly.

### `JobPostgresqlModule`

Dagger module that wires the repository decorator pattern. Annotated `@Module(includes = JobModule.class)` to pull in `JobModule`'s `@Multibinds Set<JobExecutionStateTransitionListener>` declaration, so the listener set resolves (empty) even in standalone cron-only setups where no listeners are contributed.

The module provides two bindings:

1. `@RawJobRepository JobRepository` → `PgJobRepository` (singleton): the bare PostgreSQL implementation, exposed only for use by the decorator.
2. `JobRepository` (unqualified, singleton) → `NotifyingJobRepository`: the listener-firing decorator that all application code and framework components receive.

Automatically included by `DelayedJobModule`.

```java
@Component(modules = {DbPostgresqlModule.class, DbFlywayModule.class, JobPostgresqlModule.class, ...})
public interface AppComponent { ... }
```

---

## Database Schema

Flyway migration:
- `V1__create_job_tables.sql` — initial schema (`job_executions`, `job_logs`, `job_checkpoints`, `job_schedules`, `job_server_heartbeats`), including `metadata JSONB` on `job_executions` for durable context propagation and the `target` column on `job_schedules` for the explicit target-reference cron model

The framework is pre-release: every new column or table folds back into `V1` rather than shipping as `V2`/`V3` until the first released version is cut.

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

Per-execution log entries written by `JobLogger` and flushed via `JobRepository.saveLogs()`.

| Column | Type | Description |
|--------|------|-------------|
| `id` | `BIGSERIAL PK` | Auto-increment row ID |
| `execution_id` | `UUID FK → job_executions.id` | Owning execution (CASCADE DELETE) |
| `level` | `VARCHAR(5)` | `INFO`, `WARN`, `ERROR` |
| `message` | `TEXT` | Log message |
| `logged_at` | `TIMESTAMPTZ` | When the entry was logged |

Index: `idx_job_logs_execution` on `(execution_id, logged_at)`.

### `job_checkpoints`

Named key-value checkpoints for idempotent re-execution.

| Column | Type | Description |
|--------|------|-------------|
| `execution_id` | `UUID FK → job_executions.id` | Owning execution (CASCADE DELETE) |
| `key` | `VARCHAR(255)` | Checkpoint name |
| `value` | `JSONB NOT NULL` | Checkpoint data |
| `updated_at` | `TIMESTAMPTZ` | Last update time |

Primary key: `(execution_id, key)`. Upsert on conflict updates `value` and `updated_at`.

### `job_schedules`

Cron schedule definitions written by `JobRepository.saveSchedule()` at startup. Code is the source of truth — rows are overwritten on every deploy via UPSERT.

| Column | Type | Description |
|--------|------|-------------|
| `job_id` | `VARCHAR(255) PK` | Unique job identifier |
| `cron_expression` | `VARCHAR(100)` | 6-field cron expression string |
| `handler` | `VARCHAR(255)` | Event bus address of the handler |
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

Configure `DbFlywayModule` to include the job migration location alongside the application's own migrations:

```json
{
  "flyway": {
    "locations": ["classpath:db/migration", "classpath:db/migration/job"]
  }
}
```

If using a dedicated DDL user for migrations, set `flyway.ddlUsername` and `flyway.ddlPassword` in `FlywayConfig`.

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

- **job-core** — `JobRepository` SPI, `JobExecution`, `JobExecutionStateTransitionListener`, `JobState`, `JobType`, `ProgressSnapshot`, `Checkpoint`, `LogEntry`, `CronJobSchedule`
- **db-postgresql** — `PgSqlRepository`, `PgDbExceptionMapper`, `Pool`
- **db-flyway** — `FlywayMigrationRunner` (required to apply the bundled migration)
