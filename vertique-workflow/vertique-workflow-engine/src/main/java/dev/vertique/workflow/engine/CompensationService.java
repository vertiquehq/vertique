// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.events.WorkflowEventType;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.plan.CompensationNode;
import dev.vertique.workflow.plan.ServiceDispatchNode;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Package-private collaborator responsible for the single-path LIFO compensation flow of a
 * {@code FAILED} workflow instance.
 *
 * <p>This class is extracted from {@link WorkflowEngine} as part of the Phase-1 decomposition
 * (PRD-WF-006, Slice C3). It is the single-path counterpart to
 * {@link BranchCompensationOrchestrator}, which handles branch-scoped (fork) compensation.
 *
 * <p>When an instance reaches a {@link dev.vertique.workflow.plan.FailNode}, this service:
 * <ol>
 *   <li>Walks the instance history to find completed compensable forward dispatch steps via
 *       per-step signal matching (preventing over-compensation).</li>
 *   <li>If any exist, transitions the instance {@code FAILED} → {@code COMPENSATING}.</li>
 *   <li>Records one compensating side-effect intent per completed step in LIFO order (most
 *       recently dispatched first), each incrementing the instance version.</li>
 *   <li>Transitions the instance {@code COMPENSATING} → {@code COMPENSATED}.</li>
 * </ol>
 * All of this runs within the caller's transaction.
 *
 * <p>This class is a leaf in the dependency graph: it records compensation steps but does not drive
 * any further plan transitions, so it has no reference to the transition driver or fork/join
 * coordinator. It depends only on the instance/history repository SPIs, {@link RecorderRouter},
 * {@link WorkflowEventEmitter}, and {@link Clock}.
 *
 * <p>Instances are {@code @Singleton} and constructed by Dagger via {@code @Inject}.
 */
@Singleton
final class CompensationService {

    // --- Dependencies ---

    private final WorkflowInstanceRepository<SqlClient> instances;
    private final WorkflowHistoryRepository<SqlClient> history;
    private final RecorderRouter recorders;
    private final WorkflowEventEmitter eventEmitter;
    private final Clock clock;

    /**
     * Constructs a new compensation service.
     *
     * @param instances the instance repository used for optimistic-concurrency state transitions
     * @param history the history repository used to read prior entries and append compensation entries
     * @param recorders the recorder router used to route compensating side-effect intents
     * @param eventEmitter the event emitter used to emit lifecycle events on state transitions
     * @param clock the clock used to timestamp entries and state updates
     */
    @Inject
    CompensationService(
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            RecorderRouter recorders,
            WorkflowEventEmitter eventEmitter,
            Clock clock) {
        this.instances = instances;
        this.history = history;
        this.recorders = recorders;
        this.eventEmitter = eventEmitter;
        this.clock = clock;
    }

    // --- Compensation flow ---

    /**
     * Drives the LIFO compensation flow for a {@code FAILED} instance.
     *
     * <p>Reads history to find completed compensable forward steps. If there are none, leaves the
     * instance as {@code FAILED}. Otherwise, transitions to {@code COMPENSATING}, records intents
     * in LIFO order, and transitions to {@code COMPENSATED} — all in the same transaction.
     *
     * @param failedInst the FAILED instance
     * @param rw the resolved runtime workflow
     * @param tx the active transaction
     * @return a {@link Future} that completes after compensation is recorded
     */
    Future<Void> compensate(WorkflowInstance failedInst, RuntimeWorkflow rw, SqlClient tx) {
        return history.listByInstance(failedInst.id(), tx).compose(entries -> {
            List<String> completedCompensableStepIds = collectCompletedCompensableStepIds(entries, rw);

            if (completedCompensableStepIds.isEmpty()) {
                // No compensable steps — stay FAILED.
                return Future.succeededFuture();
            }

            // Transition to COMPENSATING.
            long prevVersion = failedInst.version();
            WorkflowInstance compensating = failedInst
                    .withVersion(prevVersion + 1)
                    .withStatus(WorkflowStatus.COMPENSATING)
                    .withWait(null, null)
                    .withUpdatedAt(clock.instant());

            return instances
                    .updateOptimistic(compensating, prevVersion, tx)
                    .compose(rowCount -> WorkflowPayloads.requireRowUpdated(
                            rowCount, "instance '" + failedInst.id().value() + "' transitioning to COMPENSATING"))
                    .compose(v -> history.nextSequence(failedInst.id(), tx))
                    .compose(seq -> {
                        String histPayload =
                                Json.encode(new CompensatingStartHistoryPayload(completedCompensableStepIds.size()));
                        return history.append(
                                        new WorkflowHistoryEntry(
                                                failedInst.id(),
                                                seq,
                                                WorkflowEntryType.COMPENSATING_START,
                                                histPayload,
                                                clock.instant()),
                                        tx)
                                .compose(v2 -> eventEmitter.emitEvent(
                                        compensating,
                                        WorkflowEventType.WORKFLOW_COMPENSATING,
                                        seq,
                                        null,
                                        null,
                                        Map.of(),
                                        tx));
                    })
                    .compose(v -> executeCompensationSteps(compensating, completedCompensableStepIds, rw, tx));
        });
    }

    /**
     * Executes each compensation step in LIFO order and transitions to {@code COMPENSATED}.
     *
     * @param compensating the instance in COMPENSATING status
     * @param lifoStepIds the forward step ids in LIFO order (already reversed)
     * @param rw the resolved runtime workflow
     * @param tx the active transaction
     * @return a {@link Future} that completes after all compensation intents are recorded
     */
    private Future<Void> executeCompensationSteps(
            WorkflowInstance compensating, List<String> lifoStepIds, RuntimeWorkflow rw, SqlClient tx) {
        // Track the current instance for version increments.
        Future<WorkflowInstance> chain = Future.succeededFuture(compensating);

        for (String forwardStepId : lifoStepIds) {
            chain = chain.compose(currentInst -> recordOneCompensationStep(currentInst, forwardStepId, rw, tx));
        }

        return chain.compose(currentInst -> {
            // Final: transition to COMPENSATED.
            long prevVersion = currentInst.version();
            WorkflowInstance compensated = currentInst
                    .withVersion(prevVersion + 1)
                    .withStatus(WorkflowStatus.COMPENSATED)
                    .withWait(null, null)
                    .withUpdatedAt(clock.instant());

            return instances
                    .updateOptimistic(compensated, prevVersion, tx)
                    .compose(rowCount -> WorkflowPayloads.requireRowUpdated(
                            rowCount, "instance '" + currentInst.id().value() + "' transitioning to COMPENSATED"))
                    .compose(v -> history.nextSequence(currentInst.id(), tx))
                    .compose(seq -> {
                        String histPayload = Json.encode(new CompensatedHistoryPayload(lifoStepIds.size()));
                        return history.append(
                                        new WorkflowHistoryEntry(
                                                currentInst.id(),
                                                seq,
                                                WorkflowEntryType.COMPENSATED,
                                                histPayload,
                                                clock.instant()),
                                        tx)
                                .compose(v2 -> eventEmitter.emitEvent(
                                        compensated,
                                        WorkflowEventType.WORKFLOW_COMPENSATED,
                                        seq,
                                        null,
                                        null,
                                        Map.of(),
                                        tx));
                    });
        });
    }

    /**
     * Records one compensation step's intent and history entry, incrementing the instance version.
     *
     * @param inst the current instance snapshot
     * @param forwardStepId the forward step id being compensated
     * @param rw the resolved runtime workflow
     * @param tx the active transaction
     * @return a {@link Future} resolving to the updated instance after this compensation step
     */
    private Future<WorkflowInstance> recordOneCompensationStep(
            WorkflowInstance inst, String forwardStepId, RuntimeWorkflow rw, SqlClient tx) {
        // Find the CompensationNode for this forward step.
        CompensationNode compNode = rw.compensationByForwardStepId().get(forwardStepId);
        if (compNode == null) {
            // No compensation node for this step — skip with no failure.
            return Future.succeededFuture(inst);
        }

        Object stateObj = Json.decodeValue(Buffer.buffer(inst.stateJson()), rw.stateType());
        Object compPayload;
        try {
            @SuppressWarnings("unchecked")
            var factory = (java.util.function.Function<Object, Object>)
                    (java.util.function.Function<?, ?>) rw.callbacks().payloadFactory(compNode.payloadCallbackId());
            compPayload = factory.apply(stateObj);
        } catch (Exception e) {
            return Future.failedFuture(new WorkflowDefinitionException(
                    "compensation payloadFactory threw for step '" + forwardStepId + "': " + e.getMessage()));
        }

        return history.nextSequence(inst.id(), tx).compose(seq -> {
            WorkflowSideEffectIntent intent = new WorkflowSideEffectIntent(
                    IntentKind.SERVICE,
                    compNode.targetId(),
                    compPayload,
                    Map.of(),
                    WorkflowSideEffectIntent.Correlation.singlePath(
                            inst.id(), seq, inst.definitionId(), compNode.stepId()));

            return recorders
                    .route(intent, tx)
                    .compose(result -> {
                        String histPayload = Json.encode(new CompensatingStepHistoryPayload(
                                forwardStepId, compNode.stepId(), compNode.targetId()));
                        return history.append(
                                new WorkflowHistoryEntry(
                                        inst.id(),
                                        seq,
                                        WorkflowEntryType.COMPENSATING_STEP,
                                        histPayload,
                                        clock.instant()),
                                tx);
                    })
                    .compose(v -> {
                        long prevVersion = inst.version();
                        WorkflowInstance updated =
                                inst.withVersion(prevVersion + 1).withUpdatedAt(clock.instant());
                        return instances
                                .updateOptimistic(updated, prevVersion, tx)
                                .map(rowCount -> {
                                    if (rowCount == 0) {
                                        throw new WorkflowConflictException(
                                                "Optimistic concurrency conflict for instance '"
                                                        + inst.id().value() + "' during compensation step '"
                                                        + forwardStepId + "'");
                                    }
                                    return updated;
                                });
                    });
        });
    }

    // --- Compensation matching ---

    /**
     * Encapsulates a single {@code SIDE_EFFECT_RECORDED} occurrence for a compensable dispatch
     * step, used by the per-step compensation matching algorithm.
     *
     * @param sequence the history sequence number of the dispatch occurrence
     * @param stepId the forward dispatch step id
     * @param node the {@link ServiceDispatchNode} for this dispatch
     */
    private record DispatchOccurrence(long sequence, String stepId, ServiceDispatchNode node) {}

    /**
     * Analyzes the history entries of an instance to determine which forward service-dispatch steps
     * were completed (i.e., their corresponding wait signal was received AFTER the dispatch was
     * recorded), and returns their step ids in LIFO order (most recent first).
     *
     * <p>A dispatch step is considered "completed" when:
     * <ul>
     *   <li>There is a {@code SIDE_EFFECT_RECORDED} entry for a {@link ServiceDispatchNode} that
     *       has a non-null {@code compensationStepId}.</li>
     *   <li>The {@link ServiceDispatchNode}'s {@code nextStepId} resolves to a
     *       {@link WaitSignalNode}.</li>
     *   <li>A {@code SIGNAL_RECEIVED} entry for that wait node's {@code signalName} appears in the
     *       history with a sequence number strictly greater than the dispatch's sequence number.</li>
     * </ul>
     *
     * <p>This per-step matching prevents over-compensation: a dispatch whose signal was never
     * received is excluded even if a different dispatch's signal was received.
     *
     * <p>The algorithm uses per-signal-name FIFOs of received-signal sequences. For each dispatch,
     * it finds the earliest signal sequence strictly greater than the dispatch sequence and consumes
     * it from the FIFO. This correctly handles plans that loop back through the same
     * {@link WaitSignalNode}: iteration N and iteration N+1 each consume a distinct signal
     * sequence entry, so both occurrences are independently matched.
     *
     * @param entries all history entries for the instance, ordered by sequence ascending
     * @param rw the resolved runtime workflow (used to look up node types)
     * @return forward step ids of completed compensable steps, in LIFO order (most recent first)
     */
    private static List<String> collectCompletedCompensableStepIds(
            List<WorkflowHistoryEntry> entries, RuntimeWorkflow rw) {
        // Per-name FIFO of received-signal sequences (oldest first).
        Map<String, ArrayDeque<Long>> signalSeqsByName = new HashMap<>();
        // Forward dispatches in arrival order, with their step ids.
        List<DispatchOccurrence> dispatches = new ArrayList<>();

        for (WorkflowHistoryEntry e : entries) {
            if (e.entryType() == WorkflowEntryType.SIGNAL_RECEIVED) {
                SignalReceivedHistoryPayload p = WorkflowPayloads.decodeOrFail(
                        e.payloadJson(), SignalReceivedHistoryPayload.class, e, "SIGNAL_RECEIVED");
                WorkflowPayloads.requireField(p.signalName(), "signalName", e, "SIGNAL_RECEIVED");
                signalSeqsByName
                        .computeIfAbsent(p.signalName(), k -> new ArrayDeque<>())
                        .add(e.sequence());
            } else if (e.entryType() == WorkflowEntryType.SIDE_EFFECT_RECORDED) {
                SideEffectRecordedHistoryPayload p = WorkflowPayloads.decodeOrFail(
                        e.payloadJson(), SideEffectRecordedHistoryPayload.class, e, "SIDE_EFFECT_RECORDED");
                WorkflowPayloads.requireField(p.stepId(), "stepId", e, "SIDE_EFFECT_RECORDED");
                WorkflowNode n = rw.nodeById().get(p.stepId());
                if (n instanceof ServiceDispatchNode sdn && sdn.compensationStepId() != null) {
                    dispatches.add(new DispatchOccurrence(e.sequence(), p.stepId(), sdn));
                }
            }
        }

        if (dispatches.isEmpty()) {
            return List.of();
        }

        // For each dispatch in arrival order, find the earliest unconsumed signal sequence
        // strictly greater than the dispatch's sequence and consume it from the per-name FIFO.
        List<String> completedStepIds = new ArrayList<>();
        for (DispatchOccurrence occurrence : dispatches) {
            WorkflowNode nextNode = rw.nodeById().get(occurrence.node().nextStepId());
            if (!(nextNode instanceof WaitSignalNode waitNode)) {
                // Next node is not a WaitSignalNode — completion cannot be determined via signal.
                continue;
            }
            String signalName = waitNode.signalName();
            ArrayDeque<Long> seqs = signalSeqsByName.get(signalName);
            if (seqs == null) {
                continue;
            }
            // Find the earliest signal sequence strictly greater than this dispatch's sequence.
            Iterator<Long> it = seqs.iterator();
            while (it.hasNext()) {
                long seq = it.next();
                if (seq > occurrence.sequence()) {
                    completedStepIds.add(occurrence.stepId());
                    it.remove();
                    break;
                }
            }
        }

        if (completedStepIds.isEmpty()) {
            return List.of();
        }

        // Reverse for LIFO order (most recently dispatched first).
        Collections.reverse(completedStepIds);
        return completedStepIds;
    }
}
