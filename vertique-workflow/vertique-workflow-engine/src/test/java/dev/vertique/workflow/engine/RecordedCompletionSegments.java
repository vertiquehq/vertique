// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.engine.executor.WorkflowSegments;
import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.SegmentCommand;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.tasks.TaskRecord;
import dev.vertique.workflow.tasks.TaskStatus;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import java.util.UUID;

/**
 * Test support: runs the segment of a recorded {@code COMPLETION} command on the engine, the way the executor does after
 * it has claimed and fenced the command. A completion is applied by a segment, not by the call that records it, so the
 * tests that observe how a completion is applied drive that segment. The task the completion reserves must read as
 * {@code COMPLETING} under {@link #COMMAND_ID}; {@link #reserved} turns an open task row into that.
 */
final class RecordedCompletionSegments {

    /** The id of the recorded completion command, and so the owner of the reservation. */
    static final long COMMAND_ID = 1L;

    private static final int MAX_STEPS = 1000;

    private RecordedCompletionSegments() {}

    /**
     * Returns the task row as it reads while the recorded completion {@link #COMMAND_ID} reserves it.
     *
     * @param task the task row
     * @return the same row, {@code COMPLETING} and owned by the command
     */
    static TaskRecord reserved(TaskRecord task) {
        return new TaskRecord(
                task.taskId(),
                task.workflowId(),
                task.stepId(),
                task.assignment(),
                TaskStatus.COMPLETING,
                task.decisions(),
                task.dueAt(),
                task.dueDateTimerId(),
                task.completedAt(),
                task.cancelledAt(),
                task.expiredAt(),
                task.updatedAt(),
                task.decisionName(),
                task.decisionPayloadJson(),
                task.completedBy(),
                task.cancelledBy(),
                task.reassignedBy(),
                task.cancellationReason(),
                task.reassignmentReason(),
                task.subjectVersionAtCreation(),
                task.branchTokenId(),
                task.forkStepId(),
                task.branchId(),
                task.scopeKey(),
                task.requireVersionStability(),
                COMMAND_ID);
    }

    /**
     * Runs the segment of the completion of {@code task} with the decision {@code approve}, an empty payload and the
     * user {@code user-1}.
     *
     * @param engine the engine assembled by the factory
     * @param instance the instance, as it is read when the segment starts
     * @param task the reserved task, as {@link #reserved} returns it
     * @param tx the segment's transaction handle
     * @return a future that succeeds when the segment applied the completion and fails with the failure of the segment,
     *     or with an {@link AssertionError} when it settled the command any other way
     */
    static Future<Void> run(
            TransactionalWorkflowOperations<SqlClient> engine,
            WorkflowInstance instance,
            TaskRecord task,
            SqlClient tx) {
        return run(engine, instance, task, "approve", new JsonObject(), new WorkflowActor.User("user-1"), tx);
    }

    /**
     * Runs the segment of a recorded completion.
     *
     * @param engine the engine assembled by the factory
     * @param instance the instance, as it is read when the segment starts
     * @param task the reserved task, as {@link #reserved} returns it
     * @param decision the decision's name
     * @param payload the decision's payload
     * @param actor the completing actor
     * @param tx the segment's transaction handle
     * @return a future that succeeds when the segment applied the completion and fails with the failure of the segment,
     *     or with an {@link AssertionError} when it settled the command any other way
     */
    static Future<Void> run(
            TransactionalWorkflowOperations<SqlClient> engine,
            WorkflowInstance instance,
            TaskRecord task,
            String decision,
            Object payload,
            WorkflowActor actor,
            SqlClient tx) {
        CommandHead head = new CommandHead(
                COMMAND_ID,
                instance.id(),
                "COMPLETION",
                instance.definitionScopeKey(),
                instance.definitionId(),
                instance.definitionVersion(),
                instance.planHash());
        ClaimedCommand claim = new ClaimedCommand(COMMAND_ID, instance.id(), "COMPLETION", UUID.randomUUID(), 1);
        JsonObject actorJson = new JsonObject().put("kind", "USER").put("value", ((WorkflowActor.User) actor).userId());
        SegmentCommand command = new SegmentCommand(
                COMMAND_ID,
                instance.id(),
                "COMPLETION",
                Json.encode(payload),
                new JsonObject()
                        .put("taskId", task.taskId().toString())
                        .put("decision", decision)
                        .put("completedBy", actorJson)
                        .encode(),
                "idem-1");
        return ((WorkflowEngine) engine)
                .segments()
                .run(head, claim, command, tx, MAX_STEPS)
                .compose(outcome -> outcome == WorkflowSegments.Outcome.APPLIED
                        ? Future.<Void>succeededFuture()
                        : Future.<Void>failedFuture(new AssertionError(
                                "the completion segment ended with " + outcome + " instead of applying")));
    }
}
