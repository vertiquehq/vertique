<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Workflow Services Module

> **Status:** Stable
> **Package:** `dev.vertique.workflow.services`
> **Artifact:** `vertique-workflow-services`
> **Depends on:** workflow-core, services, inbox-outbox-core, vertx-sql-client

`vertique-workflow-services` connects workflow service-dispatch steps to Vertique's transactional outbox and exposes a service contract for posting workflow signals.

Install this module when workflows dispatch service work or when service consumers need to signal workflow instances.

---

## What It Provides

| Component | Purpose |
|---|---|
| Service side-effect recorder | Persists `SERVICE` workflow intents to the outbox inside the workflow transaction |
| Signal service contributor | Registers the `workflow.signals.post` service contract |
| Composition validator | Fails startup when workflow service targets or outbox handlers are miswired |
| Source delivery bean | `WorkflowSourceDelivery` records a correlated signal for a signal source, under the identity its envelope names, and runs a whole source plan to one disposition per message (see "Source Delivery" and "Source Plans") |

This module does not compile-depend on `workflow-postgresql` or `inbox-outbox-services`. The application composes those modules at the Dagger boundary.

---

## Service Dispatch

When a workflow reaches a service-dispatch step, the engine emits a service side-effect intent. The services module records that intent as an outbox entry. The downstream service call happens later through the outbox relay, after the transaction commits.

Recorder behavior:

- Resolve the workflow target id through `ServiceTargetResolver`.
- Require the target to be a one-payload, `Future<Void>` service operation.
- Write an outbox entry with workflow correlation headers: `x-workflow-id`, `x-workflow-step-sequence`, `x-workflow-definition-id`, `x-workflow-definition-scope` (the scope of the definition the instance is pinned to), `x-workflow-scope` (the scope the instance was started in), `x-workflow-step-id`, and the branch identity headers for a branch dispatch. A correlation header replaces an intent header of the same name.
- Write the outbox row in the scope the instance was started in: `scope_key` carries it alongside `x-workflow-scope`. The workflow client's `.scope(...)` or `StartCommand.startScope` selects that scope, and an instance started without one is in `default`. A target reads it from `TransactionalMessageContext.scopeKey()`. The scope is a label, not authorization: nothing checks it at publish, by id, or under a service principal. Row scope and the ambient `ScopeContext` are independent, so a handler that partitions data chooses one source, compares the other when both are present, fails on a mismatch, and authorizes access itself.
- Fail the workflow transaction on an unknown or invalid target.
- Treat a `null` intent header map as no headers, and fail the returned future with an `IllegalArgumentException` naming the key when an intent header has a `null` key or value.

The recorder must never make a live downstream call. The outbox row is the durable handoff.

---

## Signal Ingress

`WorkflowSignalContributor` registers the `workflow.signals.post` service contract. Service delivery uses inbox deduplication before recording the signal in the same SQL transaction through `RelayedSignals<SqlClient>` (`vertique-workflow-core`), which the engine binds. `RelayedSignals` is the public `signal` form of `TransactionalWorkflowOperations` with the same transaction guard, branch guards, carrier sanitization and checks, minus one: it is never refused by the intake limit (`WorkflowIntakeRefusedException`). The signal is already durable in the outbox, and a refusal would only spend delivery attempts until it was dead-lettered while the queue it would join is the one that must drain. The public constructor that takes `Provider<TransactionalWorkflowOperations<SqlClient>>` stays and adapts the operations it receives, so the signals it records go through the public forms, which the limit applies to; the dependency graph uses the constructor that takes `Provider<RelayedSignals<SqlClient>>`.

The endpoint's reply means the signal is **recorded**, not applied: `signal` records a `SIGNAL` command of the instance and returns,
and an executor applies it afterwards in queue order. A signal the instance does not await yet is parked and applied when the instance
reaches a wait that accepts it. The call is refused only for what recording refuses: a missing or ended instance, a name the instance's
pinned definition does not declare for the addressed wait, a payload or carrier over the record-time bounds, or an instance that already
holds `workflow.recording.maxOpenSignalsPerInstance` open signals (`WorkflowSignalLimitException`). It is never refused because the
cluster holds too many due commands: an application that signals through the outbox passes the intake limit, and its backlog is bounded
by the relay and not by the limit. A refusal rolls the inbox record back
with the transaction, so the relay retries it.

**Who is logged.** The endpoint records the signal inside the dispatch context the relay rebuilt for the delivery, and the engine
logs the signal's actor from the identity of that context (see "Who asked for a command" in the engine module reference). The
actor is never read from `WorkflowSignalRequest`, its payload or its carrier. By default the relay's identity is `SYSTEM` with the
outbox row's event type as the reason; a bound `ServiceIdentityResolver` names a service instead. Either acts for the user whose
transaction wrote the outbox row (`on_behalf_of`). A snapshot that cannot be verified refuses the delivery under the default
degradation policy, so no command, dedup or inbox row is written; under `CONTINUE_WITHOUT_IDENTITY` the signal is logged `DEGRADED`
and acts for no one. A graph without identity carriage logs the signal as `NONE`.

Signal dedup happens at two layers:

| Layer | Scope | Purpose |
|---|---|---|
| Inbox dedup | Transport delivery | Handles at-least-once outbox relay retries |
| Workflow dedup | Workflow instance | Handles duplicate logical signals |

The inbox message id must include workflow identity. A caller-provided signal dedup key alone is not globally unique.

### Explicit Durable-Context Carrier

`WorkflowSignalRequest` carries an additional `@Nullable JsonObject metadata` field — the raw
`{"context": …}` carrier document produced by `DurableMetadata.toCarrier()`. All existing factory
overloads (`instance(...)`, `branch(...)`) are preserved; new overloads accept the carrier alongside
the original arguments. A `null` carrier keeps every existing call site's behavior unchanged.

`WorkflowSignalContributor.handleSignal` decodes a non-null carrier via
`DurableMetadata.fromCarrier(...)` **inside** the `InboxService.processOnce` work supplier — not
before `pool.withTransaction` is entered — and then calls the metadata-aware 8-arg
`TransactionalWorkflowOperations.signal` overload instead of the legacy 7-arg one. This placement
has two consequences:

- **Decode failure is a failed future, not a thrown exception.** A malformed `context` section or
  namespace body raises `MalformedDurableMetadataException` (a `ValidationException` subtype),
  which propagates through the normal async error-handling path.
- **First-delivery decode failure rolls back the inbox insert.** Because the decode runs inside the
  same transaction as the inbox insert, a poison message on its first delivery never commits a dedup
  record — every redelivery re-decodes and re-fails identically. An **already-committed** duplicate
  (whose first delivery succeeded) is skipped by the dedup check before decode is attempted, so a
  legitimate duplicate delivery of a previously-successful carrier is never re-decoded.

**Security.** `WorkflowSignalEndpoint.post` is an internal-relay-only endpoint. The `metadata`
carrier is stored with the recorded signal, without its identity-snapshot namespace, and the executor binds it as the *authoritative* durable-context base for the signal's drive — it is never
populated from untrusted or end-user input, only from the relay that produced it via
`DurableMetadata.toCarrier()`. The carrier's provenance is not independently authenticated against
the caller's identity — a future revision may derive the tenant namespace from the authenticated
`SecurityContext` instead of trusting it verbatim off the carrier.

---

## Source Delivery

`WorkflowSourceDelivery` is a singleton bean that records one correlated signal for a signal source, in process. It is added to the graph by `WorkflowSourceModule`, which `WorkflowServicesModule` includes. A node that only receives signals installs `WorkflowSourceModule` alone: the module needs `TransactionalWorkflowOperations<SqlClient>`, `Pool`, `Vertx` and `InboundExecutionContextScope`, and no outbox or inbox module.

**INTERNAL.** `WorkflowSourceDelivery`, `SourceEnvelope`, `SourceDisposition`, `SignalSourcePlan` and `AbstractSignalSourceBuilder` are internal seams, outside the maturity promise of `vertique-workflow-services`. They are public only because a transport in another repository needs them; application code does not depend on them. The stage interfaces (`SignalStages`, `SourceStages`, `SignalTargetStage`, `KeyedTargetStage`) carry no such mark: they are the surface a transport's own builder exposes.

The caller passes resolved values only, in a `SourceEnvelope`: the transport (`kafka` or `camel`), the `SecurityContext` to record under, an optional `CorrelationContext`, the `InvocationOrigin`, and a function from a signal's resolved dedup key to its `SignalProvenance`. For each signal the bean:

- creates its own duplicated Vert.x context, from the root of the context it was called on, so a value bound on the caller's context never reaches the recording;
- installs exactly the envelope's security context, correlation (when there is one) and origin on that context through `InboundExecutionContextScope.installDispatch`, so the recorded command's actor comes from the envelope's security context, as it does for every recording, and two deliveries in flight never see each other's values;
- runs `signalByKey` or `signalByBusinessKey` in its own transaction, with the provenance built for the signal's own dedup key;
- closes the binding when the recording's future completes, whether it succeeded or failed, and also when the operations throw synchronously.

The returned future completes on the context the caller delivered from, after the binding is closed, and a failure from the operations reaches the caller as the same exception instance. Nothing blocks, and the bean logs no key, header or payload.

`admitting()` answers whether this node accepts new signals now, from the transactional operations. A transport that can hold a message back asks it before it delivers one: the Kafka source's dispatch gate does, so a node that refuses intake keeps its records in the topic and spends no retry.

`report(SignalSourceEvent)` tells every `SignalSourceObserver` in the `Set<SignalSourceObserver>` multibinding about an outcome. It isolates each observer: an `Exception`, `LinkageError`, `AssertionError` or `StackOverflowError` from one observer is reported by observer class and callback name, never by its message, and does not stop the others or reach the caller. A transport reports its own outcomes through it. `WorkflowSourceModule` declares the set, so an application with no observer still resolves the bean.

The bean adds no service operation and no event-bus address: only code in the application's JVM can call it. The only service entry of this module stays `workflow.signals.post`.

---

## Source Plans

A `SignalSourcePlan<T>` describes a transport-neutral signal source, and `WorkflowSourceDelivery.deliver(plan, message, envelope)` runs it for one message and answers one `SourceDisposition` (`DONE`, `RETRY`, `WAIT` or `TERMINAL`, with the type of the failure that decided it). A plan is immutable. Obtain a plan builder from `SignalSourcePlan.builder(sourceName, messageType)`; a transport instead extends `AbstractSignalSourceBuilder<T, SELF, R>` with its own builder as `SELF`, adds only its own stages, and implements `build()` as `plan(sourceName)`, so every transport applies one set of construction checks. The plan exposes `sourceName()`, `lookupTimeoutMs()` and `restoresCorrelationFromHeaders()` for a transport that builds the envelope.

**Stages.** `SignalStages<T, SELF, R>` declares `when`, `scope`, `anyScope`, `dedupBy`, `lookupTimeoutMs`, `dispatchAs`, `restoreCorrelationFromHeaders`, `signal(contract, name)` and `build()`. `SourceStages` adds `onNoMatch(policy)`, the source's default. Each `signal` is followed by exactly one key source (`key`, `keyFrom`, `businessKey`, `businessKeyFrom`) and an optional `payload`; the `KeyedTargetStage` that follows offers no second key source and declares its own `onNoMatch` for that signal alone. A stage written after the first `signal` applies to the whole source.

**Construction rules.** `build()` fails with an `IllegalStateException` that names the source, never a key, for a signal with no key source, a message type that differs from the signal's parameter type with no `payload`, a business-key signal with no `dedupBy`, `scope` beside `anyScope`, a `lookupTimeoutMs` below 1 (the default is 5000), and a signal the contract does not declare. A source with no stated message type needs a `payload` on every signal. `build()` is idempotent: it returns the same plan, and a stage called after it fails.

**Per-message order.** `deliver` runs `when`, then the dedup key (`dedupBy`, else the envelope's default), so an excluded or blank-keyed message never reaches identity; then `dispatchAs` unless the envelope's identity was contributed (an empty answer keeps the envelope's identity); then `scope`; then each signal in turn: its key, its payload, and its recording. A message's signals run one at a time, every signal is attempted, and the message's fate is the most severe of them: `TERMINAL` over `RETRY` over `WAIT` over `DONE`.

**Threading.** `deliver` captures its calling context once and completes on it. Every synchronous stage (`when`, `scope`, `key`, `businessKey`, `dedupBy`, `payload`) and every lookup (`keyFrom`, `businessKeyFrom`) runs on a fresh duplicate of that context with the envelope's correlation and origin bound, and the scope bound for the key stages, and no identity. `dispatchAs` runs on its own duplicate and binds nothing. An application future may complete on another context, so `deliver` returns to the captured context before it starts the next stage. `dispatchAs` and each lookup are bounded by `lookupTimeoutMs`, and a timeout counts as a failed stage. Stage functions run on the event loop and must not block.

**Outcomes and fates.** Each signal reports one `SignalSourceEvent` through `report`, and a stage that ends the message before a signal is chosen reports once with null contract and signal names.

| Cause | Outcome | Fate |
|---|---|---|
| Recorded | `APPLIED` | `DONE` |
| No match | `NO_MATCH` | `DONE`, or `RETRY` under the signal's policy |
| More than one match | `AMBIGUOUS` | `TERMINAL` |
| Duplicate receipt | `DEDUPLICATED` | `DONE` |
| `when` rejects; a key that is null or empty, or a lookup answering empty | `IGNORED` | `DONE` |
| `scope` answers empty | `SCOPE_EMPTY` | `DONE` |
| Anonymous identity, whether the envelope carried it or `dispatchAs` answered it, `WorkflowSignalActorException`, or `SignalSourceRefusal` from `dispatchAs`, also when wrapped in another exception | `REFUSED_IDENTITY` | `TERMINAL` |
| A key, scope or payload the type or database refuses (blank, over 255 characters, NUL, SQLSTATE class `22`); a payload that cannot be encoded as JSON; a null or blank dedup key | `INVALID_INPUT` | `TERMINAL` |
| `WorkflowSignalLimitException` | `LIMITED` | `RETRY` |
| `WorkflowIntakeRefusedException` | `LIMITED` | `WAIT` |
| `WorkflowInvalidPayloadException` (oversized payload) | `OVERSIZED` | `TERMINAL` |
| A stage that throws before it returns | `STAGE_FAILED` | `TERMINAL` |
| A lookup or `dispatchAs` future that fails or times out; any other failure, such as a database outage | `STAGE_FAILED` | `RETRY` |

`WAIT` means the message is fine and the system is busy: a transport delivers it again without spending a retry, or treats it as `RETRY` when it has no way to wait.

`UNDECODABLE` is raised and reported by a transport itself. A failed stage is forwarded by exception class only, a `SignalSourceRefusal` by its `errorType()` alone, a refused input by the refusing exception's class, and a blank dedup key as `BlankDedupKey`; a message, which can hold the key, is never logged, put in a disposition or put in an event.

**Identity log.** When the identity in force is not a system identity and a message ends without recording a command, at any stage and for any outcome except `APPLIED`, `DEDUPLICATED` and `IGNORED` (no match, ambiguity, a limit, a refusal, an empty scope, a refused key, scope or payload, or a failure), `deliver` writes one log record naming the actor id, contract, signal and outcome, because no command row then shows who attempted it. An outcome reached before a signal is chosen names no contract or signal. The actor id has control and line-separator characters removed and is cut to 255 code points; the key and the message are never logged. A system identity writes none.

---

## Required App Wiring

An app that uses service-dispatch workflows needs the workflow services module plus transactional messaging modules:

```java
@Singleton
@Component(modules = {
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
}
```

`TransactionalMessagingServiceModule` is required for the `SERVICE` outbox destination handler. Without it, service-dispatch intents cannot be delivered after commit.

`WorkflowOutboxComposeValidator` is a per-plan `WorkflowDefinitionValidator` that `WorkflowServicesModule` contributes into the registry's validator set, so the registry runs it once for every plan it builds, at startup and for a definition loaded or published later. A plan that dispatches to a service (a service-dispatch or compensation step) is refused when no `SERVICE` outbox handler is bound, or when a target id does not resolve or has the wrong shape (`@OneWay`, a return type other than `Future<Void>`, other than one payload parameter). A graph whose plans dispatch to no service starts without a `SERVICE` handler. A code plan that fails a check aborts startup with an `IllegalStateException` naming the definition, step and target; a document that fails one is refused at publication with the same text. The validator is no longer a `ComposeValidator`, no accessor is needed on the component, and `OutboxSideEffectRecorder` no longer takes it as a constructor dependency. Its public constructor takes the outbox destination handlers and the `ServiceTargetResolver`.

---

## Extension Points

| Extension point | Purpose |
|---|---|
| `@WorkflowRecorders` | Receives the service side-effect recorder |
| `ServiceContractContributor` | Receives the workflow signal service contract |
| `ServiceTargetResolver` | Resolves stable workflow service target ids |
| `OutboxDestinationHandler` | Delivers outbox entries after commit |

Applications normally provide service contracts and destination handlers; this module provides the workflow bridge.

---

## Operational Notes

- Service dispatch is transactional. If the workflow transaction rolls back, no downstream service call is published.
- Unknown or shape-incompatible service targets fail startup or the workflow transition; there is no best-effort relay.
- The module boundary is intentionally narrow. `workflow-services` consumes core SPIs and messaging-core APIs, but the app decides which persistence and relay modules are installed.

---

## Dependencies

- **workflow-core** - workflow side-effect recorder SPI and transactional signal operations.
- **services** - service contract contribution and target resolution.
- **inbox-outbox-core** - outbox and inbox APIs.
- **vertx-sql-client** - `SqlClient` appears in public integration signatures.
