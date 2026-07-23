<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Workflow

> **Status:** Stable

Vertique Workflow is the framework's durable orchestration layer. It lets applications model long-running work as versioned workflow definitions, persist each instance, wait for external signals, create human tasks, schedule timers, dispatch service side effects through the transactional outbox, and resume safely after process restarts.

Use Workflow when the business process outlives a single request or requires explicit coordination between services, people, timers, and state transitions.

---

## Core Use Cases

| Use case | What Workflow provides |
|---|---|
| Service orchestration | Dispatch work to service contracts through durable outbox entries |
| Approval flows | Create human tasks, capture decisions, and update workflow state |
| Scheduled transitions | Wait until a timer, due date, publish time, or expiration time |
| Signal-driven processes | Suspend until an external event or service callback arrives |
| Fan-out/fan-in | Run parallel branches and join them before continuing |
| Object lifecycle workflows | Orchestrate review, publish, expire, archive, or remediation around object ids and versions |
| Document-authored flows | Load typed YAML/JSON workflow definitions without changing the runtime engine |

Workflow is not a replacement for simple request/response service calls. If all work finishes inside one request and does not need durable retry, waiting, human action, or long-running state, use regular services.

---

## Mental Model

### Definition

A workflow definition describes the step graph for one workflow version. Definitions can be written in Java with the builder DSL or loaded from YAML/JSON through the workflow definition module.

Definitions are registered by id and version. New workflow instances pin the definition version they start with.

### Plan

Registration compiles a definition into a deterministic workflow plan. The plan stores serializable step metadata, callback ids, type names, and a plan hash. Java callback functions are kept in a callback registry and referenced by id.

The engine executes plans. It does not parse source files or YAML during runtime transitions.

### Instance

A workflow instance is one running copy of a definition. It stores:

- definition id and pinned version
- current step
- status
- serialized workflow state
- optional business key
- optional subject reference
- plan hash

Instances are persisted by `workflow-postgresql`.

### State

Workflow state is the private state of the orchestration. It should contain the facts needed to continue the workflow, not arbitrary copies of external domain data.

For object-backed workflows, state should usually carry object type, object id, object version, transition id, and workflow-local progress. The object store remains the source of truth for object content.

### Signals

A signal is an external event delivered to a waiting workflow instance. Signals have names, payloads, and dedup keys. They are useful for service callbacks, webhooks, message consumers, and user actions that resume a waiting workflow.

### Tasks

A human task suspends a workflow until a user or system completes a named decision. Tasks can be assigned to users, roles, or queues. Decisions may carry typed payloads and update workflow state.

Tasks can require subject-version stability, which is useful for approvals over mutable domain objects.

### Timers

Timers suspend a workflow until a time is reached. Timers support standalone waits, signal timeouts, task due dates, reminders, publish-at steps, and expire-at steps.

Durable timer execution is provided by `workflow-delayed`. Each `TimerRecord` carries a `metadata: DurableMetadata` field that holds the namespaced durable propagation context captured at timer-create time. See [Durable Context Propagation](#durable-context-propagation) below.

### Side Effects

The engine does not make live downstream calls inside a workflow transaction. Instead, it emits side-effect intents. Installed recorders persist durable handoff records inside the same transaction.

Common side effects are service dispatch, timer scheduling, and workflow events.

---

## Module Map

| Module doc | Artifact | Purpose |
|---|---|---|
| [workflow-core.md](../vertique-workflow/vertique-workflow-core/src/main/resources/META-INF/vertique/module.md) | `vertique-workflow-core` | Public APIs, builder DSL, registry, contracts, task/timer/side-effect SPIs |
| [workflow-postgresql.md](../vertique-workflow/vertique-workflow-postgresql/src/main/resources/META-INF/vertique/module.md) | `vertique-workflow-postgresql` | Durable Postgres engine and stores |
| [workflow-services.md](../vertique-workflow/vertique-workflow-services/src/main/resources/META-INF/vertique/module.md) | `vertique-workflow-services` | Service-dispatch outbox recorder and workflow signal service contract |
| [workflow-delayed.md](../vertique-workflow/vertique-workflow-delayed/src/main/resources/META-INF/vertique/module.md) | `vertique-workflow-delayed` | Durable timers through delayed jobs and cron recovery |
| [workflow-tasks.md](../vertique-workflow/vertique-workflow-tasks/src/main/resources/META-INF/vertique/module.md) | `vertique-workflow-tasks` | Application-facing task list/get/complete/reassign API |
| [workflow-events.md](../vertique-workflow/vertique-workflow-events/src/main/resources/META-INF/vertique/module.md) | `vertique-workflow-events` | Durable external workflow event stream |
| [workflow-definition.md](../vertique-workflow/vertique-workflow-definition/src/main/resources/META-INF/vertique/module.md) | `vertique-workflow-definition` | Typed YAML/JSON workflow definition authoring |

---

## Which Modules Do I Need?

| Need | Install |
|---|---|
| Define workflows and use core APIs | `WorkflowCoreModule` |
| Persist and execute workflows | `WorkflowPostgresqlModule` |
| Dispatch workflow service calls | `WorkflowServicesModule` plus transactional messaging service relay |
| Wait on timers or signal timeouts | `WorkflowDelayedModule`, delayed jobs, persistent cron |
| Human task inbox/actions | `WorkflowTasksModule` |
| Emit workflow lifecycle events | `WorkflowEventsModule` plus an outbox destination binding |
| Load YAML/JSON definitions | `WorkflowDefinitionModule` |
| Migrate in-flight instances between versions | `WorkflowMigrationModule` with migration handlers |

Most production workflow apps install core, postgresql, services, transactional messaging, database modules, and then add delayed/tasks/events/definition based on the workflow features they use.

---

## Common App Wiring

Minimal durable service-orchestration setup:

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

Add human tasks and timers:

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    WorkflowCoreModule.class,
    WorkflowPostgresqlModule.class,
    WorkflowServicesModule.class,
    WorkflowDelayedModule.class,
    WorkflowTasksModule.class,
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
    TaskService taskService();
    WorkflowDelayedComposeValidator workflowDelayedComposeValidator();
    WorkflowTasksComposeValidator workflowTasksComposeValidator();
}
```

Add document-authored workflows:

```java
@Singleton
@Component(modules = {
    WorkflowCoreModule.class,
    WorkflowPostgresqlModule.class,
    WorkflowServicesModule.class,
    WorkflowDefinitionModule.class,
    AppWorkflowDefinitionCallbacksModule.class,
    AppWorkflowDefinitionSourcesModule.class
})
public interface AppComponent {
    WorkflowOperations workflowOperations();
    WorkflowDefinitionService workflowDefinitionService();
}
```

Exact wiring depends on the application's persistence, messaging, delayed-job, and event-destination modules.

---

## Authoring Models

### Java DSL

Use the Java DSL when workflow logic is application-owned and benefits from compile-time types:

```java
wf.init(PlaceOrder.class, cmd -> new OrderState(cmd.orderId()))
  .initialStep("reserve-inventory")
  .dispatch("reserve-inventory", "inventory.reserve",
      state -> new ReserveInventoryRequest(state.orderId()),
      "await-inventory")
  .waitFor("await-inventory", "inventory.reserved",
      InventoryReserved.class,
      (state, event) -> state.withInventoryReserved(true),
      "ship")
  .complete("done");
```

### Typed Documents

Use YAML/JSON definitions when workflow shape should be data-driven while Java still supplies the type model and callback catalog.

```yaml
definitionId: order-fulfillment
definitionVersion: 1
stateType: com.example.OrderState
contract: com.example.OrderFulfillmentWorkflowV1
startPayloadType: com.example.PlaceOrder
initialStateMapper: order.fromPlaceOrder
initialStep: reserve-inventory
steps:
  - id: reserve-inventory
    type: service
    target: inventory.reserve
    payloadMapper: order.reserveInventoryPayload
    next: done
  - id: done
    type: complete
```

Document definitions are not currently no-code object schemas. They still reference Java classes and named callbacks.

---

## Durable Context Propagation

Workflow timers, branches, instances, signals, and commands all participate in the framework's durable context propagation substrate. Every engine drive binds an *effective* durable-context document computed from a **base** (the most specific context available for that drive) with an **instance-fill** step that fills only namespaces the base does not already carry.

### New metadata fields (workflow-core)

| Type | Field | Description |
|------|-------|-------------|
| `TimerRecord` | `metadata: DurableMetadata` | Namespaced durable propagation context captured at timer-create time; persisted as `{"context": …}` JSONB; immutable after creation |
| `BranchToken` | `metadata: DurableMetadata` | Namespaced durable propagation context captured at branch-create (fork) time; persisted as `{"context": …}` JSONB; immutable through all subsequent state transitions |
| `WorkflowInstance` | `metadata: DurableMetadata` | Namespaced durable propagation context captured once at `start` time; persisted as `{"context": …}` JSONB on `workflow_instances`; `null` when the ambient capture at start was empty (no empty-document rows); immutable for the life of the instance — no transition, migration, retry, or repair operation overwrites it |

### Instance carrier and the `WorkflowContextBinder`

At `start`, the engine captures the ambient durable context via the propagator at the `WORKFLOW` boundary and persists it on the new instance row within the start transaction. `start` itself is capture-only — it opens no new durable scope; the ambient it captures *is* the context being persisted, and fork-at-start branch capture continues to read live ambient exactly as it did before this carrier existed.

Every later drive computes an **effective document**: `base.merge(instanceMetadata, MergePolicy.CALLER_WINS)`. The base is authoritative for every namespace it carries; the instance carrier contributes only namespaces the base lacks. An empty base with a non-`null` instance carrier therefore binds the instance context in full. The base is chosen per drive, in priority order: an explicit signal carrier for that transition, else the drive's own persisted carrier (branch-token or timer-row metadata), else the ambient inbound context for caller-invoked and transport-dispatched drives, else the empty document.

**Bind gate (the dark-path guarantee):** when the instance carrier is `null` and the drive carries no explicit signal carrier, the drive binds exactly as it always has — no instance fill, no new scope. An application that registers zero durable namespaces therefore sees every instance carrier persist as `null` and every drive stay bit-for-bit unchanged; there is no separate kill switch, the `null`-skip chain *is* the dark path. An explicit signal carrier always binds regardless of the instance carrier — it is the sender's deliberate handoff, not instance fill.

**Bind-once routing rule:** every drive gets exactly one durable bind, at exactly one seam.

- **Binder rows** — instance-owned single-path drives (`signal` without a branch target, `cancel`, `retry`, `migrate`, and single-path `taskCompleted`/`taskReassigned`/`timerFired`/`timerFiringFailed`/`taskDueFired`/`taskReminderFired` drives with no branch token involved) bind through the internal `WorkflowContextBinder` collaborator (`vertique-workflow-engine`), which implements the gate, base selection, and instance-fill merge above, then installs the effective document via `InboundExecutionContextScope.installDurable(...)` before running the drive.
- **Branch-owned drives** — branch-targeted signals, branch-owned timer fires and task completions, and both branch recovery sweeps — keep their bind on the existing `BranchTransitionEngine`/branch-recovery carrier seam (below), which now receives the merged effective base through an added `effectiveBaseOverride` parameter instead of using raw `token.metadata()` directly. A drive is never bound at both seams.

Instance fill happens before registered `InboundContextInitializer`s run (notably the correlation seeder), so a namespace the instance carrier supplies is never re-seeded — a drive with an absent-from-base correlation namespace and a `null` instance carrier still gets a freshly seeded correlation, matching existing timer-recovery behavior.

**Fork absorption:** a branch forked during a filled drive persists the *effective* (base + instance-fill) document into its token, because fork-time capture reads the bound context holder unchanged by this feature. This intentionally closes the "carrier holds only what was ambient at fork time" gap for every branch forked after instance carriers exist, without any change to how forks capture context.

### Explicit signal carrier

A signal can carry an explicit durable-context carrier on the wire: `WorkflowSignalRequest.metadata` — an optional `@Nullable JsonObject` holding the raw `{"context": …}` shape produced by `DurableMetadata.toCarrier()`. When present, it is authoritative as the base for that transition only (including, for a branch-targeted signal, winning over the branch token's persisted metadata); it is never persisted onto the instance or the branch token. `WorkflowSignalContributor` decodes it via `DurableMetadata.fromCarrier(...)` before calling the metadata-aware `TransactionalWorkflowOperations.signal(..., @Nullable DurableMetadata signalMetadata, TX tx)` overload. That overload's default implementation delegates to the legacy 7-arg overload when the carrier is `null` (behavior unchanged) and fails fast with `UnsupportedOperationException` on a non-`null` carrier unless the implementation overrides it — the carrier is never silently dropped. `WorkflowEngine` overrides it with the real bind.

`DurableMetadata.fromCarrier`/`fromJson` validate carrier shape at decode time and raise `MalformedDurableMetadataException` (extends `ValidationException`) rather than letting a malformed nested value surface as a raw `ClassCastException` in a later merge or read path.

**Security note:** the signal ingress endpoint (`workflow.signals.post`) is internal-relay-only, and an explicit carrier is bound as the authoritative durable-context base for its transition — it MUST NOT be populated from untrusted or end-user input. Only the relay that produced the carrier via `DurableMetadata.toCarrier()` may supply it. Provenance authentication must derive trusted namespaces from the authenticated `SecurityContext` rather than trust a carrier verbatim.

### Command correlation stitching

Command drives — task completion, task reassignment, cancel, retry, migrate — append history with an additive `commandCorrelationId` field, sourced from `ContextValues.current(CorrelationContext.class)` inside the bound drive. This is a causal record of who drove the transition, independent of which namespaces filled the effective document. The field is additive on `TaskCompletedHistoryPayload`, `TaskReassignedHistoryPayload`, `TaskCancelledHistoryPayload`, `RetriedHistoryPayload`, `WorkflowMigratedHistoryPayload`, and the workflow-level cancel history payload — old rows deserialize with `commandCorrelationId = null`.

### Duplicated-context requirement for newly bound drives

**Consumer-visible behavior break, accepted pre-release:** application callers invoking the public `TransactionalWorkflowOperations` tx-variants or `TaskService` mutations from a **non-duplicated** Vert.x context — which succeed today — observe `IllegalStateException` (FR-CTX-157b) once those drives pass the bind gate (non-`null` instance carrier or an explicit signal carrier). The break is data-dependent: it manifests only when the gate passes; `null`-carrier instances never trip it. Every dispatcher entry point in production (event bus, Kafka per-record, cron `@CronJob` callback, service-method invoker) already runs on a duplicated Vert.x context, and `vertx-sql-client` 5.x preserves the caller's duplicate through `pool.withTransaction(tx -> …)`. Tests that enter the engine from a JUnit thread MUST first switch to a duplicated context via `((ContextInternal) vertx.getOrCreateContext()).duplicate().runOnContext(...)` (the same duplicated-context pattern the branch-drive seam below already requires); transport relays that cannot reach a duplicated context MUST use `decodeToDispatchContext(...)` instead.

### Timer piggyback model

Workflow timers are implemented as delayed-job executions. Durable context is captured once at timer-create and written into two locations in the same transaction:

- `workflow_timers.metadata` — recovery-state source of truth; survives delayed-job dead-letter or DELETE
- `job_executions.metadata` — dispatch-time wire format used by `DelayedJobPoller`

`WorkflowTimerSideEffectRecorder` performs the dual write by encoding the captured `DurableMetadata` document into the `{"context": …}` carrier and writing it to both columns. The caller-supplied context document is empty by construction, so no collision with framework-captured namespaces is possible.

Timer fire restores context through the standard delayed-job dispatch pipeline (`DelayedJobPoller.dispatch` decode + receive-side `InboundDispatchScope`). There is no timer-fire-specific binding in `WorkflowTimerFireExecutor`.

Timer recovery (`WorkflowTimerRecoveryService`) copies the `DurableMetadata` document from `workflow_timers.metadata` into the replacement delayed-job via `DelayedJobOptions.premergedMetadata`, which routes through `DelayedJobService.enqueuePremerged` with no re-capture.

### Branch-drive seam

`BranchTransitionEngine.driveBranchTransitions` binds `token.metadata()` once per per-branch drive via `InboundExecutionContextScope.installDurable(metadata, DispatchBoundary.WORKFLOW)`. The bind covers all branch step types: timer-step, signal-step, service-dispatch, and decision. The scope closes on `Future.eventually(...)`.

`driveBranchTransitions` takes an additional `@Nullable DurableMetadata effectiveBaseOverride` parameter. When non-`null`, the drive binds that already-computed effective document instead of raw `token.metadata()` — this is how branch recovery (below) and a branch-targeted explicit signal carrier route their merged base + instance-fill document into the branch carrier seam without a second, competing bind. When `null`, behavior is exactly what it was before instance carriers existed: bind `token.metadata()` with the empty-document fallback.

The substrate helper wraps the authoritative `DurableContextPropagator.bindFrom(...)` — every registered durable namespace absent from `token.metadata()` is cleared for the drive's scope lifetime. This prevents the drive's ambient context (e.g. a service handler that triggered the workflow signal) from leaking into a subsequent durable-context encode at a downstream seam — specifically the `WorkflowTimerSideEffectRecorder` dual-write. An empty `DurableMetadata` document still triggers the bind; it clears whatever ambient durable namespaces are live on the call-site context. After the durable bind, registered `InboundContextInitializer`s run (notably the correlation seeder), so branches whose metadata carries no encoded `CorrelationContext` still get one bound for the drive (FR-COR-125).

Production reaches this on a duplicated context: every dispatcher (event bus, Kafka per-record, cron `@CronJob` callback, service-method invoker) is on a duplicated Vert.x context, and `vertx-sql-client` 5.x preserves the caller's duplicate through `pool.withTransaction(tx -> …)`. Per FR-CTX-157b, `installDurable` propagates the strict `bindFrom` invariant: `IllegalStateException` on a non-duplicated Vert.x context (no-op only when there is no Vert.x context at all). Tests that enter the engine from a JUnit thread MUST first switch to a duplicated context via `((ContextInternal) vertx.getOrCreateContext()).duplicate().runOnContext(...)`; transport relays that cannot reach a duplicated context MUST use `decodeToDispatchContext(...)` instead.

### Branch-recovery seam

`PgWorkflowBranchRecoveryService.withBranchDurableBound` wraps every per-branch advance in the recovery sweep — both the due-branch path (`processDue`) and the stale-RUNNING path (`processStale`). Branches are processed sequentially so branch N's scope closes before N+1's opens.

Recovery loads the owning instance before the durable scope opens (never inside it) and computes `token.metadata().merge(instance.metadata(), MergePolicy.CALLER_WINS)` — a no-op merge when the instance carrier is `null` — passing that effective document into `driveBranchTransitions`'s `effectiveBaseOverride`. This means a branch parked with a degraded carrier (a namespace hole from a pre-feature fork, or a transport relay that lost a namespace) picks up the missing namespace(s) from the instance carrier the next time recovery drives it, while the carrier itself remains authoritative for every namespace it already holds.

### Branch metadata immutability

Branch metadata is set at fork time and is immutable thereafter. State transitions — retry-scheduled and terminal failure (FailNode-driven or recovery-driven) — preserve the original `token.metadata()` unchanged. No subsequent engine operation overwrites it.

---

## Operational Principles

- Always provide idempotency or dedup keys where APIs require them.
- Treat workflow state as orchestration state, not a shadow copy of domain records.
- Use subjects to link workflows to domain objects.
- Keep downstream calls behind side-effect recorders and transactional outbox handoff.
- Install durable timer infrastructure for any workflow that waits on time.
- Keep in-flight instances pinned to their started version unless explicitly migrated.
- Put authorization in the application layer. Workflow records actors and decisions; it does not decide access policy.

---

## Where To Go Next

- Start with [workflow-core.md](../vertique-workflow/vertique-workflow-core/src/main/resources/META-INF/vertique/module.md) for concepts and public APIs.
- Add [workflow-postgresql.md](../vertique-workflow/vertique-workflow-postgresql/src/main/resources/META-INF/vertique/module.md) for durable execution.
- Add [workflow-services.md](../vertique-workflow/vertique-workflow-services/src/main/resources/META-INF/vertique/module.md) for service dispatch.
- Add [workflow-delayed.md](../vertique-workflow/vertique-workflow-delayed/src/main/resources/META-INF/vertique/module.md) for timers and scheduled transitions.
- Add [workflow-tasks.md](../vertique-workflow/vertique-workflow-tasks/src/main/resources/META-INF/vertique/module.md) for human task APIs.
- Add [workflow-events.md](../vertique-workflow/vertique-workflow-events/src/main/resources/META-INF/vertique/module.md) for external event streams.
- Add [workflow-definition.md](../vertique-workflow/vertique-workflow-definition/src/main/resources/META-INF/vertique/module.md) for YAML/JSON authoring.

---
