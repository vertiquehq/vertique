// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.RecordedSignalIT.signal;
import static dev.vertique.workflow.postgresql.executor.RecordedSignalIT.waitFor;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.RaceSafety;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.RecordedCompletionIT.OpenTask;
import dev.vertique.workflow.postgresql.executor.RecordedRecoveryIT.BreakableTimeout;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.Definition;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.SignalFixture;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TaskDriver;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TimerFixture;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TimerRow;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a retry returns an instance that failed at a timed wait to that wait: the wait's handler does not run
 * again, no second timer or task is recorded for a wait the instance never left, and the timer whose command failed is
 * offered again once, with its original fire time, so that a deadline stands.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
public class RetryAtWaitIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_retry_at_wait_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName("retry of an instance failed at a timed signal wait returns to the wait and offers its timeout once")
    void retryReturnsToATimedSignalWaitAndOffersItsTimeoutOnce() {
        BreakableTimeout breakable = new BreakableTimeout();
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), breakable, List.of("approve", "finish"))) {
            TimerFixture timers = new TimerFixture(f);
            Node node = f.node("engine").singleSegments().build();

            // Given an instance failed by its timeout path
            WorkflowInstanceId id = f.waiting(node, "timed-signal");
            TimerRow timeout = timers.only(id, "TIMEOUT");
            timers.dueNow(timeout.id());
            f.idle(node, id);
            TimerRow failed = timers.byId(timeout.id());
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(failed.state()).isEqualTo("FAILED");
            assertThat(timers.timerRowOf(failed).status()).isEqualTo("FAILED");
            assertThat(f.snapshot(id).getString("wait_type")).isEqualTo("SIGNAL");

            // When the timeout path is fixed and the retry alone runs
            breakable.broken.set(false);
            retry(f, node, id);
            node.runOneSegment();

            // Then the instance waits at the same wait, with the one timer row it had, offered again
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
            assertThat(f.snapshot(id).getString("wait_type")).isEqualTo("SIGNAL");
            assertThat(f.snapshot(id).getString("wait_key")).isEqualTo("approve");
            assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("w");
            assertThat(f.scalar("SELECT count(*) FROM workflow_timers WHERE workflow_id = $1", id))
                    .isEqualTo(1);
            List<TimerRow> offered = timers.all(id);
            assertThat(offered).extracting(TimerRow::state).containsExactly("FAILED", "PENDING");
            TimerRow replacement = offered.getLast();
            assertThat(replacement.timerId()).isEqualTo(failed.timerId());
            assertThat(replacement.timerKind()).isEqualTo("TIMEOUT");
            assertThat(replacement.fireAt().toInstant())
                    .isEqualTo(failed.fireAt().toInstant());
            assertThat(replacement.target().getString("stepId")).isEqualTo("w");
            assertThat(timers.timerRowOf(replacement).status()).isEqualTo("SCHEDULED");
            assertThat(timers.timerRowOf(replacement).commandId()).isEqualTo(replacement.id());

            // And when the executors run on, the timeout fires once and the instance moves to its next wait
            f.idle(node, id);
            assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("late");
            assertThat(timers.all(id)).extracting(TimerRow::state).containsExactly("FAILED", "COMPLETE");
            assertThat(timers.timerRowOf(replacement).status()).isEqualTo("FIRED");
            assertThat(f.scalar("SELECT count(*) FROM workflow_timers WHERE workflow_id = $1", id))
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a retry whose timeout path is still broken fails again without recording another timer")
    void retryWithAStillBrokenTimeoutFailsAgainWithOneTimerRow() {
        BreakableTimeout breakable = new BreakableTimeout();
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), breakable, List.of("approve", "finish"))) {
            TimerFixture timers = new TimerFixture(f);
            Node node = f.node("engine").build();

            // Given an instance failed by its timeout path
            WorkflowInstanceId id = f.waiting(node, "still-broken");
            timers.dueNow(timers.only(id, "TIMEOUT").id());
            f.idle(node, id);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");

            // When a retry runs while the path is still broken, twice
            retry(f, node, id);
            f.idle(node, id);
            retry(f, node, id);
            f.idle(node, id);

            // Then the instance is failed again, at the same wait, with one timer row and one command per failure
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(f.snapshot(id).getString("wait_type")).isEqualTo("SIGNAL");
            assertThat(f.scalar("SELECT count(*) FROM workflow_timers WHERE workflow_id = $1", id))
                    .isEqualTo(1);
            assertThat(timers.all(id)).extracting(TimerRow::state).containsExactly("FAILED", "FAILED", "FAILED");

            // And a retry after the fix completes the wait once
            breakable.broken.set(false);
            retry(f, node, id);
            f.idle(node, id);
            assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("late");
            assertThat(timers.all(id)).extracting(TimerRow::state).endsWith("COMPLETE");
            assertThat(f.scalar("SELECT count(*) FROM workflow_timers WHERE workflow_id = $1", id))
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a signal recorded after the timeout's fire time stays parked behind the timeout offered again")
    void signalRecordedBetweenTheFireTimesDoesNotOvertakeTheTimeout() {
        BreakableTimeout breakable = new BreakableTimeout();
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), breakable, List.of("approve", "finish"))) {
            TimerFixture timers = new TimerFixture(f);
            Node node = f.node("engine").singleSegments().build();

            // Given an instance failed by its timeout path, and an approve signal parked while it was failed
            WorkflowInstanceId id = f.waiting(node, "deadline");
            timers.dueNow(timers.only(id, "TIMEOUT").id());
            f.idle(node, id);
            f.record(node, id, "approve", signal("late"), "k1");
            f.idle(node, id);
            assertThat(f.signal(id, "k1").state()).isEqualTo("PARKED");

            // When the path is fixed and the retry alone runs
            breakable.broken.set(false);
            retry(f, node, id);
            node.runOneSegment();

            // Then the signal is not applied at the wait its deadline has passed for
            assertThat(f.signal(id, "k1").state()).isEqualTo("PARKED");
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
            assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("w");

            // And the timeout fires, and the signal, which the next wait does not accept, is never applied
            f.idle(node, id);
            assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("late");
            assertThat(f.signal(id, "k1").state()).isEqualTo("PARKED");
            assertThat(String.valueOf(f.fixture.instanceOf(id).stateJson()))
                    .contains("timeout:timeout")
                    .doesNotContain("approve:");
        }
    }

    @Test
    @DisplayName("retry of an instance failed at a timer node returns to the wait and fires the one timer again")
    void retryReturnsToATimerNode() {
        BreakableTimerNode breakable = new BreakableTimerNode();
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), breakable, List.of())) {
            TimerFixture timers = new TimerFixture(f);
            Node node = f.node("engine").singleSegments().build();

            // Given an instance whose step after the timer node fails when the timer fires
            WorkflowInstanceId id = f.waiting(node, "timer-node");
            TimerRow fire = timers.only(id, "FIRE");
            timers.dueNow(fire.id());
            f.idle(node, id);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(f.snapshot(id).getString("wait_type")).isEqualTo("TIMER");
            assertThat(f.snapshot(id).getString("wait_key"))
                    .isEqualTo(fire.timerId().toString());

            // When the step is fixed and the retry alone runs
            breakable.broken.set(false);
            retry(f, node, id);
            node.runOneSegment();

            // Then the instance waits on the same timer, which has one new command and no new row
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
            assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("t");
            assertThat(f.scalar("SELECT count(*) FROM workflow_timers WHERE workflow_id = $1", id))
                    .isEqualTo(1);
            assertThat(timers.all(id)).extracting(TimerRow::state).containsExactly("FAILED", "PENDING");
            assertThat(timers.all(id).getLast().fireAt().toInstant())
                    .isEqualTo(timers.byId(fire.id()).fireAt().toInstant());

            // And the timer fires and the instance completes
            f.idle(node, id);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("COMPLETED");
            assertThat(timers.timerRowOf(timers.all(id).getLast()).status()).isEqualTo("FIRED");
            assertThat(f.scalar("SELECT count(*) FROM workflow_timers WHERE workflow_id = $1", id))
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("retry of a task whose due date failed keeps the one task and offers its due date and reminder once")
    void retryReturnsToATaskWithDueDateAndReminder() {
        BreakableDueTask breakable = new BreakableDueTask();
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), breakable, List.of())) {
            TimerFixture timers = new TimerFixture(f);
            TaskDriver tasks = new TaskDriver(f);
            Node node = f.node("engine").singleSegments().build();

            // Given a task with a due date and a reminder, and a due date that fails when it fires
            OpenTask task = tasks.open(node, "due-and-reminder");
            TimerRow due = timers.only(task.id(), "DUE");
            TimerRow reminder = timers.only(task.id(), "REMINDER");
            timers.dueNow(due.id());
            f.idle(node, task.id());
            assertThat(f.fixture.instanceOf(task.id()).status()).isEqualTo("FAILED");
            assertThat(tasks.statusOf(task)).isEqualTo("OPEN");

            // And the reminder falls due while the instance is failed
            timers.dueNow(reminder.id());
            f.idle(node, task.id());
            assertThat(timers.byId(reminder.id()).state()).isEqualTo("STALE");
            assertThat(timers.timerRowOf(reminder).status()).isEqualTo("SCHEDULED");

            // When the due date is fixed and the retry alone runs
            breakable.broken.set(false);
            retry(f, node, task.id());
            node.runOneSegment();

            // Then the instance waits for the same task, which was not recorded again, and each timer has one new
            // command
            assertThat(f.fixture.instanceOf(task.id()).status()).isEqualTo("WAITING");
            assertThat(f.snapshot(task.id()).getString("wait_type")).isEqualTo("TASK");
            assertThat(f.scalar("SELECT count(*) FROM workflow_tasks WHERE workflow_id = $1", task.id()))
                    .isEqualTo(1);
            assertThat(tasks.statusOf(task)).isEqualTo("OPEN");
            assertThat(f.scalar("SELECT count(*) FROM workflow_timers WHERE workflow_id = $1", task.id()))
                    .isEqualTo(2);
            List<TimerRow> all = timers.all(task.id());
            assertThat(all).extracting(TimerRow::timerKind).containsExactly("DUE", "REMINDER", "DUE", "REMINDER");
            assertThat(all).extracting(TimerRow::state).containsExactly("FAILED", "STALE", "PENDING", "PENDING");
            assertThat(all.get(2).timerId()).isEqualTo(due.timerId());
            assertThat(all.get(3).timerId()).isEqualTo(reminder.timerId());
            assertThat(all.get(2).fireAt().toInstant())
                    .isEqualTo(timers.byId(due.id()).fireAt().toInstant());
            assertThat(all.get(3).fireAt().toInstant())
                    .isEqualTo(timers.byId(reminder.id()).fireAt().toInstant());

            // And when the executors run on, the due date expires the task once and the reminder is cancelled
            f.idle(node, task.id());
            assertThat(f.fixture.instanceOf(task.id()).status()).isEqualTo("COMPLETED");
            assertThat(tasks.statusOf(task)).isEqualTo("EXPIRED");
            assertThat(f.scalar("SELECT count(*) FROM workflow_tasks WHERE workflow_id = $1", task.id()))
                    .isEqualTo(1);
            assertThat(f.scalar("SELECT count(*) FROM workflow_timers WHERE workflow_id = $1", task.id()))
                    .isEqualTo(2);
            assertThat(timers.timerRowOf(timers.all(task.id()).get(2)).status()).isEqualTo("FIRED");
        }
    }

    @Test
    @DisplayName(
            "retry of an instance failed while it waits at a join keeps its branches and offers the branch timer once")
    void retryReturnsToAJoinWithoutForkingAgain() {
        BreakableBranchTimer breakable = new BreakableBranchTimer();
        try (SignalFixture f = new SignalFixture(
                db.toPoolConfig(), breakable, List.of(), new JsonObject().put("fork/b", new JsonArray().add("note")))) {
            TimerFixture timers = new TimerFixture(f);
            Node node = f.node("engine").singleSegments().build();

            // Given a fork whose timer branch fails when its timer fires
            WorkflowInstanceId id = f.waiting(node, "branch-timer");
            TimerRow fire = timers.only(id, "FIRE");
            timers.dueNow(fire.id());
            f.idle(node, id);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(f.snapshot(id).getString("wait_type")).isEqualTo("JOIN");
            assertThat(f.scalar("SELECT count(*) FROM workflow_branch_tokens WHERE workflow_id = $1", id))
                    .isEqualTo(2);

            // When the step is fixed and the retry alone runs
            breakable.broken.set(false);
            retry(f, node, id);
            node.runOneSegment();

            // Then the instance waits at the join again, with the two branches it had and the one timer, offered again
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
            assertThat(f.snapshot(id).getString("wait_type")).isEqualTo("JOIN");
            assertThat(f.scalar("SELECT count(*) FROM workflow_branch_tokens WHERE workflow_id = $1", id))
                    .isEqualTo(2);
            assertThat(f.scalar("SELECT count(*) FROM workflow_timers WHERE workflow_id = $1", id))
                    .isEqualTo(1);
            assertThat(timers.all(id)).extracting(TimerRow::state).containsExactly("FAILED", "PENDING");

            // And the branch ends when the timer fires, and the branches are not dispatched again
            f.idle(node, id);
            assertThat(f.scalar(
                            "SELECT count(*) FROM workflow_branch_tokens WHERE workflow_id = $1 AND status = 'COMPLETED'",
                            id))
                    .isEqualTo(1);
            assertThat(f.scalar("SELECT count(*) FROM workflow_branch_tokens WHERE workflow_id = $1", id))
                    .isEqualTo(2);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
        }
    }

    // --- Recording ---

    /** Records a retry in the caller's own transaction, on a context of its own, and commits it. */
    private static void retry(SignalFixture f, Node node, WorkflowInstanceId id) {
        await(f.caller(() -> node.pool.withTransaction(tx -> node.engine.retry(id, tx))));
    }

    // --- Fixture workflows ---

    /** A timer node whose next step throws while the switch is on. */
    static final class BreakableTimerNode extends Definition {
        final AtomicBoolean broken;

        BreakableTimerNode() {
            this(new AtomicBoolean(true));
        }

        private BreakableTimerNode(AtomicBoolean broken) {
            super("retry-at-wait-timer-node", wf -> {
                wf.initialStep("t");
                wf.timer("t", Duration.ofHours(1)).toStep("work");
                wf.decide("work", state -> {
                    if (broken.get()) {
                        throw new IllegalStateException("work step broken");
                    }
                    return "done";
                });
                wf.complete("done");
            });
            this.broken = broken;
        }
    }

    /** A fork of a branch that waits on a timer whose next step throws while the switch is on and a branch that waits. */
    static final class BreakableBranchTimer extends Definition {
        final AtomicBoolean broken;

        BreakableBranchTimer() {
            this(new AtomicBoolean(true));
        }

        private BreakableBranchTimer(AtomicBoolean broken) {
            super("retry-at-wait-branch-timer", wf -> {
                wf.initialStep("fork")
                        .fork("fork")
                        .branch("a", "a-timer", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                        .branch("b", "b-note", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                        .join("join");
                wf.timer("a-timer", Duration.ofSeconds(60)).toStep("a-work");
                wf.decide("a-work", state -> {
                    if (broken.get()) {
                        throw new IllegalStateException("branch step broken");
                    }
                    return "a-done";
                });
                wf.complete("a-done");
                waitFor(wf, "b-note", "note", "b-done");
                wf.complete("b-done");
                wf.join("join")
                        .allRequired((state, results) -> state)
                        .toStep("done")
                        .onFailure("done")
                        .endJoin();
                wf.complete("done");
            });
            this.broken = broken;
        }
    }

    /** A task with a due date whose branch throws while the switch is on, and a reminder every half hour. */
    static final class BreakableDueTask extends Definition {
        final AtomicBoolean broken;

        BreakableDueTask() {
            this(new AtomicBoolean(true));
        }

        private BreakableDueTask(AtomicBoolean broken) {
            super("retry-at-wait-due-task", wf -> {
                wf.initialStep("review");
                wf.task("review")
                        .assignToRole("compliance")
                        .dueIn(Duration.ofHours(1))
                        .onDue(state -> {
                            if (broken.get()) {
                                throw new IllegalStateException("due branch broken");
                            }
                            return state.add("expired", signal("expired"));
                        })
                        .toStepOnDue("expired")
                        .reminderEvery(Duration.ofMinutes(30), 3)
                        .decision("approve", RecordedCompletionIT.Decision.class)
                        .onDecision((state, decision) -> state.add("approve", signal("approve")))
                        .toStep("done")
                        .build();
                wf.complete("expired");
                wf.complete("done");
            });
            this.broken = broken;
        }
    }
}
