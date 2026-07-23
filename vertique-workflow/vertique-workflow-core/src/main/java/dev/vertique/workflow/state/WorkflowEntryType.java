// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

/**
 * Discriminator values for {@link WorkflowHistoryEntry#entryType()}.
 *
 * <p>Each constant's {@link #name()} is the string value stored in the {@code entry_type} column of
 * {@code workflow_history}. Producer sites in the engine use {@code WorkflowEntryType.X.name()} so
 * that the set of valid values is enumerated in one place and any addition or rename is caught at
 * compile time. The persistence layer remains string-typed so no DDL change is required.
 */
public enum WorkflowEntryType {

    /** Instance was created and the first step was reached. */
    START,

    /** A named external signal was applied to the instance. */
    SIGNAL_RECEIVED,

    /** Instance was cancelled by an operator or API call. */
    CANCELLED,

    /** A {@code FAILED} instance was reset to {@code RUNNING} to re-execute the current step. */
    RETRIED,

    /** A service-dispatch intent was recorded in the outbox. */
    SIDE_EFFECT_RECORDED,

    /** Compensation flow was initiated (instance transitioned to {@code COMPENSATING}). */
    COMPENSATING_START,

    /** A single compensating service intent was recorded. */
    COMPENSATING_STEP,

    /** Workflow reached a {@link dev.vertique.workflow.plan.CompleteNode}. */
    COMPLETED,

    /** Workflow reached a {@link dev.vertique.workflow.plan.FailNode}. */
    FAILED,

    /** All compensation steps have been recorded; instance transitioned to {@code COMPENSATED}. */
    COMPENSATED,

    /** A durable workflow timer was scheduled (for a {@link dev.vertique.workflow.plan.TimerNode} or
     * a {@link dev.vertique.workflow.plan.WaitSignalNode} timeout branch). */
    TIMER_SCHEDULED,

    /** A scheduled timer fired and the workflow was resumed or advanced to the next step. */
    TIMER_FIRED,

    /** A signal-wait step timed out because the timer fired before the expected signal arrived. */
    TIMEOUT,

    /** A scheduled timer was explicitly cancelled (e.g., the expected signal arrived in time). */
    TIMER_CANCELLED,

    /** A timer-fire attempt permanently failed after exhausting retries. */
    TIMER_FAILED,

    /** A human task was created and the workflow is waiting for an actor's decision (cycle 3+). */
    TASK_CREATED,

    /** An actor submitted a decision and the task was completed (cycle 3+). */
    TASK_COMPLETED,

    /** A task was cancelled because the parent workflow instance was cancelled (cycle 3+). */
    TASK_CANCELLED,

    /** A task's due-date timer fired before the task was completed (cycle 3+). */
    TASK_EXPIRED,

    /** The assignment of a task was changed to a different user, role, or queue (cycle 3+). */
    TASK_REASSIGNED,

    /** A reminder timer for a human task fired; no workflow state change (cycle 4+). */
    TASK_REMINDER_FIRED,

    // --- PRD-WF-002 fan-out / fan-in (cycle 6+) -----------------------------------------------

    /** A {@link dev.vertique.workflow.plan.ForkNode} was dispatched: branch tokens and join state created. */
    FORK_DISPATCHED,

    /** A branch token was created (one entry per branch on fork dispatch). */
    BRANCH_CREATED,

    /** A branch reached a durable wait (signal, timer, or task). */
    BRANCH_WAIT,

    /** A branch resumed from a durable wait (signal arrived, timer fired, task completed). */
    BRANCH_RESUMED,

    /** A branch reached a successful terminal state. */
    BRANCH_COMPLETED,

    /** A branch reached a non-retryable terminal failure. */
    BRANCH_FAILED,

    /** A branch failure was retryable; {@code next_retry_at} is set. */
    BRANCH_RETRY_SCHEDULED,

    /** A branch was marked SUPERSEDED after losing a race join. */
    BRANCH_SUPERSEDED,

    /** A branch expired through a branch-level timer or deadline. */
    BRANCH_EXPIRED,

    /** A branch was cancelled by workflow/group cancellation. */
    BRANCH_CANCELLED,

    /** A branch entered the compensation pipeline. */
    BRANCH_COMPENSATING_START,

    /** A single compensating service intent was recorded for a branch step. */
    BRANCH_COMPENSATING_STEP,

    /** A branch finished compensating. */
    BRANCH_COMPENSATED,

    /** A late branch result arrived after the join was already decided (race winner) and was ignored. */
    BRANCH_LATE_RESULT_IGNORED,

    /** The join policy was evaluated; the entry payload records the input branch statuses. */
    FAN_IN_EVALUATED,

    /** The join completed successfully; the workflow advanced via the join's nextStepId. */
    FAN_IN_COMPLETED,

    /** The join failed; the workflow advanced via the join's failureStepId. */
    FAN_IN_FAILED,

    // --- PRD-WF-002 branch-owned durable waits (slice 3 / slice 4) -------------------------

    /** A human task was created on behalf of a fork branch. */
    BRANCH_TASK_CREATED,

    /**
     * A branch-owned human task was completed; the owning branch resumed.
     */
    BRANCH_TASK_COMPLETED,

    /**
     * A branch-owned task's due-date timer fired before the task was completed; the branch
     * advanced via the node's {@code dueNextStepId}.
     */
    BRANCH_TASK_DUE_EXPIRED,

    /**
     * A branch-owned task reminder timer fired; no branch-state change, only a notification
     * event (mirrors {@link #TASK_REMINDER_FIRED} for the single-path case).
     */
    BRANCH_TASK_REMINDER_FIRED,

    /**
     * A branch-owned standalone timer ({@code TimerNode}) fired and the branch resumed.
     */
    BRANCH_TIMER_FIRED,

    /**
     * A scheduled timer for a branch was recorded (mirrors {@link #TIMER_SCHEDULED}).
     */
    BRANCH_TIMER_SCHEDULED,

    /**
     * A branch signal-timeout timer fired before the expected signal arrived; the branch resumed
     * via the timeout path.
     */
    BRANCH_TIMER_TIMEOUT_FIRED,

    /**
     * A branch signal-with-timeout was scheduled; the timeout timer id is captured in the entry
     * payload.
     */
    BRANCH_SIGNAL_TIMEOUT_SCHEDULED,

    /**
     * A late callback (task completion, timer fire, reminder) arrived for a branch that is no
     * longer in a matching waiting state (e.g., SUPERSEDED, CANCELLED, or already advanced). The
     * callback was silently ignored; this entry records the event for observability.
     */
    BRANCH_LATE_CALLBACK_IGNORED,

    // --- PRD-WF-003 migration (cycle 7+) ---

    /**
     * An instance was migrated from one definition version to another by an application-registered
     * {@link dev.vertique.workflow.migration.WorkflowMigrationHandler}.
     */
    WORKFLOW_MIGRATED
}
