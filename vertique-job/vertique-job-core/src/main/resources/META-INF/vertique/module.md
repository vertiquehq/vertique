<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Job Core Module

> **Status:** Implemented
> **Package:** `dev.vertique.job`
> **Artifact:** `vertique-job-core`
> **Depends on:** core, services

Shared infrastructure for all job scheduling workloads (cron, delayed, batch). Defines the job state machine, `JobContext` API for handler-side progress reporting, structured logging, metadata, step deduplication, and cooperative cancellation. Declares the `JobInterceptor` SPI for cross-cutting concerns and the `JobRepository` SPI for persistence. `JobCompletionHandler` provides shared completion logic used by cron and delayed-job triggers. `JobCoordinator` manages node heartbeats, dead-node detection, orphan recovery, and cooperative cancellation across a cluster.

---

## Package Layout

| Package | Contents |
|---------|----------|
| `dev.vertique.job` | `JobContext`, `DefaultJobContext`, `JobDispatchContext`, `JobInterceptor`, `JobInterceptors`, `JobState`, `JobType`, `JobRepository`, `JobExecution`, `JobExecutionStateTransitionEvent`, `JobExecutionStateTransitionListener`, `JobCompletionHandler`, `JobCoordinator`, `JobCoordinatorConfig`, `CronJobSchedule`, `JobLogger`, `DefaultJobLogger`, `ProgressReporter`, `DefaultProgressReporter`, `ProgressSnapshot`, `Checkpoint`, `LogEntry` |
| `dev.vertique.job.dagger` | `JobModule`, `JobCoordinatorModule` |

---

## Key Classes

### `JobExecution`

Immutable record representing a job execution. A delayed job reuses one `JobExecution` row across its retry attempts (the `id` is stable; `attemptNumber` increments), while each cron fire creates a distinct execution.

| Field | Type | Description |
|-------|------|-------------|
| `id` | `UUID` | Unique execution identifier; stable across retries for a delayed job (`attemptNumber` distinguishes attempts), distinct per cron fire |
| `jobId` | `String` | Logical job identifier (stable across retries) |
| `jobType` | `JobType` | Scheduling mechanism (`CRON`, `DELAYED`, `BATCH`) |
| `handler` | `String` | Event bus address of the handler |
| `queue` | `String` | Logical queue this execution belongs to |
| `state` | `JobState` | Current lifecycle state |
| `attemptNumber` | `int` | Zero-based attempt counter (0 = first attempt) |
| `maxAttempts` | `int` | Maximum allowed attempts (inclusive) |
| `payload` | `Object` | Typed job data passed to the handler; stored as JSONB in DB |
| `priority` | `int` | Claiming order — higher values claimed first; default 0 |
| `lockedBy` | `String` | Node identity of the worker currently processing, or `null` |
| `scheduledAt` | `Instant` | When this execution is eligible for claiming |
| `enqueuedAt` | `Instant` | When placed on the work queue, or `null` |
| `startedAt` | `Instant` | When processing began, or `null` |
| `completedAt` | `Instant` | When processing finished, or `null` |
| `errorMessage` | `String` | Human-readable error message on failure, or `null` |
| `errorType` | `String` | Exception class name on failure, or `null` |
| `progress` | `ProgressSnapshot` | Latest progress snapshot (never null; defaults to `EMPTY`) |
| `parameters` | `Map<String,Object>` | Static parameters at scheduling time (immutable) |
| `attributes` | `Map<String,Object>` | Runtime attributes set by interceptors or handlers (immutable) |

Copy-on-write methods: `withState()`, `withProgress()`, `withPayload()`, `withLockedBy()`, `withError()`, `withStarted()`, `withCompleted()`.

### `JobState`

State machine for job executions. There is no separate `SCHEDULED` state — future-scheduled executions use `ENQUEUED` with a future `scheduled_at` value, and claim queries filter by `scheduled_at <= NOW()`.

| State | Description | Valid transitions |
|-------|-------------|-------------------|
| `ENQUEUED` | On the work queue, awaiting a worker. May have future `scheduled_at`. | → `PROCESSING` |
| `PROCESSING` | Actively being executed | → `SUCCEEDED`, `FAILED`, `CANCELLED`, `ABANDONED`, `DEAD_LETTER` (exhausted failure / timeout) |
| `SUCCEEDED` | Completed successfully | (terminal) |
| `FAILED` | Error; may retry if attempts remain | → `ENQUEUED`, `DEAD_LETTER` |
| `CANCELLED` | Cancelled by coordinator | (terminal) |
| `ABANDONED` | Heartbeat expired; reclaimed by coordinator | → `ENQUEUED`, `DEAD_LETTER`, `SUCCEEDED`, `FAILED` |
| `DEAD_LETTER` | All retry attempts exhausted | (terminal) |

`JobState.canTransitionTo(target)` validates transitions at runtime.

### `JobType`

Identifies the scheduling mechanism:

| Constant | Description |
|----------|-------------|
| `CRON` | Timer-based recurring execution |
| `DELAYED` | DB-backed deferred execution |
| `BATCH` | Chunk-oriented batch processing |

### `JobContext`

Runtime context for a single job execution. Annotated with `@DispatchContextValue` (and a `ContextValue`) so `ServiceMethodInvoker` automatically injects it into handler methods that declare it as a parameter.

```java
@CronJob(id = "daily-report", cron = "0 0 8 * * *")
public Future<Void> generateDailyReport(ReportRequest request, JobContext ctx) {
    ctx.logger().info("Starting report generation");
    ctx.progress().setTotal(100);
    return ctx.runStepOnce("export-csv", () -> reportService.exportCsv(request))
        .compose(file -> {
            ctx.progress().incrementSucceeded(50);
            return ctx.runStepOnce("send-email", () -> emailService.send(file));
        })
        .map(v -> {
            ctx.progress().incrementSucceeded(50);
            if (ctx.isCancelled()) log.info("Cancellation requested");
            return null;
        });
}
```

| Method | Description |
|--------|-------------|
| `jobId()` | Logical job identifier (stable across retries) |
| `executionId()` | Unique execution identifier (stable across retries for a delayed job; distinct per cron fire) |
| `attemptNumber()` | Zero-based attempt counter (0 = first attempt) |
| `jobType()` | Scheduling mechanism (`CRON`, `DELAYED`, `BATCH`) |
| `progress()` | Returns the `ProgressReporter` for this execution |
| `logger()` | Returns the buffered `JobLogger` for this execution |
| `isCancelled()` | Returns `true` if cancellation has been signalled |
| `setCancelled(boolean)` | Sets the cancellation flag |
| `saveMetadata(key, value)` | Stores arbitrary named metadata; `null` value removes the key |
| `getMetadata(key, type)` | Retrieves stored metadata cast to the given type |
| `hasCompletedStep(name)` | Returns `true` if the named step already completed |
| `runStepOnce(name, task)` | Executes a task exactly once; skips and returns cached result if already completed |
| `checkpoint(key, value)` | Saves a named checkpoint value (survives restarts when backed by `JobRepository`) |
| `lastCheckpoint(key, type)` | Retrieves the last saved checkpoint |

### `DefaultJobContext`

In-memory implementation of `JobContext`. All state is held in `ConcurrentHashMap` structures for thread safety. Cancellation uses a `volatile boolean` flag.

`runStepOnce()` uses private sentinels to distinguish three states:
- `IN_PROGRESS` — a concurrent call returns `null` immediately without waiting
- `COMPLETED_NULL` — step completed with null result
- Stored value — step completed with a non-null result

Failed tasks remove the sentinel so the step can be retried on the next call. The `checkpointMap()` package-visible accessor returns an immutable copy for flushing to persistent storage.

### `JobDispatchContext`

Immutable record carrying scheduling metadata known at dispatch time: `jobId`, `executionId`, `jobType`, `attemptNumber`, `maxAttempts`, `queue`, `scheduledAt`, `startedAt`, `parameters`, `attributes`. Annotated with `@DispatchContextValue` (and a `ContextValue`) for automatic injection alongside `JobContext`.

Supports copy-on-write via `withAttribute(String, Object)`. Factory method `fromExecution(execution, executionId, startedAt)` builds a dispatch context from a `JobExecution` record.

```java
public Future<Void> process(MyPayload payload, JobDispatchContext dispatchCtx) {
    if (dispatchCtx.attemptNumber() > 0) {
        log.warn("Retrying attempt {}", dispatchCtx.attemptNumber());
    }
    return doWork(payload);
}
```

`toMdcContext()` returns a map of MDC keys:

| Key | Value |
|-----|-------|
| `job.id` | Logical job identifier |
| `job.type` | `JobType` name (e.g., `"CRON"`) |
| `job.executionId` | Execution UUID (correlation ID) |
| `job.queue` | Queue name |
| `job.attempt` | Zero-based attempt number |

### `JobCompletionHandler`

Reusable utility that handles job completion outcomes: persists terminal state, schedules retries with backoff, and transitions exhausted executions to dead-letter. Used by job triggers (cron, delayed-job) in their per-execution reply handlers.

If no `JobRepository` is provided (`null`), all operations are no-ops — supports in-memory-only mode.

```java
// In a reply consumer:
handler.handleCompletion(execution, result, BackoffStrategy.linear(30_000, 3_600_000))
       .onFailure(err -> log.warn("Completion handling failed", err));
```

| Method | Description |
|--------|-------------|
| `handleCompletion(execution, result, backoffStrategy)` | On success: persists `SUCCEEDED`. On retryable failure: atomically records the `FAILED` attempt and re-enqueues via `failAndScheduleRetry` (one transaction). On exhausted: persists `DEAD_LETTER`. |

**Retry-persistence invariant:** A retryable failure records the `FAILED` attempt (durable and observable to `JobExecutionStateTransitionListener`s) and re-enqueues the row in a **single transaction** via `failAndScheduleRetry` — either both writes commit or neither does, so a crash can never strand a retryable execution in an intermediate `FAILED` state. A no-op (already-terminal execution) returns `Optional.empty()` and schedules nothing.

### `JobCoordinator`

Coordinates job lifecycle across a cluster: node heartbeat emission, dead-node detection, orphaned execution recovery, and cooperative cancellation.

**Three detection layers:**

1. **Node heartbeat** — writes to `job_server_heartbeats` every `nodeHeartbeatIntervalMs` (default 10 s). First write is issued immediately on `start()`.
2. **Dead-node scan** — every `scanIntervalMs` (default 30 s) queries for servers with an expired heartbeat (`> nodeHeartbeatTimeoutMs`, default 60 s) and recovers their orphaned executions.
3. **Cooperative cancellation** — publishes on `job.cancel.<executionId>` event bus address to signal in-process handlers; marks the execution as `CANCELLED` in the repository.

**Recovery behaviour for dead nodes:**
- `DELAYED` jobs with remaining attempts are recovered via `abandonAndScheduleRetry` — one transaction that records the `ABANDONED` interruption and re-enqueues (no crash-strand window).
- `DELAYED` jobs with exhausted attempts are dead-lettered (`completeExecution(DEAD_LETTER)`).
- `CRON` jobs are marked `ABANDONED` (`completeExecution`) — the next scheduled fire creates a fresh execution.
- The dead server's heartbeat row is removed **only after every per-execution recovery write commits**; if any fails, removal is skipped so the next scan re-discovers the server and retries (removing it would orphan a still-`PROCESSING` execution permanently).

**Late completion race:** A handler may reply after the coordinator marks the execution `ABANDONED`. `completeExecution` accepts both `PROCESSING` and `ABANDONED` as current state, and `ABANDONED` allows `SUCCEEDED`/`FAILED` as targets, so late completions correctly overwrite the coordinator's mark.

| Method | Description |
|--------|-------------|
| `start()` | Issues initial heartbeat and starts periodic timers |
| `stop()` | Cancels timers and removes this server's heartbeat row (clean shutdown) |
| `cancelExecution(UUID)` | Publishes cancel signal and marks execution `CANCELLED` in repository |
| `serverId()` | Returns this coordinator's node identity (`hostname-<8-char-suffix>`) |

### `JobCoordinatorConfig`

Deserialized from `job.coordinator` in the application config. All fields have `@Builder.Default` values for unit test construction without config.

| Field | Default | Description |
|-------|---------|-------------|
| `enabled` | `true` | If `false`, no timers are started |
| `nodeHeartbeatIntervalMs` | `10000` | How often this node writes a heartbeat (ms) |
| `nodeHeartbeatTimeoutMs` | `60000` | How long before a heartbeat is considered expired (ms) |
| `scanIntervalMs` | `30000` | How often to scan for dead nodes (ms) |
| `executionTimeoutMs` | `120000` | Per-execution consumer timeout (ms); 0 = disabled |
| `progressFlushIntervalMs` | `10000` | How often to flush progress snapshots to DB (ms); 0 = disabled |

```yaml
job:
  coordinator:
    enabled: true
    nodeHeartbeatIntervalMs: 10000
    nodeHeartbeatTimeoutMs: 60000
    scanIntervalMs: 30000
    executionTimeoutMs: 120000
    progressFlushIntervalMs: 10000
```

### `JobRepository`

SPI for persisting job execution state. Bind a concrete implementation (`JobPostgresqlModule`) to enable persistent tracking, heartbeat-based crash detection, and retry coordination. The cron module operates without it (in-memory only when using `CronModule`).

**Execution lifecycle methods:**

| Method | Description |
|--------|-------------|
| `save(execution)` | Persists a new execution record; returns the saved ID |
| `updateState(id, fromState, toState)` | Atomic optimistic-concurrency state transition |
| `claimNextJob(queue, batchSize)` | Claims up to `batchSize` executions atomically (`ENQUEUED` → `PROCESSING`); filters `scheduled_at <= NOW()` |
| `heartbeat(id, progress)` | Updates heartbeat timestamp and progress snapshot |
| `findStale(timeout)` | Finds `PROCESSING` executions with expired heartbeat |
| `findById(id)` | Looks up an execution by ID |
| `saveLogs(id, entries)` | Appends log entries to the persistent job log |
| `saveCheckpoint(id, key, value)` | Upserts a named checkpoint value |
| `loadCheckpoint(id, key)` | Loads the latest checkpoint for a key |
| `completeExecution(id, newState, errorMessage, errorType, progress)` | Atomically transitions to `newState` (SUCCEEDED, FAILED, DEAD_LETTER, CANCELLED, or ABANDONED) with final error/progress; accepts `PROCESSING` or `ABANDONED` as current state. Returns `Future<Optional<JobExecution>>` — the persisted execution on a real transition, or `Optional.empty()` when no row changed (idempotent no-op — execution already terminal). |
| `scheduleRetry(id, nextScheduledAt, newAttempt)` | Transitions `FAILED`/`ABANDONED` → `ENQUEUED` with new schedule time and incremented attempt |
| `failAndScheduleRetry(id, errorMessage, errorType, progress, nextScheduledAt, nextAttempt)` | Atomically (one transaction) records the `FAILED` attempt and re-enqueues; returns the `FAILED` snapshot for audit (committed row is `ENQUEUED`), or `Optional.empty()` on a no-op |
| `abandonAndScheduleRetry(id, errorMessage, errorType, progress, nextScheduledAt, nextAttempt)` | The `ABANDONED` counterpart (timeout / dead-node recovery): atomically records the `ABANDONED` interruption and re-enqueues; returns the `ABANDONED` snapshot, or `Optional.empty()` on a no-op |
| `tryInsert(execution)` | INSERT ON CONFLICT DO NOTHING for SINGLE_INSTANCE leader election; returns `Optional.empty()` on conflict |
| `saveSchedule(schedule)` | Upserts a cron job schedule definition for dashboard visibility |
| `updateScheduleFireTimes(jobId, lastFiredAt, nextFireAt)` | Updates `last_fired_at` / `next_fire_at` after each cron fire |
| `findSchedule(jobId)` | Loads a cron schedule by job ID (for misfire detection) |

**Node heartbeat methods:**

| Method | Description |
|--------|-------------|
| `serverHeartbeat(serverId)` | UPSERT this node's heartbeat row |
| `findDeadServers(timeout)` | Returns server IDs with an expired heartbeat |
| `removeServer(serverId)` | Deletes a dead server's heartbeat row after recovery |
| `findByLockedBy(serverId)` | Finds `PROCESSING` executions owned by the given server |

### `CronJobSchedule`

Immutable record persisted in `job_schedules` for dashboard visibility. Written by `JobRepository.saveSchedule()` at startup using an UPSERT.

| Field | Type | Description |
|-------|------|-------------|
| `jobId` | `String` | Unique job identifier |
| `cronExpression` | `String` | Cron expression string |
| `handler` | `String` | Event bus address of the handler |
| `executionMode` | `String` | `"EVERY_INSTANCE"` or `"SINGLE_INSTANCE"` |
| `timezone` | `String` | IANA zone ID |
| `enabled` | `boolean` | Whether the schedule is active |
| `overlapPolicy` | `String` | `"SKIP"` or `"QUEUE_ONE"` |
| `maxAttempts` | `int` | Max attempts per execution |
| `tracked` | `boolean` | Whether executions are persisted |
| `lastFiredAt` | `Instant` | Last fire instant, or `null` |
| `nextFireAt` | `Instant` | Next computed fire instant, or `null` |

### `ProgressReporter`

Progress tracking for a single execution. Thread-safe.

| Method | Description |
|--------|-------------|
| `setTotal(long)` | Total units of work |
| `incrementSucceeded()` / `incrementSucceeded(long)` | Mark units succeeded |
| `incrementFailed()` / `incrementFailed(long)` | Mark units failed |
| `setStatus(String)` | Free-text status message |
| `percentage()` | Computed 0–100 (0 when total is zero) |
| `snapshot()` | Returns an immutable `ProgressSnapshot` |

### `ProgressSnapshot`

Immutable record: `total`, `succeeded`, `failed`, `status`. `ProgressSnapshot.EMPTY` is the initial state. `percentage()` returns `(succeeded + failed) * 100 / total`.

### `JobLogger`

Buffered in-memory logger for structured per-execution log entries. Thread-safe (uses `CopyOnWriteArrayList` internally). Entries are flushed to the repository after job completion.

```java
ctx.logger().info("Processing batch chunk 3/10");
ctx.logger().warn("Skipping row: missing field 'email'");
ctx.logger().error("Failed to connect to external API");
```

### `LogEntry`

Record: `level` (string: `"INFO"`, `"WARN"`, `"ERROR"`), `message`, `loggedAt` (Instant).

### `Checkpoint`

Record: `key`, `value` (any object; serialization is repository-specific), `updatedAt` (Instant).

### `JobExecutionStateTransitionEvent`

Curated, safe-by-type record delivered to `JobExecutionStateTransitionListener`s after a persisted state transition.

This event carries only low-cardinality identifiers, the new state, attempt counts, timing, the exception class name, and the persisted `DurableMetadata` context document. It deliberately omits the job payload, the raw error message, and the `parameters` / `attributes` maps (safe-by-type rule from PRD-AUD-002 §10.3). `metadata` is the durable context carrier (correlation/localization namespaces only — no payload or credentials).

| Field | Type | Description |
|-------|------|-------------|
| `executionId` | `UUID` | Unique execution identifier |
| `jobId` | `String` | Logical job identifier |
| `jobType` | `JobType` | Scheduling mechanism |
| `queue` | `String` | Queue the execution ran on |
| `newState` | `JobState` | State the execution transitioned to (`ABANDONED` is non-terminal) |
| `attemptNumber` | `int` | Zero-based attempt number |
| `maxAttempts` | `int` | Configured maximum attempts |
| `errorType` | `String` | Fully-qualified exception class name, or `null` on success |
| `scheduledAt` | `Instant` | When the execution was scheduled |
| `enqueuedAt` | `Instant` | When the execution was enqueued |
| `startedAt` | `Instant` | When processing began, or `null` if the job never ran |
| `completedAt` | `Instant` | When the execution reached `newState` |
| `metadata` | `DurableMetadata` | Persisted durable context document (never `null`) |

---

## Extension Points

### `JobExecutionStateTransitionListener`

SPI for observing **persisted** job state transitions across all completion paths. Fired by `NotifyingJobRepository` (in `vertique-job-postgresql`) after a confirmed persisted transition — `completeExecution`, `failAndScheduleRetry`, or `abandonAndScheduleRetry` — i.e. after the database row has actually transitioned, on every path including failure-retry, timeout, dead-node recovery, and cooperative cancellation.

Unlike `JobInterceptor.onComplete` (which fires pre-persistence on the handler-reply path only), this observer sees the durable outcome on all paths, including `CANCELLED` and `ABANDONED`.

**Implementations must be non-blocking.** The call runs synchronously on the job-completion thread, which may be a Vert.x event-loop, timer, or event-bus callback thread. Submit any async work fire-and-forget; do not chain on it. Exceptions thrown by one listener are caught and logged; subsequent listeners still run.

Register via Dagger multibinding (`@IntoSet`) against `Set<JobExecutionStateTransitionListener>`. `JobModule` declares the `@Multibinds` empty-set binding so the set resolves (empty) in standalone setups with no listeners contributed.

```java
public interface JobExecutionStateTransitionListener {
    void onStateTransition(JobExecutionStateTransitionEvent event);
}
```

**Dagger wiring:**

```java
@Provides @IntoSet
static JobExecutionStateTransitionListener auditJobsListener(AuditJobsStateTransitionListener l) {
    return l;
}
```

### `JobInterceptor`

SPI for cross-cutting logic applied around job dispatch. Register via Dagger multibinding.

`JobInterceptor extends OrderedExtension`. Interceptors are sorted by `OrderedExtension.comparator()` — phase ascending, then priority ascending, then `orderKey` (default FQCN) as a stable tie-break. Lower priority values run first.

```java
public interface JobInterceptor extends OrderedExtension {
    default void onDispatch(JobDispatchContext ctx) {}
    default void onComplete(JobDispatchContext ctx, Result<?> result,
                            Instant startTime, Instant endTime) {}
}
```

`onDispatch` fires before the event bus send. `onComplete` fires after the reply is received. Both are synchronous observers — exceptions are swallowed by `JobInterceptors` utility.

Common use cases: distributed tracing, metrics, audit logging.

**Example — metrics interceptor:**

```java
public class MetricsJobInterceptor implements JobInterceptor {

    private final MeterRegistry registry;

    @Inject
    MetricsJobInterceptor(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onComplete(JobDispatchContext ctx, Result<?> result,
                           Instant startTime, Instant endTime) {
        Duration duration = Duration.between(startTime, endTime);
        registry.timer("job.duration", "job", ctx.jobId(), "type", ctx.jobType().name())
                .record(duration);
    }
}
```

**Dagger wiring:**

```java
@Provides @IntoSet
static JobInterceptor metricsInterceptor(MetricsJobInterceptor interceptor) {
    return interceptor;
}
```

---

## Dagger Wiring

### `JobModule`

Declares the empty `Set<JobInterceptor>` and `Set<JobExecutionStateTransitionListener>` multibindings. Include whenever any job scheduling module is used.

```java
@Component(modules = {VertxModule.class, DispatchModule.class, JobModule.class})
public interface AppComponent { ... }
```

### `JobCoordinatorModule`

Provides `JobCoordinatorConfig` (from `job.coordinator` config) and `JobCoordinator` (singleton). Requires a `JobRepository` binding. Automatically included by `CronPersistenceModule` and `DelayedJobModule`.

```java
@Component(modules = {VertxModule.class, JobPostgresqlModule.class, JobCoordinatorModule.class, ...})
public interface AppComponent { ... }
```

---

## Dependencies

- **core** — `DispatchContextValue`, `DispatchEnvelope`, `Result`, `VertiqueException` hierarchy, `BackoffStrategy`
- **services** — `ServiceMethodInvoker` (injects `JobContext` and `JobDispatchContext` into handler methods via `@DispatchContextValue` scanning)

---

## Related ADRs

- ADR-0080: Job State-Transition Audit Notification via a Repository Decorator — establishes `JobExecutionStateTransitionListener` as the post-persistence producer SPI, changes `completeExecution` to `Future<Optional<JobExecution>>`, and adds the atomic `failAndScheduleRetry` operation so a retryable failure records the `FAILED` attempt and re-enqueues in one transaction.
- ADR-0084: Framework Extension-Ordering Contract — establishes `OrderedExtension` and `ExtensionPhase` as the canonical ordering contract for framework extensions.
- ADR-0085: OrderedExtension Rolled Out Across Sorted Behavioral SPIs — `JobInterceptor` now follows the framework OrderedExtension ordering contract (phase → priority → orderKey).
