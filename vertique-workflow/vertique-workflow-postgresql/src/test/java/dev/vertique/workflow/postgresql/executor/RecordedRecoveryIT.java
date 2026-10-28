// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.failureOf;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.sleep;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.startOf;
import static dev.vertique.workflow.postgresql.executor.RecordedSignalIT.signal;
import static dev.vertique.workflow.postgresql.executor.RecordedSignalIT.waitFor;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.exception.WorkflowSignalRejectedException;
import dev.vertique.workflow.history.PendingCommand;
import dev.vertique.workflow.migration.DefaultWorkflowMigrationRegistry;
import dev.vertique.workflow.migration.WorkflowMigrationIllegalStateException;
import dev.vertique.workflow.ops.WorkflowExecutionProbe.IdleReport;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.InstanceRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.RecordedCompletionIT.OpenTask;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.Definition;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.OrderSignalFixture;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.Signal;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.SignalFixture;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.DueDateTask;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TaskDriver;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TimerFixture;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TimerRow;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that {@code retry} is a recorded command and the one recovery operation: it initializes an instance whose
 * start failed before initialization from the stored start payload, re-drives a failed instance and applies the
 * signals parked meanwhile, and then offers every dead-lettered command again in its original sequence; and that an
 * instance which ends settles its dead letters.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
public class RecordedRecoveryIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_recorded_recovery_it")
            .withMigration("classpath:db/migration/workflow");

    private static final Duration DEAD_LETTERED = Duration.ofSeconds(45);
    private static final String SECRET = "zz-secret-marker";

    @Test
    @DisplayName(
            "a start whose initializer throws fails without state and retry initializes it from the stored payload")
    void failedInitializationIsRetriedFromTheStoredPayload() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given an initial-state callback that throws while the switch is on
            AtomicBoolean broken = new AtomicBoolean(true);
            fixture.onInitialState = ref -> {
                if (broken.get()) {
                    throw new IllegalStateException(SECRET + " initializer broken");
                }
            };
            Node node = fixture.node("engine").build();
            WorkflowInstanceId id = node.record(startOf("init-retry"));

            // When the start executes
            node.startExecutor();
            awaitCondition("the start to fail", IDLE, () -> fixture.commandOf(id)
                    .state()
                    .equals("FAILED"));

            // Then the instance is failed without state, with the reason logged
            InstanceRow failed = fixture.instanceOf(id);
            assertThat(failed.status()).isEqualTo("FAILED");
            assertThat(failed.stateJson()).isNull();
            assertThat(fixture.reasonRecorded(id, ReasonCodes.DETERMINISTIC_FAILURE))
                    .isTrue();

            // When the switch is turned off, a retry is recorded and the instance runs until idle
            broken.set(false);
            retry(node, id);
            node.awaitIdle(id);

            // Then the instance is initialized from the stored payload and waiting at its first wait
            InstanceRow retried = fixture.instanceOf(id);
            assertThat(retried.status()).isEqualTo("WAITING");
            assertThat(retried.currentStepId()).isEqualTo("paid");
            assertThat(String.valueOf(retried.stateJson())).contains("init-retry");
            assertThat(fixture.initialStates).containsExactly("init-retry", "init-retry");
            assertThat(fixture.intentsOf(id)).containsExactly("charge");

            // And the start stays failed while the retry is complete
            List<CommandRow> commands = fixture.commandsOf(id);
            assertThat(commands).extracting(CommandRow::kind).containsExactly("START", "RETRY");
            assertThat(commands).extracting(CommandRow::state).containsExactly("FAILED", "COMPLETE");
            assertThat(fixture.historyOf(id)).contains("COMMAND_COMPLETED");
        }
    }

    @Test
    @DisplayName("retry of a dead-lettered start initializes the instance once and supersedes the start")
    void retryOfADeadLetteredStartSupersedesIt() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given two attempts, a lease of five seconds, and a start whose every segment is abandoned until the
            // start is dead-lettered
            CommandFaults faults = new CommandFaults();
            Node node = fixture.node("engine")
                    .leaseSeconds(5)
                    .maxAttempts(2)
                    .commands(faults.decorator())
                    .build();
            WorkflowInstanceId id = node.record(startOf("dead-start"));
            faults.crashEverySegmentOf(id);
            node.startExecutor();
            awaitCondition("the start to be dead-lettered", DEAD_LETTERED, () -> fixture.commandOf(id)
                    .state()
                    .equals("DEAD"));
            node.awaitIdle(id);
            faults.crashSegmentsOf(id, 0);
            assertThat(fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(fixture.initialStates).isEmpty();
            assertThat(fixture.intentsOf(id)).isEmpty();

            // When a retry is recorded and the instance runs until idle
            retry(node, id);
            node.awaitIdle(id);

            // Then the instance was initialized once and wrote its intent once
            assertThat(fixture.instanceOf(id).status()).isEqualTo("WAITING");
            assertThat(fixture.instanceOf(id).currentStepId()).isEqualTo("paid");
            assertThat(fixture.initialStates).containsExactly("dead-start");
            assertThat(fixture.intentsOf(id)).containsExactly("charge");

            // And the dead-lettered start is superseded, not offered again
            List<CommandRow> commands = fixture.commandsOf(id);
            assertThat(commands).extracting(CommandRow::kind).containsExactly("START", "RETRY");
            assertThat(commands).extracting(CommandRow::state).containsExactly("SUPERSEDED", "COMPLETE");
        }
    }

    @Test
    @DisplayName("a pending branch retry does not stand for the instance retry that a failed instance needs")
    void pendingBranchRetryDoesNotCountAsAnInstanceRetry() {
        try (SignalFixture f =
                new SignalFixture(db.toPoolConfig(), new OrderSignalFixture(), List.of("paid", "shipped", "note"))) {
            // Given a failed instance and a pending retry addressed to one of its branches, as the upgrade leaves one
            BreakableTimeout breakable = new BreakableTimeout();
            f.seed(breakable, List.of("approve", "finish"), new JsonObject());
            TimerFixture timers = new TimerFixture(f);
            Node node = f.node("engine").build();
            WorkflowInstanceId failed = await(
                    f.caller(() -> node.pool.withTransaction(tx -> node.engine.start(f.start(breakable, "B"), tx))));
            f.idle(node, failed);
            timers.dueNow(timers.only(failed, "TIMEOUT").id());
            f.idle(node, failed);
            assertThat(f.fixture.instanceOf(failed).status()).isEqualTo("FAILED");
            insertBranchRetry(f, failed);

            // When a retry of the instance is recorded
            retry(f, node, failed);

            // Then an instance-level retry is recorded next to the branch retry
            assertThat(retryTargets(f, failed))
                    .containsExactlyInAnyOrder("{\"branchId\": \"b1\", \"forkStepId\": \"fork\"}", "");

            // And a repeated retry records nothing more, because the instance-level one is on its way
            retry(f, node, failed);
            assertThat(retryTargets(f, failed)).hasSize(2);
        }
    }

    @Test
    @DisplayName("an ended instance rejects its dead letters and refuses recovery; a second retry finds nothing to do")
    void endedInstanceRejectsDeadLettersAndRefusesRecovery() {
        try (SignalFixture f =
                new SignalFixture(db.toPoolConfig(), new OrderSignalFixture(), List.of("paid", "shipped", "note"))) {
            // Given instance A waiting for paid whose first paid signal is dead-lettered, and instance B failed
            BreakableTimeout breakable = new BreakableTimeout();
            f.seed(breakable, List.of("approve", "finish"), new JsonObject());
            TimerFixture timers = new TimerFixture(f);
            CommandFaults faults = new CommandFaults();
            Node node = f.node("engine")
                    .leaseSeconds(5)
                    .maxAttempts(2)
                    .commands(faults.decorator())
                    .migrations(new DefaultWorkflowMigrationRegistry(Set.of(), f.registry))
                    .build();
            WorkflowInstanceId a = f.waiting(node, "end-A");
            WorkflowInstanceId b = await(
                    f.caller(() -> node.pool.withTransaction(tx -> node.engine.start(f.start(breakable, "B"), tx))));
            f.idle(node, b);
            timers.dueNow(timers.only(b, "TIMEOUT").id());
            f.idle(node, b);
            assertThat(f.fixture.instanceOf(b).status()).isEqualTo("FAILED");
            assertThat(f.fixture.instanceOf(b).stateJson()).isNotNull();
            faults.crashSegmentsOf(a, 2);
            f.record(node, a, "paid", signal("one"), "k1");
            node.startExecutor();
            try {
                awaitCondition("the first paid to be dead-lettered", DEAD_LETTERED, () -> f.signal(a, "k1")
                        .state()
                        .equals("DEAD"));
                assertThat(f.openSignals(a)).isZero();

                // When a second paid completes A
                f.record(node, a, "paid", signal("two"), "k2");
                node.awaitIdle(a);
            } finally {
                node.stopExecutor();
            }

            // Then A is completed and the dead-lettered signal is rejected with history
            assertThat(f.fixture.instanceOf(a).status()).isEqualTo("COMPLETED");
            CommandRow dead = f.signal(a, "k1");
            assertThat(dead.state()).isEqualTo("REJECTED");
            assertThat(dead.lastError()).matches("(?s)^[A-Z][A-Z_]*: .+");
            assertThat(f.entry(a, "COMMAND_REJECTED", dead.id())).isNotNull();
            assertThat(f.signal(a, "k2").state()).isEqualTo("COMPLETE");
            assertThat(f.openSignals(a)).isZero();

            // When the caller records retry and migrate for the ended instance
            List<CommandRow> before = f.fixture.commandsOf(a);
            Throwable retryRefusal =
                    failureOf(f.caller(() -> node.pool.withTransaction(tx -> node.engine.retry(a, tx))), IDLE);
            Throwable migrateRefusal =
                    failureOf(f.caller(() -> node.pool.withTransaction(tx -> node.engine.migrate(a, 2L, tx))), IDLE);

            // Then both are refused in the caller's transaction and record nothing
            assertThat(retryRefusal)
                    .isInstanceOf(WorkflowSignalRejectedException.class)
                    .hasMessageContaining(a.value().toString())
                    .hasMessageContaining("COMPLETED");
            assertThat(migrateRefusal)
                    .isInstanceOfAny(
                            WorkflowMigrationIllegalStateException.class, WorkflowSignalRejectedException.class)
                    .hasMessageContaining("COMPLETED");
            assertThat(f.fixture.commandsOf(a)).isEqualTo(before);

            // When two retries of the failed instance B are recorded, one after the other, and run
            breakable.broken.set(false);
            retry(f, node, b);
            retry(f, node, b);

            // Then the second call succeeded and recorded nothing, because one retry is already on its way
            assertThat(f.fixture.commandsOf(b))
                    .filteredOn(row -> row.kind().equals("RETRY"))
                    .hasSize(1);

            // And when a duplicate recorded some other way is queued behind it, the first retry recovers B and the
            // duplicate finds nothing to recover
            insertRetry(f, b);
            f.idle(node, b);
            List<CommandRow> retries = f.fixture.commandsOf(b).stream()
                    .filter(row -> row.kind().equals("RETRY"))
                    .toList();
            assertThat(retries).extracting(CommandRow::state).containsExactly("COMPLETE", "STALE");
            assertThat(f.fixture.instanceOf(b).status()).isEqualTo("WAITING");
            assertThat(f.fixture.historyOf(b)).contains("COMMAND_STALE");
        }
    }

    @Test
    @DisplayName(
            "a signal parked for the wait after a failed timeout is applied by the segment of the timeout offered again")
    void retryAppliesParkedSignalsAtTheReachedWait() {
        BreakableTimeout breakable = new BreakableTimeout();
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), breakable, List.of("approve", "finish"))) {
            TimerFixture timers = new TimerFixture(f);
            Node node = f.node("engine").build();

            // Given an instance failed by its timeout path, and a finish signal parked while it was failed
            WorkflowInstanceId id = f.waiting(node, "parked-retry");
            TimerRow timeout = timers.only(id, "TIMEOUT");
            timers.dueNow(timeout.id());
            f.idle(node, id);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            f.record(node, id, "finish", signal("parked"), "k1");
            f.idle(node, id);
            CommandRow parked = f.signal(id, "k1");
            assertThat(parked.state()).isEqualTo("PARKED");

            // When the timeout path is fixed, retry is recorded and executes
            breakable.broken.set(false);
            retry(f, node, id);
            f.idle(node, id);

            // Then the retry is complete and the signal, which the wait the instance failed in does not accept, was
            // applied by the segment of the timeout, at the wait the timeout led to
            CommandRow retry = f.fixture.commandsOf(id).stream()
                    .filter(row -> row.kind().equals("RETRY"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no retry command of " + id));
            assertThat(retry.state()).isEqualTo("COMPLETE");
            assertThat(f.signal(id, "k1").state()).isEqualTo("COMPLETE");
            TimerRow offered = timers.all(id).getLast();
            assertThat(offered.state()).isEqualTo("COMPLETE");
            assertThat(f.entry(id, "SIGNAL_APPLIED", parked.id()).transaction())
                    .isEqualTo(f.entry(id, "COMMAND_COMPLETED", offered.id()).transaction());

            // And the timeout was taken before the signal, which changed the state of the instance that then ended
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("COMPLETED");
            assertThat(applied(f, id)).containsExactly("timeout:timeout", "finish:parked");
            assertThat(f.openSignals(id)).isZero();
        }
    }

    @Test
    @DisplayName("a dead-lettered completion holds its due date until retry applies the completion")
    void deadLetteredCompletionHoldsItsDueDateUntilRetry() {
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), new DueDateTask(), List.of())) {
            TimerFixture timers = new TimerFixture(f);
            TaskDriver tasks = new TaskDriver(f);
            CommandFaults faults = new CommandFaults();
            Node node = f.node("engine")
                    .leaseSeconds(5)
                    .maxAttempts(2)
                    .commands(faults.decorator())
                    .build();

            // Given a task with a due date whose completion is abandoned until it is dead-lettered, and the due date
            // passed
            OpenTask task = tasks.open(node, "dead-completion");
            TimerRow due = timers.only(task.id(), "DUE");
            tasks.complete(node, task, new JsonObject(), "k1");
            faults.crashSegmentsOf(task.id(), 2);
            timers.dueNow(due.id());
            node.startExecutor();
            try {
                awaitCondition(
                        "the completion to be dead-lettered",
                        DEAD_LETTERED,
                        () -> tasks.completions(task.id()).getFirst().state().equals("DEAD"));

                // When executors keep running for a while
                long until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while (System.nanoTime() < until) {
                    // Then the due date does not fire and the task stays reserved
                    assertThat(timers.byId(due.id()).state()).isEqualTo("PENDING");
                    assertThat(tasks.statusOf(task)).isEqualTo("COMPLETING");
                    sleep(100);
                }
                IdleReport report = await(node.engine.executionProbe().awaitIdle(task.id(), IDLE));
                assertThat(report.stuck()).extracting(PendingCommand::kind).contains("COMPLETION");

                // When retry is recorded and the instance runs until idle
                retry(f, node, task.id());
                node.awaitIdle(task.id());
            } finally {
                node.stopExecutor();
            }

            // Then the completion applied once, the task is completed and the due date cancelled
            CommandRow completion = tasks.completions(task.id()).getFirst();
            assertThat(completion.state()).isEqualTo("COMPLETE");
            assertThat(completion.attempts()).isEqualTo(1);
            assertThat(tasks.statusOf(task)).isEqualTo("COMPLETED");
            assertThat(timers.byId(due.id()).state()).isEqualTo("CANCELLED");
            assertThat(timers.timerRowOf(due).status()).isEqualTo("CANCELLED");
            assertThat(f.fixture.instanceOf(task.id()).status()).isEqualTo("COMPLETED");
            assertThat(applied(f, task.id())).containsExactly("approve:approve");
            assertThat(f.fixture.historyOf(task.id())).doesNotContain("TASK_EXPIRED");
            assertThat(f.fixture.commandsOf(task.id()))
                    .filteredOn(row -> row.kind().equals("RETRY"))
                    .extracting(CommandRow::state)
                    .containsExactly("COMPLETE");
        }
    }

    @Test
    @DisplayName("retry fires a dead-lettered timeout before the signal parked behind it")
    void retryFiresTheDeadLetteredTimeoutBeforeTheParkedSignal() {
        TimeoutThenWait later = new TimeoutThenWait();
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), later, List.of("approve"))) {
            TimerFixture timers = new TimerFixture(f);
            CommandFaults faults = new CommandFaults();
            TimeoutThenEnd ending = new TimeoutThenEnd();
            f.seed(ending, List.of("approve"), new JsonObject());
            Node node = f.node("engine")
                    .leaseSeconds(5)
                    .maxAttempts(1)
                    .commands(faults.decorator())
                    .build();

            // Given two instances waiting with a timeout each, one whose timeout returns to the wait
            // and one whose timeout ends the instance
            WorkflowInstanceId waits = f.waiting(node, "timeout-then-wait");
            WorkflowInstanceId ends = await(f.caller(
                    () -> node.pool.withTransaction(tx -> node.engine.start(f.start(ending, "timeout-then-end"), tx))));
            f.idle(node, ends);
            TimerRow waitsTimeout = timers.only(waits, "TIMEOUT");
            TimerRow endsTimeout = timers.only(ends, "TIMEOUT");

            // And both timeouts are due, abandoned and dead-lettered, and an approve signal recorded after each
            // fire time is parked behind it
            faults.crashSegmentsOf(waits, 1);
            faults.crashSegmentsOf(ends, 1);
            node.startExecutor();
            try {
                timers.dueNow(waitsTimeout.id());
                timers.dueNow(endsTimeout.id());
                awaitCondition(
                        "both timeouts to be dead-lettered",
                        DEAD_LETTERED,
                        () -> timers.byId(waitsTimeout.id()).state().equals("DEAD")
                                && timers.byId(endsTimeout.id()).state().equals("DEAD"));
                f.record(node, waits, "approve", signal("late"), "late-1");
                f.record(node, ends, "approve", signal("late"), "late-2");
                node.awaitIdle(waits);
                node.awaitIdle(ends);
                assertThat(f.signal(waits, "late-1").state()).isEqualTo("PARKED");
                assertThat(f.signal(ends, "late-2").state()).isEqualTo("PARKED");

                // When retry is recorded for each instance and they run until idle
                retry(f, node, waits);
                retry(f, node, ends);
                node.awaitIdle(waits);
                node.awaitIdle(ends);
            } finally {
                node.stopExecutor();
            }

            // Then the first instance took its timeout branch and only then applied the parked signal at the next wait
            assertThat(timers.byId(waitsTimeout.id()).state()).isEqualTo("COMPLETE");
            assertThat(f.signal(waits, "late-1").state()).isEqualTo("COMPLETE");
            assertThat(applied(f, waits)).containsExactly("timeout:timeout", "approve:late");
            assertThat(f.fixture.instanceOf(waits).status()).isEqualTo("COMPLETED");

            // And the second instance ended on its timeout branch and rejected the parked signal unapplied
            assertThat(timers.byId(endsTimeout.id()).state()).isEqualTo("COMPLETE");
            assertThat(f.fixture.instanceOf(ends).status()).isEqualTo("COMPLETED");
            CommandRow rejected = f.signal(ends, "late-2");
            assertThat(rejected.state()).isEqualTo("REJECTED");
            assertThat(f.entry(ends, "COMMAND_REJECTED", rejected.id())).isNotNull();
            assertThat(applied(f, ends)).containsExactly("timeout:timeout");
            assertThat(f.openSignals(waits)).isZero();
            assertThat(f.openSignals(ends)).isZero();
        }
    }

    @Test
    @DisplayName(
            "crash-looping signals and completions are dead-lettered without failing the instance and retry applies"
                    + " them once")
    void crashLoopingSignalAndCompletionAreDeadLetteredThenAppliedOnceByRetry() {
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), new DueDateTask(), List.of())) {
            OrderSignalFixture order = new OrderSignalFixture();
            f.seed(order, List.of("paid", "shipped", "note"), new JsonObject());
            TaskDriver tasks = new TaskDriver(f);
            CommandFaults faults = new CommandFaults();

            // Given two attempts, a lease of five seconds and a stuck age of one second, an instance A waiting on
            // paid and an instance B with an open task
            Node node = f.node("engine")
                    .leaseSeconds(5)
                    .maxAttempts(2)
                    .stuckAfterSeconds(1)
                    .singleSegments()
                    .commands(faults.decorator())
                    .build();
            WorkflowInstanceId a =
                    await(f.caller(() -> node.pool.withTransaction(tx -> node.engine.start(f.start(order, "A"), tx))));
            f.idle(node, a);
            OpenTask b = tasks.open(node, "B");

            // When paid is recorded for A and the task of B is completed, and every segment of both is abandoned
            f.record(node, a, "paid", signal("paid"), "k-paid");
            tasks.complete(node, b, new JsonObject(), "k-done");
            faults.crashEverySegmentOf(a);
            faults.crashEverySegmentOf(b.id());
            node.startExecutor();
            IdleReport report;
            try {
                awaitCondition(
                        "both commands to be dead-lettered",
                        DEAD_LETTERED,
                        () -> f.signal(a, "k-paid").state().equals("DEAD")
                                && tasks.completions(b.id()).getFirst().state().equals("DEAD"));
                report = await(node.engine.executionProbe().awaitAllIdle(IDLE));
            } finally {
                faults.crashSegmentsOf(a, 0);
                faults.crashSegmentsOf(b.id(), 0);
                node.stopExecutor();
            }

            // Then both are dead-lettered while A and B keep their status, B's task stays reserved and A's counter
            // was lowered
            assertThat(f.fixture.instanceOf(a).status()).isEqualTo("WAITING");
            assertThat(f.fixture.instanceOf(b.id()).status()).isEqualTo("WAITING");
            assertThat(tasks.statusOf(b)).isEqualTo("COMPLETING");
            assertThat(report.stuck()).extracting(PendingCommand::instanceId).contains(a, b.id());
            assertThat(report.stuck()).extracting(PendingCommand::kind).contains("SIGNAL", "COMPLETION");
            assertThat(f.openSignals(a)).isZero();

            // When retry is recorded for A and its segment runs alone
            retry(f, node, a);
            node.runOneSegment();

            // Then the signal was offered again from zero attempts and counted again
            CommandRow offered = f.signal(a, "k-paid");
            assertThat(offered.state()).isEqualTo("PENDING");
            assertThat(offered.attempts()).isZero();
            assertThat(f.openSignals(a)).isEqualTo(1);
            assertThat(f.fixture.commandsOf(a))
                    .filteredOn(row -> row.kind().equals("RETRY"))
                    .extracting(CommandRow::state)
                    .containsExactly("COMPLETE");

            // When retry is recorded for B and the executors run until idle
            retry(f, node, b.id());
            node.startExecutor();
            try {
                node.awaitIdle(a);
                node.awaitIdle(b.id());
            } finally {
                node.stopExecutor();
            }

            // Then each command is complete after one claim since the retry, applied once
            CommandRow signal = f.signal(a, "k-paid");
            assertThat(signal.state()).isEqualTo("COMPLETE");
            assertThat(signal.attempts()).isEqualTo(1);
            assertThat(f.history(a))
                    .filteredOn(h -> h.type().equals("SIGNAL_APPLIED"))
                    .hasSize(1);
            assertThat(f.fixture.instanceOf(a).status()).isEqualTo("WAITING");
            assertThat(f.openSignals(a)).isZero();
            CommandRow completion = tasks.completions(b.id()).getFirst();
            assertThat(completion.state()).isEqualTo("COMPLETE");
            assertThat(completion.attempts()).isEqualTo(1);
            assertThat(tasks.statusOf(b)).isEqualTo("COMPLETED");
            assertThat(applied(f, b.id())).containsExactly("approve:approve");
            assertThat(f.fixture.commandsOf(b.id()))
                    .filteredOn(row -> row.kind().equals("RETRY"))
                    .extracting(CommandRow::state)
                    .containsExactly("COMPLETE");
        }
    }

    // --- Recording ---

    /** Records a retry in the caller's own transaction and commits it. */
    private static void retry(Node node, WorkflowInstanceId id) {
        await(node.pool.withTransaction(tx -> node.engine.retry(id, tx)));
    }

    /** Records a retry in the caller's own transaction, on a context of its own, and commits it. */
    private static void retry(SignalFixture f, Node node, WorkflowInstanceId id) {
        await(f.caller(() -> node.pool.withTransaction(tx -> node.engine.retry(id, tx))));
    }

    /** Queues a second pending retry for the instance with a statement of the database, as no caller can record one. */
    private static void insertRetry(SignalFixture f, WorkflowInstanceId id) {
        await(f.fixture.observer().withTransaction(connection -> connection
                .preparedQuery("INSERT INTO workflow_commands (workflow_id, seq, kind, state, recorded_at,"
                        + " available_at) SELECT $1, COALESCE(MAX(seq), 0) + 1, 'RETRY', 'PENDING',"
                        + " clock_timestamp(), clock_timestamp() FROM workflow_commands WHERE workflow_id = $1")
                .execute(io.vertx.sqlclient.Tuple.of(id.value()))
                .compose(ignored -> connection
                        .preparedQuery("UPDATE workflow_command_queues SET next_seq = next_seq + 1"
                                + " WHERE workflow_id = $1")
                        .execute(io.vertx.sqlclient.Tuple.of(id.value())))));
    }

    /** Queues a pending retry addressed to a branch with a statement of the database, as the upgrade writes one. */
    private static void insertBranchRetry(SignalFixture f, WorkflowInstanceId id) {
        await(f.fixture.observer().withTransaction(connection -> connection
                .preparedQuery("INSERT INTO workflow_commands (workflow_id, seq, kind, target_json, state,"
                        + " recorded_at, available_at) SELECT $1, COALESCE(MAX(seq), 0) + 1, 'RETRY',"
                        + " '{\"forkStepId\":\"fork\",\"branchId\":\"b1\"}'::jsonb, 'PENDING',"
                        + " clock_timestamp(), clock_timestamp() + interval '1 hour'"
                        + " FROM workflow_commands WHERE workflow_id = $1")
                .execute(io.vertx.sqlclient.Tuple.of(id.value()))
                .compose(ignored -> connection
                        .preparedQuery("UPDATE workflow_command_queues SET next_seq = next_seq + 1"
                                + " WHERE workflow_id = $1")
                        .execute(io.vertx.sqlclient.Tuple.of(id.value())))));
    }

    /** The targets of the instance's retry commands as text, empty for one without a target. */
    private static List<String> retryTargets(SignalFixture f, WorkflowInstanceId id) {
        List<String> targets = new java.util.ArrayList<>();
        for (io.vertx.sqlclient.Row row : await(f.fixture
                .observer()
                .preparedQuery("SELECT COALESCE(target_json::text, '') AS target FROM workflow_commands"
                        + " WHERE workflow_id = $1 AND kind = 'RETRY'")
                .execute(io.vertx.sqlclient.Tuple.of(id.value())))) {
            targets.add(row.getString("target"));
        }
        return targets;
    }

    /** The signals the state of the instance records as applied, in order. */
    private static List<Object> applied(SignalFixture f, WorkflowInstanceId id) {
        Object stored = f.snapshot(id).getValue("state_json");
        JsonObject state = stored instanceof String text ? new JsonObject(text) : (JsonObject) stored;
        JsonArray applied = state.getJsonArray("applied");
        return applied.getList();
    }

    // --- Fixture workflows ---

    /** A signal wait whose timeout path throws while the switch is on. */
    static final class BreakableTimeout extends Definition {
        final AtomicBoolean broken;

        BreakableTimeout() {
            this(new AtomicBoolean(true));
        }

        private BreakableTimeout(AtomicBoolean broken) {
            super("recovery-breakable-timeout", wf -> {
                wf.initialStep("w");
                wf.waitForSignal("w", "approve", Signal.class)
                        .onSignal((state, payload) -> state.add("approve", payload))
                        .toStepOnSignal("done")
                        .timeoutAfter(Duration.ofMinutes(10))
                        .onTimeout(state -> {
                            if (broken.get()) {
                                throw new IllegalStateException(SECRET + " timeout path broken");
                            }
                            return state.add("timeout", signal("timeout"));
                        })
                        .toStepOnTimeout("late");
                waitFor(wf, "late", "finish", "done");
                wf.complete("done");
            });
            this.broken = broken;
        }
    }

    /** A signal wait with a timeout whose branch returns to the same wait, which starts its timeout again. */
    static final class TimeoutThenWait extends Definition {
        TimeoutThenWait() {
            super("recovery-timeout-then-wait", wf -> {
                wf.initialStep("w");
                wf.waitForSignal("w", "approve", Signal.class)
                        .onSignal((state, payload) -> state.add("approve", payload))
                        .toStepOnSignal("done")
                        .timeoutAfter(Duration.ofMinutes(10))
                        .onTimeout(state -> state.add("timeout", signal("timeout")))
                        .toStepOnTimeout("w");
                wf.complete("done");
            });
        }
    }

    /** A signal wait with a timeout whose branch ends the instance. */
    static final class TimeoutThenEnd extends Definition {
        TimeoutThenEnd() {
            super("recovery-timeout-then-end", wf -> {
                wf.initialStep("w");
                wf.waitForSignal("w", "approve", Signal.class)
                        .onSignal((state, payload) -> state.add("approve", payload))
                        .toStepOnSignal("done")
                        .timeoutAfter(Duration.ofMinutes(10))
                        .onTimeout(state -> state.add("timeout", signal("timeout")))
                        .toStepOnTimeout("done");
                wf.complete("done");
            });
        }
    }
}
