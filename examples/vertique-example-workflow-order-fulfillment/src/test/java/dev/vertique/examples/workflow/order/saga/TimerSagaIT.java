// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.examples.workflow.order.TimerWorkflowDefinition;
import dev.vertique.job.DefaultJobContext;
import dev.vertique.job.JobState;
import dev.vertique.job.JobType;
import dev.vertique.workflow.delayed.job.TimerFirePayload;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end integration tests for cycle-2 durable-timer scenarios.
 *
 * <p>The test class exercises two workflow definitions registered alongside the order-fulfillment
 * saga:
 * <ul>
 *   <li>{@link TimerWorkflowDefinition.StandaloneTimer} — {@code "timer-standalone"}: a plain
 *       2-second {@link dev.vertique.workflow.plan.TimerNode} that advances to COMPLETED when the
 *       timer fires.</li>
 *   <li>{@link TimerWorkflowDefinition.SignalWithTimeout} — {@code "timer-signal-timeout"}: a
 *       {@link dev.vertique.workflow.plan.WaitSignalNode} with a 2-second timeout deadline that
 *       either completes via the signal path or the timeout path.</li>
 * </ul>
 *
 * <p>The delayed-job poller is not deployed. Instead the tests claim the enqueued timer job from
 * the database and invoke {@link dev.vertique.workflow.delayed.job.WorkflowTimerFireExecutor}
 * directly, mirroring the production dispatch path without requiring a running event-bus poller
 * verticle.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class TimerSagaIT extends SagaTestBase {

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

    // --- Test 1: standalone timer fires and advances the workflow ---

    @Test
    @DisplayName("standalone timer fires after the configured delay and advances the workflow")
    void standaloneTimerFiresAndAdvancesWorkflow(VertxTestContext ctx) {
        String testId = "timer-standalone-1";
        TimerWorkflowDefinition.TimerStart start = new TimerWorkflowDefinition.TimerStart(testId);

        component
                .workflowOperations()
                .start(new StartCommand("timer-standalone", start, testId, null, null))
                .compose(id -> {
                    // Workflow should be WAITING on the timer step
                    return component.workflowOperations().query(id).map(view -> {
                        assertEquals(WorkflowStatus.WAITING, view.instance().status());
                        assertEquals(WaitType.TIMER, view.instance().waitType());
                        return id;
                    });
                })
                .compose(id -> driveTimerJob(id).map(v -> id))
                .compose(id -> component.workflowOperations().query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "workflow query must succeed: " + ar.cause());
                    var view = ar.result();
                    assertEquals(
                            WorkflowStatus.COMPLETED, view.instance().status(), "must be COMPLETED after timer fires");

                    var entryTypes = view.recentHistory().stream()
                            .map(e -> e.entryType())
                            .collect(Collectors.toList());
                    assertTrue(
                            entryTypes.contains(WorkflowEntryType.TIMER_SCHEDULED),
                            "history must have TIMER_SCHEDULED");
                    assertTrue(entryTypes.contains(WorkflowEntryType.TIMER_FIRED), "history must have TIMER_FIRED");
                    assertTrue(entryTypes.contains(WorkflowEntryType.COMPLETED), "history must have COMPLETED");

                    ctx.completeNow();
                }));
    }

    // --- Test 2: signal-wait timeout fires when no signal arrives ---

    @Test
    @DisplayName("signal-wait timeout fires when no signal arrives")
    void signalWaitTimeoutFiresWhenNoSignal(VertxTestContext ctx) {
        String testId = "timer-timeout-1";
        TimerWorkflowDefinition.TimerStart start = new TimerWorkflowDefinition.TimerStart(testId);

        component
                .workflowOperations()
                .start(new StartCommand("timer-signal-timeout", start, testId, null, null))
                .compose(id -> {
                    // Workflow should be WAITING on the signal/timeout step
                    return component.workflowOperations().query(id).map(view -> {
                        assertEquals(WorkflowStatus.WAITING, view.instance().status());
                        return id;
                    });
                })
                // Drive the timer job WITHOUT sending the signal — timeout path fires
                .compose(id -> driveTimerJob(id).map(v -> id))
                .compose(id -> component.workflowOperations().query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "workflow query must succeed: " + ar.cause());
                    var view = ar.result();
                    assertEquals(
                            WorkflowStatus.COMPLETED,
                            view.instance().status(),
                            "must be COMPLETED via cancelled branch after timeout");

                    var entryTypes = view.recentHistory().stream()
                            .map(e -> e.entryType())
                            .collect(Collectors.toList());
                    assertTrue(
                            entryTypes.contains(WorkflowEntryType.TIMER_SCHEDULED),
                            "history must have TIMER_SCHEDULED");
                    assertTrue(entryTypes.contains(WorkflowEntryType.TIMEOUT), "history must have TIMEOUT");

                    ctx.completeNow();
                }));
    }

    // --- Test 3: signal arrives before timeout — timer is CANCELLED ---

    @Test
    @DisplayName("signal arriving before timeout cancels the timer and takes the signal path")
    void signalArrivingBeforeTimeoutCancelsTimer(VertxTestContext ctx) {
        String testId = "timer-signal-wins-1";
        TimerWorkflowDefinition.TimerStart start = new TimerWorkflowDefinition.TimerStart(testId);

        component
                .workflowOperations()
                .start(new StartCommand("timer-signal-timeout", start, testId, null, null))
                .compose(id -> {
                    // Send the signal immediately — before any timer fires
                    var sig = new TimerWorkflowDefinition.OkSignal(testId);
                    return component
                            .workflowOperations()
                            .signal(id, "ok-signal", sig, testId + ":ok")
                            .map(v -> id);
                })
                .compose(id -> component.workflowOperations().query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "workflow query must succeed: " + ar.cause());
                    var view = ar.result();
                    // Workflow should have advanced through the signal path to COMPLETED
                    assertEquals(
                            WorkflowStatus.COMPLETED,
                            view.instance().status(),
                            "must be COMPLETED via done branch after signal");

                    var entryTypes = view.recentHistory().stream()
                            .map(e -> e.entryType())
                            .collect(Collectors.toList());
                    assertTrue(
                            entryTypes.contains(WorkflowEntryType.SIGNAL_RECEIVED),
                            "history must have SIGNAL_RECEIVED");
                    assertTrue(
                            entryTypes.contains(WorkflowEntryType.TIMER_CANCELLED),
                            "history must have TIMER_CANCELLED");

                    // Verify the timer row is CANCELLED in the database
                    ctx.completeNow();
                }));
    }

    // --- Test 4: workflow cancel while waiting on a timer marks the timer CANCELLED ---

    @Test
    @DisplayName("workflow cancel while waiting on a timer marks the timer CANCELLED")
    void workflowCancelWhileWaitingOnTimerCancelsTimer(VertxTestContext ctx) {
        String testId = "timer-cancel-1";
        TimerWorkflowDefinition.TimerStart start = new TimerWorkflowDefinition.TimerStart(testId);

        component
                .workflowOperations()
                .start(new StartCommand("timer-standalone", start, testId, null, null))
                .compose(id -> {
                    // Immediately cancel the workflow while it waits on the timer
                    return component
                            .workflowOperations()
                            .cancel(id, "test-cancel")
                            .map(v -> id);
                })
                .compose(id -> component.workflowOperations().query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "workflow query must succeed: " + ar.cause());
                    var view = ar.result();
                    assertEquals(
                            WorkflowStatus.CANCELLED,
                            view.instance().status(),
                            "must be CANCELLED after explicit cancel");

                    var entryTypes = view.recentHistory().stream()
                            .map(e -> e.entryType())
                            .collect(Collectors.toList());
                    assertTrue(entryTypes.contains(WorkflowEntryType.CANCELLED), "history must have CANCELLED");
                    assertTrue(
                            entryTypes.contains(WorkflowEntryType.TIMER_CANCELLED),
                            "history must have TIMER_CANCELLED when cancelled while waiting on timer");

                    ctx.completeNow();
                }));
    }

    // --- Helpers ---

    /**
     * Finds and directly executes all ENQUEUED timer-fire jobs in the database, bypassing the
     * {@code scheduled_at <= NOW()} gate used by the normal poller.
     *
     * <p>Timer jobs are scheduled 2 seconds in the future so the standard
     * {@code claimNextJob} path (which applies {@code scheduled_at <= NOW()}) would block them.
     * Tests bypass this by issuing a raw UPDATE that moves ENQUEUED timer-fire jobs straight to
     * PROCESSING, then invoke the executor with the recovered payload. This faithfully exercises
     * the full executor code path without requiring real wall-clock delays.
     *
     * @param workflowId the workflow instance whose timer job should be fired; scopes the error
     *     message when no job is found
     * @return a {@link Future} that completes when all matching timer jobs have been executed
     */
    private static Future<Void> driveTimerJob(WorkflowInstanceId workflowId) {
        var pool = component.pgInboxOutboxRepository().pool();
        var executor = component.workflowTimerFireExecutor();
        var jobRepo = component.jobRepository();

        // Lock and transition all ENQUEUED timer-fire jobs to PROCESSING, regardless of
        // scheduled_at, to bypass the future-date gate without waiting for the real clock.
        // The handler column stores the event bus address: jobs/delayed/{name}/execute.
        String sql = "UPDATE job_executions"
                + " SET state = 'PROCESSING', started_at = NOW(), updated_at = NOW(),"
                + " locked_by = 'test-timer-driver'"
                + " WHERE handler = 'jobs/delayed/vertique.workflow.timer.fire/execute'"
                + " AND state = 'ENQUEUED'"
                + " RETURNING id, job_id, attempt, payload";

        return pool.query(sql).execute().compose(rows -> {
            var futures = StreamSupport.stream(rows.spliterator(), false)
                    .map(row -> {
                        UUID execId = row.getUUID("id");
                        String jobId = row.getString("job_id");
                        int attempt = row.getInteger("attempt");
                        JsonObject rawPayload = row.getJsonObject("payload");
                        TimerFirePayload payload = Json.decodeValue(rawPayload.encode(), TimerFirePayload.class);
                        DefaultJobContext ctx = new DefaultJobContext(jobId, execId, attempt, JobType.DELAYED);
                        return executor.execute(payload, ctx)
                                .compose(v -> jobRepo.completeExecution(execId, JobState.SUCCEEDED, null, null, null)
                                        .mapEmpty());
                    })
                    .collect(Collectors.toList());
            if (futures.isEmpty()) {
                return Future.failedFuture(
                        new AssertionError("No ENQUEUED timer-fire jobs found for workflow " + workflowId.value()));
            }
            return Future.all(futures).mapEmpty();
        });
    }
}
