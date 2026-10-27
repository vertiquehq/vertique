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
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import java.util.UUID;

/**
 * Test support: runs the segment of a recorded {@code RETRY} or {@code MIGRATION} command of an instance on the engine,
 * the way the executor does after it has claimed and fenced the command. A retry and a migration are carried out by a
 * segment, not by the call that records them, so the tests that observe how they are carried out drive that segment.
 */
final class RecordedRecoverySegments {

    private static final int MAX_STEPS = 1000;

    private RecordedRecoverySegments() {}

    /**
     * Runs the segment of a migration recorded without a target scope.
     *
     * @param engine the engine assembled by the factory
     * @param instance the instance, as it is read when the segment starts
     * @param targetVersion the version the migration moves the instance to
     * @param tx the segment's transaction handle
     * @return a future of what the segment came to
     */
    static Future<WorkflowSegments.Outcome> migrate(
            TransactionalWorkflowOperations<SqlClient> engine,
            WorkflowInstance instance,
            long targetVersion,
            SqlClient tx) {
        return migrate(engine, instance, targetVersion, null, null, tx);
    }

    /**
     * Runs the segment of a migration.
     *
     * @param engine the engine assembled by the factory
     * @param instance the instance, as it is read when the segment starts
     * @param targetVersion the version the migration moves the instance to
     * @param targetScope the scope of the target definition, or {@code null} for the scope the instance is pinned to
     * @param contextJson the durable context captured when the migration was recorded, in the carrier wrapper, or
     *     {@code null}
     * @param tx the segment's transaction handle
     * @return a future of what the segment came to
     */
    static Future<WorkflowSegments.Outcome> migrate(
            TransactionalWorkflowOperations<SqlClient> engine,
            WorkflowInstance instance,
            long targetVersion,
            @Nullable String targetScope,
            @Nullable String contextJson,
            SqlClient tx) {
        JsonObject target = new JsonObject().put("version", targetVersion);
        if (targetScope != null) {
            target.put("scope", targetScope);
        }
        CommandHead head = new CommandHead(
                1L,
                instance.id(),
                "MIGRATION",
                instance.definitionScopeKey(),
                instance.definitionId(),
                instance.definitionVersion(),
                instance.planHash(),
                false,
                targetVersion,
                targetScope);
        return run(engine, head, instance, "MIGRATION", target.encode(), contextJson, tx);
    }

    /**
     * Runs the segment of a retry.
     *
     * @param engine the engine assembled by the factory
     * @param instance the instance, as it is read when the segment starts
     * @param contextJson the durable context captured when the retry was recorded, in the carrier wrapper, or
     *     {@code null}
     * @param tx the segment's transaction handle
     * @return a future of what the segment came to
     */
    static Future<WorkflowSegments.Outcome> retry(
            TransactionalWorkflowOperations<SqlClient> engine,
            WorkflowInstance instance,
            @Nullable String contextJson,
            SqlClient tx) {
        CommandHead head = new CommandHead(
                1L,
                instance.id(),
                "RETRY",
                instance.definitionScopeKey(),
                instance.definitionId(),
                instance.definitionVersion(),
                instance.planHash());
        return run(engine, head, instance, "RETRY", null, contextJson, tx);
    }

    private static Future<WorkflowSegments.Outcome> run(
            TransactionalWorkflowOperations<SqlClient> engine,
            CommandHead head,
            WorkflowInstance instance,
            String kind,
            @Nullable String targetJson,
            @Nullable String contextJson,
            SqlClient tx) {
        ClaimedCommand claim = new ClaimedCommand(1L, instance.id(), kind, UUID.randomUUID(), 1);
        SegmentCommand command = new SegmentCommand(1L, instance.id(), kind, null, targetJson, null, null, contextJson);
        return ((WorkflowEngine) engine).segments().run(head, claim, command, tx, MAX_STEPS);
    }
}
