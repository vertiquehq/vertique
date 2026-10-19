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
| `WorkflowDefinitionRegistryStore` | Durable definition metadata, TEXT sources, and fenced activation (`PgWorkflowDefinitionRegistryStore`) |
| `WorkflowTransactionRunner<SqlClient>` | Pg implementation of the engine's transaction seam |
| Query services | `PgWorkflowInstanceQueryService` — read model for workflow instances and branch state |
| Retention service | `PgWorkflowRetentionService` — SKIP LOCKED archive + purge sweep; the application drives the batch loop and cadence, there is no built-in scheduler |
| Recovery service | `PgWorkflowBranchRecoveryService` — opt-in cluster-singleton branch recovery cron |
| Migration support | Optional in-flight workflow definition migration (`WorkflowMigrationModule`) |
| Flyway schema | Shipped `V1__create_workflow_tables.sql` plus additive `V002__create_workflow_definitions.sql` `V003__add_workflow_definition_digest.sql` and `V004__add_workflow_tenant_key.sql` |

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

The migration files live with the module under `db/migration/workflow`. The shipped `V1__create_workflow_tables.sql` contains the instance/runtime tables, including branch tokens and durable metadata. The additive `V002__create_workflow_definitions.sql` adds the definition registry and `V003__add_workflow_definition_digest.sql` adds its `document_digest` column. `V004__add_workflow_tenant_key.sql` adds a nullable `tenant_key VARCHAR(255)` column to `workflow_instances` and `workflow_tasks`; existing rows read `null`. The statements change only catalog metadata, but each takes an `ACCESS EXCLUSIVE` lock on its table until the migration commits, and the script bounds its wait for that lock with `SET LOCAL lock_timeout = '30s'`: run it when no long transaction (a retention sweep, a long read) is open, and expect it to fail and be retried rather than stall the workflow tables. No index is added; tenant filters use the existing keyset indexes until a workload shows the need for a dedicated one. Nodes that still run the previous release insert tasks without a tenant, so once tenants are stamped, finish the rolling upgrade before relying on tenant-filtered task lists. V1, V002 and V003 are unchanged.

`PgWorkflowInstanceRepository.insert` writes `tenant_key` from `WorkflowInstance.tenantKey()` and no update statement changes it. `PgTaskStore.insertOpen` writes the task's `tenant_key` from the owning instance row (`SELECT tenant_key FROM workflow_instances WHERE id = …`) and ignores the value on the `TaskRecord`, so every task creation path inherits the instance tenant; the guarantee belongs to this store, not to the `TaskStore` SPI. `WorkflowInstanceQuery.tenantKey()` and `TaskFilter.tenantKey()` add an exact-match `tenant_key = ?` condition when non-null and leave keyset order and archived handling unchanged; `null` matches every tenant. The column and the filters are information and a filter, not an isolation or authorization boundary: callers must not pass an unresolved tenant as `null` expecting scoping, and start idempotency and business keys stay global. This module only stores the tenant; the engine resolves it at start through `WorkflowTenantResolver`.

---

## Definition Registry And Source Storage

The definition registry requires **PostgreSQL 15 or later** for null-tenant uniqueness using
`NULLS NOT DISTINCT`. `workflow_definitions` stores CODE metadata and DOCUMENT source text separately
from compiled plans. DOCUMENT source uses TEXT with kind/nullability constraints and no fixed database
size cap. The adapter enforces the shared `WorkflowDefinitionsConfig` policy at document boundaries.

Runtime document load writes a CANDIDATE. Activation uses a fresh UUID claim, local registration, then
a fenced completion; obsolete completions fail and obsolete releases are no-ops. Every definition
mutation takes an id-derived transaction advisory lock followed by ordered locks on all rows of that
id. Claim ages use PostgreSQL wall clock after lock acquisition, with twice `maxStalenessSeconds`
(default 30 seconds) as the stale age. These locks serialize each store transaction, not the whole
cross-node compilation operation. Before every mutating lock acquisition, the store sets transaction-
local `lock_timeout` to `30000ms`; this bounds each advisory or row-lock wait independently. A timeout
rolls back the transaction, releases its locks, and clears the local setting when the connection is
reused. SQLSTATE `55P03` is surfaced as a retryable `WorkflowPersistenceException` with the exact
definition id in its context and the pessimistic-locking database failure preserved as its cause. The
store does not retry inside the aborted transaction.

`recomputeActive` operates on one exact tenant owner and id. It chooses the highest version whose status
is ACTIVE or SUPERSEDED, demotes an older ACTIVE row before promotion to satisfy the immediate
one-ACTIVE index, and leaves other statuses and owners untouched. Both status changes commit together;
a failed promotion rolls back its preceding demotion. Rows whose status already matches the result keep
their original `status_changed_at`.

Candidates for the same id and version may coexist under different tenant keys (or the global owner), and
`list` returns them by exact tenant scope and status set without reading document bodies. Executable
membership is stricter: one owner at a time holds a definition id. Rows of another owner in ACTIVE,
SUPERSEDED or DEPRECATED, or holding a fresh ACTIVATING claim, hold the id; a stale claim does not.
`claim` and `complete` (with a current token; an obsolete token fails as a stale claim first) for a
different owner fail with `OWNER_CONFLICT` naming the holders, and `upsertDocument` reports `OWNER_CONFLICT`. The check runs under the id lock, so concurrent activations
by different owners, including for an id with no rows yet, admit exactly one. Within one owner,
completion demotes lower ACTIVE versions before promotion, and a lower version completing after a higher
one becomes SUPERSEDED, so the one-ACTIVE index is never violated.

`transition(key, from, to)` is a conditional status change under the same id lock: it checks the row,
the owner and the expected current status, and updates only a row still in that status. The store
accepts ACTIVE to DEPRECATED and SUPERSEDED to DEPRECATED (which do not recompute or promote other versions) and RETIRED
to SUPERSEDED (which does); any other pair, a missing row or a changed status fails with `INVALID_TRANSITION`.

`transition(key, RETIRED, SUPERSEDED)` is the atomic reinstatement: in one transaction under the id lock it checks the row is RETIRED
and that no other owner holds the id, sets it SUPERSEDED, demotes lower ACTIVE versions of the owner and then promotes the highest
eligible version; any failure rolls back everything. Like every registry mutation (see `retire` below), its transaction runs at READ COMMITTED whatever the server
default, so the owner check sees every row another owner committed while the call waited for the id lock. The store does not check
that an executable source exists or that its hash matches; the lifecycle does that before it calls the store.

`retire(key)` moves a CANDIDATE row, or a DEPRECATED row past the cooling window (`maxStalenessSeconds`, database
clock), to RETIRED. Every registry mutation runs at READ COMMITTED, whatever the server default, so the owner checks and the pin count see
every row and pin committed while the mutation waited for the id lock. Under the id lock it checks the row, the status, and for a
DEPRECATED row the cooling age and then the exact-version count of RUNNING, WAITING and COMPENSATING instances (archived
rows included), in that order; a CANDIDATE row is retired without the pin check because a candidate is not admitted for
pinning (a runtime registered directly for the same version is outside the guard). ACTIVE and SUPERSEDED rows cannot retire. The count scans `workflow_instances` by definition id and version while
retirement holds the exclusive id lock and no index covers that predicate, so on a very large live instance table a
retirement can delay new starts of that id. `PgWorkflowInstanceQueryService.countNonTerminal(definitionId, version)` exposes the same
informational count; it is not the retirement guard.

Pin admission: before `insert` creates an instance pin, `migratePinAndState` changes one, or `updateOptimistic`
resurrects a terminal instance (FAILED retry or FAILED to COMPENSATING), the repository takes
`pg_advisory_xact_lock_shared` on the definition id in its own statement, then reads the id's definition rows in a second
statement. Starts share the lock, so they do not serialize each other, but a queued deprecate or retire waits for them and
PostgreSQL queues later admissions behind that waiting writer, so long caller transactions can delay lifecycle and startup
mutations. The caller's pin transaction must therefore end before it awaits any store-owned lifecycle call for the same
id. At READ COMMITTED an id with no definition rows is admitted (a definition registered only by direct runtime registration after startup, or an engine built without the registry bootstrap, has none); otherwise the sole
holding owner must have the exact version and plan hash ACTIVE or SUPERSEDED, and a resurrection also accepts DEPRECATED.
Under REPEATABLE READ or SERIALIZABLE the rows are read `FOR SHARE`, a changed row raises a serialization failure, and an id
with no visible row is refused with `INVALID_TRANSITION` (a snapshot taken before the first row cannot tell absence from a
newly managed id; this includes engines built by hand that skip the registry bootstrap). Every admission sets
`SET LOCAL lock_timeout = '30000ms'`; a timeout rolls back the caller's transaction, so retry the whole caller
transaction. In a caller-owned transaction the failure is the data-access `PessimisticLockingFailureException`
(SQLSTATE 55P03); the retryable `WorkflowPersistenceException` carrying the id is what the engine's own runners and the
store's lifecycle operations raise. The setting overrides a stricter `lock_timeout` configured on the role or session and
also bounds every later lock wait of that transaction at 30 seconds until its commit or rollback. Direct callers must pass a `SqlConnection` with a pending Vert.x transaction; a pool, bare or completed
connection, raw `BEGIN` or wrapper fails with `IllegalArgumentException` before any SQL.

DOCUMENT bodies require `server_encoding = UTF8`. The adapter checks this before its first body
read/write, caches only a successful capability result, and retries a failed lookup on the next
operation. Metadata and CODE-only operations do not require that capability. Every source read checks
UTF-8 byte length and code-point count in the same SQL statement that conditionally projects the body.
Oversized rows return identity/counts with no body transfer, then fail with a sanitized key/limit
message; they are not treated as absent. Metadata listing never selects the source body.

The stored plan hash covers the compiled plan structure, not callback bodies. `V003` adds `document_digest`,
the lowercase hex SHA-256 of the stored UTF-8 text (backfilled for existing DOCUMENT rows; present exactly on
DOCUMENT rows). The adapter writes it with every document, treats an equal plan hash with a different text
digest as drift when a packaged document is reconciled, and verifies it on every guarded source read (after the
size-envelope check): altered stored text is never returned and fails as drift. This detects accidental or
concurrent change of stored text; it does not authenticate the writer, so restrict write access to the table.
Any other writer of DOCUMENT rows must supply the digest. On first boot after upgrading, every application that uses the PostgreSQL module writes a global CODE row per code-first definition even if it never touches documents; the `V003` migration must have run.

With a durable registry store bound (the PostgreSQL module binds one), the engine records every code definition, and with `WorkflowDefinitionModule` installed also the packaged documents, and restores activated documents of every owner after
Flyway migration, recomputes active membership per owner, and refreshes verified definition-cache entries
from committed metadata before BOOTSTRAP verticle deployment. Every node sharing live document rows must
carry their loaders and callback/type catalogs. Source locations are attribution, never remote fetch
instructions. Configure the same policy on every node; raising the input limit increases memory and
parse cost, and lowering it below required stored sources makes startup fail. The stored envelope is
floor(3L/2) UTF-8 bytes and L code points for configured raw input limit L (default 262144 bytes).

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
- **Archive and purge do not share a clock.** `archiveBefore` stamps `archived_at` from the *database* clock, while `purgeArchivedBefore` compares it against the cutoff *you* supply. Since your application drives the sweep cadence, a purge issued immediately after an archive with a cutoff read from your own clock may skip rows it just archived, whenever the database clock is running ahead. Use a cutoff meaningfully in the past — real retention horizons of hours or days make this invisible.

---

## Dependencies

- **workflow-engine** - portable orchestration runtime: `WorkflowEngine`, the 6 internal repository SPIs, `WorkflowTransactionRunner`, `RecorderRouter`, `WorkflowReminderComposeValidator`, `WorkflowEngineModule`. This module includes `WorkflowEngineModule`.
- **workflow-core** - public workflow APIs, plans, tasks, timers, and side-effect SPIs.
- **db-postgresql** - Postgres pool, `PgDbExceptionMapper`, and `PgSqlRepository` base.
- **vertx-sql-client** - SQL connection, pool, and transaction types.
- **jackson-databind** - workflow state and history payload serialization.
