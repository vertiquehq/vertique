// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.startOf;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.DeadlineCall;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.HeldLock;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.InstanceRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Verifies that a command is claimed by exactly one executor and run once, and that a segment whose work has not
 * completed at its deadline is rolled back and its command reclaimed once.
 */
@ExtendWith(DatabaseExtension.class)
class CommandClaimIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_command_claim_it")
            .withMigration("classpath:db/migration/workflow");

    private static final int LEASE_SECONDS = 5;
    private static final long MARGIN_MS = 1000;
    private static final List<String> SEGMENT_ENTRIES =
            List.of("START", "COMMAND_RECEIVED", "COMMAND_COMPLETED", "SIDE_EFFECT_RECORDED");

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("concurrent claims of one head spend one attempt and run once")
    void concurrentClaimsOfOneHeadSpendOneAttemptAndRunOnce() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given one recorded start, and three repository clients, each over a pool of its own
            Node recording = fixture.node("recording").build();
            WorkflowInstanceId id = recording.record(startOf("race-head"));
            List<PgWorkflowCommandRepository> clients = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Pool pool = fixture.newPool(2);
                clients.add(new PgWorkflowCommandRepository(pool, new PgDbExceptionMapper()));
            }
            var heads = await(clients.get(0).peek(10));
            assertThat(heads).hasSize(1);

            // When the three clients claim the head concurrently
            List<Future<Boolean>> claims = new ArrayList<>();
            for (int i = 0; i < clients.size(); i++) {
                claims.add(clients.get(i).claim(heads.get(0), "client-" + i, 60).map(Optional::isPresent));
            }
            long successes = 0;
            for (Future<Boolean> claim : claims) {
                if (await(claim)) {
                    successes++;
                }
            }

            // Then exactly one claim succeeds and the command is claimed once
            assertThat(successes).isEqualTo(1);
            CommandRow claimed = fixture.commandOf(id);
            assertThat(claimed.state()).isEqualTo("CLAIMED");
            assertThat(claimed.attempts()).isEqualTo(1);
            assertThat(claimed.claimToken()).isNotNull();
            assertThat(claimed.claimedBy()).startsWith("client-");
        }

        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given 20 recorded starts and three engines, each with an executor that polls every 100 ms
            Node first = fixture.node("first").pollIntervalMs(100).build();
            Node second = fixture.node("second").pollIntervalMs(100).build();
            Node third = fixture.node("third").pollIntervalMs(100).build();
            List<WorkflowInstanceId> ids = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                ids.add(first.record(startOf("race-" + i)));
            }

            // When the three executors run
            first.startExecutor();
            second.startExecutor();
            third.startExecutor();
            for (WorkflowInstanceId id : ids) {
                first.awaitIdle(id);
            }

            // Then every start is complete, with one claim, one command entry and one initial-state entry
            for (WorkflowInstanceId id : ids) {
                CommandRow command = fixture.commandOf(id);
                assertThat(command.state()).isEqualTo("COMPLETE");
                assertThat(command.attempts()).isEqualTo(1);
                List<String> history = fixture.historyOf(id);
                assertThat(Collections.frequency(history, "COMMAND_COMPLETED")).isEqualTo(1);
                assertThat(Collections.frequency(history, "START")).isEqualTo(1);
                assertThat(fixture.intentsOf(id)).containsExactly("charge");
            }
            assertThat(fixture.initialStates).hasSize(20).doesNotHaveDuplicates();
        }
    }

    /** The situations in which a segment's work is unfinished, or finished, around its deadline. */
    enum Situation {
        /** The segment stalls idle after the first intent was requested. */
        EARLY_IDLE_STALL,
        /** The segment stalls idle after state, the first intent and history were written. */
        LATE_IDLE_STALL,
        /** The stalled segment resumes after the deadline and before the lease ends. */
        RESUMED_CONTINUATION,
        /** The segment finishes one second before the deadline. */
        HEALTHY,
        /** The work finishes before the deadline and its commit completes after it. */
        LATE_COMMIT,
        /** A statement is running in the database at the deadline. */
        STATEMENT_IN_FLIGHT,
        /** The execution lock is busy for two margins, so the body starts with half of its budget. */
        SHORTENED_BUDGET
    }

    @ParameterizedTest
    @EnumSource(Situation.class)
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a segment running at its deadline rolls back and is reclaimed once")
    void segmentRunningAtItsDeadlineRollsBackAndIsReclaimedOnce(Situation situation) throws Exception {
        boolean late = situation == Situation.LATE_IDLE_STALL;
        String definition = late ? ExecutorFixture.LATE : ExecutorFixture.SKELETON;
        Class<?> contract = late ? ExecutorFixture.LateContract.class : ExecutorFixture.SkeletonContract.class;
        int dispatches = late ? 2 : 1;
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given a lease of five seconds, so the deadline is one second before the lease ends, executor A on a
            // Vert.x instance of its own whose runner records its deadline calls, and a gate armed from the initial
            // state callback so that the stall is inside the segment after the execution lock is held
            fixture.onInitialState = ref -> fixture.gate.armOnCall(late ? 2 : 1);
            if (situation == Situation.STATEMENT_IN_FLIGHT) {
                fixture.gate.afterOpen(
                        client -> client.query("SELECT pg_sleep(2)").execute().mapEmpty());
            }
            ExecutorFixture.NodeSpec specOfA = fixture.node("A")
                    .ownVertx()
                    .leaseSeconds(LEASE_SECONDS)
                    .pollIntervalMs(100)
                    .recordDeadlines();
            if (situation == Situation.SHORTENED_BUDGET) {
                specOfA.holdFirstDeadlineCall();
            }
            Node a = specOfA.build();
            boolean slowCommit = situation == Situation.LATE_COMMIT;
            if (slowCommit) {
                createSlowCompletionTrigger(fixture);
            }
            HeldLock held = null;
            try {
                WorkflowInstanceId id = a.record(startOf(definition, contract, "deadline"));
                long clockOffset = fixture.databaseClockOffsetMillis();

                // When A claims the start and runs its segment
                a.startExecutor();
                awaitCondition("A's claim", IDLE, () -> "CLAIMED"
                        .equals(fixture.commandOf(id).state()));
                long claimSeenNanos = System.nanoTime();
                CommandRow claimed = fixture.commandOf(id);
                UUID token = claimed.claimToken();
                long leaseEndMs = claimed.leaseUntil().toInstant().toEpochMilli() - clockOffset;
                long deadlineMs = leaseEndMs - MARGIN_MS;

                // And executor B on another Vert.x instance starts after A's claim
                Node b = fixture.node("B")
                        .ownVertx()
                        .leaseSeconds(LEASE_SECONDS)
                        .pollIntervalMs(100)
                        .build();
                b.startExecutor();

                long holdMs = 0;
                if (situation == Situation.SHORTENED_BUDGET) {
                    // E3's first attempt is held; take the instance's execution lock, release the call, and keep the
                    // lock for two margins
                    await(Future.fromCompletionStage(a.runner.held()), IDLE);
                    assertThat(fixture.commandOf(id).claimToken()).isEqualTo(token);
                    held = fixture.holdExecutionLock(id);
                    long heldAt = System.nanoTime();
                    a.runner.releaseHeldCall();
                    ExecutorFixture.sleep(2 * MARGIN_MS);
                    held.release();
                    holdMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - heldAt);
                }

                switch (situation) {
                    case HEALTHY -> {
                        sleepUntil(deadlineMs - MARGIN_MS);
                        fixture.gate.open();
                        awaitCondition("A's commit", IDLE, () -> "COMPLETE"
                                .equals(fixture.commandOf(id).state()));
                    }
                    case LATE_COMMIT -> {
                        sleepUntil(deadlineMs - MARGIN_MS / 2);
                        fixture.gate.open();
                        awaitCondition("A's late commit", IDLE, () -> "COMPLETE"
                                .equals(fixture.commandOf(id).state()));
                        assertThat(System.currentTimeMillis())
                                .as("the commit completed after the deadline")
                                .isGreaterThan(deadlineMs);
                    }
                    case STATEMENT_IN_FLIGHT -> {
                        sleepUntil(deadlineMs - MARGIN_MS / 2);
                        fixture.gate.open();
                        sleepUntil(deadlineMs + MARGIN_MS / 2);
                        assertThat(fixture.holdersOfX(id))
                                .as("the statement running at the deadline keeps the lock")
                                .isNotEmpty();
                        awaitCondition(
                                "the lock to be released",
                                Duration.ofMillis(Math.max(1, deadlineMs + 2300 - System.currentTimeMillis())),
                                () -> fixture.holdersOfX(id).isEmpty());
                        // B may claim the command the moment the lock is free, so what A's rolled-back segment left
                        // is proven by the exactly-once assertions below, not by A's token here
                    }
                    default -> {
                        sleepUntil(deadlineMs + MARGIN_MS / 2);
                        assertThat(fixture.holdersOfX(id))
                                .as("the deadline released the execution lock")
                                .isEmpty();
                        assertNothingOfASegment(fixture, id, token);
                        if (situation == Situation.RESUMED_CONTINUATION) {
                            fixture.gate.open();
                            ExecutorFixture.sleep(300);
                            assertThat(fixture.intentsOf(id))
                                    .as("the resumed continuation wrote nothing")
                                    .isEmpty();
                            assertThat(fixture.historyOf(id)).doesNotContainAnyElementsOf(SEGMENT_ENTRIES);
                        }
                    }
                }

                // Then every call A made under its first claim ends no later than the deadline after that claim
                for (DeadlineCall call : a.runner.calls) {
                    long startedMs = TimeUnit.NANOSECONDS.toMillis(call.startedNanos - claimSeenNanos);
                    if (startedMs >= LEASE_SECONDS * 1000L - MARGIN_MS) {
                        continue; // a later claim of A, after the lease ended
                    }
                    assertThat(startedMs + call.duration.toMillis())
                            .isLessThanOrEqualTo(LEASE_SECONDS * 1000L - MARGIN_MS + 100);
                }

                if (situation == Situation.HEALTHY || situation == Situation.LATE_COMMIT) {
                    // Then the segment committed once under A's claim and no deadline outcome was recorded
                    sleepUntil(leaseEndMs + MARGIN_MS);
                    CommandRow done = fixture.commandOf(id);
                    assertThat(done.state()).isEqualTo("COMPLETE");
                    assertThat(done.attempts()).isEqualTo(1);
                    assertThat(done.settledAt()).isNotNull();
                    assertThat(a.runner.calls).isNotEmpty();
                    assertThat(a.runner.calls.get(a.runner.calls.size() - 1).succeeded)
                            .isTrue();
                    assertThat(a.runner.calls).noneMatch(DeadlineCall::hitDeadline);
                    assertRanOnce(fixture, id, dispatches);
                } else {
                    // Then A's segment hit its deadline and left the claim to lapse with its attempt spent
                    assertThat(a.runner.calls).anyMatch(DeadlineCall::hitDeadline);
                    if (situation == Situation.SHORTENED_BUDGET) {
                        assertShortenedBudget(a.runner.calls, holdMs);
                    }

                    // And B claims after the lease ended, with the second attempt, and the start ran once
                    sleepUntil(leaseEndMs + 300);
                    awaitCondition("the start to complete", IDLE, () -> "COMPLETE"
                            .equals(fixture.commandOf(id).state()));
                    assertThat(fixture.commandOf(id).attempts()).isEqualTo(2);
                    fixture.gate.open();
                    ExecutorFixture.sleep(500);
                    assertRanOnce(fixture, id, dispatches);
                }

                // And no deadline outcome was logged as a failure
                List<ILoggingEvent> failures = new ArrayList<>(fixture.logged(Level.ERROR));
                fixture.logged(Level.WARN).stream()
                        .filter(event -> mentionsDeadline(event))
                        .forEach(failures::add);
                assertThat(failures).isEmpty();
            } finally {
                fixture.gate.open();
                if (held != null) {
                    held.release();
                }
                if (a.runner != null) {
                    a.runner.releaseHeldCall();
                }
                if (slowCommit) {
                    dropSlowCompletionTrigger(fixture);
                }
            }
        }
    }

    // --- Assertions ---

    /** Nothing of the segment that A started is visible, and the command is still A's claim with one attempt. */
    private static void assertNothingOfASegment(ExecutorFixture fixture, WorkflowInstanceId id, UUID token) {
        InstanceRow instance = fixture.instanceOf(id);
        assertThat(instance.status()).isEqualTo("PENDING");
        assertThat(instance.stateJson()).isNull();
        assertThat(fixture.intentsOf(id)).isEmpty();
        assertThat(fixture.historyOf(id)).doesNotContainAnyElementsOf(SEGMENT_ENTRIES);
        CommandRow command = fixture.commandOf(id);
        assertThat(command.state()).isEqualTo("CLAIMED");
        assertThat(command.claimToken()).isEqualTo(token);
        assertThat(command.attempts()).isEqualTo(1);
    }

    /** The start ran once: one intent per dispatch, one initial-state entry and one command-completed entry. */
    private static void assertRanOnce(ExecutorFixture fixture, WorkflowInstanceId id, int dispatches) {
        assertThat(fixture.intentsOf(id)).hasSize(dispatches);
        List<String> history = fixture.historyOf(id);
        assertThat(Collections.frequency(history, "START")).isEqualTo(1);
        assertThat(Collections.frequency(history, "COMMAND_COMPLETED")).isEqualTo(1);
        assertThat(fixture.commandOf(id).settledAt()).isNotNull();
    }

    /**
     * The lock was busy for about two margins: after the held first call, each retry passes a shorter duration than the
     * one before, and the call that acquired the lock passes at most what remained of the budget after the hold.
     */
    private static void assertShortenedBudget(List<DeadlineCall> calls, long holdMs) {
        int firstDeadline = 0;
        while (firstDeadline < calls.size() && !calls.get(firstDeadline).hitDeadline()) {
            firstDeadline++;
        }
        assertThat(firstDeadline).as("a call that hit the deadline").isLessThan(calls.size());
        assertThat(firstDeadline).as("E3 retried the busy lock").isGreaterThanOrEqualTo(2);
        for (int i = 2; i <= firstDeadline; i++) {
            assertThat(calls.get(i).duration)
                    .as("retry %d passes a shorter duration than the retry before it", i)
                    .isLessThan(calls.get(i - 1).duration);
        }
        assertThat(calls.get(firstDeadline).duration.toMillis())
                .as("the body that acquired the lock has about half of the budget")
                .isLessThanOrEqualTo(LEASE_SECONDS * 1000L - MARGIN_MS - holdMs + 600);
    }

    private static boolean mentionsDeadline(ILoggingEvent event) {
        if (event.getFormattedMessage().contains("TransactionDeadlineExceeded")) {
            return true;
        }
        return event.getThrowableProxy() != null
                && event.getThrowableProxy().getClassName().contains("TransactionDeadlineExceeded");
    }

    // --- Timing ---

    private static void sleepUntil(long epochMillis) {
        ExecutorFixture.sleep(Math.max(0, epochMillis - System.currentTimeMillis()));
    }

    // --- The slow commit ---

    /** A deferred constraint trigger that sleeps two seconds at commit when a command row becomes complete. */
    private static void createSlowCompletionTrigger(ExecutorFixture fixture) {
        await(fixture.observer()
                .query("CREATE OR REPLACE FUNCTION executor_it_slow_completion() RETURNS trigger LANGUAGE plpgsql AS $$"
                        + " BEGIN IF NEW.state = 'COMPLETE' THEN PERFORM pg_sleep(2); END IF; RETURN NULL; END $$")
                .execute());
        await(fixture.observer()
                .query("CREATE CONSTRAINT TRIGGER executor_it_slow_completion AFTER UPDATE ON workflow_commands"
                        + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION executor_it_slow_completion()")
                .execute());
    }

    private static void dropSlowCompletionTrigger(ExecutorFixture fixture) {
        await(fixture.observer()
                .query("DROP TRIGGER IF EXISTS executor_it_slow_completion ON workflow_commands")
                .execute());
        await(fixture.observer()
                .query("DROP FUNCTION IF EXISTS executor_it_slow_completion()")
                .execute());
    }
}
