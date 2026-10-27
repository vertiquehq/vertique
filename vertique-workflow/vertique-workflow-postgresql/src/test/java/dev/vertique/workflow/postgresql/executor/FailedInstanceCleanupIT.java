// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.events.WorkflowEventIntent;
import dev.vertique.workflow.events.WorkflowEventType;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.RecordedCompletionIT.OpenTask;
import dev.vertique.workflow.postgresql.executor.RecordedRecoveryIT.BreakableTimeout;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.SignalFixture;
import dev.vertique.workflow.postgresql.executor.RetryAtWaitIT.BreakableDueTask;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TaskDriver;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TimerFixture;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TimerNode;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TimerRow;
import io.vertx.sqlclient.Tuple;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies what becomes of the rows a timer and a task leave behind: a timer whose command failed is closed with it, a
 * cancellation finds the task of an instance that failed, and a timer command that meets an instance that has ended
 * closes its timer.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
public class FailedInstanceCleanupIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_failed_instance_cleanup_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName("a timer that fails deterministically fails its command, its timer and its instance, and reports it")
    void deterministicTimerFailureFailsTheCommandTheTimerAndTheInstance() {
        BreakableTimeout breakable = new BreakableTimeout();
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), breakable, List.of("approve", "finish"))) {
            TimerFixture timers = new TimerFixture(f);
            Node node = f.node("engine").build();

            // Given an instance waiting with a timeout whose path throws
            WorkflowInstanceId id = f.waiting(node, "timer-failure");
            TimerRow timeout = timers.only(id, "TIMEOUT");

            // When the timeout falls due and runs
            timers.dueNow(timeout.id());
            f.idle(node, id);

            // Then the command is failed with a coded reason that holds no text of the failure
            TimerRow failed = timers.byId(timeout.id());
            assertThat(failed.state()).isEqualTo("FAILED");
            CommandRow command = f.fixture.commandsOf(id).stream()
                    .filter(row -> row.id() == timeout.id())
                    .findFirst()
                    .orElseThrow();
            assertThat(command.lastError()).startsWith(ReasonCodes.DETERMINISTIC_FAILURE + ": ");
            assertThat(command.lastError()).doesNotContain("timeout path broken");

            // And the timer row is closed with it and the instance is failed
            assertThat(timers.timerRowOf(failed).status()).isEqualTo("FAILED");
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");

            // And WORKFLOW_FAILED was reported once, with the reason
            List<WorkflowEventIntent> events = f.fixture.workflowEvents.stream()
                    .filter(event ->
                            event.eventType() == WorkflowEventType.WORKFLOW_FAILED && id.equals(event.workflowId()))
                    .toList();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().attributes().get("errorType")).isEqualTo(ReasonCodes.DETERMINISTIC_FAILURE);
        }
    }

    @Test
    @DisplayName("cancel of a failed instance cancels its open task, its due date and its reminders")
    void cancelOfAFailedInstanceCancelsItsOpenTask() {
        BreakableDueTask breakable = new BreakableDueTask();
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), breakable, List.of())) {
            TimerFixture timers = new TimerFixture(f);
            TaskDriver tasks = new TaskDriver(f);
            Node node = f.node("engine").build();

            // Given a task with a due date and a reminder, and an instance failed by its due date
            OpenTask task = tasks.open(node, "cancel-failed");
            timers.dueNow(timers.only(task.id(), "DUE").id());
            f.idle(node, task.id());
            assertThat(f.fixture.instanceOf(task.id()).status()).isEqualTo("FAILED");
            assertThat(tasks.statusOf(task)).isEqualTo("OPEN");

            // When the instance is cancelled
            cancel(f, node, task.id());

            // Then the task, its timers and its commands are all cancelled
            assertThat(f.fixture.instanceOf(task.id()).status()).isEqualTo("CANCELLED");
            assertThat(tasks.statusOf(task)).isEqualTo("CANCELLED");
            assertThat(openTimerRows(f, task.id())).isZero();
            assertThat(f.fixture.historyOf(task.id())).contains("TASK_CANCELLED");
        }
    }

    @Test
    @DisplayName("cancel finds the task of a failed instance from the task rows when the instance names no wait")
    void cancelFindsTheTaskOfAFailedInstanceThatNamesNoWait() {
        BreakableDueTask breakable = new BreakableDueTask();
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), breakable, List.of())) {
            TimerFixture timers = new TimerFixture(f);
            TaskDriver tasks = new TaskDriver(f);
            Node node = f.node("engine").build();

            // Given an instance failed by its due date, whose row names no wait (as a failure of an earlier release
            // left it)
            OpenTask task = tasks.open(node, "cancel-failed-no-wait");
            timers.dueNow(timers.only(task.id(), "DUE").id());
            f.idle(node, task.id());
            await(f.fixture
                    .observer()
                    .preparedQuery("UPDATE workflow_instances SET wait_type = NULL, wait_key = NULL, wait_aux_id = NULL"
                            + " WHERE id = $1")
                    .execute(Tuple.of(task.id().value())));

            // When the instance is cancelled
            cancel(f, node, task.id());

            // Then the open task and its timers are cancelled all the same
            assertThat(f.fixture.instanceOf(task.id()).status()).isEqualTo("CANCELLED");
            assertThat(tasks.statusOf(task)).isEqualTo("CANCELLED");
            assertThat(openTimerRows(f, task.id())).isZero();
            assertThat(f.fixture.historyOf(task.id())).contains("TASK_CANCELLED");
        }
    }

    @Test
    @DisplayName("a timer command claimed for an instance that has ended is settled stale and closes its timer")
    void timerCommandOfAnEndedInstanceClosesItsTimerAtClaim() {
        try (SignalFixture f =
                new SignalFixture(db.toPoolConfig(), new TimerNode(java.time.Duration.ofHours(1)), List.of())) {
            TimerFixture timers = new TimerFixture(f);
            Node node = f.node("engine").build();

            // Given an instance waiting at a timer node whose timer is due, and the instance ended meanwhile
            WorkflowInstanceId id = f.waiting(node, "timer-of-ended");
            TimerRow fire = timers.only(id, "FIRE");
            timers.dueNow(fire.id());
            f.fixture.endInstanceThroughFixtureCommand(id);

            // When an executor claims the command
            f.idle(node, id);

            // Then the command is stale and the timer is closed with it
            assertThat(timers.byId(fire.id()).state()).isEqualTo("STALE");
            assertThat(timers.timerRowOf(fire).status()).isEqualTo("CANCELLED");
        }
    }

    private static long openTimerRows(SignalFixture f, WorkflowInstanceId id) {
        return f.scalar("SELECT count(*) FROM workflow_timers WHERE workflow_id = $1 AND status = 'SCHEDULED'", id);
    }

    private static void cancel(SignalFixture f, Node node, WorkflowInstanceId id) {
        await(f.caller(() -> node.pool.withTransaction(tx -> node.engine.cancel(id, "cleanup", tx))));
    }
}
