<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Workflow PostgreSQL Module

> **Status:** Stable
> **Package:** `dev.vertique.workflow.postgresql`
> **Artifact:** `vertique-workflow-postgresql`
> **Depends on:** workflow-engine, workflow-core, db-postgresql (which brings `dev.vertique:vertique-db-core` for
> `DbSchemas` and `MigrationDefinition`), vertx-sql-client, jackson-databind. `dev.vertique:vertique-db-flyway`
> is a declared dependency, but no type of this module references Flyway: the application supplies the
> migration runner.

`vertique-workflow-postgresql` is the **PostgreSQL adapter** for the Vertique Workflow engine. It provides PostgreSQL implementations of the engine's internal repository and transaction-runner SPIs, owns the schema and Flyway migrations, and supplies Pg-specific query, retention, and recovery services. `WorkflowMigrationsModule` declares those migrations as the `workflow` migration instance for named Flyway instances, and the optional `db.schemas.workflow` setting places every workflow table in one schema for the migrations and for all nine stores.

For the dialect-neutral orchestration logic — `WorkflowEngine`, the 6 SPI interfaces (`WorkflowInstanceRepository`, `WorkflowHistoryRepository`, `WorkflowDedupRepository`, `BranchTokenRepository`, `JoinStateRepository`, `WorkflowTransactionRunner`), `RecorderRouter`, `WorkflowReminderComposeValidator`, and the Dagger module that binds `WorkflowOperations` — see `dev.vertique:vertique-workflow-engine`.

Install this module when workflows must survive process restarts, run across more than one node, or coordinate with transactional outbox and delayed-job modules. `WorkflowPostgresqlModule` includes `WorkflowEngineModule` transitively, so consumer wiring is unchanged — no `@Component` edits are required.

---

## What It Provides

| Binding | Purpose |
|---|---|
| Pg repo impls | `PgWorkflowInstanceRepository`, `PgWorkflowHistoryRepository`, `PgWorkflowDedupRepository`, `PgBranchTokenRepository`, `PgJoinStateRepository` — implement the engine SPIs; each is one scoped instance built with the optional `DbSchemas` placement |
| `TaskStore<SqlClient>` | Human task persistence (`PgTaskStore`), one scoped instance built with the placement |
| `TaskReservations<SqlClient>` | The `OPEN` to `COMPLETING` reservation of a recorded completion and its reopening (`PgTaskReservations`), one scoped instance built with the placement; two conditional updates, each a compare-and-set on the task's status and owner |
| `TimerStore<SqlClient>` | Workflow timer persistence (`PgTimerStore`), one scoped instance built with the placement |
| `WorkflowDefinitionRegistryStore` | Durable definition metadata, TEXT sources, and fenced publication (`PgWorkflowDefinitionRegistryStore`), one scoped instance built with the placement |
| `WorkflowCommandRepository<SqlClient>` | The recorded command log (`PgWorkflowCommandRepository`), one scoped instance built with the placement and the engine's propagator (the Dagger module injects `DurableContextPropagator`): the execution probe's bounded lists, the executor's one-row availability-delay read, the execution lock and queue sequence, the `START` insert of a recorded start, the `COMPLETION` insert of a recorded task completion, and the executor's peek, defer, claim, fence, completion and release; eligibility is rendered once in `CommandEligibilitySql` |
| `WorkflowTransactionRunner<SqlClient>` | Pg implementation of the engine's transaction seam, including the deadline overload the executor's segments run through (`TransactionBuilder.deadline`; `TransactionDeadlineExceededException` passes the workflow exception mapping unchanged) |
| Query services | `PgWorkflowInstanceQueryService` — read model for workflow instances and branch state |
| Retention service | `PgWorkflowRetentionService` — SKIP LOCKED archive + purge sweep; the application drives the batch loop and cadence, there is no built-in scheduler. It archives and purges through the scoped `PgWorkflowRetentionRepository`, so it follows the same placement |
| Recovery service | `PgWorkflowBranchRecoveryService` — opt-in cluster-singleton branch recovery cron |
| Migration declaration | `WorkflowMigrationsModule` — contributes the `workflow` `MigrationDefinition` (`classpath:db/migration/workflow`) with `@IntoSet`; see [Flyway Configuration](#flyway-configuration) |
| Flyway schema | Shipped `V1__create_workflow_tables.sql` plus the additive `V002__workflow_definitions_and_scopes.sql` and `V003__recorded_execution.sql` |

The module `includes WorkflowEngineModule`, which provides the `WorkflowOperations` binding and recorder-router wiring. It also includes `WorkflowMigrationsModule` and `DbSchemasModule` (the optional `DbSchemas` binding). It does not deliver downstream service calls or execute delayed jobs by itself; those are separate modules.

> **Two similarly named modules.** `WorkflowMigrationsModule` (plural, in `dev.vertique.workflow.postgresql.engine`) declares the *database schema* migrations of this module for Flyway. `WorkflowMigrationModule` (singular, in `dev.vertique:vertique-workflow-core`) is the optional wiring for *in-flight workflow definition migration*, which moves running instances from one definition version to another. They are unrelated; install the second only when you use in-flight migration (see [Required App Wiring](#required-app-wiring)).

---

## Key Classes

### The nine stores and their constructors

Nine PostgreSQL classes own every workflow SQL statement: `PgWorkflowInstanceRepository`, `PgWorkflowHistoryRepository`, `PgWorkflowDedupRepository`, `PgBranchTokenRepository`, `PgJoinStateRepository`, `PgTaskStore`, `PgTimerStore`, `PgWorkflowDefinitionRegistryStore` and `PgWorkflowRetentionRepository`. Each has two public constructors:

```java
// the other six stores: (Pool pool, PgDbExceptionMapper mapper)
@Inject
public PgWorkflowInstanceRepository(Pool pool, PgDbExceptionMapper mapper)

public PgWorkflowInstanceRepository(Pool pool, PgDbExceptionMapper mapper, DbSchemas schemas)

// PgTimerStore takes no Pool
@Inject
public PgTimerStore(PgDbExceptionMapper exceptionMapper)

public PgTimerStore(PgDbExceptionMapper exceptionMapper, DbSchemas schemas)

// PgWorkflowDefinitionRegistryStore also takes the optional shared document policy
@Inject
public PgWorkflowDefinitionRegistryStore(
        Pool pool, PgDbExceptionMapper mapper, Optional<WorkflowDefinitionsConfig> config)

public PgWorkflowDefinitionRegistryStore(
        Pool pool, PgDbExceptionMapper mapper, Optional<WorkflowDefinitionsConfig> config, DbSchemas schemas)
```

- **Unmapped means unchanged.** The constructors without `DbSchemas` leave every table unqualified, so the workflow tables resolve through the connection's default schema. With no `workflow` entry in the `DbSchemas` passed to the other constructor, the SQL is the same as theirs.
- **Mapped means every reference is qualified.** The `DbSchemas` constructors qualify every workflow table reference, in every statement, with the schema that `schemas` maps to `workflow`. This covers lifecycle, history, dedup, branch and join, task, timer, definition registry, query, recovery and retention statements, including the joins and subqueries between workflow tables (the task insert's scope subselect on `workflow_instances` and the narrowed instances projection that `PgTaskStore.findByFilter` joins for a subject filter among them) and the pin-admission and retirement-guard statements that read `workflow_definitions` and `workflow_instances`. Statements are built once at construction.
- **Invalid mappings fail at construction.** A schema that is not a single valid PostgreSQL identifier (or is reserved by PostgreSQL) throws `IllegalArgumentException` when the store is created, before any query runs (see `PgTableNames` in `dev.vertique:vertique-db-postgresql`).
- **Column qualifiers are never schema-qualified.** Qualifiers inside the SQL, such as `workflow_dedup.workflow_id` in the dedup upsert, name the table within the statement. SQL in this document is shown with unqualified table names.
- **Transactions are unchanged.** Placement changes only where tables are named. Every method that takes a `SqlClient` still runs on the client the caller passes, so workflow rows commit or roll back with the application's and the outbox's own writes.

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

In-flight instances are pinned to the definition version and plan hash recorded at start. They do not silently move to a newer definition. The pin includes the definition scope, and branch recovery resolves the plan of the pinned definition scope, id and version. A node whose registry holds no plan for the pin fails the sweep of that token with `WorkflowVersionNotRunnableException`: the transaction rolls back, the token stays `RETRY_SCHEDULED` for a node that has the code, and the instance is not failed.

The `WorkflowTransactionRunner` in this module delegates to `vertique-db-core`'s `TransactionBuilder`. Write paths run at the connection's default isolation (0 `SET TRANSACTION` statements); the `query()` read path uses `REPEATABLE READ` (1 statement, no `READ ONLY`).

Failures raised inside the transaction pass through a two-stage exception mapper. A DB-boundary stage owned by this module translates raw SQL/driver exceptions into `DataAccessException` subtypes, reusing the PostgreSQL SQL-state rules from `db-postgresql`; already-thrown workflow exceptions and other non-DB throwables pass through unchanged. A workflow-boundary stage owned by `vertique-workflow-engine` then translates those `DataAccessException` subtypes into the workflow exception hierarchy (for example `WorkflowConflictException` for a 409, `WorkflowPersistenceException` for a 500). See `dev.vertique:vertique-workflow-engine` for the full translation table.

---

## Schema Overview

The module owns workflow persistence tables, including:

| Table | Purpose |
|---|---|
| `workflow_instances` | Current snapshot of each workflow instance; includes `metadata JSONB` for the durable context captured once at start |
| `workflow_history` | Append-only engine history |
| `workflow_dedup` | Idempotency and signal deduplication, keyed by `(kind, scope_key, dedup_namespace, key)` |
| `workflow_timers` | Durable timer state; includes `metadata JSONB` for durable context. This row is the authoritative source of timer status. A timer recorded by a segment links to its `TIMER` command by `command_id` and names no delayed job; `vertique-workflow-delayed` reconciles only the rows of earlier releases, which name a delayed job and no command. |
| `workflow_tasks` | Human task rows |
| `workflow_branch_tokens` | Active branch execution state; includes `metadata JSONB` for durable context |
| `workflow_join_states` | Fan-in state for fork/join workflows |
| `workflow_definitions` | Durable definition metadata and DOCUMENT source text, added by `V002__workflow_definitions_and_scopes.sql` (see [Definition Registry And Source Storage](#definition-registry-and-source-storage)) |
| `workflow_definition_activations` | The current version of a definition id per scope, added by the same script (see [Definition Registry And Source Storage](#definition-registry-and-source-storage)) |
| `workflow_commands` | The recorded command log per instance, added by `V003__recorded_execution.sql` (see [Recorded-execution schema](#recorded-execution-schema)) |
| `workflow_command_queues` | One row per instance holding the next command sequence and the open-signal counter, added by the same script |

The migration files live with the module under `db/migration/workflow`. The shipped `V1__create_workflow_tables.sql` contains the instance/runtime tables, including branch tokens and durable metadata. The additive `V002__workflow_definitions_and_scopes.sql` creates the definition registry and converts the released tables to scope keys:

- `workflow_definitions` is keyed by `(scope_key, definition_id, definition_version)`; it carries the logical `contract_name` and `state_type_name`, the `document_digest` column and `kind VARCHAR(16)`, `plan_hash VARCHAR(128)` and `name_binding JSONB NOT NULL DEFAULT '{}'`; a `CODE` row must be in the `default` scope.
- `workflow_definition_activations` is keyed by `(scope_key, definition_id)`, one record per scope and id, with `target_scope_key`, `definition_version` and `activated_at`. A check requires the target scope to be the scope itself or `default`, and `default` to name only itself; a foreign key from `(target_scope_key, definition_id, definition_version)` to `workflow_definitions` is `ON DELETE RESTRICT`, so a definition row that a record names cannot be deleted. No row means the scope follows the default scope.
- `workflow_instances` gains `scope_key` (the start scope) and `definition_scope_key` (the scope of the pinned definition version), and `workflow_tasks` gains `scope_key`, each `VARCHAR(255) NOT NULL DEFAULT 'default'`. Existing rows read `default`; the script rewrites no row.
- The business-key index `idx_workflow_instances_business_key` becomes unique per `(scope_key, definition_id, business_key)` where the business key is not null, and `idx_workflow_instances_scope_updated_at` and `idx_workflow_tasks_scope_updated_at` serve scope-filtered keyset queries on `(scope_key, updated_at DESC, <id>)`.
- `workflow_dedup.scope` is renamed `dedup_namespace` (for start rows it still holds the definition id), a `scope_key` column is added, and the primary key becomes `(kind, scope_key, dedup_namespace, key)`. Stored fingerprints and namespaces are kept as they were.

### Recorded-execution schema

`V003__recorded_execution.sql` adds the schema for recorded execution. `start` writes the command log (a `PENDING` instance, its queue row and a `START` command), `signal` writes a `SIGNAL` command, a task completion writes a `COMPLETION` command and reserves its task, and the executor claims, parks, settles and completes commands; other operations do not write it yet (see [Executor commands](#executor-commands) and [Execution probe](#execution-probe)).

- `workflow_commands` has a generated identity `id`, `workflow_id` (a foreign key to `workflow_instances(id)` with `ON DELETE CASCADE`), `seq`, `kind`, `state`, the timer, task and target columns, the claim and lease columns, the attribution columns, the durable context columns, `attempts`, `transient_count`, `deferral_count`, `available_at`, `settled_at` and `last_error TEXT`. `(workflow_id, seq)` is unique. `state` admits `PENDING`, `DEFERRED`, `CLAIMED`, `PARKED`, `DEAD`, `COMPLETE`, `REJECTED`, `STALE`, `FAILED`, `SUPERSEDED` and `CANCELLED`; `kind` admits `START`, `SIGNAL`, `COMPLETION`, `RETRY`, `MIGRATION` and `TIMER`; `timer_kind` is set exactly when `kind` is `TIMER`. Three partial indexes serve the active states (`PENDING`, `DEFERRED`, `CLAIMED`) by `(workflow_id, seq)` and by `available_at`, and the `PARKED` and `DEAD` states by `workflow_id`.
- `workflow_command_queues` is keyed by `workflow_id` (foreign key with `ON DELETE CASCADE`) and holds `next_seq BIGINT NOT NULL` and `open_signals INT NOT NULL DEFAULT 0`. Deleting an instance deletes its commands and queue row. `open_signals` counts the instance's `SIGNAL` commands that are `PENDING`, `DEFERRED`, `CLAIMED` or `PARKED`: recording runs `UPDATE ... SET open_signals = open_signals + 1 WHERE workflow_id = $1 AND open_signals < $cap` (after `INSERT ... ON CONFLICT DO NOTHING` creates the row of an instance from before the command log), and every repository write that settles a `SIGNAL` into `COMPLETE`, `REJECTED`, `STALE`, `FAILED` or `DEAD` lowers it in the same statement. Moves between open states, including parking and the guarded `PARKED` to `PENDING` re-queue, leave it unchanged. The `SIGNAL` command's `target_json` holds `{"name": ..., "forkStepId": ..., "branchId": ...}` (the last two only for a branch-addressed signal) and `explicit_carrier_json` the sanitized explicit carrier.
- An `AFTER INSERT OR UPDATE OF state, available_at` trigger on `workflow_commands` calls `pg_notify` on the channel `vertique_workflow_commands` when the new state is `PENDING` or `DEFERRED`. The payload is `available_at` in epoch milliseconds and names no instance; PostgreSQL delivers it at commit.
- `workflow_instances.state_json` and `current_step_id` become nullable (`status` has no check, so a `PENDING` instance needs no further change), and `baseline_logged BOOLEAN NOT NULL DEFAULT TRUE` is added.
- `workflow_history` gains fourteen nullable columns (`actor_kind`, `actor_id`, `actor_reason`, `on_behalf_of`, `attribution_status`, `executor_id`, `command_id`, `step_id`, `outcome`, `definition_scope_key`, `definition_id`, `definition_version`, `plan_hash`, `state_version_before`, `state_version_after`, `patch_json`) and `entry_version SMALLINT NOT NULL DEFAULT 1`; existing rows keep nulls and version 1.
- `workflow_definitions` gains nullable `signal_names_json` and `start_schema_json`. The definition store writes them in the same statement as the plan hash (code insert, code rehash, document insert) when the node binds the schema producer (`WorkflowSchemaModule`), leaves them unchanged for an unchanged hash, and fills a row that has both null with `fillSignalSchemas`, guarded by `signal_names_json IS NULL` and the expected plan hash. A node without the producer writes both as null. Recording a signal reads `signal_names_json` of the instance's pinned row in the caller's transaction (`PgWorkflowCommandRepository.declaredSignalNames`); a null column admits every name.
- `workflow_tasks.status` admits `COMPLETING` (the check `workflow_tasks_status_check` is replaced), and `reservation_command_id BIGINT` and `require_version_stability BOOLEAN` are added, both nullable; existing tasks read null. A task being completed is `COMPLETING` and `reservation_command_id` names its `COMPLETION` command (whose `task_id` and `target_json`, holding the decision, the reviewed subject version and the completing actor, say what it completes); a new task writes `require_version_stability` from its plan and the payload schema of each decision into `decisions_snapshot_json`.
- `workflow_timers.delayed_job_execution_id` becomes nullable and `command_id BIGINT` is added.

The command log is written and read by `PgWorkflowCommandRepository`: the execution probe reads it ('Execution probe' below), a recorded start inserts its `START` command, and the executor claims and completes commands ('Executor commands' below). The same repository takes the per-instance execution lock and allocates positions in `workflow_command_queues` ('Lock-Order Requirements' below). The stores read these columns: an instance row with `status = 'PENDING'` and null `state_json` and `current_step_id` reads as a `WorkflowInstance` (it is not terminal); a `COMPLETING` task reads back with its `reservationCommandId`, `reassign` refuses it as if it were already completed and `markCompleted` completes it (it also still completes an `OPEN` task, for callers of the `TaskStore` SPI; the engine completes only a reserved task); `insertOpen` writes `require_version_stability` from `TaskRecord.requireVersionStability()`, and a row reads it back as `null` when the column is null; the decisions snapshot stores `payloadSchema` per decision only when present and reads it back as null when absent; `PgTimerStore` writes and reads `command_id` and `delayed_job_execution_id`, which is null for a timer linked to a command, and its `markCancelled` also sets the linked command `CANCELLED` (clearing its claim) when that command is still unsettled, in the same statement, while `findRecoverableScheduled` returns only the rows of earlier releases: scheduled, naming a delayed job, linked to no command. `PgWorkflowInstanceRepository` writes `PENDING` for a recorded start (and counts it as a live pin, as does the registry store's retirement count); `PgTaskReservations` writes `COMPLETING` and its reopening, and `PgWorkflowCommandRepository.recordTimer` writes a timer row and its `TIMER` command together ('Timer commands' below). Every optimistic update of an instance also writes its subject columns, because the first segment of a start resolves the subject.

The script sets `SET LOCAL lock_timeout = '30s'` and takes `ACCESS EXCLUSIVE` locks for the `ALTER`s until the migration commits; every statement is `IF [NOT] EXISTS` or drops before it creates, so a failed run can be repeated. Stop every node of the previous release before the first node of this release applies it. Existing rows keep their values.

Each `ALTER TABLE`, the index rebuild on `workflow_instances` and the primary-key rebuild on `workflow_dedup` take an `ACCESS EXCLUSIVE` lock on their table until the migration commits, and each index build is a sequential scan of its table under that lock, so the blocked time grows with table size. The script bounds its wait for each lock with `SET LOCAL lock_timeout = '30s'`: run it when no long transaction (a retention sweep, a long read) is open. While a statement waits for its lock, new statements on that table queue behind it for up to the timeout, per table, and then the migration fails and can be rerun; a manual run must be one transaction (`psql --single-transaction`), or the timeout has no effect. The column and index statements are `IF NOT EXISTS`, so an operator of a very large installation can add the scope columns on tasks and then instances and build the two scope keyset indexes with the exact statements of the migration as `CREATE INDEX CONCURRENTLY IF NOT EXISTS` on an autocommit connection ahead of the upgrade (set `lock_timeout` on that session too; a statement that times out changes nothing and can be rerun), and the migration then builds nothing for them. The business-key index is always dropped and rebuilt by the migration, and the `workflow_dedup` rename and primary-key change always run inside it: `ALTER TABLE ... RENAME COLUMN` has no `IF EXISTS` form, so do not run it ahead of time. Both `IF NOT EXISTS` forms match by name only, so use the exact statements; a failed concurrent build leaves an `INVALID` index that `IF NOT EXISTS` skips, so check `pg_index.indisvalid` and drop it first. `V1__` is unchanged. A development database that applied an earlier snapshot of this release must be recreated, because Flyway validation fails on the changed checksum and description of version 2.

**Upgrade from 0.3.0.** Stop all nodes that run 0.3.0 before the first 0.4.0 node starts and applies the migration. The 0.3.0 dedup statements use `ON CONFLICT (kind, scope, key)`; the renamed column and the new primary key `(kind, scope_key, dedup_namespace, key)` leave that conflict target without a matching index, so a 0.3.0 node that is still running after the migration fails every start. Keeping the old column name would not help, because the primary key change alone breaks the target. Pre-applying statements ahead of the upgrade does not lift this rule: run them only after the last 0.3.0 node has stopped. Rolling-deploy guidance applies only from 0.4.0 on, and rolling code back to 0.3.0 against a migrated database is not supported. After the upgrade the instances of 0.3.0 resume in the default scope and complete their stored task decisions.

`PgWorkflowInstanceRepository.insert` writes `scope_key` and `definition_scope_key` from `WorkflowInstance.scopeKey()` and `definitionScopeKey()` and no update statement changes them. `PgTaskStore.insertOpen` writes the task's `scope_key` from the owning instance row (`SELECT scope_key FROM workflow_instances WHERE id = …`; the instances table is qualified like every other reference when a `workflow` schema is mapped) and ignores the value on the `TaskRecord`, so every task creation path inherits the start scope of its instance; the guarantee belongs to this store, not to the `TaskStore` SPI. `WorkflowInstanceQuery.scopeKey()` and `TaskFilter.scopeKey()` add an exact-match `scope_key = ?` condition when non-null and leave keyset order and archived handling unchanged; `null` matches every scope. The columns and the filters are information and a filter, not an isolation or authorization boundary: callers must not pass an unresolved scope as `null` expecting scoping. No operation addressed by instance or task id checks the scope either. Start dedup statements (`findStart`, `claimOrResolveStart`) and `findByBusinessKey` take the start scope and filter on `scope_key`; signal, task and dispatch dedup statements address the `default` scope. `PgWorkflowInstanceRepository.findDefinitionContract(key, tx)` reads `contract_name` from `workflow_definitions` through the caller's transaction, so a start replay on a node without the pinned plan takes no second pool connection.

---

## Definition Registry And Source Storage

The definition registry is built and tested against **PostgreSQL 15 or later**. `workflow_definitions` stores CODE metadata and DOCUMENT source text separately
from compiled plans. DOCUMENT source uses TEXT with kind/nullability constraints and no fixed database
size cap. The adapter enforces the shared `WorkflowDefinitionsConfig` policy at document boundaries.

Runtime document load writes a CANDIDATE. Publication uses a fresh UUID claim (`publish_token`, present exactly while the
status is `PUBLISHING`), local registration, then a fenced completion that makes the row `AVAILABLE`; obsolete completions fail and obsolete releases are no-ops. Every definition
mutation takes an id-derived transaction advisory lock followed by ordered locks on all rows of that
id. Claim ages use PostgreSQL wall clock after lock acquisition, with twice `maxStalenessSeconds`
(default 30 seconds) as the stale age. These locks serialize each store transaction, not the whole
cross-node compilation operation. Before every mutating lock acquisition, the store sets transaction-
local `lock_timeout` to `30000ms`; this bounds each advisory or row-lock wait independently. A timeout
rolls back the transaction, releases its locks, and clears the local setting when the connection is
reused. SQLSTATE `55P03` is surfaced as a retryable `WorkflowPersistenceException` with the exact
definition id in its context and the pessimistic-locking database failure preserved as its cause. The
store does not retry inside the aborted transaction.

The status column allows exactly `CANDIDATE`, `PUBLISHING`, `AVAILABLE`, `DEPRECATED` and `RETIRED`. No unique index limits
the available rows of a scope and id, so any number of versions and scopes of one id are `AVAILABLE` at once, and `complete`
changes only the row of its claim. Rows for the same id and version may coexist under different scope keys in every status, and
`list` returns them by optional scope, id and status set without reading document bodies. A claim, a completion and a
static document or code reconciliation look only at their own row; there is no per-id holder and no conflict between scopes.

`transition(key, from, to)` is a conditional status change under the id lock: it checks the row and the expected current status,
and updates only a row still in that status. The store accepts `AVAILABLE` to `DEPRECATED`, `DEPRECATED` to `AVAILABLE` and
`RETIRED` to `AVAILABLE` and no other pair; an unsupported pair, a missing row or a changed status fails with
`INVALID_TRANSITION`. A pair outside those three is refused before any statement is issued. The last two are the
reinstatement: the store does not check that an executable source exists or that its hash matches; the lifecycle does that
before it calls the store. Like every registry mutation (see `retire` below), the transaction runs at READ COMMITTED whatever the
server default.

`retire(key)` moves a CANDIDATE row, or a DEPRECATED row past the cooling window (`maxStalenessSeconds`, database
clock), to RETIRED. Every registry mutation runs at READ COMMITTED, whatever the server default, so the pin count sees
every row and pin committed while the mutation waited for the id lock. Under the id lock it checks the row, the status, and for a
DEPRECATED row the cooling age and then the exact-version count of PENDING, RUNNING, WAITING and COMPENSATING instances (archived
rows included), in that order; a CANDIDATE row is retired without the pin check because a candidate is not admitted for
pinning (a runtime registered directly for the same version is outside the guard). AVAILABLE rows cannot retire. The count scans `workflow_instances` by definition id and version while
retirement holds the exclusive id lock and no index covers that predicate, so on a very large live instance table a
retirement can delay new starts of that id. `PgWorkflowInstanceQueryService.countNonTerminal(DefinitionKey)` exposes the same
scope-aware informational count (`countNonTerminal(definitionId, version)` counts the default scope); it is not the
retirement guard. The count and the retirement guard include only instances pinned to the definition's own scope.

`upsertCode(record, activateDefault)` and `upsertDocument(record, document, activateDefault)` insert a missing row and, only when
the outcome is `INSERTED` and `activateDefault` is true, write `(default, id) -> (default, version)` into
`workflow_definition_activations` in the same transaction and under the same id lock: one statement inserts the record or
replaces it only when the stored version is lower, so concurrent first boots write one record, the highest version wins in any
order, and a failure of either statement rolls back the row as well. `UNCHANGED`, `REHASHED`, conflicts and drift write no
record, so a restart never activates and never undoes an operator's choice. Deploy a new version with `activateOnDeploy` false
while nodes running the previous code are still up, and activate it once every node runs the new code.

`insertCandidate` of a row in a scope other than the default one reads the locked rows of the id across every scope, after the advisory lock was granted, and refuses with `WorkflowDefinitionContractMismatchException` when the default scope holds the id, in any status, under another contract and the scope holds no row of the id; a default-scope insert is never refused for the rows of other scopes.

`putActivation`, `removeActivation` and `listActivations` maintain `workflow_definition_activations`. A write runs like every registry mutation, under the id lock at READ COMMITTED: `putActivation` requires the target row `(target_scope_key, id, version)` to exist and be `AVAILABLE` (a refusal is `INVALID_TRANSITION` naming the target key), refuses with `WorkflowDefinitionContractMismatchException` a scope's record that names a default-scope version of another contract than the scope's own rows of the id, and then inserts or replaces the scope's record; `removeActivation` deletes it and accepts an absent record. The default-current guard lives in `transition` and `retire`: in the same transaction and after the lock was granted, a default-scope row is refused with `INVALID_TRANSITION` while the default scope's record of the id names its version, so a concurrent activation and deprecation serialize on the lock and the record never ends on a deprecated target. Rows of other scopes are not guarded. Delete a record with `removeActivation` (or `deactivateDefault` through the lifecycle) before deleting the definition rows it names, and note that tests or tools that delete definition rows directly must remove the id's records first because of the restricting foreign key.

Activation records reach the other nodes of the same database through the engine's propagation pass: each pass reads
`listActivations(null)` once, and the cadence is `workflow.definitions.maxStalenessSeconds`, so an `activate`, `follow` or
`deactivateDefault` committed on one node changes which version a start selects on the others within that bound plus the apply
time. Records are durable and restored into the node's view at startup; a start never reads `workflow_definition_activations`.

Registry refusal messages can name other scopes and plan hashes: do not forward them to end users.

`migratePinAndState` writes the definition scope, the definition version and the plan hash of the re-pinned instance, so a migration to another scope's definition changes the stored definition scope. Run the cross-scope migration with `vertique-workflow-schema` installed when the two definitions use different state classes.

Pin admission: before `insert` creates an instance pin, `migratePinAndState` changes one, or `updateOptimistic`
resurrects a terminal instance (FAILED retry or FAILED to COMPENSATING), the repository takes
`pg_advisory_xact_lock_shared` on the definition id in its own statement, then reads the id's definition rows in a second
statement. Starts share the lock, so they do not serialize each other, but a queued deprecate or retire waits for them and
PostgreSQL queues later admissions behind that waiting writer, so long caller transactions can delay lifecycle and startup
mutations. The caller's pin transaction must therefore end before it awaits any store-owned lifecycle call for the same
id. At READ COMMITTED an id with no definition rows is admitted (a definition registered only by direct runtime registration after startup, or an engine built without the registry bootstrap, has none); otherwise the row of the pin's exact definition scope, id and version must exist with the plan hash and be `AVAILABLE`, and a resurrection also accepts `DEPRECATED`; the rows of other scopes and versions of the id play no part, so a plan hash of another scope's row is a drift against this scope's row. The pin is the instance's definition scope, definition id and definition version, so an instance pinned to a definition scope is admitted only against the row of that scope.
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

`failure_reason` (`VARCHAR(64)`, nullable, no check constraint) holds the name of the rule a failing scope row breaks,
`STATE_TYPE_NOT_EXTENDED` or `NAME_BINDING_UNRESOLVED`, and is null for every other row. Only `recordFailure(key, failure)`
writes it, in one `UPDATE` under the definition id lock that only matches a DOCUMENT row of a scope other than `default` (a default scope row or a CODE row is left untouched), and a startup or a reconciliation pass that finds no conflict clears it. `find` and
`list` read it into `WorkflowDefinitionRecord.failure()` with the rule's fixed description as the detail; a reason this
release does not know reads as no failure. A node refuses starts from its own startup evaluation, not from this column, so
nodes that ship different code can rewrite it during a rolling deploy.

The stored plan hash covers the compiled plan structure, not callback bodies. `name_binding` holds, as a JSON
object, the binary name of each state and payload class the plan names, mapped to the name the hash used. The
adapter writes it in the same statement as the plan hash and never alone: when a version is first recorded (a code
row, or a document candidate) and when a changed code hash replaces the stored one. Publication, deprecation,
retirement, reinstatement and propagation leave it as it is. Every start hashes the version with the stored
binding, so annotating a class after the version was published keeps the hash of that version, and a version first
published afterwards records the logical name. A class that a non-retired version is bound to by its binary name
cannot move until those versions are retired; startup fails naming the version and the recorded name. A version that
instances of an earlier release started under is recorded by the first start of this release with the names its
classes have then, so upgrade before you annotate a class those instances use.
Annotate contracts and types before moving them. The `document_digest` column holds
the lowercase hex SHA-256 of the stored UTF-8 text (present exactly on
DOCUMENT rows). The adapter writes it with every document, treats an equal plan hash with a different text
digest as drift when a packaged document is reconciled, and verifies it on every guarded source read (after the
size-envelope check): altered stored text is never returned and fails as drift. This detects accidental or
concurrent change of stored text; it does not authenticate the writer, so restrict write access to the table.
Any other writer of DOCUMENT rows must supply the digest. On first boot after upgrading, every application that uses the PostgreSQL module writes a global CODE row per code-first definition even if it never touches documents; the `V002__` migration must have run.

With a durable registry store bound (the PostgreSQL module binds one), the engine records every code definition, and with `WorkflowDefinitionModule` installed also the packaged documents, and restores available documents of every scope after
Flyway migration, and refreshes verified definition-cache entries
from committed metadata before BOOTSTRAP verticle deployment. Every node sharing live document rows must
carry their loaders and callback/type catalogs. Source locations are attribution, never remote fetch
instructions. Configure the same policy on every node; raising the input limit increases memory and
parse cost, and lowering it below required stored sources makes startup fail. The stored envelope is
floor(3L/2) UTF-8 bytes and L code points for configured raw input limit L (default 262144 bytes).

**Placement.** The migration script is unqualified, so the migration instance's default schema decides where the nine tables and their indexes land. In named Flyway mode with a `db.schemas.workflow` entry, that schema is the default, and the stores qualify every table with it, so both agree. In single mode the migration lands where the single-run configuration says (see [Flyway Configuration](#flyway-configuration)). `V003__recorded_execution.sql` also creates a trigger function and a trigger on `workflow_commands`, and the identity column of that table creates a sequence; they land in the same default schema as the tables. The module creates no views. The trigger notifies on the unqualified channel `vertique_workflow_commands`, which is database-wide, not per schema.

---

## Flyway Configuration

The workflow migration is declared once, by `WorkflowMigrationsModule`, and can be run two ways. The declaration is metadata only: it names the `workflow` instance and the location `classpath:db/migration/workflow`, has no dependencies and needs no connection pool, so a migration-only component can include it on its own. `WorkflowPostgresqlModule` includes it, so an application that wires the workflow stores contributes the declaration automatically.

### Named instances

Give the workflow tables their own schema and history table:

```json
{
  "db": {
    "schemas": { "workflow": "workflow_data" }
  },
  "flyway": {
    "mode": "MIGRATE",
    "named": true,
    "instances": { "workflow": {} }
  }
}
```

`"named": true` turns on named mode; `instances` only carries per-instance overrides and may be omitted (`"named": true` alone runs every wired declaration with the root settings). `instances` without `"named": true` is a configuration error.

- **One setting places everything.** `db.schemas.workflow` is the instance's default schema and history schema, and the nine stores qualify every workflow table with it, so the migration and every statement use one schema. The tables and indexes land there, and the history table is `flyway_schema_history_workflow` in that schema.
- **Run it through the named runner.** A migration component installs `DbModule`, `DbFlywayModule` and `WorkflowMigrationsModule` (plus the configuration and Vert.x modules the application already uses), and calls `FlywayMigrationRunner.run(vertx)`; `FlywayMigrationStartupStep` runs the same engine at startup. `dev.vertique:vertique-db-flyway` documents `instances()`, `run(Vertx)`, reports and failures.
- **Without a `db.schemas.workflow` entry** the migration uses the first entry of the instance's Flyway `schemas` list, or the connection's default schema, and the stores stay unqualified. Configure the migration and runtime connections to the same default schema.
- **Include `DbModule` in the migration component** (or bind `DbSchemas` yourself). A named migration component without either ignores `db.schemas`, and the tables can land in a different schema than a runtime component with `DbModule` queries.
- **The application's own migrations** are a separate instance with a disjoint location such as `classpath:db/migration/app`. A broad `classpath:db/migration` location overlaps `classpath:db/migration/workflow` and is rejected.
- **A dedicated DDL user** is set with `flyway.user` and `flyway.password` (both, or neither), or per instance with `flyway.instances.workflow.user` and `flyway.instances.workflow.password`. Without them the migration uses the `db` pool credentials.

`dev.vertique:vertique-db-flyway` documents the full instance configuration, the agreement rules between `db.schemas` and Flyway schema lists, and the failure model.

### Running the migration without the application

The migration does not need the application to start. A migration-only component is an ordinary Dagger component that:

- installs `DbModule`, `DbFlywayModule`, `WorkflowMigrationsModule` and the configuration module, together with the declaration modules of every other instance the deployment migrates (for example the job and inbox/outbox migration modules, and a module contributing the application's own `MigrationDefinition`);
- binds the caller's `Vertx` and the application configuration, and exposes `FlywayMigrationRunner`;
- installs no `DbPostgresqlModule`, no `WorkflowPostgresqlModule` and no worker or HTTP modules, so no connection pool, HTTP server or worker deployment is ever created.

A one-off deploy step or job builds that component, calls `FlywayMigrationRunner.run(vertx)` and reads the `MigrationReport`; the runtime replicas then start in `VALIDATE` or `DISABLED` mode against the migrated schemas. The runner uses the `Vertx` instance it is given for blocking work only and never closes it, so the caller keeps using it afterwards. `dev.vertique:vertique-db-flyway` documents the concurrent-start behavior of the runner and the `lockRetryCount` setting.

**Class loader.** The runner captures the calling thread's context class loader when `run` or `migrate` is called (its own class loader when the thread has none), and resolves packaged scripts and application locations through that loader, not through the ambient loader of the Vert.x worker thread that executes the migration. Call it from a thread whose context class loader can see the migration scripts, normally the application class path. Instance locations are independent per directory prefix: `classpath:db/migration/job` and a sibling such as `classpath:db/migration/job-v2` never read each other's scripts.

### Single Flyway configuration

Without `flyway.named`, the default `classpath:db/migration` location already includes the bundled `db/migration/workflow` scripts, so no extra location is needed. Every bundled adapter ships its own `V1` script, so one history can hold only one of them (a second would fail with a duplicate version); to run several adapters, set `"named": true`. Wired migration declarations are ignored in a single run, so adding another adapter module never switches the mode. The single-history example:

```json
{
  "flyway": {
    "mode": "MIGRATE",
    "user": "ddl_user",
    "password": "ddl_secret"
  }
}
```

If you override `locations`, list the workflow directory next to an application directory that does not contain it, for example `["classpath:db/migration/workflow", "classpath:db/app"]`. Do not list both `classpath:db/migration` and `classpath:db/migration/workflow`: the first already covers the second.

In this mode the migration lands where the single-run configuration says, and nothing checks it against `db.schemas`. If `db.schemas.workflow` is also set, the stores query that schema, so make the `flyway.schemas` list (first entry) or the connection default match it, or the stores will look in a schema the migration never filled.

### Runtime placement

- **The stores read placement from `DbSchemas`.** A runtime component that installs `DbModule` gets it from `db.schemas`, with no Flyway module or Flyway configuration. A component that installs neither `DbModule` nor its own `DbSchemas` binding keeps unqualified SQL.
- **Check the keys yourself.** `db.schemas` keys are open and are not validated, so a misspelled key (`workflows` for `workflow`) leaves the placement unmapped and the SQL unqualified, with no error. Compare `DbSchemas.mappings()` with the instance names before deploying.
- **The runtime database role needs `USAGE` on the mapped schema**, besides the usual privileges on the tables.
- **An invalid mapping fails at construction.** A mapped schema that is not a single valid PostgreSQL identifier throws `IllegalArgumentException` when a store is created, not on the first query.
- **Your own tables.** An application can inject `DbSchemas` and place its own tables with the same mapping mechanism; see `dev.vertique:vertique-db-core` (`DbSchemas`) and `PgTableNames` in `dev.vertique:vertique-db-postgresql`.
- **One source of `DbSchemas`.** A component that binds `DbSchemas` itself cannot also install `DbModule`: Dagger reports a duplicate binding at compile time, with no silent precedence.

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
    DbModule.class,                              // supplies DbPoolConfig and DbSchemas (from db.schemas)
    DbPostgresqlModule.class,
    DbFlywayModule.class,                        // only to run the migration at startup
    AppModule.class
})
public interface AppComponent {
    WorkflowOperations workflowOperations();
    WorkflowInstanceQueryService workflowInstanceQueryService();
}
```

The runtime needs `DbPostgresqlModule` (the `Pool` and the PostgreSQL failure mapper) and `DbModule` (the pool configuration and the optional `db.schemas` placement). `DbFlywayModule` is needed only when the application runs the migration itself at startup; a runtime that migrates elsewhere installs no Flyway module and no Flyway configuration.

Add optional modules based on behavior:

| Behavior | Add |
|---|---|
| Timer steps, signal timeouts, task due dates and reminders | Nothing to add: they are `TIMER` commands that the executor runs (`WorkflowDelayedModule` only recovers the timers of earlier releases) |
| Human task service API | `WorkflowTasksModule` |
| External workflow event stream | `WorkflowEventsModule` |
| YAML/JSON definition loading | `WorkflowDefinitionModule` |
| In-flight definition migration | `WorkflowMigrationModule` (from `dev.vertique:vertique-workflow-core`; not the schema-migration declaration `WorkflowMigrationsModule`, which this module already includes) |

### Executor commands

`PgWorkflowCommandRepository` is the executor's side of the command log; each statement composes its eligibility from `CommandEligibilitySql`:

- **Record** (`recordStart`, in the caller's transaction): allocates the next queue position and inserts a `START` command in state `PENDING`, with `recorded_at` and `available_at` read from the database clock by the insert itself, the start payload stored as `payload_json`, the idempotency key as `dedup_key`, and a caller-supplied subject reference as `target_json`. Every command insert also stores, as `context_json`, the durable context the recording caller has bound (`DurableContextPropagator.mergeCaptured` for the `WORKFLOW` boundary, encoded for the command row's carrier descriptor: target kind `workflow-command`, the instance id as address and the queue position `seq` as carrier id) without the identity-snapshot namespace, in the `{"context": {...}}` wrapper; the column is `NULL` when the capture is empty or the repository was built without a propagator.
- **Record a signal** (`declaredSignalNames`, `raiseOpenSignals`, `recordSignal`, in the caller's transaction): reads the pinned row's `signal_names_json`, raises the instance's `open_signals` count (creating the queue row when missing, then the conditional update), and allocates the next queue position and inserts a `SIGNAL` command in state `PENDING` with the same clock reads as a start, the payload as `payload_json`, the dedup key as `dedup_key`, the name and address as `target_json` and the sanitized explicit carrier as `explicit_carrier_json` (same `{"context": {...}}` wrapper); the context capture is stored as for a start. The fence and the parked-command read return both `explicit_carrier_json` and `context_json` to the segment, which binds them as described in the engine module reference.
- **Peek**: a pooled read without locks of the claimable heads (joined with their instance's pin), oldest availability first.
- **Claim**: one transaction of its own: `pg_try_advisory_xact_lock` on the instance, then a locking read (`FOR UPDATE`) of the command that checks `claimable` again and joins the instance's status. A busy lock or a command that is no longer claimable writes nothing. Otherwise, in this order: an instance that has ended (`COMPLETED`, `COMPENSATED`, `CANCELLED`, `EXPIRED`) settles the command `REJECTED` (`STALE` with a `COMMAND_STALE` entry for a branch-addressed signal) with the token cleared, `last_error` set to an `INSTANCE_ENDED` reason and a `COMMAND_REJECTED` history entry, spends no attempt and never dead-letters, and leaves the instance as it is; else a command with `attempts` at or above the configured `maxAttempts` becomes `DEAD` with the token cleared, its attempts unchanged, an `ATTEMPTS_EXHAUSTED` reason and a `COMMAND_DEAD_LETTERED` history entry, and a `START` also sets its instance `FAILED` (`error_type` the reason code, `completed_at` now) with a `FAILED` history entry; else one `UPDATE` sets `CLAIMED`, `attempts + 1`, a new `gen_random_uuid()` token, `lease_until` and `claimed_by`. `claimed_by` is kept after completion. The peek reads whether the instance has ended, so that such a command is claimed without making a plan resident.
- **Defer** (`defer`): one transaction of its own for a head the node cannot run: `pg_try_advisory_xact_lock` on the instance, then a locking read (`FOR UPDATE`) of the command that checks `claimable` (which includes being available now, so a command already deferred into the future fails it), and that the instance's definition scope, id, version and plan hash equal the head's. A busy lock or a failed check writes nothing and returns `0`. Otherwise one `UPDATE` sets `DEFERRED`, `deferral_count + 1`, `available_at` the database clock plus `min(5 s * 2^count, 5 min)` computed from the count before the increment, and a `NOT_RUNNABLE` reason in `last_error`; attempts, token and lease are untouched. When the count goes from 0 to 1 it also appends one `COMMAND_DEFERRED` history entry whose payload holds the command id, sequence, kind and reason, never the command payload. It returns the new count.
- **Park, settle and re-queue signals** (in the segment's transaction): `park` sets a claimed command `PARKED` with the token and lease cleared and the attempt its claim spent restored, fenced on the claim; `reject(..., lastError)` and `stale` settle it with a reason; `settleParked` moves a `PARKED` command to `COMPLETE`, `REJECTED` or `STALE` with no token; `requeueParked` moves a `PARKED` command back to `PENDING` with its `seq`, id, attempts and counters and `available_at` no later than now, which wakes executors through the trigger. `deadlineBlocked` reads the `TIMER` commands of `timer_kind` `TIMEOUT` whose `target_json` names the wait (`stepId` and, for a branch wait, `branchId`).
- **Timer commands**: `recordTimer(spec, tx)` takes the next position of the instance's queue and inserts, in the caller's transaction, a `TIMER` command (`timer_kind` `FIRE`, `TIMEOUT`, `DUE` or `REMINDER` from the timer's purpose; `timer_id`; `task_id` for a due date or reminder; state `PENDING`) whose `recorded_at` and `fire_at` are read from `clock_timestamp()` in the insert and whose `available_at` equals `fire_at` (a delay counts from the recording, an instant already past is taken as the recording time), and then the `workflow_timers` row with `command_id` set, the same fire time and `metadata` signed for `WorkflowTimerCarriers`. A `TIMEOUT` command's `target_json` is `{stepId, forkStepId?, branchId?, signalNames}`. `fence` returns the command's `timer_kind`, `timer_id` and `task_id` in the `SegmentCommand`. A claim that finds the instance ended settles a `TIMER` command `STALE` (history `COMMAND_STALE`), not `REJECTED`.
- **Fence, complete, reject** (in the segment's transaction): the fence locks the row `FOR UPDATE` and checks `CLAIMED`, the claim's token and `atHead`; completion sets `COMPLETE`, clears the token and lease and stamps `settled_at`, fenced on the same state and token, and returns the row count.
- **Release**: one pooled statement that takes no lock and returns a claim to `PENDING` with its attempt restored, only while the command is still `CLAIMED` with the claim's token.
- **Transient retry** (`retryTransient`): one transaction that locks only the command row (`FOR UPDATE`, `CLAIMED` with the claim's token) and returns it to `PENDING` with the attempt restored, `transient_count + 1`, `available_at` the database clock plus the backoff of the new count, the token and lease cleared and a `TRANSIENT_FAILURE` reason in `last_error`. It takes no execution lock.
- **Failure disposition** (`failCommand`): one transaction of its own that tries the execution lock (a busy lock answers `BUSY` and writes nothing, so the executor retries without holding a connection), locks the command row under the same token fence (`CLAIM_LOST` and nothing written when the command is no longer `CLAIMED` with it) and settles it `FAILED` with the token cleared, `settled_at` and the sanitized reason; the instance becomes `FAILED` (`error_type` the reason code, `error_message` the same text, `completed_at` now) with a `FAILED` history entry (`stepId` null) unless it has already ended or failed; the repository then calls the engine's failure listener inside the same transaction, which emits `WORKFLOW_FAILED`. The dead-letter branch of the claim does the same for a `START`.
- **Last error**: every writer stores `<REASON_CODE>: <message>` built by the engine's `CommandErrors` (at most 4000 characters, no exception text, class names, SQL or payload). The probe additionally cuts the column to 4000 characters when it reads.

A segment runs through `PgWorkflowTransactionRunner`'s deadline overload, so the work's client is the `vertique-db` deadline-guarded connection. It is a `SqlConnection` that delegates `transaction()`, so the engine's check of a pending Vert.x transaction accepts it, and it refuses every statement executed after the deadline. `prepare()`, `begin()` and `close()` always fail on it, so `Query.stream(...)` is unavailable in a segment.

### Executor wake-up

A command that becomes `PENDING` or `DEFERRED` wakes the node's executor instances at commit, through the `pg_notify` trigger
on the channel `vertique_workflow_commands` (payload: `available_at` in epoch milliseconds, no instance id). The node has one
`CommandNotificationListener` (public, `dev.vertique.workflow.postgresql.executor`), shared by every `workflow-executor`
instance of the node, whether deployed by the deployer or started with `WorkflowExecutorControl.start()`:

- **One connection per node.** The first instance to start opens a dedicated connection outside the pool with the pool's
  `PgConnectOptions` (TLS and credentials), issues `LISTEN vertique_workflow_commands` and nothing else, and never begins a
  transaction. The last instance to stop closes it. The channel is database-wide: an instance may be woken for a command another
  node or engine owns, peeks and finds nothing.
- **The payload is a hint.** Only digits are read, as the command's available time; anything else wakes the instances to peek
  now. Each registered instance is woken with `runOnContext` on its own verticle context, never on the connection's context.
- **Scheduling is separate from reports.** The executor reads `findEarliestAvailabilityDelayMs()` through
  `PgCommandWakeupQueries`, which samples the database clock once and returns the nonnegative delay to the earliest future
  `PENDING` or `DEFERRED` availability. The executor schedules that delay on a monotonic timer; it does not compare the
  notification timestamp with the JVM clock. The `findNotYetAvailable` read remains a bounded diagnostic list for
  `IdleReport` and is not used to schedule the executor.
- **Loss degrades to polling.** A connection that cannot be opened or is lost leaves the instances polling every
  `pollIntervalMs`. The listener logs one warning for the outage, with the exception class only and no connection details, and
  tries again once per `pollIntervalMs`. There is no liveness check and no restart logic.
- **Queue usage.** While at least one `WorkflowExecutorObserver` is bound, the listener reads `pg_notification_queue_usage()` on
  a pooled connection every five seconds, outside any transaction, and reports it as `QueueSnapshot.notificationQueueUsage`
  (a value from 0 to 1). The other fields of that snapshot are zero; the executor instances report the queue counts. A usage
  near 1 means a session that listens is not consuming notifications, and new notifications then fail at commit.
- **Factory-built engines** construct the listener with the Vert.x instance, the pool's connect options, the pool, the observers
  and the two intervals, and pass it to `WorkflowEngineParts.Builder.commandWakeups`.

### Execution probe

`WorkflowExecutionProbe` completes when an instance (or every instance) has no command in flight and none an executor could claim now. `PgWorkflowCommandRepository` answers it with plain pooled reads of `workflow_commands`: it opens no transaction on a caller's connection and takes no lock, and it judges time with the database clock (`now()`). `CommandEligibilitySql` is the one SQL rendering of the eligibility rule, composed by every reader: a command is claimable when it is at the head of its instance's queue (no earlier `PENDING`, `DEFERRED` or `CLAIMED` command blocks, where a timer before its `fire_at` and a held-back timer block nothing), is not held back, is available, and is `PENDING`, `DEFERRED`, or `CLAIMED` under an expired lease. `PARKED`, `DEAD` and settled commands block nothing. A due-date or reminder timer is held back while its task is `COMPLETING`, including under a dead-lettered completion. A timeout timer is held back while a `SIGNAL` or `COMPLETION` for its wait, recorded before the timeout's `fire_at`, is `PENDING`, `DEFERRED` or `CLAIMED`: the wait is read from the timeout command's `target_json` (the signal names the wait accepts, its `branchId` and `forkStepId`) and `task_id`, and a signal targets it when its name is among the accepted names and its branch address is the same; a parked or dead-lettered signal holds nothing back. A `CLAIMED` command under a live lease counts as in flight. The report lists `DEFERRED` commands that are not claimable, `PARKED` commands, and the other `PENDING` and `CLAIMED` commands that are not claimable, ordered by instance id then sequence; `stuck` lists every `DEAD` command and every `PENDING`, `DEFERRED`, `CLAIMED` or `PARKED` command whose first availability (`fire_at` for a timer, `recorded_at` otherwise, never the moving `available_at`) is older than `workflow.executor.stuckAfterSeconds`; a command may be in `stuck` and in its state list. Each list holds at most 1000 commands.

Tests that run no engine build a probe from a pool with `PgExecutionProbeSupport.probe(pool, vertx)` (test sources of this module).

---

## Module Dagger Bindings

`WorkflowPostgresqlModule` includes `WorkflowEngineModule`, `LoggingContextModule`, `WorkflowMigrationsModule` (the `workflow` migration declaration) and `DbSchemasModule` (the optional `DbSchemas` binding), and provides:

| Binding | Scope | Description |
|---|---|---|
| `PgWorkflowInstanceRepository`, `PgWorkflowHistoryRepository`, `PgWorkflowDedupRepository`, `PgBranchTokenRepository`, `PgJoinStateRepository`, `PgTaskStore`, `PgTimerStore`, `PgWorkflowDefinitionRegistryStore`, `PgWorkflowRetentionRepository` | Singleton | Nine explicit providers, each built from `Optional<DbSchemas>`: placed in the schema mapped to `workflow` when the component supplies `DbSchemas` (a component that installs `DbModule` gets it from `db.schemas.workflow`), unqualified when it does not |
| The five repository SPIs, `TaskStore<SqlClient>`, `TimerStore<SqlClient>` | Singleton | Aliases of the concrete instances above, so a concrete lookup and an SPI lookup return the same instance |
| `PgTaskReservations`, `TaskReservations<SqlClient>` | Singleton | The task reservations, built from the `Pool`, the failure mapper and `Optional<DbSchemas>` like the stores, and bound as the engine's `TaskReservations` SPI |
| `WorkflowTransactionRunner<SqlClient>` | Singleton | The PostgreSQL transaction seam |
| `CommandWakeups` | Singleton | The node's `CommandNotificationListener` (see [Executor wake-up](#executor-wake-up)), built from the optional `Vertx`, the optional `PgConnectOptions` that `DbPostgresqlModule` supplies, the `Pool`, the `Set<WorkflowExecutorObserver>` and the optional `WorkflowExecutorConfig` (its `pollIntervalMs` is the reconnect interval); a graph without `Vertx` or connect options binds a source that never wakes anyone, and its executors poll |
| `PgWorkflowCommandRepository`, `WorkflowCommandRepository<SqlClient>` | Singleton | The command repository (reads, execution lock, queue sequence), built from `Optional<DbSchemas>` like the stores and from the optional `WorkflowRecordingConfig` (default lock timeout 5000 ms) and from the `DurableContextPropagator` that captures the context stored with each recorded command; the engine module builds the `WorkflowExecutionProbe` and the executor from it |
| `WorkflowInstanceQueryService`, `WorkflowRetentionService` | Singleton | Services over the same scoped instances (as is the branch query service), so queries, archive and purge follow the placement |
| `Set<MigrationDefinition>` | `@IntoSet` | The `workflow` declaration from `WorkflowMigrationsModule`: location `classpath:db/migration/workflow`, no dependencies |

The branch-recovery cron installed by `WorkflowBranchRecoveryModule` reaches the same scoped repositories, so recovery sweeps follow the placement too.

**Compatibility note.** The nine concrete store types are now provided by explicit `@Provides` methods. A component that installs this module and also provides any of them itself sees a duplicate binding; remove its own provider. The `@Inject` constructors remain for components that do not install this module; they leave the SQL unqualified.

---

## Idempotency And Deduplication

Workflow operations require caller-supplied idempotency or dedup keys where duplicate delivery is possible:

| Operation | Scope | Key |
|---|---|---|
| Start | Start scope and definition id | Start idempotency key |
| Signal | Workflow instance | Signal dedup key |
| Task completion | Task id | Task completion idempotency key; a committed duplicate is looked up without a lock and returns its earlier result before any payload check |
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
- Timers are commands of the executor and survive restarts with the command log; timers of earlier releases are recovered by `workflow-delayed` when that module is installed.
- Outbox relay retries may redeliver messages; workflow signal and task APIs are designed for idempotent callers.

The engine does not depend on in-memory workflow state for correctness.

---

## Lock-Order Requirements

Operations that touch multiple workflow tables follow a fixed lock order to avoid deadlocks with the timer firing executor and the signal claim path:

```
execution lock (instance)  →  workflow_timers  →  workflow_tasks  →  workflow_branch_tokens  →  workflow_instances  →  workflow_command_queues
```

The execution lock is an exclusive, transaction-scoped PostgreSQL advisory lock in the two-integer form, derived from the
instance id in a namespace of its own; it never collides with the definition locks, which are single-bigint. Every
transaction that changes an instance takes it first (`PgWorkflowCommandRepository.lockInstance`), so one transaction at a
time changes an instance and appends to its history. The wait applies `workflow.recording.lockTimeoutMs` (default 5000, at
least 1) to that one acquisition and restores the caller's own `lock_timeout` in the same statement, so it never leaks into
the rest of the caller's transaction. A timeout (`55P03`) or a deadlock (`40P01`) fails the call with
`WorkflowPersistenceException` with `retryable()` true, and PostgreSQL rolls the caller's transaction back. The branch
recovery sweep takes the lock of a branch's instance, as a requester, before it locks the branch row.
`tryLockInstance` takes the lock without waiting. `nextQueueSequence` allocates from `workflow_command_queues` under the
lock, creating the queue row on first touch (the first value is 1); a rolled-back transaction gives its value back.

Reversing any segment of this chain deadlocks against an already-running firing executor or a concurrent signal claim. All engine code paths — fire, signal, cancel, complete, supersede — follow it.

Integrator rule: do not write custom transactions that lock workflow tables in a different order. Prefer the public workflow APIs or the provided transactional services. When extending storage behavior, preserve the established ordering across timers, tasks, branch state, and workflow instances.

---

## Side Effects

`WorkflowPostgresqlModule` runs installed `WorkflowSideEffectRecorder<SqlClient>` bindings during transitions. Recorders are responsible for durable handoff only.

Common side-effect modules:

| Side effect | Module |
|---|---|
| Service dispatch outbox entries | `workflow-services` |
| Timer delayed jobs (earlier releases only) | `workflow-delayed` |
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

**Executor branch drive:** a branch-addressed signal applied by the executor goes through `BranchTransitionEngine.driveExecutorBranchTransitions`, which hands the document it would bind (the explicit-carrier override, else `token.metadata()`) to the binder's executor entry. That entry strips the identity-snapshot namespace from it and refuses the drive, failing the segment transiently, when a `SecurityContext` or a deferred-execution origin is already bound. The executor never binds an identity; requester drives keep the behavior below.

**Branch drive:** `BranchTransitionEngine.driveBranchTransitions` binds `token.metadata()` at the start of every per-branch drive via `InboundExecutionContextScope.installDurable(metadata, DispatchBoundary.WORKFLOW)`. The bind covers timer-step, signal-step, service-dispatch, and decision step types and closes on `Future.eventually(...)`. The substrate helper wraps the authoritative `DurableContextPropagator.bindFrom(...)` — every registered durable type absent from `token.metadata()` is cleared for the drive's scope so a downstream `mergeCaptured(...)` (e.g. the timer metadata a timer command records) cannot pick up ambient context that did not cross the workflow boundary. After the durable bind, registered `InboundContextInitializer`s run (notably the correlation seeder), so a branch whose metadata carries no encoded `CorrelationContext` still gets one bound for the duration of the drive. Empty metadata still triggers the bind. Production reaches branch-drive on a duplicated context: dispatcher entry points (event bus, Kafka per-record, cron callback, service-method invoker) are all duplicates, and `vertx-sql-client` 5.x preserves the caller's duplicate through `pool.withTransaction(tx -> …)`. `installDurable` propagates the strict `bindFrom` invariant — `IllegalStateException` on a non-duplicated Vert.x context — so tests that call into the engine directly from a JUnit thread must first switch to a duplicate.

`driveBranchTransitions` accepts an additional `@Nullable DurableMetadata effectiveBaseOverride` parameter. When non-`null` — as supplied by branch recovery below, and by a branch-targeted explicit signal carrier — the drive binds that already-merged effective document instead of raw `token.metadata()`. This is the seam every branch-owned drive routes through under the bind-once routing rule: it is never additionally wrapped by the instance-path `WorkflowContextBinder`, so a branch-owned drive is bound exactly once.

**Branch recovery — instance-fill seam:** `PgWorkflowBranchRecoveryService.withBranchDurableBound` wraps every per-branch advance in both the `processDue` path (due branches) and the `processStale` path (stale-RUNNING branches) via the same `InboundExecutionContextScope.installDurable(...)` helper, so the recovery sweep gets the same initializer fan-out as the live drive. Branches advance sequentially within one sweep so scopes do not overlap.

Before each row's scope opens, the sweep loads the owning `WorkflowInstance` (a non-locking `findById`, preserving the `workflow_timers → workflow_tasks → workflow_branch_tokens → workflow_instances` lock order — no new `FOR UPDATE`) and computes `effectiveBase = token.metadata().merge(instance.metadata(), MergePolicy.CALLER_WINS)`. When the instance carrier is `null` this is a no-op merge — `effectiveBase` equals `token.metadata()` unchanged, byte-identical to pre-feature behavior. When the instance carrier is non-`null`, it fills any namespace absent from the branch token — the mechanism that closes a namespace hole in a token that was forked during a context-degraded drive (a transport relay that lost a namespace, or a pre-feature fork). `effectiveBase` is passed as `driveBranchTransitions`'s `effectiveBaseOverride`, so the token's own carrier remains authoritative for every namespace it already carries. A bind failure is isolated to that row's advance failure; the sweep continues with the next row.

**Timer metadata:** a timer recorded by a segment stores the durable context of that drive in `workflow_timers.metadata`, signed for the `workflow-timer` carrier (`WorkflowTimerCarriers` in this module, the same kind and address earlier releases used) and without the identity-snapshot namespace; the timer's command stores no context. The `TIMER` segment that fires the timer binds the metadata as an executor drive, which strips the namespace from metadata written by earlier releases too.

**Timer recovery (earlier releases):** `WorkflowTimerRecoveryService.withTimerDurableBound` similarly uses `installDurable(row.metadata(), DispatchBoundary.DELAYED_JOB)` for the terminal `timerFiringFailed` paths (DEAD_LETTER / SUCCEEDED / CANCELLED), so the cron sweep clearing ambient context still gets a freshly seeded `CorrelationContext` for the `WORKFLOW_FAILED` outbox emission when the timer row's metadata carries no correlation.

**Metadata immutability:** Once written at fork, `workflow_branch_tokens.metadata` is never overwritten. Demotion to retry-scheduled or terminal failure preserves the original value.

---

## Invariants & Gotchas

- **Branch tables have no FK to workflow_instances.** `workflow_tasks` and `workflow_timers` carry nullable `branch_token_id` / `fork_step_id` / `branch_id` columns but no FK constraint, by design — adding one would invert the established lock order.
- **Dispatch dedup lives in the engine, not the recorder.** Branch service-dispatch idempotency is enforced inside the persistence engine before the recorder runs, so the `workflow-services` boundary stays generic.
- **Stranded re-enqueue is detected by row count.** Timer-store mutations are guarded by `status = 'SCHEDULED'` and fail when `rowCount = 0`, rolling back the transaction rather than silently re-enqueuing into the wrong state.
- **Late callbacks are swallowed deliberately.** Task / timer callbacks carrying a `branchTokenId` from a superseded branch are recorded as `BRANCH_LATE_CALLBACK_IGNORED` history entries and return without raising — preventing zombie callbacks from corrupting later state.
- **A task is completed in two transactions.** `taskCompleted` writes the dedup row, the `COMPLETION` command and the `OPEN` to `COMPLETING` reservation under the execution lock; the executor's segment completes the task (`markCompleted`), or rejects the command and reopens the task. A reservation owner is never cleared except by that reopening, and a `COMPLETING` task whose command is lost would refuse every completion and reassignment, so a command repository that settles a `COMPLETION` (an ended instance, a failure disposition) settles the reservation in the same transaction.
- **Signal recording claims the dedup key last.** `signal()` looks up a committed duplicate, reads the instance and the pinned row's declared names without the execution lock, takes the lock, raises the instance's `open_signals` count with one conditional update and only then claims the dedup key and inserts the `SIGNAL` command, so a refusal (cap, undeclared name, ended instance) leaves no dedup row. `cancel()` still uses `findById` + `claimOrResolveSignal` to honor the `workflow_timers`-before-`workflow_instances` lock-order invariant.
- **Archived rows are filtered by default.** `findFiltered` / `findByFilter` exclude `archived_at IS NOT NULL` rows unless `WorkflowFilter.includeArchived = true`.
- **Archive and purge do not share a clock.** `archiveBefore` stamps `archived_at` from the *database* clock, while `purgeArchivedBefore` compares it against the cutoff *you* supply. Since your application drives the sweep cadence, a purge issued immediately after an archive with a cutoff read from your own clock may skip rows it just archived, whenever the database clock is running ahead. Use a cutoff meaningfully in the past — real retention horizons of hours or days make this invisible.

---

## Dependencies

- **workflow-engine** - portable orchestration runtime: `WorkflowEngine`, the 8 internal repository SPIs (including `TaskReservations`), `WorkflowTransactionRunner`, `RecorderRouter`, `WorkflowReminderComposeValidator`, `WorkflowEngineModule`. This module includes `WorkflowEngineModule`.
- **workflow-core** - public workflow APIs, plans, tasks, timers, and side-effect SPIs.
- **db-postgresql** - Postgres pool, `PgDbExceptionMapper`, `PgSqlRepository` base, and `PgTableNames`, which qualifies every table reference.
- **db-core** - `DbSchemas`, `DbSchemasModule` and `MigrationDefinition` (through db-postgresql).
- **db-flyway** - `FlywayMigrationRunner` applies the packaged migration. Declared as a dependency, but no type of this module references Flyway, so the application supplies and wires the runner.
- **vertx-sql-client** - SQL connection, pool, and transaction types.
- **jackson-databind** - workflow state and history payload serialization.
