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
| Compose validator | Ensures the selected destination has a registered outbox handler, and refuses a `DELAYED_JOB` destination |

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
  "definitionVersion": 1,
  "definitionScopeKey": "default",
  "scopeKey": "default",
  "occurredAt": "2026-05-13T12:00:00Z",
  "attributes": {}
}
```

Consumer rules:

- Route by `eventType`, not by Java enum types.
- Tolerate unknown `eventType` values.
- Tolerate unknown top-level fields.
- Treat `attributes` as event-specific data.
- Filter or route by scope with `definitionScopeKey` (the scope of the definition the instance is pinned to) and `scopeKey` (the scope the instance was started in). Both keys are plain labels, not an isolation or authorization boundary.
- Read `definitionScopeKey` and `scopeKey` as optional: envelopes written before the fields existed carry neither, and they read as `null`.

The example shows the principal fields. An event can also carry `subjectRef` (the instance's subject, with `type`, `id` and an optional `version`), `businessKey`, `sequence` (the workflow history sequence number at emission), `taskId` (task events), `stepId` (the plan step that produced the event), `correlationId` and `causationId` (see Invariants & Gotchas).

The outbox row is written in the scope the instance was started in: `scope_key` carries it alongside the envelope's `scopeKey`. The workflow client's `.scope(...)` or `StartCommand.startScope` selects that scope, and an instance started without one is in `default`. The scope is a label, not authorization: nothing checks it at publish, by id, or under a service principal. Row scope and the ambient `ScopeContext` are independent, so a handler that partitions data chooses one source, compares the other when both are present, fails on a mismatch, and authorizes access itself.

The two scope fields were added without changing `schemaVersion`. Adding fields or event types is intended to be non-breaking. Removing or renaming existing fields requires a new schema version.

### Event Types

`eventType` is currently one of the following strings. Each is the name of a constant of `WorkflowEventType`, which the engine uses to label what it emits; the envelope itself carries the string.

| `eventType` | Emitted when |
|---|---|
| `WORKFLOW_STARTED` | The instance is created and its first step is reached |
| `WORKFLOW_COMPLETED` | The instance reaches a complete node |
| `WORKFLOW_FAILED` | The instance reaches a fail node, or fails |
| `WORKFLOW_CANCELLED` | The instance is cancelled by an operator or an API call |
| `WORKFLOW_COMPENSATING` | Compensation starts and the instance becomes `COMPENSATING` |
| `WORKFLOW_COMPENSATED` | Every compensation step is recorded and the instance becomes `COMPENSATED` |
| `TASK_CREATED` | A human task is created and the workflow waits for an actor's decision |
| `TASK_COMPLETED` | An actor's decision completes the task |
| `TASK_CANCELLED` | A task is cancelled; `attributes.cause` is `WORKFLOW_CANCELLED` or `BRANCH_CANCELLED`; when the cause is `BRANCH_CANCELLED`, `attributes.branchId` names the branch that owned the task |
| `TASK_EXPIRED` | A task's due-date timer fires before the task is completed |
| `TASK_REASSIGNED` | A task's assignment changes to another user, role or queue |
| `TASK_REMINDER` | A task reminder timer fires; no workflow state changes |

`WORKFLOW_EXPIRED` is a reserved name that the engine does not emit.

`TASK_REMINDER` carries `reminderIndex`, `isLast`, and `kind` in `attributes`. `kind` is one of two reminder kinds:

| `attributes.kind` | Reminder mode | Further attribute |
|---|---|---|
| `ONE_SHOT_OFFSET` | One timer per declared offset from task creation | `offsetFromCreation`, an ISO-8601 duration |
| `RECURRING` | One timer per interval, the next scheduled as each fires | `interval`, an ISO-8601 duration |

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

The `VALIDATE` startup phase runs `WorkflowEventsComposeValidator` with every other compose validator, so the component needs no accessor for it. A destination with no registered handler fails startup there, and so does a `DELAYED_JOB` destination: a delayed job needs a handler name and schedule per delivery that the binding does not carry. Use `SERVICE` or `KAFKA`.

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

- **Reminder-using workflows require this module.** If any registered workflow plan declares task reminders, the per-plan `WorkflowReminderComposeValidator` (package `dev.vertique.workflow.engine`, module `vertique-workflow-engine`) refuses that plan, aborting startup for a code plan, when `WorkflowEventsModule` is not installed. There is no silent reminder drop.
- **Optional intent kind.** When no application installs an event recorder and no plan requires it, `WORKFLOW_EVENT` is treated as an optional intent kind that no-ops cleanly rather than failing transitions.
- **History stays the engine's ledger.** Events are derived from history but are not a substitute for it. Recovery, retries, and engine decisions read history rows; consumers read the outbox-delivered event stream.
- **Correlation and cause.** Every event carries `correlationId`, the correlation id the instance captured when it started (absent
  when it captured none), and `causationId`, the row id of the command whose segment emitted the event, as text. An event written
  outside a command's segment, such as the events of `cancel`, has no `causationId`. A signal applied from the parked state is the
  cause of its own events.
- **Branch-owned tasks are reported like the tasks of a single path.** A task created by a fork branch emits `TASK_CREATED`,
  completing it emits `TASK_COMPLETED`, its due date passing emits `TASK_EXPIRED`, and cancelling it (with its branch, or when
  the branch loses a race) emits `TASK_CANCELLED` with cause `BRANCH_CANCELLED`. Each event carries the sequence of the history
  entry written with it; the cancellation of a branch's task writes a `TASK_CANCELLED` history entry, which it did not before,
  and its event carries `attributes.branchId`, the branch id of the branch that owned the task.
  **Behaviour change:** consumers that counted the task events of an instance with a fork now see these four for branch tasks.
- **Event text is not redacted.** The `reason` attribute of `WORKFLOW_CANCELLED` and the `errorMessage` attribute of `WORKFLOW_FAILED` carry the
  text the engine holds. The history entry of the same transition stores that text after the history redactor has replaced the values at its
  pointers, so a value the application hides in history can still reach an event consumer. Keep text that must stay hidden out of the cancel
  reason and the fail node's message.
- **Attribution headers.** The outbox entry of an event a workflow segment emits carries only the attribution header `x-workflow-actor-kind`, taken from the command the segment executes; the actor id and reason are not sent to event consumers. They are for audit and logs. They are unauthenticated: anyone who can write an outbox record or publish to the broker can forge them, so a consumer must never treat them as the identity of the caller. The envelope is unchanged.
- **Envelope contract is versioned.** External consumers depend on the v1 event envelope contract; breaking changes require a new envelope version, not field-shape changes in place.

---

## Dependencies

- **workflow-core** - event envelope, event intent, and workflow side-effect recorder SPI.
- **inbox-outbox-core** - outbox service, destination type, and destination handler API.
- **vertx-sql-client** - SQL transaction type used by the recorder.
