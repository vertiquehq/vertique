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

This module does not compile-depend on `workflow-postgresql` or `inbox-outbox-services`. The application composes those modules at the Dagger boundary.

---

## Service Dispatch

When a workflow reaches a service-dispatch step, the engine emits a service side-effect intent. The services module records that intent as an outbox entry. The downstream service call happens later through the outbox relay, after the transaction commits.

Recorder behavior:

- Resolve the workflow target id through `ServiceTargetResolver`.
- Require the target to be a one-payload, `Future<Void>` service operation.
- Write an outbox entry with workflow correlation headers.
- Fail the workflow transaction on an unknown or invalid target.

The recorder must never make a live downstream call. The outbox row is the durable handoff.

---

## Signal Ingress

`WorkflowSignalContributor` registers the `workflow.signals.post` service contract. Service delivery uses inbox deduplication before calling `TransactionalWorkflowOperations.signal(...)` in the same SQL transaction.

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
carrier is bound as the *authoritative* durable-context base for the signal's drive — it is never
populated from untrusted or end-user input, only from the relay that produced it via
`DurableMetadata.toCarrier()`. The carrier's provenance is not independently authenticated against
the caller's identity — a future revision may derive the tenant namespace from the authenticated
`SecurityContext` instead of trusting it verbatim off the carrier.

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
    WorkflowOutboxComposeValidator workflowOutboxComposeValidator();
}
```

`TransactionalMessagingServiceModule` is required for the `SERVICE` outbox destination handler. Without it, service-dispatch intents cannot be delivered after commit.

Expose and call `workflowOutboxComposeValidator()` during application startup if you want fail-fast validation before serving requests. The recorder also enforces the same checks when the workflow graph is constructed.

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
