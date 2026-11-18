<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Job Core Module

> **Status:** Stable
> **Package:** `dev.vertique.job`
> **Artifact:** `vertique-job-core`
> **Depends on:** core, deploy

`vertique-job-core` defines the vocabulary every Vertique scheduling module shares: the job execution
record and its state machine, the `JobContext` handler-side API for progress, structured logging,
step deduplication and cooperative cancellation, the `JobRepository` persistence SPI, and the two
observation SPIs. It also ships `JobCompletionHandler`, which turns a handler reply into a persisted
outcome, and `JobCoordinator`, which runs node heartbeats, dead-node detection, orphan recovery and
cooperative cancellation across a cluster.

This module schedules nothing by itself — it owns no trigger and no queue. Install
`dev.vertique:vertique-job-cron` for recurring work or `dev.vertique:vertique-job-delayed` for
durable one-shot work, plus `dev.vertique:vertique-job-postgresql` for the persistence that makes
retries, heartbeats and dead-lettering durable.

---

## When To Use It

You rarely install this module directly — both scheduling modules depend on it. Reach for its types
when you write code that must be *aware* of jobs: a handler that reports progress or honours
cancellation (`JobContext`), an interceptor that traces or measures dispatch (`JobInterceptor`), an
audit or notification consumer that must see durable outcomes
(`JobExecutionStateTransitionListener`), or a persistence adapter for a store other than PostgreSQL
(`JobRepository`).

---

## Core Concepts

### One logical job, one or many executions

`jobId` is the **logical** identifier and is stable across retries; `JobExecution.id()` is the
**execution** identifier. A delayed job reuses one execution row across all its attempts — `id` stays
put and `attemptNumber` increments, so a specific attempt is `(id, attemptNumber)`. A cron fire
creates a distinct row each time. `attemptNumber` is zero-based and `maxAttempts` is inclusive, so
attempts remain while `attemptNumber + 1 < maxAttempts`.

### Scope

Every `JobExecution` carries a `scopeKey`, the application's partition label for the work (a tenant,
project or workspace). The enqueue call selects it explicitly (`DelayedJobClientFactory.client(...).scope(...)` or
`DelayedJob.scopeKey` in `vertique-job-delayed`); an execution that never selected one reads `default`
(`ScopeKey.DEFAULT.value()`), and `scopeKey()` is never `null`. The ambient `ScopeContext` does not
choose it. The scope is a label, not authorization: claiming, retry, retention and cron
de-duplication ignore it, so a worker claims jobs of every scope from its queue.

A running handler reads the row's scope from `JobContext.scopeKey()` or
`JobDispatchContext.scopeKey()`, and a state-transition listener reads it from
`JobExecutionStateTransitionEvent.scopeKey()`. All three are never `null`; a `JobContext` or
dispatch context built without a scope reports `default`. The framework does not bind a
`ScopeContext` from the row, so the row scope and any ambient `ScopeContext` the handler sees are two
independent values: the ambient one is whatever durable context was captured at enqueue, and it may
differ from the row or be absent.

A handler that partitions data by scope:

- **Choose one source** for the scope, the row (`scopeKey()`) or the ambient `ScopeContext`, and use
  it everywhere in the handler.
- **Compare the other** when both are present, and fail the execution on a mismatch rather than
  pick one.
- **Authorize the access itself.** The scope is a label: it is not checked at enqueue, by execution
  id, or under a service principal, so a handler that reads or writes tenant data must establish that
  the work may touch that scope.

A cron fire has no enqueue-time scope, so its `JobContext`, dispatch context and execution row all
report `default`.

### The state machine

There is no `SCHEDULED` state. Future-dated work is `ENQUEUED` with a future `scheduledAt`, and claim
queries filter by `scheduled_at <= NOW()`, so a job becomes claimable exactly when its time arrives
without a transition timer.

| State | Meaning | Allowed next states |
|---|---|---|
| `ENQUEUED` | On the queue, awaiting a worker; may be future-dated | `PROCESSING` |
| `PROCESSING` | Claimed and running | `SUCCEEDED`, `FAILED`, `CANCELLED`, `ABANDONED`, `DEAD_LETTER` |
| `SUCCEEDED` | Completed successfully | *(terminal)* |
| `FAILED` | Attempt failed; may retry | `ENQUEUED`, `DEAD_LETTER` |
| `CANCELLED` | Cancelled through the coordinator | *(terminal)* |
| `ABANDONED` | Interrupted — timeout or dead-node reclaim | `ENQUEUED`, `DEAD_LETTER`, `SUCCEEDED`, `FAILED` |
| `DEAD_LETTER` | Attempts exhausted; parked | *(terminal)* |

`JobState.canTransitionTo(JobState)` validates a proposed transition. `ABANDONED` is deliberately
**not** terminal and deliberately allows `SUCCEEDED`/`FAILED`: a handler can reply after the
coordinator has reclaimed its execution, and that late reply must win.

### What a handler receives

`JobContext` and `JobDispatchContext` are annotated `@DispatchContextValue` and implement
`ContextValue`, so declaring either as a handler-method parameter is enough — the dispatch layer
injects them. `JobContext` is mutable per-execution state; `JobDispatchContext` is an immutable
snapshot of the scheduling metadata (`jobId`, `executionId`, `jobType`, `attemptNumber`,
`maxAttempts`, `queue`, `scheduledAt`, `startedAt`, `parameters`, `attributes`, `scopeKey`), with
`withAttribute(String, Object)` returning a copy and `toMdcContext()` rendering the standard logging
keys `job.id`, `job.type`, `job.executionId`, `job.queue`, `job.attempt` (the scope is not a logging
key).

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
                if (ctx.isCancelled()) {
                    log.info("Cancellation requested");
                }
                return null;
            });
}
```

---

## Key Classes

### JobContext

Per-execution runtime API; thread-safe.

| Method | Description |
|---|---|
| `jobId()` / `executionId()` / `attemptNumber()` / `jobType()` | Execution identity |
| `scopeKey()` | The row's scope label, never `null`; `default` when the execution has none. A `default` method, so existing implementations keep compiling |
| `progress()` / `logger()` | The execution's `ProgressReporter` and buffered `JobLogger` (never `null`) |
| `isCancelled()` / `setCancelled(boolean)` | Cooperative cancellation flag |
| `saveMetadata(String, Object)` / `getMetadata(String, Class<T>)` | Named values; a `null` value removes the key |
| `hasCompletedStep(String)` / `runStepOnce(String, Supplier<Future<T>>)` | Step deduplication |

`runStepOnce` gives lightweight idempotency inside one execution without a transaction:

- A completed step replays its recorded result on later calls; a step whose result was `null` replays
  as `null`.
- A **concurrent** second call for the same step returns a succeeded future of `null` immediately —
  it neither waits for nor receives the first call's result. Do not fan out concurrent calls to one
  step name.
- A failed task — failed future *or* a supplier that throws synchronously — clears the marker, so the
  step runs again on the next call.

### Progress and logging

`ProgressReporter` (`setTotal`, `incrementSucceeded()`/`(long)`, `incrementFailed()`/`(long)`,
`setStatus`, `percentage`, `snapshot`) is thread-safe and always optional. `snapshot()` returns the
immutable `ProgressSnapshot(total, succeeded, failed, status)`, whose `percentage()` is
`(succeeded + failed) * 100 / total` and `0` when `total` is `0`; `ProgressSnapshot.EMPTY` is the
initial value. `JobLogger` buffers `LogEntry(level, message, loggedAt)` records with `level` one of
`"INFO"`, `"WARN"`, `"ERROR"`; call it as `ctx.logger().warn("Skipping row: missing field 'email'")`.
The scheduling module periodically drains buffered entries to `job_logs` through a `JobLogFlusher`
(below), so `logger().entries()` reflects only what is still buffered, not the whole execution's
output.

The default `JobLogger` normalizes every message as it is buffered, so no single entry can make the
persisting write fail: a `null` message is stored as `<null>`, C0 control characters are stripped
(`\n`, `\r` and `\t` are kept, so multi-line output survives), and a message longer than 8192
characters is truncated with a `…[truncated N chars]` marker. Pass unvalidated job-payload text to
the logger freely — a `NUL` inside it cannot poison the execution's log writes.

### JobLogFlusher

Drains one execution's buffered `JobLogger` entries into a `JobRepository`; one instance per
execution. `flush()` claims up to 500 buffered entries as one batch and persists them via
`JobRepository.saveLogs(UUID, List<LogEntry>)` — a successful write acknowledges the batch, a failed
write returns it to the front of the buffer so the next `flush()` retries it ahead of newer entries.
Anything beyond the 500-entry cap stays buffered for the following `flush()`.

`flush()` always returns a succeeded future: a persistence failure is logged as a warning and never
fails the job whose logs these are. That holds for every way a `JobRepository` implementation can
misbehave — a synchronous throw, a `null` return, or a write that never settles; the write itself is
bounded at 5 seconds. A write that exceeds that bound but commits afterwards leaves rows the retry
writes again, because `job_logs` has no natural key to deduplicate on; duplicate log rows are the
accepted cost of never stalling the flush loop.

`drain()` is the variant for a site that *ends* the execution. The claim is single-flight, so a
`flush()` issued while an earlier write is still outstanding claims nothing and returns an
already-succeeded future — harmless on a periodic tick, because the next tick picks the work up, but
lossy at an ending site, which has just cancelled that tick. `drain()` therefore awaits the
outstanding write and re-flushes while entries remain, which also retries a nacked batch that no
later tick would have retried. It is bounded at **4 rounds**, and a round whose write failed counts
the same as one that succeeded: a repository that is down costs a fixed number of fast rounds, and a
still-running handler that keeps appending cannot hold the ending site open. Entries left when the
rounds are spent are lost, and the loss is logged at WARN naming the execution and the entry count.
Like `flush()`, `drain()` always returns a succeeded future.

```java
JobLogFlusher flusher = new JobLogFlusher(repository, executionId, ctx);
flusher.flush(); // Future<Void>, always succeeds — periodic tick
flusher.drain(); // Future<Void>, always succeeds — execution-ending site
```

Construction with a `null` repository or a `null` execution id yields a genuine no-op flusher that
never touches the repository — the case for an execution with no persisted row to reference. The
scheduling modules (`vertique-job-cron`, `vertique-job-delayed`) own when `flush()` and `drain()` are
called; application code does not construct or call a `JobLogFlusher` directly.

### JobCompletionHandler

Turns a handler reply into a persisted outcome. Scheduling modules call it from their reply
consumers; applications rarely construct one. `handleCompletion(JobExecution, Result<?>,
BackoffStrategy)` returns `Future<Void>` and persists `SUCCEEDED` on success or on a `null` result;
on failure it calls `failAndScheduleRetry` at `now + backoff.delay(nextAttempt)` while attempts
remain, and `DEAD_LETTER` once they are exhausted, carrying the error message and exception class
name. Constructed with a `null` repository, every path is a no-op — the in-memory-only mode cron uses
without persistence.

```java
handler.handleCompletion(
        execution, result, retryCount -> Math.min(30_000L * (retryCount + 1L), 3_600_000L))
        .onFailure(err -> log.warn("Completion handling failed", err));
```

### JobCoordinator

Cluster-level lifecycle owner, constructed as `new JobCoordinator(vertx, repository, config, serverId)`.
`serverId` is this node's identity: the heartbeat row's `server_id`, which must equal the `locked_by`
value the node's repository stamps on its claims, because recovery finds a dead node's executions by
that value. `JobCoordinatorModule` passes the `@JobNodeId` binding, which `JobPostgresqlModule` and
`CronModule` and `CronPersistenceModule` also use.

**Breaking change.** The `new JobCoordinator(vertx, repository, config)` constructor, which generated
an identity of its own that no repository shared, is removed without a deprecation cycle; a
coordinator built that way heartbeated but could not recover a dead node's claims. Pass the node's
`@JobNodeId` value as `serverId`. `PgJobRepository` and `CronScheduler` lose their identity-generating
constructors the same way (see `vertique-job-postgresql` and `vertique-job-cron`).

| Method | Description |
|---|---|
| `start()` | Writes an immediate heartbeat, then starts the heartbeat and scan timers; a no-op when `enabled()` is `false` or when already started |
| `stop()` | Cancels both timers and, when this node holds no `PROCESSING` claims, removes its heartbeat row; a no-op when not started; succeeds even if a repository call fails |
| `cancelExecution(UUID)` | Publishes on `job.cancel.<executionId>` and marks the execution `CANCELLED` |
| `serverId()` | This node's identity, `<hostname>-<8-char suffix>` |

It heartbeats every `nodeHeartbeatIntervalMs`, scans every `scanIntervalMs` for nodes whose heartbeat
is older than `nodeHeartbeatTimeoutMs`, and recovers each orphan it finds: a `DELAYED` execution with
attempts remaining is atomically abandoned and re-enqueued, an exhausted one is dead-lettered, and
any other type (`CRON`) is marked `ABANDONED` so its schedule re-fires normally. Cancellation is
cooperative — the published signal sets the in-process flag, and the repository write makes the
decision durable even if the handler ignores it. The dead node's heartbeat row is deleted **only
after every per-execution recovery write has committed**; if any fails, the row stays so the next
scan rediscovers the node, because deleting it early would strand a still-`PROCESSING` orphan
permanently. For the same reason `stop()` keeps this node's own row while executions it claimed are
still `PROCESSING` (handlers still running at shutdown): the row expires and a surviving node recovers
whatever is left. `start()` completes once its initial heartbeat write has settled.

Every recovery write is **fenced by the claim**: it adds `locked_by = <dead server id>` and
`attempt = <attempt read>` (and requires the row to still be `PROCESSING`) to its predicate. When two
surviving nodes read the same orphan, another node claims it again between the scan's read and its write,
or a node that stalled past the heartbeat timeout wakes and claims its own orphan again (the same
`locked_by`, a new attempt), the later write matches no row and recovery leaves the new claim alone, so the
execution does not run twice. A fenced write that matches nothing is
a normal outcome: the coordinator skips that execution, logs at `DEBUG`, writes nothing more for it, and
still removes the dead node's heartbeat row once the other recoveries have committed.

Applications do not call `start()` or `stop()`: `JobCoordinatorModule` deploys a verticle that does
(see Module Dagger Bindings). The coordinator therefore writes a heartbeat to
`job_server_heartbeats` every `nodeHeartbeatIntervalMs` and scans every `scanIntervalMs` on every node
that installs a scheduling module with persistence; set `job.coordinator.enabled: false` to turn both
off.

### JobCoordinatorConfig

Parsed from `job.coordinator`. Every field has a default, so the section may be omitted entirely.

| Field | Default | Description |
|---|---|---|
| `enabled` | `true` | When `false`, no timers start and no background work runs |
| `nodeHeartbeatIntervalMs` | `10000` | How often this node writes its heartbeat |
| `nodeHeartbeatTimeoutMs` | `60000` | Age at which a heartbeat is treated as expired |
| `scanIntervalMs` | `30000` | Interval between dead-node scans |
| `executionTimeoutMs` | `120000` | Per-execution reply timeout; `0` disables |
| `progressFlushIntervalMs` | `10000` | Interval for flushing changed progress snapshots; `0` disables |

```yaml
job:
  coordinator:
    executionTimeoutMs: 120000
    progressFlushIntervalMs: 10000
```

The last two are read here and applied by the scheduling modules' dispatchers, so every queue shares
one timeout policy.

### Invariants & Gotchas

- **Retry persistence is all-or-nothing.** A retryable failure records the `FAILED` attempt and
  re-enqueues in one transaction; an interruption does the same for `ABANDONED`. A crash between the
  two writes cannot strand an execution in an intermediate state.
- **Terminal writes are idempotent.** `completeExecution`, `failAndScheduleRetry` and
  `abandonAndScheduleRetry` return `Optional.empty()` when no row transitioned — an already-terminal
  execution — and no listener fires for that no-op.
- **`logger()` output is durable, not a live transcript.** The default `JobContext` buffers entries
  in memory; the scheduling module drains them through a per-execution `JobLogFlusher` on the
  progress-flush tick and after every path that ends the execution, plus a bounded cutoff drain at
  shutdown. Delivery is at-least-once on a known write failure — a failed batch is retried at the
  front of the buffer, ahead of newer entries — and a flush failure never fails the job. Because
  flushed entries are removed from the buffer, `logger().entries()` returns only what is still
  buffered, not everything the execution has logged.
- **The ending-site retry is bounded at 4 rounds.** A periodic tick can retry a failed write
  indefinitely, but a site that ends the execution has cancelled that tick, so its `drain()` gets a
  fixed budget: 4 rounds, counting failed rounds. Entries a sustained write outage leaves behind when
  the budget is spent are lost, and reported at WARN.
- **Log messages are normalized, not stored verbatim.** A `null` message becomes `<null>`, C0
  control characters are dropped (tabs, newlines and carriage returns survive), and messages are
  truncated at 8192 characters. Do not use `logger()` output as a byte-exact record of a payload.
- **`getMetadata` does not type-check.** The `Class<T>` argument documents intent only; the value is
  cast unchecked, so a wrong type surfaces as `ClassCastException` at the call site.
- **`JobExecution.payload` is not copied.** The compact constructor copies `parameters` and
  `attributes` but keeps `payload` as an opaque reference — do not mutate it after construction.
- **Interceptor and listener callbacks are synchronous and must not block.** They run on the
  job-completion thread, which may be an event loop, a timer, or an event-bus callback.

---

### Framework seams

`DefaultJobContext`, `DefaultJobLogger`, `DefaultProgressReporter`, and `SchedulerMdcScope` are the
framework implementations behind the contracts above, and their Javadoc marks them INTERNAL. They
are outside this module's compatibility promise: an application receives a `JobContext` (and its
`JobLogger` and `ProgressReporter`) from the framework and never constructs one.

## Extension Points

### JobRepository

The persistence SPI. Bind `dev.vertique:vertique-job-postgresql`'s `JobPostgresqlModule` for the
supported implementation, or implement this interface to back jobs with another store. Cron runs
without it (in-memory only); delayed jobs require it. Every method returns a `Future`:

| Concern | Methods |
|---|---|
| Lifecycle | `save`, `updateState`, `claimNextJob`, `heartbeat`, `findStale`, `findById` |
| Terminal + retry | `completeExecution`, `scheduleRetry`, `failAndScheduleRetry`, `abandonAndScheduleRetry` |
| Dead-node recovery | `completeClaimedExecution`, `abandonClaimedAndScheduleRetry` |
| Leader election | `tryInsert` |
| Auxiliary records | `saveLogs` |
| Cron schedules | `saveSchedule`, `updateScheduleFireTimes`, `findSchedule` |
| Node heartbeats | `serverHeartbeat`, `findDeadServers`, `removeServer`, `findByLockedBy` |

Obligations an implementation must honour:

- `claimNextJob(queue, batchSize)` transitions `ENQUEUED` → `PROCESSING` atomically, filters
  `scheduled_at <= NOW()`, and claims in descending `priority` order (the PostgreSQL implementation
  breaks ties by ascending `scheduledAt`). Two workers must never claim the same row.
- `completeExecution(id, newState, errorMessage, errorType, progress)` accepts **both** `PROCESSING`
  and `ABANDONED` as the current state, so a late handler reply overwrites a coordinator-injected
  `ABANDONED` mark. It returns the persisted execution, or `Optional.empty()` when the
  `(current → newState)` pair is not a valid transition.
- `failAndScheduleRetry` and `abandonAndScheduleRetry` apply both writes in one transaction and
  return the **pre-re-enqueue** snapshot (`FAILED` and `ABANDONED` respectively) even though the
  committed row is `ENQUEUED`, so an audit consumer sees the attempt that failed. `Optional.empty()`
  signals a no-op.
- `completeClaimedExecution` and `abandonClaimedAndScheduleRetry` are the claim-fenced variants of
  `completeExecution` and `abandonAndScheduleRetry` that `JobCoordinator` uses for dead-node recovery.
  They take the claim owner (`lockedBy`) and the attempt number read (`claimedAttempt`), and must match a
  row only while it is still `PROCESSING` under that owner at that attempt, returning `Optional.empty()`
  otherwise. Both writes of the retry apply all three predicates. They are abstract: there is no default
  that falls back to the unfenced writes, so a repository that cannot test the claim cannot take part in
  dead-node recovery. A decorator must forward both to its delegate unchanged, or it removes the fence.

  **Breaking change.** These two methods were `default` methods without the `claimedAttempt` parameter,
  delegating to the unfenced writes. They are now abstract and take `claimedAttempt` after `lockedBy`.
  There is no deprecation cycle: every `JobRepository` implementation and decorator must declare both, and an
  implementation that takes part in recovery must apply the three predicates atomically.
- `tryInsert(execution)` is an insert-if-absent used for cron `SINGLE_INSTANCE` leader election: all
  nodes race on the same `(jobId, scheduledAt)` and only the winner receives the generated id.
- `saveSchedule` is an upsert — code is the source of truth and overwrites the stored row on every
  deploy. `removeServer` must tolerate a missing row.

The two records an implementation reads and writes are constructed **positionally**, so their
component order is part of this contract:

- `JobExecution(id, jobId, jobType, handler, queue, state, attemptNumber, maxAttempts, payload,
  priority, lockedBy, scheduledAt, enqueuedAt, startedAt, completedAt, errorMessage, errorType,
  progress, parameters, attributes, metadata, scopeKey)` — 22 components; a public 21-argument
  constructor without `scopeKey` remains and reads `default`. The compact constructor copies
  `parameters`/`attributes`, defaults `progress` to `ProgressSnapshot.EMPTY` and `metadata` to
  `DurableMetadata.empty()`, and maps a `null` `scopeKey` to `default`. A repository must persist
  `scopeKey` with the execution and return it unchanged on every read. Copy-on-write helpers (each
  carries `scopeKey`): `withState`, `withProgress`, `withPayload`,
  `withLockedBy`, `withError`, `withStarted(Instant, String)`, `withCompleted(Instant)`.
- `CronJobSchedule(jobId, cronExpression, handler, target, executionMode, timezone, enabled,
  overlapPolicy, maxAttempts, tracked, lastFiredAt, nextFireAt)` — 12 components, persisted for
  dashboard visibility. `executionMode` is `"EVERY_INSTANCE"` or `"SINGLE_INSTANCE"`;
  `overlapPolicy` is `"SKIP"` or `"QUEUE_ONE"`. `handler` may be `null` for `service:` targets, and
  `target` is `null` on rows written before that component existed — derive it as
  `"eventbus:" + handler` in that case.

### JobInterceptor

Cross-cutting observation around dispatch. `JobInterceptor extends OrderedExtension`, so instances
sort by extension phase ascending, then `priority()` ascending, then `orderKey()` (the fully
qualified class name by default). Both methods default to no-ops.

```java
public interface JobInterceptor extends OrderedExtension {
    default void onDispatch(JobDispatchContext ctx) {}
    default void onComplete(JobDispatchContext ctx, Result<?> result,
                            Instant startTime, Instant endTime) {}
}
```

`onDispatch` fires before the handler send; `onComplete` fires when the reply arrives, **before**
persistence, and only on the handler-reply path. Both are synchronous observers, and an exception
thrown by one interceptor is logged and swallowed so the rest still run. `result` is `null` when the
reply body was not a `Result`.

```java
@Singleton
public class MetricsJobInterceptor implements JobInterceptor {

    private final MeterRegistry registry;

    @Inject
    MetricsJobInterceptor(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onComplete(JobDispatchContext ctx, Result<?> result,
                           Instant startTime, Instant endTime) {
        registry.timer("job.duration", "job", ctx.jobId(), "type", ctx.jobType().name())
                .record(Duration.between(startTime, endTime));
    }
}

// In an application Dagger module:
@Provides @IntoSet
static JobInterceptor metricsInterceptor(MetricsJobInterceptor interceptor) {
    return interceptor;
}
```

### JobExecutionStateTransitionListener

Observation of **persisted** transitions. Where `JobInterceptor.onComplete` sees only the
handler-reply path before persistence, this listener fires after the database row has actually
transitioned — on every path, including retry, timeout, dead-node recovery, cancellation and
dead-lettering. Delivery requires a repository that emits these events;
`dev.vertique:vertique-job-postgresql` decorates its `JobRepository` binding to do so.

```java
public interface JobExecutionStateTransitionListener {
    void onStateTransition(JobExecutionStateTransitionEvent event);
}
```

The event is a curated, safe-by-type record with components, in order: `executionId`, `jobId`,
`jobType`, `queue`, `newState`, `attemptNumber`, `maxAttempts`, `errorType`, `scheduledAt`,
`enqueuedAt`, `startedAt`, `completedAt`, `metadata`, `scopeKey`. `errorType` is the fully qualified exception
class name, or `null` on success; `newState` may be the non-terminal `ABANDONED`; `startedAt` is
`null` when the job never ran; `metadata` is the persisted `DurableMetadata` document and is never
`null`; `scopeKey` is the row's scope, `default` when none was set, and never `null`. The record
keeps a public constructor without `scopeKey` that reads `default`. The record deliberately omits the payload, the raw error message, and the
`parameters`/`attributes` maps.

Implementations **must not block** — the call is synchronous on the completion thread. Submit async
work fire-and-forget rather than chaining on it. An exception from one listener is logged and
swallowed; later listeners still run.

```java
@Provides @IntoSet
static JobExecutionStateTransitionListener myListener(MyStateTransitionListener l) {
    return l;
}
```

---

## Module Dagger Bindings

`JobModule` declares the empty `Set<JobInterceptor>` and
`Set<JobExecutionStateTransitionListener>` multibindings so both resolve even when nothing is
contributed. It also binds `@JobNodeId String`, this node's job-scheduling identity
(`<hostname>-<8-char suffix>`, scoped once per component), which the coordinator heartbeats under and
the PostgreSQL repository and persistence-backed cron scheduler stamp as `locked_by`. A custom
`JobRepository` that records claim ownership should inject it rather than generate its own. Include
`JobModule` whenever any job scheduling module is used — the scheduling modules already include it
transitively.

`JobCoordinatorModule` includes `JobModule` and provides `JobCoordinatorConfig` (parsed from
`job.coordinator`) and the singleton `JobCoordinator`, built with the `@JobNodeId` identity, and
requires a bound `JobRepository`. `CronPersistenceModule` and
`DelayedJobModule` include it, so an application lists it explicitly only when wiring the coordinator
without either of those. It also contributes one `VerticleDeployment` named `job-coordinator` (one
however many modules include it) whose verticle calls `start()` when it deploys and `stop()` when it
undeploys. It deploys in the `SERVICES` phase at priority 50, below the priority 100 of the delayed-job
pollers and the cron scheduler. Verticles deploy in ascending and undeploy in descending priority, so
the claimers deploy only after the initial heartbeat write has settled (when that write fails, the next
periodic heartbeat writes the row), heartbeats continue until
every claimer has stopped, and the shutdown claim check sees the claims they made last. A host that
does not deploy the `Set<VerticleDeployment>` must call `start()` and `stop()` itself, in that order
around its claimers.

```java
@Component(modules = {VertxModule.class, JobPostgresqlModule.class, JobCoordinatorModule.class})
public interface AppComponent { ... }
```

---

## Dependencies

- **core** — `ContextValue` and `@DispatchContextValue` (handler-parameter injection), `Result`
  (dispatch outcomes), `DurableMetadata` (durable propagation context on `JobExecution` and the
  transition event), `OrderedExtension` (interceptor ordering), and `ConfigParser` /
  `JsonConfigPaths` (the `job.coordinator` parse boundary).
- **resilience** — the `BackoffStrategy` contract used for retry delays.
- **deploy** — `VerticleDeployment`, through which `JobCoordinatorModule` deploys the coordinator.
