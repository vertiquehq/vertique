<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Workflow Engine Module

> **Status:** Stable
> **Package:** `dev.vertique.workflow.engine`
> **Artifact:** `vertique-workflow-engine`
> **Depends on:** workflow-core, db-core, context, vertx-sql-client

`vertique-workflow-engine` is the portable, dialect-neutral workflow orchestration runtime. It holds the
execution kernel — the durable saga state machine, fork/join coordinator, task and timer lifecycle
services, signal delivery, migration executor, and history recorder — decoupled from any specific SQL
dialect. Persistence is injected at application composition time through six `<TX>`-generic repository
SPIs and a transaction-runner SPI.

The module is the seam for DB portability: a second Vert.x SQL backend needs only dialect-specific
implementations of those SPIs; the orchestration logic is reused verbatim. A maven-enforcer
`bannedDependencies` rule in the module's `pom.xml` makes this boundary machine-checked — the build
fails if `io.vertx:vertx-pg-client`, `vertique-db-postgresql`, or `vertique-workflow-postgresql` appear
in the engine's dependency tree.

---

## When To Use It

Install `vertique-workflow-engine` indirectly — it is `include`d by every workflow persistence adapter.
Applications install only the adapter module (`vertique-workflow-postgresql`) and the engine comes with
it. Access the engine through the interfaces `WorkflowOperations` and `TransactionalWorkflowOperations<SqlClient>`.

Implement the SPIs in this module when building a new Vert.x SQL workflow persistence backend.

---

## Core Concepts

### Three-Layer Hierarchy

```
vertique-workflow-core       — SQL-free DSL, registry, operation interfaces, side-effect SPI
        ↑
vertique-workflow-engine     — portable orchestration runtime, <TX>-generic SPIs
        ↑
vertique-workflow-postgresql — PostgreSQL adapter: SPI impls, schema, Pg runner
```

The engine depends on `io.vertx:vertx-sql-client` (the abstract Vert.x SQL API JAR) because the six
repository SPIs and `WorkflowTransactionRunner` are parameterized with `SqlClient` as the concrete
transaction-handle type. This is an honest declaration of scope — the engine targets the Vert.x SQL
family, not arbitrary persistence stores — but carries no Pg-specific APIs.

### Package-Private Internals, Public Boundary

`WorkflowEngine` and its fifteen collaborators are all package-private within
`dev.vertique.workflow.engine`. Dagger generates binding code inside that package and wires the engine
through `WorkflowEngineModule` using `@Binds`. Code outside the package (adapter modules, application
code) never references the concrete class — it uses `WorkflowEngineHandle`, `WorkflowEngineFactory`,
or the four public ops/callback interfaces.

### Transaction Ownership

The engine performs no transaction management of its own. Public operation methods (`start`, `signal`,
`cancel`, `retry`, `migrate`, `query`) open a transaction through `WorkflowTransactionRunner.inTransaction`
and pass the resulting `SqlClient` handle into their transactional variants. Callers that already own a
transaction use `TransactionalWorkflowOperations<SqlClient>` directly.

---

## Key Classes

### WorkflowEngineHandle

```java
public interface WorkflowEngineHandle
        extends WorkflowOperations,
                TransactionalWorkflowOperations<SqlClient>,
                TransactionalTimerCallbacks<SqlClient>,
                TransactionalTaskCallbacks<SqlClient> {}
```

Public composite handle re-bundling the four already-public engine interfaces into a single type. Adds
no methods — exists solely as the return type of `WorkflowEngineFactory.create(...)` so callers outside
the engine package can hold a fully wired engine without depending on the package-private concrete class.

### WorkflowEngineFactory

Static factory that is the non-Dagger parallel to `WorkflowEngineModule`. Accepts the repository SPIs,
task/timer stores, workflow registry, `Clock`, side-effect recorders, optional intent kinds, optional
`WorkflowMigrationRegistry`, and optional `DurableContextPropagator`, and assembles the entire
package-private collaborator graph — including hand-wiring the `WorkflowTransitionDriver` ↔
`ForkJoinCoordinator` mutual-recursion cycle using a one-element holder array. Returns the assembled
engine as a `WorkflowEngineHandle`.

Key members:

| Member | Description |
|---|---|
| `DEFAULT_OPTIONAL_INTENT_KINDS` | Shared constant (`Set.of(IntentKind.WORKFLOW_EVENT)`) — single source of truth for both Dagger and non-Dagger assembly paths |
| `create(...)` | Assembles and returns a `WorkflowEngineHandle` |

```java
// Broad engine IT setup (non-Dagger path via PgWorkflowEngineTestSupport)
WorkflowEngineHandle engine = WorkflowEngineFactory.create(
        txRunner, registry, instances, history, dedup,
        branchTokens, joinStates, timerStore, taskStore,
        clock, recorders, WorkflowEngineFactory.DEFAULT_OPTIONAL_INTENT_KINDS,
        Optional.empty(), null);
```

The `WorkflowRecoveryBridge` is a Dagger-bound SPI: `WorkflowEngineModule` binds
`WorkflowRecoveryBridgeImpl` as `WorkflowRecoveryBridge`. Code that needs a bridge obtains it via
a Dagger component that installs `WorkflowPostgresqlModule` (which transitively includes
`WorkflowEngineModule`). There is no factory accessor for the bridge.

### WorkflowEngineModule

Dagger module owning the engine-internal bindings:

- Binds `WorkflowEngine` as `WorkflowOperations`, `TransactionalWorkflowOperations<SqlClient>`,
  `TransactionalTimerCallbacks<SqlClient>`, and `TransactionalTaskCallbacks<SqlClient>`.
- Binds `WorkflowRecoveryBridgeImpl` as `WorkflowRecoveryBridge`.
- Declares the `@WorkflowRecorders`-qualified `Set<WorkflowSideEffectRecorder<SqlClient>>` multibinding.
- Provides the `@OptionalIntentKinds Set<IntentKind>` (delegates to `WorkflowEngineFactory.DEFAULT_OPTIONAL_INTENT_KINDS`).
- Declares `WorkflowMigrationRegistry` as `@BindsOptionalOf` (opt-in migration support).
- Includes `WorkflowCoreModule` and `ContextRuntimeModule`.

Adapter modules (`WorkflowPostgresqlModule`) include this module, so applications installing only the
adapter module get all engine bindings automatically.

```java
// Application component — install only the adapter; engine is included transitively
@Singleton
@Component(modules = {
    VertxModule.class,
    WorkflowPostgresqlModule.class,   // includes WorkflowEngineModule
    WorkflowServicesModule.class,
    DbPostgresqlModule.class,
    AppModule.class
})
public interface AppComponent {
    WorkflowOperations workflowOperations();
}
```

### WorkflowRecoveryBridge

```java
public interface WorkflowRecoveryBridge {
    Future<BranchToken> driveRecoveredBranch(
            BranchToken token,
            String parentStateJson,
            @Nullable String parentSubjectVersion,
            RuntimeWorkflow rw,
            SqlClient tx);

    Future<Void> evaluateRecoveredBranchIfTerminal(
            WorkflowInstance inst, BranchToken branch, RuntimeWorkflow rw, SqlClient tx);

    void validateRecoveryPlan(WorkflowInstance inst, RuntimeWorkflow rw);
}
```

Public SPI exposing exactly the three operations a dialect-side branch recovery sweep needs, without
widening the visibility of the package-private `BranchTransitionEngine` or `ForkJoinCoordinator`.
Bound by `WorkflowEngineModule` to its package-private implementation; adapter recovery services
inject this interface.

The bridge is pure orchestration — it performs no transaction management and owns no durable context
scope. The adapter recovery service retains ownership of the transaction boundary, the
`DurableContextScope` bind, and optimistic-concurrency retries.

### WorkflowContextBinder

Package-private binder-row seam that binds effective durable context for **instance-owned,
single-path drives**: `signal`, `cancel`, `retry`, `migrate`, `taskCompleted`, `taskReassigned`,
`taskDueFired`, `taskReminderFired`, `timerFired`, and `timerFiringFailed`. It implements the bind
gate and the base-wins/instance-fill composition described in `dev.vertique:vertique-workflow-core`
§ Durable Context on the Instance:

```java
<T> Future<T> withBound(
        WorkflowInstance instance,
        @Nullable DurableMetadata explicitCarrier,   // explicit signal carrier only; null = capture ambient
        Supplier<Future<T>> drive);
```

- **Bind gate.** When `explicitCarrier` is `null` and `instance.metadata()` is `null`, no bind
  happens at all — `drive` runs exactly as it did before the instance carrier existed.
- **Base-wins/instance-fill.** The base is `explicitCarrier` when present, otherwise the ambient
  context captured via `DurableContextPropagator.capture(WORKFLOW)`. When the instance carries
  non-null metadata, it is merged into that base with `MergePolicy.CALLER_WINS`: the base wins for
  any namespace present on both sides; the instance only fills namespaces the base is missing.
- **`noop()` null-object.** `WorkflowContextBinder.noop()` returns a binder whose `withBound` always
  runs `drive` unbound, with no capture/merge/bind — the equivalent of the gate always being closed.
  Call sites that used to null-check a `WorkflowContextBinder` field before this seam existed
  substitute this instance instead, keeping the field non-`@Nullable`.

**Must not be used for branch-owned drives.** `WorkflowContextBinder` binds instance-owned drives
only. Fork continuations, branch-targeted signals, and branch timers are branch-owned and bind
through `BranchTransitionEngine.driveBranchTransitions`'s own carrier seam instead — this is the
bind-once routing rule: a drive binds through exactly one of the two seams, never both.

### BranchTransitionEngine.driveBranchTransitions

The branch-owned counterpart to `WorkflowContextBinder`. Its public entry point takes an additional
`@Nullable DurableMetadata effectiveBaseOverride` parameter:

```java
Future<BranchToken> driveBranchTransitions(
        BranchToken token,
        String parentStateJson,
        @Nullable String parentSubjectVersion,
        RuntimeWorkflow rw,
        SqlClient tx,
        @Nullable DurableMetadata effectiveBaseOverride);
```

By default the drive binds `token.metadata()` — the branch token's own persisted carrier. When
`effectiveBaseOverride` is non-null, the drive binds that pre-merged document instead of
`token.metadata()`. Two callers pass a non-null override: branch recovery (the sweep's own
carrier-row instance fill) and the branch-targeted explicit-carrier signal path (an explicit signal
carrier that must win over the branch token for that one transition). Every other call site passes
`null`, preserving the original token-metadata bind unchanged.

History entries appended during a bound drive may additionally record `commandCorrelationId` — see
`WorkflowMigratedHistoryPayload` and the sibling command-history payloads for the field-level shape.

### WorkflowExceptionMapper

Stage-2 (workflow-boundary) exception mapper applied in the Pg runner's outer `recover` after the DB
layer's stage-1 mapper (`WorkflowPgExceptionMapper`) has run. Translates `DataAccessException` subtypes
into workflow-semantic exceptions rooted in the core hierarchy (e.g. `WorkflowConflictException` →
`ConflictException` for 409, `WorkflowPersistenceException` → `WorkflowTechnicalException` for 500);
already-semantic workflow exceptions and non-DB throwables pass through unchanged:

| Input type | Output |
|---|---|
| any already-semantic workflow exception | Passthrough unchanged |
| `OptimisticLockingFailureException` | `WorkflowConflictException` (cause-preserving) |
| `PessimisticLockingFailureException` | `WorkflowPersistenceException` (`retryable=true`) |
| `TransientDataAccessException` (deadlock, timeout, connection failure) | `WorkflowPersistenceException` (`retryable=true`) |
| Any other `DataAccessException` | `WorkflowPersistenceException` (`retryable=false`) |
| Anything else | Passthrough unchanged |

Message sanitization: the produced exception message includes only the failure kind, operation name,
cause simple class name, and SQL state (when available). The upstream `DataAccessException.getMessage()`
text is intentionally excluded to prevent SQL fragments or column names from reaching the workflow API
surface.

### WorkflowReminderComposeValidator

Startup composition guard that aborts application boot when any registered workflow plan declares task
reminders (`HumanTaskNode.reminders() != null`) but no `WORKFLOW_EVENT` `WorkflowSideEffectRecorder` is
installed. Enforced through the `RecorderRouter` constructor chain: requesting `WorkflowOperations`
(or any type that depends on `WorkflowEngine`) from the Dagger component forces construction of this
validator.

---

## Extension Points

### Database Adapter SPIs (`dev.vertique.workflow.engine.spi`)

A database adapter implements these six interfaces to provide persistence for the engine. All are
parameterized over `<TX>` (typically `SqlClient`):

| SPI | Responsibility |
|---|---|
| `WorkflowInstanceRepository<TX>` | Instance insert, optimistic update, migration update, and reads (by id without lock, with tx, with `FOR UPDATE`, by business key, filtered+paginated) |
| `WorkflowHistoryRepository<TX>` | History append, next-sequence generation, and two list variants (recent, all) |
| `WorkflowDedupRepository<TX>` | Race-safe claim/resolve for start, signal (scoped and unscoped), task completion/reassignment, and service dispatch |
| `BranchTokenRepository<TX>` | Fork/join branch token CRUD, status queries, and recovery queries |
| `JoinStateRepository<TX>` | Fork/join state insert, read variants, open-for-workflow list, and the optimistic `decide` CAS |
| `WorkflowTransactionRunner<TX>` | Transaction boundary, optional isolation level, and the two-stage workflow exception mapping |

The adapter also provides:
- A `WorkflowRecoveryBridge` is already bound by `WorkflowEngineModule`; no adapter re-binding needed.
- The adapter recovery service injects `WorkflowRecoveryBridge` to drive recovered branches.

Register the SPI bindings in the adapter's Dagger module, then `include = {WorkflowEngineModule.class}`:

```java
@Module(includes = {WorkflowEngineModule.class, DbPostgresqlModule.class, ...})
public abstract class WorkflowPostgresqlModule {

    @Binds @Singleton
    abstract WorkflowInstanceRepository<SqlClient> instances(PgWorkflowInstanceRepository impl);

    @Binds @Singleton
    abstract WorkflowTransactionRunner<SqlClient> txRunner(PgWorkflowTransactionRunner impl);

    // ... other SPI @Binds ...
}
```

### Side-Effect Recorders (`@WorkflowRecorders`)

Applications contribute `WorkflowSideEffectRecorder<SqlClient>` implementations through Dagger
multibinding. The `@WorkflowRecorders` multibinding is declared by `WorkflowEngineModule`:

```java
@Provides @IntoSet @WorkflowRecorders
static WorkflowSideEffectRecorder<SqlClient> serviceRecorder(WorkflowServiceRecorder impl) {
    return impl;
}
```

`IntentKind.WORKFLOW_EVENT` is declared optional by default (`DEFAULT_OPTIONAL_INTENT_KINDS`), so
applications without a workflow-events module run without it — unless a plan declares reminders, in
which case `WorkflowReminderComposeValidator` aborts startup.

---

## Dependencies

- **workflow-core** — workflow DSL types, `WorkflowRegistry`, `WorkflowOperations` and transactional
  interfaces, `TaskStore<TX>` / `TimerStore<TX>` SPIs, side-effect SPI, exception hierarchy including
  `WorkflowPersistenceException`.
- **db-core** — `IsolationLevel` (used in `WorkflowTransactionRunner`), `DataAccessException` hierarchy
  consumed by `WorkflowExceptionMapper`, and paged-query types.
- **context** — `DurableContextPropagator` injected into `ForkJoinCoordinator` for branch durable-context capture.
- **vertx-sql-client** — `io.vertx.sqlclient.SqlClient` used as the concrete `<TX>` type throughout
  all SPI signatures and the engine implementation.

---

## Related ADRs

- ADR-0111: Workflow Engine / Adapter Module Split — governs the three-layer decomposition, the Portable/Dialect classification rule, the cross-module visibility model, and the maven-enforcer portability proof.
- ADR-0109: Workflow Repository SPIs and Transaction-Runner Seam — Phase 1 prerequisite: introduced the six `<TX>`-generic SPIs and the `WorkflowTransactionRunner` interface that this module's SPI package now owns.
- ADR-0110: Layered Workflow Exception Mapping — governs the two-stage mapping wired into `PgWorkflowTransactionRunner`; `WorkflowExceptionMapper` (stage 2) is defined in this module.
- ADR-0147: Instance-Level Durable Context with Base-Wins/Instance-Fill Binding — governs `WorkflowContextBinder`'s bind gate and base-wins/instance-fill compose, and the `effectiveBaseOverride` seam on `BranchTransitionEngine.driveBranchTransitions`.
