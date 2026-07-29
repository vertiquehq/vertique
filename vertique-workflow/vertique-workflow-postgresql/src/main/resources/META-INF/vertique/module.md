<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Workflow PostgreSQL Module

> **Status:** Stable
> **Package:** `dev.vertique.workflow.postgresql`
> **Artifact:** `vertique-workflow-postgresql`
> **Depends on:** workflow-engine, workflow-core, db-postgresql, vertx-sql-client, jackson-databind

`vertique-workflow-postgresql` is the **PostgreSQL adapter** for the Vertique Workflow engine. It provides PostgreSQL implementations of the engine's internal repository and transaction-runner SPIs, owns the schema and Flyway migrations, and supplies Pg-specific query, retention, and recovery services.

For the dialect-neutral orchestration logic — `WorkflowEngine`, the 6 SPI interfaces (`WorkflowInstanceRepository`, `WorkflowHistoryRepository`, `WorkflowDedupRepository`, `BranchTokenRepository`, `JoinStateRepository`, `WorkflowTransactionRunner`), `RecorderRouter`, `WorkflowReminderComposeValidator`, and the Dagger module that binds `WorkflowOperations` — see `dev.vertique:vertique-workflow-engine`.

Install this module when workflows must survive process restarts, run across more than one node, or coordinate with transactional outbox and delayed-job modules. `WorkflowPostgresqlModule` includes `WorkflowEngineModule` transitively, so consumer wiring is unchanged — no `@Component` edits are required.

---

## What It Provides

| Binding | Purpose |
|---|---|
| Pg repo impls | `PgWorkflowInstanceRepository`, `PgWorkflowHistoryRepository`, `PgWorkflowDedupRepository`, `PgBranchTokenRepository`, `PgJoinStateRepository` — implement the engine SPIs |
| `TaskStore<SqlClient>` | Human task persistence (`PgTaskStore`) |
| `TimerStore<SqlClient>` | Workflow timer persistence (`PgTimerStore`) |
| `WorkflowTransactionRunner<SqlClient>` | Pg implementation of the engine's transaction seam |
| Query services | `PgWorkflowInstanceQueryService` — read model for workflow instances and branch state |
| Retention service | `PgWorkflowRetentionService` — SKIP LOCKED archive + purge sweep; the application drives the batch loop and cadence, there is no built-in scheduler |
| Recovery service | `PgWorkflowBranchRecoveryService` — opt-in cluster-singleton branch recovery cron |
| Migration support | Optional in-flight workflow definition migration (`WorkflowMigrationModule`) |
| Flyway schema | `V1__create_workflow_tables.sql` — all workflow tables and indexes |

The module `includes WorkflowEngineModule`, which provides the `WorkflowOperations` binding and recorder-router wiring. It does not deliver downstream service calls or execute delayed jobs by itself; those are separate modules.

---

## Runtime Model

Each workflow operation opens a Postgres transaction via the `WorkflowTransactionRunner`. The portable orchestration (owned by `vertique-workflow-engine`) drives the transition:

1. Resolve the pinned workflow definition version.
2. Lock or read the relevant workflow row.
3. Apply the next plan step.
4. Append workflow history.
5. Persist state changes (via the Pg repo impls).
6. Record side-effect intents through installed recorders.
7. Commit.

The engine stores serialized workflow state as JSON. The Java state type comes from the registered workflow plan.

In-flight instances are pinned to the definition version and plan hash recorded at start. They do not silently move to a newer definition.

The `WorkflowTransactionRunner` in this module delegates to `vertique-db-core`'s `TransactionBuilder`. Write paths run at the connection's default isolation (0 `SET TRANSACTION` statements); the `query()` read path uses `REPEATABLE READ` (1 statement, no `READ ONLY`).

Failures raised inside the transaction pass through a two-stage exception mapper. A DB-boundary stage owned by this module translates raw SQL/driver exceptions into `DataAccessException` subtypes, reusing the PostgreSQL SQL-state rules from `db-postgresql`; already-thrown workflow exceptions and other non-DB throwables pass through unchanged. A workflow-boundary stage owned by `vertique-workflow-engine` then translates those `DataAccessException` subtypes into the workflow exception hierarchy (for example `WorkflowConflictException` for a 409, `WorkflowPersistenceException` for a 500). See `dev.vertique:vertique-workflow-engine` for the full translation table.

---

## Schema Overview

The module owns workflow persistence tables, including:

| Table | Purpose |
|---|---|
| `workflow_instances` | Current snapshot of each workflow instance; includes `metadata JSONB` for the durable context captured once at start |
| `workflow_history` | Append-only engine history |
| `workflow_dedup` | Idempotency and signal deduplication |
| `workflow_timers` | Durable timer state; includes `metadata JSONB` for durable context. This row is the authoritative source of timer status — `vertique-workflow-delayed` treats it as such when reconciling against the delayed-job scheduler. |
| `workflow_tasks` | Human task rows |
| `workflow_branch_tokens` | Active branch execution state; includes `metadata JSONB` for durable context |
| `workflow_join_states` | Fan-in state for fork/join workflows |

The migration files live with the module under the workflow PostgreSQL resources. Since this module was pre-release when branch-token and `metadata` columns were added, the `workflow_branch_tokens` table definition and the `metadata` columns on both `workflow_timers` and `workflow_branch_tokens` were folded into `V1__create_workflow_tables.sql` rather than added as a subsequent migration. `V2__add_branch_and_join_support.sql` no longer exists — it was folded into V1 before any external release. After first public release, schema changes should be additive Flyway migrations.

---

## Required App Wiring

A typical durable workflow application includes:

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    WorkflowCoreModule.class,
    WorkflowPostgresqlModule.class,
    WorkflowServicesModule.class,
    TransactionalMessagingPostgresqlModule.class,
    TransactionalMessagingServiceModule.class,
    DbPostgresqlModule.class,
    DbFlywayModule.class,
    AppModule.class
})
public interface AppComponent {
    WorkflowOperations workflowOperations();
    WorkflowInstanceQueryService workflowInstanceQueryService();
}
```

Add optional modules based on behavior:

| Behavior | Add |
|---|---|
| Timer steps, signal timeouts, task due dates | `WorkflowDelayedModule`, delayed jobs, persistent cron |
| Human task service API | `WorkflowTasksModule` |
| External workflow event stream | `WorkflowEventsModule` |
| YAML/JSON definition loading | `WorkflowDefinitionModule` |
| In-flight definition migration | `WorkflowMigrationModule` |

---

## Idempotency And Deduplication

Workflow operations require caller-supplied idempotency or dedup keys where duplicate delivery is possible:

| Operation | Scope | Key |
|---|---|---|
| Start | Definition id | Start idempotency key |
| Signal | Workflow instance | Signal dedup key |
| Task completion | Task id | Task completion idempotency key |
| Task reassignment | Task id | Task reassignment idempotency key |
| Branch dispatch | Workflow and branch identity | Generated dispatch key |

Matching retries resolve as success. Reusing a key for a different logical command fails as a conflict.

---

## Crash Safety

The engine relies on Postgres transactions, optimistic concurrency, append-only history, and transactional side-effect recording.

Important guarantees:

- A workflow transition and its side-effect records commit or roll back together.
- Waiting workflows resume from the persisted snapshot.
- Signals and task actions are deduplicated.
- Delayed timers are recovered by `workflow-delayed` when that module is installed.
- Outbox relay retries may redeliver messages; workflow signal and task APIs are designed for idempotent callers.

The engine does not depend on in-memory workflow state for correctness.

---

## Lock-Order Requirements

Operations that touch multiple workflow tables follow a fixed lock order to avoid deadlocks with the timer firing executor and the signal claim path:

```
workflow_timers  →  workflow_tasks  →  workflow_branch_tokens  →  workflow_instances
```

Reversing any segment of this chain deadlocks against an already-running firing executor or a concurrent signal claim. All engine code paths — fire, signal, cancel, complete, supersede — follow it.

Integrator rule: do not write custom transactions that lock workflow tables in a different order. Prefer the public workflow APIs or the provided transactional services. When extending storage behavior, preserve the established ordering across timers, tasks, branch state, and workflow instances.

---

## Side Effects

`WorkflowPostgresqlModule` runs installed `WorkflowSideEffectRecorder<SqlClient>` bindings during transitions. Recorders are responsible for durable handoff only.

Common side-effect modules:

| Side effect | Module |
|---|---|
| Service dispatch outbox entries | `workflow-services` |
| Timer delayed jobs | `workflow-delayed` |
| External workflow events | `workflow-events` |

Missing required recorders fail the transition. Optional event recording may be absent unless the registered workflows require reminders.

---

## Queries And Management

The module exposes query services for workflow instances and branch state. These are read-model APIs for management screens and operational tools.

Management REST endpoints are intentionally separate from this persistence module. A REST management module can layer on top of the query services and public operation APIs without coupling HTTP concerns into the engine.

---

## Durable Context Propagation Seams

This module implements the workflow-side durable context propagation surfaces for timers, branches, and instance-level fill on carrier rows. The portable half of the base-wins/instance-fill model — the `WorkflowContextBinder` binder-row seam used by signal/cancel/retry/migrate/instance-owned task and timer drives — lives in `vertique-workflow-engine`, documented in `dev.vertique:vertique-workflow-engine`.

**Branch create:** `WorkflowEngine.handleForkNode` (in `vertique-workflow-engine`) captures the current durable context into `workflow_branch_tokens.metadata` under the `"WORKFLOW"` boundary. Metadata is written once and never updated.

**Branch drive:** `BranchTransitionEngine.driveBranchTransitions` binds `token.metadata()` at the start of every per-branch drive via `InboundExecutionContextScope.installDurable(metadata, DispatchBoundary.WORKFLOW)`. The bind covers timer-step, signal-step, service-dispatch, and decision step types and closes on `Future.eventually(...)`. The substrate helper wraps the authoritative `DurableContextPropagator.bindFrom(...)` — every registered durable type absent from `token.metadata()` is cleared for the drive's scope so a downstream `mergeCaptured(...)` (e.g. the timer side-effect recorder dual-write) cannot pick up ambient context that did not cross the workflow boundary. After the durable bind, registered `InboundContextInitializer`s run (notably the correlation seeder), so a branch whose metadata carries no encoded `CorrelationContext` still gets one bound for the duration of the drive (FR-COR-125). Empty metadata still triggers the bind. Production reaches branch-drive on a duplicated context: dispatcher entry points (event bus, Kafka per-record, cron callback, service-method invoker) are all duplicates, and `vertx-sql-client` 5.x preserves the caller's duplicate through `pool.withTransaction(tx -> …)`. Per FR-CTX-157b, `installDurable` propagates the strict `bindFrom` invariant — `IllegalStateException` on a non-duplicated Vert.x context — so tests that call into the engine directly from a JUnit thread must first switch to a duplicate.

`driveBranchTransitions` accepts an additional `@Nullable DurableMetadata effectiveBaseOverride` parameter. When non-`null` — as supplied by branch recovery below, and by a branch-targeted explicit signal carrier — the drive binds that already-merged effective document instead of raw `token.metadata()`. This is the seam every branch-owned drive routes through under the bind-once routing rule: it is never additionally wrapped by the instance-path `WorkflowContextBinder`, so a branch-owned drive is bound exactly once.

**Branch recovery — instance-fill seam:** `PgWorkflowBranchRecoveryService.withBranchDurableBound` wraps every per-branch advance in both the `processDue` path (due branches) and the `processStale` path (stale-RUNNING branches) via the same `InboundExecutionContextScope.installDurable(...)` helper, so the recovery sweep gets the same initializer fan-out as the live drive. Branches advance sequentially within one sweep so scopes do not overlap.

Before each row's scope opens, the sweep loads the owning `WorkflowInstance` (a non-locking `findById`, preserving the `workflow_timers → workflow_tasks → workflow_branch_tokens → workflow_instances` lock order — no new `FOR UPDATE`) and computes `effectiveBase = token.metadata().merge(instance.metadata(), MergePolicy.CALLER_WINS)`. When the instance carrier is `null` this is a no-op merge — `effectiveBase` equals `token.metadata()` unchanged, byte-identical to pre-feature behavior. When the instance carrier is non-`null`, it fills any namespace absent from the branch token — the mechanism that closes a namespace hole in a token that was forked during a context-degraded drive (a transport relay that lost a namespace, or a pre-feature fork). `effectiveBase` is passed as `driveBranchTransitions`'s `effectiveBaseOverride`, so the token's own carrier remains authoritative for every namespace it already carries. A bind failure is isolated to that row's advance failure; the sweep continues with the next row.

**Timer recovery:** `WorkflowTimerRecoveryService.withTimerDurableBound` similarly uses `installDurable(row.metadata(), DispatchBoundary.DELAYED_JOB)` for the terminal `timerFiringFailed` paths (DEAD_LETTER / SUCCEEDED / CANCELLED), so the cron sweep clearing ambient context still gets a freshly seeded `CorrelationContext` for the `WORKFLOW_FAILED` outbox emission when the timer row's metadata carries no correlation.

**Metadata immutability:** Once written at fork, `workflow_branch_tokens.metadata` is never overwritten. Demotion to retry-scheduled or terminal failure preserves the original value.

---

## Invariants & Gotchas

- **Branch tables have no FK to workflow_instances.** `workflow_tasks` and `workflow_timers` carry nullable `branch_token_id` / `fork_step_id` / `branch_id` columns but no FK constraint, by design — adding one would invert the established lock order.
- **Dispatch dedup lives in the engine, not the recorder.** Branch service-dispatch idempotency is enforced inside the persistence engine before the recorder runs, so the `workflow-services` boundary stays generic.
- **Stranded re-enqueue is detected by row count.** Timer-store mutations are guarded by `status = 'SCHEDULED'` and fail when `rowCount = 0`, rolling back the transaction rather than silently re-enqueuing into the wrong state.
- **Late callbacks are swallowed deliberately.** Task / timer callbacks carrying a `branchTokenId` from a superseded branch are recorded as `BRANCH_LATE_CALLBACK_IGNORED` history entries and return without raising — preventing zombie callbacks from corrupting later state.
- **Signal claim is non-locking + race-safe upsert.** `signal()` and `cancel()` use `findById` + `claimOrResolveSignal` to honor the `workflow_timers`-before-`workflow_instances` lock-order invariant.
- **Archived rows are filtered by default.** `findFiltered` / `findByFilter` exclude `archived_at IS NOT NULL` rows unless `WorkflowFilter.includeArchived = true`.

---

## Dependencies

- **workflow-engine** - portable orchestration runtime: `WorkflowEngine`, the 6 internal repository SPIs, `WorkflowTransactionRunner`, `RecorderRouter`, `WorkflowReminderComposeValidator`, `WorkflowEngineModule`. This module includes `WorkflowEngineModule`.
- **workflow-core** - public workflow APIs, plans, tasks, timers, and side-effect SPIs.
- **db-postgresql** - Postgres pool, `PgDbExceptionMapper`, and `PgSqlRepository` base.
- **vertx-sql-client** - SQL connection, pool, and transaction types.
- **jackson-databind** - workflow state and history payload serialization.
