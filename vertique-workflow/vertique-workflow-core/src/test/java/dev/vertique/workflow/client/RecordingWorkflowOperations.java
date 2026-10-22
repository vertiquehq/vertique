// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.ops.WorkflowView;
import io.vertx.core.Future;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Test double that records what a client proxy sends to {@link WorkflowOperations} so a test can
 * compare the commands, signals and queries field by field.
 *
 * <p>{@code start} answers with one fixed instance id; every other operation completes
 * successfully with no result.
 */
final class RecordingWorkflowOperations implements WorkflowOperations {

    /** A recorded {@code signal} call. */
    record SignalCall(WorkflowInstanceId id, String signalName, Object payload, String signalDedupKey) {}

    /** The instance id every recorded start answers with. */
    static final WorkflowInstanceId STARTED_ID =
            new WorkflowInstanceId(UUID.fromString("00000000-0000-0000-0000-000000000042"));

    private final List<StartCommand> starts = new ArrayList<>();
    private final List<SignalCall> signals = new ArrayList<>();
    private final List<WorkflowInstanceId> queries = new ArrayList<>();

    /**
     * Returns the start commands received so far, in call order.
     *
     * @return a snapshot of the recorded commands
     */
    List<StartCommand> starts() {
        return List.copyOf(starts);
    }

    /**
     * Returns the signal calls received so far, in call order.
     *
     * @return a snapshot of the recorded calls
     */
    List<SignalCall> signals() {
        return List.copyOf(signals);
    }

    /**
     * Returns the instance ids queried so far, in call order.
     *
     * @return a snapshot of the recorded ids
     */
    List<WorkflowInstanceId> queries() {
        return List.copyOf(queries);
    }

    @Override
    public Future<WorkflowInstanceId> start(StartCommand cmd) {
        starts.add(cmd);
        return Future.succeededFuture(STARTED_ID);
    }

    @Override
    public Future<Void> signal(WorkflowInstanceId id, String signalName, Object payload, String signalDedupKey) {
        signals.add(new SignalCall(id, signalName, payload, signalDedupKey));
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> cancel(WorkflowInstanceId id, String reason) {
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> retry(WorkflowInstanceId id) {
        return Future.succeededFuture();
    }

    @Override
    public Future<WorkflowView> query(WorkflowInstanceId id) {
        queries.add(id);
        return Future.succeededFuture();
    }
}
