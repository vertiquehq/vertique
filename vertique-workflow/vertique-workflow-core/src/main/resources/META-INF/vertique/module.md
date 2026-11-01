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
| Timers, signal timeouts, task due dates | Nothing to add: they are timer commands that the workflow executor of `vertique-workflow-postgresql` fires |
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

### Signal Wait Key

`waitForSignal(stepId, signalName, Payload.class)` returns a `SignalWaitBuilder`. Its optional `correlateBy(Function<S, String>)` declares the value that identifies the wait, computed from the workflow state:

```java
wf.waitForSignal("await-payment", "payment.captured", PaymentCaptured.class)
  .correlateBy(OrderState::paymentReference)
  .onSignal((state, event) -> state.withPaid(true))
  .toStepOnSignal("ship");
```

The function is registered under the callback id `<stepId>.correlationKey`, and the resulting `WaitSignalNode` carries that id as `correlationKeyCallbackId` (the plan holds the id, never the function). The id is `null` when the wait declares no key, and the deprecated `waitFor` declares none. `WorkflowCallbackRegistry.correlationKeyResolver(CallbackId)` returns the function; like `timerResolver`, its default throws `UnsupportedOperationException`, and registration fails a plan whose node names a key callback the registry cannot resolve. The declaration is recorded in the plan; the engine computes the key each time an instance enters the wait (see `dev.vertique:vertique-workflow-engine`).

`WaitSignalNode` has seven components and keeps its six-argument constructor, which leaves the key absent. A component added later is appended after `correlationKeyCallbackId`.

A computed key is a non-blank string of at most 255 characters with no NUL. A key that is `null`, blank, too long or holds a NUL, or an accessor that throws, fails the wait's segment with `WorkflowCorrelationKeyException`, a final `WorkflowDefinitionException` (so a signal whose application reached the wait is rejected and the instance stays where it was). Its message names the definition, the step and the reason, never the key; it carries no cause, because an accessor's own message may hold the key.

### Delivery By Key

`TransactionalWorkflowOperations<TX>` has two delivery methods that address a signal by a key instead of an instance id:

```java
Future<CorrelatedSignalReceipt> signalByKey(CorrelatedSignal signal, TX tx);
Future<CorrelatedSignalReceipt> signalByBusinessKey(CorrelatedSignal signal, TX tx);
```

Both are default methods that fail the returned future with `UnsupportedOperationException`, so an implementer written before them keeps compiling; a binding opts in by overriding. `dev.vertique:vertique-workflow-engine` overrides `signalByKey`.

`CorrelatedSignal` is a record of `contract`, `signalName`, `key`, `payload`, `dedupKey`, `definitionId`, `scopeKey` and `provenance`. `CorrelatedSignal.of(contract, signalName, key, payload, dedupKey)` leaves `definitionId`, `scopeKey` and `provenance` null, so the delivery matches across definitions and scopes; `withDefinition`, `withScope` and `withProvenance` return a copy that differs in that one component. The key must be non-blank, at most 255 characters and free of NUL; the dedup key must be non-blank; `withScope` applies the scope key rules. A refusal names the component and never repeats the value, and `toString` prints the contract and signal name only, never the key, dedup key or payload.

`CorrelatedSignalReceipt(outcome, definitionId)` reports `RECORDED` with the id of the definition of the matched instance, or `DUPLICATE` with a null definition id. A delivery that finds no open wait fails with `WorkflowSignalNoMatchException`, and one that finds more than one with `WorkflowSignalAmbiguousException` (narrow it with `withDefinition` or `withScope`); each exposes `contractName()` and `signalName()`, its message names those two and never the key, and a caller may deliver again later because a wait may open. `WorkflowDedupScopes.correlatedScope(contractName, signalName, scope)` and `correlatedKey(dedupKey)` encode the dedup row of a delivery by key: each is a 64-character lowercase SHA-256 hex digest, the first of the three parts joined by the unit separator `0x1F` with the empty string for an absent scope, the second of the dedup key, so a dedup key of any length fits and no key is stored. `SignalProvenance(transport, source, attributes)` names where a signal came from: `SignalProvenance.kafka(source, topic, partition, offset)` and `SignalProvenance.camel(source, triggerId, messageHash)` fill `transport` and `source` and put their other arguments in `attributes` (numbers as decimal strings); the map is copied immutably. Both delivery methods store the provenance with the command, in its own column and never in the command's target, and an executor does not read it. A delivery that carries a provenance and captures no actor (no identity bound, or an anonymous one), or whose identity the actor mapper refuses, fails with `WorkflowSignalActorException` and writes nothing, so a source never records a command that no actor stands behind; the exception exposes `contractName()` and `signalName()`, its message names those two and never the key, and it is neither a no-match nor an ambiguity, so a caller does not retry it as one. A delivery without provenance records with an absent actor as before.


### Parallel Branches

`WorkflowBuilder.fork(stepId)` declares a fan-out into named branches, each with its own start step; `join(stepId)` declares the matching join step with a completion policy (`allRequired`, `firstSuccess`, or `firstFailure`) that reduces branch results back into workflow state. Each running branch executes against its own `BranchToken`; the fork's fan-in progress is tracked in a `JoinState`.

### Workflow Plan

Registration turns a definition into an immutable `WorkflowPlan`. A plan contains serializable step metadata, callback ids, type names, and a content-derived plan hash. Executable Java callbacks stay in the callback registry and are referenced by id. The engine executes only `WorkflowPlan` objects — the Java DSL above and document-based authoring (see `dev.vertique:vertique-workflow-definition`) are alternative ways to produce one.

The engine records the definition id, definition version, and plan hash when an instance starts. Resumes use that pinned version rather than silently switching to the latest registered definition.

### Logical Type Names

A plan names its state type, its signal payload types and its decision payload types. By default a name is the class's binary name (`Class.getName()`), so renaming or moving a class changes the name in the plan and with it the plan hash. To keep the name stable, annotate the class:

```java
@WorkflowType("order.paid")
public record OrderPaid(String orderId) {}
```

`@WorkflowType` takes a required `value()` that is the logical name: nonblank, at most 255 code points and free of ISO control characters. An invalid name fails registration with a `WorkflowDefinitionException` that names the class and never the raw value. `@WorkflowContract("orders")` names a contract the same way; an empty `value()` (the default) means the interface's binary name. A contract name that is blank or longer than 255 code points fails compilation with an error that names the interface and never the value.

- **Two namespaces.** Type names (state and payload classes) and contract names are separate: a contract and a state class may both be named `shared`.
- **One class per name.** Within a namespace a name maps to one class. A second class under the same name fails registry startup with `WorkflowTypeNameConflictException`, which names the logical name and both binary class names. Every indexed class is also reachable by its binary name, so a logical name equal to the binary name of a different indexed class is the same conflict, whichever class is indexed first.
- **Plan and hash.** `WorkflowPlan.stateTypeName` and the payload type names of wait-signal nodes and task decisions hold logical names, and the plan hash digests them. A class without `@WorkflowType` hashes exactly as before, so plans of unannotated types keep their hash. Annotating a class whose binary name differs from the new logical name changes the hash of every version that uses it, unless the registry kept the names that version was published with (see the next item). The start payload type and the contract name are not part of the hash.
- **Name bindings.** With a durable registry store, a version's row keeps a binding from the binary name of each state and payload class its plan names to the name its plan hash used (`WorkflowDefinitionRecord.nameBinding`). It is written when the row is first recorded with its plan hash and again only with a replaced plan hash, never otherwise. Every later start hashes the version with that binding: a class in the binding keeps its recorded name, so annotating a class after a version was published leaves that version's hash and its live instances alone, while a version first published afterwards records and hashes the new logical name. A class the binding does not hold, such as one added by changed code, hashes by its current logical name. A version that instances of an earlier release started under has no row until the first start of this release, which records it with the names its classes have then: upgrade before you annotate a class those instances use. A class that a non-retired version is bound to by its binary name cannot move: startup then fails with `WorkflowUnknownTypeNameException` naming the scope, the definition, the version and the recorded name; keep the class where it is, or retire the version first. A retired version is not served, so its binding is not checked. Without a store no binding is kept and every version hashes with current logical names. Annotate contracts and state and payload classes before moving them: a contract without `@WorkflowContract` is identified by its binary name, so moving it changes its logical name, and registration then refuses the version for naming another contract than earlier versions.
- **Resolver.** `WorkflowTypeNames` maps names to classes and back: `nameOf` and `contractNameOf` return the logical name, `resolve` and `find` look up a type, `findContract` looks up a contract. A lookup tries the logical name and then the binary name of an indexed class; it never loads a class by name, and `resolve` throws `WorkflowUnknownTypeNameException` for a name nobody indexed. The message repeats the name only when it consists of letters, digits, underscore, dot, dollar sign and hyphen and is at most 255 characters long; any other value is described without being repeated. `DefaultWorkflowTypeNames` is the internal framework implementation of that index, not an application contract; depend on `WorkflowTypeNames`. `WorkflowCoreModule` provides one singleton index, binds it to `WorkflowTypeNames`, and the registry adds each registered definition's contract, state, start, signal and decision classes to it before the contributed validators run. A failed registration removes the names it added. A registration refusal that repeats a document-supplied signal name, and an engine refusal that repeats a caller-supplied signal or contract name, show at most 64 code points of it, replace control characters and unpaired surrogates by `?` and end a cut name with `...`; a refusal for undeclared signals names the first five in sorted order and counts the rest.
- **Type catalogs.** `WorkflowTypeCatalog` (`List<WorkflowTypeCatalogEntry> entries()`) lists classes under their names; `WorkflowTypeCatalogEntry(Kind kind, String name, Class<?> type)` has `Kind.CONTRACT` or `Kind.TYPE`. `WorkflowCoreModule` declares the `Set<WorkflowTypeCatalog>` multibinding, empty by default, and builds the one index from it at startup before the registry adds the classes of registered definitions. The workflow annotation processor generates a module that contributes a catalog for the `@WorkflowContract` and `@WorkflowType` classes it compiles; an application can contribute one by hand with `@Provides @IntoSet WorkflowTypeCatalog`, for example for a class the generated module cannot reference. Every listed class is reachable by the entry name and by its binary name.
- **Merge and conflicts.** Entries from all catalogs, and later the registered definitions, share the two namespaces. Two catalogs that list different classes under one name in a namespace fail startup with `WorkflowTypeNameConflictException`, which names the name and both binary class names; listing the same class twice under the same name is not a conflict. An invalid entry name fails startup with a `WorkflowDefinitionException` that names the class.
- **Documents resolve through the index.** A document loaded through the definition service or restored from the store resolves its contract, state and payload names through the index only; it can reach exactly the classes the catalogs and the registered definitions put there, and no class is loaded by a name the document supplies. See the workflow-definition guide.

### Workflow Registry

`WorkflowRegistry` holds the code definitions of the application. Applications normally register definitions through Dagger `WorkflowContributor` multibindings:

```java
@Provides
@IntoSet
static WorkflowContributor orderFulfillment(OrderFulfillmentDefinition definition) {
    return registry -> registry.register(definition);
}
```

The registry validates definitions at registration time. Invalid graphs fail before they can serve traffic. A document definition, packaged or published at run time, is never registered: it is a row of the definition store, and the node builds its plan from the row when the plan is first used (see Loading plans).

`definitionClass(Class<?>)` answers with a `RegisteredDefinitionClass(definitionId, contractName, contract)` for the exact runtime class of a registered definition, and is empty for a class that was never registered or was registered under more than one definition id. `WorkflowClientFactory` uses it to create a client from a definition class. It is an abstract member of the interface: `DefaultWorkflowRegistry` implements it, and a custom `WorkflowRegistry` implementation no longer compiles until it implements the member (an implementation that has no definition classes returns `Optional.empty()`). Workflow releases may add further abstract members, so the registry is an interface the framework implements rather than one applications extend.

A version is identified by a `DefinitionKey`: the scope that owns it, the definition id and the version. `register(definition)` registers a code definition in the default scope, `WorkflowScopes.DEFAULT`, and is a startup operation: once the engine's startup step has ended it seals the framework registry (`DefaultWorkflowRegistry.seal()`, called by the engine only, idempotent), and `register(definition)` then throws `IllegalStateException` naming the definition and leaves the registry as it was. Code definitions register while the registry is constructed, before the seal; a registry no engine started, as in a unit test, is never sealed. The registry holds nothing else: a version of another scope, and a packaged or published document of any scope, is a row of the definition store whose plan lives in the plan cache. The SPI takes definition keys: `resolve(key)` returns the plan of a code definition or a document plan that is resident now, or throws `WorkflowDefinitionNotFoundException` (a `WorkflowVersionPinUnavailableException`, so existing handlers keep working) naming the scope, the definition id and the version, `find(key)` answers an `Optional`, `load(key)` answers a `Future` of the plan (see Loading plans), and `codeDefinitions()` lists the code definitions deployed with the node, never a document plan. Whether a version is available, deprecated or retired, which contract it names and which version a start runs are answers of the definition store, read by the engine in the transaction that needs them. `resolveCurrent` is removed: the version a start without a version runs is chosen by activation records, not by the registry (see Activation records). Every `RuntimeWorkflow` reports the scope it is registered in as `definitionScopeKey()`. A custom `WorkflowRegistry` must implement `register(definition)`, `resolve`, `find`, `load`, `definitionClass` and `codeDefinitions`; the members that registered a version of another scope, listed versions across scopes, removed a version or marked one deprecated are gone, and the definition store answers what they answered. The engine resolves every instance's plan with the instance's full pin: its definition scope, definition id and definition version.

**Loading plans.** `load(key)` is the lookup that may take time. It answers a plan the registry holds, which is every code definition, at once. For any other key `DefaultWorkflowRegistry` hands the request to the `DocumentPlanSource` the engine binds (an INTERNAL seam between the registry and the engine's plan cache, not an extension point): the stored row is read and the plan built on a worker thread, outside any transaction, into a size-bounded cache, and concurrent requests for one key share one read and one build. The future fails with `WorkflowDefinitionNotFoundException` when no available row supplies the version, with a `WorkflowDefinitionException` subtype when the plan cannot be built (the document does not compile, a recorded type name no class resolves, or the rebuilt plan hash differs from the stored one; the cache remembers these), or with the persistence failure that prevented the row from being read (never remembered). `find(key)` and `resolve(key)` never load: they answer a key the registry holds, or a document plan that is resident in the cache now, and otherwise `find` is empty and `resolve` throws `WorkflowDefinitionNotFoundException`. A registry with no source bound, such as one built for a unit test, answers `load` for a key it does not hold with that exception. `load` is an abstract member, so a custom `WorkflowRegistry` implementation supplies it; one that holds every plan itself answers `find(key)` as a succeeded future or fails with `WorkflowDefinitionNotFoundException`.

**State types.** All code versions of one definition id use one state class, compared as the resolved class rather than the stored name; registration refuses a version that uses another and names the scope, the id and the version and no state class name (the class names are logged at DEBUG level only). That state class may evolve only by adding properties: adding a property to a shared class is safe, removing or retyping one is not, and the registry does not check an edit of a class in place. A property added to a type that stored state JSON lacks must tolerate absence (it reads as the type's default). When a scope-owned definition and the default scope's definition of the same id name the same contract and different state classes, the scope-owned class must extend the default's: every property of the default's class exists in the scope-owned class with the same type, nested objects, collection items and map values compare by the same rule, and an enum must keep every constant. "Extends" is schema compatibility, not Java inheritance. The comparison is the `WorkflowStateCompatibility` SPI (`firstIncompatibility(source, target)` answers the first incompatible property path, such as `address.city` or `items[].sku`, or empty); `vertique-workflow-schema` binds the implementation and `WorkflowCoreModule` declares the binding optional. The engine applies it when the plan cache builds the plan of a scope-owned version: a plan whose state class does not extend the default's is marked failing (see Failing rows). Without an implementation differing state classes of one id and contract across scopes never extend. Definitions of different contracts are never compared. A definition built from a document may use only named callbacks registered for exactly its own state class, so a callback registered for the default definition's state class is refused for an extending state class.

**Failing scope rows.** A stored scope definition that newly deployed code conflicts with is still built, so that its running instances keep resuming: the plan cache builds its plan under every plan rule and records the conflict as a failing mark on the row (see Failing rows). Only the comparison of the scope's state class with the default scope's is left to the mark; the plan checks, the signal declarations and the contributed validators still refuse a plan.

Registration runs the plan checks and the callback consistency check, indexes the definition's type names (see Logical Type Names), then every contributed
`WorkflowDefinitionValidator`, and only then publishes the definition. The validator step applies to
bootstrap and runtime registrations alike. A validator exception propagates unchanged to the caller
of `register` or `publish` and leaves the `(definitionId, version)` pair unresolvable.

### Workflow Operations

`WorkflowOperations` is the SQL-free application facade:

```java
Future<WorkflowInstanceId> start(StartCommand cmd);
Future<Void> signal(WorkflowInstanceId id, String signalName, Object payload, String signalDedupKey);
Future<Void> cancel(WorkflowInstanceId id, String reason);
Future<Void> retry(WorkflowInstanceId id);
Future<WorkflowView> query(WorkflowInstanceId id);
Future<Void> migrate(WorkflowInstanceId id, long targetVersion);
Future<Void> migrate(WorkflowInstanceId id, String targetScope, long targetVersion);
```

`retry` and both `migrate` forms record a command in the caller's transaction and return once it is recorded; an executor carries the command out afterwards, in queue order, so a caller that needs the effect awaits the execution probe. `retry` is the single recovery operation: when it runs it initializes an instance whose start failed before initialization from the stored start payload (a dead-lettered start is settled as superseded, so initialization runs once), drives a `FAILED` instance again from its current step and applies the signals parked meanwhile at the wait it reaches, and then offers every dead-lettered command of the instance again with its attempts reset and its place in the queue kept; a retry that finds nothing to recover completes as stale. It is refused when it is recorded, with `WorkflowSignalRejectedException` naming the instance and its status, if the instance has ended or is neither `FAILED` nor holds a dead-lettered command. When an instance ends, its dead-lettered commands are rejected.

`migrate(id, targetVersion)` moves an instance to a version of the definition scope it is pinned to. `migrate(id, targetScope, targetVersion)` moves it to the definition of the same id in `targetScope`, which must be the instance's start scope or the default scope. A migration of an ended instance is refused when it is recorded, with `WorkflowMigrationIllegalStateException`. Every other check belongs to the executor, which rejects the command and leaves the instance on its previous version, state and status, with the reason in the instance's history and the command's last error (a fixed statement, never the handler's text): a scope that is not visible, a target of another contract, a target whose state class differs from the source's and does not extend it (see Workflow Registry), a deprecated or failing target, a missing handler, a handler whose state types are not those of the two definitions, a failed instance or one with active branches, a failing handler or refused pin admission, and a drive of the target plan that runs more nodes than the executor's step cap (reason `STEP_LIMIT`). A node that holds either plan not leaves the migration for a node that does. An applied migration rejects the parked signals the target version does not declare.

A `WorkflowMigrationHandler` is keyed by `(sourceScope, definitionId, fromDefinitionVersion, targetScope, targetDefinitionVersion)`. `sourceScope()` defaults to the default scope and `targetScope()` defaults to `sourceScope()`. The rule that the source version is lower than the target applies only when both scopes are equal, because each scope numbers its own versions; a move between two scopes may keep the version number. `WorkflowMigrationRegistry.find(sourceScope, definitionId, sourceVersion, targetScope, targetVersion)` takes both scopes, and `MigrationContext` carries `sourceScope()` and `targetScope()`. `find` returns the handler registered for the key whether or not either definition version is resident, and `Optional.empty()` only when no handler is registered; it resolves no plan and throws no state-type error, because the executor compares the handler's state types with the two plans when it runs the migration. The `WorkflowRegistry` constructor parameter of `DefaultWorkflowMigrationRegistry` stays for compatibility and is unused. A migration re-pins the instance's definition scope to the target; its start scope does not change.

Two default methods carry the contract of the caller: `signal(id, contractName, signalName, payload, signalDedupKey)` and `query(id, contractName)`. A binding that checks contracts compares the name with the contract of the definition version the instance is pinned to and fails a mismatch with `WorkflowDefinitionContractMismatchException`; the engine's `query(id, contractName)` compares with the contract stored on the pinned definition row, read in the query's transaction, so it needs no plan or class on any node and a pinned row that no longer exists fails with `WorkflowVersionNotRunnableException`; the defaults delegate to the contract-less methods, which perform no check. Typed clients send their contract name through these overloads; direct callers of the four-argument `signal` and of `query(id)` keep today's behavior. A wrapper or decorator around a `WorkflowOperations` binding must forward both overloads to the wrapped binding; inheriting the defaults silently drops the caller-contract check. The check guards against developer error, such as a client built for the wrong contract; it is not an authorization boundary, because the caller supplies the contract name. A client proxy generated by an older annotation processor does not call these overloads and fails with `WorkflowClientProxyLinkageException` until it is rebuilt.

The Postgres implementation opens its own transaction for each call. Code that already owns a transaction should use `TransactionalWorkflowOperations<TX>`.

Instance state is modeled as a single current snapshot plus an append-only history: `query` returns the latest snapshot, and each transition that produced it is recorded as a separate history entry.

`start` returns once the start is recorded: the instance exists as `PENDING` with no state, current step or subject, and the executor of `vertique-workflow-engine` runs its first segment afterwards (initial state, subject, steps up to the first wait). Await the instance with the execution probe before reading it. `signal` returns once the signal is recorded as a command of its instance, not once it is applied: an executor applies it afterwards, in queue order behind the commands recorded before it, so it may be sent right after the start. Await the probe to observe its effect. A signal the instance does not await yet is parked and applied when the instance reaches a wait that accepts it. Timers and the other operations still run inline.

Starts require an idempotency key. `StartCommand` has nine components: `definitionId`, `contractName`, `payload`, `idempotencyKey`, `businessKey`, `subjectRef`, `requestedDefinitionVersion`, `startScope` and `requestedTargetScope`; there is no shorter constructor, so a direct caller passes `null` for the optional components. `contractName` names the contract the caller started through and must not be blank. `requestedDefinitionVersion` may pin a new instance to a specific registered version; when omitted, the current version is used. A non-null `startScope` or `requestedTargetScope` must be nonblank, at most 255 code points and free of control characters and unpaired surrogates, or the constructor throws `IllegalArgumentException` that names the field and never repeats the value. A `null` `startScope` is the default scope. Idempotency and dedup keys are scoped to what they protect — a start key is scoped to the start scope and the definition id and a signal dedup key is scoped to the workflow instance; see `dev.vertique:vertique-workflow-postgresql` for the complete key-scope table across all operations.

### Execution Probe

`WorkflowExecutionProbe` (package `dev.vertique.workflow.ops`) waits until recorded work has run; it is meant for tests and operator tooling only. Do not expose it to callers without authorization, because its reports name instances and carry stored failure text. `PendingCommand.lastError` is whatever the writer stored, so writers must store only a reason code plus a sanitized, bounded message:

```java
Future<IdleReport> awaitIdle(WorkflowInstanceId id, Duration timeout);
Future<IdleReport> awaitAllIdle(Duration timeout);

record IdleReport(List<PendingCommand> deferred, List<PendingCommand> stuck,
                  List<PendingCommand> parked, List<PendingCommand> notYetAvailable) {}
```

An instance is idle when it has no command in flight (claimed under a live lease) and no command an executor could claim now: one at the head of its queue, not held back and available. Availability alone is not the rule: a command behind a blocking head, a backed-off command (after a deferral or a transient failure), a future-dated command such as a timer before its fire time, and a due-date or reminder timer whose task is completing do not hold the probe. The probe polls the database at short intervals and reads the database clock, so it observes work recorded by any node. It completes with a report of the commands it stopped on, each list ordered by instance and sequence: `deferred` (deferred commands that cannot be claimed yet), `parked`, and `notYetAvailable` (the other pending and claimed commands that cannot be claimed now). `stuck` lists every dead-lettered command and every unsettled command (pending, deferred, claimed or parked) whose first availability is older than `workflow.executor.stuckAfterSeconds`; the first availability of a timer command is its fire time and of any other command the time it was recorded, never its current `availableAt`, which deferral and transient backoff move forward. A command may appear in `stuck` and in the list of its state; settled commands appear in no list. `PendingCommand` (package `dev.vertique.workflow.history`) carries the instance id, sequence, kind, state, `availableAt`, `attempts` and a nullable `lastError`; it never carries a payload.

The future fails with `TimeoutException` when the wait elapses; the message names the instance (or "all instances") and the number of runnable commands left. A non-positive timeout is refused with `IllegalArgumentException`, and a timeout longer than ten minutes is shortened to ten minutes. Each list of the report holds at most 1000 commands, the first in instance and sequence order. Obtain the probe from `WorkflowEngineHandle.executionProbe()` or from the Dagger graph; both come from `vertique-workflow-engine`. A start recorded while no executor runs keeps the probe waiting until an executor runs it.

### Instance History

`WorkflowInstanceHistoryService` (package `dev.vertique.workflow.history`) reads one instance's log and its open commands; it is bound in the engine graph by the dialect module.

```java
Future<List<WorkflowHistoryEntry>> entries(WorkflowInstanceId id, long afterSequence, int limit);
Future<List<PendingCommand>> openCommands(WorkflowInstanceId id);
```

- **Paging.** `entries` returns entries in ascending sequence order after `afterSequence` (exclusive; `0` for the first page). `limit` must be 1 to 500; anything else throws `IllegalArgumentException` before anything is read. Page by passing the last sequence read.
- **Open commands.** `openCommands` lists the commands that are `PENDING`, `DEFERRED`, `CLAIMED`, `PARKED` or `DEAD`, in sequence order, as `PendingCommand`. `lastError` is the reason code plus the sanitized message stored when the command failed (never raw exception text); the service returns it as stored.
- **The entry.** `WorkflowHistoryEntry` keeps its five original components and gains trailing nullable ones: `entryVersion` (boxed `Integer`; the stored format version: 2 for `DECISION` and `STATE_BASELINE` entries, 1 for every other entry), `actor` (`HistoryActor`), `onBehalfOf`, `attributionStatus` (`VERIFIED`, `ABSENT` or `DEGRADED`), `executorId`, `commandId`, `stepId`, `outcome`, `definitionKey`, `planHash`, `stateVersionBefore`, `stateVersionAfter` and `patchJson` (a JSON array; `[]` for no change). The five-argument constructor stays and reports `entryVersion` 1 with every other new component null. An entry that was never attributed has a null `actor`; work that ran with no identity is recorded as `HistoryActor.Kind.NONE`, a kind that exists only in the history and never on `WorkflowActor`.
- **Command and definition on the entry.** An entry records the command it names in `commandId`: its own column, or the `commandId` of
  its payload where the writer put it there. An entry that names no command keeps a null `commandId`; the engine does not give it the
  command of the segment it was written in, because it may belong to another command, such as a parked signal applied during the
  segment. An entry that the executor writes in a command's segment and that records no definition takes the definition the instance
  was pinned to when the segment began, except in a `MIGRATION` segment, whose pin changes part-way: there every entry keeps the
  definition its writer recorded, which is null for most and the target for the entry that logs the migration. An entry that already
  records a value keeps it. An entry written outside a segment can also have a null `commandId` or `definitionKey`.
- **`query()` stays narrow.** The history in the `WorkflowView` that `WorkflowOperations.query` returns carries the five original components and the stored `entryVersion`; every other new component is null there. Only this service returns the enriched entry.
- **Reading is not authorized here.** Entries name actors and carry state patches, and open commands carry stored failure text. The framework performs no authorization on these reads; the application authorizes them before exposing them.
- **No lock.** The service reads on a pooled connection outside any caller transaction and takes no execution lock.

### Scope Keys on Instances, Tasks And Filters

`WorkflowScopes.DEFAULT` (`"default"`) is the reserved default scope. It is a value, not a stored record: it always exists, needs no registration, and every row written without a scope belongs to it.

`WorkflowInstance` ends with two non-null components, `String scopeKey` (the start scope) and `String definitionScopeKey` (the scope of the definition version the instance is pinned to). `TaskRecord` ends with a non-null `String scopeKey` followed by a nullable `Boolean requireVersionStability` and a nullable `Long reservationCommandId`, and `WorkflowInstanceQuery` and `TaskFilter` each end with a nullable `String scopeKey` filter component (after `includeArchived`). The previous constructors of all four records are retained: those of `WorkflowInstance` and `TaskRecord` delegate with `WorkflowScopes.DEFAULT` (and, for `TaskRecord`, a `null` stability flag and reservation), those of `WorkflowInstanceQuery` and `TaskFilter` with `null`, so existing callers keep compiling. `WorkflowInstanceQuery.withScopeKey(...)` and `TaskFilter.withScopeKey(...)` set the filter, and every other `with*` method preserves it. The instance has no scope setter and every instance updater passes both scopes through. Record patterns that deconstruct these four records by component list stop compiling and must add the components.

The PostgreSQL store persists the values in `scope_key` and `definition_scope_key` columns that default to `default`, added by `V002__workflow_definitions_and_scopes.sql`. Existing rows read the default scope. The filters are exact-match; a task's scope is the start scope of its owning instance row, taken by the PostgreSQL store whatever the `TaskRecord` carries on insert. The engine stamps the start scope of the `StartCommand` on a new instance (the default scope when the command names none) and the scope of the definition version it resolved as the definition scope. A start scope written by another writer stays with the instance for life: tasks created afterwards carry it, and migration and resurrection keep it. A migration to another scope's definition changes only the definition scope.

The values and the filters are information and a filter, not an isolation or authorization boundary: a `null` filter matches every scope, so a caller must not pass an unresolved scope as `null` expecting scoping. Business keys and start idempotency keys are unique per start scope and definition id, whatever the contract of the definition; a start whose business key is already held fails with `WorkflowBusinessKeyConflictException`, a `ConflictException` that names the scope and definition id and not the key. No operation addressed by instance or task id (query, signal, cancel, retry, migrate, task complete or reassign) checks the scope, and timers, branch tokens and definition resolution are global: load the instance and compare its `scopeKey()` with the caller's scope before acting on a caller-supplied id.

### Durable Context on the Instance

`WorkflowInstance` carries a `@Nullable DurableMetadata metadata` component — the durable
context (correlation, tenant, and other registered namespaces) captured once at start time, using
the framework's standard `{"context": …}` carrier shape. It is `null` when no ambient durable context was
present at start (empty capture) or for instances started before this field existed.

The field is immutable for the instance's lifetime: no `withMetadata(...)` updater exists, and
every other `with*` updater (`withVersion`, `withStatus`, `withCurrentStepId`, `withWait`,
`withState`, `withError`, `withUpdatedAt`) passes it through unchanged (as it does the scope keys). No transition, migration,
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
@WorkflowContract
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

A typed client is always bound to one workflow definition id; no `WorkflowClientFactory` method takes a contract alone. Create it from the class of a registered definition, or from a contract and a definition id:

```java
OrderFulfillmentWorkflow orders = factory.create(OrderFulfillmentDefinition.class);
OrderFulfillmentWorkflow byId = factory.create(OrderFulfillmentWorkflow.class, "order-fulfillment");
```

- **From a definition class.** `create(DefinitionClass)` looks the class up in the registry. A class that is not registered, or that is registered under more than one definition id, fails with `WorkflowProxyContractException` naming the class. The client's contract is the definition's contract.
- **From a contract and an id.** `create(Contract.class, definitionId)` needs a nonblank id of at most 255 code points (`IllegalArgumentException` otherwise) and does not require the id to be registered yet, because definitions may be loaded later. The contract name is `@WorkflowContract.value()` or, when empty, the interface's binary name.
- **Version and scope.** A client starts the current version of its definition. `client(DefinitionClass)` and `client(Contract.class, definitionId)` return a `WorkflowClientBuilder` with `version(long)` (positive, `IllegalArgumentException` otherwise), `version(targetScope, long)`, `scope(startScope)` and `create()`. The selections are recorded in the client's `WorkflowClientBinding`; the engine acts on them at start. `scope(startScope)` runs the start in that scope. `version(n)` resolves version `n` from the start scope when the start scope holds a registered (available or deprecated) version of the definition id that implements the client's contract, and from the default scope otherwise; a default-scope version of another contract than the client's fails with `WorkflowDefinitionContractMismatchException`. `version(targetScope, n)` resolves exactly that version of `targetScope`, which must be the start scope or `default`; any other scope fails with `WorkflowDefinitionMissingException` naming the definition id and the scope. A start without a version runs the start scope's current version of the definition id when the start scope holds versions of it under the client's contract, and the default scope's current version otherwise; a client never starts a definition of another contract, and fails with `WorkflowNoCurrentVersionException` when the version that would apply implements another contract. Only registered, non-deprecated versions start; a loaded document that is not published is never started.
- **Replay before resolution.** The engine looks for the start of the same start scope, definition id and idempotency key before it resolves any version, so a replay returns the original instance even after the current version moved or the pinned version was deprecated. A replay that names a version, or a target scope, other than the pin of the existing instance fails with `WorkflowIdempotencyConflictException` of kind `start`; a replay without a version returns the existing instance. A replay through a client of another contract than the contract of the existing instance's definition also fails with `WorkflowIdempotencyConflictException`; business keys and idempotency keys are shared by the clients of every contract of one start scope and definition id. When the node holds no plan for the pinned version and has a durable registry, the contract is the one the registry stores for the pinned definition, and a pin with no stored row refuses the replay with `WorkflowVersionNotRunnableException`; only an engine without a durable registry takes it from another registered version of the same id in that scope (every version of an id in a scope implements one contract), and it refuses the same way when there is none. A first start whose contract name differs from the contract of the resolved definition fails with `WorkflowDefinitionContractMismatchException` before anything is written.
- **What creation checks.** Creation validates the contract interface's methods only: the annotation, the interface kind, one role annotation per method, one start method, unique signal names and the method shapes. It does not resolve a plan or compare payload types; a payload that does not fit the definition is reported when the client starts or signals.
- **Generated and reflective clients.** Both build the start command through `WorkflowClientBinding.startCommand(...)`, so they send the same command. A generated proxy takes `(WorkflowOperations, WorkflowClientBinding)` and embeds no definition id or version.

- **Many definitions per contract.** Any number of definition ids and versions may name one contract. The registry reads a definition's id, version, state type and contract from the definition, and keeps no map from a contract to one definition. The registry no longer offers a lookup from a contract class to its definition; look a definition up with `resolve(key)`, `find(key)`, `load(key)`, `codeDefinitions()` or `definitionClass(definitionClass)`.
- **One contract per definition id.** Every code version of a definition id must name the same contract; another scope may hold the same id under another contract. A scope other than the default one cannot load an id that the default scope holds, in any status, under another contract, unless the scope already holds that id; the load fails with `WorkflowDefinitionContractMismatchException`. A default-scope version recorded later than a scope's own versions of the same id under another contract is accepted, and the scope keeps its own versions for clients of their contract. Registering a code version whose contract name differs from that of an earlier version of the same id fails with `WorkflowDefinitionException` naming `'<id>' v<version>`, the scope and both contract names; nothing is registered.
- **Declared signals.** A definition that waits for a signal must declare it on its contract, as a `@WorkflowSignal` method of the contract interface or of an interface it extends. Registration fails with `WorkflowDefinitionException` naming the definition and the undeclared signals in sorted order; waits inside fork branches and waits guarded by a timeout count. Marker contracts are not exempt: an empty contract can only back a definition that waits for no signal. A contract may declare signals that some of its versions do not wait for, and it may grow: add the `@WorkflowSignal` method before registering a version that waits for the new signal.
- **Signal sent to an older version.** A signal for an instance whose pinned definition row does not declare it for the addressed wait fails when it is recorded with `WorkflowSignalRejectedException` naming the instance, the signal and `'<id>' v<version>`, and changes nothing; a row without stored names records it and the executor rejects it. A client of another contract than the instance's pinned contract is refused with `WorkflowDefinitionContractMismatchException`.

`@WorkflowContract` has the single attribute `value()` and names no definition: a contract that sets `definitionId` or `definitionVersion` no longer compiles, because definitions carry their own id and version. `WorkflowClientFactory.create(Contract.class)` no longer exists, and the annotation processor no longer generates Dagger providers of typed clients: obtain a client from the factory, for example in your own `@Provides` method.

`@WorkflowContract.value()` gives the contract a logical name (see Logical Type Names); the name does not change the plan hash. Contract annotations are useful for type-safe application code. Document-defined workflows still use a contract class today; see `dev.vertique:vertique-workflow-definition` for the typed document model and its current limits.

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
| External workflow event | `vertique-workflow-events` |

Missing required recorders fail the workflow transition rather than silently dropping work.

`WorkflowSideEffectIntent.Correlation` carries `String definitionScopeKey` and `String scopeKey` directly after `definitionId`, and `Correlation.singlePath(...)` takes them in the same position: the scope of the definition the instance is pinned to and the scope the instance was started in. `WorkflowEventIntent` carries the same two components directly after `definitionVersion`, and neither may be `null`. There is no overload without them, so an emitter must name both scopes; recorders stamp them on event envelopes and on outbox headers. Callers that construct these records by component list must add the two values.

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

Add `WorkflowTasksModule`, `WorkflowEventsModule`, or `WorkflowDefinitionModule` only when the application uses those capabilities.

Add the type catalog module that `vertique-codegen-workflow` generates (`GeneratedWorkflowTypeCatalogModule`, one for each module that declares `@WorkflowContract` or `@WorkflowType` classes) when documents are loaded at runtime or packaged documents name annotated types by logical name.

---

## Public Extension Points

| Extension point | Purpose |
|---|---|
| `WorkflowContributor` | Register workflow definitions with the registry |
| `WorkflowSideEffectRecorder<TX>` | Persist workflow side-effect intents |
| `TransactionalWorkflowOperations<TX>` | Drive workflows inside an existing transaction, including delivery by key |
| `TaskStore<TX>` | Store human task rows |
| `TransactionalTaskCallbacks<TX>` | Engine callback surface used by the task service |
| `TimerStore<TX>` | Timer persistence; the timer row links to the command that fires it |
| `WorkflowMigrationHandler` | Move an in-flight instance to a newer definition version, or to the same version in another scope |
| `WorkflowStateCompatibility` | Decide whether one state class extends another; bound by `vertique-workflow-schema` |
| `WorkflowHistoryRedactor` | Name the state and payload values that an instance's history must never hold |
| `WorkflowHistoryListener` | Be told, after the commit, what an instance's history gained and which caller action failed |
| `WorkflowDefinitionLifecycleListener` | Be told, after the commit, that a definition version was published, deprecated, retired, reinstated or activated, and which such change was refused |

Application code should normally depend on the high-level services first and implement these SPIs only when replacing a storage or delivery backend.

### Internal Registration Seam

`WorkflowDefinitionValidator` and `RegisteredWorkflowDefinition` (the read-only plan plus start
payload type handed to a validator) are an internal framework seam. They are not an application
contract and sit outside this module's maturity promise; sibling framework modules use them to check
a definition before it is published. Applications should not implement them.

- **Internal wiring.** The `DefaultWorkflowRegistry` constructors that take a
  `Set<WorkflowDefinitionValidator>` or a `Provider<Set<WorkflowDefinitionValidator>>` and the
  `Set<WorkflowDefinitionValidator>` multibinding declared by `WorkflowCoreModule` are internal for the
  same reason. The existing constructors delegate with an empty set. `WorkflowCoreModule` passes the
  provider, so the registry resolves the validators on each plan build and not while it is constructed;
  a validator may therefore depend on recorders and handlers that themselves depend on the registry.
- **Contributors.** The reminder check of `vertique-workflow-engine` and the service-dispatch checks
  of `vertique-workflow-services` are contributed validators; they run on every plan the registry builds.
- **Unordered.** The validator set has no defined order; a validator must not depend on another one
  having run.
- **Synchronous.** Validators run on the thread that calls `register` or `publish`, so call
  `publish` off the event loop.

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

### WorkflowHistoryRedactor

SPI (`dev.vertique.workflow.history`) that names the values the instance history must never hold. The engine logs every command's
payload, a patch of every state change and the full state of an instance at a few points (see the engine module); a bound redactor
replaces the values it names with the string `"[redacted]"` in all of those forms before they are written.

```java
@FunctionalInterface
public interface WorkflowHistoryRedactor {
    Set<String> redactedPointers(DefinitionKey key);
}
```

- **Binding.** Provide it with a Dagger binding (`@Provides WorkflowHistoryRedactor`; the engine module declares the binding optional), or pass it to
  `WorkflowEngineParts.Builder.historyRedactor(Optional.of(redactor))` for a factory-built engine. Without one nothing is redacted.
- **Pointers.** Each pointer is an RFC 6901 JSON Pointer such as `/card/number`; a key that holds `/` or `~` is escaped `~1` or `~0`.
  A pointer resolves into an array by index (`/cards/1/number`), but an index names a position, not a record: it names another record
  after an insert or removal, so never redact an array of sensitive records by index. A segment that is exactly `*` matches every
  element of an array and every member of an object at that position (`/cards/*/number`); redacting the array root (`/cards`) also
  covers every record. A key that is literally `*` cannot be named on its own. The root pointer, a string that does not start with `/`
  and an escape other than `~0` or `~1` are invalid.
- **One set per definition version.** The redactor returns one set for the definition version (scope, id, version) the instance is
  pinned to. The same set is applied to the instance state and to every logged command payload. A pointer that does not resolve in a
  document, or resolves to a JSON `null`, is ignored for that document; a pointer that names a subtree redacts the subtree.
- **Where it applies.** The logged copy of a command's payload (the `payload` of its `COMMAND_RECEIVED` and `SIGNAL_RECEIVED` entries),
  the patch of each decision, every full-state entry and the reasons written to history. The instance's own state, the command rows and the
  events keep their values, because the engine needs them to run; `workflow_commands.payload_json` is not redacted.
- **Not retroactive.** Rows written before a redactor was bound, or before a pointer was added, keep the values they hold. The payload
  copy is matched on the JSON the client sent, so a value under an alias or a differently cased key is not matched by a pointer written
  for the definition's own field names.
- **Across a migration.** The pointers of the source version cover the migration command and every entry before it; the pointers of the
  target version cover the migrated full state and every later entry; both cover the migration's own patch.
- **Failure.** The engine asks at most once per definition version in a segment. A redactor that throws, or a pointer that is invalid,
  fails the segment as a transient failure: nothing is logged unredacted, the command is retried with backoff, and the instance shows as
  stuck until the redactor is fixed.
- **Not recoverable.** A redacted value cannot be recovered from the log. Replaying an instance's patches from its latest full-state
  entry reproduces its stored state except at the redacted pointers.
- **Threading.** The method runs on an event loop; it must not block.

### WorkflowHistoryListener

SPI (`dev.vertique.workflow.history`) that observes what the engine records in an instance's history, after it is committed. Use it
to feed a metric, a notification or a search index without reading the history table.

```java
public interface WorkflowHistoryListener {
    void onHistoryChanged(WorkflowHistoryChange change);
    default void onHistoryFailed(WorkflowHistoryFailure failure) {}
}
```

Contribute a listener with a Dagger multibinding. `WorkflowCoreModule` declares the set empty, so an application with no listener
needs no wiring:

```java
@Provides
@IntoSet
static WorkflowHistoryListener historyNotifier(Notifier notifier) {
    return new WorkflowHistoryListener() {
        @Override
        public void onHistoryChanged(WorkflowHistoryChange change) {
            for (WorkflowHistoryEvent event : change.events()) {
                WorkflowHistoryEvent.EntryInfo info = event.entryInfo();
                switch (event) {
                    case WorkflowHistoryEvent.TaskDecided decided ->
                        notifier.taskDecided(info.instanceId(), decided.taskId(), decided.decisionName(), info.actor());
                    case WorkflowHistoryEvent.InstanceCompleted completed ->
                        notifier.completed(info.instanceId(), info.recordedAt());
                    default -> { }   // variants added later, and events this listener does not use
                }
            }
        }

        @Override
        public void onHistoryFailed(WorkflowHistoryFailure failure) {
            notifier.refused(failure.action(), failure.instanceId(), failure.errorType());
        }
    };
}
```

- **Delivery.** Every contributed listener receives every event, one listener after another, in no defined order. The callback runs
  after the transaction committed, on the thread that completes the read of the committed entries (a Vert.x thread), so it must not
  block and must not assume it runs before or after the caller's own result completes. A failed action is reported on the thread
  where the failure was observed. Do slow work elsewhere, for example by handing the event to a queue.
- **At most once.** If the process stops between the commit and the notification, the event is not delivered; the instance history
  stays the source of truth. A history read that fails after the commit is logged and the change is not delivered. A listener runs
  after the transaction, so it cannot write an outbox row in that transaction. Where nothing may be lost, keep the last `sequence`
  you handled per instance and catch up from `WorkflowInstanceHistoryService`, or publish workflow events to external consumers
  with `vertique-workflow-events`, which records them through the transactional outbox.
- **Isolated.** An `Exception` a listener throws is logged at warn with its stack and swallowed: it reaches neither the engine, the
  caller nor the other listeners. An `Error` (`LinkageError`, `AssertionError`, `StackOverflowError` and the like) is a programming or
  VM failure and is not caught: it ends the delivery to the listeners after it, and what becomes of it is left to the thread's
  handler. A failure of the actor capture, of the read of the committed entries, of the mapping or of a sequence read is logged
  with the exception class only, never its message.
- **Order.** Changes of one instance are read on independent connections, so two successive changes can be delivered out of order.
  Every event carries its `sequence`; a consumer that needs the order sorts by it.
- **Why events arrive in a batch.** One engine action writes several history entries in one transaction (a signal, the step it
  completes and the step it starts, for example), and the commit is the unit that became visible. `WorkflowHistoryChange` hands you
  that unit together: one callback per commit and instance instead of one per entry, the entries read once after the commit, and
  related events in one place for a notification or a metric. Iterate `events()` when you want to act on single events.
- **One transaction, one settlement, merged ranges.** A transaction registers one settlement callback however many engine calls it
  makes. The entries each call wrote for an instance form a range of sequences, and the ranges of one instance are merged when the
  transaction commits: two adjacent actions on the same instance in one transaction give **one** `WorkflowHistoryChange`, and
  sequences that a rollback to a savepoint let a later action reuse are announced once. `events()` holds the entries of the merged
  range for `instanceId()`, ascending by sequence, and is never empty. A range that is larger than the read page (1000 entries)
  arrives as several consecutive changes, still in sequence order. A transaction that touches several instances produces changes for
  each. A start that only replays an existing instance, and a call that wrote nothing, produce none.
- **Nothing sensitive.** An event carries no payload, state, patch or failure message. Task events read only the task id, the
  decision name and the assignments, each cut at 255 characters, and a decision node's choice reads only the chosen step id, cut at 40 characters.
- **Each event knows its own provenance.** `WorkflowHistoryEvent.EntryInfo` holds the instance, sequence, definition pin, step, the
  command id and `CommandKind` (`START`, `SIGNAL`, `COMPLETION`, `RETRY`, `MIGRATION` or `TIMER`), the `HistoryActor`, `onBehalfOf`, a
  `HistoryAttribution` and the recording time. One change can mix actors and definition pins: a parked signal applied by another
  command's segment carries the actor that recorded the signal, and a migration logs the target `DefinitionKey`.
- **Provenance is never guessed.** `commandId` is set only from the entry's own command column or the `commandId` in its payload;
  an entry that names no command does not take the command of the segment it was written in. The `definition` pin is filled from the
  instance's head only for an entry written in a command's segment that is not a migration. So `commandId`, `commandKind`,
  `definition` and `stepId` are `null` whenever the writer did not record them: for example an entry written outside a command, or
  inside a `MIGRATION` segment, whose pin changes part-way, except the entry that logs the migration itself. `commandKind` is also
  `null` when the command row's kind is not one this node knows. `actor` is `HistoryActor.Kind.NONE` and `attribution` is `ABSENT`
  for an entry that was never attributed.
- **Attribution.** `VERIFIED` is a verified caller identity, `ABSENT` means there was none (the actor is `HistoryActor.Kind.NONE`),
  and `DEGRADED` means the identity was rebuilt from a snapshot that could not be verified and the work continued. Treat `DEGRADED` as
  unverified for authorization or compliance use.
- **Failures.** `onHistoryFailed` reports a caller action that failed, whose transaction did not commit or whose commit was not
  confirmed. It is reported once per attempt, with an `attemptId` minted for that attempt (a repeat of the action is a new attempt), the
  `Action` (`START`, `SIGNAL`, `CANCEL`, `RETRY`, `MIGRATE`, `TASK_COMPLETE`, `TASK_REASSIGN` or `FAILURE_DISPOSITION`, the terminal
  failure of a recorded command), the actor, and only the simple class name of the failure in `errorType`. `instanceId` is `null`
  when the action never resolved an instance. The entries the attempt wrote were not committed, and their sequences can be reused.
  When the caller owns the transaction and rolls it back after the engine call succeeded, the rollback is reported as a failed
  action. A lost connection during the commit can leave the outcome unknown. A call nested in another call for the same instance in
  one transaction only runs its work, so it has no report of its own. A failed outer action is reported once even if the caller
  recovers (for example with a savepoint) and then commits the rest of the transaction.
- **Executor work is announced too.** The history written by the executor (timers, signals, completions, retries, migrations, task
  due dates and the dispositions of a command that was deferred, dead-lettered or failed) is delivered like the history of a direct
  call, with the actor of the command it ran.

The set of event variants grows additively, so end every `switch` with a `default` branch, and tolerate a `Action`,
`TimerChange`, `BranchChange`, `CommandDisposition` or `DecisionKind` constant you do not know. Each stored entry type maps to
one variant:

| Event | Entry types |
|---|---|
| `InstanceStarted`, `InstanceRetried`, `InstanceCancelled`, `InstanceCompleted`, `InstanceFailed`, `InstanceMigrated` | `START`, `RETRIED`, `CANCELLED`, `COMPLETED`, `FAILED`, `WORKFLOW_MIGRATED` |
| `SignalReceived`, `SignalParked`, `SignalApplied` | `SIGNAL_RECEIVED`, `SIGNAL_PARKED`, `SIGNAL_APPLIED` |
| `TaskCreated`, `TaskDecided`, `TaskReassigned`, `TaskCancelled` | `TASK_CREATED`, `TASK_COMPLETED`, `TASK_REASSIGNED`, `TASK_CANCELLED`; each carries the task id, and `TaskCreated` adds `assignment`, `TaskDecided` adds `decisionName`, `TaskReassigned` adds `from` and `to` |
| `TaskExpired`, `TaskReminderFired` | `TASK_EXPIRED`, `TASK_REMINDER_FIRED` |
| `TimerChanged` (`SCHEDULED`, `FIRED`, `TIMED_OUT`, `CANCELLED`, `FAILED`) | `TIMER_SCHEDULED`, `TIMER_FIRED`, `TIMEOUT`, `TIMER_CANCELLED`, `TIMER_FAILED` |
| `CompensationChanged` (`STARTED`, `STEP_RECORDED`, `COMPENSATED`) | `COMPENSATING_START`, `COMPENSATING_STEP`, `COMPENSATED` |
| `SideEffectRecorded` | `SIDE_EFFECT_RECORDED` |
| `ForkDispatched`, `FanInChanged` (`EVALUATED`, `COMPLETED`, `FAILED`) | `FORK_DISPATCHED`, `FAN_IN_EVALUATED`, `FAN_IN_COMPLETED`, `FAN_IN_FAILED` |
| `BranchChanged` | every `BRANCH_*` entry type, as the matching `BranchChange` |
| `CommandChanged` | every `COMMAND_*` entry type, as the matching `CommandDisposition` (`RECORDED`, `RECEIVED`, `COMPLETED`, `REJECTED`, `STALE`, `DEFERRED`, `DEAD_LETTERED`, `CANCELLED`, `SUPERSEDED`) |
| `StepDecided` | `DECISION`, as a `DecisionKind`: an engine word (`INTENT`, `WAIT_SIGNAL`, `JOINED`, `COMPLETED` and so on), or `BRANCH` with `chosenStepId` for the step a decision node chose |
| `Other` | an entry type that has no variant of its own yet, with its `WorkflowEntryType`; also an entry whose task payload cannot be read |

`STATE_BASELINE` entries hold a state snapshot and produce no event. An entry whose type this node does not know, written by a
newer node during a rolling deploy, is skipped silently: the read filters the known types in SQL and nothing is logged. A
`DECISION` outcome that is not an engine word reads as `BRANCH` with that text, cut at 40 characters, as the chosen step id.

- **Zero cost without a listener.** With no listener bound the engine runs no extra statement and captures no actor.
- **A listener makes a call take the instance lock first.** With a listener bound, an action on an existing instance takes the
  instance's execution lock before it reads the sequence the commit will be measured from, so a duplicate signal that would have
  returned without writing waits for the lock like any other change. See `vertique-workflow-engine`.
- **Two reads run in your transaction.** For an action on an existing instance the engine reads the instance's highest history
  sequence before and after the work (a start reads it once, after). If one of those statements fails, a PostgreSQL transaction is
  poisoned like by any failed statement: the action's next statement fails or the commit becomes a rollback, even though the
  engine recovered from the read itself. This can happen only while a listener is bound.
- **Needs a transaction it can watch.** The `SqlClient` a `TransactionalWorkflowOperations` call runs on must be a connection with an
  open transaction. Otherwise the commit cannot be observed, the call itself succeeds, and the events are not announced (an error is
  logged).
- **A factory-built engine** announces nothing unless `WorkflowEngineParts.Builder.historyPublisher` is set. A Dagger graph binds
  the publisher itself.

### WorkflowDefinitionLifecycleListener

SPI (`dev.vertique.workflow.registry.store`) that observes changes to the definition registry after they are durable: publishing,
deprecating, retiring and reinstating a version, and activating, deactivating or following a default.

```java
public interface WorkflowDefinitionLifecycleListener {
    void onDefinitionLifecycleChange(DefinitionLifecycleChange change);
    default void onDefinitionLifecycleFailed(DefinitionLifecycleFailure failure) {}
}
```

Contribute it with `@Provides @IntoSet`; `WorkflowCoreModule` declares the set empty.

```java
@Provides
@IntoSet
static WorkflowDefinitionLifecycleListener registryNotifier(Notifier notifier) {
    return new WorkflowDefinitionLifecycleListener() {
        @Override
        public void onDefinitionLifecycleChange(DefinitionLifecycleChange change) {
            switch (change) {
                case DefinitionLifecycleChange.VersionChange v -> notifier.version(v.operation(), v.key(), v.actor());
                case DefinitionLifecycleChange.ActivationChange a ->
                    notifier.activation(a.operation(), a.scopeKey(), a.definitionId(), a.version(), a.actor());
                default -> { }
            }
        }
    };
}
```

- **`VersionChange`** carries a `VersionOperation` (`PUBLISH`, `DEPRECATE`, `RETIRE`, `REINSTATE`) and the `DefinitionKey`.
  **`ActivationChange`** carries an `ActivationOperation` (`ACTIVATE`, `DEACTIVATE_DEFAULT`, `FOLLOW`), the scope key whose default
  changed, the definition id, the target scope key and the version (`null` when none). The target scope key is the scope that owns
  the version for `ACTIVATE`, always `WorkflowScopes.DEFAULT` for `FOLLOW`, and `null` for `DEACTIVATE_DEFAULT`; the version is set
  only for `ACTIVATE`. Both carry the `changeId`, the actor, `onBehalfOf`, the `HistoryAttribution` and `occurredAt`. The variants
  grow additively, so keep a `default` branch.
- **Actor and time.** The actor is the caller's identity, captured on the calling thread before the operation is queued, and
  `occurredAt` is stamped at the same moment, when the call was made, not when the change committed.
- **Failures.** `onDefinitionLifecycleFailed` receives a `DefinitionLifecycleFailure` for a change the lifecycle refused or failed to
  commit: the attempted change, the typed `WorkflowDefinitionLifecycleException.Reason` for a refusal (`null` for any other failure)
  and the simple class name of the failure, never its message. The failure carries the same `changeId` as the change of that call.
- **Repeats are reported.** A repeat that changes nothing, such as publishing an available version, is accepted and reported like any
  other change, because the registry result cannot tell the two apart.
- **Startup.** The versions startup records for the first time are reported as a `PUBLISH` by `HistoryActor.Kind.SYSTEM` with the
  reason `bootstrap` and `VERIFIED` attribution, and a version that also became the default scope's current version is followed by
  an `ACTIVATE` in the default scope (scope key and target scope key both `WorkflowScopes.DEFAULT`). A restart that finds nothing new
  reports nothing, and startup reports no failures.
- **Only in a Dagger graph.** The events are built by the engine module's Dagger-injected lifecycle and startup step. A
  factory-built engine (`WorkflowEngineFactory`) emits none.
- **Delivery.** The same as for history listeners: after the durable commit, at most once, one listener after another on the thread
  that completes the operation, isolated (an `Exception` is logged at warn with its stack; an `Error` is not caught), and carrying no
  definition source or payload.

---

## Durable Definition Registry

`dev.vertique.workflow.registry.store` supplies SQL-free metadata and source values, the
`WorkflowDefinitionRegistryStore` persistence SPI, and `WorkflowDefinitionLifecycle.publish(DefinitionKey)`.
A `DefinitionKey` carries a required scope key (`WorkflowScopes.DEFAULT` for the default scope), definition id, and
positive version; ids and scope keys are nonblank, at most 255 code points and free of control characters, and
provenance text is bounded (source type 64, URI 2048, revision, author and upload id 255 code points). A
`DefinitionFilter(scopeKey, definitionId, statuses)` selects by exact scope, id and statuses, and a `null` component
selects every value; it does not authorize scope access.
`WorkflowDefinitionRecord` contains immutable metadata and provenance, including the logical contract name and state
type name of the definition; a CODE record belongs to the default scope. Source text is fetched separately
as `WorkflowDefinitionDocument`. Source URIs are opaque attribution and are never fetched on restore.

The store provides candidate insertion, metadata lookup/list, guarded source lookup, static document
reconciliation, and fenced claim/complete/release operations. A definition row has one of five statuses:
`CANDIDATE` (loaded, not published), `PUBLISHING` (a node holds the fenced claim), `AVAILABLE` (published: it can be
started, pinned and resolved), `DEPRECATED` (new starts and migrations are refused while pinned instances keep running) and
`RETIRED` (retained metadata without an executable). Any number of versions and scopes of one definition id are
`AVAILABLE` at once; no store or lifecycle operation chooses one of them, and `complete(claim)` changes only the claimed row.
Which version starts by default is decided separately from the status. The transitions are `CANDIDATE` to `PUBLISHING` (claim) to
`AVAILABLE` (complete), or back to `CANDIDATE` (release, or the reset of a stale claim); `CANDIDATE` to `RETIRED`;
`AVAILABLE` to `DEPRECATED`; `DEPRECATED` to `RETIRED` as soon as no live instance is pinned to it (there is no cooling
window); and
`DEPRECATED` or `RETIRED` back to `AVAILABLE` (reinstate). Every other pair is refused with `INVALID_TRANSITION`, and a
repeat of a completed transition (publishing an available version, deprecating a deprecated one, retiring a retired one) is
accepted without a change.
A deprecated version is a status of its row: the engine refuses a new start or a migration into it from the status it reads in the transaction that needs it, pinned resolution still serves it, and an activation record, not the registry, selects the current version. The store SPI adds the
conditional `transition(key, from, to)` operation and the lifecycle adds `deprecate(DefinitionKey)`.
The store adds `retire(DefinitionKey)` and the lifecycle adds `retire(DefinitionKey)` (CANDIDATE, or DEPRECATED with no live
pinned instance); `WorkflowInstanceQueryService.countNonTerminal(DefinitionKey)` (and the default-scope
`countNonTerminal(definitionId, version)`) is an
optional informational count whose default fails with `UnsupportedOperationException`. `TransactionalWorkflowOperations`
implementations backed by the Vert.x SQL engine receive a `SqlConnection` with a pending Vert.x transaction. A lifecycle
change is a change of the row alone: the node keeps no copy of the row to update, and the code the registry holds stays
registered whatever the status of its row is.
The lifecycle and the definition service also add `reinstate(DefinitionKey)`; the store performs it as `transition(key, RETIRED,
AVAILABLE)` or `transition(key, DEPRECATED, AVAILABLE)`.
The store also adds `upsertCode` (startup record of deployed code as `AVAILABLE`: rehash only while no live instance is pinned to
that exact version, decided inside the store transaction) and `upsertDocument`; both take an `activateDefault` flag (the forms
without it pass `false`) that writes the default scope's activation record in the same transaction as the first insert (see
Activation records), and `resetStalePublishing` (claims older than
`publicationClaimTimeoutSeconds` return to CANDIDATE).
`ScopeKeyRules` (`dev.vertique.workflow.types`) is an internal validator shared by the workflow modules so every entry point applies one scope key rule; it is not an application contract.
Rows of different scopes and versions of one id coexist and do not conflict: a claim, completion or static reconciliation
looks only at its own row.

**Failing rows.** `WorkflowDefinitionRecord.failure()` is a `DefinitionFailure(rule, detail)`, or `null` for a row that is not failing. Only a document definition of a scope other than the default one is ever marked: when newly deployed code makes its state class no longer extend the default scope's (`STATE_TYPE_NOT_EXTENDED`) or a type name in its recorded name binding no longer resolves (`NAME_BINDING_UNRESOLVED`), the engine marks the row instead of failing it, and clears the mark when the conflict is gone. `recordFailure(key, failure)` on the store writes or clears the mark of an existing row under the definition id lock (`null` clears it) and changes nothing else. Only the rule is stored, so a record read back carries the rule's fixed description from `DefinitionFailure.of(rule)` as its `detail`; the detail holds keys and rule-level text and never document content, state values or class names. The record's constructor takes `failure` as its last component, so code that constructs a record or implements the store must be updated.

**Activation records.** The current version of an id in a scope is a `DefinitionActivation(scopeKey, definitionId,
targetScopeKey, version, activatedAt)`. A scope with no record follows the default scope; a record names a version of the
scope itself or of the default scope, and the default scope's record names only the default scope. The constructor rejects
any other combination with `IllegalArgumentException`, so an invalid argument fails before storage is read. The store adds
`putActivation(DefinitionActivation)`, `removeActivation(scopeKey, definitionId)` and `listActivations(scopeKey)` (a `null`
scope lists every scope, ordered by id and scope). A write runs under the definition id lock and requires the target row to
exist and be `AVAILABLE`, otherwise it fails with `INVALID_TRANSITION` naming the target key; activating changes no
definition row. The lifecycle and the definition service add `activate(scopeKey, definitionId, targetScopeKey, version)`,
which replaces the scope's record, `deactivateDefault(definitionId)`, which clears the default scope's record (an absent
record is accepted, no definition row changes, other scopes' records stay), `follow(scopeKey, definitionId)`, which removes a
non-default scope's record (`follow` on the default scope fails with `IllegalArgumentException`), and
`activations(scopeKey)`. The records are durable: every operation goes through the registry store, and the engine
requires one.

**Auto-activation.** `WorkflowDefinition.activateOnDeploy()` (default `true`) decides whether a version becomes the default
scope's current version when it is first published at deployment. With a durable store, a code version or packaged document
version that startup inserts for the first time is activated in the default scope (the store reports `INSERTED_AND_ACTIVATED`; an insert that leaves the record as it was is `INSERTED`) when it does
not opt out and is higher than the version the default scope's record already names, or when there is no record. The insert
and the activation commit in one transaction under the definition id lock, so a failure leaves neither and the next startup
tries again. Among the versions of one id first published in the same startup the highest that does not opt out wins whatever
the registration order, and a lower version published later (a backport) never replaces a higher record. A restart reconciles
the same versions as unchanged, which is not a first publication, so it never activates anything: an operator's `activate`
and a `deactivateDefault` survive restarts, and a cleared id becomes current again only through a later first publication of
one of its versions or an operator `activate`. A version published through the definition service is never activated by this
rule, whatever its document says. Returning `false` from `activateOnDeploy()`, or setting the `activateOnDeploy: false` header
of a packaged document, publishes the version without activating it, so it can be tried in another scope with `activate` and
made current later.

**Rolling deploys.** Auto-activation runs on the first node that starts with the new version, and every node then reads the
activation record. Nodes still running the previous code cannot run the new version, so to roll a new version out across a
mixed fleet deploy it with `activateOnDeploy` set to `false`, and activate it once every node runs the new code. Rollback is
roll-forward only: activate the previous version again and retire the newer one once nothing is pinned to it (activation alone does not let an old-code node restart), or deploy a corrected version.

**Default-current guard.** While the default scope's record of an id names a version, `deprecate` and `retire` of that
version fail with `INVALID_TRANSITION` naming the key; the check runs in the same store transaction as the status change.
Activate another version in the default scope, or call `deactivateDefault(id)`, and the version can be deprecated and
retired. Decommissioning an id is therefore `deactivateDefault`, `deprecate`, wait until no instance is pinned to the version, `retire`. A
version that only a non-default scope's record names can be deprecated, and that scope's starts then fail as deprecated
until it is activated elsewhere or follows. `reinstate` returns a version to `AVAILABLE` and never makes it current.
`WorkflowCoreModule` declares optional
state-compatibility and configuration bindings plus the loader multibinding. It does not declare the registry store
or the lifecycle optional: a graph whose consumers (the engine, the definition service) request them must bind both,
the PostgreSQL module binds the store and the engine module the lifecycle, and a graph without them fails to build.
A definition graph needs no configuration parser.

### Document Policy Configuration

Bind `WorkflowDefinitionsConfig` directly, or install `WorkflowDefinitionsConfigModule` together with
`dev.vertique.config.parser.ConfigParsingModule` and the existing `@VertxConfig` input. The opt-in module
parses and validates `workflow.definitions` before the first document parse. Omitting that module keeps
the defaults and introduces no parser dependency into the graph.

| Property | Default | Meaning |
|---|---|---|
| `workflow.definitions.publicationClaimTimeoutSeconds` | `60` | At least 1: the age, in seconds on the database clock, at which a `PUBLISHING` claim is stale. Another `publish` call may then take the claim over, and startup resets it to `CANDIDATE`. A value below 1 fails startup with a `ConfigurationException` naming the key. It replaces the former staleness-seconds key of the same group, which is no longer read: an application that still sets that key gets the default for this one, so rename it |
| `workflow.definitions.maxDocumentSizeBytes` | `262144` | Positive runtime input-byte limit L; L + floor(L/2) must fit an integer |
| `workflow.definitions.buildDefaultScopeDocumentsAtStartup` | `false` | Boolean. When `true`, startup builds the plan of every live default scope document that is not packaged with the application into the plan cache, so a document that does not build (drift, a recorded type name no class resolves, a missing loader, a document over the size limit) fails startup naming it. When `false`, startup builds only the code definitions and the packaged documents, and such a document is built on first use: if it does not build, its first command is deferred as not runnable and a warning names it once. Documents of other scopes are never built at startup |

The engine's plan cache is not sized by this record. The engine reads `workflow.definitions.cache.maximumEntries` (default 500, at
least 1) and `workflow.definitions.cache.ttlSeconds` (default `0`: no expiry) itself; see the engine module reference. A key left out
keeps its default. Size it from the resident size of your largest plan; a resident plan is held by reference, so the cache
bounds the plan count, not bytes.

`maxStoredDocumentSizeBytes()` derives floor(3L/2). Durable source text must fit both that UTF-8 byte
bound and L Unicode code points. These bounds describe normalized text, not its original encoding.
All nodes sharing the table must use the same policy. Raising L increases parsing and memory costs;
raise it on every node before admitting larger documents. Before lowering it, ensure documents
required for restoration fit the new envelope; otherwise startup fails explicitly and a periodic reconciliation counts and retries the row.

### Recording Configuration

Bind `WorkflowRecordingConfig` directly, or install `WorkflowRecordingConfigModule` together with
`dev.vertique.config.parser.ConfigParsingModule` and the `@VertxConfig` input to parse `workflow.recording`.
`WorkflowCoreModule` declares the binding optional, so a graph without it keeps the defaults and needs no parser.

| Property | Default | Meaning |
|---|---|---|
| `workflow.recording.lockTimeoutMs` | `5000` | How long, in milliseconds, a caller waits to take an instance's execution lock before its transaction fails with a retryable `WorkflowPersistenceException`; at least 1, and a smaller value fails parsing with a `ConfigurationException` naming the key |
| `workflow.recording.maxPayloadBytes` | `262144` | Maximum encoded UTF-8 JSON payload size in bytes for record-time validation; at least 1024, and a smaller value fails parsing or direct construction with a `ConfigurationException` naming the key |
| `workflow.recording.maxOpenSignalsPerInstance` | `100` | Most signals one instance may hold open (pending, deferred, claimed or parked); from 1 to 10000, and a value outside that range fails parsing or direct construction with a `ConfigurationException` naming the key. A signal recorded beyond it fails with `WorkflowSignalLimitException` and writes nothing |
| `workflow.recording.schemaCacheMaxEntries` | `256` | Most compiled payload schemas the record-time validation keeps, keyed by the SHA-256 of the schema's canonical JSON, oldest-used evicted first; at least 1, and a smaller value fails parsing or direct construction with a `ConfigurationException` naming the key |

The timeout bounds only that one acquisition; the caller's own `lock_timeout` is unchanged afterwards.
`WorkflowRecordingConfig(lockTimeoutMs)`, `WorkflowRecordingConfig(lockTimeoutMs, maxPayloadBytes)` and
`WorkflowRecordingConfig(lockTimeoutMs, maxPayloadBytes, maxOpenSignalsPerInstance)` use the default for the components they omit.
Record-time validation also has a fixed maximum depth of 64, counting each object and array as one level. The engine applies the
payload bounds to a recorded signal's payload and explicit carrier, and to a task completion's payload, before it takes the execution lock;
a completion's payload is also validated against the JSON Schema its task stores for the decision. The open-signal limit is
enforced by a counter on the instance's queue row, raised by a conditional update when a signal is recorded and lowered when a
signal settles, so it holds under any isolation level; a parked signal stays counted.

### Executor Configuration

Bind `WorkflowExecutorConfig` directly, or install `WorkflowExecutorConfigModule` together with
`dev.vertique.config.parser.ConfigParsingModule` and the `@VertxConfig` input to parse `workflow.executor`.
`WorkflowCoreModule` declares the binding optional, so a graph without it keeps the defaults and needs no parser. The
executor itself is deployed by `vertique-workflow-engine` (see its module reference).

| Property | Default | Meaning |
|---|---|---|
| `workflow.executor.instances` | `1` | Executor verticle instances per node, each on its own event loop; 1 to 64 |
| `workflow.executor.concurrency` | `8` | Segments one instance keeps in flight; 1 to 256, so at most `instances` x `concurrency` per node |
| `workflow.executor.pollIntervalMs` | `5000` | The pause between two looks for runnable commands, in milliseconds; at least 100 |
| `workflow.executor.leaseSeconds` | `60` | How long a claim holds a command, in seconds; at least 5. Size it above the longest healthy segment: the execution lock is admitted until two margins before the lease ends and the segment is bounded one margin before it ends, where the margin is a tenth of the lease and at least one second. A segment that cannot finish in about `leaseSeconds` minus a margin after its claim is rolled back |
| `workflow.executor.maxAttempts` | `5` | The claims of a command that may end by a crash or a lease overrun before it is dead-lettered; at least 1. A transient failure, a busy execution lock and a release never spend an attempt |
| `workflow.executor.transientBackoffBaseMs` | `200` | The pause before the first retry of a transient failure, in milliseconds; at least 1. Each further failure of the same command doubles it |
| `workflow.executor.transientBackoffMaxMs` | `30000` | The longest pause before a retry of a transient failure, in milliseconds; at least `transientBackoffBaseMs` |
| `workflow.executor.maxStepsPerSegment` | `1000` | The nodes one segment may execute without reaching a wait; at least 1. A start that exceeds it fails its instance with the reason `STEP_LIMIT` |
| `workflow.executor.stuckAfterSeconds` | `300` | How long an unsettled command may wait after it first became available before it is reported stuck, in seconds; at least 1 |
| `workflow.executor.enabled` | `true` | Whether this node runs the executor. `false` makes the node recording-only (see Node Roles) |

A value outside its bound fails parsing with a `ConfigurationException` naming the full key. The executor's graceful stop
ends claiming and waits for in-flight segments to commit or reach their own deadline: normally within `leaseSeconds`, longer
when a statement is stuck in the database. A process killed before its stop completes leaves its claims to lease recovery:
another executor claims them after the lease ends, and the attempt of each killed claim stays spent.

What becomes of a command whose segment fails depends on the failure:

- A **transient failure** (a lost connection, a timeout, a serialization failure, a deadlock, a lock timeout or an optimistic
  conflict of the instance) rolls the segment back and reschedules the command with a capped backoff
  (`transientBackoffBaseMs` doubling per failure of the command up to `transientBackoffMaxMs`). The attempt its claim spent is
  restored, so an endless transient failure never dead-letters a command; it is reported stuck after `stuckAfterSeconds`.
- A **deterministic failure** of a start, and a segment that executes more than `maxStepsPerSegment` nodes, roll the segment
  back and settle the command `FAILED` and fail its instance with the reason. The instance then has no state.
- A **crash**, or a segment that does not finish before its deadline, leaves the claim to lapse with its lease and spends the
  attempt. A command that has spent `maxAttempts` attempts is dead-lettered at its next claim and reported stuck; a dead-lettered
  start also fails its instance.
- A claim that finds the command's **instance ended** settles the command as rejected, with a history entry, instead of
  claiming or dead-lettering it, whatever its attempts.

The reason of a failure is stored in the command's `last_error` as `<REASON_CODE>: <message>`, where the code is one of
`TRANSIENT_FAILURE`, `DETERMINISTIC_FAILURE`, `STEP_LIMIT`, `ATTEMPTS_EXHAUSTED` and `INSTANCE_ENDED`, and the message is
composed by the framework from the command kind and the counts involved, at most 4000 characters in all. Exception text,
class names, stack traces, SQL and payload values are never stored; the full exception goes to the executor log only.

### Node Roles

A node is an **executor node** by default. Setting `workflow.executor.enabled=false` makes it **recording-only**: it
deploys no executor, so it never claims a command, and it holds and loads no workflow code. The role is read once when the
graph is built; no operation changes it later.

A recording-only node starts against a database whose live code rows it has no code for. It writes nothing to the
definition tables at startup, does not reconcile code, does not check that code rows are provided and builds no plan.
It records starts, signals, task completions, retries, migrations and cancels, reassigns tasks, serves `query` (including the
contract-aware overload, which compares with the pinned row's stored contract) and runs the lifecycle operations that need no
build: `activate`, `follow`, `deactivateDefault`, `deprecate` and `retire`. An executor node, wherever it runs, executes what a
recording-only node recorded.

No `WorkflowContributor` runs on a recording-only node: it registers no code definition, and a packaged document is not parsed or compiled and none of its classes is loaded.

**Separation of duties.** The lifecycle operations stay open on a recording-only node by design, and the framework has no authorization of its own: any code that can call them can change which version starts new instances. Put an authorization layer in front of them, and restrict the database role of a recording-only node to the privileges it needs.

A recording-only node refuses the operations that need a build with `WorkflowNodeRoleException`: document `load`,
`publish` and `reinstate` of the definition service, `WorkflowRegistry.load`, and every `WorkflowPayloadSchemas` method. The
message names the operation and `workflow.executor.enabled=false`, never document content.

A node that runs the executor needs a `WorkflowPlanSchemaProducer`, which `vertique-workflow-schema` binds. An executor node
without it fails startup with a `WorkflowConfigurationException` that names `vertique-workflow-schema` and
`workflow.executor.enabled`, before it writes any definition row. A recording-only node needs neither the module nor any
workflow class.

### Executor Observer

`WorkflowExecutorObserver` (package `dev.vertique.workflow.executor`) receives the executor's events; `WorkflowCoreModule`
declares the empty `Set<WorkflowExecutorObserver>` multibinding, to which an application or a bridge module contributes with
`@Provides @IntoSet`. A factory-built engine takes the set from `WorkflowEngineParts.Builder.executorObservers`.

```java
public interface WorkflowExecutorObserver {
    void onEvent(WorkflowExecutorEvent event);
}
```

`WorkflowExecutorEvent` is sealed. Its events carry the command kind, an outcome, counts and durations, never an instance id or
a payload: `CommandSettled(kind, outcome, elapsed)` (outcome `COMPLETE`, `REJECTED`, `FAILED`, `DEAD_LETTERED` and the other
settled states), `TransientRetry(kind, transientCount)`, `LeaseExpired(kind)` (a claim ended by a crash or a lease overrun and the
command was claimed again), `QueueSnapshot(available, stuck, parked, deadLettered, notificationQueueUsage)` (sampled at most once a
second per executor instance, and only while an observer is registered), and the types `CommandDeferred` and `ActorAbsent`,
which are declared for the executor features that report them. **The set of event types grows additively in later releases, so an
observer must tolerate an event type it does not know: give its `switch` a `default` arm that ignores the event.**

`onEvent` runs on an event loop outside any database transaction and must not block. The executor calls each observer in its own
`try`: an exception it throws is caught and logged at warn level with the observer class and the event type, the other observers
still receive the event, and command execution is unaffected.

### Signal Source Observer

`SignalSourceObserver` and `SignalSourceEvent` (package `dev.vertique.workflow.source`) report the outcome of each signal a correlated signal source records, once per signal, and of a message that ends before a signal is chosen, once for the message. An application or a bridge module contributes an observer into the `Set<SignalSourceObserver>` multibinding, which `WorkflowSourceModule` in `vertique-workflow-services` declares empty.

```java
public interface SignalSourceObserver {
    void onOutcome(SignalSourceEvent event);
}

public record SignalSourceEvent(String source, String transport, @Nullable String contractName, @Nullable String signalName,
        @Nullable String definitionId, Outcome outcome) { }
```

`Outcome` is `APPLIED`, `NO_MATCH`, `AMBIGUOUS`, `DEDUPLICATED`, `IGNORED`, `UNDECODABLE`, `SCOPE_EMPTY`, `REFUSED_IDENTITY`, `INVALID_INPUT`, `LIMITED`, `OVERSIZED` and `STAGE_FAILED`. `APPLIED` means recorded for application. `contractName` and `signalName` are null only for an outcome reached before a signal is chosen. The event never carries a key, a header or a payload.

`onOutcome` runs on the context of the delivery, may run concurrently for different messages, and must not block. Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect the enclosing operation. `LinkageError`, `AssertionError` and `StackOverflowError` are swallowed the same way, and a `LinkageError` is reported at error level at a limited rate. The log names the observer class and the callback, never the observer's message. A throw never changes a message's fate, and the other observers still receive the event.

### Signal Source Types

A correlated signal source is described with three small types in `dev.vertique.workflow.source`, shared by every transport. `SignalMessage<T>` is what a source's stages read: `body()` is the decoded message, `header(name)` a transport header, and `key()` the Kafka record key (always empty on Camel). `SignalNoMatch` is the policy for a message whose key matches no waiting instance: `IGNORE` finishes it, `RETRY` redelivers it.

```java
public interface SignalMessage<T> {
    T body();
    Optional<String> header(String name);
    Optional<String> key();
}

public enum SignalNoMatch { IGNORE, RETRY }

public final class SignalSourceRefusal extends RuntimeException {
    public SignalSourceRefusal(String errorType);
    public String errorType();
}
```

A source's identity function (`dispatchAs`) fails with `SignalSourceRefusal` to refuse a message outright: the message is not retried and goes to the dead-letter path, or its exchange fails. The refusal carries its error type and nothing else (no cause, no stack trace, no message beyond the type), a null or blank error type is refused at construction, and the error type is the only detail that is forwarded, so keep it to a short name such as `TokenRejected` and never put a value from the message in it.

`WorkflowDefinitionLifecycleException` reports a typed `reason()` and `key()` for lifecycle refusals,
including stale claims, unavailable code, missing loaders, and plan drift. Asynchronous entrypoints
report invalid arguments through failed Futures.

---

## Exceptions

`workflow-core` defines the semantic exception hierarchy applications catch when workflow operations fail. Each root maps to the matching core framework exception root and its default HTTP status:

| Exception | Core root | HTTP |
|---|---|---|
| `WorkflowException` | `BusinessRuleException` | 400 |
| `WorkflowInvalidPayloadException` | leaf of `WorkflowException`; implements `ViolationCarrier` | 400 |
| `WorkflowSignalLimitException` | leaf of `WorkflowException`; not retryable | 400 |
| `WorkflowSignalNoMatchException` | final leaf of `WorkflowException`; names the contract and the signal | 400 |
| `WorkflowSignalAmbiguousException` | final leaf of `WorkflowConflictException`; names the contract and the signal | 409 |
| `WorkflowSignalActorException` | final leaf of `WorkflowException`; a delivery that carries a provenance captured no actor or the actor mapper refused it; names the contract and the signal | 400 |
| `WorkflowConflictException` | `ConflictException` | 409 |
| `WorkflowNotFoundException` | `NotFoundException` | 404 |
| `WorkflowConfigurationException` | `ConfigurationException` | — |
| `WorkflowNodeRoleException` | extends `WorkflowConfigurationException` | — |
| `WorkflowTechnicalException` | `TechnicalException` | 500 |
| `WorkflowUnavailableException` | `UnavailableException` | 503 |
| `WorkflowPersistenceException` | extends `WorkflowTechnicalException` (carries a `retryable` flag) | 500 |

`WorkflowSignalLimitException` is thrown when a signal cannot be recorded because its instance already holds
`workflow.recording.maxOpenSignalsPerInstance` open signals. The call fails in the caller's transaction and writes nothing, no
command, history entry or deduplication key, so the same signal can be sent again once an open signal has settled. `instanceId()`
and `limit()` expose what the message names; the message never contains a payload.

`WorkflowNodeRoleException` extends `WorkflowConfigurationException` and refuses an operation that needs a workflow build on a recording-only node (`workflow.executor.enabled=false`): document load, publication and reinstatement, `WorkflowRegistry.load` and the `WorkflowPayloadSchemas` methods. Its message names the operation and the setting and never any document content.

`WorkflowNoCurrentVersionException` extends `WorkflowDefinitionMissingException` and is thrown by a start without a version that finds no current version: neither the start scope nor the default scope has an activation record (with a durable store) or, without one, the deployment registered no version of the id that is not deprecated and does not opt out. It exposes `scopeKey()` (the start scope) and `definitionId()` and no version, because none is current, and it never selects a version from another scope.

`WorkflowDefinitionFailingException` extends `WorkflowConfigurationException` and is thrown by a start that selects a definition version marked failing (see Failing rows). It exposes `scopeKey()`, `definitionKey()` and `rule()`, and its message names the scope key, the definition id, the version and the rule only, never document content, state values or class names; the scope key and the id are repeated in bounded form (at most 64 code points, control characters replaced), and the accessors keep the full values. A failing row persists until an operator repairs it, so a retry cannot succeed and the exception is not the retryable `WorkflowUnavailableException`; running instances of the version and replays of earlier starts are not refused.

`WorkflowDefinitionNotFoundException` extends `WorkflowVersionPinUnavailableException` (503), so existing handlers keep working. The registry throws it from `resolve`, and fails `load` with it, for a key it holds no plan for and no stored available row supplies: a document version that does not exist, is not available, or is not resident in the plan cache when it is only resolved, and a code version this node does not deploy. It exposes `definitionKey()` and its message names the scope key, the definition id and the version only. It is never cached, so the next `load` reads the row again.

`WorkflowVersionNotRunnableException` extends `WorkflowVersionPinUnavailableException`, so existing handlers keep working. It is thrown when the node that receives the work holds no code for the definition version: a start that resolves to the version, or a task completion, typed-client query, retry, migration or timer on an instance pinned to it. A signal needs no plan to be recorded; the executor of a node without the code defers the recorded command instead. It exposes `definitionKey()` and `scopeKey()`, and its message names the scope key, the definition id and the version only. The version can be runnable on another node, so the failure belongs to the node and not to the instance: nothing is written, and no node marks an instance failed because it cannot run its pin. See `vertique-workflow-engine` for how work is refused and how the executor defers it.

`WorkflowUnknownTypeNameException` (a name no indexed class has) and `WorkflowTypeNameConflictException` (two classes under one name in a namespace) extend `WorkflowDefinitionException`, so they are configuration failures and existing handlers for definition errors keep working.

`WorkflowException` is the business-rule root only, not the superclass of every workflow failure — missing resources, state conflicts, configuration problems, unavailable capabilities, and technical failures use their own roots above instead.

`WorkflowInvalidPayloadException` reports a caller-supplied payload that is not acceptable. It is a
`ViolationCarrier`, so the default REST mapping renders it as a structured validation problem with one
error per violation instead of a message-only 400. `violations()` returns an unmodifiable list of
`ViolationDetail` (`path`, `message`, `type`, `args`, `form`) and is never `null`. A detail the engine builds for a
located failure has `form()` `JSON_POINTER`, so its `path` is an RFC 6901 JSON pointer; a detail that concerns the
whole payload (an unknown decision, a payload that is not accepted or not required, one that does not bind) has a
`null` path. The pointer is relative to the payload the engine validated and is named
with the engine's own mapper; it can differ from a pointer into the request body that carried the payload, for
example when the route binds names differently or nests the payload, so the default REST mapping reports no
location for it. An application whose payload is the request body, or sits at a known place in it, reports the
pointers with `ValidationErrorDetail.ofViolations(exception, basePointer)` from its own exception mapper. A detail
an application builds with `ViolationDetail.atPointer` must hold a path that is `""` or starts with `/` and
contains no key the client chose. Details built by the engine are value-free (no payload value, decision name, class name,
decode message or map key). Constraint messages produced by an application's constraints pass through
unchanged, so applications must not interpolate the validated value. A binding failure keeps the decode
exception as the cause, and it can carry submitted text, so applications must not log the cause of a
`WorkflowInvalidPayloadException` at INFO or above.

`vertique-workflow-engine` translates DB-origin failures into this hierarchy at the persistence boundary; see `dev.vertique:vertique-workflow-engine` for the translation table.

---

## Invariants & Gotchas

Behaviors that are load-bearing for correct use and not obvious from method signatures alone:

- **Definition rows can carry the declared signal names and the start schema.** `WorkflowDefinitionRecord` has two trailing nullable components, `signalNames` (`{"instance": [names], "branches": {"<forkStep>/<branch>": [names]}}`, names sorted and de-duplicated, `branches` empty without a fork) and `startSchema` (the JSON Schema of the start payload). A store writes them in the same statement as the plan hash and leaves them as they are when the hash is unchanged; the previous twelve-argument constructor still exists and passes null for both. They are null when the row was written by a node without schema production, and for a row written before they were stored until a node with schema production next builds its plan. `WorkflowDefinitionRegistryStore.fillSignalSchemas(key, planHash, signalNames, startSchema)` writes them to a row whose `signalNames` is still null and whose plan hash is still `planHash`, without touching the plan hash; its default does nothing.
- **`WorkflowPlanSchemaProducer` is an internal seam.** `dev.vertique.workflow.plan.WorkflowPlanSchemaProducer` produces the start schema and the decision schemas of a plan; `vertique-workflow-schema` binds it, and `WorkflowCoreModule` declares the binding optional (`Optional<WorkflowPlanSchemaProducer>`). Applications do not implement it. Without a binding nothing fails and rows keep null columns.
- **New states and links exist as types before every writer is in place.** `WorkflowStatus.PENDING` (accepted, not initialized; not terminal, so a `PENDING` instance has a null `stateJson`, `currentStepId` and `subjectRef`), `TaskStatus.COMPLETING` (a recorded completion reserves the task; it cannot be reassigned or completed again, and returns to `OPEN` if that completion is rejected), `TimerRecord.commandId` (a nullable trailing component) and `TaskDecisionDescriptor.payloadSchema` (nullable; the three-argument constructors are kept) are appended, as are thirteen `WorkflowEntryType` constants for recorded commands, parked signals, decisions and state baselines. Exhaustive switches over `WorkflowStatus` and `TaskStatus` need a branch for the new constants. A new task stores each decision's payload schema in `payloadSchema` when a schema module is installed, and `TaskRecord.requireVersionStability` records the plan's version-stability requirement; both are `null`/absent on a task created by an earlier version, which the executor validates from the plan alone. `TaskRecord.reservationCommandId` names the recorded completion that reserves a `COMPLETING` task.
- **A declared key moves the plan hash; an undeclared one does not.** The hash writes `correlationKeyCallbackId` after the timeout branch, and only when it is non-null. A plan whose waits declare no key hashes as it did before the key existed, and adding `.correlateBy(...)` to a wait changes the hash of the version that declares it.
- **`WorkflowBuilder.init(payloadType, initialState)` is mandatory and may be called only once.** It must be the first builder call in a definition. Missing or duplicate `init` is rejected at registration with `WorkflowDefinitionException`.
- **Logical names move the hash.** The plan hash digests logical type names. A class without `@WorkflowType` keeps its binary name and its hash; adding `@WorkflowType` with a value that differs from the binary name changes the hash of every version first published afterwards. With a durable registry store, versions published earlier keep the names recorded with their hash (see Name bindings).
- **Plan hash pinning.** Each `WorkflowInstance` records the `definitionId`, `definitionVersion`, and `planHash` it started under. Resumes execute against that pinned plan, not the currently registered "latest" version. Use `WorkflowOperations.migrate(...)` plus a `WorkflowMigrationHandler` to move an in-flight instance to a newer version.
- **Missing recorders fail the transition.** When a step emits a side-effect intent and no `WorkflowSideEffectRecorder<TX>` is registered for that `IntentKind`, the transaction fails. There is no silent no-op. Optional intent kinds may be declared via `@OptionalIntentKinds` and are no-ops only when explicitly opted in.
- **Recorders must be durable, never live.** A `WorkflowSideEffectRecorder<TX>` may persist an outbox row, or write to another transactional store, but must not call a downstream service before commit.
- **A history listener must not block, and must have a default branch.** Callbacks run on a Vert.x thread, and the variants of `WorkflowHistoryEvent`, `DefinitionLifecycleChange` and their enums grow additively. A `switch` without `default` fails to compile, or throws `MatchException`, when a version adds a variant. See `WorkflowHistoryListener`.
- **A history listener may miss events.** Delivery is after the commit and at most once; a process stop between the two loses the notification. Catch up from `WorkflowInstanceHistoryService` by `sequence` when a consumer must be complete, and never use a listener as the only copy of a record.
- **Listener events are not authorization input.** An event with `HistoryAttribution.DEGRADED` or `ABSENT` was not attributed to a verified identity.
- **A listener makes a call wait for the instance lock.** With a `WorkflowHistoryListener` bound, even a duplicate signal waits for the instance's execution lock, and a lock timeout fails the call with a retryable `WorkflowPersistenceException`.
- **Human task DSL guard rails.** A task step's decision list must be non-empty with unique decision names (enforced by the `HumanTaskNode` compact constructor). The optional due-date triplet (`dueDate`, `onDueMutator`, `dueNextStepId`) is all-or-nothing — supplying a partial subset is rejected at construction.
- **`TaskFilter` single-assignee-kind guard.** At most one of `assigneeUser` / `assigneeRole` / `assigneeQueue` may be set, mirroring the single-`assignee_type` storage shape. Use `TaskFilter.empty()` plus `with*` copies to compose filters. The optional `scopeKey` filter is an exact match, not validated, and `null` means every scope.
- **Version-stable approvals.** `.requireVersionStability()` on a task captures `subject_version` at task creation; completion fails with `WorkflowStaleSubjectVersionException` when the reviewer's `reviewedSubjectVersion` does not match the snapshot. Creating a stability-required task with a null `subject_version` fails with `WorkflowSubjectVersionUnavailableException`.
- **Subject resolver precedence.** An explicit `StartCommand.subjectRef` wins; otherwise a definition-level `.subject(...)` resolver is invoked with the initial state. A configured resolver returning `null` is a contract violation (`WorkflowDefinitionException`), not a silent "no subject".
- **`WorkflowActor` is required on every task mutation command.** `TaskCompletionCommand` and `TaskReassignmentCommand` reject null / blank actor identity through their compact constructors. Blank `reason` is normalized to `null` before the command is fingerprinted for idempotency.
- **Optional `expectedAssignment` precondition.** `TaskCompletionCommand` and `TaskReassignmentCommand` accept a nullable `expectedAssignment`: the assignment the caller observed when it decided the actor was eligible. The engine compares it against the `FOR UPDATE`-locked task row (never against the earlier non-locking read) and fails with `WorkflowStaleAssignmentException`, a `WorkflowConflictException` with 409 semantics, when the assignment has moved. `null` keeps the unchecked behavior. The value is a precondition, not mutation content, so it is excluded from the idempotency fingerprint and a retry resolves through dedup regardless of it.

---

## Dependencies

- **core** - common Vertique exception and module conventions.
- **db-core** - `PageCursor` and `PagedResult` used by task and workflow query APIs.
