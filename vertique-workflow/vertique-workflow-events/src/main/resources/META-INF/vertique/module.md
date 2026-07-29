<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Workflow Events Module

> **Status:** Stable
> **Package:** `dev.vertique.workflow.events`
> **Artifact:** `vertique-workflow-events`
> **Depends on:** workflow-core, inbox-outbox-core, vertx-sql-client

`vertique-workflow-events` publishes workflow lifecycle events through the transactional outbox. Install it when external consumers need durable workflow events for projections, audit sinks, notifications, or analytics.

The event stream is separate from workflow history. History is the engine's internal ledger. Workflow events are the external integration contract.

---

## What It Provides

| Component | Purpose |
|---|---|
| Event side-effect recorder | Persists workflow event envelopes to the outbox inside the workflow transaction |
| Event outbox binding | Application-provided destination for event delivery |
| Compose validator | Ensures the selected destination has a registered outbox handler |

When the module is absent, workflow event emission is optional and the engine continues to function. Task reminders are the exception: workflows with reminders require event delivery because reminders are event-only side effects.

---

## Event Contract

Workflow events use a JSON envelope:

```json
{
  "schemaVersion": 1,
  "eventType": "WORKFLOW_STARTED",
  "workflowId": "00000000-0000-0000-0000-000000000000",
  "definitionId": "order-fulfillment",
  "occurredAt": "2026-05-13T12:00:00Z",
  "attributes": {}
}
```

Consumer rules:

- Route by `eventType`, not by Java enum types.
- Tolerate unknown `eventType` values.
- Tolerate unknown top-level fields.
- Treat `attributes` as event-specific data.

Adding fields or event types is intended to be non-breaking. Removing or renaming existing fields requires a new schema version.

---

## Required App Wiring

Applications must provide a `WorkflowEventOutboxBinding` and install an outbox destination handler for the selected destination type.

Kafka example:

```java
@Module
public abstract class AppModule {

    @Provides
    @Singleton
    static WorkflowEventOutboxBinding workflowEventBinding() {
        return new WorkflowEventOutboxBinding(DestinationType.KAFKA, "workflow.events");
    }
}

@Singleton
@Component(modules = {
    WorkflowCoreModule.class,
    WorkflowPostgresqlModule.class,
    WorkflowEventsModule.class,
    TransactionalMessagingPostgresqlModule.class,
    TransactionalMessagingKafkaModule.class,
    DbPostgresqlModule.class,
    DbFlywayModule.class,
    AppModule.class
})
public interface AppComponent {
    WorkflowOperations workflowOperations();
    WorkflowEventsComposeValidator workflowEventsComposeValidator();
}
```

Service destination example:

```java
@Provides
@Singleton
static WorkflowEventOutboxBinding workflowEventBinding() {
    return new WorkflowEventOutboxBinding(
        DestinationType.SERVICE,
        "workflow-event-projector.ingest");
}
```

For a service destination, install the module that provides the `SERVICE` outbox destination handler.

Expose and call `workflowEventsComposeValidator()` during startup if you want fail-fast validation before the first workflow operation constructs the engine graph.

---

## Extension Points

| Extension point | Purpose |
|---|---|
| `WorkflowEventOutboxBinding` | Chooses where workflow events are published |
| `OutboxDestinationHandler` | Delivers events after commit |
| `@WorkflowRecorders` | Receives the workflow event recorder |

Applications normally only provide the binding and destination handler.

---

## Operational Notes

- Events are published transactionally. If the workflow transition rolls back, no event outbox entry remains.
- Events are durable but asynchronous; consumers should expect at-least-once delivery from the outbox relay.
- Workflow history remains the source of truth for engine recovery. Events are for external consumption.
- If any workflow task declares reminders, install `WorkflowEventsModule` or startup validation fails.

---

## Invariants & Gotchas

- **Reminder-using workflows require this module.** If any registered workflow plan declares task reminders, the startup `WorkflowReminderComposeValidator` (package `dev.vertique.workflow.engine`, module `vertique-workflow-engine`) fails when `WorkflowEventsModule` is not installed. There is no silent reminder drop.
- **Optional intent kind.** When no application installs an event recorder and no plan requires it, `WORKFLOW_EVENT` is treated as an optional intent kind that no-ops cleanly rather than failing transitions.
- **History stays the engine's ledger.** Events are derived from history but are not a substitute for it. Recovery, retries, and engine decisions read history rows; consumers read the outbox-delivered event stream.
- **Envelope contract is versioned.** External consumers depend on the v1 event envelope contract; breaking changes require a new envelope version, not field-shape changes in place.

---

## Dependencies

- **workflow-core** - event envelope, event intent, and workflow side-effect recorder SPI.
- **inbox-outbox-core** - outbox service, destination type, and destination handler API.
- **vertx-sql-client** - SQL transaction type used by the recorder.
