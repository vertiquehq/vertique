<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Workflow Core Module

> **Status:** Stable
> **Package:** `dev.vertique.workflow`
> **Artifact:** `vertique-workflow-core`
> **Depends on:** core, db-core

`vertique-workflow-core` defines the public workflow programming model. It contains the DSL for describing workflows, the registry that stores compiled plans, the SQL-free operation interfaces, the typed contract proxy support, task and subject abstractions, and the side-effect recorder SPI used by persistence modules.

The module does not contain a database implementation. Persistence, timers, task storage, and outbox delivery are provided by sibling modules.

---

## When To Use It

Use this module when an application needs to define or interact with durable workflows.

Install `workflow-core` in every app that uses Vertique Workflow. Add persistence and integration modules only for the capabilities the app needs:

| Capability | Add |
|---|---|
| Durable runtime backed by Postgres | `vertique-workflow-postgresql` |
| Service dispatch side effects | `vertique-workflow-services` |
| Timers, signal timeouts, task due dates | `vertique-workflow-delayed` |
| Human task service API | `vertique-workflow-tasks` |
| Durable external workflow events | `vertique-workflow-events` |
| YAML/JSON workflow authoring | `vertique-workflow-definition` |

---

## Core Concepts

### Workflow Definition

A `WorkflowDefinition<S, C>` describes one version of a workflow. `S` is the workflow state type and `C` is the typed workflow contract interface.

Definitions call the `WorkflowBuilder<S>` DSL to declare the initial state mapper, the initial step, and the step graph:

```java
public final class OrderFulfillmentDefinition
        implements WorkflowDefinition<OrderState, OrderFulfillmentWorkflow> {

    @Override
    public String definitionId() {
        return "order-fulfillment";
    }

    @Override
    public long definitionVersion() {
        return 1;
    }

    @Override
    public Class<OrderFulfillmentWorkflow> contract() {
        return OrderFulfillmentWorkflow.class;
    }

    @Override
    public Class<OrderState> stateType() {
        return OrderState.class;
    }

    @Override
    public void define(WorkflowBuilder<OrderState> wf) {
        wf.init(PlaceOrder.class, cmd -> new OrderState(cmd.orderId()))
          .initialStep("reserve-inventory")
          .dispatch("reserve-inventory", "inventory.reserve",
              state -> new ReserveInventoryRequest(state.orderId()),
              "await-inventory")
          .waitFor("await-inventory", "inventory.reserved",
              InventoryReserved.class,
              (state, event) -> state.withInventoryReserved(true),
              "ship")
          .dispatch("ship", "shipping.create",
              state -> new ShipOrderRequest(state.orderId()),
              "done")
          .complete("done");
    }
}
```

### Parallel Branches

`WorkflowBuilder.fork(stepId)` declares a fan-out into named branches, each with its own start step; `join(stepId)` declares the matching join step with a completion policy (`allRequired`, `firstSuccess`, or `firstFailure`) that reduces branch results back into workflow state. Each running branch executes against its own `BranchToken`; the fork's fan-in progress is tracked in a `JoinState`.

### Workflow Plan

Registration turns a definition into an immutable `WorkflowPlan`. A plan contains serializable step metadata, callback ids, type names, and a content-derived plan hash. Executable Java callbacks stay in the callback registry and are referenced by id. The engine executes only `WorkflowPlan` objects — the Java DSL above and document-based authoring (see `dev.vertique:vertique-workflow-definition`) are alternative ways to produce one.

The engine records the definition id, definition version, and plan hash when an instance starts. Resumes use that pinned version rather than silently switching to the latest registered definition.

### Workflow Registry

`WorkflowRegistry` stores all registered definition versions. Applications normally register definitions through Dagger `WorkflowContributor` multibindings:

```java
@Provides
@IntoSet
static WorkflowContributor orderFulfillment(OrderFulfillmentDefinition definition) {
    return registry -> registry.register(definition);
}
```

The registry validates definitions at registration time. Invalid graphs fail before they can serve traffic.

### Workflow Operations

`WorkflowOperations` is the SQL-free application facade:

```java
Future<WorkflowInstanceId> start(StartCommand cmd);
Future<Void> signal(WorkflowInstanceId id, String signalName, Object payload, String signalDedupKey);
Future<Void> cancel(WorkflowInstanceId id, String reason);
Future<Void> retry(WorkflowInstanceId id);
Future<WorkflowView> query(WorkflowInstanceId id);
Future<Void> migrate(WorkflowInstanceId id, long targetVersion);
```

The Postgres implementation opens its own transaction for each call. Code that already owns a transaction should use `TransactionalWorkflowOperations<TX>`.

Instance state is modeled as a single current snapshot plus an append-only history: `query` returns the latest snapshot, and each transition that produced it is recorded as a separate history entry.

Starts require an idempotency key. `StartCommand.requestedDefinitionVersion` may pin a new instance to a specific registered version; when omitted, the current version is used. Idempotency and dedup keys are scoped to what they protect — a start key is scoped to the definition id and a signal dedup key is scoped to the workflow instance; see `dev.vertique:vertique-workflow-postgresql` for the complete key-scope table across all operations.

### Durable Context on the Instance

`WorkflowInstance` carries an 18th component, `@Nullable DurableMetadata metadata` — the durable
context (correlation, tenant, and other registered namespaces) captured once at start time, using
the framework's standard `{"context": …}` carrier shape. It is `null` when no ambient durable context was
present at start (empty capture) or for instances started before this field existed.

The field is immutable for the instance's lifetime: no `withMetadata(...)` updater exists, and
every other `with*` updater (`withVersion`, `withStatus`, `withCurrentStepId`, `withWait`,
`withState`, `withError`, `withUpdatedAt`) passes it through unchanged. No transition, migration,
retry, or repair operation may overwrite it.

At drive time, the engine treats this carrier as a base-wins/instance-fill *fill* source, not an
override — a drive's live context (ambient, signal carrier, branch token, or timer record) stays
authoritative for every namespace it carries; the instance's captured context only fills namespaces
the drive's own base is missing. See `dev.vertique:vertique-workflow-engine` for the binder that
implements this composition.

`TransactionalWorkflowOperations.signal` has an additional 8-arg overload that accepts an explicit
`@Nullable DurableMetadata signalMetadata` carrier alongside the existing branch-targeting
parameters:

```java
Future<Void> signal(
        WorkflowInstanceId id,
        String signalName,
        Object payload,
        String signalDedupKey,
        @Nullable String forkStepId,
        @Nullable String branchId,
        @Nullable DurableMetadata signalMetadata,
        TX tx);
```

**No-silent-drop contract.** The interface's default implementation delegates to the 7-arg overload
only when `signalMetadata` is `null` — behavior is unchanged for every existing caller. When
`signalMetadata` is non-null and the implementing class does not override this 8-arg method, the
call fails fast with `UnsupportedOperationException` instead of silently discarding the supplied
carrier. An implementation that wants to honor an explicit carrier must override this method
directly; the engine (`WorkflowEngine`) does so.

---

## Typed Contracts

Typed workflow clients are Java interfaces annotated with `@WorkflowContract`. The runtime proxy validates the interface once and routes methods to `WorkflowOperations`.

```java
@WorkflowContract(definitionId = "order-fulfillment", definitionVersion = 1)
public interface OrderFulfillmentWorkflow {

    @WorkflowStart
    Future<WorkflowInstanceId> placeOrder(
        @IdempotencyKey String idempotencyKey,
        PlaceOrder command);

    @WorkflowSignal("inventory.reserved")
    Future<Void> inventoryReserved(
        WorkflowInstanceId workflowId,
        @SignalDedupKey String dedupKey,
        InventoryReserved event);

    @WorkflowQuery("status")
    Future<WorkflowView> status(WorkflowInstanceId workflowId);
}
```

Create a proxy through `WorkflowClientFactory`:

```java
@Provides
@Singleton
static OrderFulfillmentWorkflow orderWorkflow(WorkflowClientFactory factory) {
    return factory.create(OrderFulfillmentWorkflow.class);
}
```

Contract annotations are useful for type-safe application code. Document-defined workflows still use a contract class today; see `dev.vertique:vertique-workflow-definition` for the typed document model and its current limits.

---

## Subjects And Object Workflows

`WorkflowSubjectRef` links a workflow instance to a domain object. A subject is application-defined metadata, usually object type, object id, and version.

```java
wf.subject(state -> new WorkflowSubjectRef(
        "article",
        state.articleId(),
        Long.toString(state.articleVersion())));
```

Callers can also pass a subject in `StartCommand`; an explicit start command subject wins over a definition-level resolver.

Human tasks can require version stability:

```java
wf.task("editorial-review")
  .assignToRole("editors")
  .requireVersionStability()
  .decision("approve", void.class)
      .onDecision((state, ignored) -> state.approved())
      .toStep("publish")
  .build();
```

Decisions carry typed payloads; `void.class`, as used for `approve` above, declares a decision with no payload.

When version stability is required, task completion compares the version the reviewer saw with the version snapshotted when the task was created. The engine does not read live domain content; applications remain responsible for authorization and for loading current object state.

This is the intended fit for Vertique Objects: workflow state should carry object type/id/version and workflow-local progress, not a copy of the object document.

---

## Side Effects

The engine does not call downstream services directly. Workflow steps emit `WorkflowSideEffectIntent` values, and installed recorders persist those intents inside the caller's transaction.

Recorder implementations must be durable and transactional. They must not make live downstream calls before commit.

Common recorders:

| Intent | Recorder module |
|---|---|
| Service dispatch | `vertique-workflow-services` |
| Workflow timer | `vertique-workflow-delayed` |
| External workflow event | `vertique-workflow-events` |

Missing required recorders fail the workflow transition rather than silently dropping work.

---

## Required Wiring

A minimal durable workflow app usually includes:

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
    WorkflowClientFactory workflowClientFactory();
}
```

Add `WorkflowDelayedModule`, `WorkflowTasksModule`, `WorkflowEventsModule`, or `WorkflowDefinitionModule` only when the application uses those capabilities.

---

## Public Extension Points

| Extension point | Purpose |
|---|---|
| `WorkflowContributor` | Register workflow definitions with the registry |
| `WorkflowSideEffectRecorder<TX>` | Persist workflow side-effect intents |
| `TransactionalWorkflowOperations<TX>` | Drive workflows inside an existing transaction |
| `TaskStore<TX>` | Store human task rows |
| `TransactionalTaskCallbacks<TX>` | Engine callback surface used by the task service |
| `TimerStore<TX>` and timer callbacks | Timer persistence and timer firing integration |
| `WorkflowMigrationHandler` | Move an in-flight instance to a newer definition version |

Application code should normally depend on the high-level services first and implement these SPIs only when replacing a storage or delivery backend.

### WorkflowActorMapper

SPI (`dev.vertique.workflow.actor`) that maps a `SecurityIdentity` to a `WorkflowActor`. Provides the
bridge between the framework's identity model and the workflow audit identity model — every mutating
workflow task operation requires a `WorkflowActor` so the append-only workflow history records who
initiated the change.

```java
public interface WorkflowActorMapper {
    WorkflowActor toWorkflowActor(SecurityIdentity identity);
}
```

The default implementation (`DefaultWorkflowActorMapper`, bound by `WorkflowCoreModule`) applies these
mapping rules:

| `PrincipalType` | `WorkflowActor` subtype |
|---|---|
| `USER` | `WorkflowActor.User(actor.id())` |
| `SERVICE` | `WorkflowActor.Service(actor.id())` |
| `SYSTEM` | `WorkflowActor.System(actor.attributes().get("system.reason") ?: "unspecified")` |
| `ANONYMOUS` | `IllegalArgumentException` — workflow operations require a named actor |

Anonymous identities must be rejected at the authorization layer before reaching the workflow engine.
Override by providing a custom `@Provides WorkflowActorMapper` binding when a service principal
should map to a specific system reason rather than a generic service id.

```java
@Provides
WorkflowActorMapper customActorMapper() {
    return identity -> switch (identity.actor().type()) {
        case USER -> new WorkflowActor.User(identity.actor().id());
        case SERVICE -> new WorkflowActor.Service("billing-service");
        case SYSTEM -> new WorkflowActor.System("scheduled-maintenance");
        case ANONYMOUS -> throw new IllegalArgumentException("anonymous actor not permitted");
    };
}
```

---

## Exceptions

`workflow-core` defines the semantic exception hierarchy applications catch when workflow operations fail. Each root maps to the matching core framework exception root and its default HTTP status:

| Exception | Core root | HTTP |
|---|---|---|
| `WorkflowException` | `BusinessRuleException` | 400 |
| `WorkflowConflictException` | `ConflictException` | 409 |
| `WorkflowNotFoundException` | `NotFoundException` | 404 |
| `WorkflowConfigurationException` | `ConfigurationException` | — |
| `WorkflowTechnicalException` | `TechnicalException` | 500 |
| `WorkflowUnavailableException` | `UnavailableException` | 503 |
| `WorkflowPersistenceException` | extends `WorkflowTechnicalException` (carries a `retryable` flag) | 500 |

`WorkflowException` is the business-rule root only, not the superclass of every workflow failure — missing resources, state conflicts, configuration problems, unavailable capabilities, and technical failures use their own roots above instead.

`vertique-workflow-engine` translates DB-origin failures into this hierarchy at the persistence boundary; see `dev.vertique:vertique-workflow-engine` for the translation table.

---

## Invariants & Gotchas

Behaviors that are load-bearing for correct use and not obvious from method signatures alone:

- **`WorkflowBuilder.init(payloadType, initialState)` is mandatory and may be called only once.** It must be the first builder call in a definition. Missing or duplicate `init` is rejected at registration with `WorkflowDefinitionException`.
- **Plan hash pinning.** Each `WorkflowInstance` records the `definitionId`, `definitionVersion`, and `planHash` it started under. Resumes execute against that pinned plan, not the currently registered "latest" version. Use `WorkflowOperations.migrate(...)` plus a `WorkflowMigrationHandler` to move an in-flight instance to a newer version.
- **Missing recorders fail the transition.** When a step emits a side-effect intent and no `WorkflowSideEffectRecorder<TX>` is registered for that `IntentKind`, the transaction fails. There is no silent no-op. Optional intent kinds may be declared via `@OptionalIntentKinds` and are no-ops only when explicitly opted in.
- **Recorders must be durable, never live.** A `WorkflowSideEffectRecorder<TX>` may persist an outbox row, schedule a delayed job, or write to another transactional store, but must not call a downstream service before commit.
- **Human task DSL guard rails.** A task step's decision list must be non-empty with unique decision names (enforced by the `HumanTaskNode` compact constructor). The optional due-date triplet (`dueDate`, `onDueMutator`, `dueNextStepId`) is all-or-nothing — supplying a partial subset is rejected at construction.
- **`TaskFilter` single-assignee-kind guard.** At most one of `assigneeUser` / `assigneeRole` / `assigneeQueue` may be set, mirroring the single-`assignee_type` storage shape. Use `TaskFilter.empty()` plus `with*` copies to compose filters.
- **Version-stable approvals.** `.requireVersionStability()` on a task captures `subject_version` at task creation; completion fails with `WorkflowStaleSubjectVersionException` when the reviewer's `reviewedSubjectVersion` does not match the snapshot. Creating a stability-required task with a null `subject_version` fails with `WorkflowSubjectVersionUnavailableException`.
- **Subject resolver precedence.** An explicit `StartCommand.subjectRef` wins; otherwise a definition-level `.subject(...)` resolver is invoked with the initial state. A configured resolver returning `null` is a contract violation (`WorkflowDefinitionException`), not a silent "no subject".
- **`WorkflowActor` is required on every task mutation command.** `TaskCompletionCommand` and `TaskReassignmentCommand` reject null / blank actor identity through their compact constructors. Blank `reason` is normalized to `null` before the command is fingerprinted for idempotency.

---

## Dependencies

- **core** - common Vertique exception and module conventions.
- **db-core** - `PageCursor` and `PagedResult` used by task and workflow query APIs.
