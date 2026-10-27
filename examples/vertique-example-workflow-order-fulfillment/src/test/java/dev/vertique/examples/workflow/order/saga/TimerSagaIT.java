// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.examples.workflow.order.TimerWorkflowDefinition;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
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
 * <p>Timers are {@code TIMER} commands that the workflow executor runs when they come due. The tests move the fire time
 * of the pending commands to the database clock instead of waiting for the wall clock.
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
        closeComponent();
    }

    // --- Test 1: standalone timer fires and advances the workflow ---

    @Test
    @DisplayName("standalone timer fires after the configured delay and advances the workflow")
    void standaloneTimerFiresAndAdvancesWorkflow(VertxTestContext ctx) {
        String testId = "timer-standalone-1";
        TimerWorkflowDefinition.TimerStart start = new TimerWorkflowDefinition.TimerStart(testId);

        component
                .workflowOperations()
                .start(new StartCommand(
                        "timer-standalone",
                        TimerWorkflowDefinition.StandaloneTimerContract.class.getName(),
                        start,
                        testId,
                        null,
                        null,
                        null,
                        null,
                        null))
                .compose(id -> awaitIdle(id))
                .compose(id -> {
                    // Workflow should be WAITING on the timer step
                    return component.workflowOperations().query(id).map(view -> {
                        assertEquals(WorkflowStatus.WAITING, view.instance().status());
                        assertEquals(WaitType.TIMER, view.instance().waitType());
                        return id;
                    });
                })
                .compose(id -> fireDueTimers(id).map(v -> id))
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
                .start(new StartCommand(
                        "timer-signal-timeout",
                        TimerWorkflowDefinition.SignalWithTimeoutContract.class.getName(),
                        start,
                        testId,
                        null,
                        null,
                        null,
                        null,
                        null))
                .compose(id -> awaitIdle(id))
                .compose(id -> {
                    // Workflow should be WAITING on the signal/timeout step
                    return component.workflowOperations().query(id).map(view -> {
                        assertEquals(WorkflowStatus.WAITING, view.instance().status());
                        return id;
                    });
                })
                // Drive the timer job WITHOUT sending the signal — timeout path fires
                .compose(id -> fireDueTimers(id).map(v -> id))
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
                .start(new StartCommand(
                        "timer-signal-timeout",
                        TimerWorkflowDefinition.SignalWithTimeoutContract.class.getName(),
                        start,
                        testId,
                        null,
                        null,
                        null,
                        null,
                        null))
                .compose(id -> awaitIdle(id))
                .compose(id -> {
                    // Send the signal immediately — before any timer fires
                    var sig = new TimerWorkflowDefinition.OkSignal(testId);
                    return component
                            .workflowOperations()
                            .signal(id, "ok-signal", sig, testId + ":ok")
                            .compose(v -> awaitIdle(id));
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
                .start(new StartCommand(
                        "timer-standalone",
                        TimerWorkflowDefinition.StandaloneTimerContract.class.getName(),
                        start,
                        testId,
                        null,
                        null,
                        null,
                        null,
                        null))
                .compose(id -> awaitIdle(id))
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
     * Makes the timers of the workflow due now and waits until the executor has run them.
     *
     * <p>The timers are recorded as {@code TIMER} commands that fire 2 seconds after they were recorded. The test moves
     * the fire time of the pending timer commands, and of their timer rows, to the database clock so that it does not
     * wait for the wall clock; the executor of the component then runs the commands as segments.
     *
     * @param workflowId the workflow instance whose timers fire
     * @return a {@link Future} that completes when the instance is idle again
     */
    private static Future<Void> fireDueTimers(WorkflowInstanceId workflowId) {
        var pool = component.pgInboxOutboxRepository().pool();
        String sql = "WITH due AS (UPDATE workflow_commands SET fire_at = clock_timestamp(),"
                + " available_at = clock_timestamp() WHERE workflow_id = $1 AND kind = 'TIMER' AND state = 'PENDING'"
                + " RETURNING fire_at, timer_id) UPDATE workflow_timers t SET fire_at = due.fire_at FROM due"
                + " WHERE t.timer_id = due.timer_id";
        return pool.preparedQuery(sql)
                .execute(io.vertx.sqlclient.Tuple.of(workflowId.value()))
                .compose(rows -> awaitIdle(workflowId))
                .mapEmpty();
    }
}
