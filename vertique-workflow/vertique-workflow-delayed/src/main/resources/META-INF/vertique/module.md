<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Workflow Delayed Module

> **Status:** Stable
> **Package:** `dev.vertique.workflow.delayed`
> **Artifact:** `vertique-workflow-delayed`
> **Depends on:** workflow-core, job-core, job-delayed, job-cron, services, deploy, vertx-sql-client

`vertique-workflow-delayed` provides durable workflow timers by integrating the workflow engine with Vertique delayed jobs and cron-backed recovery.

Install this module when workflows use timer steps, signal timeouts, task due dates, task reminders, publish-at steps, or expire-at steps.

---

## What It Provides

| Component | Purpose |
|---|---|
| Timer side-effect recorder | Persists workflow timer intents and enqueues delayed jobs in the workflow transaction |
| Timer fire executor | Handles delayed-job execution and resumes the waiting workflow |
| Timer recovery service | Reconciles orphaned or dead-lettered timer jobs |
| Compose validator | Ensures timer, delayed-job, and cron dependencies are installed together |

The workflow engine owns timer semantics. This module supplies durable scheduling and recovery.

---

## Timer Flow

When the engine reaches a timer-backed wait:

1. The engine emits a workflow timer side-effect intent.
2. The recorder writes the workflow timer row and enqueues a delayed job inside the same transaction.
3. The workflow waits.
4. The delayed-job executor locks the timer row before firing.
5. If the timer is still scheduled, the executor calls back into the engine and marks the timer fired.

All writes happen in the caller's SQL transaction. A timer is not visible to the delayed-job poller until the workflow transition commits.

---

## Recovery

The module includes cron-driven recovery for timers whose delayed job row is missing, stuck, or dead-lettered.

Recovery is a cluster-singleton cron job. It periodically scans scheduled timers that are past a grace period and reconciles them with delayed-job state.

Operational behavior:

- Orphaned scheduled timers are re-enqueued.
- In-flight delayed jobs are left alone.
- Dead-lettered timer jobs are reported back to the workflow engine.
- Per-row failures are isolated so one bad timer does not block the whole sweep.

The cadence is configured through the cron job configuration for workflow timer recovery.

---

## Required App Wiring

Apps that use durable workflow timers need the workflow, delayed-job, and persistent cron modules together:

```java
@Singleton
@Component(modules = {
    WorkflowCoreModule.class,
    WorkflowPostgresqlModule.class,
    WorkflowServicesModule.class,
    WorkflowDelayedModule.class,
    DelayedJobModule.class,
    CronPersistenceModule.class,
    TransactionalMessagingPostgresqlModule.class,
    TransactionalMessagingServiceModule.class,
    DbPostgresqlModule.class,
    DbFlywayModule.class,
    AppModule.class
})
public interface AppComponent {
    WorkflowOperations workflowOperations();
    WorkflowDelayedComposeValidator workflowDelayedComposeValidator();
}
```

`WorkflowDelayedModule` does not include `DelayedJobModule` or `CronPersistenceModule`. The application must install them explicitly.

Use persistent cron for production-style workflow timers. The in-memory cron module is not sufficient for the cluster-singleton recovery job.

---

## Extension Points

| Extension point | Purpose |
|---|---|
| `@WorkflowRecorders` | Receives the workflow timer recorder |
| `@DelayedJobs` | Receives the timer fire executor |
| `@Services` | Receives the cron recovery service |
| `TimerStore<TX>` | Persistence SPI supplied by `workflow-postgresql` |
| `TransactionalTimerCallbacks<TX>` | Engine callback SPI supplied by `workflow-postgresql` |

Applications usually install the provided module rather than implementing these SPIs.

---

## Durable Context Propagation

Durable context — correlation, caller identity, and any other registered durable namespace — is captured when a timer is created and restored when it fires, including after a crash, a dead-letter, or a recovery re-enqueue. Applications do not thread anything through the timer themselves.

**Timer create.** The recorder captures the ambient durable context once and writes it to `workflow_timers.metadata` as the recovery-state copy, which survives dead-letter cleanup of the job row. The timer's delayed job is enqueued through the *ordinary* enqueue path, so the job row independently captures its own copy of the same ambient context.

The two documents are written in one transaction but are **not** interchangeable: each is bound to the row that owns it. That is why neither is copied into the other — a document bound to the timer row cannot be replayed as the job row's context.

**Timer fire.** Context is restored by the standard delayed-job dispatch pipeline before the executor runs, so the resumed workflow observes the context that was ambient at timer create. This module adds no fire-time binding of its own.

**Timer recovery.** `workflow_timers.metadata` is the recovery source of truth, because the job row may be absent (dead-letter cleanup) or stale. Recovery restores the timer row's stored context for the duration of the reconcile and re-enqueues the replacement job through the *ordinary* enqueue path — under that restored context — so the replacement job captures its own copy bound to itself. The stored document is never handed to the replacement job verbatim.

---

## Operational Notes

- The timer row is the source of truth. Delayed jobs are the scheduling mechanism.
- Transactions that touch both timer rows and workflow instance rows must lock timer rows first to avoid deadlocks with the firing executor.
- Timer jobs may be retried by delayed-job infrastructure. The executor is idempotent and treats already-closed timers as no-ops.
- A dead-lettered timer leaves the workflow waiting for manual recovery or a management action.

---

## Invariants & Gotchas

- **Lock order.** Within the firing path and any extension code, `workflow_timers` is locked before `workflow_tasks` / `workflow_branch_tokens` / `workflow_instances`. Reversing this order produces a deadlock with the firing executor. All engine code paths (fire, signal, cancel) follow this invariant.
- **Idempotent executor.** The executor treats an already-closed timer row (`FIRED` / `CANCELLED` / `FAILED`) as a no-op and returns without raising. Delayed-job retries are therefore safe.
- **Cluster-singleton recovery.** The orphan-recovery cron job must run as a cluster singleton — install the persistent-cron module, not the in-memory variant. Running it on every node racing against itself can resurrect cancelled timers.
- **Wait-slot mismatch.** A fire is gated on the wait slot of the waiting workflow instance — or, for a timer owned by a parallel branch, of the branch token. The slot is `wait_type` plus `wait_key` / `wait_aux_id`, and the firing timer's id must match it: `wait_type=TIMER` with `wait_key` = timer id (standalone), `wait_type=SIGNAL` with `wait_aux_id` = timer id (signal timeout), or `wait_type=TASK` with `wait_aux_id` = timer id (task due date). If the instance is no longer waiting, or the slot names a different timer or a different wait type, the engine reports the fire as a stale no-op: the timer row is marked `FAILED` with a `TIMER_INCONSISTENCY` reason, the delayed job completes successfully without retrying, and the workflow is left exactly as it was rather than transitioning on a stale schedule. A superseded timer therefore ends up as a `FAILED` timer row against an otherwise healthy workflow — read the reason before treating it as a fault. This is distinct from `timerFiringFailed`, which belongs to the recovery sweep for dead-lettered or inconsistent timer jobs.

---

## Dependencies

- **workflow-core** - timer side-effect SPI and timer callback contracts.
- **job-delayed** - delayed-job client and executor infrastructure.
- **job-cron** - cluster-singleton recovery scheduling.
- **services** - service contract registration for the recovery job.
- **vertx-sql-client** - SQL transaction type used by the recorder and executor.
