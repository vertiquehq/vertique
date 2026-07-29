<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Workflow Tasks Module

> **Status:** Stable
> **Package:** `dev.vertique.workflow.tasks`
> **Artifact:** `vertique-workflow-tasks`
> **Depends on:** workflow-core

`vertique-workflow-tasks` provides the application-facing API for human tasks created by workflows. When the engine reaches a human-task step, it creates a task row and suspends the workflow instance. This module lets application code list, read, complete, and reassign those tasks.

The module is intentionally thin. It contains no SQL implementation and no workflow mutation logic. Storage and engine callbacks come from the installed persistence module.

---

## What It Provides

| API | Purpose |
|---|---|
| `TaskService` | Application-facing service that opens its own transaction |
| `TransactionalTaskService<TX>` | Same operations inside a caller-provided transaction |
| `WorkflowTasksModule` | Dagger bindings for the task services |
| Compose validator | Optional startup check that required task bindings exist |

The engine still owns state transitions. Completing or reassigning a task delegates to engine callbacks so workflow state, history, timers, and idempotency are updated consistently.

---

## Task API

```java
public interface TaskService {
    Future<PagedResult<TaskRecord>> list(TaskFilter filter, PageCursor cursor);
    Future<Optional<TaskRecord>> get(UUID taskId);
    Future<Void> complete(TaskCompletionCommand cmd);
    Future<Void> reassign(TaskReassignmentCommand cmd);
}
```

Use `TransactionalTaskService<TX>` when task work must participate in a larger transaction.

Task commands require an idempotency key. A retry with the same key and same logical payload succeeds as an idempotent retry. Reusing the same key with a different logical payload fails with an idempotency conflict.

---

## Defining Human Tasks

Human tasks are declared in workflow definitions through `workflow-core`:

```java
wf.task("review")
  .assignToRole("editors")
  .requireVersionStability()
  .decision("approve", ApprovalPayload.class)
      .onDecision((state, payload) -> state.approve(payload.approvedBy()))
      .toStep("publish")
  .decision("reject", RejectionPayload.class)
      .onDecision((state, payload) -> state.reject(payload.reason()))
      .toStep("rejected")
  .dueIn(Duration.ofDays(3))
  .onDue(state -> state.markReviewOverdue())
  .toStepOnDue("escalate")
  .build();
```

Assignment can be literal or resolved from workflow state. Decisions carry typed payloads and update workflow state before moving to their next step. Use `void.class` as the payload type to declare a decision that carries no payload. Due dates and reminders require the delayed workflow module.

Version-stable tasks compare the reviewed subject version supplied by the caller with the version snapshotted when the task was created. This is useful for approval flows over mutable domain objects.

---

## Required App Wiring

```java
@Singleton
@Component(modules = {
    WorkflowCoreModule.class,
    WorkflowPostgresqlModule.class,
    WorkflowTasksModule.class,
    WorkflowServicesModule.class,
    TransactionalMessagingPostgresqlModule.class,
    TransactionalMessagingServiceModule.class,
    DbPostgresqlModule.class,
    DbFlywayModule.class,
    AppModule.class
})
public interface AppComponent {
    TaskService taskService();
    WorkflowTasksComposeValidator workflowTasksComposeValidator();
}
```

Add `WorkflowDelayedModule`, `DelayedJobModule`, and persistent cron wiring when human tasks use due dates or reminders.

Expose and call `workflowTasksComposeValidator()` during startup if you want fail-fast validation before serving requests.

---

## Extension Points

| Extension point | Purpose |
|---|---|
| `TaskStore<TX>` | Persists task rows; supplied by `workflow-postgresql` |
| `TransactionalTaskCallbacks<TX>` | Engine mutation callbacks used by task completion and reassignment |
| `TaskService` | Application API for task inboxes and task actions |
| `TransactionalTaskService<TX>` | Transaction-aware task API |

Application code should use `TaskService` or `TransactionalTaskService<TX>`. It should not call `TransactionalTaskCallbacks<TX>` directly.

---

## Operational Notes

- Human task completion and reassignment are idempotent when callers reuse the same idempotency key for the same logical command.
- A task with a due date depends on workflow timer infrastructure.
- Transactions that touch timers, tasks, and workflow instances must follow the lock order enforced by the engine and persistence module.
- Authorization is application-defined. The workflow engine records the actor supplied on the task command but does not decide whether that actor may act.

---

## Invariants & Gotchas

- **All-or-nothing due-date triplet.** A task step's optional due-date sub-chain (`.dueDate(...)`, `.onDueMutator(...)`, `.dueNextStepId(...)`) must specify all three or none. Partial triplets are rejected at construction by the `HumanTaskNode` compact constructor.
- **Non-empty unique decisions.** Each task step must declare at least one decision, and decision names within one step must be unique.
- **`TaskFilter` single-assignee-kind guard.** At most one of `assigneeUser` / `assigneeRole` / `assigneeQueue` may be set. Use `TaskFilter.empty()` plus the `with*` copies to compose filters.
- **Required actor.** `TaskCompletionCommand` and `TaskReassignmentCommand` reject null / blank `WorkflowActor` via their compact constructors. Authorization is the application's job; the actor recorded here is for audit and idempotency.
- **Blank `reason` normalization.** Blank `reason` strings on commands are normalized to `null` before idempotency fingerprinting, so callers who omit a reason do not get treated as different commands.
- **Return contract.** `complete` / `reassign` return `Future<Void>`. There is no result enum on the application-facing API: a succeeded future means the command's effect is persisted, and every other outcome is a failed future carrying a typed exception — `WorkflowTaskNotWaitingException`, `WorkflowConflictException`, `WorkflowIdempotencyConflictException`, `WorkflowTaskNotFoundException`, or `WorkflowStaleSubjectVersionException`. These operations never report a problem by returning a "nothing happened" value.
- **Replays succeed silently.** A retry with the same idempotency key and the same logical payload succeeds without re-applying the effect, and is indistinguishable from the original call — the future carries no marker saying which call did the work. Callers that must know whether *this* call changed anything have to read the task back with `get(...)`.
- **`STALE_NOOP` is not an outcome of these operations.** It belongs to the due-date timer path (`taskDueFired` on `TransactionalTaskCallbacks<TX>`), where a workflow has legitimately moved past the wait before the timer fires. If a custom `TransactionalTaskCallbacks<TX>` implementation returns it from `taskCompleted` / `taskReassigned`, the service fails the future with `IllegalStateException` rather than silently reporting success. Treat that as a bug in the SPI implementation, not as a case to handle.
- **Version-stable approvals.** When a step calls `.requireVersionStability()`, completion must supply the `reviewedSubjectVersion` the reviewer saw. A mismatch fails with `WorkflowStaleSubjectVersionException`; creation without a snapshotable `subject_version` fails with `WorkflowSubjectVersionUnavailableException`.

---

## Dependencies

- **workflow-core** - task records, filters, commands, service interfaces, and engine SPIs.
