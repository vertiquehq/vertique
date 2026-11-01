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

### Glossary

| Term | Meaning |
|---|---|
| Contract | A Java interface annotated `@WorkflowContract` that declares the operations of a workflow: one start method, signal methods and query methods. A contract names no definition and no version. Any number of definitions and versions may implement one contract |
| Definition | One version of a workflow: a `WorkflowDefinition` class written in Java, or a YAML/JSON document. It has an id, a version, a state type and a contract |
| Plan | The compiled, immutable form of a definition that the engine executes, identified by a plan hash |
| Workflow type | A state or payload class that a plan names, optionally given a stable logical name with `@WorkflowType` |
| Type catalog | A list of the contract and workflow-type classes under their names. The annotation processor generates one for each module; a document loaded at runtime can name only classes that a catalog or a registered definition lists |
| Name binding | The names a stored version was hashed with. It keeps a published version's plan hash stable when a class is annotated or moved |
| Scope | A string key that groups definitions, instances and tasks. `default` is the reserved default scope. A scope is a label and a filter, not a security boundary |
| Publish | Make a loaded document available. A document is loaded as a candidate and becomes `AVAILABLE` when published; code definitions are available when deployed |
| Activation | The record that says which version a start without a version runs, per scope and definition id |
| Current version | The version that activation selects for a start without a version |
| Client | A typed proxy of a contract bound to one definition id, created from `WorkflowClientFactory` |
| Instance | One running copy of a definition, pinned to a definition scope, id and version |

### Definition

A workflow definition describes the step graph for one workflow version. Definitions can be written in Java with the builder DSL or loaded from YAML/JSON through the workflow definition module.

A definition is identified by a scope, an id and a version. Code definitions always belong to the default scope; documents can be loaded into any scope. New workflow instances pin the definition version they start with.

### Plan

Registration compiles a definition into a deterministic workflow plan. The plan stores serializable step metadata, callback ids, type names, and a plan hash. Java callback functions are kept in a callback registry and referenced by id.

The engine executes plans. It does not parse source files or YAML during runtime transitions.

### Instance

A workflow instance is one running copy of a definition. It stores:

- start scope and the pinned definition scope, id and version
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

A signal is an external event delivered to a waiting workflow instance. Signals have names, payloads, and dedup keys. They are useful for service callbacks, webhooks, message consumers, and user actions that resume a waiting workflow. An event that carries a business reference, such as an order id, and not a workflow instance id can resume the instance that waits for it: see [Signals from events with a reference](#signals-from-events-with-a-reference).

### Tasks

A human task suspends a workflow until a user or system completes a named decision. Tasks can be assigned to users, roles, or queues. Decisions may carry typed payloads and update workflow state.

Tasks can require subject-version stability, which is useful for approvals over mutable domain objects.

### Timers

Timers suspend a workflow until a time is reached. Timers support standalone waits, signal timeouts, task due dates, reminders, publish-at steps, and expire-at steps.

Timers need no module of their own: each timer is a command that the workflow executor of `workflow-postgresql` fires when it comes due. Each `TimerRecord` carries a `metadata: DurableMetadata` field that holds the namespaced durable propagation context captured at timer-create time. See [Durable Context Propagation](#durable-context-propagation) below.

### Side Effects

The engine does not make live downstream calls inside a workflow transaction. Instead, it emits side-effect intents. Installed recorders persist durable handoff records inside the same transaction.

Common side effects are service dispatch and workflow events.

### Recorded starts and the executor

`start` records and returns. In the caller's transaction it inserts the instance as `PENDING` (pin, business key and
durable carrier, no state, step or subject), its queue row and a `START` command; a rolled-back caller leaves nothing. The
executor, the `workflow-executor` verticle that `vertique-workflow-engine` contributes to the `SERVICES` phase, claims the
command with a lease, runs the first segment (initial state, subject, steps up to the first wait or the end) in one
transaction that also writes the segment's intents and history and completes the command, and keeps up to
`workflow.executor.concurrency` segments in flight per instance and `workflow.executor.instances` instances per node. A
segment that has not completed one margin before its lease ends is rolled back and another executor takes the command after the
lease. Wait for a recorded start with the execution probe. Signals, task completions, retries and migrations record commands the same way, and timers are commands the executor fires; only `cancel` and task reassignment run in the caller's transaction. See [Recorded Execution](#recorded-execution).

---

## Module Map

| Module doc | Artifact | Purpose |
|---|---|---|
| [workflow-core.md](../vertique-workflow/vertique-workflow-core/src/main/resources/META-INF/vertique/module.md) | `vertique-workflow-core` | Public APIs, builder DSL, registry, contracts, task/timer/side-effect SPIs |
| [workflow-postgresql.md](../vertique-workflow/vertique-workflow-postgresql/src/main/resources/META-INF/vertique/module.md) | `vertique-workflow-postgresql` | Durable Postgres engine and stores, the workflow schema migration declaration (`WorkflowMigrationsModule`) and the optional `db.schemas.workflow` placement |
| [workflow-services.md](../vertique-workflow/vertique-workflow-services/src/main/resources/META-INF/vertique/module.md) | `vertique-workflow-services` | Service-dispatch outbox recorder and workflow signal service contract |
| [workflow-kafka.md](../vertique-workflow/vertique-workflow-kafka/src/main/resources/META-INF/vertique/module.md) | `vertique-workflow-kafka` | Kafka topics as signal sources: builder, source classes and annotated listeners |
| [workflow-tasks.md](../vertique-workflow/vertique-workflow-tasks/src/main/resources/META-INF/vertique/module.md) | `vertique-workflow-tasks` | Application-facing task list/get/complete/reassign API |
| [workflow-events.md](../vertique-workflow/vertique-workflow-events/src/main/resources/META-INF/vertique/module.md) | `vertique-workflow-events` | Durable external workflow event stream |
| [workflow-definition.md](../vertique-workflow/vertique-workflow-definition/src/main/resources/META-INF/vertique/module.md) | `vertique-workflow-definition` | Typed YAML/JSON workflow definition authoring |
| [workflow-schema.md](../vertique-workflow/vertique-workflow-schema/src/main/resources/META-INF/vertique/module.md) | `vertique-workflow-schema` | Payload schemas for decision and start payloads, checked at definition registration, and the state-class comparison across scopes |
| [codegen-workflow.md](../vertique-codegen/vertique-codegen-workflow/src/main/resources/META-INF/vertique/module.md) | `vertique-codegen-workflow` | Annotation processor: contract validation, generated client proxies and the generated type catalog module |

---

## Which Modules Do I Need?

| Need | Install |
|---|---|
| Define workflows and use core APIs | `WorkflowCoreModule` |
| Persist and execute workflows | `WorkflowPostgresqlModule` (also contributes the workflow schema migration declaration), plus `WorkflowSchemaModule` and `JsonRuntimeModule` on every node that runs the executor: such a node fails startup without the schema producer |
| Run a node that only records starts, signals and task completions and runs no workflow code | `workflow.executor.enabled=false`; the node needs no workflow class and no `WorkflowSchemaModule`, and refuses document load, publication, reinstatement, `WorkflowRegistry.load` and `WorkflowPayloadSchemas` with `WorkflowNodeRoleException` |
| Run the workflow schema migration without starting the application | `WorkflowMigrationsModule` in a migration-only component (see below) |
| Deliver a signal by a key from a Kafka topic | `vertique-workflow-kafka` with `WorkflowSourceModule`, and `KafkaModule` with `KafkaJsonModule`; the inbox module with its migration and cleanup job; `WorkflowPostgresqlModule` on any node, executor or recording-only |
| Dispatch workflow service calls | `WorkflowServicesModule` plus transactional messaging service relay |
| Wait on timers or signal timeouts | Nothing to add: the workflow executor of `WorkflowPostgresqlModule` fires them as commands |
| Meters for the executor | `MicrometerWorkflowModule` from `vertique-micrometer-workflow`, with a bound `MeterRegistry` (see [Executor metrics](#executor-metrics)) |
| Human task inbox/actions | `WorkflowTasksModule` |
| Emit workflow lifecycle events | `WorkflowEventsModule` plus an outbox destination binding |
| Load YAML/JSON definitions | `WorkflowDefinitionModule` |
| Describe and pre-validate decision and start payloads | `WorkflowSchemaModule` |
| Migrate in-flight instances between versions or scopes | `WorkflowMigrationModule` with migration handlers (not `WorkflowMigrationsModule`: see below) |
| Compare state classes across scopes | `WorkflowSchemaModule` (see [Evolving state](#evolving-state)) |
| Generate the type catalog and typed client proxies | `vertique-codegen-workflow` on the processor path; add `GeneratedWorkflowTypeCatalogModule` to each component (see [Type catalog module](#type-catalog-module)) |

Most production workflow apps install core, postgresql, services, transactional messaging, database modules, and then add tasks/events/definition based on the workflow features they use.

---

## Common App Wiring

Minimal durable service-orchestration setup:

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    WorkflowCoreModule.class,
    WorkflowPostgresqlModule.class,
    WorkflowSchemaModule.class,   // the schema producer every executor node needs
    JsonRuntimeModule.class,      // the JSON bindings the schema module reads
    WorkflowServicesModule.class,
    GeneratedWorkflowTypeCatalogModule.class,  // generated by vertique-codegen-workflow, see below
    TransactionalMessagingPostgresqlModule.class,
    TransactionalMessagingServiceModule.class,
    DbModule.class,           // pool configuration and the optional db.schemas placement
    DbPostgresqlModule.class,
    DbFlywayModule.class,     // only when the application runs the migration at startup
    AppModule.class
})
public interface AppComponent {
    WorkflowOperations workflowOperations();
    WorkflowClientFactory workflowClientFactory();
}
```

Add human tasks:

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    WorkflowCoreModule.class,
    WorkflowPostgresqlModule.class,
    WorkflowSchemaModule.class,   // the schema producer every executor node needs
    JsonRuntimeModule.class,      // the JSON bindings the schema module reads
    WorkflowServicesModule.class,
    WorkflowTasksModule.class,
    TransactionalMessagingPostgresqlModule.class,
    TransactionalMessagingServiceModule.class,
    DbModule.class,
    DbPostgresqlModule.class,
    DbFlywayModule.class,
    AppModule.class
})
public interface AppComponent {
    WorkflowOperations workflowOperations();
    TaskService taskService();
    WorkflowTasksComposeValidator workflowTasksComposeValidator();
}
```

Add document-authored workflows:

```java
@Singleton
@Component(modules = {
    WorkflowCoreModule.class,
    WorkflowPostgresqlModule.class,
    WorkflowSchemaModule.class,   // the schema producer every executor node needs
    JsonRuntimeModule.class,      // the JSON bindings the schema module reads
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

Exact wiring depends on the application's persistence, messaging, and event-destination modules.

### Type catalog module

The annotation processor of `vertique-codegen-workflow` generates a `GeneratedWorkflowTypeCatalogModule` for every compilation unit that declares `@WorkflowContract` interfaces or `@WorkflowType` classes. Each component that runs workflows must include the generated module of every module that declares such classes. Without it, a document loaded at runtime or a packaged document that names an annotated class by its logical name fails with an unknown-name error, and classes that only a catalog lists cannot be resolved. Two catalogs that list different classes under one name fail startup. The generated module sits in the package given by the `vertique.codegen.package` processor option or, when that is unset, in the longest common package of the listed classes; set the option when two modules would otherwise generate a module with the same name. See the [codegen-workflow reference](../vertique-codegen/vertique-codegen-workflow/src/main/resources/META-INF/vertique/module.md#generatedworkflowtypecatalogmodule) for accessibility rules and warnings.

### Schema placement and migrations

The workflow tables live in the connection's default schema unless `db.schemas.workflow` maps them to another one. With `DbModule` installed, that single setting places all nine PostgreSQL workflow stores and, in named Flyway mode (`flyway.named`), the migration itself, so the migration and every statement agree. Without the entry, SQL stays unqualified. Components that migrate and components that run the workflows read the same `db.schemas` configuration.

The migration can run without the application: a migration-only component installs `DbModule`, `DbFlywayModule` and `WorkflowMigrationsModule` (plus the declaration modules of the other instances it migrates), needs no connection pool, and calls `FlywayMigrationRunner.run(vertx)` from a deploy step or job. The runtime component then installs `DbModule` and `DbPostgresqlModule` and needs no Flyway module unless it migrates at startup. See the [workflow-postgresql reference](../vertique-workflow/vertique-workflow-postgresql/src/main/resources/META-INF/vertique/module.md#flyway-configuration) for the configuration, the legacy single-Flyway mode and the class loader the runner uses.

Do not confuse the two similarly named modules: `WorkflowMigrationsModule` (plural, in `vertique-workflow-postgresql`) declares the database schema migration, while `WorkflowMigrationModule` (singular, in `vertique-workflow-core`) wires migration of in-flight workflow instances between definition versions.

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
stateType: order
contract: order-fulfillment
startPayloadType: place-order
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

`stateType`, `contract` and `startPayloadType` are logical names (`@WorkflowType` and `@WorkflowContract` values). A document loaded at runtime can name only classes that a type catalog or a registered definition lists; no class is loaded by a name the document supplies. A document that is packaged with the application is trusted and may also name a class by its binary name. Document definitions are not currently no-code object schemas. They still reference Java classes and named callbacks.

A document never names its scope: the scope is the first argument of `load`, and a top-level `scope` property is rejected as unknown. Runtime management uses Futures. Load a candidate into a scope, then publish its durable key:

```java
Future<CompiledDefinitionRef> published = definitionService
        .load("acme", content, DocumentFormat.YAML, sourceMetadata)
        .compose(ref -> definitionService.publish(new DefinitionKey(
                "acme", ref.definitionId(), ref.definitionVersion())).map(ref));
```

With PostgreSQL installed, every definition, code-first or document-authored, is also a row in a durable
registry that survives restarts and converges across nodes. See [Durable Definition Registry](#durable-definition-registry)
for the lifecycle and operations, and [Upgrading to 0.4.0](#upgrading-to-040) before moving an existing application.

---

## Contracts, Definitions And Clients

A contract is a Java interface annotated `@WorkflowContract`. It declares one `@WorkflowStart` method, any `@WorkflowSignal` methods and `@WorkflowQuery` methods, and names no definition and no version. A definition implements `WorkflowDefinition<S, C>` for a state type `S` and a contract `C`, and carries its own id and version.

```java
@WorkflowContract("order-fulfillment")
public interface OrderFulfillmentWorkflow { /* start, signal and query methods */ }

public final class OrderFulfillmentDefinition
        implements WorkflowDefinition<OrderState, OrderFulfillmentWorkflow> { /* id, version, define(...) */ }
```

- **Many definitions per contract.** Any number of definition ids and versions may name one contract. The registry keeps no map from a contract to one definition.
- **One contract per scope and id.** Every version of a definition id in one scope must name the same contract. A scope other than `default` cannot load an id that the default scope holds under another contract, unless the scope already holds that id.
- **Declared signals.** A definition that waits for a signal must declare it on its contract. Registration fails naming the definition and the undeclared signals. A signal sent to an instance whose pinned version does not wait for it fails with `WorkflowSignalRejectedException` and changes nothing.

### Creating clients

A client is always bound to one definition id. Create it from `WorkflowClientFactory`, from the class of a registered definition or from a contract and an id:

```java
OrderFulfillmentWorkflow orders = factory.create(OrderFulfillmentDefinition.class);
OrderFulfillmentWorkflow byId = factory.create(OrderFulfillmentWorkflow.class, "order-fulfillment");

OrderFulfillmentWorkflow pinned = factory.client(OrderFulfillmentDefinition.class)
        .scope("acme")
        .version(2)
        .create();
```

- **No client from a contract alone.** `create(Contract.class)` does not exist and the annotation processor generates no Dagger providers of clients. Create clients in your own `@Provides` method or where you need them.
- **From a definition class.** The class must be registered, under exactly one definition id; the client's contract is the definition's contract.
- **From a contract and an id.** The id need not be registered yet, because documents may be loaded later.
- **Version and scope.** A client starts the current version of its definition. The builder's `scope(startScope)` runs the start in that scope, `version(n)` starts a specific registered version, and `version(targetScope, n)` names the scope of that version, which must be the start scope or `default`.
- **Generated or reflective.** With `vertique-codegen-workflow` on the processor path the factory uses a generated proxy; otherwise it falls back to a reflective one. Both send the same command, and the proxy embeds no definition id or version.
- **Replays resolve first.** A start is looked up by start scope, definition id and idempotency key before any version is resolved, so a replay returns the original instance even after the current version moved or the pinned version was deprecated. A replay that names another version than the original pin fails with an idempotency conflict. A node that has no plan for the pin compares the contract stored for it in the durable registry; with a durable registry and no stored row for the pin, or without a durable registry and without another registered version of the id, the replay fails with `WorkflowVersionNotRunnableException`.

## Scopes

A scope is a string key (nonblank, at most 255 code points, no control characters). `WorkflowScopes.DEFAULT` (`"default"`) is the reserved default scope. It is a value, not a stored record: it always exists and needs no registration.

- **Definitions.** A definition row is identified by `(scope, id, version)`. Code definitions are always in the default scope. Documents are loaded into the scope you name, and each scope numbers its own versions of an id, so a scope's first version of `purchase-approval` is version 1 beside the default scope's own versions. Ids carry no scope prefix.
- **Instances and tasks.** An instance has a start scope (the scope its start named, or `default`) and a definition scope (the scope of the version it is pinned to). A task carries the start scope of its instance. A migration to another scope's definition changes only the definition scope, and the start scope stays for the life of the instance.
- **Which definition a start uses.** A start in a scope uses that scope's current version of the id when the scope holds versions of the id under the client's contract, and the default scope's current version otherwise. A scope that has no record of its own follows the default scope. See [Current version and activation](#current-version-and-activation).
- **Filters.** `WorkflowInstanceQuery` and `TaskFilter` accept a `scopeKey` that matches exactly when non-null; `null` matches every scope. Never pass an unresolved scope as `null` expecting scoping.
- **Information, not isolation.** A scope is a label and a filter. Operations addressed by instance or task id do not check it. Before acting on a caller-supplied id, load the instance and compare its `scopeKey()` with the caller's scope.

## Type Names And Name Bindings

A plan names its state type and its signal and decision payload types. By default a name is the class's binary name, so renaming or moving a class changes the plan hash. Annotate the class with `@WorkflowType("order.paid")` to give it a stable logical name, and a contract with `@WorkflowContract("orders")`.

- **Two namespaces.** Type names and contract names are separate. Within a namespace one logical name maps to one class; a second class under the same name fails startup with `WorkflowTypeNameConflictException`.
- **Unannotated classes keep their hash.** A class without `@WorkflowType` hashes by its binary name exactly as before.
- **Bindings keep published hashes.** With the durable registry, each stored version keeps a binding from the binary name of each class its plan names to the name its plan hash used. Annotating a class after a version was published keeps that version's hash and its live instances, while a version first published afterwards records and hashes the new logical name.
- **A bound class cannot move.** A class that a non-retired version is bound to by its binary name cannot move: startup fails naming the scope, the definition, the version and the recorded name. Keep the class where it is, or retire the version first. Retired versions are not checked. A class moved without annotation changes the hashes of the versions that use it.
- **Annotate before you move.** Annotate contracts and state and payload classes before moving them. A contract without `@WorkflowContract` is identified by its binary name, so moving it changes its logical name, and registration then refuses a version that names another contract than earlier versions.
- **Classes used by live 0.3.0 instances.** The first 0.4.0 startup records each deployed version with the names its classes have at that moment. Annotate a class that live 0.3.0 instances use only after those instances finish. If you annotate it in the deployment that first runs 0.4.0, the recorded hash differs from the hash the instances were started under, and startup fails with `DRIFT` naming both hashes. Annotating in a later deployment keeps the recorded hash through the binding, but waiting for the instances to finish is the rule that needs no further reasoning.

## Evolving State

All versions of one scope and definition id use one state class. Registration refuses a version that uses another.

- **Additive only.** A state class may evolve only by adding properties. Removing or retyping a property is not safe, and the registry does not check an in-place edit of a class. A property added to a type that stored state lacks must tolerate absence and read as the type's default.
- **Scope-owned state must extend the default's.** When a scope's definition and the default scope's definition of the same id name the same contract and different state classes, the scope's class must extend the default's: every property exists with the same type, nested objects, collection items and map values compare by the same rule, and an enum keeps every constant. This is schema compatibility, not Java inheritance.
- **Comparison needs `vertique-workflow-schema`.** Without it, differing state classes of one id and contract across scopes are refused. A migration between differing state classes applies the same comparison.
- **Callbacks.** A document definition can use only named callbacks registered for exactly its own state class.
- **Not compared.** The cross-scope state comparison does not compare `required` or `additionalProperties`, so a non-default-scope state class can loosen them and still be accepted (see the schema module reference).

## Shared Business And Idempotency Keys

Business keys and start idempotency keys are unique per `(start scope, definition id)`, whatever the contract of the definition. Clients of every contract of one `(scope, id)` share them: a repeated key through a client of another contract fails with an idempotency conflict, and a business key held by one instance blocks a second start with the same key. Different scopes do not share keys. Signal dedup keys are scoped to the workflow instance.

---

## Recorded Execution

Workflow operations record work and return. The executor runs it afterwards, in a transaction of its own. Every
instance has a queue of commands, and the executor runs the commands of one workflow instance one at a time, in the order they were recorded.

| Operation | What the call does in the caller's transaction | What runs later |
|---|---|---|
| `start` | Inserts the instance as `PENDING` and a `START` command, then returns the instance id | The first segment: initial state, subject and steps up to the first wait |
| `signal` | Checks the signal, takes a slot of the instance's open-signal count and inserts a `SIGNAL` command | The signal is applied when the instance waits for it |
| Task completion | Inserts a `COMPLETION` command and moves the task from `OPEN` to `COMPLETING` | The decision is applied |
| `retry`, `migrate` | Inserts a `RETRY` or `MIGRATION` command | The recovery or the migration |
| Timers | A segment that enters a timed wait records the timer and its `TIMER` command | The timer fires when it comes due |
| `cancel`, task reassignment | Run in the caller's transaction and record no command | Nothing |

An operation that returns has recorded its command; it has not run it. A failure, a rejection or a stale outcome therefore
appears in the instance's log (see [Command outcomes](#command-outcomes)) and not as an exception of the call that recorded the
command. A caller that needs the effect awaits the execution probe (`executionProbe().awaitIdle`), which is meant for tests and
operator tooling. A rolled-back caller leaves nothing behind. All recording calls wait at most `workflow.recording.lockTimeoutMs`
(default 5000) for the instance's execution lock and then fail with a retryable `WorkflowPersistenceException`; retry the whole transaction.

### Node roles and the executor

A node is an executor node by default. It runs the `workflow-executor` verticle, which claims commands with a lease, runs each
as a segment in one transaction together with the segment's intents and history, and completes the command. A segment
that has not finished one margin before its lease ends is rolled back, and another executor takes the command after the lease.
A command that fails transiently is retried with a capped backoff, one that exhausts `maxAttempts` claims is dead-lettered and
reported as stuck, and `retry` offers dead-lettered commands again.

- **Executor nodes.** The node needs `WorkflowSchemaModule` and `JsonRuntimeModule` (see [Common App Wiring](#common-app-wiring)); it fails startup without the schema producer.
- **Recording-only nodes.** `workflow.executor.enabled=false` deploys no executor. The node claims and runs nothing, needs no workflow class and no `WorkflowSchemaModule`, and still records starts, signals, task completions, retries, migrations, cancellations and reassignments, answers queries, and activates, deprecates and retires versions. It still runs the intake sampler and refuses new starts and signals under pressure like an executor node (see [Intake limit](#intake-limit)). It refuses `publish`, `reinstate`, document load, `WorkflowRegistry.load` and `WorkflowPayloadSchemas` with `WorkflowNodeRoleException`. The lifecycle operations stay open on such a node and the framework authorizes none of them, so put an authorization layer in front of them.
- **Signal sources on a recording-only node.** A [signal source](#signal-sources) only records signals, and recording needs no plan and no workflow class, so a source may run on a recording-only node while the executors run elsewhere. The source records the signal in a transaction of its own; an executor node applies it afterwards.
- **What that node installs.** The workflow database and recording path in its JVM (`WorkflowPostgresqlModule` and the database modules); the contract and payload classes that the source's signals name; `JsonRuntimeModule`, with the same `json.systemProfile` as the executors, because the profile is a wire-format decision that reaches workflow state and payloads (`KafkaJsonModule` installs `JsonRuntimeModule`); and `WorkflowSourceModule`, which supplies the delivery bean `WorkflowSourceDelivery`. `WorkflowServicesModule` includes `WorkflowSourceModule`, so a node that also dispatches service calls installs the services module; a node that only receives signals installs `WorkflowSourceModule` alone. A source needs no outbox module, but it needs the inbox module (`vertique-inbox-outbox-postgresql`) with its migration and its cleanup job, on a recording-only node as on any other: the delivery bean writes an inbox row per signal, and a graph without an `InboxService` does not compile. The node still needs no `WorkflowSchemaModule`.
- **Rollout of correlated signals.** Apply the workflow schema migrations first (`V004__signal_correlation.sql` and `V005__business_key_lookup_index.sql` of `vertique-workflow-postgresql`; they are additive and need no stop of older nodes, but `V005` blocks writers of `workflow_instances` while its index builds, so a large installation builds that index ahead of the upgrade as the module reference shows), then upgrade the nodes in any order. Nodes that still carry the earlier migrations keep running against the upgraded database. A business-key command applies on an older executor, because its provenance is stored in its own column and its target keeps its earlier shape. A wait's-key command belongs to an instance pinned to a version that declares the key, which only an upgraded node has the code for; an older executor that is still running defers the command as not runnable, spending no attempt, until an upgraded executor runs it. Deployments stay roll-forward only (see [Roll-forward only](#roll-forward-only)).
- **Deployment.** The executor is deployed in the `SERVICES` phase with the application's other verticles. An application that is wired by hand and does not use a starter must include the deployer of `vertique-deploy` (`DeployerModule`) and deploy the phase through it. Without it no command executes and every start stays `PENDING`.

| Key | Default | Meaning |
|---|---|---|
| `workflow.executor.enabled` | `true` | `false` makes the node recording-only |
| `workflow.executor.instances` | `1` | Executor verticle instances per node, 1 to 64 |
| `workflow.executor.concurrency` | `8` | Segments one instance keeps in flight, 1 to 256 |
| `workflow.executor.pollIntervalMs` | `5000` | Pause between two looks for runnable commands; at least 100. A command that becomes pending wakes the executor at commit |
| `workflow.executor.leaseSeconds` | `60` | How long a claim holds a command; at least 5. Size it above the longest healthy segment |
| `workflow.executor.maxAttempts` | `5` | Claims that may end by a crash or lease overrun before a command is dead-lettered |
| `workflow.executor.transientBackoffBaseMs`, `transientBackoffMaxMs` | `200`, `30000` | Backoff of a command after a transient failure |
| `workflow.executor.maxStepsPerSegment` | `1000` | Nodes one segment may run without reaching a wait |
| `workflow.executor.stuckAfterSeconds` | `300` | Age after which an unsettled command is reported stuck |

Read the keys by installing `WorkflowExecutorConfigModule` and `WorkflowRecordingConfigModule` together with `ConfigParsingModule`;
otherwise the defaults apply. The recording keys are `workflow.recording.lockTimeoutMs` (5000), `maxPayloadBytes` (262144, at
least 1024), `maxOpenSignalsPerInstance` (100, 1 to 10000), `schemaCacheMaxEntries` (256) and the `intake` block (see [Intake limit](#intake-limit)). See the
[workflow core reference](../vertique-workflow/vertique-workflow-core/src/main/resources/META-INF/vertique/module.md) for the full table.

### Signals

A signal is recorded when `signal` returns and applied by a segment afterwards, so it can be sent right after the start.

- **Parked signals.** A signal the instance does not await yet is parked: it keeps its place, blocks nothing and spends no attempt, and it is applied when the instance reaches a wait that accepts it. A signal the plan never waits for, or that the contract does not declare, is refused or rejected.
- **Open-signal cap.** An instance holds at most `workflow.recording.maxOpenSignalsPerInstance` signals that are pending, deferred, claimed or parked. A signal recorded beyond it fails with `WorkflowSignalLimitException` (a 400) and writes nothing, so the dedup key stays free. The cap holds under every isolation level.
- **Re-queue beyond the segment budget.** After a segment reaches a wait, it applies the parked signals that wait accepts, in order, while it has run at most half of `maxStepsPerSegment` nodes. When the next one would start beyond that, or its application overruns the cap, every signal the wait accepts is set back to `PENDING` at its original place and the segment commits at the wait. Each of them then runs as the command of its own segment.

#### Signals from events with a reference

An event usually names something the business knows, such as an order id, and not the id of a workflow instance. A correlated signal finds the waiting instance from that reference. The delivery has two forms, which differ in what the reference identifies:

| Delivery | The reference is | It finds | What you declare |
|---|---|---|---|
| `signalByKey` | A key the wait computes from the instance's state | The one instance that waits at a wait of that contract and signal name with that key | A key on the wait |
| `signalByBusinessKey` | The business key the instance was started with | The one live instance that carries that business key and whose pinned version declares the signal | Nothing; the instance has the key from its start |

- **One signal resumes exactly one instance.** A delivery that finds no instance is a no-match, and one that finds more than one is ambiguous. Neither writes anything.
- **Early events.** A wait's key has no record before the instance reaches the wait: the record exists exactly while the wait is open. An event that arrives first is therefore a no-match, which a source reports as `NO_MATCH`. By default the source drops it; choose `RETRY` to have it redelivered until the wait opens. A business key exists from the start, so an event for a live instance that has not reached its wait yet is recorded and parked, and applies when the instance reaches a wait that accepts it. An event for a business key that no live instance carries is a no-match.
- **Scope.** A delivery matches instances of every scope and definition unless it is narrowed. A source narrows with `scope`; a direct delivery narrows with `withScope` and `withDefinition`. Scope is a filter, not an isolation boundary.
- **Keys are sensitive.** A key never appears in a log line, an error message, a history entry or an outcome report; a refusal names the contract and the signal and never repeats the value, so a metric built from the outcomes carries none either. A dedup key is stored only as a hash. The wait record itself holds its key in plain text, so restrict access to the workflow tables accordingly.

##### Declaring a key

A key identifies a wait for the lifetime of that wait. Declare it in code or in a document; both produce the same plan node.

- **In code.** `correlateBy(Function<S, String>)` on the `waitForSignal` chain. The engine computes the key from the instance's state when the instance enters the wait and stores it with the wait.
- **In a document.** `correlationKey` on a `wait-signal` step names the id of a registered correlation-key resolver, for example `correlationKey: order.paymentReference`. An unregistered id fails the load with `CORRELATION_KEY_RESOLVER_UNKNOWN`, naming the step and the id.
- **A valid key** is a non-blank string of at most 255 characters with no NUL character. A key that is not, or a resolver that throws, fails the wait's segment with `WorkflowCorrelationKeyException`, which names the definition, the step and the reason and never the key. When a signal's application reached the wait, that signal is rejected and the instance stays where it was.
- **Declare it in a new version.** A declared key changes the plan hash of the version that declares it; a wait without a key hashes as before.
- **Branches.** A keyed wait inside a fork branch records its own key, computed from the parent instance's state as the branch enters the wait.

##### Delivery and outcomes

Application code rarely calls the delivery methods: a source does. `TransactionalWorkflowOperations` declares them, and `vertique-workflow-engine` implements them.

```java
Future<CorrelatedSignalReceipt> signalByKey(CorrelatedSignal signal, TX tx);
Future<CorrelatedSignalReceipt> signalByBusinessKey(CorrelatedSignal signal, TX tx);
```

| Outcome | How it is reported | What it writes |
|---|---|---|
| Recorded | `CorrelatedSignalReceipt` with outcome `RECORDED` and the id of the matched instance's definition | The `SIGNAL` command |
| Duplicate | `CorrelatedSignalReceipt` with outcome `DUPLICATE`; this is a success | Nothing |
| No match | `WorkflowSignalNoMatchException`; a wait may open later, so the caller may deliver again | Nothing |
| Ambiguous | `WorkflowSignalAmbiguousException` (409); narrow the delivery | Nothing |

- **Recorded is not applied.** Like every signal, a correlated one is applied by the executor afterwards, in queue order. The open-signal cap and the payload bounds apply as for any signal.
- **It applies to the wait that matched.** The recorded command carries the identity of the wait entry it matched. If that wait is left before the command runs, the command settles as `STALE`, is logged and changes nothing. A wait entered again is a new entry, even with an equal key.
- **Deduplication is per event.** The dedup key names the event. A delivery by key is deduplicated by contract, signal name, narrowing scope and dedup key across instances, not within one instance as an instance-addressed signal's key is. A redelivery after the first one resumed the instance answers `DUPLICATE` and writes nothing, even when another instance now waits on the same key.
- **Identity.** A delivery that carries provenance records under the identity bound on the call. When there is none, or it is anonymous, or the actor mapper refuses it, the delivery fails with `WorkflowSignalActorException` and writes nothing, so no recorded command lacks an actor.

##### Signal sources

A signal source turns the messages of a transport into correlated deliveries. `vertique-workflow-kafka` provides sources for Kafka topics. Sibling framework modules offer the same stages for other transports, so a source reads the same on each. A source needs `vertique-workflow-services`, which supplies the delivery bean through `WorkflowSourceModule`, and the inbox module, which makes the recording of each signal idempotent. It needs no outbox module.

There are three forms, and all three build the same source:

| Form | You write | Registered by |
|---|---|---|
| Builder | A call to `WorkflowKafkaSignals.on(topic, type)` that ends in `build()` | You add the built `KafkaConsumerBinding` to the `Set<KafkaConsumerBinding<?>>` that the Kafka consumer registry reads |
| Source class | A class annotated `@WorkflowSignalSource` that implements `KafkaSignalSource<T>`, has an `@Inject` constructor and states the source in `define` | Generated wiring |
| Annotated listener | A `@KafkaListener` interface whose methods carry `@WorkflowSignalTarget`; it needs no implementation | The code generator writes the source and the wiring |

```java
KafkaSignals<ShipmentDelivered> source = signals
    .on("carrier.events", ShipmentDelivered.class)
    .groupId("logistics")
    .filter(KafkaRecordFilter.headerEquals("type", "delivered"));

KafkaConsumerBinding<ShipmentDelivered> binding = source
    .signal(OrdersContract.class, "shipment-delivered")
    .key(ShipmentDelivered::carrierRef)
    .build();
```

```java
@WorkflowSignalSource
final class ShipmentSignals implements KafkaSignalSource<ShipmentDelivered> {
    private final ShipmentLookup lookup;

    @Inject
    ShipmentSignals(ShipmentLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public void define(KafkaSignals<ShipmentDelivered> source) {
        source.topic("shipment.events")
              .groupId("logistics")
              .dedupBy(ShipmentDelivered::eventId)
              .signal(OrdersContract.class, "shipment-delivered")
              .businessKeyFrom(message -> lookup.orderNumberFor(message.body().carrierRef()));
    }
}
```

- **Stages.** Each signal names its contract and signal name with `signal(contract, name)` and takes exactly one key source: `key` or `businessKey` computes it from the message, and `keyFrom` or `businessKeyFrom` looks it up asynchronously, bounded by `lookupTimeoutMs` (default 5000). An optional `payload` maps the message to the signal's payload; without it the message type must be the signal's payload type. `when` excludes messages, `scope` takes a function from the message to the scope to match (an empty answer ends the message as `SCOPE_EMPTY`), `anyScope` states the default outright, which is to match every scope, `dedupBy` names the dedup key, `onNoMatch` sets the default no-match policy and a signal may set its own, `dispatchAs` sets the identity, and `restoreCorrelationFromHeaders` restores the correlation id. Kafka adds `topic`, `groupId`, `name`, `filter` and `dlqReplay`. A source may declare several signals; the signals of one message run one after the other.
- **Dedup key.** A business-key signal must name `dedupBy`. Otherwise the dedup key is `topic:partition:offset`, so a redelivered record is recorded once. A dead-lettered record carries the position it started at in its `x-dlq-source-*` headers and keeps it when it is dead-lettered again, so a `dlqReplay` source computes the same key and the same inbox identities for any number of replays: a signal that finished on one replay is deduplicated on the next, and only a failed one runs again.
- **Wiring.** Name the generated registrations module in the component; it includes the Kafka source module, so you do not list that. A component that leaves it out builds and consumes no source. An annotated listener also needs `vertique-workflow-kafka` on the compile classpath and `vertique-codegen-kafka` on the processor path.
- **Registration fails the build.** A source class with no `@Inject` constructor, one that is not concrete, one that implements no source interface, one the generated module cannot reference, and a listener target on a type that is no source each fail compilation and name the class. A registration is never skipped with a warning. `@NoAutoWire` turns the registration off.
- **Checks at build.** `build()` fails with an `IllegalStateException` that names the source, never a key, for a signal the contract does not declare, a signal with no key source, a business-key signal with no `dedupBy`, and `scope` beside `anyScope`.

##### A worked example

The order-fulfillment example saga waits at `wait-payment` for a `payment.captured` signal. The payment event carries the order id and no instance id, so the wait keys on the order id:

```java
.waitForSignal("wait-payment", "payment.captured", PaymentCaptured.class)
.correlateBy(OrderState::orderId)
.onSignal((s, sig) -> s.withPayment(sig.chargeId()))
.toStepOnSignal("create-shipment")
.build()
```

An annotated listener turns the `payments.captured` topic into deliveries to that wait. The interface has no implementation:

```java
@KafkaListener(name = "payment-events", topic = "payments.captured", groupId = "order-fulfillment")
public interface PaymentEvents {
    @WorkflowSignalTarget(contract = OrderFulfillmentWorkflow.class, signal = "payment.captured", key = "orderId")
    void captured(PaymentCaptured event);
}
```

- **A matching event.** The event's `orderId` finds the one order that waits at `wait-payment` with that key. The source records the signal, the commit follows, and an executor resumes the order at `create-shipment`.
- **An early event.** An event for an order that has not reached `wait-payment` finds no wait. The target sets no policy, so the default applies: the event is reported as `NO_MATCH` and dropped. Add `onNoMatch = SignalNoMatch.RETRY` to the target to have it redelivered with the consumer's retry backoff and then dead-lettered.
- **A redelivered event.** The target names no `dedupBy`, so the dedup key is `topic:partition:offset` and a redelivered record answers `DUPLICATE`.
- **Identity.** The command is recorded under the system identity `kafka source: payment-events` with the `kafka` origin. Add `dispatchAs` in the builder or source-class form to record under another identity.

A listener that targets a business key states the accessor of the dedup key, for example `@WorkflowSignalTarget(..., businessKey = "orderNumber", dedupBy = "eventId")`.

##### What a source does with a message

- **Concurrency, and no order.** A Kafka source runs records concurrently, up to `kafka.consumers.{name}.maxInFlight` at once (default 256) across the partitions assigned to it. There is no ordering rule, not by partition and not by key: a later record, also of the same key, can be recorded before an earlier one, and the consumer commits only up to the lowest record that has not finished. The ceiling is the database: each record costs about a dozen statements in one transaction, so `maxInFlight` records hold that many connections of the pool, and the pool or `NOTIFY` is the next limit. Raise `kafka.consumers.{name}.instances` to add consumers, up to the number of partitions. A retry holds the commit of its partition, not the other records.
- **An early signal.** A signal that arrives before the thing it resumes parks when it addresses a live instance by business key. When it addresses a wait by the wait's key it finds no wait and is a no-match: `onNoMatch(RETRY)` retries it with the consumer's backoff until the wait opens or it is dead-lettered, and a source that ignores no-match drops it.
- **Stages have no side effects.** `when`, `dedupBy`, `dispatchAs`, the key functions, the lookups and the payload mapping run again on every attempt and on every replay of a record after a restart or a rebalance, so they must read and compute only.
- **At-least-once from the start of the topic.** A source commits manually and retries, and the configuration cannot restore `AUTO` commit or the `SKIP` error strategies; registration fails naming the consumer and the option. A new group starts at the beginning of the topic, because `auto.offset.reset` defaults to `earliest`. The first deploy of a source therefore reads all retained history, and `dedupBy` does not catch those old events, so an old event can match a current wait or park on a live business key. Set `kafka.consumers.{name}.properties` `auto.offset.reset=latest` to start at the end of the topic instead; a first-ever record that fails before the first commit can then be lost when the consumer restarts or the partition moves.
- **Idempotent through the inbox.** Each signal of a message commits one inbox row with the command that records it, so a record that runs again after a restart, a rebalance or a dead-letter replay ends as it did the first time. A recorded signal is deduplicated; a signal that found no wait and is ignored, or whose key or lookup answered empty, commits its row in a transaction of its own, so a replay stays ignored even if a wait has opened by then; a signal that failed, or whose no-match is retried, leaves no row and runs normally on its next attempt. The identity is a digest of the contract, the signal, the narrowing scope and the message's dedup key, so a replay meets it whatever instance or key it resolves to. The inbox retention (30 days by default) bounds this: an ignored record replayed later is treated as new. A node that runs a source installs the inbox module with its migration and cleanup job, a recording-only node included.
- **Finished, retried or terminal.** A message that is finished completes the record. One that may succeed later fails the record with an ordinary exception, so the consumer retries it with the backoff of `kafka.consumers.{name}.retry` and then dead-letters it. One that is fine while the node is busy waits and is delivered again without spending a retry (see Intake pressure below). One that never will succeed is terminal: it is dead-lettered at once, never retried, and does not pause the consumer. When a message has several signals, the most severe fate wins: a signal that waits for intake outranks every failure, so the message is delivered again whole, spends no retry and is not dead-lettered, and its failed signals run again and decide once none waits.

| What happened | Outcome reported | Fate |
|---|---|---|
| The signal was recorded | `APPLIED` | Finished |
| A duplicate delivery | `DEDUPLICATED` | Finished |
| No match | `NO_MATCH` | Finished; retried under `RETRY` |
| More than one match | `AMBIGUOUS` | Terminal |
| `when` rejected the message, a key was null or empty, or a lookup answered empty | `IGNORED` | Finished |
| `scope` answered empty | `SCOPE_EMPTY` | Finished |
| Anonymous identity, an actor the mapper refuses, or a `SignalSourceRefusal` from `dispatchAs` | `REFUSED_IDENTITY` | Terminal |
| Input the message type or the database refuses (a blank or over-long key, a NUL character, an invalid scope), a payload that cannot be encoded as JSON, a blank dedup key, or a `dlqReplay` record with malformed replay headers and no `dedupBy` | `INVALID_INPUT` | Terminal |
| The instance holds the open-signal cap | `LIMITED` | Retried |
| The node refuses intake | `LIMITED` | Waits |
| An oversized payload | `OVERSIZED` | Terminal |
| A stage threw | `STAGE_FAILED` | Terminal |
| A lookup or `dispatchAs` failed or timed out, or the database failed | `STAGE_FAILED` | Retried |
| The record could not be decoded, or has a null value | `UNDECODABLE` | Acknowledged |

- **Undecodable records.** A record that cannot be decoded is acknowledged and reported once. A transient decode failure, such as an unreachable schema registry, is retried and then dead-lettered instead.
- **Outcome reports.** Each signal reports one outcome to every `SignalSourceObserver` contributed to the `Set<SignalSourceObserver>`; a message that ends before a signal is chosen reports once with no contract or signal name. The report names the source, transport and outcome and never a key, header or payload. A rising `NO_MATCH` count shows events that arrive before their wait, or for a key nothing waits on.
- **Provenance.** Each recorded command stores the source name, topic, partition and offset of the record that caused it.
- **Intake pressure.** A Kafka source waits out a refusal under the [intake limit](#intake-limit): while the node refuses intake the consumer starts no record and fetches no partition, and keeps polling so it stays in its group; it asks again every quarter second and resumes when the node admits. A record the consumer held back, or that the node refused after it started, goes back to the queue without a retry and is never dead-lettered. There is no limit on the wait, so an intake refusal that never clears stalls the source and lag grows, and records are safe only while the topic retains them: alert on `LIMITED` and on consumer lag. A Camel source retries instead, under its route's policy.

### Intake limit

Recording a start or a signal is cheaper than running it, so intake can outrun the executors until the database is the bottleneck. A node therefore refuses a **new** start or signal with `WorkflowIntakeRefusedException`, a retryable 503 that writes nothing, while the cluster holds too many due commands. Callers see this as a new 503 from `start`, `signal`, `signalByKey` and `signalByBusinessKey` under load: wait and send the same request again, and a source of records stops dispatching and keeps them.

- **What is counted.** Commands that are `PENDING` or `DEFERRED` and available now, read once per node every `sampleIntervalMs` (four intervals while the queue is at or below the low mark) with a statement that stops at `highMark + 1`. Timers not yet due, parked signals and claimed commands are not counted, so an idle system with many timers is never refused. It is a proxy for unrun work and does not judge whether a command is at the head of its queue.
- **Each node decides alone**, including a [recording-only node](#node-roles-and-the-executor): it refuses from a reading at or above `highMark`, and admits again from a reading at or below `lowMark`. The check reads node-local state and adds no SQL.
- **What keeps its outcome.** The check is the last one before the execution lock, and a refused call never takes it, whether or not a history listener is bound, so a refusal under pressure does not wait for the lock. A start or signal that committed before the pressure rose returns its original outcome when retried, and a missing or ended instance, an undeclared name, an unavailable version and an invalid payload keep their own refusal.
- **What is never refused.** `cancel`, `retry`, `migrate`, task completions and reassignments, timers, everything a segment records, and a signal the outbox relay delivers to `workflow.signals.post`: it is already durable, and refusing it would only spend delivery attempts until it was dead-lettered. An application that signals through the outbox passes the limit.
- **It is approximate.** At worst the cluster records `5 x sampleIntervalMs x` its intake rate more commands than the last reading showed. At the defaults and 2,000 commands per second that is 10,000 commands past the last reading.
- **A failing reading never becomes an outage.** A node keeps its state through two failed or timed-out readings and admits on the third, with one warning for the outage.
- **It does not bound the outbox.** A command that runs can move its backlog into the outbox, and the table is shared, so its depth is not pressure. Watch the outbox claim report (`OutboxPublishObserver.onClaimCompleted`).
- **Commands no executor can claim stay counted**, so they can hold refusal on. When an `IntakePressureChanged` event reports a node refusing while the executors are idle, read the report of the execution probe and fix the cause (deploy the missing version, `retry` or `cancel` the instance). If that cannot be done at once, set `workflow.recording.intake.enabled` to `false` on the recording nodes and restart them. **Prerequisite:** this key is read only when the application installs `WorkflowRecordingConfigModule` together with `ConfigParsingModule` (see [Node roles and the executor](#node-roles-and-the-executor)); without the module every node runs the default limit and ignores the setting, so confirm the module is installed before you rely on it.

The keys below are read only when the application installs `WorkflowRecordingConfigModule` together with `ConfigParsingModule`; without it the defaults apply and a configured value, `enabled=false` included, has no effect.

| Key | Default | Meaning |
|---|---|---|
| `workflow.recording.intake.enabled` | `true` | `false` runs no sampler and admits every call |
| `workflow.recording.intake.highMark` | `10000` | Due commands at or above which the node refuses |
| `workflow.recording.intake.lowMark` | `5000` | Due commands at or below which it admits again; at least 1 and below `highMark` |
| `workflow.recording.intake.sampleIntervalMs` | `1000` | Time between readings, and the longest a reading may take; at least 100 |

Each change of a node between admitting and refusing emits one `WorkflowExecutorEvent.IntakePressureChanged` to the executor observers and one warning. The marks are depths, not capacities: tune them for a deployment whose drain rate is far from the default. See the [workflow core reference](../vertique-workflow/vertique-workflow-core/src/main/resources/META-INF/vertique/module.md).

### Timers

Timers are commands. A timer node, a signal timeout, a task due date and a task reminder are each a `workflow_timers` row linked to a `TIMER` command that comes due at its fire time, and the executor fires it. A segment that leaves the wait cancels the timer and its command. A timer whose wait has gone settles as stale and changes nothing else. A due date or reminder is held back while its task is `COMPLETING`, and a timeout is held back by an earlier signal or completion recorded for its wait. A signal recorded at or after a timeout's fire time is never applied at that wait while the timeout is outstanding. No timer is a scheduled job, and the artifact that ran them is removed in 0.4.0 (see [Removed in 0.4.0](#removed-in-040)).

### Task completions and reservations

A task completion records a `COMPLETION` command and reserves the task by moving it from `OPEN` to `COMPLETING`. The call
checks the earlier result of a duplicate, the payload bounds and the decision's stored schema before it takes the lock. A
`COMPLETING` task refuses a second completion and a reassignment with `WorkflowTaskNotWaitingException`. The executor applies the decision;
when the command is rejected (an invalid payload, a decision applicator that throws, a stale reviewed version, a step-limit overrun) the task is reopened and the instance is unchanged, and when the instance has ended the task is cancelled.

### Command outcomes

Every command ends in one state, and the log records why. The reason is a code with framework text; it never holds a payload value, exception text or a class name.

| State | Meaning |
|---|---|
| `COMPLETE` | The command ran |
| `REJECTED` | The command is invalid for the instance: a deterministic failure, an undeclared signal, an ended instance. The savepoint is rolled back and the instance is unchanged |
| `STALE` | The wait the command belonged to is gone: a timer whose wait ended, a branch that was superseded |
| `PARKED` | A signal the instance does not await yet |
| `DEFERRED` | This node cannot run the pinned plan; another node runs it, and no attempt is spent |
| `FAILED` | A start, retry or timer failed deterministically, or a segment overran the step cap; the instance becomes `FAILED` and `retry` can recover it |
| `DEAD` | The command spent `maxAttempts` claims; `retry` offers it again |
| `CANCELLED` | The instance was cancelled |
| `SUPERSEDED` | A dead-lettered start that a retry replaced |

What becomes of a command that meets an instance that has ended or failed:

| Command | `FAILED` | `COMPLETED`, `COMPENSATED`, `EXPIRED` | `CANCELLED` |
|---|---|---|---|
| signal (instance-addressed) | parked | rejected | cancelled |
| signal (branch-addressed) | stale if its branch ended, else parked | stale | cancelled |
| task completion | rejected, task reopened | rejected, task cancelled | cancelled, task cancelled |
| retry | executes | rejected | cancelled |
| migration | rejected | rejected | cancelled |
| timer | stale | stale | cancelled |

### Audit log and history API

The history of an instance is an append-only log. Every segment logs, under the instance's execution lock, a `DECISION` entry
for each step that changes the instance, with the step, the outcome, the definition scope, id and version, the plan hash, the
state version before and after, the executor and the actor, and an RFC 6902 patch of the state change. A full `STATE_BASELINE` entry follows the first
segment, every migration, the first claim of an instance that existed before 0.4.0 and any change too large to log as a patch (over 65,536 bytes).
Storage grows with the changed fields of each decision, so plan retention accordingly. The entries of an instance are removed with
the instance by retention, through the cascade from the instance row.

`WorkflowInstanceHistoryService` reads an instance's log and its open commands:

- `entries(id, afterSequence, limit)` pages the log in ascending sequence order; `limit` is 1 to 500.
- `openCommands(id)` lists the commands that are `PENDING`, `DEFERRED`, `CLAIMED`, `PARKED` or `DEAD`, with their stored failure reason.
- `WorkflowHistoryEntry` gains the actor, attribution status, executor, command, step, outcome, definition key, plan hash, state versions and patch. Its `entryVersion` is the stored format version of the entry: the `DECISION` and `STATE_BASELINE` entries are stored as version 2, and every other entry, such as the lifecycle entries, as version 1. `query()` keeps its narrow entries and returns only the five original components and the stored `entryVersion`.
- **Authorize reads.** Entries name actors and carry state patches, and open commands carry stored failure text. The framework authorizes none of these reads; the application must do it before it exposes them.

**Redaction.** The log stores business data. Bind a `WorkflowHistoryRedactor` to name, per definition version, the JSON Pointers whose values must never reach the log. Their values become `"[redacted]"` in the logged copy of command payloads, in the patches, in every full state and in the reasons written to history. The instance's own state, the command rows and the events keep their values, because the engine needs them to run. Redaction is not retroactive, and a redacted value cannot be recovered from the log. Without a redactor nothing is redacted.

**Tamper-evidence.** The workflow history table is an operational log that the database owner can change. Tamper-evident audit comes from the application's audit pipeline, not from the workflow history table.

### Who asked, and what the downstream runs as

Every recorded command carries who asked for it (`actor_kind`, `actor_id`, `actor_reason`, `on_behalf_of`, `attribution_status`), taken in the recording caller's transaction from the caller's `SecurityContext`, never from a payload. A caller with no security context, or an anonymous one, is logged as `NONE` with the status `ABSENT`; a timer is logged as `SYSTEM` with the reason `timer`. The executor copies the actor of the command it runs onto the history entries it writes.

- **Attribution headers.** A service dispatch that a segment writes carries `x-workflow-actor-kind`, `x-workflow-actor-id`, `x-workflow-actor-reason`, `x-workflow-on-behalf-of` and `x-workflow-attribution` on its outbox record. Any other intent, such as a workflow event, carries only `x-workflow-actor-kind` and `x-workflow-attribution`. The engine removes every application-set header that starts with `x-workflow-` before it adds them. These headers are attribution, not identity, and they are unauthenticated: no relay, handler or recording path binds an identity from them, and a downstream service must never authorize on them.
- **Downstream authority.** A segment binds no identity snapshot, so a service operation that a segment dispatches through the outbox runs as the relay's identity, `SYSTEM` with the relay's reason by default, and not as the user who recorded the command. Operators who relied on the user's identity downstream must bind a `ServiceIdentityResolver` that names a dedicated relay service and grant that service the permissions the downstream operation checks.
- **Startup warning.** When the executor is deployed it logs one warning for each of `identity.snapshot.carriageRequirements.outbox-relay` and `identity.snapshot.carriageRequirements.workflow` that is `REQUIRED`, because the intents of a segment carry no identity snapshot and executor drives bind none. A recording-only node warns about nothing.
- **Relayed signals.** A signal that the outbox relay delivers is recorded under the identity the relay rebuilt for the delivery.

### Plan cache

Any plan that the registry does not hold, which means every document version, is built by the engine's plan cache from its
stored row the first time a command needs it. The cache is bounded by `workflow.definitions.cache.maximumEntries` (default 500; see the table below): it
bounds the number of plans, not bytes, and a remembered failure counts as an entry. A resident plan is held by reference; the largest plan
of the order-fulfillment example (15 nodes) retains about 2.4 KB, so the default budgets about 1.2 MB for plans of that size. A plan grows with its node count and
with the callbacks and schemas it holds, so measure your largest document before you raise the limit. The cache builds one plan per key however many commands ask for it, and the executor holds the plans of a segment until the segment ends so that a plan cannot be evicted under it.
The cache is sized and expired by `workflow.definitions.cache`: `maximumEntries` (default 500, at least 1) bounds it, and a positive
`ttlSeconds` drops a plan that long after it was built, so that the next request builds it again (default `0`: no expiry). A key you
leave out keeps its default, so a block that sets only `ttlSeconds` still holds 500 plans. The cache module's `cache` keys do not
apply to it and `cache.enabled` does not disable it. The engine reads the block from the application configuration when one is bound:

```json
{
  "workflow": {
    "definitions": { "cache": { "maximumEntries": 2000, "ttlSeconds": 3600 } }
  }
}
```

Startup builds the code definitions and checks the packaged documents but keeps no document plan. Set
`workflow.definitions.buildDefaultScopeDocumentsAtStartup` to `true` to build every live default scope document into the cache at startup, so that one that does not build fails startup.

### Executor metrics

The engine itself publishes no meters. Add the opt-in `vertique-micrometer-workflow` bridge (`MicrometerWorkflowModule`) and bind a
`MeterRegistry` to get counters for settled, deferred, transiently retried and lease-expired commands and for recordings
with an absent actor, a timer for command duration, and gauges for the available, stuck, parked and dead commands and the
notification queue usage. Tags are the lower-cased command kind and outcome only. See the
[Micrometer workflow reference](../vertique-micrometer/vertique-micrometer-workflow/src/main/resources/META-INF/vertique/module.md).

### Removed in 0.4.0

The no-store mode, the definition poller, delayed-job timers and the `vertique-workflow-delayed` artifact are removed in 0.4.0.
Definitions always live in the database, a node reads the rows it needs and runs no poller, and timers are commands.

---

## Durable Definition Registry

The durable registry stores one row per workflow definition version in the `workflow_definitions` table, so
definitions, their lifecycle status and their document sources survive restarts and reach every node.

### When it is active

- **Store required.** The registry runs over a `WorkflowDefinitionRegistryStore`. `WorkflowPostgresqlModule` binds one
  unconditionally, so every PostgreSQL application has it. It needs PostgreSQL 15 or later. There is no mode without a
  store: a component that installs the definition service without a store and a lifecycle fails to build, and a factory-built
  engine takes the store as a part.
- **Identity.** A row is identified by `DefinitionKey(scopeKey, definitionId, definitionVersion)`. The scope key is
  required; `WorkflowScopes.DEFAULT` is the default scope, which code definitions always use. Any number of versions and scopes of one id are available at once.
- **Definition ids.** Ids are unprefixed: the document id is the definition id everywhere it is used
  (`StartCommand.definitionId`, pinned resolution, migration targets). A document may use the id of a code definition or of
  another scope's document, and each scope numbers its own versions. `load` and `publish` take any scope key, so restrict
  them to trusted callers and never forward their refusal messages to end users: a refusal can name other scopes and plan hashes. Definition ids
  may contain Unicode format and bidirectional characters that validation does not strip (scope keys reject them); do not render them unescaped in
  operator UIs or logs.

### Code and document definitions

| Kind | Where it comes from | Row |
|---|---|---|
| `CODE` | Definitions the application registers in Java, including contributors | Default scope, recorded at startup |
| `DOCUMENT` | Packaged YAML/JSON sources and documents loaded at runtime | Any scope, with source text, format and provenance |

Code registers itself from the deployed application, so `load` and `publish` apply to documents only.
`deprecate`, `retire` and `reinstate` apply to both kinds.

### Lifecycle

| Status | Meaning |
|---|---|
| `CANDIDATE` | Loaded and compiled, not published |
| `PUBLISHING` | A node holds a short-lived publication claim; a claim older than `workflow.definitions.publicationClaimTimeoutSeconds` counts as abandoned |
| `AVAILABLE` | Published: it can be started explicitly, pinned and resolved. Any number of versions and scopes of one id are available together; which version starts by default is selected by an activation record (see [Current version and activation](#current-version-and-activation)) |
| `DEPRECATED` | Live for pinned instances; refused for new starts and migration targets |
| `RETIRED` | Retained metadata only; no executable membership |

| Operation | Moves | Typical failure reasons |
|---|---|---|
| `publish` | `CANDIDATE` to `AVAILABLE` through `PUBLISHING` | `STALE_CLAIM`, `DRIFT`, `LOADER_MISSING`, `INVALID_TRANSITION` |
| `deprecate` | `AVAILABLE` to `DEPRECATED` | `INVALID_TRANSITION` (also for the default scope's current version) |
| `retire` | `CANDIDATE`, or a `DEPRECATED` row, to `RETIRED` | `PINNED_INSTANCES`, `INVALID_TRANSITION` (also for the default scope's current version) |
| `reinstate` | `DEPRECATED` or `RETIRED` to `AVAILABLE` | `CODE_UNAVAILABLE`, `DRIFT`, `LOADER_MISSING`, `INVALID_TRANSITION` |

`activate`, `deactivateDefault` and `follow` change the activation records, not the status of a definition row. `reinstate` returns a version to `AVAILABLE` and never makes it current.

Every other status pair is refused with `INVALID_TRANSITION`. Repeating a completed operation (publishing an available
version, deprecating a deprecated one, retiring a retired one) is accepted without a change.

Refusals are `WorkflowDefinitionLifecycleException` instances with a `reason()` and the `key()`; other failures, such as
no bound store, an invalid key or a lock timeout, use their own exception types. They arrive as failed `Future`s, never as
synchronous exceptions.

- **Deprecation.** An explicit-version start and a `migrate` whose target is deprecated fail with
  reason `DEPRECATED`. Signals, timers and resumes of instances already pinned to the version keep working.
  Deprecating a version leaves every other version available; the default scope's current version cannot be
  deprecated until another version is activated or `deactivateDefault` clears its record.
- **No waiting period.** A deprecated version retires as soon as no instance is pinned to it, however recently it was
  deprecated. Retirement is refused with `PINNED_INSTANCES` while any `PENDING`, `RUNNING`, `WAITING` or `COMPENSATING`
  instance, archived ones included, is pinned to the exact version.
- **Reinstatement.** `reinstate` of a retired version needs an executable source: the deployed code for a code version, the
  stored text for a document. A deprecated version is still registered, so its reinstatement only clears the deprecation
  mark. Either way the version becomes `AVAILABLE`.
- **No authorization.** `load`, `publish`, `deprecate`, `retire`, `reinstate`, `activate`, `deactivateDefault`,
  `follow` and `activations` perform no access check. Restrict them to authorized callers in your application.
- **Retire and scope records.** `retire` guards only the default scope's current version. A non-default scope's activation
  record can name a version that is then retired or deprecated; starts without a version in that scope fail until the record
  is moved (`activate`) or removed (`follow`). A start through a record that names a retired version fails with the
  not-runnable error, which is permanent until the record is moved (`activate` another version or `follow`).
- **Deactivation is not durable across a release.** A later release that deploys a higher version activates it on its
  first publication unless it opts out (see [Deploying new versions](#deploying-new-versions)).

### Current version and activation

The version that a start without a version runs is selected by activation records, not by the registry. A record names, for one scope and definition id, a version of that scope or of the default scope; the default scope's record names only the default scope.

- **Order.** A start consults the start scope's record and then the default scope's record. A scope with no record follows the default scope. Only the start scope and the default scope are consulted; a start never borrows a version of another scope, and nothing in the start path weights or splits traffic between versions.
- **No record, no start.** A start without a version fails with `WorkflowNoCurrentVersionException` when neither record exists, including after `deactivateDefault`.
- **Contract-aware.** A scope's own record applies only to clients of the contract that the scope's own versions implement. A client never starts a definition of another contract.
- **Operations.** `activate(scopeKey, definitionId, targetScopeKey, version)` replaces the scope's record. The target must be `AVAILABLE` in the target scope, otherwise the call fails with `INVALID_TRANSITION`; a target in the default scope of another contract than the scope's own versions fails with `WorkflowDefinitionContractMismatchException`. `deactivateDefault(definitionId)` clears the default scope's record; an absent record is accepted. `follow(scopeKey, definitionId)` removes a non-default scope's record so that the scope follows the default again, and fails with `IllegalArgumentException` for the default scope. `activations(scopeKey)` lists the records of a scope or, with `null`, of every scope.
- **Publishing never activates.** `publish` makes a version available and changes no record.
- **Visibility.** A node keeps no copy of the records. A first start reads the records of the id together with its rows on the caller's transaction, so freshness depends on the caller's isolation level. Under `READ COMMITTED` (the default) a start sees every activation, deprecation, retirement and publication committed before it, whichever node committed it. Under `REPEATABLE READ` or `SERIALIZABLE` a start sees the caller's snapshot, so an activation change made after the snapshot can be missed and the start can run the version the snapshot names. The engine neither checks nor changes the isolation level. Pin admission stays the fence for status, so a version deprecated or retired between the read and the insert is refused at every level.

### Deploying new versions

When a code version or a packaged document version is first published at deployment, it becomes the default scope's current version if it does not opt out and is higher than the version the default scope's record already names (or there is no record). The record and the new row commit in one transaction.

- **First publication only.** A restart is not a first publication and never activates anything, so an operator's `activate` and `deactivateDefault` survive restarts. Among versions of one id first published in the same startup the highest that does not opt out wins, and a lower version published later never replaces a higher record.
- **Opt out.** A code definition returns `false` from `activateOnDeploy()`; a packaged document sets the header `activateOnDeploy: false`. A version loaded through the definition service ignores the header and stays inactive until an operator activates it.
- **Try before you activate.** A version that is not current can still be started explicitly, or made current in one scope with `activate("qa", id, "default", 2)`.

#### Rolling deploys

Auto-activation runs on the first node that starts with the new version, and every node then reads the record. Nodes still running the previous code cannot run the new version, so starts on them fail with the not-runnable error until they are replaced. To roll out across a mixed fleet:

1. Deploy the new version with `activateOnDeploy` set to `false` on every node.
2. When every node runs the new code, call `activate` for the default scope.

The same procedure applies to a packaged document with `activateOnDeploy: false`.

#### Roll-forward only

Deployments are roll-forward only. A node whose code lacks a version that is not retired fails application startup with `CODE_UNAVAILABLE`: the node does not start, and none of its workflows run. Three cases are therefore unsupported: an old-code node that restarts or autoscales after a newer version was published, a rollback of the code past a version that has live instances, and rolling code back to 0.3.0. Old nodes that stay up keep serving, but starts that resolve to the newer version fail with the not-runnable error, which is retryable only against a node that has the code.

Activating the previous version again changes only which version new starts use; it does not let an old-code node restart, because the startup check looks at the published row, not at the activation. To undo a bad rollout, activate the previous version and then retire the newer one once no instance is pinned to it, or deploy a corrected version. To keep a rollback possible, deploy with `activateOnDeploy` set to `false`, activate once every node runs the new code, and retire the new version promptly if you abandon it.

#### Work a node cannot run

A node that is already running can still meet work for a version whose code it does not hold, for example while a rolling deploy is in progress. No node marks an instance failed because it cannot run its pin.

- **Recording.** A start, a signal, a task completion, a retry and a migration are recorded on any node, because recording needs no plan. A start
  selects its version from the database rows and records a `PENDING` instance and a `START` command.
- **Commands.** The executor of a node that cannot run the pinned plan defers the command instead of claiming it: the command
  gets a later availability (5 seconds, doubled for every earlier deferral, at most 5 minutes), spends no attempt and keeps
  blocking the later commands of its instance. A node that has the code claims it as soon as it is available. Timers are
  commands too, so a timer that no running node can run waits the same way and fires on a node that has the code. The first deferral of a command appends
  one history entry, and a command nobody can run is reported as stuck once it is older than `workflow.executor.stuckAfterSeconds`.
- **Queries.** A typed-client query (`query(id, contractName)`) on an instance pinned to such a version cannot vouch for the contract, so it fails with `WorkflowVersionNotRunnableException`. It writes nothing. The failure
  belongs to the node, not to the instance, so a retry on a node that has the code succeeds. It is retryable only while some node holds the code: after the version is retired and its class removed, no node will.

### Decommissioning a definition

To remove a definition id or a version, in order:

1. Make sure no activation record names the version. For the default scope's current version, call `deactivateDefault(id)` (or activate another version); for a non-default scope's record, `activate` another version or `follow`. After `deactivateDefault`, starts without a version fail with `WorkflowNoCurrentVersionException` in every scope that follows the default.
2. `deprecate` each version. New explicit starts and migrations are refused; pinned instances keep running.
3. `retire` each version once no instance is pinned to it. `retire` is refused with `PINNED_INSTANCES` until then, so treat it as retryable.
4. Remove the definition class in a later deployment.

`query(id)` reads the instance and its history from the database and does not need the pinned plan, so a finished instance of a retired version stays readable after its class is removed. A typed client's `query(id, contractName)` compares the client's contract with the pinned plan's contract; a node without that plan cannot vouch for the contract and refuses with the not-runnable error.

Deprecating or retiring the default scope's current version is refused with `INVALID_TRANSITION` until step 1 is done.

### Failing scope rows

A stored document row of a scope other than `default` can conflict with code deployed later. Two conflicts mark the row failing at startup instead of failing it: the row's state class no longer extends the default scope's class for the same id and contract (`STATE_TYPE_NOT_EXTENDED`), and a type name in the row's recorded name binding no longer resolves (`NAME_BINDING_UNRESOLVED`).

- **What keeps working.** Instances pinned to a state-rule row keep resuming, receiving signals and completing tasks. For a binding-rule row nothing is compiled, so its instances are not resumed on that node until the class is back.
- **What is refused.** A new start that selects the row, by an activation record or an explicit version, fails with `WorkflowDefinitionFailingException` naming the scope, the definition key and the rule. A replay of an earlier start still returns its instance.
- **Visible in listings.** `list` shows the rule in `WorkflowDefinitionRecord.failure()`. Each node evaluates every live scope row against the code it ships and refuses starts by its own evaluation, so during a rolling deploy the listing shows the most recent node's result.
- **The default scope is unaffected.** A failing scope row never blocks registering a default scope version of the same id. A default scope row or a code row that conflicts still fails startup, and so does any other failure to restore a scope row (a different hash, a missing loader, an unreadable source).
- **Repair.** Restore the class or the compatible state shape and restart; the mark is cleared when no conflict remains.

### Async usage

```java
DefinitionKey v1 = new DefinitionKey(WorkflowScopes.DEFAULT, "order-fulfillment", 1);
SourceMetadata source = new SourceMetadata("upload", null, null, "ops", null, Instant.now());

// Load a candidate from bytes and publish it.
Future<Void> published = definitionService
        .load(WorkflowScopes.DEFAULT, yamlBytes, DocumentFormat.YAML, source)
        .compose(ref -> definitionService.publish(
                new DefinitionKey(WorkflowScopes.DEFAULT, ref.definitionId(), ref.definitionVersion())));

// The snippets below are separate steps: run each one when its turn comes, not all at once.

// Stop new starts of the old version; pinned instances keep running. The default scope's current
// version is refused until deactivateDefault("order-fulfillment") or another activation moves the record.
Future<Void> deprecated = definitionService.deprecate(v1);

// Later, once no instance is pinned. Until then this fails with reason
// PINNED_INSTANCES, so treat it as retryable.
Future<Void> retired = definitionService.retire(v1)
        .onFailure(failure -> {
            if (failure instanceof WorkflowDefinitionLifecycleException e) {
                log.info("retire of {} refused: {}", e.key(), e.reason());
            }
        });

// Bring a retired version back.
Future<Void> restored = definitionService.reinstate(v1);

// Make version 2 the one a start without a version runs in the qa scope, then let qa follow the default again.
Future<Void> activated = definitionService.activate("qa", "order-fulfillment", WorkflowScopes.DEFAULT, 2);
Future<Void> following = definitionService.follow("qa", "order-fulfillment");

Future<List<WorkflowDefinitionRecord>> live = definitionService.list(new DefinitionFilter(
        WorkflowScopes.DEFAULT, null, Set.of(DefinitionStatus.AVAILABLE)));
```

`list` returns immutable metadata rows without source text. `sourceMetadata(key)` returns the source attribution
without reading the source body.

### Startup reconciliation

Before any `BOOTSTRAP` verticle deploys, the engine records and restores the registry, in this order:

1. It records every code definition as a default-scope `CODE` row and every packaged document as a `DOCUMENT` row, both `AVAILABLE`, together with the name binding of each. A version inserted for the first time that does not opt out is also activated in the default scope, in the same transaction.
2. It reads the default scope's rows and requires every live `CODE` row to be provided by the deployed code. A packaged
   document is built once, with the stored text digest and plan hash checked, so one that does not build fails startup;
   the plan is not kept. No plan of a document is registered: the plan cache builds a document from its row the first time a
   command needs it, and rows of other scopes are not read at startup. A row of a non-default scope that newly deployed code
   conflicts with is marked failing when its plan is built (see [Failing scope rows](#failing-scope-rows)).
3. It resets abandoned publication claims and seals the registry. Nothing is restored into a copy of the table, because a
   node keeps none.

Startup fails, naming the definition, in these cases, among others (a loader or compile failure of a stored document, a failing
loader callback, a stored document over the envelope after lowering the limit, a non-UTF8 database, a second loader for one
format, an unattributed direct registration, and a lock timeout):

| Situation | Failure |
|---|---|
| Code compiles to a different plan hash while instances are pinned to that exact version | `DRIFT`, both hashes named |
| A stored row of the same id and version is of the other kind | `INVALID_TRANSITION` (kind conflict) |
| A live `CODE` row whose version the deployed code no longer provides | `CODE_UNAVAILABLE` |
| A non-retired version bound to a class by its binary name that no longer resolves (the class moved) | `WorkflowUnknownTypeNameException` naming the scope, the definition, the version and the name |
| A code definition whose id or version breaks the key rules | `WorkflowDefinitionException` naming the definition |
| A live document row with no loader, or whose compiled hash or text digest differs | `LOADER_MISSING` or `DRIFT` |
| A packaged document whose text differs from the stored text under an equal plan hash | `DRIFT` (digest) |

- **Code hash change without pins.** When no instance is pinned to that exact version, the stored hash is replaced and
  a WARN logs both hashes. Changing a deployed version's compiled shape is still a rolling-deploy hazard, see
  [Upgrading to 0.4.0](#upgrading-to-040); prefer a new version.
- **Digest.** Each `DOCUMENT` row stores the SHA-256 of its stored UTF-8 text. It detects accidental or concurrent change
  of the stored text; it does not authenticate the writer, so restrict write access to the table.
- **Compose validators** run in `VALIDATE` and do not re-run for documents the plan cache builds later or that are published at run time.

### Seeing a change on other nodes

A node keeps no copy of the definition table, so there is nothing to refresh.

- **Starts** read the rows and activation records of the id on the caller's transaction, so a publication, deprecation,
  retirement or activation committed on another node is seen by the next start at the isolation level of that transaction.
- **Commands** read their plan through the plan cache, which builds a document version from its row the first time the node
  runs it. A version published, deprecated or retired elsewhere needs no step on this node; a plan the cache holds is
  determined by the row's plan hash, which never changes for a version that has instances.
- **Failures.** A row that does not build is not runnable: its commands are deferred, and a warning names its key once per
  time the cache remembers the failure. The log never carries source text.
- **Do not rely on peers for safety.** Starts and migrations are protected by a database check at pin time (below), and a
  migration reads its target's status from the database, so a retired or deprecated target is refused whatever plan a node
  still holds.

### Database coordination

- **Pin admission.** Before a new instance pin is written, a migration re-pins an instance, or a finished instance is
  resurrected (failed retry), the repository takes a shared advisory lock on the definition id and reads the id's rows in
  the caller's transaction. A refused pin fails with `WorkflowDefinitionLifecycleException` and rolls the transaction
  back. Starts share the lock, so they do not serialize each other, but `retire` and startup writes wait for them.
- **Lock timeout.** Every admission, and every registry write, sets `SET LOCAL lock_timeout = '30000ms'`. It overrides a
  stricter value set on the role or session and bounds every later lock wait in that transaction until commit or rollback.
  A timeout rolls back the caller's transaction (retry the whole transaction); registry operations surface it as a
  retryable `WorkflowPersistenceException`.
- **Stalls.** Long caller transactions can delay lifecycle and startup writes, and PostgreSQL queues later admissions
  behind a waiting writer, including a restarting node's startup reconciliation. Do not await a lifecycle call for an
  id while your own transaction holds an admission for it.
- **Stronger isolation.** Under `REPEATABLE READ` or `SERIALIZABLE`, an id with no visible definition row is refused with
  `INVALID_TRANSITION`, because the snapshot cannot tell an unmanaged id from a newly managed one. This includes engines
  assembled by hand that skip the registry bootstrap. `READ COMMITTED` admits an id with no rows.
- **Brief refusals.** For a short window between local publication and the durable commit of a publication, new starts of
  that version are refused. After a same-version code rehash (below), nodes still running the old code refuse new pins.
- **Registry writes** always run at `READ COMMITTED`, whatever the server default.
- **Retirement cost.** `retire` counts the pinned instances while it holds the exclusive id lock, and no index covers that
  predicate, so on a very large instance table a retirement can delay new starts of that id.
- **Shared per-id serialization.** Load, publish, activate and the other registry writes of one definition id serialize
  on the same id lock across every scope, so one caller can delay the others. Rate-limit those calls in your
  application. A listing is filtered by the database and returns every matching row without pagination.

### Registry mutation outside the lifecycle

- **Direct registration.** The engine tracks which runtime it published for which key. A runtime registered directly
  through `WorkflowRegistry` is never adopted or removed by the lifecycle: `deprecate`, `retire` and `reinstate` of that
  version fail with `INVALID_TRANSITION`, and a startup step that needs the slot fails the same way and leaves the
  runtime in place. Do not register definitions directly at runtime and expect the lifecycle to manage them.
- **Concurrency boundary.** Managed operations serialize per definition id on each node. A direct `register` or `unregister`
  of the same id during a managed operation is outside that guarantee. Each individual registry operation stays thread safe.
- **Metadata reads.** With a store bound, `list` and `sourceMetadata` read the durable table. Metadata of definitions
  that application contributors or packaged sources register appears only after the managed `BOOTSTRAP` step has recorded
  it, and there is no in-memory fallback.

### Configuration

| Key | Default | Meaning |
|---|---|---|
| `workflow.definitions.publicationClaimTimeoutSeconds` | `60` | At least 1: the age in seconds at which a `PUBLISHING` claim is stale, so another `publish` takes it over and startup resets it. Replaces the former staleness-seconds key of the same group, which is no longer read |
| `workflow.definitions.maxDocumentSizeBytes` | `262144` | Largest runtime `load` input, in bytes (L); the stored form is capped at floor(3L/2) UTF-8 bytes and L code points |
| `workflow.definitions.buildDefaultScopeDocumentsAtStartup` | `false` | When `true`, startup builds every live default scope document into the plan cache, so one that does not build fails startup |
| `workflow.definitions.cache.maximumEntries` | `500` | At least 1: the most document plans the plan cache keeps resident; it bounds the plan count, not bytes. Read by the engine, not by `WorkflowDefinitionsConfig` |
| `workflow.definitions.cache.ttlSeconds` | `0` | How long a plan stays resident after it was built, in seconds; `0` for no expiry. Read by the engine |

All keys are optional. To read them from configuration, install `WorkflowDefinitionsConfigModule` together with
`ConfigParsingModule`; otherwise the defaults apply, or bind `WorkflowDefinitionsConfig` directly. Use the same values on
every node sharing the table. Raise L on every node before admitting larger documents. Retire documents that exceed the
new envelope before lowering L; a lower limit makes startup fail for a stored document over the new envelope, and a later
`publish` or `reinstate` of a retained row that exceeds the new envelope also fails.
Non-positive values are rejected at startup with a configuration error, as is an L whose stored envelope would overflow an
integer.
Raising L increases memory and parse cost; the parser's nesting and expression limits are independent. The
[workflow definition module reference](../vertique-workflow/vertique-workflow-definition/src/main/resources/META-INF/vertique/module.md)
holds the per-path size rules.

---

## Upgrading to 0.4.0

This section covers the durable definition registry, the contract, definition, scope and activation model, and recorded execution. Other changes in the release, such as the payload schema validation,
are described in their own sections. This release adds the durable definition registry, separates workflow contracts from
definitions, adds scopes and activation records, and adds logical type names. It contains breaking changes to
`WorkflowClientFactory`, `WorkflowDefinitionService`, `WorkflowRegistry`, `@WorkflowContract` and `StartCommand`, and
changes startup for every PostgreSQL application. Operations now record work that an executor runs later (see [Recorded Execution](#recorded-execution)). Do the following in order.

### 1. Migrate the schema

- Flyway applies two new migrations from `db/migration/workflow`: `V002__workflow_definitions_and_scopes.sql` and, after it,
  `V003__recorded_execution.sql` (see the last bullet of this step). `V1__` is unchanged. `V002__` creates the `workflow_definitions` table, keyed by `(scope_key, definition_id,
  definition_version)`, with its `document_digest`, `contract_name`, `state_type_name` and `name_binding` columns, and the
  `workflow_definition_activations` table, keyed by `(scope_key, definition_id)`. It also converts the
  released tables to scope keys:
  `workflow_instances` gains `scope_key` and `definition_scope_key` and `workflow_tasks` gains `scope_key`, each
  `VARCHAR(255) NOT NULL DEFAULT 'default'` (existing rows read `default`; no row is rewritten); the business-key index
  `idx_workflow_instances_business_key` becomes unique per `(scope_key, definition_id, business_key)`; the keyset indexes
  `idx_workflow_instances_scope_updated_at` and `idx_workflow_tasks_scope_updated_at` serve scope-filtered listings, each
  on `(scope_key, updated_at DESC, <id>)`; and `workflow_dedup.scope` is renamed `dedup_namespace`, gains `scope_key`, and
  its primary key becomes `(kind, scope_key, dedup_namespace, key)`. Stored fingerprints and namespaces are kept as they were.
- **Stop every 0.3.0 node first.** All nodes that run 0.3.0 must be stopped before the first 0.4.0 node starts and applies
  the migration. The 0.3.0 dedup statements use `ON CONFLICT (kind, scope, key)`; the renamed column and the new primary
  key leave that conflict target without a matching index, so a 0.3.0 node that is still running after the migration
  fails every start. Rolling deploys apply only from 0.4.0 on, and rolling code back to 0.3.0 is not supported.
- **Lock and duration.** Each `ALTER TABLE`, the business-key index rebuild and the primary-key rebuild on
  `workflow_dedup` hold an `ACCESS EXCLUSIVE` lock until the migration commits, and each index build is a sequential scan
  of its table under that lock, so the time the tables are blocked grows with their size. Run the migration when no long
  transaction is open on `workflow_tasks`, `workflow_instances` or `workflow_dedup`. It waits at most 30 seconds per
  statement for a lock (`SET LOCAL lock_timeout`), during which new statements on that table queue behind it, and then
  fails, so rerun it after the blocking transaction ends. A manual run must be one transaction
  (`psql --single-transaction -f V002__workflow_definitions_and_scopes.sql`), or the timeout has no effect.
- **Large installations.** The column and keyset-index statements are `IF NOT EXISTS`, so you can add the scope columns
  and build the two keyset indexes ahead of the upgrade and let the migration find them done, but only after the last
  0.3.0 node has stopped is it safe to change `workflow_dedup`, which always happens inside the migration. Run these exact
  statements outside a transaction (each on an autocommit connection), with the `ALTER` statements first. A statement that
  times out on its lock changes nothing and can be rerun:

  ```sql
  SET lock_timeout = '30s';
  ALTER TABLE workflow_tasks ADD COLUMN IF NOT EXISTS scope_key VARCHAR(255) NOT NULL DEFAULT 'default';
  ALTER TABLE workflow_instances ADD COLUMN IF NOT EXISTS scope_key VARCHAR(255) NOT NULL DEFAULT 'default';
  CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_workflow_tasks_scope_updated_at
      ON workflow_tasks (scope_key, updated_at DESC, task_id ASC);
  CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_workflow_instances_scope_updated_at
      ON workflow_instances (scope_key, updated_at DESC, id ASC);
  ```

  The migration then builds nothing for them. It always drops and rebuilds the business-key index, and it always renames
  the dedup column (`ALTER TABLE ... RENAME COLUMN` has no `IF EXISTS` form, so do not run it ahead of time). The
  `IF NOT EXISTS` forms match by name only, so a column or index of that name with a different definition is silently
  accepted: use the statements above unchanged. A failed concurrent build leaves an `INVALID` index that `IF NOT EXISTS`
  would skip: check `pg_index.indisvalid` for both index names and drop an invalid one before the migration runs.
- **Existing rows.** Instances, tasks and start deduplication rows of 0.3.0 read the default scope after the upgrade; the
  instances resume in it and complete their stored task decisions. A start that names no start scope runs in the default
  scope as well.
- **Development databases.** A database that applied an earlier 0.4.0 snapshot must be recreated: version 2 changed its
  checksum and description, so Flyway validation fails.
- Anything else that writes `DOCUMENT` rows, such as operator scripts or other modules, must supply `document_digest`:
  the lowercase hex SHA-256 of the stored UTF-8 text, present on `DOCUMENT` rows and absent on `CODE` rows. Where Flyway
  is disabled, run the migration script yourself, in one transaction, before the first 0.4.0 node starts.
- Durable `DOCUMENT` bodies need `server_encoding = UTF8`. The check runs before the first document read or write. `CODE`
  rows and applications without documents work on any encoding.
- **`V003__recorded_execution.sql` converts the running work of 0.3.0 in the same stop-the-fleet step.** It adds the command
  log and the history columns, and it turns each scheduled timer into a `TIMER` command and each retry-scheduled branch into a
  `RETRY` command. It marks every existing instance so that the first segment that claims it logs a full-state baseline; that mark is one unfiltered
  update of `workflow_instances`, which rewrites every row once inside the migration, so plan the window and a vacuum afterwards on a large table. It also deletes the live
  jobs that fired 0.3.0 timers (`vertique.workflow.timer.fire`) from `job_executions` when that table resolves in the schema the migration runs in under its default
  name. A deployment whose job tables are in another schema, or a job table shared by several deployments, runs a manual statement before the first
  0.4.0 node starts; it is in the [workflow-postgresql reference](../vertique-workflow/vertique-workflow-postgresql/src/main/resources/META-INF/vertique/module.md#recorded-execution-schema).

### 2. Port `WorkflowDefinitionService` callers (breaking)

Four operations are replaced in place, on the interface and on `DefaultWorkflowDefinitionService`, with no deprecation
cycle. Eight old declarations are removed; constructors and all other members stay.

| Before | After |
|---|---|
| `CompiledDefinitionRef load(byte[], DocumentFormat, SourceMetadata)` | `Future<CompiledDefinitionRef> load(String scopeKey, byte[], DocumentFormat, SourceMetadata)` |
| `void activate(String id, long version)` | `Future<Void> publish(DefinitionKey key)` |
| `Collection<CompiledDefinitionRef> list()` | `Future<List<WorkflowDefinitionRecord>> list(DefinitionFilter filter)` |
| `Optional<SourceMetadata> sourceMetadata(String id, long version)` | `Future<Optional<SourceMetadata>> sourceMetadata(DefinitionKey key)` |

- **Added.** `deprecate(DefinitionKey)`, `retire(DefinitionKey)` and `reinstate(DefinitionKey)`, all returning `Future<Void>`, and the activation operations
  `activate(String scopeKey, String definitionId, String targetScopeKey, long version)` (the same name as the removed method, with a different meaning:
  it selects the current version and does not publish), `deactivateDefault(String)`, `follow(String, String)` and `activations(String)`.
  With no store, the four replaced operations keep their outcomes behind the new signatures, `deprecate`, `retire` and `reinstate` fail, and the activation operations act on the node's in-memory view.
- **Custom implementations.** A class that implements `WorkflowDefinitionService` must now implement all eleven operations.
  Classes that implement the new `WorkflowDefinitionRegistryStore`, `WorkflowDefinitionLifecycle` or
  `PersistedDefinitionLoader` types implement every method they declare.
- **`ActivationStatus`** is `CANDIDATE`, `PUBLISHING`, `AVAILABLE`, `DEPRECATED` and `RETIRED`: `ACTIVATING`, `ACTIVE` and `SUPERSEDED` are removed and `DEPRECATED` and `RETIRED` are new. A `switch` over it needs the new cases.
- **`WorkflowDefinitionStore` is removed.** The node no longer mirrors the definition table in memory, so the class that
  held that copy and its members (`list`, `get`, `currentStatus`, `markActive`, `storeCandidate`, `sourceMetadata` and the
  claim and reset helpers) are gone. Read definition metadata through `WorkflowDefinitionService.list` and
  `sourceMetadata`, and a plan through `WorkflowRegistry.load`.

### 3. Port contracts, clients and definitions (breaking)

Contracts no longer name a definition, and clients are created from the factory.

- **`@WorkflowContract`.** It has the single attribute `value()`. A contract that sets `definitionId` or `definitionVersion` no longer compiles: move the id and the version into the definition (`definitionId()` and `definitionVersion()`), and use `value()` only for a stable logical contract name.
- **Clients.** `WorkflowClientFactory.create(Contract.class)` and the generated Dagger providers of typed clients (`GeneratedWorkflowClientsModule`) are gone. Remove that module from your components and create clients with `factory.create(DefinitionClass.class)` or `factory.create(Contract.class, definitionId)`. A client proxy generated by an older annotation processor fails with `WorkflowClientProxyLinkageException` until the module is rebuilt with this release's processor.
- **Type catalog module.** Add `GeneratedWorkflowTypeCatalogModule` of every module that declares `@WorkflowContract` or `@WorkflowType` classes to each component (see [Type catalog module](#type-catalog-module)).
- **Contract declarations.** A definition that waits for a signal must declare it on its contract, so add the missing `@WorkflowSignal` methods before registering. Any number of definitions and versions may now name one contract; one scope and id must use one contract.
- **Registry and start API.** `WorkflowRegistry` takes `DefinitionKey` (scope, id, version): `resolve(key)`, `find(key)`, `keysFor(id)`, `register(scopeKey, definition)`; `resolveCurrent` and the id-and-version forms are removed, as is any lookup from a contract class to a definition. A custom `WorkflowRegistry` must implement the new members, including `definitionClass`. `StartCommand` has nine components: `contractName` is required, and `startScope` and `requestedTargetScope` are optional.
- **Documents.** Documents name the state, contract and payload types by logical name; a document loaded at runtime can name only classes in a type catalog or used by a registered definition. A document names no scope, and `load` takes the scope as its first argument.
- **Events and outbox.** Workflow event envelopes gain `definitionScopeKey` and `scopeKey` without a schema version change, and service-dispatch outbox headers gain `x-workflow-definition-scope` and `x-workflow-scope`. Code that constructs `WorkflowSideEffectIntent.Correlation` or `WorkflowEventIntent` by component list must pass both scopes.
- **Migration handlers.** A `WorkflowMigrationHandler` is keyed by source and target scope as well as by definition id and versions; both scopes default to the default scope, so existing handlers keep working.

### 4. Decide activation, scopes and type names

- **First 0.4.0 startup activates your current versions.** Each code version and packaged document the first 0.4.0 node publishes is activated in the default scope when it does not opt out, and the highest wins. From then on, new versions follow [Deploying new versions](#deploying-new-versions).
- **Rolling deploys apply only from 0.4.0 on.** Stop every 0.3.0 node before the first 0.4.0 node starts (step 1). After that, deploy new versions with `activateOnDeploy` set to `false` and activate them once every node runs the new code.
- **Deployments are roll-forward only.** After 0.4.0 runs, a node whose code lacks a version that is not retired fails startup with `CODE_UNAVAILABLE`, and rolling code back to 0.3.0 is not supported (see [Roll-forward only](#roll-forward-only)).
- **Annotate state classes after 0.3.0 instances finish.** The first 0.4.0 startup records each version with the names its classes have then. Annotate a class that live 0.3.0 instances use with `@WorkflowType` only after those instances finish; annotating it in the deployment that first runs 0.4.0 changes the recorded hash and startup fails with `DRIFT` while instances are pinned (see [Type names and name bindings](#type-names-and-name-bindings)).
- **Scopes.** Existing instances, tasks and start deduplication rows read the `default` scope. Introduce other scopes only when you load documents into them.

### 5. Check what changes at startup

Every PostgreSQL application, including code-first ones that never touch documents:

- **First boot writes `CODE` rows.** Each code definition is recorded as a default-scope row. The `V002__` migration must have run.
- **Code drift can fail startup.** If a version's compiled shape changed and instances are pinned to that exact version
  (`RUNNING`, `WAITING` or `COMPENSATING`), startup fails with `DRIFT` naming both hashes. Keep the code of a version unchanged
  until its instances finish, or bump the version. With no pinned instance the stored hash is replaced and a WARN logs it.
- **Key rules.** A code definition with a blank, over-long or control-character id, or a non-positive version, fails
  startup with a message naming the definition. Definition ids and scope keys are at most 255 code points.
- **Kind conflicts** fail startup naming both rows.
- **Definition ids are unprefixed.** A document may use the id of a code definition or of another scope's document; each
  scope holds its own versions of the id.

Applications that use static document sources gain extra startup prerequisites. Each packaged document now must:

- **Fit the stored envelope.** At the default L it is at most 393216 UTF-8 bytes and 262144 Unicode code points after
  decoding. In 0.3.0 static JSON had no document size cap; this is a new boundary for JSON sources. A YAML source keeps
  its existing code-point guard, which now follows L.
- **Be strictly representable.** The whole source must decode without malformed encoding, decoded NUL or unpaired surrogates,
  including trailing bytes the parser ignores. In 0.3.0 such trailing bytes were accepted. Escaped source sequences
  stay as source text.
- **Be stored as UTF-8 text.** Durable storage keeps decoded characters, comments, whitespace and line endings, not the
  original bytes. JSON may arrive in UTF-8, UTF-16 or UTF-32; YAML must be UTF-8. The database must be `UTF8`.
- **Not change text without a new version.** An equal plan hash no longer proves equal text. A packaged document whose
  text differs from the stored text of the same id and version, even by a comment, whitespace or line endings only, fails
  startup with `DRIFT`, on every node whose packaged text differs from the stored text, including during a rolling deploy.
  Bump the version of a changed document: a row of the same id and version blocks startup in every status, retired
  ones included.

Metadata limits apply to `load` and static sources: definition id and scope
key at most 255 code points; source type at most 64; URI at most 2048; revision, author and upload id at most 255. The
definition service checks the key (id length and control characters, positive version). NUL and unpaired surrogates are rejected in every text field, and
ISO control characters in ids and scope keys. A scope key must also be nonblank, at most 255 code points, free of format characters (zero-width characters, bidirectional marks and overrides), must not start or end with whitespace, and must not be blank once its format characters are removed. A version must be positive. An over-limit source is rejected before anything
is registered, and a static source failing a limit fails startup naming the resource.

### 6. Plan the rollout

- **Same-version rehash.** The first 0.4.0 node that finds no pinned instance for a changed version rewrites the stored
  hash. From then on, every node still running the old code refuses new starts and migrations of that version with `DRIFT`, until it restarts
  on the new code. A failed instance pinned before the rewrite can no longer be retried on any node, because its recorded
  hash is the old one. Prefer a new version over editing a deployed one.
- **Rollback or old-code restart.** A node that restarts on code lacking a version the database holds as live fails startup
  with `CODE_UNAVAILABLE`. Deployments are roll-forward only (see [Roll-forward only](#roll-forward-only)).
- **Deleting a version.** To remove a version's code safely: `deprecate` it, `retire` it
  while no instance is pinned, then remove the code in a later deployment.
- **Finish the rolling upgrade first.** `retire` and `reinstate` rely on every node taking the shared id lock when it
  admits a pin, so use them only once all nodes run 0.4.0.
- **Startup stalls.** Startup writes wait for open caller transactions on the same ids, up to 30 seconds per lock, and then
  fail with a retryable persistence error naming the id.

### 7. Adapt callers of the engine

- **Transaction clients.** Public transaction-aware engine entrypoints (`start`, the `signal` overloads, `cancel`, `retry`,
  `migrate`, and the task callbacks) require a `SqlConnection` with a pending transaction begun through Vert.x,
  for example with `Pool.withTransaction`. A `Pool`, a bare or completed connection, a raw `BEGIN`, a wrapper or a mock
  fails the returned `Future` with `IllegalArgumentException` before any SQL. Await the operation before committing or
  rolling back. A custom `WorkflowTransactionRunner` must keep the transaction pending until the work future completes.
- **Pin admission.** Starts and migrations now run the admission check described under
  [Database coordination](#database-coordination), including the 30-second `SET LOCAL lock_timeout` and the stronger-isolation
  refusal.
- **Registry implementations.** `WorkflowRegistry` gains `unregister`, `markDeprecated`, `isDeprecated` and
  `registeredDefinition` as default methods, so existing implementations compile. The defaults of the first two throw
  `UnsupportedOperationException`, `isDeprecated` returns `false` and `registeredDefinition` returns empty.
  A decorator that forwards only the old members therefore makes `deprecate` and `retire` fail after the durable commit,
  keeps serving deprecated versions to new starts, and reports a code version as `CODE_UNAVAILABLE` on reinstatement.
  Forward all four members. `DefaultWorkflowRegistry` implements them.
- **Instance query.** `WorkflowInstanceQueryService.countNonTerminal(id, version)` is a default method that fails with
  `UnsupportedOperationException`; the PostgreSQL implementation overrides it.
- **Added bindings.** Applications gain the bound `WorkflowDefinitionLifecycle` and the engine's plan cache. No timer is
  armed: a node reads the rows it needs when it needs them.
- **Instance scopes.** `V002__` adds the scope columns, which default to `default` (see [Scopes](#scopes) and
  [Scope Keys On Instances And Tasks](#scope-keys-on-instances-and-tasks)). Record patterns that deconstruct `WorkflowInstance`,
  `TaskRecord`, `WorkflowInstanceQuery` or `TaskFilter` by component list need the new trailing `scopeKey` components
  (`scopeKey` and `definitionScopeKey` on the instance). Other modules or tools that read `workflow_instances` or
  `workflow_tasks` see extra non-null columns, and tools that read `workflow_dedup.scope` must read `dedup_namespace`.

### 8. Configuration

- `workflow.definitions.publicationClaimTimeoutSeconds` (60) replaces the former staleness-seconds key of the same group,
  which is no longer read. An application that still sets the old key gets the default for the new one, so rename it.
- `workflow.definitions.maxDocumentSizeBytes` (262144) is unchanged.
- New: `workflow.definitions.cache.maximumEntries` (500) and `workflow.definitions.cache.ttlSeconds` (0, no expiry) size and expire
  the plan cache, and `workflow.definitions.buildDefaultScopeDocumentsAtStartup` (`false`); see [Plan cache](#plan-cache).
- New: `workflow.executor.*` (see [Node roles and the executor](#node-roles-and-the-executor)) and `workflow.recording.*`,
  including `workflow.recording.maxOpenSignalsPerInstance` (100) and `workflow.recording.intake.*` (see [Intake limit](#intake-limit)),
  which is on by default.

See [Configuration](#configuration).

### 9. Adopt recorded execution

- **Run an executor.** Every node that should run workflow code needs `WorkflowSchemaModule` and `JsonRuntimeModule` and, when the application is wired by hand, the deployer of `vertique-deploy` so that the `workflow-executor` verticle is deployed in the `SERVICES` phase. Without the deployer no command executes. Use `workflow.executor.enabled=false` for a node that only records.
- **Expect operations to return before they run.** `start`, `signal`, task completion, `retry` and `migrate` return once the command is recorded. A failure, a rejection or a stale outcome appears in the instance's log and not as an exception of the call. Code and tests that read an instance right after a start await the execution probe.
- **Remove the delayed module.** The `vertique-workflow-delayed` artifact is removed in 0.4.0, because timers are commands: drop the dependency and `WorkflowDelayedModule` from your components.
- **Handle the new refusals.** A signal beyond the open-signal cap fails with `WorkflowSignalLimitException`. A new start or signal fails with `WorkflowIntakeRefusedException`, a 503 that writes nothing, while the cluster holds too many due commands (see [Intake limit](#intake-limit)); retry it later. Release notes: callers can now receive this 503 under load.
- **Review who the downstream runs as.** A service operation that a segment dispatches through the outbox runs as the relay's identity, not as the recording user. See [Who asked, and what the downstream runs as](#who-asked-and-what-the-downstream-runs-as).
- **Decide on redaction and read authorization.** Bind a `WorkflowHistoryRedactor` if the log must never hold some values, and authorize the history reads in your application.
- **Add the metrics bridge** if you want executor meters: `vertique-micrometer-workflow` with a bound `MeterRegistry`.
- **Transaction deadlines.** `TransactionBuilder.deadline(Duration)` and `TransactionDeadlineExceededException` of `vertique-db-core` are new and additive: the executor bounds its segments with them, and without `deadline(...)` nothing changes in other transactions.

## Scope Keys On Instances And Tasks

`WorkflowScopes.DEFAULT` (`"default"`) is the reserved default scope. It is a value, not a stored record: it always exists
and needs no registration. `WorkflowInstance` carries a non-null start scope (`scopeKey`) and the scope of its pinned
definition version (`definitionScopeKey`); `TaskRecord` carries a non-null `scopeKey`; `WorkflowInstanceQuery` and
`TaskFilter` carry a nullable `scopeKey` filter. The engine stamps the start scope of the `StartCommand` on a new instance
(the default scope when the command names none) and the scope of the resolved definition version as the definition scope.
The start scope is validated like any scope key: `StartCommand` refuses an invalid one. Migration and resurrection keep an
instance's start scope for its life; a migration to another scope's definition changes only the definition scope.

- **Tasks follow the instance.** The PostgreSQL task store writes the owning instance row's start scope on every task
  insert, whatever the `TaskRecord` carries, so a task created after another writer stamped its instance carries that
  scope and a task created earlier stays in the default scope.
- **Filters.** `WorkflowInstanceQuery.scopeKey` and `TaskFilter.scopeKey` match exactly when non-null; `null` matches every
  scope. Never pass an unresolved scope as `null` expecting scoping.
- **Information, not isolation.** The scope is a label and a filter. Operations addressed by instance or task id (query,
  signal, cancel, retry, migrate, task complete or reassign) do not check it, and timers, branch tokens and definition
  resolution are global: before acting on a caller-supplied id, load the instance and compare its `scopeKey()` with the
  caller's scope. Definition-lifecycle refusal messages can name another scope, status and plan hashes: do not
  forward them to end users. Definition ids may contain Unicode format and bidirectional characters that validation
  does not strip (scope keys reject them); do not render them unescaped in operator UIs or logs.

## Durable Context Propagation

Workflow timers, branches, instances, signals, and commands all participate in the framework's durable context propagation substrate. Every engine drive binds an *effective* durable-context document computed from a **base** (the most specific context available for that drive) with an **instance-fill** step that fills only namespaces the base does not already carry.

### New metadata fields (workflow-core)

| Type | Field | Description |
|------|-------|-------------|
| `TimerRecord` | `metadata: DurableMetadata` | Namespaced durable propagation context captured at timer-create time; persisted as `{"context": …}` JSONB; immutable after creation |
| `BranchToken` | `metadata: DurableMetadata` | Namespaced durable propagation context captured at branch-create (fork) time; persisted as `{"context": …}` JSONB; immutable through all subsequent state transitions |
| `WorkflowInstance` | `metadata: DurableMetadata` | Namespaced durable propagation context captured once at `start` time; persisted as `{"context": …}` JSONB on `workflow_instances`; `null` when the ambient capture at start was empty (no empty-document rows); immutable for the life of the instance — no transition, migration, retry, or repair operation overwrites it |

### Instance carrier and the `WorkflowContextBinder`

At `start`, the engine captures the ambient durable context via the propagator at the `WORKFLOW` boundary and persists it on the new instance row within the start transaction, after removing the identity-snapshot namespace, so no later drive can bind an identity from the instance carrier. `start` itself is capture-only — it opens no new durable scope; the ambient it captures *is* the context being persisted. `start` only records: the instance is `PENDING` until the executor runs its first segment, which binds the instance carrier like any later drive and drives the first steps. That segment runs on a fresh duplicate of the executor verticle's own context, so nothing bound to the context of the caller that recorded the start, or of whoever deployed the executor, reaches it.

Every later drive computes an **effective document**: `base.merge(instanceMetadata, MergePolicy.CALLER_WINS)`. The base is authoritative for every namespace it carries; the instance carrier contributes only namespaces the base lacks. An empty base with a non-`null` instance carrier therefore binds the instance context in full. The base is chosen per drive, in priority order: an explicit signal carrier for that transition, else the drive's own persisted carrier (branch-token or timer-row metadata), else the ambient inbound context for caller-invoked and transport-dispatched drives, else the empty document.

**Bind gate (the dark-path guarantee):** when the instance carrier is `null` and the drive carries no explicit signal carrier, the drive binds exactly as it always has — no instance fill, no new scope. An application that registers zero durable namespaces therefore sees every instance carrier persist as `null` and every drive stay bit-for-bit unchanged; there is no separate kill switch, the `null`-skip chain *is* the dark path. An explicit signal carrier always binds regardless of the instance carrier — it is the sender's deliberate handoff, not instance fill.

**Bind-once routing rule:** every drive gets exactly one durable bind, at exactly one seam.

- **Binder rows** — instance-owned single-path drives (`signal` without a branch target, `cancel`, `retry`, `migrate`, and single-path `taskCompleted`/`taskReassigned` drives with no branch token involved) bind through the internal `WorkflowContextBinder` collaborator (`vertique-workflow-engine`), which implements the gate, base selection, and instance-fill merge above, then installs the effective document via `InboundExecutionContextScope.installDurable(...)` before running the drive.
- **Branch-owned drives** — branch-targeted signals, and branch-owned timer fires and task completions — keep their bind on the existing `BranchTransitionEngine` carrier seam (below), which now receives the merged effective base through an added `effectiveBaseOverride` parameter instead of using raw `token.metadata()` directly. A drive is never bound at both seams.

Instance fill happens before registered `InboundContextInitializer`s run (notably the correlation seeder), so a namespace the instance carrier supplies is never re-seeded — a drive with an absent-from-base correlation namespace and a `null` instance carrier still gets a freshly seeded correlation, matching the behavior of every other drive.

**Fork absorption:** a branch forked during a filled drive persists the *effective* (base + instance-fill) document into its token, because fork-time capture reads the bound context holder unchanged by this feature. This intentionally closes the "carrier holds only what was ambient at fork time" gap for every branch forked after instance carriers exist, without any change to how forks capture context.

### Explicit signal carrier

A signal can carry an explicit durable-context carrier on the wire: `WorkflowSignalRequest.metadata` — an optional `@Nullable JsonObject` holding the raw `{"context": …}` shape produced by `DurableMetadata.toCarrier()`. When present, it is authoritative as the base for that transition only (including, for a branch-targeted signal, winning over the branch token's persisted metadata); it is never persisted onto the instance or the branch token. `WorkflowSignalContributor` decodes it via `DurableMetadata.fromCarrier(...)` before calling the metadata-aware `TransactionalWorkflowOperations.signal(..., @Nullable DurableMetadata signalMetadata, TX tx)` overload. That overload's default implementation delegates to the legacy 7-arg overload when the carrier is `null` (behavior unchanged) and fails fast with `UnsupportedOperationException` on a non-`null` carrier unless the implementation overrides it — the carrier is never silently dropped. `WorkflowEngine` overrides it with the real bind.

`DurableMetadata.fromCarrier`/`fromJson` validate carrier shape at decode time and raise `MalformedDurableMetadataException` (extends `ValidationException`) rather than letting a malformed nested value surface as a raw `ClassCastException` in a later merge or read path.

**Security note:** the signal ingress endpoint (`workflow.signals.post`) is internal-relay-only, and an explicit carrier is bound as the authoritative durable-context base for its transition — it MUST NOT be populated from untrusted or end-user input. Only the relay that produced the carrier via `DurableMetadata.toCarrier()` may supply it. Provenance authentication must derive trusted namespaces from the authenticated `SecurityContext` rather than trust a carrier verbatim.

### Command correlation stitching

Command drives — task completion, task reassignment, cancel, retry, migrate — append history with an additive `commandCorrelationId` field, sourced from `ContextValues.current(CorrelationContext.class)` inside the bound drive. This is a causal record of who drove the transition, independent of which namespaces filled the effective document. The field is additive on `TaskCompletedHistoryPayload`, `TaskReassignedHistoryPayload`, `TaskCancelledHistoryPayload`, `RetriedHistoryPayload`, `WorkflowMigratedHistoryPayload`, and the workflow-level cancel history payload — old rows deserialize with `commandCorrelationId = null`.

### Duplicated-context requirement for newly bound drives

**Consumer-visible behavior break, accepted pre-release:** application callers invoking the public `TransactionalWorkflowOperations` tx-variants or `TaskService` mutations from a **non-duplicated** Vert.x context — which succeed today — observe `IllegalStateException` once those drives pass the bind gate (non-`null` instance carrier or an explicit signal carrier). The break is data-dependent: it manifests only when the gate passes; `null`-carrier instances never trip it. Every dispatcher entry point in production (event bus, Kafka per-record, cron `@CronJob` callback, service-method invoker) already runs on a duplicated Vert.x context, and `vertx-sql-client` 5.x preserves the caller's duplicate through `pool.withTransaction(tx -> …)`. Tests that enter the engine from a JUnit thread MUST first switch to a duplicated context via `((ContextInternal) vertx.getOrCreateContext()).duplicate().runOnContext(...)` (the same duplicated-context pattern the branch-drive seam below already requires); transport relays that cannot reach a duplicated context MUST use `decodeToDispatchContext(...)` instead.

### Timer context

A timer is a `workflow_timers` row linked to a `TIMER` command. The segment that enters the timed wait captures the ambient durable context once, removes the identity-snapshot namespace, and writes it to `workflow_timers.metadata`, signed for the workflow-timer carrier. The command stores no context.

When the command comes due, the executor's `TIMER` segment binds `workflow_timers.metadata` as the base of the drive, like any other persisted carrier, so the context survives the wait without a second copy.

### Branch-drive seam

`BranchTransitionEngine.driveBranchTransitions` binds `token.metadata()` once per per-branch drive via `InboundExecutionContextScope.installDurable(metadata, DispatchBoundary.WORKFLOW)`. The bind covers all branch step types: timer-step, signal-step, service-dispatch, and decision. The scope closes on `Future.eventually(...)`.

`driveBranchTransitions` takes an additional `@Nullable DurableMetadata effectiveBaseOverride` parameter. When non-`null`, the drive binds that already-computed effective document instead of raw `token.metadata()` — this is how a branch-targeted explicit signal carrier routes its merged base + instance-fill document into the branch carrier seam without a second, competing bind. When `null`, behavior is exactly what it was before instance carriers existed: bind `token.metadata()` with the empty-document fallback.

The substrate helper wraps the authoritative `DurableContextPropagator.bindFrom(...)` — every registered durable namespace absent from `token.metadata()` is cleared for the drive's scope lifetime. This prevents the drive's ambient context (e.g. a service handler that triggered the workflow signal) from leaking into a subsequent durable-context encode at a downstream seam — specifically the timer metadata that a timer command records. An empty `DurableMetadata` document still triggers the bind; it clears whatever ambient durable namespaces are live on the call-site context. After the durable bind, registered `InboundContextInitializer`s run (notably the correlation seeder), so branches whose metadata carries no encoded `CorrelationContext` still get one bound for the drive.

Production reaches this on a duplicated context: every dispatcher (event bus, Kafka per-record, cron `@CronJob` callback, service-method invoker) is on a duplicated Vert.x context, and `vertx-sql-client` 5.x preserves the caller's duplicate through `pool.withTransaction(tx -> …)`. `installDurable` propagates the strict `bindFrom` invariant: `IllegalStateException` on a non-duplicated Vert.x context (no-op only when there is no Vert.x context at all). Tests that enter the engine from a JUnit thread MUST first switch to a duplicated context via `((ContextInternal) vertx.getOrCreateContext()).duplicate().runOnContext(...)`; transport relays that cannot reach a duplicated context MUST use `decodeToDispatchContext(...)` instead.

### Branch metadata immutability

Branch metadata is set at fork time and is immutable thereafter. State transitions — a terminal failure (FailNode-driven) — preserve the original `token.metadata()` unchanged. No subsequent engine operation overwrites it.

---

## Operational Principles

- Always provide idempotency or dedup keys where APIs require them.
- Treat workflow state as orchestration state, not a shadow copy of domain records.
- Use subjects to link workflows to domain objects.
- Keep downstream calls behind side-effect recorders and transactional outbox handoff.
- Keep in-flight instances pinned to their started version unless explicitly migrated.
- Deploy new versions with `activateOnDeploy` set to `false` and activate them once every node runs the new code; deployments are roll-forward only.
- Put authorization in the application layer. Workflow records actors and decisions; it does not decide access policy.

---

## Where To Go Next

- Start with [workflow-core.md](../vertique-workflow/vertique-workflow-core/src/main/resources/META-INF/vertique/module.md) for concepts and public APIs.
- Add [workflow-postgresql.md](../vertique-workflow/vertique-workflow-postgresql/src/main/resources/META-INF/vertique/module.md) for durable execution.
- Add [workflow-services.md](../vertique-workflow/vertique-workflow-services/src/main/resources/META-INF/vertique/module.md) for service dispatch.
- Add [workflow-tasks.md](../vertique-workflow/vertique-workflow-tasks/src/main/resources/META-INF/vertique/module.md) for human task APIs.
- Add [workflow-events.md](../vertique-workflow/vertique-workflow-events/src/main/resources/META-INF/vertique/module.md) for external event streams.
- Add [workflow-definition.md](../vertique-workflow/vertique-workflow-definition/src/main/resources/META-INF/vertique/module.md) for YAML/JSON authoring.
- Add [codegen-workflow.md](../vertique-codegen/vertique-codegen-workflow/src/main/resources/META-INF/vertique/module.md) for contract validation, client proxies and the generated type catalog module.

---
