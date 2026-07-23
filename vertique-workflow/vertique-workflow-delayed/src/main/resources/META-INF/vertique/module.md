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

## Durable Context Propagation Seams

This module implements the timer-side durable context propagation path. Timer metadata is captured once at timer-create and survives the full timer lifecycle including dead-letter and recovery.

**Timer create (`WorkflowTimerSideEffectRecorder`):** Calls `DurableContextPropagator.mergeCaptured(DurableMetadata.empty(), "DELAYED_JOB")` — the caller-supplied context document is always empty by construction so no collision with framework-captured namespaces is possible. The resulting `DurableMetadata` document is dual-written inside the same transaction:
- Into `workflow_timers.metadata` — recovery-state source of truth; survives delayed-job dead-letter or DELETE
- Into `job_executions.metadata` — dispatch-time wire format picked up by `DelayedJobPoller`

Both `DelayedJobOptions.premergedMetadata` and the `TimerStore.insertScheduled` call receive the same `DurableMetadata` document, so both rows carry identical metadata at creation.

**Timer fire:** Context is restored through the standard delayed-job dispatch pipeline. `DelayedJobPoller.dispatch` calls `decodeToDispatchContext(execution.metadata(), "DELAYED_JOB")` and places decoded values in the envelope's `callerOverrides`. `InboundDispatchScope.install` binds them on the handler's duplicated context. There is no `WorkflowTimerFireExecutor`-side binding.

**Timer recovery (`WorkflowTimerRecoveryService`):** Copies `workflow_timers.metadata` into the replacement delayed-job via `DelayedJobOptions.premergedMetadata`. This routes through `DelayedJobService.enqueuePremerged`, persisting the `DurableMetadata` document as-is without re-capture. The recovery sweep uses `workflow_timers.metadata` as the source of truth because `job_executions` rows may be absent (dead-letter DELETE) or stale.

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
- **Slot mismatch.** Firing executes against a captured `executionId` slot. If the slot does not match the current timer row, the fire is recorded as `timerFiringFailed` and the workflow remains in its prior wait state rather than transitioning on a stale schedule.

---

## Dependencies

- **workflow-core** - timer side-effect SPI and timer callback contracts.
- **job-delayed** - delayed-job client and executor infrastructure.
- **job-cron** - cluster-singleton recovery scheduling.
- **services** - service contract registration for the recovery job.
- **vertx-sql-client** - SQL transaction type used by the recorder and executor.

---

## Related ADRs

- ADR-0035: Timer transactional enqueue — timer rows are persisted inside the workflow transaction.
- ADR-0036: Timer cancellation source of truth — the timer row is authoritative; delayed jobs are only the scheduling mechanism.
- ADR-0038: Orphan recovery in workflow-delayed — cluster-singleton cron-driven recovery for stranded timers.
- ADR-0040: Timer dead-letter policy — failed timers wait for explicit recovery rather than auto-cancellation.
- ADR-0041: Timer callbacks SPI — `TransactionalTimerCallbacks<TX>` shape.
