<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Workflow Definition Module

> **Status:** Stable
> **Package:** `dev.vertique.workflow.definition`
> **Artifact:** `vertique-workflow-definition`
> **Depends on:** workflow-core, vertique-core, jackson-databind, jackson-dataformat-yaml, dev.cel:cel-runtime

`vertique-workflow-definition` adds YAML/JSON workflow authoring on top of `workflow-core`. A definition document is parsed, validated, compiled into the same `WorkflowPlan` used by code-first definitions, and stored as a row of the definition table; a node builds the plan from the row when it is first used.

The runtime engine never parses definition files. Parsing and validation happen in the control plane, either at bootstrap or through `WorkflowDefinitionService`.

This module currently provides **typed document-backed workflows**, not fully no-code workflows. The document defines orchestration. Java still supplies the state type, contract type, start payload type, payload classes, and named callbacks referenced by the document.

---

## When To Use It

Use this module when workflow shape should be data-driven but the application still owns typed Java integration code.

Good fits:

- Environment- or scope-specific orchestration assembled from registered callbacks.
- Workflows managed through files, CMS records, database rows, REST uploads, or generated artifacts.
- Reviewable workflow documents where engineers still control the callback catalog.

Poor fits for the current implementation:

- Fully dynamic workflows where the document defines new state and payload schemas with no Java classes.
- Business-user-authored object schemas that must not contain Java class names.
- Arbitrary scripting inside definitions.

For Vertique Objects, prefer an objects workflow bridge with fixed Java state/payload types carrying object type, object id, object version, and workflow-local context.

---

## Compilation Flow

```text
DefinitionResource
  -> WorkflowDefinitionParser
  -> WorkflowDefinitionDocumentValidator
  -> WorkflowDefinitionCompiler
  -> DocumentBackedWorkflowDefinition
  -> definition table row  (the plan is built from the row: WorkflowRegistry.load(...))
```

`DefinitionResource` contains bytes, format, and source metadata. The source may be a repository file, CMS record, database row, REST upload, object store, or generated artifact. Source location is provenance metadata; it does not affect engine execution or plan identity.

`WorkflowDefinitionBootstrap` loads contributed `WorkflowDefinitionSource` resources during registry construction. A packaged document is trusted: it may name a class that no catalog lists, by its binary name. Before the document is resolved, the bootstrap loads each such class with the context class loader (lookup only) and adds it to the index, so the class then joins the index for later loads. A name that is not a loadable class fails startup with a `LOAD_FAILURE` violation attributed to the resource, and a packaged document that startup then rejects leaves no name behind in the index. The bootstrap parses, validates and compiles every packaged document and registers none of them: the registry holds code only. The engine's startup then records each packaged document in the definition table, builds its plan once under the plan rules (a plan that waits for a signal its contract does not declare, for example, fails startup with the `WorkflowDefinitionException` naming that document's definition id and version) and keeps it only in the plan cache, where it is built again from its row when it is next needed. Fix or remove the document before the next start. Runtime management surfaces can call `WorkflowDefinitionService.load(...)` and `publish(...)` directly.

---

## Example Definition

This example mirrors the current document shape used by the test fixtures.

```yaml
definitionId: order-fulfillment
definitionVersion: 1
stateType: dev.vertique.examples.workflow.order.state.OrderState
contract: dev.vertique.examples.workflow.order.OrderFulfillmentWorkflowV1
startPayloadType: dev.vertique.examples.workflow.order.command.PlaceOrder
initialStateMapper: order.fromPlaceOrder
subjectResolver: order.subject
initialStep: reserve-inventory
steps:
  - id: reserve-inventory
    type: service
    target: inventory.reserve
    payloadMapper: order.reserveInventoryPayload
    compensation: release-inventory
    next: wait-inventory

  - id: release-inventory
    type: compensation
    forwardStep: reserve-inventory
    target: inventory.release
    payloadMapper: order.releaseInventoryPayload

  - id: wait-inventory
    type: wait-signal
    signal: inventory.reserved
    payloadType: dev.vertique.examples.workflow.order.signal.InventoryReserved
    stateReducer: order.applyInventoryReserved
    next: route-review
    timeout:
      after: PT15M
      onTimeoutMutator: order.markInventoryTimedOut
      next: cancel-order

  - id: route-review
    type: decision
    routes:
      - when: "review.riskLevel in ['high', 'critical']"
        to: manual-review
      - condition: policy.lowRiskAutoApprove
        to: ship-order
    default: manual-review

  - id: manual-review
    type: human-task
    taskType: approval
    assignment:
      mode: role
      value: editors
    decisions:
      - name: approve
        payloadType: dev.vertique.examples.workflow.order.task.ApprovePayload
        applicator: review.applyApprove
        next: ship-order
      - name: reject
        payloadType: dev.vertique.examples.workflow.order.task.RejectPayload
        applicator: review.applyReject
        next: cancel-order
    requireVersionStability: true

  - id: ship-order
    type: service
    target: shipping.create
    payloadMapper: order.shippingPayload
    next: done

  - id: done
    type: complete

  - id: cancel-order
    type: fail
    errorType: order-cancelled
    messageFactory: order.cancellationMessage
```

The full fixture also demonstrates timers, compensation, fork, join, due dates, and reminders.

---

## Definition Elements

### Top-Level Fields

| Field | Required | Meaning |
|---|---:|---|
| `definitionId` | yes | Durable workflow definition id |
| `definitionVersion` | yes | Version registered for this document |
| `stateType` | yes | Logical type name (`@WorkflowType`) of the workflow state; a packaged document may also use a binary class name |
| `contract` | yes | Logical contract name (`@WorkflowContract.value()`) of an interface annotated with `@WorkflowContract`; a packaged document may also use a binary class name. Any number of documents and Java definitions may name one contract |
| `startPayloadType` | yes | Logical type name of the start payload; a packaged document may also use a binary class name |
| `initialStateMapper` | yes | Named callback that maps start payload to initial state |
| `subjectResolver` | no | Named callback that derives `WorkflowSubjectRef` from state |
| `initialStep` | yes | Step id where execution begins |
| `steps` | yes | Non-empty list of step definitions |

The `contract` class must carry `@WorkflowContract`. A contract names no definition, so any number of documents and versions may name one contract.

### Step Shape

Each step has an `id` and `type`. Common step fields:

| Type | Purpose | Important fields |
|---|---|---|
| `service` | Record a service-dispatch side effect | `target`, `payloadMapper`, `next`, optional `compensation` |
| `wait-signal` | Suspend until a named signal arrives | `signal`, `payloadType`, `stateReducer`, `next`, optional `timeout` |
| `timer` | Suspend until a resolved time | `fireAt`, `next` |
| `human-task` | Create a task and wait for a decision | `taskType`, `assignment`, `decisions`, optional `due`, `reminders`, `requireVersionStability` |
| `decision` | Route based on CEL expressions or named conditions | `routes`, `default` |
| `fork` | Start parallel branches | `branches`, `join`, optional `retryPolicy` |
| `join` | Fan branches back into one path | `policy`, `reducer`, `next`, optional `onFailure` |
| `compensation` | Record compensating service dispatch | `forwardStep`, `target`, `payloadMapper` |
| `complete` | End successfully | none beyond `id` |
| `fail` | End with failure and optional compensation | `errorType`, `messageFactory` |

Callback-like fields are string identifiers resolved against registries contributed by the application. The document never embeds Java lambdas or scripts.

---

## Expressions And Conditions

Decision routes support two mutually exclusive condition forms:

```yaml
routes:
  - when: "state.total > 1000 && review.country == 'FI'"
    to: manual-review
  - condition: policy.lowRiskAutoApprove
    to: auto-approve
default: manual-review
```

`when` is parsed and compiled by the configured `ExpressionProfile`. The default profile uses CEL. The runtime evaluation environment includes:

- `state` - the workflow state converted to a map.
- Registered named conditions - pre-evaluated booleans keyed by condition id.

`condition` references a named condition callback directly. Named conditions are evaluated once per decision evaluation and may be reused by multiple routes.

CEL is intentionally isolated behind `ExpressionProfile`; replacing CEL should require rebinding the profile, not changing the compiler or validator.

---

## Validation

A well-formed definition is one that passes both parsing and semantic validation:

- Jackson YAML/JSON parsing is strict: unknown fields and invalid step types fail.
- Required top-level fields and required step fields must be present.
- Type and contract names resolve through the `WorkflowTypeNames` index only (types for state and payload names, contracts for the contract name). The index holds the classes of every contributed `WorkflowTypeCatalog` and of every registered definition, under their logical and binary names. Before validation the pipeline resolves the contract, the state type, the start payload type, then the signal and decision payload types in step order, and the first name the index does not hold fails the load with `WorkflowUnknownTypeNameException`, which names the document field, and repeats the name only when it consists of letters, digits, underscore, dot, dollar sign and hyphen and is at most 255 characters long. No class is loaded by a name the document supplies.
- The contract class must be annotated with `@WorkflowContract`. A contract names no definition, so several documents and versions can share one contract. A document's plan is built under the same plan rules as a Java definition's, including the check that every signal it waits for is declared on its contract.
- Contract names and type names are separate namespaces: a contract name given as `stateType`, or a type name given as `contract`, is unknown.
- Named callbacks must resolve through the registered callback registries.
- Decision expressions must parse through the expression profile.
- Step ids must be unique, reachable from `initialStep`, and all step references must point to existing steps.
- The plan of a document is built through the registry's build path, so core plan validation and the contributed validators still apply. Publishing a loaded definition through the service builds its plan once to validate it and completes the row; nothing is registered, and the node builds the plan again from the row when it is first used. A definition loaded into one scope is not found under the default scope. Documents packaged with the application are recorded in the default scope.

There is currently no separate published JSON Schema or YAML Schema file for workflow definitions. The authoritative validation is strict Jackson mapping plus `WorkflowDefinitionDocumentValidator`.

---

## Named Callback Catalog

Applications expose reusable behavior by contributing named callbacks through Dagger multibindings. Documents reference these names.

Common callback categories:

| Category | Example use |
|---|---|
| Initial state mapper | Start payload -> workflow state |
| Payload mapper | State -> service request payload |
| State reducer | State + signal payload -> state |
| State mutator | State -> state for timers, due dates, or timeout branches |
| Timer resolver | State -> due instant or duration |
| Fail message factory | State -> human-readable failure message |
| Subject resolver | State -> `WorkflowSubjectRef` |
| Task assignment resolver | State -> task assignment |
| Branch result reducer | State + branch results -> state |
| Named condition | State -> boolean used by decisions |

Example:

```java
@Provides
@IntoSet
static PayloadMapperContributor reserveInventoryPayload() {
    return PayloadMapperContributor.of(
        "order.reserveInventoryPayload",
        state -> new ReserveInventoryRequest(((OrderState) state).orderId()));
}
```

---

## Runtime Loading And Publication

`WorkflowDefinitionService` returns Futures for loading, publication, metadata listing, and source
attribution. `load` takes the scope key as its first argument; compose publication after the candidate load completes:

```java
Future<CompiledDefinitionRef> published = service
        .load(WorkflowScopes.DEFAULT, content, DocumentFormat.YAML, metadata)
        .compose(ref -> service.publish(new DefinitionKey(
                WorkflowScopes.DEFAULT, ref.definitionId(), ref.definitionVersion())).map(ref));

Future<List<WorkflowDefinitionRecord>> definitions = service.list(
        new DefinitionFilter(WorkflowScopes.DEFAULT, null, null));
```

`load` parses, validates, and compiles a candidate into the named scope without registering it. When a `WorkflowPlanSchemaProducer` is bound (install `WorkflowSchemaModule`), the candidate row is inserted with the signal names its plan declares and its start schema; without one both columns stay null. The scope argument is the only
source of the scope: a document never names one, and a top-level `scope` property is rejected as an unknown property with a
parse failure and stores nothing. The scope key is required, nonblank, at most 255 code points and free of control characters;
a `null` key fails the returned Future with `NullPointerException` and any other invalid key with `IllegalArgumentException`,
before the document is read. A definition id needs no scope prefix, and each scope numbers its own versions of an id, so a
scope's first `purchase-approval` is version 1 beside the default scope's own versions. A document loaded this way, or restored from the store, can name only classes in the type-name index, so a class an application wants such a document to use must be listed in a contributed `WorkflowTypeCatalog` (the workflow annotation processor generates one for `@WorkflowContract` and `@WorkflowType` classes) or be used by a registered definition; a name outside the index fails the returned Future with `WorkflowUnknownTypeNameException` before any validation runs. `publish` makes it available;
a failed load leaves every published version untouched. A document status is `CANDIDATE` after the load, `PUBLISHING` while a
node holds the publication claim, `AVAILABLE` once published, and later `DEPRECATED` or `RETIRED`. Publishing one version
changes no other: any number of versions and scopes of one id are available at once, and publishing an available version again is
accepted without a change. Which version starts when none is named is selected separately from the status. Source and
metadata are durable (a graph that includes this module binds a `WorkflowDefinitionRegistryStore` and a
`WorkflowDefinitionLifecycle`, normally PostgreSQL and the engine) and publication recompiles the stored text rather than
trusting a local cache.
The managed application lifecycle records the packaged documents and the deployed code before any BOOTSTRAP verticle
deploys. It keeps no copy of the table: a node builds the plan of a stored document from its row, outside any
transaction, when the plan is first needed, and a missing loader or a stored/compiled hash mismatch fails that load.
All nodes sharing live documents must have the appropriate loader and application callback/type catalog. Source URIs
are never fetched.

`deprecate(DefinitionKey)` moves an AVAILABLE version to DEPRECATED durably and reports DEPRECATED in service metadata
and lists. New starts of that version and migrations to it are refused with reason `DEPRECATED`, including a retry of an
explicit start under an idempotency key used before the deprecation, on every node at once, because a start reads the
status of the row in its own transaction; instances already pinned to it keep running. Deprecating a version leaves every
other version available. The default scope's current version, which an activation record selects, cannot be deprecated
until another version is activated or `deactivateDefault` clears the record. It requires the durable registry:
without one, the returned Future fails.

`activate(scopeKey, definitionId, targetScopeKey, version)` makes a version the one a start without a version runs in a scope,
replacing the scope's record; the target is the scope itself or the default scope (the default scope may target only itself),
and it must be available in the target scope, otherwise the Future fails with `INVALID_TRANSITION` naming the target key or,
for an invalid scope combination, with `IllegalArgumentException`. Publishing through this service never activates anything. A packaged document (a `WorkflowDefinitionSource` contribution
loaded at startup) is activated in the default scope when it is first published, like code, unless its header sets
`activateOnDeploy: false`; the optional boolean header is absent (true) by default. A document loaded through the service
ignores the header: it stays `CANDIDATE` until published and stays inactive until an operator activates it. To roll a new
packaged version out across a mixed fleet, ship it with `activateOnDeploy: false` and activate it once every node runs the new
code. `follow(scopeKey,
definitionId)` removes a non-default scope's record so it follows the default again, `deactivateDefault(definitionId)` clears
the default scope's record (an absent record is accepted), and `activations(scopeKey)` lists the records of a scope or, with
`null`, of every scope. The default scope's current version cannot be deprecated or retired until another version is activated
or `deactivateDefault` clears the record; to decommission an id call `deactivateDefault`, `deprecate`, and `retire` once no
instance is pinned to the version. Like
`load`, they take any scope, so restrict them to authorized callers. A deactivation is not durable across a release: when a
later release deploys a higher version whose header does not set `activateOnDeploy: false`, that version becomes the
default scope's current version again on its first publication. Ship such a version with `activateOnDeploy: false` to keep
the id deactivated.

`reinstate(DefinitionKey)` brings a retired version back when its source is available (the deployed code for a code version,
the stored text for a document that builds), and a deprecated version back by changing its status; either becomes AVAILABLE.
The returned Future fails with `CODE_UNAVAILABLE`, `DRIFT`,
`LOADER_MISSING` or `INVALID_TRANSITION`, and service metadata reports the committed status.

`retire(DefinitionKey)` retires a candidate version, or a deprecated one with no live pinned
instance, however recently it was deprecated, and reports RETIRED in service metadata and lists; the returned Future fails with
`PINNED_INSTANCES` or `INVALID_TRANSITION`. Retiring a version changes only its own row, even when id, version and hash
are equal to those of another scope's. A scope's activation record can still name a version that is retired, because
only the default scope's current version is protected; a start without a version in that scope then fails until the
record is moved to an available version or removed with `follow`.

Startup reads the default scope, so it does not grow with the documents of other scopes: it records the code definitions and
the packaged documents, builds each packaged document once to validate it, and builds no other document (a document published
at run time is built from its row on first use; `workflow.definitions.buildDefaultScopeDocumentsAtStartup` makes startup build
every live default scope document into the plan cache and fail on one that does not build). A document of another scope is
built from its row when its plan is first needed. An id that deployed code registers is a
default-scope CODE row, and a document of another scope may publish the same id and version beside it.
A load into a scope writes only that scope's rows; it never touches the default scope's or another scope's rows. Documents
packaged with the application are loaded into the default scope. The service does not check identity: the application decides who may load
into which scope, so restrict `load`, `publish`, `deprecate`, `retire`, `reinstate`, `activate`, `deactivateDefault`, `follow` and `activations` to authorized callers. The
public `currentStatus` of a version that is only persisted metadata (a candidate or retired row that startup did
not compile) reports the persisted status instead of returning `null`; it returns `null` only when nothing is known about the version. An equal plan hash no longer proves equal text: a
packaged document whose text differs (even by a comment, whitespace or line endings) from the stored text of the
same id and version fails startup, so bump the version of a changed document; a retired row of that id and version blocks it too.

A listed `WorkflowDefinitionRecord` of a scope row that newly deployed code conflicts with carries `failure()`, a
`DefinitionFailure` naming the rule (`STATE_TYPE_NOT_EXTENDED` or `NAME_BINDING_UNRESOLVED`); `list(new DefinitionFilter(scope,
id, null))` shows it in every status, and it is `null` for a row that is not failing. New starts on such a version are
refused with `WorkflowDefinitionFailingException` (see the engine reference).

`sourceMetadata(DefinitionKey)` reads attribution without fetching source text. Lists contain immutable,
key-bearing metadata. `list` and `sourceMetadata` read the durable table and have no
in-memory fallback, so metadata of packaged documents and code definitions appears only after the managed
BOOTSTRAP step has recorded it. Definition id and scope key (255), source type (64), URI (2048) and revision,
author and upload id (255) code-point limits apply in every mode. NUL and
unpaired surrogates are rejected in every text field, ISO control characters in ids and scope keys, and a
version must be positive. Over-limit input is rejected before anything is registered. Invalid asynchronous
arguments are failed Futures. Previously started instances keep their pinned version. Coexisting versions of one document id name the same contract class. A load into a scope other than the default one is refused with `WorkflowDefinitionContractMismatchException` when the default scope holds the id, in any status including candidate, under another contract, unless the scope already holds the id; the in-memory store applies the same rule, and a default-scope load of an id that a scope holds under another contract is accepted.

### Source Text And Size Policy

`WorkflowDefinitionsConfig` supplies runtime input limit L (default 262144 bytes). Install the opt-in
`WorkflowDefinitionsConfigModule` with the canonical `ConfigParsingModule` to parse
`workflow.definitions.maxDocumentSizeBytes`, `buildDefaultScopeDocumentsAtStartup`, `planCache.maxEntries` and `publicationClaimTimeoutSeconds`, or bind the typed config directly.
The default applies without a parser binding. The YAML scanner's code-point limit is L; nesting,
expression limits, and collection-alias settings remain independent.

| Source path | Admission |
|---|---|
| Runtime `load` | Original input at most L bytes, checked before parsing |
| Static source | Existing pipeline first, then strict whole-source conversion and stored-text envelope; no runtime raw-byte cap |
| Durable restore | Guarded stored text, at most floor(3L/2) UTF-8 bytes and L Unicode code points; no raw-L restore cap |

Durable storage preserves decoded source characters, comments, whitespace, and line endings, rather
than original encoded bytes or a reserialized document. JSON retains the existing UTF-8/16/32 reader
support and consumes its recognized leading encoding marker; YAML remains UTF-8 and preserves its
reader's leading marker character. Strict conversion checks the whole durable source after the
ordinary pipeline succeeds: malformed encoding, actual decoded NUL, and unpaired surrogates fail
before persistence. Escaped source sequences remain source text. No additional trailing-token policy
is enabled, so this representation check can reject malformed trailing bytes that the byte parser alone
ignores.

PostgreSQL DOCUMENT bodies require server encoding UTF8. All nodes sharing the table use the same
limits. Raise L everywhere before admitting larger sources; increased limits increase memory and
parsing costs. Lowering L below a required stored row's envelope makes restoration fail explicitly.
A changed catalogue lookup alias alone need not change the existing plan hash: the hash includes
builder-generated plan callback identities and structure. Keep the required callback behavior deployed.

---

## Vertique Objects Fit

Object-backed workflows should carry object references, not object document content.

Recommended state shape for an objects bridge:

```java
record ObjectWorkflowState(
    String objectTypeId,
    String objectId,
    long objectVersion,
    String transitionId,
    Map<String, Object> workflowContext
) {}
```

The workflow definition then orchestrates approval, publish, expiration, compensation, and escalation. Vertique Objects remains the source of truth for object fields, schema, lifecycle state, and versioning.

ObjectType bundles should reference workflow definition ids and versions through the objects workflow bridge. They should not need per-object Java workflow classes or embed object content inside workflow state.

---

## Required Wiring

```java
@Singleton
@Component(modules = {
    WorkflowCoreModule.class,
    WorkflowDefinitionModule.class,
    WorkflowPostgresqlModule.class,
    WorkflowServicesModule.class,
    TransactionalMessagingServiceModule.class,
    AppWorkflowDefinitionCallbacksModule.class
})
public interface AppComponent {
    WorkflowDefinitionService workflowDefinitionService();
    WorkflowOperations workflowOperations();
}
```

The component must bind a `WorkflowDefinitionRegistryStore` and a `WorkflowDefinitionLifecycle`: `WorkflowPostgresqlModule` binds the store and the engine module (installed through it) the lifecycle. There is no store-less mode; a component without them fails to build. `DefaultWorkflowDefinitionService` takes both and `WorkflowDefinitionBootstrap` takes the store through their constructors; the 0.3.0 constructors without them were removed.

Applications must also contribute at least one `WorkflowDefinitionSource` for bootstrap-loaded definitions, or call `WorkflowDefinitionService` from their own management surface. Add the generated `GeneratedWorkflowTypeCatalogModule` of each module that declares the contracts and types such documents use.

---

## Dependencies

- **workflow-core** - builder DSL, registry, plan model, callback ids, and workflow errors.
- **vertique-core** - common exception hierarchy.
- **jackson-databind** and **jackson-dataformat-yaml** - strict JSON/YAML parsing.
- **dev.cel:cel-runtime** - default expression engine, isolated behind `ExpressionProfile`.

---

## Invariants & Gotchas

- **Engine purity.** The runtime engine never parses YAML / JSON. Parsing, validation, and plan compilation happen in the control plane (bootstrap loaders and `WorkflowDefinitionService`). The engine only executes `WorkflowPlan` nodes + `WorkflowCallbackRegistry` callbacks.
- **Service-loaded documents are catalog-only.** `WorkflowDefinitionService.load` and the build of a stored document resolve names through the index only. A deployment where no catalog and no registered definition lists a class refuses a document that names it, even by its binary name, and loads nothing. Packaged documents are the trusted exception described above.
- **Trusted callers only; refusals are operator text.** `WorkflowDefinitionService.load` and `publish` take any scope key, so expose them only to trusted callers. A refusal message can name other scopes and plan hashes: log it for operators and never forward it to end users.
- **Document plans are not no-code.** A document references Java classes (state type, contract type, payload types) and named callbacks from the application's callback module. A document without the corresponding Java types is rejected.
- **Named conditions / mappers are looked up by id.** Document expression nodes resolve to callbacks registered in the application's `WorkflowDefinitionCallbacksModule`. Unknown ids fail at registration, not at first execution.
- **Plan-hash stability.** Documents compile to the same `WorkflowPlan` shape as the Java DSL, so existing instances pinned to a `planHash` resume against the same plan only if the document compiles to a byte-identical plan structure. Use migration handlers when changing semantics across versions. The hash covers the logical name of the state class (its `@WorkflowType` value, or its binary name when it has none), and a document restored from the store is hashed by the same name, so a restart accepts a document whose state class carries a logical name. A document that names the state class by its binary name therefore hashes the same as one that names it by its logical name, whichever source it comes from. `CompiledDefinition.stateTypeName()` and `contractName()` report those logical names.
