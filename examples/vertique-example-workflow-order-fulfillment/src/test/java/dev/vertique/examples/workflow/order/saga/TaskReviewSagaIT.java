// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.examples.workflow.order.TaskReviewWorkflowDefinition;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.exception.WorkflowTaskNotWaitingException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Tuple;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end integration tests for the cycle-3 human-task scenario.
 *
 * <p>Exercises the {@code "task-review"} workflow definition via the full Dagger component stack,
 * proving that {@link dev.vertique.workflow.tasks.di.WorkflowTasksModule} composes correctly with
 * {@link dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule} and the rest of the
 * application graph.
 *
 * <p>Two scenarios are covered:
 * <ul>
 *   <li><strong>Happy-path approval</strong> — start a workflow, assert it suspends on a
 *       {@link WaitType#TASK} wait, locate the task id from the database, complete the task with
 *       decision {@code "approve"}, assert the workflow advances to {@code COMPLETED} with the
 *       correct state, and assert {@code TASK_CREATED} / {@code TASK_COMPLETED} history entries
 *       are present.</li>
 *   <li><strong>Duplicate-completion guard</strong> — complete a task, then try to complete it again
 *       after the workflow has already advanced. The second call must fail with
 *       {@link dev.vertique.workflow.exception.WorkflowTaskNotWaitingException} (STALE_NOOP path) while
 *       the workflow remains correctly {@code COMPLETED} (no double-transition).</li>
 * </ul>
 *
 * <p>The test drives the workflow directly via {@link dev.vertique.workflow.ops.WorkflowOperations}
 * and {@link dev.vertique.workflow.tasks.TaskService} — no Vert.x service verticles are deployed.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class TaskReviewSagaIT extends SagaTestBase {

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        setUpComponent(vertx).onComplete(ar -> {
            if (ar.succeeded()) {
                ctx.completeNow();
            } else {
                ctx.failNow(ar.cause());
            }
        });
    }

    @BeforeEach
    void reset(VertxTestContext ctx) {
        truncateTables().onComplete(ar -> ctx.completeNow());
    }

    @AfterAll
    static void tearDown() {
        if (component != null && component.pgInboxOutboxRepository() != null) {
            component.pgInboxOutboxRepository().pool().close();
        }
    }

    // --- Test 1: happy-path approval ---

    @Test
    @DisplayName("task-review happy path: start → WAITING/TASK → approve → COMPLETED with correct state and history")
    void happyPathApprovalCompletesWorkflow(VertxTestContext ctx) {
        String testId = "task-review-happy-1";
        var start = new TaskReviewWorkflowDefinition.TaskReviewStart(testId);
        String idempotencyKey = "approve-" + testId;

        component
                .workflowOperations()
                .start(new StartCommand("task-review", start, testId, null, null))
                .compose(id -> {
                    // Workflow must be WAITING on a TASK step
                    return component.workflowOperations().query(id).map(view -> {
                        assertEquals(WorkflowStatus.WAITING, view.instance().status(), "must be WAITING after start");
                        assertEquals(WaitType.TASK, view.instance().waitType(), "wait type must be TASK");
                        assertEquals("review", view.instance().currentStepId(), "must be at 'review' step");
                        return id;
                    });
                })
                .compose(id -> findTaskId(id).map(taskId -> new Object[] {id, taskId}))
                .compose(pair -> {
                    WorkflowInstanceId id = (WorkflowInstanceId) pair[0];
                    UUID taskId = (UUID) pair[1];
                    var cmd = new TaskCompletionCommand(
                            taskId, "approve", "needs-fix", idempotencyKey, new WorkflowActor.User("alice"), null);
                    return component.taskService().complete(cmd).map(v -> id);
                })
                .compose(id -> component.workflowOperations().query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "workflow query must succeed: " + ar.cause());
                    var view = ar.result();

                    assertEquals(
                            WorkflowStatus.COMPLETED, view.instance().status(), "must be COMPLETED after approval");
                    assertEquals("done", view.instance().currentStepId(), "must have advanced to 'done' step");

                    // Verify state encodes the decision outcome
                    assertTrue(
                            view.instance().stateJson().contains("approved:needs-fix"),
                            "state must contain 'approved:needs-fix' but was: "
                                    + view.instance().stateJson());

                    // Verify history contains TASK_CREATED and TASK_COMPLETED
                    var entryTypes = view.recentHistory().stream()
                            .map(e -> e.entryType())
                            .collect(Collectors.toList());
                    assertTrue(entryTypes.contains(WorkflowEntryType.TASK_CREATED), "history must have TASK_CREATED");
                    assertTrue(
                            entryTypes.contains(WorkflowEntryType.TASK_COMPLETED), "history must have TASK_COMPLETED");
                    assertTrue(entryTypes.contains(WorkflowEntryType.COMPLETED), "history must have COMPLETED");

                    ctx.completeNow();
                }));
    }

    // --- Test 2: duplicate-completion guard ---

    @Test
    @DisplayName(
            "task-review duplicate guard: second completion after workflow advanced fails with WorkflowTaskNotWaitingException")
    void secondCompletionAfterTransitionFails(VertxTestContext ctx) {
        String testId = "task-review-idem-1";
        var start = new TaskReviewWorkflowDefinition.TaskReviewStart(testId);

        component
                .workflowOperations()
                .start(new StartCommand("task-review", start, testId, null, null))
                .compose(id -> findTaskId(id).map(taskId -> new Object[] {id, taskId}))
                .compose(pair -> {
                    WorkflowInstanceId id = (WorkflowInstanceId) pair[0];
                    UUID taskId = (UUID) pair[1];
                    var firstCmd = new TaskCompletionCommand(
                            taskId, "approve", "ok", "approve-first-" + testId, new WorkflowActor.User("bob"), null);
                    var secondCmd = new TaskCompletionCommand(
                            taskId, "approve", "ok", "approve-second-" + testId, new WorkflowActor.User("bob"), null);
                    // First completion transitions the workflow; second must be rejected as stale.
                    return component.taskService().complete(firstCmd).compose(v -> component
                            .taskService()
                            .complete(secondCmd)
                            .transform(ar -> {
                                // Second call MUST fail with WorkflowTaskNotWaitingException
                                if (ar.succeeded()) {
                                    return Future.failedFuture(
                                            new AssertionError("second complete() must fail but succeeded"));
                                }
                                if (!(ar.cause() instanceof WorkflowTaskNotWaitingException)) {
                                    return Future.failedFuture(
                                            new AssertionError("expected WorkflowTaskNotWaitingException but got: "
                                                    + ar.cause().getClass().getName()));
                                }
                                // Expected: swallow the expected failure and return success
                                return Future.<WorkflowInstanceId>succeededFuture(id);
                            }));
                })
                .compose(id -> component.workflowOperations().query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "query after duplicate attempt must succeed: " + ar.cause());
                    var view = ar.result();

                    // Workflow must be COMPLETED exactly once — no double transition
                    assertEquals(
                            WorkflowStatus.COMPLETED,
                            view.instance().status(),
                            "must be COMPLETED after the first (and only) valid completion");

                    // Exactly one TASK_COMPLETED entry — the second call was rejected before writing
                    long taskCompletedCount = view.recentHistory().stream()
                            .filter(e -> WorkflowEntryType.TASK_COMPLETED.equals(e.entryType()))
                            .count();
                    assertEquals(1L, taskCompletedCount, "exactly one TASK_COMPLETED history entry must exist");

                    ctx.completeNow();
                }));
    }

    // --- Helpers ---

    /**
     * Reads the most recently created task id for the given workflow instance from the database.
     *
     * @param workflowId the workflow instance whose task id should be retrieved
     * @return a {@link Future} resolving to the task UUID
     */
    private static Future<UUID> findTaskId(WorkflowInstanceId workflowId) {
        return component
                .pgInboxOutboxRepository()
                .pool()
                .preparedQuery(
                        "SELECT task_id FROM workflow_tasks WHERE workflow_id = $1 ORDER BY task_id DESC LIMIT 1")
                .execute(Tuple.of(workflowId.value()))
                .compose(rows -> {
                    if (!rows.iterator().hasNext()) {
                        return Future.failedFuture(
                                new AssertionError("No task found for workflow: " + workflowId.value()));
                    }
                    return Future.succeededFuture(rows.iterator().next().getUUID("task_id"));
                });
    }
}
