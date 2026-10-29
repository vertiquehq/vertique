// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.history;

import dev.vertique.workflow.engine.spi.CommittedHistoryEntry;
import dev.vertique.workflow.history.CommandKind;
import dev.vertique.workflow.history.HistoryAttribution;
import dev.vertique.workflow.history.WorkflowHistoryEvent;
import dev.vertique.workflow.history.WorkflowHistoryEvent.BranchChange;
import dev.vertique.workflow.history.WorkflowHistoryEvent.CommandDisposition;
import dev.vertique.workflow.history.WorkflowHistoryEvent.CompensationChange;
import dev.vertique.workflow.history.WorkflowHistoryEvent.DecisionKind;
import dev.vertique.workflow.history.WorkflowHistoryEvent.EntryInfo;
import dev.vertique.workflow.history.WorkflowHistoryEvent.FanInChange;
import dev.vertique.workflow.history.WorkflowHistoryEvent.TimerChange;
import dev.vertique.workflow.state.HistoryActor;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import dev.vertique.workflow.tasks.TaskAssignment;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INTERNAL: maps a committed history entry to the semantic {@link WorkflowHistoryEvent} a listener receives.
 *
 * <p>The mapping is a pure function of the entry. Every {@link WorkflowEntryType} maps to a typed variant except
 * {@code STATE_BASELINE}, which holds a state snapshot and maps to nothing, and a type added after this mapping, which
 * maps to {@link WorkflowHistoryEvent.Other}. A task event reads only the task id, the decision name and the
 * assignments from the entry's payload; when one of those cannot be read the entry maps to {@code Other} instead. The
 * mapper never throws (it catches {@link RuntimeException}) and logs only the exception class of a failure, never its
 * message. A stored entry type this node does not know never reaches it: the range read filters the known types in SQL.
 *
 * <p>The provenance of an event is exactly what the entry records and is never guessed: the command id comes from the
 * entry's own column or its payload, and the command kind, the definition pin and the step are {@code null} when the
 * writer did not record them.
 *
 * <p>The kind of a {@code DECISION} entry is recovered from its stored outcome: the words the engine writes map to
 * their {@link DecisionKind}, and any other outcome is the step an author-defined decision node chose and maps to
 * {@code BRANCH} with that step id (at most {@value #MAX_STEP_ID_LENGTH} characters). The decision name and the
 * assignment ids of a task event are cut to {@value #MAX_NAME_LENGTH} characters.
 */
final class HistoryEventMapper {

    private static final Logger log = LoggerFactory.getLogger(HistoryEventMapper.class);

    /** The longest chosen step id a {@code BRANCH} decision carries. */
    static final int MAX_STEP_ID_LENGTH = 40;

    /**
     * The longest decision name or assignment id an event carries, the bound the engine puts on an identifier when it
     * records it; a longer stored value is cut to it.
     */
    static final int MAX_NAME_LENGTH = 255;

    /** The outcome words the engine writes to a decision entry. */
    private static final Map<String, DecisionKind> DECISION_WORDS = Map.ofEntries(
            Map.entry("intent", DecisionKind.INTENT),
            Map.entry("wait-signal", DecisionKind.WAIT_SIGNAL),
            Map.entry("wait-timer", DecisionKind.WAIT_TIMER),
            Map.entry("wait-task", DecisionKind.WAIT_TASK),
            Map.entry("signal-applied", DecisionKind.SIGNAL_APPLIED),
            Map.entry("task-decided", DecisionKind.TASK_DECIDED),
            Map.entry("task-expired", DecisionKind.TASK_EXPIRED),
            Map.entry("timer-fired", DecisionKind.TIMER_FIRED),
            Map.entry("timeout", DecisionKind.TIMEOUT),
            Map.entry("fork-dispatched", DecisionKind.FORK_DISPATCHED),
            Map.entry("joined", DecisionKind.JOINED),
            Map.entry("join-failed", DecisionKind.JOIN_FAILED),
            Map.entry("completed", DecisionKind.COMPLETED),
            Map.entry("failed", DecisionKind.FAILED),
            Map.entry("compensating", DecisionKind.COMPENSATING),
            Map.entry("compensation-step", DecisionKind.COMPENSATION_STEP),
            Map.entry("compensated", DecisionKind.COMPENSATED),
            Map.entry("retried", DecisionKind.RETRIED),
            Map.entry("migrated", DecisionKind.MIGRATED));

    private HistoryEventMapper() {
        // static helpers only
    }

    /**
     * Maps one committed entry.
     *
     * @param committed the entry with the kind of the command it names
     * @return the event, or empty for a {@code STATE_BASELINE} entry (and for an entry that cannot be read at all)
     */
    static Optional<WorkflowHistoryEvent> map(CommittedHistoryEntry committed) {
        WorkflowHistoryEntry entry = committed.entry();
        try {
            if (entry.entryType() == WorkflowEntryType.STATE_BASELINE) {
                return Optional.empty();
            }
            EntryInfo info = entryInfo(committed);
            try {
                return Optional.of(event(entry, info));
            } catch (RuntimeException e) {
                log.warn(
                        "History entry {} of instance {} read as an unknown event ({})",
                        entry.sequence(),
                        entry.instanceId(),
                        e.getClass().getName());
                return Optional.of(new WorkflowHistoryEvent.Other(info, entry.entryType()));
            }
        } catch (RuntimeException e) {
            log.warn(
                    "History entry {} of instance {} cannot be mapped ({})",
                    entry.sequence(),
                    entry.instanceId(),
                    e.getClass().getName());
            return Optional.empty();
        }
    }

    private static EntryInfo entryInfo(CommittedHistoryEntry committed) {
        WorkflowHistoryEntry entry = committed.entry();
        HistoryActor actor =
                entry.actor() != null ? entry.actor() : new HistoryActor(HistoryActor.Kind.NONE, null, null);
        return new EntryInfo(
                entry.instanceId(),
                entry.sequence(),
                entry.definitionKey(),
                entry.stepId(),
                entry.commandId(),
                commandKind(committed.commandKind()),
                actor,
                entry.onBehalfOf(),
                attribution(entry.attributionStatus()),
                entry.recordedAt());
    }

    @Nullable
    private static CommandKind commandKind(@Nullable String stored) {
        if (stored == null) {
            return null;
        }
        try {
            return CommandKind.valueOf(stored);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static HistoryAttribution attribution(@Nullable String stored) {
        if (stored == null) {
            return HistoryAttribution.ABSENT;
        }
        try {
            return HistoryAttribution.valueOf(stored);
        } catch (IllegalArgumentException unknown) {
            return HistoryAttribution.ABSENT;
        }
    }

    private static WorkflowHistoryEvent event(WorkflowHistoryEntry entry, EntryInfo info) {
        WorkflowEntryType type = entry.entryType();
        return switch (type) {
            case START -> new WorkflowHistoryEvent.InstanceStarted(info);
            case RETRIED -> new WorkflowHistoryEvent.InstanceRetried(info);
            case CANCELLED -> new WorkflowHistoryEvent.InstanceCancelled(info);
            case COMPLETED -> new WorkflowHistoryEvent.InstanceCompleted(info);
            case FAILED -> new WorkflowHistoryEvent.InstanceFailed(info);
            case WORKFLOW_MIGRATED -> new WorkflowHistoryEvent.InstanceMigrated(info);

            case SIGNAL_RECEIVED -> new WorkflowHistoryEvent.SignalReceived(info);
            case SIGNAL_PARKED -> new WorkflowHistoryEvent.SignalParked(info);
            case SIGNAL_APPLIED -> new WorkflowHistoryEvent.SignalApplied(info);

            case TASK_CREATED -> taskCreated(entry, info);
            case TASK_COMPLETED -> taskDecided(entry, info);
            case TASK_REASSIGNED -> taskReassigned(entry, info);
            case TASK_EXPIRED -> new WorkflowHistoryEvent.TaskExpired(info);
            case TASK_CANCELLED -> taskCancelled(entry, info);
            case TASK_REMINDER_FIRED -> new WorkflowHistoryEvent.TaskReminderFired(info);

            case TIMER_SCHEDULED -> new WorkflowHistoryEvent.TimerChanged(info, TimerChange.SCHEDULED);
            case TIMER_FIRED -> new WorkflowHistoryEvent.TimerChanged(info, TimerChange.FIRED);
            case TIMEOUT -> new WorkflowHistoryEvent.TimerChanged(info, TimerChange.TIMED_OUT);
            case TIMER_CANCELLED -> new WorkflowHistoryEvent.TimerChanged(info, TimerChange.CANCELLED);
            case TIMER_FAILED -> new WorkflowHistoryEvent.TimerChanged(info, TimerChange.FAILED);

            case COMPENSATING_START -> new WorkflowHistoryEvent.CompensationChanged(info, CompensationChange.STARTED);
            case COMPENSATING_STEP ->
                new WorkflowHistoryEvent.CompensationChanged(info, CompensationChange.STEP_RECORDED);
            case COMPENSATED -> new WorkflowHistoryEvent.CompensationChanged(info, CompensationChange.COMPENSATED);
            case SIDE_EFFECT_RECORDED -> new WorkflowHistoryEvent.SideEffectRecorded(info);

            case FORK_DISPATCHED -> new WorkflowHistoryEvent.ForkDispatched(info);
            case FAN_IN_EVALUATED -> new WorkflowHistoryEvent.FanInChanged(info, FanInChange.EVALUATED);
            case FAN_IN_COMPLETED -> new WorkflowHistoryEvent.FanInChanged(info, FanInChange.COMPLETED);
            case FAN_IN_FAILED -> new WorkflowHistoryEvent.FanInChanged(info, FanInChange.FAILED);

            case BRANCH_CREATED -> branch(info, BranchChange.CREATED);
            case BRANCH_WAIT -> branch(info, BranchChange.WAITING);
            case BRANCH_RESUMED -> branch(info, BranchChange.RESUMED);
            case BRANCH_COMPLETED -> branch(info, BranchChange.COMPLETED);
            case BRANCH_FAILED -> branch(info, BranchChange.FAILED);
            case BRANCH_RETRY_SCHEDULED -> branch(info, BranchChange.RETRY_SCHEDULED);
            case BRANCH_SUPERSEDED -> branch(info, BranchChange.SUPERSEDED);
            case BRANCH_EXPIRED -> branch(info, BranchChange.EXPIRED);
            case BRANCH_CANCELLED -> branch(info, BranchChange.CANCELLED);
            case BRANCH_COMPENSATING_START -> branch(info, BranchChange.COMPENSATING_STARTED);
            case BRANCH_COMPENSATING_STEP -> branch(info, BranchChange.COMPENSATING_STEP_RECORDED);
            case BRANCH_COMPENSATED -> branch(info, BranchChange.COMPENSATED);
            case BRANCH_LATE_RESULT_IGNORED -> branch(info, BranchChange.LATE_RESULT_IGNORED);
            case BRANCH_LATE_CALLBACK_IGNORED -> branch(info, BranchChange.LATE_CALLBACK_IGNORED);
            case BRANCH_TASK_CREATED -> branch(info, BranchChange.TASK_CREATED);
            case BRANCH_TASK_COMPLETED -> branch(info, BranchChange.TASK_COMPLETED);
            case BRANCH_TASK_DUE_EXPIRED -> branch(info, BranchChange.TASK_DUE_EXPIRED);
            case BRANCH_TASK_REMINDER_FIRED -> branch(info, BranchChange.TASK_REMINDER_FIRED);
            case BRANCH_TIMER_SCHEDULED -> branch(info, BranchChange.TIMER_SCHEDULED);
            case BRANCH_TIMER_FIRED -> branch(info, BranchChange.TIMER_FIRED);
            case BRANCH_TIMER_TIMEOUT_FIRED -> branch(info, BranchChange.TIMER_TIMEOUT_FIRED);
            case BRANCH_SIGNAL_TIMEOUT_SCHEDULED -> branch(info, BranchChange.SIGNAL_TIMEOUT_SCHEDULED);

            case COMMAND_RECORDED -> command(info, CommandDisposition.RECORDED);
            case COMMAND_RECEIVED -> command(info, CommandDisposition.RECEIVED);
            case COMMAND_COMPLETED -> command(info, CommandDisposition.COMPLETED);
            case COMMAND_REJECTED -> command(info, CommandDisposition.REJECTED);
            case COMMAND_STALE -> command(info, CommandDisposition.STALE);
            case COMMAND_DEFERRED -> command(info, CommandDisposition.DEFERRED);
            case COMMAND_DEAD_LETTERED -> command(info, CommandDisposition.DEAD_LETTERED);
            case COMMAND_CANCELLED -> command(info, CommandDisposition.CANCELLED);
            case COMMAND_SUPERSEDED -> command(info, CommandDisposition.SUPERSEDED);

            case DECISION -> decision(entry.outcome(), info);

            // STATE_BASELINE is filtered before this switch; a type added later reads as Other
            default -> new WorkflowHistoryEvent.Other(info, type);
        };
    }

    private static WorkflowHistoryEvent branch(EntryInfo info, BranchChange change) {
        return new WorkflowHistoryEvent.BranchChanged(info, change);
    }

    private static WorkflowHistoryEvent command(EntryInfo info, CommandDisposition disposition) {
        return new WorkflowHistoryEvent.CommandChanged(info, disposition);
    }

    private static WorkflowHistoryEvent decision(@Nullable String outcome, EntryInfo info) {
        DecisionKind kind = outcome == null ? null : DECISION_WORDS.get(outcome);
        if (kind != null) {
            return new WorkflowHistoryEvent.StepDecided(info, kind, null);
        }
        String chosen = outcome == null || outcome.length() <= MAX_STEP_ID_LENGTH
                ? outcome
                : outcome.substring(0, MAX_STEP_ID_LENGTH);
        return new WorkflowHistoryEvent.StepDecided(info, DecisionKind.BRANCH, chosen);
    }

    // --- Task payloads: only the task id, the decision name and the assignments are read ---

    private static WorkflowHistoryEvent taskCreated(WorkflowHistoryEntry entry, EntryInfo info) {
        JsonObject payload = payload(entry);
        UUID taskId = taskId(payload);
        TaskAssignment assignment = assignment(payload, "assignment");
        return taskId == null || assignment == null
                ? other(entry, info)
                : new WorkflowHistoryEvent.TaskCreated(info, taskId, assignment);
    }

    private static WorkflowHistoryEvent taskDecided(WorkflowHistoryEntry entry, EntryInfo info) {
        JsonObject payload = payload(entry);
        UUID taskId = taskId(payload);
        String decisionName = string(payload, "decisionName");
        return taskId == null || decisionName == null
                ? other(entry, info)
                : new WorkflowHistoryEvent.TaskDecided(info, taskId, decisionName);
    }

    private static WorkflowHistoryEvent taskReassigned(WorkflowHistoryEntry entry, EntryInfo info) {
        JsonObject payload = payload(entry);
        UUID taskId = taskId(payload);
        TaskAssignment from = assignment(payload, "oldAssignment");
        TaskAssignment to = assignment(payload, "newAssignment");
        return taskId == null || from == null || to == null
                ? other(entry, info)
                : new WorkflowHistoryEvent.TaskReassigned(info, taskId, from, to);
    }

    private static WorkflowHistoryEvent taskCancelled(WorkflowHistoryEntry entry, EntryInfo info) {
        UUID taskId = taskId(payload(entry));
        return taskId == null ? other(entry, info) : new WorkflowHistoryEvent.TaskCancelled(info, taskId);
    }

    private static WorkflowHistoryEvent other(WorkflowHistoryEntry entry, EntryInfo info) {
        return new WorkflowHistoryEvent.Other(info, entry.entryType());
    }

    /** The entry's payload, or null when it is missing or not a JSON object. */
    @Nullable
    private static JsonObject payload(WorkflowHistoryEntry entry) {
        String json = entry.payloadJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return new JsonObject(json);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    @Nullable
    private static String string(@Nullable JsonObject object, String key) {
        if (object == null) {
            return null;
        }
        return object.getValue(key) instanceof String text ? bounded(text) : null;
    }

    private static String bounded(String text) {
        return text.length() <= MAX_NAME_LENGTH ? text : text.substring(0, MAX_NAME_LENGTH);
    }

    @Nullable
    private static UUID taskId(@Nullable JsonObject payload) {
        String text = string(payload, "taskId");
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** An assignment object holds exactly one of {@code userId}, {@code roleId} and {@code queueName}. */
    @Nullable
    private static TaskAssignment assignment(@Nullable JsonObject payload, String key) {
        if (payload == null || !(payload.getValue(key) instanceof JsonObject assignment)) {
            return null;
        }
        String userId = string(assignment, "userId");
        String roleId = string(assignment, "roleId");
        String queueName = string(assignment, "queueName");
        int present = (userId == null ? 0 : 1) + (roleId == null ? 0 : 1) + (queueName == null ? 0 : 1);
        if (present != 1) {
            return null;
        }
        try {
            if (userId != null) {
                return new TaskAssignment.User(userId);
            }
            if (roleId != null) {
                return new TaskAssignment.Role(roleId);
            }
            return new TaskAssignment.Queue(queueName);
        } catch (IllegalArgumentException blank) {
            return null;
        }
    }
}
