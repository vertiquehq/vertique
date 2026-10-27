// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static dev.vertique.workflow.postgresql.executor.RecordedSignalIT.signal;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import dev.vertique.db.exception.DataAccessException;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.ops.TaskReassignmentCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.RecordedCancelIT.HeldCall;
import dev.vertique.workflow.postgresql.executor.RecordedCompletionIT.Decision;
import dev.vertique.workflow.postgresql.executor.RecordedCompletionIT.OpenTask;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.Definition;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.History;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.SignalFixture;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TimerFixture;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TimerRow;
import dev.vertique.workflow.tasks.TaskAssignment;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Tuple;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that every writer of an instance's history holds the instance's execution lock, so that racing writers leave
 * a dense sequence: a due-date timer whose handling fails, the failure disposition that follows it, a cancellation and a
 * task reassignment; and that a cancellation never lets that disposition fail the cancelled instance.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 90, unit = TimeUnit.SECONDS)
public class HistorySequenceRaceIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_history_sequence_race_it")
            .withMigration("classpath:db/migration/workflow");

    private static final int REPETITIONS = 50;

    @Test
    @DisplayName("a failing due-date segment, its disposition, a cancel and a reassignment leave a dense history")
    void segmentCancelReassignAndDispositionKeepHistorySequenceDense() throws Exception {
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), new FailingDueDate(), List.of())) {
            TimerFixture timers = new TimerFixture(f);
            Node setup = f.node("setup").build();
            Node node = f.node("executor").build();

            // Given fifty instances at a task wait whose due-date command fails when it is handled
            List<OpenTask> tasks = openTasks(f, setup, REPETITIONS, "race-");
            ExecutorService threads = Executors.newFixedThreadPool(2);
            List<Throwable> failures = new ArrayList<>();
            node.startExecutor();
            try {
                for (int run = 0; run < REPETITIONS; run++) {
                    OpenTask task = tasks.get(run);
                    TimerRow due = timers.only(task.id(), "DUE");
                    int repetition = run;
                    CyclicBarrier ready = new CyclicBarrier(3);

                    // When the due date fires and, around it, a caller cancels the instance and another reassigns the
                    // task
                    Callable<Throwable> cancel = () -> {
                        ready.await(10, TimeUnit.SECONDS);
                        ExecutorFixture.sleep((repetition * 5L) % 25);
                        return attempt(() -> RecordedCancelIT.cancelAsync(f, node, task.id()));
                    };
                    Callable<Throwable> reassign = () -> {
                        ready.await(10, TimeUnit.SECONDS);
                        ExecutorFixture.sleep((repetition * 11L) % 25);
                        return attempt(() -> f.caller(() -> node.pool.withTransaction(tx -> node.engine.taskReassigned(
                                new TaskReassignmentCommand(
                                        task.taskId(),
                                        new TaskAssignment.Role("legal"),
                                        new WorkflowActor.User("manager"),
                                        "re-" + repetition,
                                        "reassigned"),
                                tx))));
                    };
                    var cancelled = threads.submit(cancel);
                    var reassigned = threads.submit(reassign);
                    ready.await(10, TimeUnit.SECONDS);
                    timers.dueNow(due.id());
                    failures.add(cancelled.get(30, TimeUnit.SECONDS));
                    failures.add(reassigned.get(30, TimeUnit.SECONDS));
                    node.awaitIdle(task.id());

                    // Then the instance's history sequences are one to n, without a gap or a duplicate
                    assertDense(f, task.id(), "repetition " + run);
                }
            } finally {
                node.stopExecutor();
                threads.shutdownNow();
            }

            // And no writer failed with a unique violation
            assertThat(failures)
                    .filteredOn(failure -> failure != null && uniqueViolation(failure))
                    .isEmpty();
            assertThat(f.fixture.logged(Level.ERROR))
                    .noneMatch(event -> event.getFormattedMessage().contains("duplicate key")
                            || event.getFormattedMessage().contains("23505"));
        }
    }

    @Test
    @DisplayName("a cancel that races a failure disposition never fails the cancelled instance")
    void cancelRacingAFailureDispositionLeavesTheInstanceCancelled() {
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), new FailingDueDate(), List.of())) {
            TimerFixture timers = new TimerFixture(f);
            Node setup = f.node("setup").build();

            // Ordering (a): given a failing due date whose disposition is held after its segment rolled back
            HeldCall holdA = new HeldCall("failCommand", arguments -> true);
            Node first = f.node("first").commands(holdA.decorator()).build();
            OpenTask a = openTasks(f, setup, 1, "gate-a-").getFirst();
            TimerRow dueA = timers.only(a.id(), "DUE");
            holdA.arm();
            first.startExecutor();
            try {
                timers.dueNow(dueA.id());
                holdA.awaitHeld();
                assertThat(timerCommandOf(f, a.id()).state()).isEqualTo("CLAIMED");

                // When the caller cancels and commits, and the disposition goes on
                RecordedCancelIT.cancel(f, first, a.id());
                holdA.release();
                holdA.awaitFinished();
                first.awaitIdle(a.id());

                // Then the disposition wrote nothing: the instance stays cancelled and the timer command is cancelled
                // with its token cleared, and no failure entry follows the cancellation
                assertThat(f.fixture.instanceOf(a.id()).status()).isEqualTo("CANCELLED");
                CommandRow timerA = timerCommandOf(f, a.id());
                assertThat(timerA.state()).isEqualTo("CANCELLED");
                assertThat(timerA.claimToken()).isNull();
                List<String> historyA = f.fixture.historyOf(a.id());
                assertThat(historyA).doesNotContain("FAILED");
                assertThat(historyA.subList(historyA.indexOf("CANCELLED"), historyA.size()))
                        .doesNotContain("FAILED");
                assertDense(f, a.id(), "ordering (a)");
            } finally {
                holdA.release();
                first.stopExecutor();
            }

            // Ordering (b): given the same failing due date
            HeldCall holdB = new HeldCall("failCommand", arguments -> true);
            Node second = f.node("second").commands(holdB.decorator()).build();
            OpenTask b = openTasks(f, setup, 1, "gate-b-").getFirst();
            TimerRow dueB = timers.only(b.id(), "DUE");
            holdB.arm();
            second.startExecutor();
            try {
                timers.dueNow(dueB.id());
                holdB.awaitHeld();

                // When the disposition goes on and commits, and then the caller cancels
                holdB.release();
                holdB.awaitFinished();
                awaitCondition(
                        "the disposition to fail the instance",
                        IDLE,
                        () -> f.fixture.instanceOf(b.id()).status().equals("FAILED"));
                assertThat(timerCommandOf(f, b.id()).state()).isEqualTo("FAILED");
                RecordedCancelIT.cancel(f, second, b.id());

                // Then the command stays failed, the instance is cancelled after the failure, and nothing else was
                // cancelled
                assertThat(f.fixture.instanceOf(b.id()).status()).isEqualTo("CANCELLED");
                assertThat(timerCommandOf(f, b.id()).state()).isEqualTo("FAILED");
                List<String> historyB = f.fixture.historyOf(b.id());
                assertThat(historyB).contains("FAILED", "CANCELLED");
                assertThat(historyB.indexOf("FAILED")).isLessThan(historyB.indexOf("CANCELLED"));
                assertThat(historyB).doesNotContain("COMMAND_CANCELLED");
                assertDense(f, b.id(), "ordering (b)");
            } finally {
                holdB.release();
                second.stopExecutor();
            }
        }
    }

    @Test
    @DisplayName("a cancel that races the failure disposition of a start never fails the cancelled instance")
    void cancelRacingAFailedStartDispositionLeavesTheInstanceCancelled() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given a start whose initial state fails, and its failure disposition held after the segment rolled back
            fixture.onInitialState = ref -> {
                throw new IllegalStateException("the initial state always fails");
            };
            HeldCall hold = new HeldCall("failCommand", arguments -> true);
            Node node = fixture.node("engine").commands(hold.decorator()).build();
            WorkflowInstanceId id = node.record(ExecutorFixture.startOf("failing-start"));
            hold.arm();
            node.startExecutor();
            try {
                hold.awaitHeld();
                assertThat(fixture.commandOf(id).state()).isEqualTo("CLAIMED");

                // When the caller cancels and commits, and the disposition goes on
                await(node.pool.withTransaction(tx -> node.engine.cancel(id, RecordedCancelIT.WHY, tx)));
                hold.release();
                hold.awaitFinished();
                node.awaitIdle(id);

                // Then the disposition wrote nothing: the instance stays cancelled without state, the start is
                // cancelled with its token cleared, and no failure entry was written
                assertThat(fixture.instanceOf(id).status()).isEqualTo("CANCELLED");
                assertThat(fixture.instanceOf(id).stateJson()).isNull();
                CommandRow start = fixture.commandOf(id);
                assertThat(start.state()).isEqualTo("CANCELLED");
                assertThat(start.claimToken()).isNull();
                assertThat(fixture.historyOf(id)).doesNotContain("FAILED");
                List<Long> sequences = new ArrayList<>();
                for (var row : await(fixture.observer()
                        .preparedQuery("SELECT sequence FROM workflow_history WHERE workflow_id = $1 ORDER BY sequence")
                        .execute(Tuple.of(id.value())))) {
                    sequences.add(row.getLong(0));
                }
                assertThat(sequences)
                        .containsExactlyElementsOf(LongStream.rangeClosed(1, sequences.size())
                                .boxed()
                                .toList());
            } finally {
                hold.release();
                node.stopExecutor();
            }
        }
    }

    // --- Helpers ---

    /** Starts instances of the fixture workflow and runs them to their task wait. */
    private static List<OpenTask> openTasks(SignalFixture f, Node node, int count, String prefix) {
        List<WorkflowInstanceId> ids = new ArrayList<>();
        node.startExecutor();
        try {
            for (int i = 0; i < count; i++) {
                String ref = prefix + i;
                ids.add(await(f.caller(() -> node.pool.withTransaction(tx -> node.engine.start(f.start(ref), tx)))));
            }
            for (WorkflowInstanceId id : ids) {
                node.awaitIdle(id);
            }
        } finally {
            node.stopExecutor();
        }
        List<OpenTask> tasks = new ArrayList<>();
        for (WorkflowInstanceId id : ids) {
            UUID taskId = await(f.fixture
                            .observer()
                            .preparedQuery("SELECT task_id FROM workflow_tasks WHERE workflow_id = $1")
                            .execute(Tuple.of(id.value())))
                    .iterator()
                    .next()
                    .getUUID("task_id");
            tasks.add(new OpenTask(id, taskId));
        }
        return tasks;
    }

    private static CommandRow timerCommandOf(SignalFixture f, WorkflowInstanceId id) {
        return f.fixture.commandsOf(id).stream()
                .filter(row -> row.kind().equals("TIMER"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no timer command of " + id));
    }

    private static void assertDense(SignalFixture f, WorkflowInstanceId id, String label) {
        List<Long> sequences = f.history(id).stream().map(History::sequence).toList();
        assertThat(sequences)
                .as("history sequences of %s, %s", id, label)
                .containsExactlyElementsOf(
                        LongStream.rangeClosed(1, sequences.size()).boxed().toList());
    }

    /** The failure of the operation, or {@code null} when it succeeded. */
    private static Throwable attempt(Supplier<Future<?>> operation) {
        try {
            await(operation.get(), Duration.ofSeconds(25));
            return null;
        } catch (AssertionError failure) {
            return failure.getCause() != null ? failure.getCause() : failure;
        }
    }

    private static boolean uniqueViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = String.valueOf(cause.getMessage());
            if (message.contains("23505") || message.contains("duplicate key")) {
                return true;
            }
            if (cause instanceof DataAccessException data && "23505".equals(data.sqlState())) {
                return true;
            }
            if (cause instanceof io.vertx.sqlclient.DatabaseException data && "23505".equals(data.getSqlState())) {
                return true;
            }
        }
        return false;
    }

    /** A task with a due date whose handling always throws, so that the due-date command fails the instance. */
    static final class FailingDueDate extends Definition {
        FailingDueDate() {
            super("history-race-failing-due", wf -> {
                wf.initialStep("review");
                wf.task("review")
                        .assignToRole("compliance")
                        .dueIn(Duration.ofHours(1))
                        .onDue(state -> {
                            throw new IllegalStateException("due-date handling is broken");
                        })
                        .toStepOnDue("expired")
                        .decision("approve", Decision.class)
                        .onDecision((state, decision) -> state.add("approve", signal("approve")))
                        .toStep("done")
                        .build();
                wf.complete("expired");
                wf.complete("done");
            });
        }
    }
}
