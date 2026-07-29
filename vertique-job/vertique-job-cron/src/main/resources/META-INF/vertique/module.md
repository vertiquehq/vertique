<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Job Cron Module

> **Status:** Implemented
> **Package:** `dev.vertique.job.cron`
> **Artifact:** `vertique-job-cron`
> **Depends on:** job-core, services, logging, deploy

Timer-based cron scheduler for recurring jobs. Parses 6-field cron expressions, registers self-rescheduling Vert.x timers, and dispatches executions via fire-and-report over the event bus to service operation handlers (the reply-address delivery mode documented in `vertique-services`'s module reference). Jobs are discovered at startup by scanning service implementations for `@CronJob` annotations. Config-only jobs (without annotations) can be registered via the `cron.jobs` config subtree. Supports both in-memory (`CronModule`) and DB-backed (`CronPersistenceModule`) operation. `SINGLE_INSTANCE` mode uses INSERT ON CONFLICT leader election for cluster-wide singletons. Misfire recovery fires executions missed while all nodes were down.

---

## Package Layout

| Package | Contents |
|---------|----------|
| `dev.vertique.job.cron` | `CronJob`, `CronJobDefinition`, `CronJobRegistrar`, `CronScheduler`, `CronExpression`, `ExecutionMode`, `OverlapPolicy`, `MisfirePolicy`, `CronConfigurationException`, `CronRegistrationException`, `CronTargetReference`, `ServiceTarget`, `EventBusTarget` |
| `dev.vertique.job.cron.dagger` | `CronBaseModule`, `CronModule`, `CronPersistenceModule`, `CronLifecycleVerticle` (package-private) |

---

## Key Classes

### `@CronJob`

Method-level annotation that marks a service implementation method as a recurring cron job. Place on the implementation method, not the contract interface.

The contract method must carry `@ServiceOperation`. Cron resolves a job to a **stable service target**, and only an annotated operation has one — registration fails at startup otherwise.

```java
@ServiceContract(namespace = "maintenance", value = "cleanup-service")
public interface CleanupService {

    @ServiceOperation("cleanup-expired")
    Future<Void> cleanupExpired();

    @ServiceOperation("daily-report")
    Future<Void> generateDailyReport();
}

public class CleanupServiceImpl implements CleanupService {

    @CronJob(id = "cleanup-expired", cron = "0 0 2 * * *", overlapPolicy = OverlapPolicy.SKIP)
    @Override
    public Future<Void> cleanupExpired() {
        return repository.deleteExpiredRecords();
    }

    @CronJob(
        id = "daily-report",
        cron = "0 0 8 * * *",
        mode = ExecutionMode.SINGLE_INSTANCE,
        timezone = "Europe/Helsinki",
        tracked = "true",
        misfirePolicy = MisfirePolicy.FIRE_NOW
    )
    @Override
    public Future<Void> generateDailyReport() {
        return reportService.generate();
    }
}
```

| Attribute | Required | Default | Description |
|-----------|----------|---------|-------------|
| `id` | Yes | — | Unique job identifier (used as config override key) |
| `cron` | Yes | — | Cron expression in 6-field format |
| `mode` | No | `EVERY_INSTANCE` | Execution mode; `SINGLE_INSTANCE` requires a `JobRepository` |
| `timezone` | No | `"UTC"` | IANA timezone ID for evaluating the cron expression |
| `maxAttempts` | No | `3` | Max retry attempts before dead-letter |
| `overlapPolicy` | No | `SKIP` | What to do when the previous execution is still running |
| `tracked` | No | `""` (use global) | `"true"`, `"false"`, or `""` (use `cron.tracked` config; default `true`) |
| `misfirePolicy` | No | `FIRE_NOW` for `SINGLE_INSTANCE`, `SKIP` for `EVERY_INSTANCE` | How to handle fires missed while the app was down |

**Cron expression format (6 fields):**

```
second  minute  hour  day-of-month  month  day-of-week
  0       0      8        *           *         *        → daily at 08:00 UTC
  0      */15    *        *           *         *        → every 15 minutes
  0       0      9        *           *         1        → every Monday at 09:00
  0/30    *      *        *           *         *        → every 30 seconds
  0       0      0       15           *         *        → 15th of each month at midnight
```

Supported syntax: `*` (all), `N` (specific), `A-B` (range), `A,B,C` (list), `*/N` (every N from min), `A-B/N` (every N in range). Day-of-week: 0 = Sunday, 1 = Monday, …, 6 = Saturday (7 is also accepted as Sunday).

### `CronExpression`

6-field cron expression parser that computes next fire times. Parses into a `TreeSet<Integer>` per field for fast lookup.

| Method | Description |
|--------|-------------|
| `CronExpression(String)` | Parse; throws `IllegalArgumentException` on invalid expression |
| `computeNextFireTime(Instant from, ZoneId zone)` | Returns the next `Instant` strictly after `from`; searches up to 4 years |
| `computeFireTimesBetween(Instant from, Instant to, ZoneId zone, int maxResults)` | Returns all fire instants in `(from, to]`; used for misfire detection |
| `expression()` | Returns the raw expression string |

### `ExecutionMode`

| Constant | Description |
|----------|-------------|
| `EVERY_INSTANCE` | The job fires on every application instance independently; no distributed locking |
| `SINGLE_INSTANCE` | Runs on exactly one node per cluster per fire time; uses INSERT ON CONFLICT leader election; requires `JobRepository` and `SKIP` overlap policy |

### `OverlapPolicy`

| Constant | Description |
|----------|-------------|
| `SKIP` | Drop the fire if the previous execution is still running; logs a warning (default) |
| `QUEUE_ONE` | Queue one missed fire to run immediately after the current execution; only the most recent skipped time is queued (earlier fires are replaced). Only supported with `EVERY_INSTANCE`. |

### `MisfirePolicy`

Applied at startup during misfire recovery. Requires a `JobRepository` and a persisted schedule with `last_fired_at` data; jobs without a repository or with `tracked=false` always behave as `SKIP`.

| Constant | Description |
|----------|-------------|
| `FIRE_NOW` | Execute the most recent missed fire immediately (only one, even if multiple ticks were missed). Default for `SINGLE_INSTANCE`. |
| `SKIP` | Ignore missed fires; wait for the next scheduled tick. Default for `EVERY_INSTANCE`. |
| `FIRE_ALL` | Execute every missed fire in sequence, oldest to newest. Capped at `CronScheduler.MAX_MISFIRE_FIRES` (100). |

### `CronTargetReference`

Sealed interface representing where a cron job dispatches. Two variants:

| Variant | Scheme | Description |
|---------|--------|-------------|
| `ServiceTarget` | `service:{stableServiceTargetId}` | Resolved via `ServiceTargetResolver` at dispatch time |
| `EventBusTarget` | `eventbus:{eventBusAddress}` | Dispatched directly to the supplied event bus address |

```java
CronTargetReference target = CronTargetReference.parse("service:maintenance.cleanup-service.cleanup-expired");
// → ServiceTarget("maintenance.cleanup-service.cleanup-expired")

CronTargetReference direct = CronTargetReference.parse("eventbus:integrations/legacy/reconcile");
// → EventBusTarget("integrations/legacy/reconcile")
```

For annotated `@CronJob` methods on service implementations, the target is derived automatically from the service contract metadata. Config-only jobs require the `target` field.

### `CronScheduler`

Manages timer registrations and job dispatches. Each registered job gets its own self-rescheduling Vert.x one-shot timer: after each fire, the next fire time is computed and the timer is re-registered immediately.

**Concurrency controls:**

- **Per-job overlap** — `OverlapPolicy` per job definition. `SKIP` drops fires when the previous execution is in progress. `QUEUE_ONE` stores the most-recent skipped time and dispatches it after the current execution completes.
- **Global concurrency** — `maxConcurrentJobs` (default: 10, `CronScheduler.DEFAULT_MAX_CONCURRENT_JOBS`) limits total concurrent jobs across all definitions. Excess dispatches are queued in a `ConcurrentLinkedQueue` and run as slots open.

**`SINGLE_INSTANCE` leader election:** All nodes race to insert a `JobExecution` row for the same `(jobId, scheduledAt)` via `JobRepository.tryInsert()`. The `idx_job_executions_cron_dedup` unique index ensures only one node wins; losers receive `Optional.empty()` and skip dispatch.

**Per-execution features:**

- **Consumer timeout:** When `executionTimeoutMs > 0`, a local Vert.x timer marks the execution `ABANDONED` and releases the concurrency slot if no reply arrives in time. Set to `0` to disable.
- **Cooperative cancellation:** Each dispatched execution registers a consumer on `job.cancel.<executionId>`. When a cancel message arrives, `DefaultJobContext.setCancelled(true)` is called. Handlers should poll `ctx.isCancelled()` and exit gracefully.
- **Progress flush:** When `progressFlushIntervalMs > 0` and a repository is available, a periodic timer writes changed `ProgressSnapshot` values to the repository.
- **Deferred-execution provenance:** `CronJobDispatcher` binds a `DeferredExecutionOrigin` (`kind = "cron"`, `reference` = the job id) into the dispatch context, proving the dispatch is deferred execution for the opt-in identity-snapshot reconstruction initializer (ADR-0165).

**Tracked executions:** When `tracked=true` and a repository is available, a `JobExecution` is persisted before dispatch and updated on completion. `SINGLE_INSTANCE` jobs are always tracked.

| Method | Description |
|--------|-------------|
| `register(CronJobDefinition)` | Registers a job definition |
| `start()` | Schedules first timers for all registered jobs; runs misfire recovery |
| `stop()` | Cancels all timers and unregisters reply consumers |

### `CronJobRegistrar`

Scans all entries in `ServiceContractRegistry` for implementation methods annotated with `@CronJob`. Also registers config-only jobs (jobs with a `target` field in `cron.jobs.*` but no matching annotation). Called once at startup via `scan()`. All violations are collected before throwing `CronRegistrationException` (extends `CronConfigurationException` → core `ConfigurationException`) so the application fails fast with a complete error list. `service:` targets are stored as a stable `CronTargetReference.ServiceTarget` and resolved to the current event bus address at dispatch time via `ServiceTargetResolver` (not at startup) so the dispatched address always reflects the live service registry.

**Validation checks:**

- `@CronJob` is not placed on the contract interface method
- Job ID is not blank and not duplicated
- Cron expression is valid and parseable
- Timezone ID is valid (`ZoneId.of()` succeeds)
- Execution mode is recognized
- Overlap policy is recognized
- `SINGLE_INSTANCE` is only used when a `JobRepository` is available and overlap policy is `SKIP`
- Implementation method corresponds to a registered operation in the service registry

**Config resolution precedence (highest to lowest):**

1. Per-job config (`cron.jobs.<id>.*`)
2. Annotation value
3. Mode-based default (`SINGLE_INSTANCE` → `FIRE_NOW`; `EVERY_INSTANCE` → `SKIP`)

After registering all jobs, `scan()` persists schedule definitions to `job_schedules` via `JobRepository.saveSchedule()` (best-effort, fire-and-forget).

### `CronJobDefinition`

Immutable record describing a registered job:

| Field | Type | Description |
|-------|------|-------------|
| `id` | `String` | Unique job identifier |
| `cronExpression` | `CronExpression` | Parsed expression |
| `target` | `CronTargetReference` | Target reference (`ServiceTarget` or `EventBusTarget`); persisted in `job_schedules.target` |
| `handlerAddress` | `String` | Resolved event bus address (derived from `target` at startup; used at dispatch time) |
| `mode` | `ExecutionMode` | Execution mode |
| `timezone` | `ZoneId` | Timezone for cron evaluation |
| `maxAttempts` | `int` | Max retry attempts |
| `payload` | `Object` | Static payload included in dispatch (may be `null`) |
| `overlapPolicy` | `OverlapPolicy` | Overlap handling policy |
| `tracked` | `boolean` | Whether to persist execution records |
| `parameters` | `Map<String,Object>` | Static key-value metadata for the handler (immutable) |
| `misfirePolicy` | `MisfirePolicy` | Misfire handling policy |

---

## Configuration

### Global settings

```json
{
  "cron": {
    "tracked": true,
    "jobs": { ... }
  }
}
```

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `cron.tracked` | boolean | `true` | Global default for whether executions are persisted |

### Per-job overrides

Override or disable any annotation-defined job without code changes:

```json
{
  "cron": {
    "jobs": {
      "cleanup-expired": {
        "enabled": true,
        "cron": "0 0 3 * * *",
        "timezone": "Europe/Helsinki",
        "mode": "EVERY_INSTANCE",
        "overlapPolicy": "QUEUE_ONE",
        "tracked": false,
        "misfirePolicy": "SKIP"
      },
      "daily-report": {
        "enabled": false
      }
    }
  }
}
```

| Key | Type | Description |
|-----|------|-------------|
| `enabled` | boolean | `false` to skip the job entirely (default: `true`) |
| `cron` | string | Override the cron expression |
| `timezone` | string | Override the timezone (IANA zone ID) |
| `mode` | string | Override execution mode (`EVERY_INSTANCE` or `SINGLE_INSTANCE`) |
| `overlapPolicy` | string | Override overlap policy (`SKIP` or `QUEUE_ONE`) |
| `tracked` | boolean | Override whether executions are persisted |
| `misfirePolicy` | string | Override misfire policy (`FIRE_NOW`, `SKIP`, `FIRE_ALL`) |

### Config-only jobs

Register jobs without an annotation by specifying a `target` field:

```json
{
  "cron": {
    "jobs": {
      "weekly-report": {
        "target": "service:reporting.reports.generate-weekly-report",
        "cron": "0 0 9 * * 1",
        "timezone": "Europe/Helsinki",
        "mode": "SINGLE_INSTANCE",
        "parameters": {
          "days": 30,
          "format": "pdf"
        }
      },
      "legacy-bridge": {
        "target": "eventbus:integrations/legacy/reconcile",
        "cron": "0 15 * * * *"
      }
    }
  }
}
```

Config-only jobs support all the same per-job keys as annotation overrides, plus `target` and `parameters`. Use `target: "eventbus:{address}"` for direct event bus dispatch or `target: "service:{stableTargetId}"` for service contract operations.

---

## Dagger Wiring

Choose exactly one cron module:

### `CronModule` (in-memory, no DB)

No `JobRepository` required. `SINGLE_INSTANCE` mode is not available. Execution records are never persisted. `ServiceTargetResolver` must be provided (bound from the `services` module) for `service:` target resolution.

```java
@Component(modules = {VertxModule.class, DispatchModule.class, CronModule.class})
public interface AppComponent {
    VerticleDeploymentManager verticleDeploymentManager();
}
```

### `CronPersistenceModule` (DB-backed)

Requires a `JobRepository` binding (include `JobPostgresqlModule` and `DbPostgresqlModule`). Enables `SINGLE_INSTANCE` mode, execution tracking, and misfire recovery. Also includes `JobCoordinatorModule`.

```java
@Component(modules = {
    VertxModule.class,
    DispatchModule.class,
    DbPostgresqlModule.class,
    DbFlywayModule.class,
    JobPostgresqlModule.class,
    CronPersistenceModule.class,
    AppModule.class
})
public interface AppComponent {
    VerticleDeploymentManager verticleDeploymentManager();
}
```

### Auto-deployment

Both `CronModule` and `CronPersistenceModule` transitively include `CronBaseModule`, which contributes a `VerticleDeployment` named `cron-scheduler` at `LifecyclePhase.SERVICES`, priority 100. The deployment is handled by a package-private `CronLifecycleVerticle` that runs `CronJobRegistrar.scan()` then `CronScheduler.start()` on verticle start, and `CronScheduler.stop()` on stop.

**No manual `scan()` / `start()` calls are needed.** Including `CronModule` (or `CronPersistenceModule`) is sufficient: `CronBaseModule` contributes the `cron-scheduler` `VerticleDeployment` at `LifecyclePhase.SERVICES`, so the lifecycle runner (or a `manager.deployAll()` / `deployPhase(SERVICES)`) deploys it automatically — there is no separate cron `ApplicationStartupStep`.

If you use phased startup rather than `deployAll()`, you must explicitly include `LifecyclePhase.SERVICES` after services deploy, otherwise cron lifecycle (and delayed-job pollers) are silently skipped:

```java
serviceDeploymentManager.deployAll()
    .compose(v -> manager.deployPhase(LifecyclePhase.SERVICES))  // includes cron-scheduler
    .compose(v -> manager.deployPhase(LifecyclePhase.EDGE))
    .onSuccess(v -> startPromise.complete())
    .onFailure(startPromise::fail);
```

`CronJobRegistrar` and `CronScheduler` remain accessible on `AppComponent` for advanced use cases (custom integrations, tests) but apps no longer need to call them directly during startup.

**`scan()` is transactional:** validation collects all violations before throwing `CronRegistrationException` (extends `CronConfigurationException` → core `ConfigurationException`). If any violations exist, the scheduler is left untouched — no partial registrations. `persistSchedule()` is wrapped in a top-level `try/catch (Throwable)` so a synchronous throw from a custom `JobRepository` cannot escape `scan()`.

**Rollback on start failure:** if `scheduler.start()` returns a failed Future after `scan()` succeeded, `CronLifecycleVerticle` invokes `scheduler.stop()` best-effort before propagating the original cause (Vert.x does not call `stop()` on a failed `start()`).

**Contributing `JobInterceptor`s:**

```java
@Provides @IntoSet
static JobInterceptor metricsInterceptor(MetricsService metrics) {
    return new MetricsJobInterceptor(metrics);
}
```

---

## Dependencies

- **job-core** — `JobContext`, `JobDispatchContext`, `JobInterceptor`, `JobRepository`, `JobCoordinatorConfig`, `CronJobSchedule`, `JobModule`, `JobCoordinatorModule`
- **services** — `ServiceContractRegistry` (scanned for `@CronJob` annotations), `ServiceMethodInvoker` (dispatches events and injects `JobContext`/`JobDispatchContext`), `ServiceTargetResolver` (mandatory; resolves `service:` target references at dispatch time)
- **deploy** — `VerticleDeployment`, `LifecyclePhase`, `DeployerModule` (wired via `CronBaseModule` to auto-deploy `CronLifecycleVerticle`)
- **logging** — MDC utilities

> **MDC note.** `CronJobDispatcher` enriches MDC before dispatching each job execution.
> Enrichment that runs on the scheduler verticle thread (non-duplicated context) uses
> `org.slf4j.MDC` directly because the framework facade (`dev.vertique.logging.MDC`) requires
> a duplicated Vert.x context and would throw otherwise. Enrichment inside event-bus consumer
> handlers (which run on duplicated contexts) uses the framework facade normally.

---

## Related ADRs

- ADR-0112: Framework exception hierarchy and REST mapping — roots cron registration failures under `CronConfigurationException` → core `ConfigurationException`; `CronRegistrationException` extends `CronConfigurationException`.
- ADR-0165: Deferred-Execution Provenance and Bounded SYSTEM-Minting — establishes why `CronJobDispatcher` binds a `DeferredExecutionOrigin` into the dispatch context, letting the receive-side identity-snapshot reconstruction initializer distinguish proven deferred execution from an ordinary context-empty dispatch.

