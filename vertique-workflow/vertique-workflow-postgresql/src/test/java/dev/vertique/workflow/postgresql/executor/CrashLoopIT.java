// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.startOf;
import static dev.vertique.workflow.postgresql.executor.FailingTransactions.connectionFailure;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.history.PendingCommand;
import dev.vertique.workflow.ops.WorkflowExecutionProbe.IdleReport;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.InstanceRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

/**
 * Verifies what becomes of commands that never settle: a start that crashes every segment is dead-lettered and fails
 * its instance, a segment that never reaches a wait is failed at the step cap, an endless transient failure is
 * reported stuck and never dead-lettered, a claim that finds its instance ended settles the command, and the stored
 * reason of a failure carries no raw exception text.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CrashLoopIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_crash_loop_it")
            .withMigration("classpath:db/migration/workflow");

    private static final int LEASE_SECONDS = 5;
    private static final String SECRET_MARKER = "zz-secret-marker";
    private static final List<String> SEGMENT_ENTRIES =
            List.of("START", "COMMAND_RECEIVED", "COMMAND_COMPLETED", "SIDE_EFFECT_RECORDED");

    @Test
    @DisplayName("a crashing start is dead-lettered and fails its instance")
    void crashingStartIsDeadLetteredAndFailsItsInstance() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given two attempts, a lease of five seconds, and a start whose every segment is abandoned
            CommandFaults faults = new CommandFaults();
            Node node = fixture.node("engine")
                    .leaseSeconds(LEASE_SECONDS)
                    .maxAttempts(2)
                    .commands(faults.decorator())
                    .build();
            WorkflowInstanceId id = node.record(startOf("crash-loop"));
            faults.crashEverySegmentOf(id);

            // When the executor runs until idle
            node.startExecutor();
            awaitCondition("the start to be dead-lettered", Duration.ofSeconds(40), () -> fixture.commandOf(id)
                    .state()
                    .equals("DEAD"));
            IdleReport report = await(node.engine.executionProbe().awaitIdle(id, IDLE));

            // Then two claims spent an attempt each and the third dead-lettered the command
            CommandRow command = fixture.commandOf(id);
            assertThat(command.state()).isEqualTo("DEAD");
            assertThat(command.attempts()).isEqualTo(2);
            assertThat(command.claimToken()).isNull();
            assertThat(faults.crashes()).isEqualTo(2);
            assertThat(command.lastError()).startsWith(ReasonCodes.ATTEMPTS_EXHAUSTED + ": ");

            // And the instance is failed with the reason
            assertThat(fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(fixture.reasonRecorded(id, ReasonCodes.ATTEMPTS_EXHAUSTED))
                    .isTrue();
            assertThat(fixture.historyOf(id)).contains("COMMAND_DEAD_LETTERED");

            // And the report lists the dead-lettered command as stuck
            assertThat(report.stuck())
                    .containsExactly(new PendingCommand(
                            id, 1L, "START", "DEAD", command.availableAt().toInstant(), 2, command.lastError()));
        }
    }

    @Test
    @DisplayName("a loop without a wait fails after the step cap")
    void loopWithoutAWaitFailsAfterMaxStepsPerSegment() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given a step cap of 50 and a decision that loops to itself
            Node node = fixture.node("engine")
                    .leaseSeconds(LEASE_SECONDS)
                    .maxStepsPerSegment(50)
                    .build();
            WorkflowInstanceId id =
                    node.record(startOf(ExecutorFixture.LOOP, ExecutorFixture.LoopContract.class, "loop-1"));

            // When the start executes
            node.startExecutor();
            awaitCondition("the start to fail", Duration.ofSeconds(20), () -> fixture.commandOf(id)
                    .state()
                    .equals("FAILED"));

            // Then the instance is failed with the step-limit reason and has no partial state
            InstanceRow instance = fixture.instanceOf(id);
            assertThat(instance.status()).isEqualTo("FAILED");
            assertThat(instance.stateJson()).isNull();
            assertThat(instance.currentStepId()).isNull();
            assertThat(fixture.reasonRecorded(id, ReasonCodes.STEP_LIMIT)).isTrue();
            assertThat(fixture.intentCalls).isEmpty();
            assertThat(fixture.historyOf(id)).doesNotContainAnyElementsOf(SEGMENT_ENTRIES);

            // And the command is failed with the reason code first
            CommandRow command = fixture.commandOf(id);
            assertThat(command.state()).isEqualTo("FAILED");
            assertThat(command.lastError()).startsWith(ReasonCodes.STEP_LIMIT + ":");
            assertThat(command.claimToken()).isNull();
        }
    }

    @Test
    @DisplayName("an endless transient failure is reported stuck and never dead-lettered")
    void endlessTransientFailureIsReportedStuckNotDeadLettered() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given a stuck age of one second, a backoff of 0.5 to 1 second, and a runner that fails every segment with
            // a lost connection
            Node node = fixture.node("engine")
                    .leaseSeconds(LEASE_SECONDS)
                    .stuckAfterSeconds(1)
                    .transientBackoffMs(500, 1000)
                    .runner(FailingTransactions.always(connectionFailure()))
                    .build();
            WorkflowInstanceId id = node.record(startOf("endless-transient"));

            // When the executor retries several times, past the stuck age, and the test awaits idle
            node.startExecutor();
            Set<String> states = ConcurrentHashMap.newKeySet();
            Set<OffsetDateTime> availableAts = ConcurrentHashMap.newKeySet();
            awaitCondition("several retries past the stuck age", Duration.ofSeconds(30), () -> {
                CommandRow command = fixture.commandOf(id);
                states.add(command.state());
                availableAts.add(command.availableAt());
                return command.transientCount() >= 3
                        && fixture.databaseNow().isAfter(command.recordedAt().plusSeconds(2));
            });
            IdleReport report = await(node.engine.executionProbe().awaitIdle(id, Duration.ofSeconds(10)));

            // Then the command was never dead-lettered and holds at most the attempt of its claim
            CommandRow command = fixture.commandOf(id);
            assertThat(states).doesNotContain("DEAD");
            assertThat(command.state()).isNotEqualTo("DEAD");
            assertThat(command.attempts()).isLessThanOrEqualTo(1);

            // And it is reported stuck although its availability keeps moving forward
            assertThat(availableAts).hasSizeGreaterThan(1);
            assertThat(command.availableAt()).isAfter(command.recordedAt().plusSeconds(1));
            assertThat(report.stuck()).hasSize(1);
            assertThat(report.stuck().get(0).instanceId()).isEqualTo(id);
            assertThat(report.stuck().get(0).kind()).isEqualTo("START");
        }
    }

    @Test
    @DisplayName("a claim finding the instance ended rejects the command instead of dead-lettering it")
    void claimFindingTheInstanceEndedRejectsTheCommandInsteadOfDeadLettering() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given one attempt and a start claimed once whose segment was abandoned as a crash would
            CommandFaults faults = new CommandFaults();
            Node node = fixture.node("engine")
                    .leaseSeconds(LEASE_SECONDS)
                    .maxAttempts(1)
                    .commands(faults.decorator())
                    .build();
            WorkflowInstanceId id = node.record(startOf("crash-once-then-end"));
            crashOnceThenEnd(fixture, node, faults, id);

            // When the lease has expired and the next claim of the crashed command runs
            awaitCondition("the lease to end", IDLE, () -> fixture.leaseExpired(id));
            node.startExecutor();
            awaitCondition(
                    "the crashed command to be settled",
                    IDLE,
                    () -> !fixture.commandsOf(id).get(0).state().equals("CLAIMED"));
            IdleReport report = await(node.engine.executionProbe().awaitIdle(id, IDLE));

            // Then the command is rejected, without a token, with a history entry naming the ended instance
            CommandRow command = fixture.commandsOf(id).get(0);
            assertThat(command.state()).isEqualTo("REJECTED");
            assertThat(command.claimToken()).isNull();
            assertThat(command.attempts()).isEqualTo(1);
            assertThat(command.settledAt()).isNotNull();
            List<String> rejections = fixture.historyTextsOf(id).stream()
                    .filter(entry -> entry.startsWith("COMMAND_REJECTED "))
                    .toList();
            assertThat(rejections).hasSize(1);
            assertThat(rejections.get(0)).containsAnyOf(ReasonCodes.INSTANCE_ENDED, "COMPLETED", "ended");

            // And the instance stays completed, and the command is not reported stuck
            assertThat(fixture.instanceOf(id).status()).isEqualTo("COMPLETED");
            assertThat(report.stuck()).isEmpty();
        }
    }

    /** Claims the start once and abandons its segment, then ends the instance through a command of the fixture. */
    private static void crashOnceThenEnd(
            ExecutorFixture fixture, Node node, CommandFaults faults, WorkflowInstanceId id) {
        faults.crashSegmentsOf(id, 1);
        node.startExecutor();
        awaitCondition("the segment to be abandoned", IDLE, () -> faults.crashes() == 1);
        node.stopExecutor();
        assertThat(fixture.commandOf(id).state()).isEqualTo("CLAIMED");
        assertThat(fixture.commandOf(id).attempts()).isEqualTo(1);
        fixture.endInstanceThroughFixtureCommand(id);
    }

    @Test
    @DisplayName("last_error is a reason code and a sanitized message")
    void lastErrorIsAReasonCodeAndASanitizedMessage() {
        Logger workflowLogger = (Logger) LoggerFactory.getLogger("dev.vertique.workflow");
        Level previous = workflowLogger.getLevel();
        workflowLogger.setLevel(Level.DEBUG);
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given a stuck age of one second, a start A whose segments always fail with a lost connection whose
            // message holds a secret, and a start B whose initial-state callback throws the secret
            CommandFaults faults = new CommandFaults();
            Node node = fixture.node("engine")
                    .leaseSeconds(LEASE_SECONDS)
                    .stuckAfterSeconds(1)
                    .transientBackoffMs(60_000, 60_000)
                    .commands(faults.decorator())
                    .build();
            fixture.onInitialState = ref -> {
                if (ref.equals("deterministic")) {
                    throw new IllegalStateException(SECRET_MARKER + " {\"card\":\"4111\"}");
                }
            };
            WorkflowInstanceId a = node.record(startOf("transient"));
            WorkflowInstanceId b = node.record(startOf("deterministic"));
            faults.failSegmentsOf(a, Integer.MAX_VALUE, connectionFailure(SECRET_MARKER + " connection reset"));

            // When the executor runs and the test reads both command rows and the idle report
            node.startExecutor();
            awaitCondition("both failures to be stored", Duration.ofSeconds(30), () -> {
                return fixture.commandOf(a).lastError() != null
                        && fixture.commandOf(b).state().equals("FAILED");
            });
            awaitCondition("the transient command to pass the stuck age", IDLE, () -> fixture.databaseNow()
                    .isAfter(fixture.commandOf(a).recordedAt().plusSeconds(2)));
            IdleReport report = await(node.engine.executionProbe().awaitAllIdle(Duration.ofSeconds(10)));
            CommandRow transientCommand = fixture.commandOf(a);
            CommandRow deterministicCommand = fixture.commandOf(b);

            // Then each stored reason is a code and a message without the secret
            assertSanitized(transientCommand.lastError(), ReasonCodes.TRANSIENT_FAILURE);
            assertSanitized(deterministicCommand.lastError(), ReasonCodes.DETERMINISTIC_FAILURE);
            assertThat(transientCommand.state()).isIn("PENDING", "CLAIMED");
            assertThat(deterministicCommand.state()).isEqualTo("FAILED");

            // And the report carries the stored value of the stuck command
            assertThat(report.stuck()).hasSize(1);
            assertThat(report.stuck().get(0).instanceId()).isEqualTo(a);
            assertThat(report.stuck().get(0).lastError()).isEqualTo(transientCommand.lastError());

            // And the full exception went to the log
            assertThat(logHolds(fixture, SECRET_MARKER)).isTrue();
        } finally {
            workflowLogger.setLevel(previous);
        }
    }

    private static void assertSanitized(String lastError, String expectedCode) {
        assertThat(lastError).isNotNull().matches("(?s)^[A-Z][A-Z_]*: .+");
        assertThat(lastError).startsWith(expectedCode + ": ");
        assertThat(lastError.length()).isLessThanOrEqualTo(4000);
        assertThat(lastError)
                .doesNotContain(SECRET_MARKER)
                .doesNotContain("4111")
                .doesNotContain("IllegalStateException")
                .doesNotContain("ConnectionException");
    }

    private static boolean logHolds(ExecutorFixture fixture, String text) {
        for (ILoggingEvent event : fixture.logged(Level.ALL)) {
            if (event.getFormattedMessage().contains(text)) {
                return true;
            }
            List<IThrowableProxy> chain = new ArrayList<>();
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                chain.add(proxy);
            }
            for (IThrowableProxy proxy : chain) {
                if (proxy.getMessage() != null && proxy.getMessage().contains(text)) {
                    return true;
                }
            }
        }
        return false;
    }
}
