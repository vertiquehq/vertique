// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.events.WorkflowEventType;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import dev.vertique.workflow.state.WorkflowInstance;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.Map;

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
     * @param inst the fresh instance
     * @param cmd the original start command
     * @param tx the active transaction
     * @return a {@link Future} that completes when the entry is inserted and the event is emitted
     */
    Future<Void> appendStartHistory(WorkflowInstance inst, StartCommand cmd, SqlClient tx) {
        return history.nextSequence(inst.id(), tx).compose(seq -> {
            String payload = Json.encode(
                    new StartHistoryPayload(cmd.definitionId(), inst.definitionVersion(), cmd.businessKey()));
            WorkflowHistoryEntry entry =
                    new WorkflowHistoryEntry(inst.id(), seq, WorkflowEntryType.START, payload, clock.instant());
            return history.append(entry, tx)
                    .compose(v -> eventEmitter.emitEvent(
                            inst,
                            WorkflowEventType.WORKFLOW_STARTED,
                            seq,
                            null,
                            null,
                            Map.of("idempotencyKey", cmd.idempotencyKey()),
                            tx));
        });
    }
}
