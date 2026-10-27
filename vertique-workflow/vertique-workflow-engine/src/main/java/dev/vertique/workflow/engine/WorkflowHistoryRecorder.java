// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.events.WorkflowEventType;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.types.EchoedNames;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Package-private collaborator responsible for appending generic workflow lifecycle history entries
 * and delegating event emission to {@link WorkflowEventEmitter}.
 *
 * <p>This class is extracted from {@link WorkflowEngine} as part of the Phase-1 decomposition
 * (PRD-WF-006, Slice C1). It holds the {@code appendStartHistory} method — the only
 * non-cluster-specific append helper assigned to this collaborator in C1. All other
 * {@code append*History} methods that are tightly coupled to specific clusters (task, timer,
 * fork/join) remain on {@link WorkflowEngine} until their respective extraction slices
 * (C4, C7, C8).
 *
 * <p>This class is a leaf in the dependency graph: it depends on the history repository SPI,
 * the clock, and {@link WorkflowEventEmitter}. It has no reference to the transition driver
 * or fork/join coordinator.
 *
 * <p>Instances are {@code @Singleton} and constructed by Dagger via {@code @Inject}.
 */
@Singleton
final class WorkflowHistoryRecorder {

    // --- Dependencies ---

    private final WorkflowHistoryRepository<SqlClient> history;
    private final WorkflowEventEmitter eventEmitter;
    private final Clock clock;

    /**
     * Constructs a new history recorder.
     *
     * @param history the history repository used to persist entries and obtain sequence numbers
     * @param eventEmitter the event emitter used to emit {@code WORKFLOW_EVENT} intents after
     *     each history entry is appended
     * @param clock the clock used to timestamp history entries
     */
    @Inject
    WorkflowHistoryRecorder(
            WorkflowHistoryRepository<SqlClient> history, WorkflowEventEmitter eventEmitter, Clock clock) {
        this.history = history;
        this.eventEmitter = eventEmitter;
        this.clock = clock;
    }

    // --- History append helpers ---

    /**
     * Appends the {@code START} history entry for a newly created instance and emits the
     * {@link WorkflowEventType#WORKFLOW_STARTED} event.
     *
     * @param inst the instance, as initialized by its first segment
     * @param idempotencyKey the idempotency key of the recorded start, or {@code null} when none was kept
     * @param tx the active transaction
     * @return a {@link Future} that completes when the entry is inserted and the event is emitted
     */
    Future<Void> appendStartHistory(WorkflowInstance inst, @Nullable String idempotencyKey, SqlClient tx) {
        return history.nextSequence(inst.id(), tx).compose(seq -> {
            String payload = Json.encode(
                    new StartHistoryPayload(inst.definitionId(), inst.definitionVersion(), inst.businessKey()));
            WorkflowHistoryEntry entry =
                    new WorkflowHistoryEntry(inst.id(), seq, WorkflowEntryType.START, payload, clock.instant());
            return history.append(entry, tx)
                    .compose(v -> eventEmitter.emitEvent(
                            inst,
                            WorkflowEventType.WORKFLOW_STARTED,
                            seq,
                            null,
                            null,
                            idempotencyKey == null ? Map.of() : Map.of("idempotencyKey", idempotencyKey),
                            tx));
        });
    }

    /**
     * Appends a history entry that says what happened to a command: received by a segment, or completed by it.
     *
     * @param workflowId the instance
     * @param type the entry type, one of the {@code COMMAND_*} types
     * @param commandId the command's row id
     * @param kind the command kind
     * @param tx the active transaction
     * @return a {@link Future} that completes when the entry is inserted
     */
    Future<Void> appendCommandHistory(
            WorkflowInstanceId workflowId, WorkflowEntryType type, long commandId, String kind, SqlClient tx) {
        return history.nextSequence(workflowId, tx).compose(seq -> {
            String payload = Json.encode(new CommandHistoryPayload(commandId, kind));
            return history.append(new WorkflowHistoryEntry(workflowId, seq, type, payload, clock.instant()), tx);
        });
    }

    /**
     * Appends a history entry about what became of a recorded command that has no payload of its own to name: a retry or
     * a migration. The entry names the command, its kind and the sanitized reason, never a payload value or exception
     * text.
     *
     * @param workflowId the instance
     * @param type the entry type
     * @param commandId the command's row id
     * @param kind the command kind
     * @param reason the sanitized reason, or {@code null} for an outcome without one
     * @param tx the active transaction
     * @return a future that completes when the entry is appended
     */
    Future<Void> appendCommandOutcomeHistory(
            WorkflowInstanceId workflowId,
            WorkflowEntryType type,
            long commandId,
            String kind,
            @Nullable String reason,
            SqlClient tx) {
        return history.nextSequence(workflowId, tx).compose(seq -> {
            String payload = Json.encode(new CommandOutcomeHistoryPayload(commandId, kind, reason));
            return history.append(new WorkflowHistoryEntry(workflowId, seq, type, payload, clock.instant()), tx);
        });
    }

    /**
     * Appends a history entry about what became of a recorded {@code SIGNAL} command. The entry names the command, the
     * signal and the sanitized reason, never a payload value or exception text.
     *
     * @param workflowId the instance
     * @param type the entry type
     * @param commandId the command's row id
     * @param signalName the signal's name
     * @param reason the sanitized reason, or {@code null} for an outcome without one
     * @param tx the active transaction
     * @return a future that completes when the entry is appended
     */
    Future<Void> appendSignalCommandHistory(
            WorkflowInstanceId workflowId,
            WorkflowEntryType type,
            long commandId,
            String signalName,
            @Nullable String reason,
            SqlClient tx) {
        return history.nextSequence(workflowId, tx).compose(seq -> {
            String payload = Json.encode(
                    new SignalCommandHistoryPayload(commandId, "SIGNAL", EchoedNames.bounded(signalName), reason));
            return history.append(new WorkflowHistoryEntry(workflowId, seq, type, payload, clock.instant()), tx);
        });
    }

    /**
     * Appends a history entry about what became of a recorded {@code TIMER} command. The entry names the command, its
     * timer kind and timer and the sanitized reason, never a payload value or exception text.
     *
     * @param workflowId the instance
     * @param type the entry type
     * @param commandId the command's row id
     * @param timerKind the timer kind, or {@code null} when unknown
     * @param timerId the timer row the command fires, or {@code null} when unknown
     * @param reason the sanitized reason, or {@code null} for an outcome without one
     * @param tx the active transaction
     * @return a future that completes when the entry is appended
     */
    Future<Void> appendTimerCommandHistory(
            WorkflowInstanceId workflowId,
            WorkflowEntryType type,
            long commandId,
            @Nullable String timerKind,
            @Nullable UUID timerId,
            @Nullable String reason,
            SqlClient tx) {
        return history.nextSequence(workflowId, tx).compose(seq -> {
            String payload =
                    Json.encode(new TimerCommandHistoryPayload(commandId, "TIMER", timerKind, timerId, reason));
            return history.append(new WorkflowHistoryEntry(workflowId, seq, type, payload, clock.instant()), tx);
        });
    }

    /**
     * Appends a history entry about what became of a recorded {@code COMPLETION} command. The entry names the command,
     * the task, the decision and the sanitized reason, never a payload value or exception text.
     *
     * @param workflowId the instance
     * @param type the entry type
     * @param commandId the command's row id
     * @param taskId the task the completion reserved
     * @param decisionName the decision's name
     * @param reason the sanitized reason, or {@code null} for an outcome without one
     * @param tx the active transaction
     * @return a future that completes when the entry is appended
     */
    Future<Void> appendCompletionCommandHistory(
            WorkflowInstanceId workflowId,
            WorkflowEntryType type,
            long commandId,
            UUID taskId,
            String decisionName,
            @Nullable String reason,
            SqlClient tx) {
        return history.nextSequence(workflowId, tx).compose(seq -> {
            String payload = Json.encode(new CompletionCommandHistoryPayload(
                    commandId, "COMPLETION", taskId, EchoedNames.bounded(decisionName), reason));
            return history.append(new WorkflowHistoryEntry(workflowId, seq, type, payload, clock.instant()), tx);
        });
    }
}
