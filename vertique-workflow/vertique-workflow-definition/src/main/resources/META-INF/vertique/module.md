<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Workflow Definition Module

> **Status:** Stable
> **Package:** `dev.vertique.workflow.definition`
> **Artifact:** `vertique-workflow-definition`
> **Depends on:** workflow-core, vertique-core, jackson-databind, jackson-dataformat-yaml, dev.cel:cel-runtime

`vertique-workflow-definition` adds YAML/JSON workflow authoring on top of `workflow-core`. A definition document is parsed, validated, compiled into the same `WorkflowPlan` used by code-first definitions, and registered with `WorkflowRegistry`.

The runtime engine never parses definition files. Parsing and validation happen in the control plane, either at bootstrap or through `WorkflowDefinitionService`.

This module currently provides **typed document-backed workflows**, not fully no-code workflows. The document defines orchestration. Java still supplies the state type, contract type, start payload type, payload classes, and named callbacks referenced by the document.

---

## When To Use It

Use this module when workflow shape should be data-driven but the application still owns typed Java integration code.

Good fits:

- Environment- or tenant-specific orchestration assembled from registered callbacks.
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
  -> WorkflowRegistry.register(...)
```

`DefinitionResource` contains bytes, format, and source metadata. The source may be a repository file, CMS record, database row, REST upload, object store, or generated artifact. Source location is provenance metadata; it does not affect engine execution or plan identity.

`WorkflowDefinitionBootstrap` loads contributed `WorkflowDefinitionSource` resources during registry construction. Runtime management surfaces can call `WorkflowDefinitionService.load(...)` and `activate(...)` directly.

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
| `stateType` | yes | Java class name for workflow state |
| `contract` | yes | Java interface annotated with `@WorkflowContract` |
| `startPayloadType` | yes | Java class name for the start payload |
| `initialStateMapper` | yes | Named callback that maps start payload to initial state |
| `subjectResolver` | no | Named callback that derives `WorkflowSubjectRef` from state |
| `initialStep` | yes | Step id where execution begins |
| `steps` | yes | Non-empty list of step definitions |

The `contract` annotation values must match the document `definitionId` and `definitionVersion`.

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
- Java class names must resolve on the application classpath.
- The contract class must be annotated with `@WorkflowContract`, and its id/version must match the document.
- Named callbacks must resolve through the registered callback registries.
- Decision expressions must parse through the expression profile.
- Step ids must be unique, reachable from `initialStep`, and all step references must point to existing steps.
- The compiled plan is registered through the normal `WorkflowRegistry` path, so core plan validation still applies.

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

## Runtime Loading And Activation

`WorkflowDefinitionService` returns Futures for loading, activation, metadata listing, and source
attribution. For a global definition, compose activation after the candidate load completes:

```java
Future<CompiledDefinitionRef> activated = service
        .load(content, DocumentFormat.YAML, metadata, null)
        .compose(ref -> service.activate(new DefinitionKey(
                null, ref.definitionId(), ref.definitionVersion())).map(ref));

Future<List<WorkflowDefinitionRecord>> definitions = service.list(
        new DefinitionFilter(DefinitionFilter.TenantScope.GLOBAL, null, null));
```

`load` parses, validates, and compiles a candidate without registering it. `activate` publishes it;
a failed load leaves the prior active version untouched. With PostgreSQL installed, source and
metadata are durable and activation recompiles the stored text rather than trusting a local cache.
The managed application lifecycle restores activated global documents before any BOOTSTRAP verticle
deploys. After source verification and active-membership recomputation, it rereads committed status and
provenance and delivers fresh metadata to each already-verified registration through `onRestored`; the
cache therefore reflects the promoted and demoted versions before traffic starts. The callback is
idempotent and a failure aborts startup. A missing loader or stored/compiled hash mismatch also aborts
startup. All nodes sharing live documents must have the appropriate loader and application callback/type
catalog. Source URIs are never fetched. Without a store, these operations retain their in-memory outcomes
through the asynchronous signatures.

`deprecate(DefinitionKey)` moves an ACTIVE or SUPERSEDED version to DEPRECATED durably, marks it in the
local registry and reports DEPRECATED in service metadata and lists. New starts of that version and
migrations to it are refused with reason `DEPRECATED`, including a retry of an explicit start under an
idempotency key used before the deprecation; instances already pinned to it keep running on the node that
marked it. A restart does not yet restore deprecation: a deprecated version that was loaded at runtime is
not registered again, so an instance pinned to it cannot resume until restoration is available, and a
deprecated version that is also registered from a packaged source comes back registered and unmarked, so
it is served and startable again and startup recomputation may then disagree with the registry about the
current version. Deprecating the active version does not promote
another, so a durable listing can show no ACTIVE version until the next startup recomputation, while
current resolution already serves the next lower version. It requires the durable registry: without one,
the returned Future fails.

Startup restores global documents only; a document activated under a tenant key stays in the database,
holding its id against other owners, but is not registered again after a restart. `load`, `activate` and
`deprecate` perform no authorization: restrict them to authorized callers. Exclusivity of an id between a
document and a code-first definition is not enforced by the registry yet, so do not load a document under an
id that code already registers.

`sourceMetadata(DefinitionKey)` reads attribution without fetching source text. Lists contain immutable,
key-bearing metadata. Definition id (255), source type (64) and revision, author and upload id (255)
code-point limits apply in every mode, including when no store is installed, and over-limit input is
rejected before anything is registered. Invalid asynchronous
arguments are failed Futures. Previously started instances keep their pinned version. Each coexisting
document version needs its own contract interface class because registry contract mappings use that class.

### Source Text And Size Policy

`WorkflowDefinitionsConfig` supplies runtime input limit L (default 262144 bytes). Install the opt-in
`WorkflowDefinitionsConfigModule` with the canonical `ConfigParsingModule` to parse
`workflow.definitions.maxDocumentSizeBytes` and `maxStalenessSeconds`, or bind the typed config directly.
The default applies without a parser binding. The YAML scanner's code-point limit is L; nesting,
expression limits, and collection-alias settings remain independent.

| Source path | Admission |
|---|---|
| Runtime `load`, with or without a store | Original input at most L bytes, checked before parsing |
| Static source without a store | Existing byte parser; YAML code-point guard L, no added JSON byte cap or source decoder |
| Static source with a store | Existing pipeline first, then strict whole-source conversion and stored-text envelope; no runtime raw-byte cap |
| Durable restore | Guarded stored text, at most floor(3L/2) UTF-8 bytes and L Unicode code points; no raw-L restore cap |

Durable storage preserves decoded source characters, comments, whitespace, and line endings, rather
than original encoded bytes or a reserialized document. JSON retains the existing UTF-8/16/32 reader
support and consumes its recognized leading encoding marker; YAML remains UTF-8 and preserves its
reader's leading marker character. Strict conversion checks the whole durable source after the
ordinary pipeline succeeds: malformed encoding, actual decoded NUL, and unpaired surrogates fail
before persistence. Escaped source sequences remain source text. No additional trailing-token policy
is enabled, so this representation check can reject malformed trailing bytes that a no-store parser
previously ignored.

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

Applications must also contribute at least one `WorkflowDefinitionSource` for bootstrap-loaded definitions, or call `WorkflowDefinitionService` from their own management surface.

---

## Dependencies

- **workflow-core** - builder DSL, registry, plan model, callback ids, and workflow errors.
- **vertique-core** - common exception hierarchy.
- **jackson-databind** and **jackson-dataformat-yaml** - strict JSON/YAML parsing.
- **dev.cel:cel-runtime** - default expression engine, isolated behind `ExpressionProfile`.

---

## Invariants & Gotchas

- **Engine purity.** The runtime engine never parses YAML / JSON. Parsing, validation, and plan compilation happen in the control plane (bootstrap loaders and `WorkflowDefinitionService`). The engine only executes `WorkflowPlan` nodes + `WorkflowCallbackRegistry` callbacks.
- **`CONTRACT_MISMATCH` is enforced at load time.** A document claiming `definitionVersion = 2` against a contract annotated `definitionVersion = 1` fails the validator with a clear violation, not silently at runtime.
- **Document plans are not no-code.** A document references Java classes (state type, contract type, payload types) and named callbacks from the application's callback module. A document without the corresponding Java types is rejected.
- **Named conditions / mappers are looked up by id.** Document expression nodes resolve to callbacks registered in the application's `WorkflowDefinitionCallbacksModule`. Unknown ids fail at registration, not at first execution.
- **Plan-hash stability.** Documents compile to the same `WorkflowPlan` shape as the Java DSL, so existing instances pinned to a `planHash` resume against the same plan only if the document compiles to a byte-identical plan structure. Use migration handlers when changing semantics across versions.
