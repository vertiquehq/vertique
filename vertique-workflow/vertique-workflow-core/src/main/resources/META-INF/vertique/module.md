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

Two default methods carry the contract of the caller: `signal(id, contractName, signalName, payload, signalDedupKey)` and `query(id, contractName)`. A binding that checks contracts compares the name with the contract of the definition version the instance is pinned to and fails a mismatch with `WorkflowDefinitionContractMismatchException`; the defaults delegate to the contract-less methods, which perform no check. Typed clients send their contract name through these overloads; direct callers of the four-argument `signal` and of `query(id)` keep today's behavior. A wrapper or decorator around a `WorkflowOperations` binding must forward both overloads to the wrapped binding; inheriting the defaults silently drops the caller-contract check. The check guards against developer error, such as a client built for the wrong contract; it is not an authorization boundary, because the caller supplies the contract name. A client proxy generated by an older annotation processor does not call these overloads and fails with `WorkflowClientProxyLinkageException` until it is rebuilt.

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
| `TransactionalWorkflowOperations<TX>` | Drive workflows inside an existing transaction |
| `TaskStore<TX>` | Store human task rows |
| `TransactionalTaskCallbacks<TX>` | Engine callback surface used by the task service |
| `TimerStore<TX>` | Timer persistence; the timer row links to the command that fires it |
| `WorkflowMigrationHandler` | Move an in-flight instance to a newer definition version, or to the same version in another scope |
| `WorkflowStateCompatibility` | Decide whether one state class extends another; bound by `vertique-workflow-schema` |

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
`AVAILABLE` to `DEPRECATED`; `DEPRECATED` to `RETIRED` after the cooling window and with no live pinned instance; and
`DEPRECATED` or `RETIRED` back to `AVAILABLE` (reinstate). Every other pair is refused with `INVALID_TRANSITION`, and a
repeat of a completed transition (publishing an available version, deprecating a deprecated one, retiring a retired one) is
accepted without a change.
A deprecated version is a status of its row: the engine refuses a new start or a migration into it from the status it reads in the transaction that needs it, pinned resolution still serves it, and an activation record, not the registry, selects the current version. The store SPI adds the
conditional `transition(key, from, to)` operation and the lifecycle adds `deprecate(DefinitionKey)`.
The store adds `retire(DefinitionKey)` and the lifecycle adds `retire(DefinitionKey)` (CANDIDATE, or DEPRECATED after the
cooling window with no live pinned instance); `WorkflowInstanceQueryService.countNonTerminal(DefinitionKey)` (and the default-scope
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
Activation records), and `resetStalePublishing` (claims older than twice the
staleness bound return to CANDIDATE).
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
version that startup inserts for the first time (the store reports `INSERTED`) is activated in the default scope when it does
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
retired. Decommissioning an id is therefore `deactivateDefault`, `deprecate`, wait out the cooling window, `retire`. A
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
| `workflow.definitions.planCache.maxEntries` | `500` | At least 1: the most document plans the engine's plan cache keeps resident (see Loading plans). Size it from the resident size of your largest plan; a resident plan is held by reference, so the cache bounds the plan count, not bytes. A value below 1, or one that is not an integer, fails startup with a `ConfigurationException` naming the key |
| `workflow.definitions.buildDefaultScopeDocumentsAtStartup` | `false` | Boolean. When `true`, startup builds the plan of every live default scope document that is not packaged with the application into the plan cache, so a document that does not build (drift, a recorded type name no class resolves, a missing loader, a document over the size limit) fails startup naming it. When `false`, startup builds only the code definitions and the packaged documents, and such a document is built on first use: if it does not build, its first command is deferred as not runnable and a warning names it once. Documents of other scopes are never built at startup |

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
| `WorkflowConflictException` | `ConflictException` | 409 |
| `WorkflowNotFoundException` | `NotFoundException` | 404 |
| `WorkflowConfigurationException` | `ConfigurationException` | — |
| `WorkflowTechnicalException` | `TechnicalException` | 500 |
| `WorkflowUnavailableException` | `UnavailableException` | 503 |
| `WorkflowPersistenceException` | extends `WorkflowTechnicalException` (carries a `retryable` flag) | 500 |

`WorkflowSignalLimitException` is thrown when a signal cannot be recorded because its instance already holds
`workflow.recording.maxOpenSignalsPerInstance` open signals. The call fails in the caller's transaction and writes nothing, no
command, history entry or deduplication key, so the same signal can be sent again once an open signal has settled. `instanceId()`
and `limit()` expose what the message names; the message never contains a payload.

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
- **`WorkflowBuilder.init(payloadType, initialState)` is mandatory and may be called only once.** It must be the first builder call in a definition. Missing or duplicate `init` is rejected at registration with `WorkflowDefinitionException`.
- **Logical names move the hash.** The plan hash digests logical type names. A class without `@WorkflowType` keeps its binary name and its hash; adding `@WorkflowType` with a value that differs from the binary name changes the hash of every version first published afterwards. With a durable registry store, versions published earlier keep the names recorded with their hash (see Name bindings).
- **Plan hash pinning.** Each `WorkflowInstance` records the `definitionId`, `definitionVersion`, and `planHash` it started under. Resumes execute against that pinned plan, not the currently registered "latest" version. Use `WorkflowOperations.migrate(...)` plus a `WorkflowMigrationHandler` to move an in-flight instance to a newer version.
- **Missing recorders fail the transition.** When a step emits a side-effect intent and no `WorkflowSideEffectRecorder<TX>` is registered for that `IntentKind`, the transaction fails. There is no silent no-op. Optional intent kinds may be declared via `@OptionalIntentKinds` and are no-ops only when explicitly opted in.
- **Recorders must be durable, never live.** A `WorkflowSideEffectRecorder<TX>` may persist an outbox row, or write to another transactional store, but must not call a downstream service before commit.
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
