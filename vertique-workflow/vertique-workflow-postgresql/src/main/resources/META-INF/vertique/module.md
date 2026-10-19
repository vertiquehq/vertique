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

`vertique-workflow-postgresql` is the **PostgreSQL adapter** for the Vertique Workflow engine. It provides PostgreSQL implementations of the engine's internal repository and transaction-runner SPIs, owns the schema and Flyway migrations, and supplies Pg-specific query, retention, and recovery services. `WorkflowMigrationsModule` declares those migrations as the `workflow` migration instance for named Flyway instances, and the optional `db.schemas.workflow` setting places every workflow table in one schema for the migrations and for all eight stores.

For the dialect-neutral orchestration logic — `WorkflowEngine`, the 6 SPI interfaces (`WorkflowInstanceRepository`, `WorkflowHistoryRepository`, `WorkflowDedupRepository`, `BranchTokenRepository`, `JoinStateRepository`, `WorkflowTransactionRunner`), `RecorderRouter`, `WorkflowReminderComposeValidator`, and the Dagger module that binds `WorkflowOperations` — see `dev.vertique:vertique-workflow-engine`.

Install this module when workflows must survive process restarts, run across more than one node, or coordinate with transactional outbox and delayed-job modules. `WorkflowPostgresqlModule` includes `WorkflowEngineModule` transitively, so consumer wiring is unchanged — no `@Component` edits are required.

---

## What It Provides

| Binding | Purpose |
|---|---|
| Pg repo impls | `PgWorkflowInstanceRepository`, `PgWorkflowHistoryRepository`, `PgWorkflowDedupRepository`, `PgBranchTokenRepository`, `PgJoinStateRepository` — implement the engine SPIs; each is one scoped instance built with the optional `DbSchemas` placement |
| `TaskStore<SqlClient>` | Human task persistence (`PgTaskStore`), one scoped instance built with the placement |
| `TimerStore<SqlClient>` | Workflow timer persistence (`PgTimerStore`), one scoped instance built with the placement |
| `WorkflowTransactionRunner<SqlClient>` | Pg implementation of the engine's transaction seam |
| Query services | `PgWorkflowInstanceQueryService` — read model for workflow instances and branch state |
| Retention service | `PgWorkflowRetentionService` — SKIP LOCKED archive + purge sweep; the application drives the batch loop and cadence, there is no built-in scheduler. It archives and purges through the scoped `PgWorkflowRetentionRepository`, so it follows the same placement |
| Recovery service | `PgWorkflowBranchRecoveryService` — opt-in cluster-singleton branch recovery cron |
| Migration declaration | `WorkflowMigrationsModule` — contributes the `workflow` `MigrationDefinition` (`classpath:db/migration/workflow`) with `@IntoSet`; see [Flyway Configuration](#flyway-configuration) |
| Flyway schema | `V1__create_workflow_tables.sql` — all workflow tables and indexes |

The module `includes WorkflowEngineModule`, which provides the `WorkflowOperations` binding and recorder-router wiring. It also includes `WorkflowMigrationsModule` and `DbSchemasModule` (the optional `DbSchemas` binding). It does not deliver downstream service calls or execute delayed jobs by itself; those are separate modules.

> **Two similarly named modules.** `WorkflowMigrationsModule` (plural, in `dev.vertique.workflow.postgresql.engine`) declares the *database schema* migrations of this module for Flyway. `WorkflowMigrationModule` (singular, in `dev.vertique:vertique-workflow-core`) is the optional wiring for *in-flight workflow definition migration*, which moves running instances from one definition version to another. They are unrelated; install the second only when you use in-flight migration (see [Required App Wiring](#required-app-wiring)).

---

## Key Classes

### The eight stores and their constructors

Eight PostgreSQL classes own every workflow SQL statement: `PgWorkflowInstanceRepository`, `PgWorkflowHistoryRepository`, `PgWorkflowDedupRepository`, `PgBranchTokenRepository`, `PgJoinStateRepository`, `PgTaskStore`, `PgTimerStore` and `PgWorkflowRetentionRepository`. Each has two public constructors:

```java
// the other seven stores: (Pool pool, PgDbExceptionMapper mapper)
@Inject
public PgWorkflowInstanceRepository(Pool pool, PgDbExceptionMapper mapper)

public PgWorkflowInstanceRepository(Pool pool, PgDbExceptionMapper mapper, DbSchemas schemas)

// PgTimerStore takes no Pool
@Inject
public PgTimerStore(PgDbExceptionMapper exceptionMapper)

public PgTimerStore(PgDbExceptionMapper exceptionMapper, DbSchemas schemas)
```

- **Unmapped means unchanged.** The constructors without `DbSchemas` leave every table unqualified, so the workflow tables resolve through the connection's default schema. With no `workflow` entry in the `DbSchemas` passed to the other constructor, the SQL is the same as theirs.
- **Mapped means every reference is qualified.** The `DbSchemas` constructors qualify every workflow table reference, in every statement, with the schema that `schemas` maps to `workflow`. This covers lifecycle, history, dedup, branch and join, task, timer, query, recovery and retention statements, including the joins and subqueries between workflow tables. Statements are built once at construction.
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

The migration files live with the module under `db/migration/workflow`. The whole schema is defined in a single migration, `V1__create_workflow_tables.sql` — including the `workflow_branch_tokens` table and the `metadata` columns on both `workflow_timers` and `workflow_branch_tokens`. Schema changes from here are additive Flyway migrations.

**Placement.** The migration script is unqualified, so the migration instance's default schema decides where the seven tables and their indexes land. In named Flyway mode with a `db.schemas.workflow` entry, that schema is the default, and the stores qualify every table with it, so both agree. In legacy mode the migration lands where the legacy configuration says (see [Flyway Configuration](#flyway-configuration)). The module creates no sequences, views, functions or triggers and uses no notification channel, so tables and indexes are everything a mapped schema receives.

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
    "instances": { "workflow": {} }
  }
}
```

- **One setting places everything.** `db.schemas.workflow` is the instance's default schema and history schema, and the eight stores qualify every workflow table with it, so the migration and every statement use one schema. The tables and indexes land there, and the history table is `flyway_schema_history_workflow` in that schema.
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

A one-off deploy step or job builds that component, calls `FlywayMigrationRunner.run(vertx)` and reads the `MigrationReport`; the runtime replicas then start in `VALIDATE` or `DISABLED` mode against the migrated schemas. The runner uses the `Vertx` instance it is given for blocking work only and never closes it, so the caller keeps using it afterwards. `dev.vertique:vertique-db-flyway` documents the multi-replica and coordination-lock guidance.

**Class loader.** The runner captures the calling thread's context class loader when `run` or `migrate` is called (its own class loader when the thread has none), and resolves packaged scripts and application locations through that loader, not through the ambient loader of the Vert.x worker thread that executes the migration. Call it from a thread whose context class loader can see the migration scripts, normally the application class path. Instance locations are independent per directory prefix: `classpath:db/migration/job` and a sibling such as `classpath:db/migration/job-v2` never read each other's scripts.

### Single Flyway (legacy) configuration

When `flyway.instances` is absent, the default `classpath:db/migration` location already includes the bundled `db/migration/workflow` scripts, so no extra location is needed. Every bundled adapter ships its own `V1` script, so one history can hold only one of them (a second would fail with a duplicate version); to run several adapters, use named instances. The single-history example:

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

In this mode the migration lands where the legacy configuration says, and nothing checks it against `db.schemas`. If `db.schemas.workflow` is also set, the stores query that schema, so make the legacy `flyway.schemas` list (first entry) or the connection default match it, or the stores will look in a schema the migration never filled.

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
| Timer steps, signal timeouts, task due dates | `WorkflowDelayedModule`, delayed jobs, persistent cron |
| Human task service API | `WorkflowTasksModule` |
| External workflow event stream | `WorkflowEventsModule` |
| YAML/JSON definition loading | `WorkflowDefinitionModule` |
| In-flight definition migration | `WorkflowMigrationModule` (from `dev.vertique:vertique-workflow-core`; not the schema-migration declaration `WorkflowMigrationsModule`, which this module already includes) |

---

## Module Dagger Bindings

`WorkflowPostgresqlModule` includes `WorkflowEngineModule`, `LoggingContextModule`, `WorkflowMigrationsModule` (the `workflow` migration declaration) and `DbSchemasModule` (the optional `DbSchemas` binding), and provides:

| Binding | Scope | Description |
|---|---|---|
| `PgWorkflowInstanceRepository`, `PgWorkflowHistoryRepository`, `PgWorkflowDedupRepository`, `PgBranchTokenRepository`, `PgJoinStateRepository`, `PgTaskStore`, `PgTimerStore`, `PgWorkflowRetentionRepository` | Singleton | Eight explicit providers, each built from `Optional<DbSchemas>`: placed in the schema mapped to `workflow` when the component supplies `DbSchemas` (a component that installs `DbModule` gets it from `db.schemas.workflow`), unqualified when it does not |
| The five repository SPIs, `TaskStore<SqlClient>`, `TimerStore<SqlClient>` | Singleton | Aliases of the concrete instances above, so a concrete lookup and an SPI lookup return the same instance |
| `WorkflowTransactionRunner<SqlClient>` | Singleton | The PostgreSQL transaction seam |
| `WorkflowInstanceQueryService`, `WorkflowRetentionService` | Singleton | Services over the same scoped instances (as is the branch query service), so queries, archive and purge follow the placement |
| `Set<MigrationDefinition>` | `@IntoSet` | The `workflow` declaration from `WorkflowMigrationsModule`: location `classpath:db/migration/workflow`, no dependencies |

The branch-recovery cron installed by `WorkflowBranchRecoveryModule` reaches the same scoped repositories, so recovery sweeps follow the placement too.

**Compatibility note.** The eight concrete store types are now provided by explicit `@Provides` methods. A component that installs this module and also provides any of them itself sees a duplicate binding; remove its own provider. The `@Inject` constructors remain for components that do not install this module; they leave the SQL unqualified.

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
- **db-postgresql** - Postgres pool, `PgDbExceptionMapper`, `PgSqlRepository` base, and `PgTableNames`, which qualifies every table reference.
- **db-core** - `DbSchemas`, `DbSchemasModule` and `MigrationDefinition` (through db-postgresql).
- **db-flyway** - `FlywayMigrationRunner` applies the packaged migration. Declared as a dependency, but no type of this module references Flyway, so the application supplies and wires the runner.
- **vertx-sql-client** - SQL connection, pool, and transaction types.
- **jackson-databind** - workflow state and history payload serialization.
