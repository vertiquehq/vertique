// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.engine.executor.WorkflowSegments;
import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.SegmentCommand;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.state.WorkflowInstance;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import java.util.UUID;

/**
 * Test support: runs the segment of a recorded {@code SIGNAL} command of an instance on the engine, the way the
 * executor does after it has claimed and fenced the command. A signal is applied by a segment, not by the call that
 * records it, so the tests that observe how a signal is applied drive that segment.
 */
final class RecordedSignalSegments {

    private static final int MAX_STEPS = 1000;

    private RecordedSignalSegments() {}

    /**
     * Runs the segment of an instance-addressed signal with an empty payload and no explicit carrier.
     *
     * @param engine the engine assembled by the factory
     * @param instance the instance, as it is read when the segment starts
     * @param signalName the signal's name
     * @param tx the segment's transaction handle
     * @return a future that succeeds when the segment applied the signal and fails with the failure of the segment, or
     *     with an {@link AssertionError} when it settled the command any other way
     */
    static Future<Void> run(
            TransactionalWorkflowOperations<SqlClient> engine,
            WorkflowInstance instance,
            String signalName,
            SqlClient tx) {
        return run(engine, instance, signalName, new JsonObject(), "dedup-1", null, tx);
    }

    /**
     * Runs the segment of an instance-addressed signal.
     *
     * @param engine the engine assembled by the factory
     * @param instance the instance, as it is read when the segment starts
     * @param signalName the signal's name
     * @param payload the signal's payload
     * @param dedupKey the signal's idempotency key
     * @param explicitCarrierJson the explicit carrier as recorded, or {@code null}
     * @param tx the segment's transaction handle
     * @return a future that succeeds when the segment applied the signal and fails with the failure of the segment, or
     *     with an {@link AssertionError} when it settled the command any other way
     */
    static Future<Void> run(
            TransactionalWorkflowOperations<SqlClient> engine,
            WorkflowInstance instance,
            String signalName,
            Object payload,
            String dedupKey,
            @Nullable String explicitCarrierJson,
            SqlClient tx) {
        CommandHead head = new CommandHead(
                1L,
                instance.id(),
                "SIGNAL",
                instance.definitionScopeKey(),
                instance.definitionId(),
                instance.definitionVersion(),
                instance.planHash());
        ClaimedCommand claim = new ClaimedCommand(1L, instance.id(), "SIGNAL", UUID.randomUUID(), 1);
        SegmentCommand command = new SegmentCommand(
                1L,
                instance.id(),
                "SIGNAL",
                Json.encode(payload),
                new JsonObject().put("name", signalName).encode(),
                dedupKey,
                explicitCarrierJson);
        return ((WorkflowEngine) engine)
                .segments()
                .run(head, claim, command, tx, MAX_STEPS)
                .compose(outcome -> outcome == WorkflowSegments.Outcome.APPLIED
                        ? Future.<Void>succeededFuture()
                        : Future.<Void>failedFuture(new AssertionError(
                                "the signal segment ended with " + outcome + " instead of applying")));
    }
}
