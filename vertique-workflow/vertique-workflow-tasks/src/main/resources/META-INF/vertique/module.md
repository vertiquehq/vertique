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

Assignment can be literal or resolved from workflow state. Decisions carry typed payloads and update workflow state before moving to their next step. Due dates and reminders require the delayed workflow module.

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
- **Mutation result vocabulary.** `complete` / `reassign` return `APPLIED`, `LOST_TO_RACE`, or `STALE_NOOP`. Idempotent replays of the same logical command return `STALE_NOOP` rather than failing; concurrent operations return `LOST_TO_RACE`.
- **Version-stable approvals.** When a step calls `.requireVersionStability()`, completion must supply the `reviewedSubjectVersion` the reviewer saw. A mismatch fails with `WorkflowStaleSubjectVersionException`; creation without a snapshotable `subject_version` fails with `WorkflowSubjectVersionUnavailableException`.

---

## Dependencies

- **workflow-core** - task records, filters, commands, service interfaces, and engine SPIs.

---

## Related ADRs

- ADR-0042: Tasks SPI shape — `TaskStore<TX>` is storage-only; `TransactionalTaskCallbacks<TX>` is the engine seam.
- ADR-0043: Decision payload typing — typed decisions; `void.class` for no-payload decisions.
- ADR-0044: Task completion idempotency — canonical fingerprint envelope, mutation-result vocabulary.
- ADR-0045: Task lock order — `workflow_timers → workflow_tasks → workflow_instances`.
- ADR-0048: Actor as audit identity — required actor on every mutation command.
- ADR-0055: Version-aware approvals — subject-version stability semantics.
- ADR-0057: Task complete fingerprint v2 — fingerprint envelope schema (`v: 2`) including `reviewedSubjectVersion`.
