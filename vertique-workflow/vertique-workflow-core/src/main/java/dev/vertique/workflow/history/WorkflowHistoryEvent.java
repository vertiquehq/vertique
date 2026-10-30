// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.history;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.state.HistoryActor;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.tasks.TaskAssignment;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.UUID;

/**
 * One committed history entry as a semantic event.
 *
 * <p>Every event carries the provenance of its own owning command in {@link #entryInfo()}: one commit can mix actors
 * (a parked signal applied by another command's segment) and definition pins (a migration logs the target). No
 * payload, state, patch or state version is exposed; the only values read from an entry's typed payload are the
 * curated, bounded ones named on the variants below.
 *
 * <p>The set of variants grows additively. A consumer must have a default branch for variants added later;
 * {@link Other} stands for an entry type that has no variant of its own yet.
 */
public sealed interface WorkflowHistoryEvent {

    /**
     * What every event knows about its own history entry.
     *
     * @return the shared entry information
     */
    EntryInfo entryInfo();

    /**
     * What every event knows about its own history entry.
     *
     * <p>Provenance is never guessed. {@code commandId} is set only from the entry's own command column or the
     * {@code commandId} of its payload, never from the command of the segment the entry was written in. The definition
     * pin is filled from the instance's head only for an entry written in a command's segment that is not a migration.
     * So {@code definition}, {@code commandId} and {@code commandKind} are {@code null} on an event whose writer did
     * not record them, for example an entry written outside a command or inside a migration segment.
     *
     * @param instanceId  the instance
     * @param sequence    the entry's position in the instance history
     * @param definition  the definition pin the entry was logged under, or {@code null} when the entry did not record
     *                    it and none was filled in
     * @param stepId      the step the entry concerns, or {@code null}
     * @param commandId   the id of the command the entry names, or {@code null} when it names none
     * @param commandKind the kind of that command, or {@code null} when the entry names no command or the stored kind
     *                    is not known to this node
     * @param actor       who the entry is attributed to; {@code HistoryActor.Kind.NONE} when it was never attributed
     * @param onBehalfOf  the subject the actor acted for, or {@code null}
     * @param attribution how far the actor can be relied on; {@code ABSENT} when it was never attributed
     * @param recordedAt  when the entry was persisted
     */
    record EntryInfo(
            WorkflowInstanceId instanceId,
            long sequence,
            @Nullable DefinitionKey definition,
            @Nullable String stepId,
            @Nullable Long commandId,
            @Nullable CommandKind commandKind,
            HistoryActor actor,
            @Nullable String onBehalfOf,
            HistoryAttribution attribution,
            Instant recordedAt) {}

    /**
     * The instance was created and reached its first step.
     *
     * @param entryInfo the shared entry information
     */
    record InstanceStarted(EntryInfo entryInfo) implements WorkflowHistoryEvent {}

    /**
     * A failed instance was reset to re-execute its current step.
     *
     * @param entryInfo the shared entry information
     */
    record InstanceRetried(EntryInfo entryInfo) implements WorkflowHistoryEvent {}

    /**
     * The instance was cancelled.
     *
     * @param entryInfo the shared entry information
     */
    record InstanceCancelled(EntryInfo entryInfo) implements WorkflowHistoryEvent {}

    /**
     * The instance reached a complete node.
     *
     * @param entryInfo the shared entry information
     */
    record InstanceCompleted(EntryInfo entryInfo) implements WorkflowHistoryEvent {}

    /**
     * The instance reached a fail node or failed.
     *
     * @param entryInfo the shared entry information
     */
    record InstanceFailed(EntryInfo entryInfo) implements WorkflowHistoryEvent {}

    /**
     * The instance moved to another definition version; the entry info carries the target pin.
     *
     * @param entryInfo the shared entry information
     */
    record InstanceMigrated(EntryInfo entryInfo) implements WorkflowHistoryEvent {}

    /**
     * A named signal was applied to the instance.
     *
     * @param entryInfo the shared entry information
     */
    record SignalReceived(EntryInfo entryInfo) implements WorkflowHistoryEvent {}

    /**
     * A signal arrived before its step was waiting and was parked.
     *
     * @param entryInfo the shared entry information
     */
    record SignalParked(EntryInfo entryInfo) implements WorkflowHistoryEvent {}

    /**
     * A parked signal was applied to its step.
     *
     * @param entryInfo the shared entry information
     */
    record SignalApplied(EntryInfo entryInfo) implements WorkflowHistoryEvent {}

    /**
     * A task's due-date timer fired before the task was completed.
     *
     * @param entryInfo the shared entry information
     */
    record TaskExpired(EntryInfo entryInfo) implements WorkflowHistoryEvent {}

    /**
     * A task reminder fired; no state changed.
     *
     * @param entryInfo the shared entry information
     */
    record TaskReminderFired(EntryInfo entryInfo) implements WorkflowHistoryEvent {}

    /**
     * A service-dispatch intent was recorded.
     *
     * @param entryInfo the shared entry information
     */
    record SideEffectRecorded(EntryInfo entryInfo) implements WorkflowHistoryEvent {}

    /**
     * A fork dispatched its branches.
     *
     * @param entryInfo the shared entry information
     */
    record ForkDispatched(EntryInfo entryInfo) implements WorkflowHistoryEvent {}

    /**
     * A human task was created.
     *
     * @param entryInfo  the shared entry information
     * @param taskId     the task
     * @param assignment who the task is assigned to
     */
    record TaskCreated(EntryInfo entryInfo, UUID taskId, TaskAssignment assignment) implements WorkflowHistoryEvent {}

    /**
     * An actor submitted a decision and the task was completed (approved, declined or any other decision the task
     * defines).
     *
     * @param entryInfo    the shared entry information
     * @param taskId       the task
     * @param decisionName the name of the decision that was applied, bounded
     */
    record TaskDecided(EntryInfo entryInfo, UUID taskId, String decisionName) implements WorkflowHistoryEvent {}

    /**
     * The assignment of a task was changed.
     *
     * @param entryInfo the shared entry information
     * @param taskId    the task
     * @param from      the previous assignment
     * @param to        the new assignment
     */
    record TaskReassigned(EntryInfo entryInfo, UUID taskId, TaskAssignment from, TaskAssignment to)
            implements WorkflowHistoryEvent {}

    /**
     * A task was cancelled.
     *
     * @param entryInfo the shared entry information
     * @param taskId    the task
     */
    record TaskCancelled(EntryInfo entryInfo, UUID taskId) implements WorkflowHistoryEvent {}

    /**
     * A durable timer changed state.
     *
     * @param entryInfo the shared entry information
     * @param change    what happened to the timer
     */
    record TimerChanged(EntryInfo entryInfo, TimerChange change) implements WorkflowHistoryEvent {}

    /**
     * Compensation progressed.
     *
     * @param entryInfo the shared entry information
     * @param change    the compensation stage
     */
    record CompensationChanged(EntryInfo entryInfo, CompensationChange change) implements WorkflowHistoryEvent {}

    /**
     * A fan-in was evaluated, completed or failed.
     *
     * @param entryInfo the shared entry information
     * @param change    what happened to the fan-in
     */
    record FanInChanged(EntryInfo entryInfo, FanInChange change) implements WorkflowHistoryEvent {}

    /**
     * A branch of a fork changed state.
     *
     * @param entryInfo the shared entry information
     * @param change    what happened to the branch
     */
    record BranchChanged(EntryInfo entryInfo, BranchChange change) implements WorkflowHistoryEvent {}

    /**
     * A recorded command progressed.
     *
     * @param entryInfo   the shared entry information
     * @param disposition what became of the command
     */
    record CommandChanged(EntryInfo entryInfo, CommandDisposition disposition) implements WorkflowHistoryEvent {}

    /**
     * The engine decided something at a step (the decision log).
     *
     * @param entryInfo    the shared entry information
     * @param kind         the engine word of the decision, {@link DecisionKind#BRANCH} for a decision node's choice
     * @param chosenStepId the step id a decision node chose, at most 40 characters; only for {@link DecisionKind#BRANCH}
     */
    record StepDecided(
            EntryInfo entryInfo,
            DecisionKind kind,
            @Nullable String chosenStepId) implements WorkflowHistoryEvent {}

    /**
     * An entry type that has no event of its own yet.
     *
     * @param entryInfo the shared entry information
     * @param type      the entry type
     */
    record Other(EntryInfo entryInfo, WorkflowEntryType type) implements WorkflowHistoryEvent {}

    /** What happened to a durable timer. */
    enum TimerChange {
        SCHEDULED,
        FIRED,
        TIMED_OUT,
        CANCELLED,
        FAILED
    }

    /** A compensation stage. */
    enum CompensationChange {
        STARTED,
        STEP_RECORDED,
        COMPENSATED
    }

    /** What happened to a fan-in. */
    enum FanInChange {
        EVALUATED,
        COMPLETED,
        FAILED
    }

    /** What happened to a branch of a fork. */
    enum BranchChange {
        CREATED,
        WAITING,
        RESUMED,
        COMPLETED,
        FAILED,
        RETRY_SCHEDULED,
        SUPERSEDED,
        EXPIRED,
        CANCELLED,
        COMPENSATING_STARTED,
        COMPENSATING_STEP_RECORDED,
        COMPENSATED,
        LATE_RESULT_IGNORED,
        LATE_CALLBACK_IGNORED,
        TASK_CREATED,
        TASK_COMPLETED,
        TASK_DUE_EXPIRED,
        TASK_REMINDER_FIRED,
        TIMER_SCHEDULED,
        TIMER_FIRED,
        TIMER_TIMEOUT_FIRED,
        SIGNAL_TIMEOUT_SCHEDULED
    }

    /** What became of a recorded command. */
    enum CommandDisposition {
        RECORDED,
        RECEIVED,
        COMPLETED,
        REJECTED,
        STALE,
        DEFERRED,
        DEAD_LETTERED,
        CANCELLED,
        SUPERSEDED
    }

    /**
     * The engine words of a decision. Any other stored outcome is the author-defined branch a decision node chose
     * and reads as {@link #BRANCH}.
     */
    enum DecisionKind {
        BRANCH,
        INTENT,
        WAIT_SIGNAL,
        WAIT_TIMER,
        WAIT_TASK,
        SIGNAL_APPLIED,
        TASK_DECIDED,
        TASK_EXPIRED,
        TIMER_FIRED,
        TIMEOUT,
        FORK_DISPATCHED,
        JOINED,
        JOIN_FAILED,
        COMPLETED,
        FAILED,
        COMPENSATING,
        COMPENSATION_STEP,
        COMPENSATED,
        RETRIED,
        MIGRATED
    }
}
