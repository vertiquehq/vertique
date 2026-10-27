// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.failureOf;
import static dev.vertique.workflow.postgresql.executor.RecordedSignalIT.signal;
import static dev.vertique.workflow.postgresql.executor.RecordedSignalIT.waitFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.vertique.context.InboundDispatchScope;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.db.IsolationLevel;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobRepository;
import dev.vertique.job.JobState;
import dev.vertique.job.JobType;
import dev.vertique.job.ProgressSnapshot;
import dev.vertique.workflow.delayed.job.WorkflowTimerFireJob;
import dev.vertique.workflow.delayed.recovery.WorkflowTimerRecoveryConfig;
import dev.vertique.workflow.delayed.recovery.WorkflowTimerRecoveryService;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.exception.WorkflowTaskNotWaitingException;
import dev.vertique.workflow.history.PendingCommand;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.WorkflowExecutionProbe;
import dev.vertique.workflow.ops.WorkflowExecutionProbe.IdleReport;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.RaceSafety;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CorrelationCtx;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.IdentitySnapshotCtx;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.RecordedCompletionIT.Decision;
import dev.vertique.workflow.postgresql.executor.RecordedCompletionIT.OpenTask;
import dev.vertique.workflow.postgresql.executor.RecordedContextIT.Observation;
import dev.vertique.workflow.postgresql.executor.RecordedContextIT.SecurityProbe;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.Definition;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.History;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.Signal;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.SignalFixture;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that workflow timers (timer nodes, signal timeouts, task due dates and task reminders) are rows of the
 * command log: entering a timed wait inserts a timer row and a {@code TIMER} command with a fire time from the
 * database clock, an executor runs the command as a segment, a timeout is held back by an earlier operation for its
 * wait, a due date or reminder is held back while its task is being completed, and a timer whose wait is gone settles
 * stale.
 *
 * <p>Tests that need a timer to be due now rewrite the fire time of its command and timer row through
 * {@link TimerFixture}; nothing else in the rows is touched.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class TimerCommandIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_timer_command_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String IDENTITY_NAMESPACE = "identity-snapshot";
    private static final Duration FAR_POLL = Duration.ofMinutes(1);

    @Test
    @DisplayName("a timeout keeps precedence over a signal recorded after its fire time")
    void timeoutPrecedesSignalsRecordedAfterItsFireTime() {
        try (SignalFixture f =
                new SignalFixture(db.toPoolConfig(), new TimeoutWait(), List.of("approve", "note", "finish"))) {
            TimerFixture timers = new TimerFixture(f);
            Node node = f.node("engine").build();

            // Variant A: given an instance in a signal wait with a timeout, and executors stopped
            WorkflowInstanceId a = f.waiting(node, "variant-a");
            TimerRow timeoutA = timers.only(a, "TIMEOUT");
            timers.assertTargetsWait(timeoutA, "w", "approve");
            // And a valid signal S1 recorded before the fire time, the fire time moved to now, and a signal S2
            // recorded after it
            f.record(node, a, "approve", signal("s1"), "a-s1");
            timers.dueNow(timeoutA.id());
            f.record(node, a, "note", signal("s2"), "a-s2");
            assertThat(f.signal(a, "a-s1").seq()).isGreaterThan(timeoutA.seq());
            assertThat(f.signal(a, "a-s2").seq())
                    .isGreaterThan(f.signal(a, "a-s1").seq());

            // When the executors start and the instance is idle
            f.idle(node, a);

            // Then S1 applied, the timeout was cancelled with its timer, and S2 ran after S1 at the next wait
            assertThat(f.signal(a, "a-s1").state()).isEqualTo("COMPLETE");
            assertThat(timers.byId(timeoutA.id()).state()).isEqualTo("CANCELLED");
            assertThat(timers.timerRowOf(timeoutA).status()).isEqualTo("CANCELLED");
            assertThat(f.signal(a, "a-s2").state()).isEqualTo("COMPLETE");
            assertThat(f.signal(a, "a-s1").settledAt())
                    .isBeforeOrEqualTo(f.signal(a, "a-s2").settledAt());
            assertThat(f.fixture.instanceOf(a).status()).isEqualTo("COMPLETED");
            assertThat(f.fixture.historyOf(a)).doesNotContain("TIMEOUT");

            // Variant B: given the same wait where S1's updater throws
            WorkflowInstanceId b = f.waiting(node, "variant-b");
            TimerRow timeoutB = timers.only(b, "TIMEOUT");
            f.record(node, b, "approve", new Signal(true, "card-secret", "s1", false, false), "b-s1");
            timers.dueNow(timeoutB.id());
            f.record(node, b, "note", signal("s2"), "b-s2");

            // When the executors start and the instance is idle
            f.idle(node, b);

            // Then S1 was rejected, the timeout completed taking its branch, and only then was S2 parked
            assertThat(f.signal(b, "b-s1").state()).isEqualTo("REJECTED");
            assertThat(timers.byId(timeoutB.id()).state()).isEqualTo("COMPLETE");
            assertThat(timers.timerRowOf(timeoutB).status()).isEqualTo("FIRED");
            assertThat(f.signal(b, "b-s2").state()).isEqualTo("PARKED");
            assertThat(f.fixture.instanceOf(b).status()).isEqualTo("WAITING");
            assertThat(f.fixture.instanceOf(b).currentStepId()).isEqualTo("late");
            List<History> history = f.history(b);
            long timeoutTaken = history.stream()
                    .filter(entry -> entry.type().equals("TIMEOUT"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no TIMEOUT entry in " + history))
                    .sequence();
            long parked = history.stream()
                    .filter(entry -> entry.type().equals("SIGNAL_PARKED")
                            && entry.commandId() != null
                            && entry.commandId() == f.signal(b, "b-s2").id())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no SIGNAL_PARKED entry for S2 in " + history))
                    .sequence();
            assertThat(timeoutTaken).isLessThan(parked);
        }
    }

    @Test
    @DisplayName("a signal after a dead-lettered timeout's fire time is parked, not applied")
    void signalAfterADeadLetteredTimeoutIsParked() {
        try (SignalFixture f =
                new SignalFixture(db.toPoolConfig(), new TimeoutWait(), List.of("approve", "note", "finish"))) {
            TimerFixture timers = new TimerFixture(f);
            CommandFaults faults = new CommandFaults();
            // Given a wait with a timeout, one attempt per command, and a first segment that is abandoned as a crash
            Node node = f.node("engine")
                    .leaseSeconds(5)
                    .maxAttempts(1)
                    .commands(faults.decorator())
                    .build();
            WorkflowInstanceId id = f.waiting(node, "dead-timeout");
            TimerRow timeout = timers.only(id, "TIMEOUT");
            faults.crashSegmentsOf(id, 1);

            // And the timeout is due, claimed, abandoned and then dead-lettered
            node.startExecutor();
            try {
                timers.dueNow(timeout.id());
                awaitCondition(
                        "the timeout to be dead-lettered",
                        Duration.ofSeconds(40),
                        () -> timers.byId(timeout.id()).state().equals("DEAD"));

                // When a signal for the wait is recorded after the timeout's fire time and executes
                f.record(node, id, "approve", signal("late"), "late-signal");
                IdleReport report = await(node.engine.executionProbe().awaitIdle(id, IDLE));

                // Then the signal is parked, the instance still waits, and the timeout is still dead-lettered
                CommandRow parked = f.signal(id, "late-signal");
                assertThat(parked.state()).isEqualTo("PARKED");
                assertThat(parked.recordedAt())
                        .isAfterOrEqualTo(timers.byId(timeout.id()).fireAt());
                assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
                assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("w");
                assertThat(timers.byId(timeout.id()).state()).isEqualTo("DEAD");
                assertThat(report.parked()).extracting(PendingCommand::sequence).containsExactly(parked.seq());
                assertThat(report.stuck()).extracting(PendingCommand::sequence).containsExactly(timeout.seq());
            } finally {
                node.stopExecutor();
            }
        }
    }

    @Test
    @DisplayName("a pending completion wins over a due date that passes meanwhile")
    void pendingCompletionCancelsAPassedDueDate() {
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), new DueDateTask(), List.of())) {
            TimerFixture timers = new TimerFixture(f);
            TaskDriver tasks = new TaskDriver(f);
            Node node = f.node("engine").build();
            // Given a task with a due date, a completion recorded, and executors stopped
            OpenTask task = tasks.open(node, "pending-completion");
            TimerRow due = timers.only(task.id(), "DUE");
            assertThat(due.taskId()).isEqualTo(task.taskId());
            tasks.complete(node, task, new JsonObject(), "k1");
            assertThat(tasks.statusOf(task)).isEqualTo("COMPLETING");

            // And the due date passes
            timers.dueNow(due.id());

            // When the executors start and the instance is idle
            f.idle(node, task.id());

            // Then the task is completed, the due date and its timer are cancelled, and nothing expired
            assertThat(tasks.statusOf(task)).isEqualTo("COMPLETED");
            assertThat(timers.byId(due.id()).state()).isEqualTo("CANCELLED");
            assertThat(timers.timerRowOf(due).status()).isEqualTo("CANCELLED");
            assertThat(tasks.completions(task.id()))
                    .extracting(CommandRow::state)
                    .containsExactly("COMPLETE");
            assertThat(f.fixture.historyOf(task.id())).doesNotContain("TASK_EXPIRED");
            assertThat(f.fixture.instanceOf(task.id()).status()).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("a completion against a claimed or running due date")
    void completionAgainstAClaimedOrRunningDueDate() {
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), new DueDateTask(), List.of())) {
            TimerFixture timers = new TimerFixture(f);
            TaskDriver tasks = new TaskDriver(f);
            SegmentPause pause = new SegmentPause();
            Node node = f.node("engine").runner(pause.decorator()).build();
            // Given two tasks with a due date of an hour each, and a running executor
            OpenTask claimed = tasks.open(node, "claimed");
            OpenTask running = tasks.open(node, "running");
            TimerRow claimedDue = timers.only(claimed.id(), "DUE");
            TimerRow runningDue = timers.only(running.id(), "DUE");
            node.startExecutor();
            try {
                // Case 1: the due-date command of the first task is claimed and its segment is paused before the lock
                pause.armBeforeLock();
                timers.dueNow(claimedDue.id());
                pause.awaitReached();
                assertThat(timers.byId(claimedDue.id()).state()).isEqualTo("CLAIMED");

                // When a caller records a completion of the task, and the paused segment goes on
                tasks.complete(node, claimed, new JsonObject(), "k1");
                pause.release();
                node.awaitIdle(claimed.id());

                // Then the completion applied whichever of the two ran first, and the due date was cancelled instead
                // of expiring the task
                assertThat(tasks.statusOf(claimed)).isEqualTo("COMPLETED");
                assertThat(timers.byId(claimedDue.id()).state()).isEqualTo("CANCELLED");
                assertThat(timers.timerRowOf(claimedDue).status()).isEqualTo("CANCELLED");
                assertThat(tasks.completions(claimed.id()))
                        .extracting(CommandRow::state)
                        .containsExactly("COMPLETE");
                assertThat(f.fixture.historyOf(claimed.id())).doesNotContain("TASK_EXPIRED");

                // Case 2: the due-date command of the second task is running, paused inside the lock before its commit
                pause.armInsideLock();
                timers.dueNow(runningDue.id());
                pause.awaitReached();

                // When a caller records a completion of the task
                Future<TaskMutationResult> completion = tasks.completeAsync(node, running, new JsonObject(), "k2");
                awaitCondition(
                        "the caller to wait for the execution lock",
                        IDLE,
                        () -> f.fixture.ungrantedAdvisoryLocks() == 1);
                assertThat(completion.isComplete()).isFalse();
                pause.release();

                // Then the caller waited for the segment and failed because the task had expired
                assertThat(failureOf(completion, IDLE)).isInstanceOf(WorkflowTaskNotWaitingException.class);
                node.awaitIdle(running.id());
                assertThat(tasks.statusOf(running)).isEqualTo("EXPIRED");
                assertThat(timers.byId(runningDue.id()).state()).isEqualTo("COMPLETE");
                assertThat(tasks.completions(running.id())).isEmpty();
                assertThat(f.fixture.historyOf(running.id())).contains("TASK_EXPIRED");
                assertThat(f.fixture.ungrantedAdvisoryLocks()).isZero();
            } finally {
                pause.release();
                node.stopExecutor();
            }
        }
    }

    @Test
    @DisplayName("a timer cancelled by a signal completes stale")
    void timerCancelledBySignalCompletesStale() {
        try (SignalFixture f =
                new SignalFixture(db.toPoolConfig(), new TimeoutWait(), List.of("approve", "note", "finish"))) {
            TimerFixture timers = new TimerFixture(f);
            SegmentPause pause = new SegmentPause();
            Node node = f.node("engine").runner(pause.decorator()).build();
            // Given a wait with a timeout whose command is claimed and whose segment is paused before the lock
            WorkflowInstanceId id = f.waiting(node, "stale-signal");
            TimerRow timeout = timers.only(id, "TIMEOUT");
            node.startExecutor();
            try {
                pause.armBeforeLock();
                timers.dueNow(timeout.id());
                pause.awaitReached();
                assertThat(timers.byId(timeout.id()).state()).isEqualTo("CLAIMED");

                // And the fire time is moved out so the claimed timeout no longer holds up the queue, and a signal
                // for the wait is recorded and applied first
                timers.fireAt(timeout.id(), Duration.ofMinutes(5));
                f.record(node, id, "approve", signal("first"), "first");
                awaitCondition("the signal to be applied", IDLE, () -> f.signal(id, "first")
                        .state()
                        .equals("COMPLETE"));

                // When the timeout's segment resumes
                pause.release();
                node.awaitIdle(id);

                // Then the timeout is settled without effect on the instance, and its timer is not fired
                TimerRow settled = timers.byId(timeout.id());
                assertThat(settled.state()).isIn("CANCELLED", "STALE");
                assertThat(timers.timerRowOf(timeout).status()).isEqualTo("CANCELLED");
                assertThat(f.fixture.historyOf(id)).doesNotContain("TIMEOUT", "TIMER_FIRED");
                assertThat(f.fixture.historyTextsOf(id))
                        .anyMatch(text -> text.contains("COMMAND_STALE")
                                || text.contains("COMMAND_CANCELLED")
                                || text.contains("TIMER_CANCELLED"));
                assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("w2");
                assertThat(String.valueOf(f.fixture.instanceOf(id).stateJson())).doesNotContain("timeout");
            } finally {
                pause.release();
                node.stopExecutor();
            }
        }
    }

    @Test
    @DisplayName("a timer due in 2 s fires without waiting for a poll")
    void timerFiresWithinTwoSecondsWithoutPolling(TestReporter reporter) {
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), new TimerNode(Duration.ofSeconds(2)), List.of())) {
            TimerFixture timers = new TimerFixture(f);
            // Given executors that poll once a minute and are woken by notifications, and a timer node of 2 s
            Node node = f.node("engine")
                    .pollIntervalMs(FAR_POLL.toMillis())
                    .listening()
                    .build();
            node.startExecutor();
            try {
                // When the start executes
                WorkflowInstanceId id = node.record(f.start("two-seconds"));
                awaitCondition("the timer command to exist", IDLE, () -> !timers.all(id)
                        .isEmpty());
                TimerRow created = timers.all(id).getFirst();
                awaitCondition("the timer command to settle", Duration.ofSeconds(10), () -> timers.byId(created.id())
                        .state()
                        .equals("COMPLETE"));

                // Then the command was linked to its timer row with a fire time two seconds after it was recorded
                TimerRow settled = timers.byId(created.id());
                assertThat(settled.timerKind()).isEqualTo("FIRE");
                assertThat(settled.fireAt()).isNotNull();
                assertThat(Duration.between(settled.recordedAt(), settled.fireAt()))
                        .isBetween(Duration.ofMillis(1500), Duration.ofMillis(2500));
                assertThat(settled.availableAt()).isEqualTo(settled.fireAt());
                TimerRecordRow row = timers.timerRowOf(settled);
                assertThat(row.commandId()).isEqualTo(settled.id());
                assertThat(row.delayedJobExecutionId()).isNull();
                assertThat(row.status()).isEqualTo("FIRED");

                // And it settled within two seconds of its fire time, though nothing polled
                Duration latency = Duration.between(settled.fireAt(), settled.settledAt());
                reporter.publishEntry("timerLatencyMs", Long.toString(latency.toMillis()));
                assertThat(latency).isLessThanOrEqualTo(Duration.ofSeconds(2));
                node.awaitIdle(id);
                assertThat(f.fixture.instanceOf(id).status()).isEqualTo("COMPLETED");
                assertThat(f.fixture.historyOf(id)).contains("TIMER_FIRED");
            } finally {
                node.stopExecutor();
            }
        }
    }

    @Test
    @DisplayName("a not-yet-due timer blocks no later signal")
    void notYetDueTimerBlocksNoLaterSignal() {
        try (SignalFixture f = new SignalFixture(
                db.toPoolConfig(),
                new ParallelWait(),
                List.of(),
                new JsonObject().put("fork/a", new JsonArray().add("ack")).put("fork/b", new JsonArray()))) {
            TimerFixture timers = new TimerFixture(f);
            Node node = f.node("engine").build();
            // Given a branch waiting for a signal next to a branch whose timer is due in a minute, executors stopped
            WorkflowInstanceId id = f.waiting(node, "parallel");
            TimerRow timer = timers.only(id, "FIRE");
            assertThat(timer.state()).isEqualTo("PENDING");
            assertThat(timer.fireAt()).isAfter(f.fixture.databaseNow().plusSeconds(30));

            // When a signal for the first branch is recorded and the executors run
            await(f.caller(() -> node.pool.withTransaction(
                    tx -> node.engine.signal(id, "ack", signal("ack"), "ack-key", "fork", "a", tx))));
            assertThat(f.signal(id, "ack-key").seq()).isGreaterThan(timer.seq());
            node.startExecutor();
            IdleReport report;
            try {
                report = await(node.engine.executionProbe().awaitIdle(id, IDLE));
            } finally {
                node.stopExecutor();
            }

            // Then the signal completed, the timer is still pending, and the report lists it as not yet available
            assertThat(f.signal(id, "ack-key").state()).isEqualTo("COMPLETE");
            TimerRow after = timers.byId(timer.id());
            assertThat(after.state()).isEqualTo("PENDING");
            assertThat(report.notYetAvailable())
                    .containsExactly(new PendingCommand(
                            id,
                            timer.seq(),
                            "TIMER",
                            "PENDING",
                            after.availableAt().toInstant(),
                            0,
                            null));
            assertThat(f.fixture.instanceOf(id).status()).isNotIn("COMPLETED", "FAILED");
        }
    }

    @Test
    @DisplayName("a rejected completion releases its due date with the original fire time and sequence")
    void rejectedCompletionReleasesTheDueDateUnchanged() {
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), new DueDateTask(), List.of())) {
            TimerFixture timers = new TimerFixture(f);
            TaskDriver tasks = new TaskDriver(f);
            Node node = f.node("engine").build();
            // Given a task whose due date has passed, and a completion whose applicator throws, recorded before the
            // due date ran
            OpenTask task = tasks.open(node, "rejected");
            TimerRow created = timers.only(task.id(), "DUE");
            timers.dueNow(created.id());
            TimerRow due = timers.byId(created.id());
            tasks.complete(node, task, new JsonObject().put("fail", true), "k1");
            assertThat(tasks.statusOf(task)).isEqualTo("COMPLETING");

            // When the executors run and the instance is idle
            f.idle(node, task.id());

            // Then the completion was rejected, the task reopened and then expired by the same due-date command,
            // with the sequence and fire time it had
            CommandRow completion = tasks.completions(task.id()).getFirst();
            assertThat(completion.state()).isEqualTo("REJECTED");
            TimerRow ran = timers.byId(created.id());
            assertThat(ran.state()).isEqualTo("COMPLETE");
            assertThat(ran.seq()).isEqualTo(due.seq());
            assertThat(ran.fireAt()).isEqualTo(due.fireAt());
            assertThat(timers.all(task.id()))
                    .filteredOn(row -> row.timerKind().equals("DUE"))
                    .hasSize(1);
            assertThat(tasks.statusOf(task)).isEqualTo("EXPIRED");
            List<String> history = f.fixture.historyOf(task.id());
            assertThat(history).contains("COMMAND_REJECTED", "TASK_EXPIRED");
            assertThat(history.indexOf("COMMAND_REJECTED")).isLessThan(history.indexOf("TASK_EXPIRED"));
            assertThat(f.fixture.instanceOf(task.id()).status()).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("a converted legacy timer with an identity snapshot binds no security context")
    void convertedLegacyTimerWithIdentitySnapshotBindsNoSecurityContext() {
        SecurityProbe probe = new SecurityProbe();
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), new ProbedTimers(probe), List.of())) {
            probe.attach(f.fixture);
            TimerFixture timers = new TimerFixture(f);
            // Given identity carriage and an instance waiting at a timer node
            Node node = f.node("engine").identityCarriage().build();
            WorkflowInstanceId id = f.waiting(node, "legacy");
            TimerRow created = timers.only(id, "FIRE");

            // And that timer converted from a legacy one: its row holds correlation L and an identity snapshot,
            // signed for the timer's own carrier, and its command holds no context
            DurableMetadata legacy = legacyMetadata(f, created.timerId(), id);
            assertThat(legacy.has(IDENTITY_NAMESPACE)).isTrue();
            timers.convert(created, legacy);

            // When the command becomes due and executes
            f.idle(node, id);

            // Then the step after the timer ran with correlation L and no identity bound
            assertThat(timers.byId(created.id()).state()).isEqualTo("COMPLETE");
            List<Observation> seen = probe.at("after-timer");
            assertThat(seen).singleElement().satisfies(observation -> {
                assertThat(observation.securityContext())
                        .as("security context bound")
                        .isFalse();
                assertThat(observation.origin())
                        .as("deferred-execution origin bound")
                        .isFalse();
                assertThat(observation.identitySnapshot())
                        .as("identity snapshot bound")
                        .isFalse();
                assertThat(observation.namespaces()).doesNotContain(IDENTITY_NAMESPACE);
                assertThat(observation.correlation()).contains("L");
            });

            // And no timer command stores an identity namespace, and the timer the segment entered holds none either
            List<TimerRow> all = timers.all(id);
            assertThat(all).hasSize(2);
            for (TimerRow command : all) {
                assertThat(command.context() == null ? "" : command.context().encode())
                        .as("context_json of command %s", command.id())
                        .doesNotContain(IDENTITY_NAMESPACE, "user-77");
            }
            TimerRow entered = all.get(1);
            assertThat(entered.state()).isEqualTo("PENDING");
            TimerRecordRow enteredRow = timers.timerRowOf(entered);
            assertThat(enteredRow.commandId()).isEqualTo(entered.id());
            assertThat(
                            enteredRow.metadata() == null
                                    ? ""
                                    : enteredRow.metadata().encode())
                    .doesNotContain(IDENTITY_NAMESPACE, "user-77");
        }
    }

    @Test
    @DisplayName("a long-deferred timer never fails the instance")
    void longDeferredTimerNeverFailsTheInstance() {
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), new TimerNode(Duration.ofHours(1)), List.of())) {
            TimerFixture timers = new TimerFixture(f);
            // Given a node that runs the workflow and a node that does not hold its code
            Node full = f.node("full").build();
            Node hidden =
                    f.node("hidden").withRegistry(new DefaultWorkflowRegistry()).build();
            WorkflowInstanceId id = f.waiting(full, "deferred");
            TimerRow timer = timers.only(id, "FIRE");

            // And the delayed timer recovery service with a short grace, whose job lookup reports a dead-lettered job
            JobRepository jobs = mock(JobRepository.class);
            when(jobs.findById(any())).thenReturn(Future.succeededFuture(Optional.of(deadLetteredJob())));
            WorkflowTimerFireJob fireJob = mock(WorkflowTimerFireJob.class);
            WorkflowTimerRecoveryService recovery = new WorkflowTimerRecoveryService(
                    full.pool,
                    new PgTimerStore(new PgDbExceptionMapper()),
                    jobs,
                    fireJob,
                    () -> full.engine,
                    new WorkflowTimerRecoveryConfig(100, Duration.ofMillis(200)),
                    Clock.systemUTC(),
                    new InboundExecutionContextScope(new InboundDispatchScope(), f.fixture.propagator(), Set.of()));

            // And the timer is due but deferred as not runnable for longer than that grace
            hidden.startExecutor();
            try {
                timers.dueNow(timer.id());
                awaitCondition("the timer to be deferred", IDLE, () -> timers.byId(timer.id())
                        .state()
                        .equals("DEFERRED"));
                ExecutorFixture.sleep(1000);

                // When the recovery service runs
                await(recovery.reconcile());

                // Then it did not touch the timer or fail the instance
                assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
                assertThat(timers.byId(timer.id()).state()).isEqualTo("DEFERRED");
                assertThat(timers.timerRowOf(timer).status()).isEqualTo("SCHEDULED");
                verifyNoInteractions(jobs);
                verify(fireJob, never()).enqueue(any(), any(), any());
            } finally {
                hidden.stopExecutor();
            }

            // When the code becomes available on a node and the instance is idle
            f.fixture.fastForward(timer.id());
            f.idle(full, id);

            // Then the timer fired once and the instance was never failed
            assertThat(timers.all(id)).hasSize(1);
            assertThat(timers.byId(timer.id()).state()).isEqualTo("COMPLETE");
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("COMPLETED");
            assertThat(f.fixture.historyOf(id)).containsOnlyOnce("TIMER_FIRED").doesNotContain("TIMER_FAILED");
        }
    }

    @Test
    @DisplayName("a recurring reminder records its next command when it fires")
    void recurringReminderRecordsItsNextCommand() {
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), new RecurringReminderTask(), List.of())) {
            TimerFixture timers = new TimerFixture(f);
            TaskDriver tasks = new TaskDriver(f);
            Node node = f.node("engine").build();
            // Given a task with a recurring reminder
            OpenTask task = tasks.open(node, "recurring");
            TimerRow first = timers.only(task.id(), "REMINDER");
            assertThat(first.taskId()).isEqualTo(task.taskId());
            assertThat(timers.timerRowOf(first).commandId()).isEqualTo(first.id());

            // When the first reminder command is made due and executes
            timers.dueNow(first.id());
            f.idle(node, task.id());

            // Then the reminder fired once
            assertThat(tasks.remindersFired(task)).isEqualTo(1);
            assertThat(timers.byId(first.id()).state()).isEqualTo("COMPLETE");
            assertThat(f.fixture.historyOf(task.id())).containsOnlyOnce("TASK_REMINDER_FIRED");

            // And a new reminder command with a later fire time exists and links its own timer row
            List<TimerRow> reminders = timers.all(task.id()).stream()
                    .filter(row -> row.timerKind().equals("REMINDER"))
                    .toList();
            assertThat(reminders).hasSize(2);
            TimerRow next = reminders.get(1);
            assertThat(next.state()).isEqualTo("PENDING");
            assertThat(next.seq()).isGreaterThan(first.seq());
            assertThat(next.fireAt()).isAfter(timers.byId(first.id()).fireAt());
            assertThat(next.availableAt()).isEqualTo(next.fireAt());
            assertThat(next.taskId()).isEqualTo(task.taskId());
            TimerRecordRow nextRow = timers.timerRowOf(next);
            assertThat(nextRow.commandId()).isEqualTo(next.id());
            assertThat(nextRow.status()).isEqualTo("SCHEDULED");
            assertThat(nextRow.purpose()).isEqualTo("TASK_REMINDER");

            // And no timer of the task is scheduled through a delayed job
            assertThat(timers.delayedJobTimers(task.id())).isZero();
        }
    }

    @Test
    @DisplayName("a timeout held back by an earlier signal for its wait is not runnable")
    void timeoutHeldBackByAnEarlierSignalIsNotRunnable() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given no executor, only the probe
            WorkflowExecutionProbe probe = PgExecutionProbeSupport.probe(fixture.observer(), fixture.vertx());
            Pool pool = fixture.observer();
            // And instance H waiting at a wait that accepts approve: an overdue timeout (seq 1), then an approve
            // signal (seq 2) deferred for an hour that was recorded before the timeout's fire time
            WorkflowInstanceId h = waitingInstance(pool, 1);
            PendingCommand heldTimeout = insertTimeout(pool, h);
            PendingCommand holdingSignal = insertSignal(pool, h, "approve", "now() - interval '2 seconds'");
            // And instance J the same, but its signal is one the wait does not accept
            WorkflowInstanceId j = waitingInstance(pool, 2);
            insertTimeout(pool, j);
            insertSignal(pool, j, "other", "now() - interval '2 seconds'");
            // And instance K the same as H, but its signal was recorded after the timeout's fire time
            WorkflowInstanceId k = waitingInstance(pool, 3);
            insertTimeout(pool, k);
            insertSignal(pool, k, "approve", "now()");

            // When the probe waits for H, then for J and K
            IdleReport held = await(probe.awaitIdle(h, Duration.ofSeconds(2)), Duration.ofSeconds(3));
            Throwable otherName = failureOf(probe.awaitIdle(j, Duration.ofMillis(300)), Duration.ofSeconds(3));
            Throwable laterSignal = failureOf(probe.awaitIdle(k, Duration.ofMillis(300)), Duration.ofSeconds(3));

            // Then H is idle at once with the timeout reported as held back and the signal as deferred
            assertThat(held.notYetAvailable()).containsExactly(heldTimeout);
            assertThat(held.deferred()).containsExactly(holdingSignal);
            assertThat(held.parked()).isEmpty();
            assertThat(held.stuck()).isEmpty();
            // And the timeouts of J and K are claimable, so their instances are not idle
            assertThat(otherName).isInstanceOf(TimeoutException.class);
            assertThat(laterSignal).isInstanceOf(TimeoutException.class);
        }
    }

    // --- Fixture rows for the probe-only proof ---

    private static WorkflowInstanceId waitingInstance(Pool pool, long n) {
        UUID id = new UUID(0L, n);
        await(pool.preparedQuery("INSERT INTO workflow_instances (id, definition_id, definition_version, plan_hash,"
                        + " status, current_step_id, state_json) VALUES ($1, 'orders', 1, repeat('a', 64), 'WAITING',"
                        + " 'wait', '{}'::jsonb)")
                .execute(Tuple.of(id)));
        return new WorkflowInstanceId(id);
    }

    /** A pending timeout, overdue by a second, of the wait at step {@code wait} that accepts {@code approve}. */
    private static PendingCommand insertTimeout(Pool pool, WorkflowInstanceId instance) {
        JsonObject target = new JsonObject().put("stepId", "wait").put("signalNames", new JsonArray().add("approve"));
        Row row = await(pool.preparedQuery("INSERT INTO workflow_commands (workflow_id, seq, kind, timer_kind, state,"
                                + " recorded_at, available_at, fire_at, target_json) VALUES ($1, 1, 'TIMER',"
                                + " 'TIMEOUT', 'PENDING', now() - interval '3 seconds', now() - interval '1 second',"
                                + " now() - interval '1 second', $2::jsonb) RETURNING available_at")
                        .execute(Tuple.of(instance.value(), target)))
                .iterator()
                .next();
        return new PendingCommand(
                instance,
                1L,
                "TIMER",
                "PENDING",
                row.getOffsetDateTime("available_at").toInstant(),
                0,
                null);
    }

    /** A signal deferred for an hour, recorded at the given database time, behind the timeout. */
    private static PendingCommand insertSignal(Pool pool, WorkflowInstanceId instance, String name, String recordedAt) {
        Row row = await(pool.preparedQuery("INSERT INTO workflow_commands (workflow_id, seq, kind, state, recorded_at,"
                                + " available_at, target_json) VALUES ($1, 2, 'SIGNAL', 'DEFERRED', " + recordedAt
                                + ", now() + interval '1 hour', $2::jsonb) RETURNING available_at")
                        .execute(Tuple.of(instance.value(), new JsonObject().put("name", name))))
                .iterator()
                .next();
        return new PendingCommand(
                instance,
                2L,
                "SIGNAL",
                "DEFERRED",
                row.getOffsetDateTime("available_at").toInstant(),
                0,
                null);
    }

    // --- Legacy timer fixture ---

    /** The metadata of a timer written before commands, with correlation L and an identity snapshot of user-77. */
    private static DurableMetadata legacyMetadata(SignalFixture f, UUID timerId, WorkflowInstanceId instance) {
        DurableCarrierDescriptor carrier = new DurableCarrierDescriptor(
                timerId.toString(),
                new DurableTarget("workflow-timer", instance.value().toString(), Optional.empty()));
        return await(f.caller(() -> {
            var holder = f.fixture.holder();
            var correlation = holder.bind(CorrelationCtx.class, new CorrelationCtx("L"));
            var identity = holder.bind(IdentitySnapshotCtx.class, new IdentitySnapshotCtx("user-77"));
            try {
                return Future.succeededFuture(f.fixture
                        .propagator()
                        .mergeCaptured(DurableMetadata.empty(), DispatchBoundary.DELAYED_JOB, carrier));
            } finally {
                identity.close();
                correlation.close();
            }
        }));
    }

    private static JobExecution deadLetteredJob() {
        Instant now = Instant.now();
        return new JobExecution(
                UUID.randomUUID(),
                "job-dead-lettered",
                JobType.DELAYED,
                "vertique.workflow.timer.fire",
                "default",
                JobState.DEAD_LETTER,
                0,
                5,
                null,
                0,
                null,
                now,
                now,
                null,
                null,
                null,
                null,
                ProgressSnapshot.EMPTY,
                Map.of(),
                Map.of(),
                DurableMetadata.empty());
    }

    // --- Observation and manipulation of timer rows ---

    /** A {@code TIMER} row of {@code workflow_commands}, as the tests read it. */
    record TimerRow(
            long id,
            long seq,
            String timerKind,
            String state,
            @Nullable UUID timerId,
            @Nullable UUID taskId,
            @Nullable JsonObject target,
            @Nullable JsonObject context,
            OffsetDateTime recordedAt,
            OffsetDateTime availableAt,
            @Nullable OffsetDateTime fireAt,
            @Nullable OffsetDateTime settledAt,
            int attempts) {}

    /** A row of {@code workflow_timers}, as the tests read it. */
    record TimerRecordRow(
            UUID timerId,
            String status,
            @Nullable Long commandId,
            @Nullable UUID delayedJobExecutionId,
            String purpose,
            @Nullable JsonObject metadata) {}

    /** Reads and rewrites the timer commands and timer rows of an instance. */
    static final class TimerFixture {
        private final SignalFixture f;

        TimerFixture(SignalFixture f) {
            this.f = f;
        }

        /** The instance's timer commands in queue order. */
        List<TimerRow> all(WorkflowInstanceId id) {
            List<TimerRow> rows = new ArrayList<>();
            for (Row row : await(f.fixture
                    .observer()
                    .preparedQuery(SELECT_TIMER + " WHERE workflow_id = $1 AND kind = 'TIMER' ORDER BY seq")
                    .execute(Tuple.of(id.value())))) {
                rows.add(map(row));
            }
            return rows;
        }

        /** The only timer command of the kind. */
        TimerRow only(WorkflowInstanceId id, String timerKind) {
            List<TimerRow> matching = all(id).stream()
                    .filter(row -> row.timerKind().equals(timerKind))
                    .toList();
            if (matching.size() != 1) {
                throw new AssertionError("expected one " + timerKind + " timer command of " + id + " but found "
                        + all(id) + "; the instance is "
                        + f.fixture.instanceOf(id).status() + " with commands "
                        + f.fixture.commandsOf(id).stream()
                                .map(row -> row.kind() + " " + row.state() + " " + row.lastError())
                                .toList());
            }
            return matching.getFirst();
        }

        TimerRow byId(long commandId) {
            return map(await(f.fixture
                            .observer()
                            .preparedQuery(SELECT_TIMER + " WHERE id = $1")
                            .execute(Tuple.of(commandId)))
                    .iterator()
                    .next());
        }

        /** The timer row the command links to. */
        TimerRecordRow timerRowOf(TimerRow command) {
            if (command.timerId() == null) {
                throw new AssertionError("timer command " + command.id() + " names no timer");
            }
            Row row = await(f.fixture
                            .observer()
                            .preparedQuery("SELECT timer_id, status, command_id, delayed_job_execution_id, purpose,"
                                    + " metadata FROM workflow_timers WHERE timer_id = $1")
                            .execute(Tuple.of(command.timerId())))
                    .iterator()
                    .next();
            return new TimerRecordRow(
                    row.getUUID("timer_id"),
                    row.getString("status"),
                    row.getLong("command_id"),
                    row.getUUID("delayed_job_execution_id"),
                    row.getString("purpose"),
                    (JsonObject) row.getValue("metadata"));
        }

        /** The number of timer rows of the instance that name a delayed-job execution. */
        long delayedJobTimers(WorkflowInstanceId id) {
            return f.scalar(
                    "SELECT count(*) FROM workflow_timers WHERE workflow_id = $1 AND delayed_job_execution_id IS NOT"
                            + " NULL",
                    id);
        }

        /** Makes the command due now: its fire time and availability, and its timer row's fire time, are the clock. */
        void dueNow(long commandId) {
            fireAt(commandId, Duration.ZERO);
        }

        /** Moves the command's fire time and availability, and its timer row's fire time, to the clock plus offset. */
        void fireAt(long commandId, Duration offset) {
            OffsetDateTime at = await(f.fixture
                            .observer()
                            .preparedQuery("UPDATE workflow_commands SET fire_at = clock_timestamp() + ($2::bigint *"
                                    + " interval '1 millisecond'), available_at = clock_timestamp() + ($2::bigint *"
                                    + " interval '1 millisecond') WHERE id = $1 RETURNING fire_at")
                            .execute(Tuple.of(commandId, offset.toMillis())))
                    .iterator()
                    .next()
                    .getOffsetDateTime("fire_at");
            await(f.fixture
                    .observer()
                    .preparedQuery("UPDATE workflow_timers SET fire_at = $2 WHERE command_id = $1")
                    .execute(Tuple.of(commandId, at)));
            await(f.fixture
                    .observer()
                    .preparedQuery("UPDATE workflow_commands SET available_at = $2 WHERE id = $1")
                    .execute(Tuple.of(commandId, at)));
        }

        /**
         * Rewrites a timer and its command as the upgrade conversion of a legacy timer writes them: the timer row
         * holds the metadata, the command holds no context, and the command is due now.
         */
        void convert(TimerRow command, DurableMetadata metadata) {
            await(f.fixture
                    .observer()
                    .preparedQuery("UPDATE workflow_timers SET metadata = $2 WHERE timer_id = $1")
                    .execute(Tuple.of(command.timerId(), metadata.toCarrier())));
            await(f.fixture
                    .observer()
                    .preparedQuery("UPDATE workflow_commands SET context_json = NULL WHERE id = $1")
                    .execute(Tuple.of(command.id())));
            dueNow(command.id());
        }

        /** A timeout stores the wait it belongs to: the step, the instance address and the signals it accepts. */
        void assertTargetsWait(TimerRow timeout, String stepId, String... signalNames) {
            assertThat(timeout.fireAt()).isNotNull();
            assertThat(timeout.availableAt()).isEqualTo(timeout.fireAt());
            JsonObject target = timeout.target();
            assertThat(target).as("target_json of the timeout").isNotNull();
            assertThat(target.getString("stepId")).isEqualTo(stepId);
            assertThat(target.getString("branchId")).isNull();
            assertThat(target.getString("forkStepId")).isNull();
            assertThat(target.getJsonArray("signalNames").getList()).containsExactlyInAnyOrder((Object[]) signalNames);
            assertThat(timerRowOf(timeout).commandId()).isEqualTo(timeout.id());
            assertThat(timerRowOf(timeout).delayedJobExecutionId()).isNull();
        }

        private static final String SELECT_TIMER = "SELECT id, seq, timer_kind, state, timer_id, task_id, target_json,"
                + " context_json, recorded_at, available_at, fire_at, settled_at, attempts FROM workflow_commands";

        private static TimerRow map(Row row) {
            return new TimerRow(
                    row.getLong("id"),
                    row.getLong("seq"),
                    row.getString("timer_kind"),
                    row.getString("state"),
                    row.getUUID("timer_id"),
                    row.getUUID("task_id"),
                    (JsonObject) row.getValue("target_json"),
                    (JsonObject) row.getValue("context_json"),
                    row.getOffsetDateTime("recorded_at"),
                    row.getOffsetDateTime("available_at"),
                    row.getOffsetDateTime("fire_at"),
                    row.getOffsetDateTime("settled_at"),
                    row.getInteger("attempts"));
        }
    }

    /** Opens fixture tasks and completes them through the recording engine. */
    static final class TaskDriver {
        private final SignalFixture f;

        TaskDriver(SignalFixture f) {
            this.f = f;
        }

        /** Starts the fixture workflow and runs it to its task wait. */
        OpenTask open(Node node, String ref) {
            WorkflowInstanceId id = f.waiting(node, ref);
            if (f.scalar("SELECT count(*) FROM workflow_tasks WHERE workflow_id = $1", id) != 1) {
                throw new AssertionError("expected one task of " + id + " but the instance is "
                        + f.fixture.instanceOf(id).status() + " with commands "
                        + f.fixture.commandsOf(id).stream()
                                .map(row -> row.kind() + " " + row.state() + " " + row.lastError())
                                .toList());
            }
            UUID taskId = await(f.fixture
                            .observer()
                            .preparedQuery("SELECT task_id FROM workflow_tasks WHERE workflow_id = $1")
                            .execute(Tuple.of(id.value())))
                    .iterator()
                    .next()
                    .getUUID("task_id");
            return new OpenTask(id, taskId);
        }

        Future<TaskMutationResult> completeAsync(Node node, OpenTask task, JsonObject payload, String key) {
            return f.caller(() -> node.pool.withTransaction(tx ->
                    node.engine.taskCompleted(RecordedCompletionIT.command(task, "approve", payload, key, null), tx)));
        }

        TaskMutationResult complete(Node node, OpenTask task, JsonObject payload, String key) {
            return await(completeAsync(node, task, payload, key));
        }

        String statusOf(OpenTask task) {
            return await(f.fixture
                            .observer()
                            .preparedQuery("SELECT status FROM workflow_tasks WHERE task_id = $1")
                            .execute(Tuple.of(task.taskId())))
                    .iterator()
                    .next()
                    .getString("status");
        }

        int remindersFired(OpenTask task) {
            return await(f.fixture
                            .observer()
                            .preparedQuery("SELECT reminders_fired_count FROM workflow_tasks WHERE task_id = $1")
                            .execute(Tuple.of(task.taskId())))
                    .iterator()
                    .next()
                    .getInteger("reminders_fired_count");
        }

        List<CommandRow> completions(WorkflowInstanceId id) {
            return f.fixture.commandsOf(id).stream()
                    .filter(row -> row.kind().equals("COMPLETION"))
                    .toList();
        }
    }

    /**
     * Holds the next segment of a node's executor at a chosen point: before it asks for the execution lock, or inside
     * the lock after its work, before the commit. The first segment that starts after arming is the one held.
     */
    static final class SegmentPause {
        private enum Mode {
            BEFORE_LOCK,
            INSIDE_LOCK
        }

        private final AtomicReference<Mode> armed = new AtomicReference<>();
        private volatile CompletableFuture<Void> reached = new CompletableFuture<>();
        private volatile Promise<Void> released = Promise.promise();

        void armBeforeLock() {
            arm(Mode.BEFORE_LOCK);
        }

        void armInsideLock() {
            arm(Mode.INSIDE_LOCK);
        }

        private void arm(Mode mode) {
            reached = new CompletableFuture<>();
            released = Promise.promise();
            armed.set(mode);
        }

        /** Waits until a segment is held. */
        void awaitReached() {
            try {
                reached.get(IDLE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                throw new AssertionError("no segment reached the pause", e);
            }
        }

        /** Lets the held segment go on; harmless when none is held. */
        void release() {
            released.tryComplete();
        }

        UnaryOperator<WorkflowTransactionRunner<SqlClient>> decorator() {
            return real -> new WorkflowTransactionRunner<>() {
                @Override
                public <R> Future<R> inTransaction(
                        @Nullable IsolationLevel level, Function<SqlClient, Future<R>> work) {
                    return real.inTransaction(level, work);
                }

                @Override
                public <R> Future<R> inTransaction(
                        @Nullable IsolationLevel level, Duration deadline, Function<SqlClient, Future<R>> work) {
                    Mode mode = armed.getAndSet(null);
                    if (mode == null) {
                        return real.inTransaction(level, deadline, work);
                    }
                    CompletableFuture<Void> signalReached = reached;
                    Promise<Void> gate = released;
                    if (mode == Mode.BEFORE_LOCK) {
                        signalReached.complete(null);
                        return gate.future().compose(ignored -> real.inTransaction(level, deadline, work));
                    }
                    return real.inTransaction(
                            level, deadline, tx -> work.apply(tx).compose(result -> {
                                signalReached.complete(null);
                                return gate.future().map(result);
                            }));
                }
            };
        }
    }

    // --- Workflows ---

    /**
     * A signal wait for approve with a timeout. Approve leads to a wait for note; the timeout leads to a wait for
     * finish.
     */
    static final class TimeoutWait extends Definition {
        TimeoutWait() {
            super("timer-command-timeout", wf -> {
                wf.initialStep("w");
                wf.waitForSignal("w", "approve", Signal.class)
                        .onSignal((state, payload) -> state.add("approve", payload))
                        .toStepOnSignal("w2")
                        .timeoutAfter(Duration.ofMinutes(10))
                        .onTimeout(state -> state.add("timeout", signal("timeout")))
                        .toStepOnTimeout("late");
                waitFor(wf, "w2", "note", "done");
                waitFor(wf, "late", "finish", "done");
                wf.complete("done");
            });
        }
    }

    /** A timer node followed by the end. */
    static final class TimerNode extends Definition {
        TimerNode(Duration delay) {
            super("timer-command-node", wf -> {
                wf.initialStep("t");
                wf.timer("t", delay).toStep("done");
                wf.complete("done");
            });
        }
    }

    /** A timer node, a step that runs the probe, and a second timer node. */
    static final class ProbedTimers extends Definition {
        ProbedTimers(SecurityProbe probe) {
            super("timer-command-probed", wf -> {
                wf.initialStep("t1");
                wf.timer("t1", Duration.ofHours(1)).toStep("probe");
                wf.decide("probe", state -> {
                    probe.observe("after-timer", state.ref());
                    return "t2";
                });
                wf.timer("t2", Duration.ofHours(1)).toStep("done");
                wf.complete("done");
            });
        }
    }

    /** A fork of a branch that waits for ack and a branch that waits on a timer of a minute. */
    static final class ParallelWait extends Definition {
        ParallelWait() {
            super("timer-command-parallel", wf -> {
                wf.initialStep("fork")
                        .fork("fork")
                        .branch("a", "a-ack", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                        .branch("b", "b-timer", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                        .join("join");
                waitFor(wf, "a-ack", "ack", "a-done");
                wf.complete("a-done");
                wf.timer("b-timer", Duration.ofSeconds(60)).toStep("b-done");
                wf.complete("b-done");
                wf.join("join")
                        .allRequired((state, results) -> state)
                        .toStep("done")
                        .onFailure("done")
                        .endJoin();
                wf.complete("done");
            });
        }
    }

    /** A task with a due date of an hour; approving can be made to throw, and expiring leads to an own end. */
    static final class DueDateTask extends Definition {
        DueDateTask() {
            super("timer-command-due-date", wf -> {
                wf.initialStep("review");
                wf.task("review")
                        .assignToRole("compliance")
                        .dueIn(Duration.ofHours(1))
                        .onDue(state -> state.add("expired", signal("expired")))
                        .toStepOnDue("expired")
                        .decision("approve", Decision.class)
                        .onDecision((state, decision) -> {
                            if (decision.fail()) {
                                throw new IllegalStateException("applicator echoed " + decision.note());
                            }
                            return state.add("approve", signal("approve"));
                        })
                        .toStep("done")
                        .build();
                wf.complete("expired");
                wf.complete("done");
            });
        }
    }

    /** A task with a reminder every hour, at most three times. */
    static final class RecurringReminderTask extends Definition {
        RecurringReminderTask() {
            super("timer-command-reminder", wf -> {
                wf.initialStep("review");
                wf.task("review")
                        .assignToRole("compliance")
                        .decision("approve", Decision.class)
                        .onDecision((state, decision) -> state.add("approve", signal("approve")))
                        .toStep("done")
                        .reminderEvery(Duration.ofHours(1), 3)
                        .build();
                wf.complete("done");
            });
        }
    }
}
