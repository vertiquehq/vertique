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
| Compose validator | Startup check, run in the `VALIDATE` phase, that required task bindings exist |

The engine still owns state transitions. Completing or reassigning a task delegates to engine callbacks so workflow state, history, timers, and idempotency are updated consistently. Completing a task is a two-step operation: `complete` validates and *records* the completion and reserves the task (`COMPLETING`), and the workflow executor applies the decision afterwards (see [Recorded completion](#recorded-completion)).

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

### Inbox: tasks assigned to the caller

`TaskFilter.withAssignedTo(principals)` lists, in one query, the tasks assigned to the caller's user id,
to any of its roles and to any of its queues, combined with every other field of the filter:

```java
@Inject TaskService tasks;
@Inject Optional<TaskPrincipalsMapper> mapper;

TaskPrincipals me = TaskPrincipalsMapper.orDefault(mapper).toTaskPrincipals(securityContext);
tasks.list(TaskFilter.empty().withAssignedTo(me).withStatus(TaskStatus.OPEN), PageCursor.first(20));
```

- Among the tasks that pass the filter's other fields (and, unless `includeArchived` is set, belong to
  an instance that is not archived), it returns exactly those whose assignment `TaskAssignment.matches(me)`
  accepts (see the core module reference for the rule and the default mapper). A principal that is not
  a `USER` gets an empty page, never every task.
- `assignedTo` excludes `assigneeUser`, `assigneeRole` and `assigneeQueue`; the filter refuses the
  combination with `IllegalArgumentException`.
- Pass a status. With `OPEN` the PostgreSQL store reads the partial indexes on open tasks of each
  assignee kind. Without a status, or with a closed status, it walks the keyset index over all tasks and
  filters, which grows with the table's history. A task reserved by a recorded completion
  (`COMPLETING`) is not in an `OPEN` inbox.
- It is a read and authorizes nothing.

Task commands require an idempotency key. A retry with the same key and same logical payload succeeds as an idempotent retry. Reusing the same key with a different logical payload fails with an idempotency conflict.

### Recorded completion

`complete(...)` returns once the completion is recorded, not once it is applied:

1. A committed duplicate (same key and same fingerprint) returns its earlier result at once, before any other check; it still succeeds after `workflow.recording.maxPayloadBytes` or the task's stored schema has changed.
2. Without taking a lock, the payload's size and depth bounds are checked and, when the task's decision snapshot stores a JSON Schema for the requested decision, the payload is validated against it. A violation fails with `WorkflowInvalidPayloadException`; nothing is written.
3. Under the instance's execution lock the task must still be `OPEN` (otherwise `WorkflowTaskNotWaitingException`), a supplied `expectedAssignment` must still match (`WorkflowStaleAssignmentException`), the `reviewedSubjectVersion` must match when the task requires version stability (`WorkflowStaleSubjectVersionException`), and the decision must be one the task offers.
4. The task becomes `COMPLETING`, owned by the recorded `COMPLETION` command, and the call returns.

The executor then applies the decision in its own transaction: the task becomes `COMPLETED` and the instance advances. If the application of the decision fails deterministically (a throwing decision applicator, a payload that does not bind to the declared type on a task created before schemas were stored, a step-limit overrun), the command is rejected with a sanitized reason, the instance is unchanged and the task is `OPEN` again; complete it again with a new idempotency key. A `COMPLETING` task refuses a second completion and a reassignment with `WorkflowTaskNotWaitingException`. To observe the outcome, wait with the workflow execution probe (`awaitIdle`) and read the task back with `get(...)`.

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

Assignment can be literal or resolved from workflow state. Decisions carry typed payloads and update workflow state before moving to their next step. Use `void.class` as the payload type to declare a decision that carries no payload.

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
}
```

Human tasks with due dates or reminders need no further module: their timers are commands that the workflow executor of `vertique-workflow-postgresql` fires.

In a component that includes `CoreLifecycleStepsModule`, as `@VertiqueApp` components do, the `VALIDATE` startup phase runs `WorkflowTasksComposeValidator` with every other compose validator, so the component needs no accessor for it. A missing `TaskService` or `TaskStore` binding is a Dagger compile-time error.

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
- Transactions that change a workflow instance or its tasks and timers must hold the instance's execution lock first; the persistence module's lock-order section states the rule.
- Authorization is application-defined. The workflow engine records the actor supplied on the task command but does not decide whether that actor may act.

---

## Invariants & Gotchas

- **All-or-nothing due-date triplet.** A task step's optional due-date sub-chain (a due date from `dueIn(...)`, `dueAt(...)` or `dueFromState(...)`, then `onDue(...)` and `toStepOnDue(...)`) must specify all three or none. The builder refuses a partial triplet at `build()` with `WorkflowDefinitionException`, and the `HumanTaskNode` compact constructor refuses one with `IllegalArgumentException`.
- **Non-empty unique decisions.** Each task step must declare at least one decision, and decision names within one step must be unique.
- **`TaskFilter` single-assignee-kind guard.** At most one of `assigneeUser` / `assigneeRole` / `assigneeQueue` may be set; for a user OR its roles OR its queues use `withAssignedTo`, which excludes the three. Use `TaskFilter.empty()` plus the `with*` copies to compose filters. `withScopeKey(...)` adds an exact-match start-scope filter (`null` matches every scope; it is a filter, not an authorization boundary: see the core module reference). `withTaskType(...)` adds an exact-match filter on the task type the step declared (`TaskRecord.taskType()`); a task created without a type matches no task type.
- **Required actor.** `TaskCompletionCommand` and `TaskReassignmentCommand` reject a null `WorkflowActor` in their compact constructors, and the `WorkflowActor.User`, `Service` and `System` records reject a blank id or reason. Authorization is the application's job; the actor recorded here is for audit and idempotency.
- **Blank `reason` normalization.** A blank `reason` on `TaskReassignmentCommand` is normalized to `null` before idempotency fingerprinting, so callers who omit a reason do not get treated as different commands.
- **Return contract.** `complete` / `reassign` return `Future<Void>`. There is no result enum on the application-facing API: a succeeded future means the command's effect is persisted (for `complete`, that the completion is *recorded* and the task reserved; the executor applies it afterwards), and every other outcome is a failed future carrying a typed exception — `WorkflowTaskNotWaitingException`, `WorkflowConflictException`, `WorkflowIdempotencyConflictException`, `WorkflowTaskNotFoundException`, `WorkflowStaleSubjectVersionException`, `WorkflowStaleAssignmentException`, or `WorkflowInvalidPayloadException` (400 semantics: unknown decision, payload missing or unexpected for the decision, or a payload that does not bind to the declared type). These operations never report a problem by returning a "nothing happened" value.
- **Replays succeed silently.** A retry with the same idempotency key and the same logical payload succeeds without re-applying the effect, and is indistinguishable from the original call — the future carries no marker saying which call did the work. A replayed completion returns before any payload check. Callers that must know whether *this* call changed anything have to read the task back with `get(...)`.
- **A completed call is not a completed task.** After `complete` succeeds the task is `COMPLETING`; it is `COMPLETED` only once the executor has applied the decision, and `OPEN` again if the decision was rejected. Code that reads the task or the instance right after `complete` must wait for the executor (`awaitIdle`) first. A due-date or reminder timer that fires while the task is `COMPLETING` finds it not `OPEN` and settles itself as stale.
- **`STALE_NOOP` is not an outcome of these operations.** It belongs to the executor's due-date timer path, where a workflow has legitimately moved past the wait before the timer fires. If a custom `TransactionalTaskCallbacks<TX>` implementation returns it from `taskCompleted` / `taskReassigned`, the service fails the future with `IllegalStateException` rather than silently reporting success. Treat that as a bug in the SPI implementation, not as a case to handle.
- **Version-stable approvals.** When a step calls `.requireVersionStability()`, completion must supply the `reviewedSubjectVersion` the reviewer saw. A mismatch fails with `WorkflowStaleSubjectVersionException`; creation without a snapshotable `subject_version` fails with `WorkflowSubjectVersionUnavailableException`.
- **Eligibility without a race.** To authorize on the current assignment, read the task with `get(...)`, decide eligibility from `task.assignment()`, and pass that same assignment as `expectedAssignment` on the `complete` / `reassign` command. If a concurrent reassignment moves the task first, the command fails with `WorkflowStaleAssignmentException` (409 semantics) instead of applying under a stale eligibility decision; re-read the task and decide again. `expectedAssignment` is not part of the idempotency fingerprint, and `null` skips the check. On `TransactionalTaskService<TX>` the caller owns the transaction and must roll it back when this exception is raised: the dedup claim and timer cancellations written before the check would otherwise be committed with the task still open. The same holds when `complete` is rejected with `WorkflowInvalidPayloadException`: roll the transaction back so the idempotency claim is released and a retry with a corrected payload can complete the task.

---

## Dependencies

- **workflow-core** - task records, filters, commands, service interfaces, and engine SPIs.
