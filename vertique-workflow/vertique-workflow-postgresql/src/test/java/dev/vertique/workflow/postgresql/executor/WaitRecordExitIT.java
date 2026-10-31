// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.ops.CorrelatedSignal;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.BranchRow;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.ForkContract;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.Payment;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.WaitRecord;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Verifies that a wait record exists exactly while its wait is open: every committed exit from a wait deletes the
 * record in the transaction that leaves it, an instance that ends has none, and a {@code FAILED} instance keeps the
 * record of every wait that is still open. Every case ends by checking the invariant over the whole database.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class WaitRecordExitIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_wait_record_exit_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String PAID = "payment-captured";
    private static final String SHIPPED = "shipment-confirmed";
    private static final String PRINTED = "label-printed";

    @Test
    @DisplayName("a signal applied on the main path closes the record")
    void signalAppliedOnTheMainPathClosesTheRecord() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given an instance at a keyed wait with its record, followed by a timer, and an executor
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.start(node, KeyedWaitFixture.ORDER_HELD, "PAY-77", "acme");
            node.startExecutor();
            node.awaitIdle(id);
            assertThat(f.waitRecords(id)).hasSize(1);

            // When a caller records the instance-id signal for the wait and the instance is idle
            f.signalInstance(node, id, PAID, new Payment("txn-1"), "k-1");
            node.awaitIdle(id);

            // Then the instance has left the wait, and no record exists for it
            assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("hold");
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
            assertThat(f.waitRecords(id)).isEmpty();
            WaitRecordInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a signal that re-enters the same wait leaves one record with a new wait id")
    void signalThatReentersTheSameWaitLeavesOneRecordWithANewWaitId() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given a looping definition whose signal returns the instance to the same keyed wait, with the same key,
            // and the wait id of its first record
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.start(node, KeyedWaitFixture.LOOPING, "PAY-77", "acme");
            node.startExecutor();
            node.awaitIdle(id);
            WaitRecord first = only(f.waitRecords(id));

            // When a signal is delivered and applied
            f.signalInstance(node, id, PAID, new Payment("txn-1"), "k-1");
            node.awaitIdle(id);

            // Then exactly one record exists for the wait's address, with another wait id and the same key
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
            WaitRecord second = only(f.waitRecords(id));
            assertThat(second.stepId()).isEqualTo(first.stepId());
            assertThat(second.waitId()).isNotEqualTo(first.waitId());
            assertThat(second.correlationKey()).isEqualTo(first.correlationKey());
            WaitRecordInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a rolled-back signal segment keeps the record until its retry commits")
    void rolledBackSignalSegmentKeepsTheRecordUntilItsRetryCommits() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given an instance at a keyed wait, and a command repository decorator that fails the second completion,
            // which is the signal segment's, with a lost connection and delegates afterwards
            UnaryOperator<WorkflowCommandRepository<SqlClient>> failFirstCompletion = ExecutorFixture.intercepting(
                    "complete",
                    (call, member, arguments, real) -> call == 2
                            ? Future.failedFuture(
                                    FailingTransactions.connectionFailure().get())
                            : real.call());
            Node node = f.node("engine")
                    .commands(failFirstCompletion)
                    .transientBackoffMs(3_000, 3_000)
                    .build();
            WorkflowInstanceId id = f.start(node, KeyedWaitFixture.ORDER_HELD, "PAY-77", "acme");
            node.startExecutor();
            node.awaitIdle(id);
            WaitRecord before = only(f.waitRecords(id));

            // When the signal is recorded and the executor runs through the failure
            f.signalInstance(node, id, PAID, new Payment("txn-1"), "k-1");
            awaitCondition("the signal's first attempt to fail", IDLE, () -> f.fixture.commandsOf(id).stream()
                    .anyMatch(command -> command.kind().equals("SIGNAL") && command.transientCount() >= 1));

            // Then a second connection sees the record while the first attempt has failed
            assertThat(only(f.waitRecords(id)).waitId()).isEqualTo(before.waitId());
            assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("wait-payment");

            // And, once the retry commits, the record is gone and the instance has moved on
            awaitCondition("the retry to commit", IDLE, () -> "hold"
                    .equals(f.fixture.instanceOf(id).currentStepId()));
            node.awaitIdle(id);
            assertThat(f.waitRecords(id)).isEmpty();
            WaitRecordInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a signal applied to a branch closes only that branch's record")
    void signalAppliedToABranchClosesOnlyThatBranchsRecord() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given a fork with two branches at keyed waits, each with its record
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startFork(node, KeyedWaitFixture.FORK, "A-1", "B-1");
            node.startExecutor();
            node.awaitIdle(id);
            WaitRecord second = recordOf(f, id, "b2");
            assertThat(f.waitRecords(id)).hasSize(2);

            // When a signal is delivered to the first branch and applied
            f.deliverByKey(node, CorrelatedSignal.of(ForkContract.class, SHIPPED, "A-1", new Payment("txn-1"), "d-1"));
            node.awaitIdle(id);

            // Then the first branch's record is gone, and the second branch's record is unchanged
            assertThat(f.branchTokens(id))
                    .contains(new BranchRow("b1", "COMPLETED", "a-done"), new BranchRow("b2", "WAITING", "wait-b"));
            assertThat(f.waitRecords(id)).containsExactly(second);
            WaitRecordInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a timeout on the main path closes the record")
    void timeoutOnTheMainPathClosesTheRecord() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given an instance at a keyed wait with a timeout and its record
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.start(node, KeyedWaitFixture.ORDER_HELD_WITH_TIMEOUT, "PAY-77", "acme");
            node.startExecutor();
            node.awaitIdle(id);
            assertThat(f.waitRecords(id)).hasSize(1);

            // When the timeout is made due and runs
            f.fireTimeout(id);
            node.awaitIdle(id);

            // Then the instance is on its timeout path and no record exists for the left wait
            assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("hold");
            assertThat(f.waitRecords(id)).isEmpty();
            WaitRecordInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a timeout in a branch closes that branch's record")
    void timeoutOnABranchClosesThatBranchsRecord() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given a fork whose first branch waits at a keyed wait with a timeout and whose second branch waits at a
            // keyed wait without one
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startFork(node, KeyedWaitFixture.FORK_WITH_TIMEOUT, "A-1", "B-1");
            node.startExecutor();
            node.awaitIdle(id);
            WaitRecord second = recordOf(f, id, "b2");
            assertThat(f.waitRecords(id)).hasSize(2);

            // When the first branch's timeout is made due and runs
            f.fireTimeout(id, "b1");
            node.awaitIdle(id);

            // Then the first branch has left its wait, its record is gone, and the second's is unchanged
            assertThat(f.branchTokens(id))
                    .contains(
                            new BranchRow("b1", "COMPLETED", "a-timed-out"), new BranchRow("b2", "WAITING", "wait-b"));
            assertThat(f.waitRecords(id)).containsExactly(second);
            WaitRecordInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a branch superseded by a race loses its record")
    void supersededBranchLosesItsRecord() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given a first-success fork whose two branches wait at keyed waits, each with its record
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startFork(node, KeyedWaitFixture.RACE, "A-1", "B-1");
            node.startExecutor();
            node.awaitIdle(id);
            assertThat(f.waitRecords(id)).hasSize(2);

            // When the winner takes its signal and completes, and the join supersedes the loser
            f.deliverByKey(node, CorrelatedSignal.of(ForkContract.class, SHIPPED, "A-1", new Payment("txn-1"), "d-1"));
            node.awaitIdle(id);

            // Then the loser is superseded and its record is gone; the instance has no record left
            assertThat(f.branchTokens(id))
                    .contains(new BranchRow("b1", "COMPLETED", "a-done"), new BranchRow("b2", "SUPERSEDED", "wait-b"));
            assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("hold");
            assertThat(f.waitRecords(id)).isEmpty();
            WaitRecordInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a migration that reaches a wait under a different key replaces the record")
    void migrationReentryHoldsTheNewKeyAndTheOldKeyNoLongerMatches() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given version 1 keyed by refA at step w, version 2 keyed by refB at step w2, a handler that continues at
            // w2, and an instance of version 1 at its wait with refA A-1 and refB B-1
            Node node = f.migratingNode("engine").build();
            WorkflowInstanceId id = f.startFork(node, KeyedWaitFixture.MIGRATING, "A-1", "B-1");
            node.startExecutor();
            node.awaitIdle(id);
            WaitRecord old = only(f.waitRecords(id));
            assertThat(old.correlationKey()).isEqualTo("A-1");

            // When the instance is migrated to version 2 and the migration runs
            f.migrate(node, id, 2);
            node.awaitIdle(id);

            // Then exactly one record exists, with key B-1 and a new wait id, and no record has key A-1
            assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("w2");
            WaitRecord migrated = only(f.waitRecords(id));
            assertThat(migrated.correlationKey()).isEqualTo("B-1");
            assertThat(migrated.stepId()).isEqualTo("w2");
            assertThat(migrated.waitId()).isNotEqualTo(old.waitId());

            // And a delivery by key with A-1 leaves no command, and one with B-1 records a command
            assertThat(f.deliverByKeyFailure(
                            node, CorrelatedSignal.of(ForkContract.class, SHIPPED, "A-1", new Payment("txn-1"), "d-1")))
                    .isNotNull();
            assertThat(f.signalCommands(id)).isEmpty();
            CorrelatedSignalReceipt receipt = f.deliverByKey(
                    node, CorrelatedSignal.of(ForkContract.class, SHIPPED, "B-1", new Payment("txn-2"), "d-2"));
            assertThat(receipt.outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.RECORDED);
            assertThat(f.signalCommands(id)).hasSize(1);
            node.awaitIdle(id);
            WaitRecordInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a migration to a version that declares no key leaves no record")
    void migrationToAVersionWithoutAKeyLeavesNoRecord() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given version 1 with a keyed wait, version 2 with the same wait unkeyed, and an instance of version 1 at
            // the wait
            Node node = f.migratingNode("engine").build();
            WorkflowInstanceId id = f.startFork(node, KeyedWaitFixture.MIGRATING_UNKEYED, "A-1", "B-1");
            node.startExecutor();
            node.awaitIdle(id);
            assertThat(f.waitRecords(id)).hasSize(1);

            // When the instance is migrated to version 2
            f.migrate(node, id, 2);
            node.awaitIdle(id);

            // Then no record exists for the instance and the instance waits at the same step
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
            assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("w");
            assertThat(f.snapshot(id).getString("wait_type")).isEqualTo("SIGNAL");
            assertThat(f.waitRecords(id)).isEmpty();
            WaitRecordInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("cancellation deletes the instance's record and every branch record")
    void cancelClosesTheInstanceRecordAndEveryBranchRecord() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given one instance at a keyed main wait, and another in a fork with two keyed branch waits
            Node node = f.node("engine").build();
            WorkflowInstanceId main = f.startKeyed(node, "PAY-77");
            WorkflowInstanceId fork = f.startFork(node, KeyedWaitFixture.FORK, "A-1", "B-1");
            node.startExecutor();
            node.awaitIdle(main);
            node.awaitIdle(fork);
            assertThat(f.waitRecords(main)).hasSize(1);
            assertThat(f.waitRecords(fork)).hasSize(2);

            // When each instance is cancelled and the cancel runs
            f.cancel(node, main);
            f.cancel(node, fork);
            node.awaitIdle(main);
            node.awaitIdle(fork);

            // Then both are CANCELLED and no record exists for either
            assertThat(f.fixture.instanceOf(main).status()).isEqualTo("CANCELLED");
            assertThat(f.fixture.instanceOf(fork).status()).isEqualTo("CANCELLED");
            assertThat(f.waitRecords(main)).isEmpty();
            assertThat(f.waitRecords(fork)).isEmpty();
            WaitRecordInvariant.holds(f);
        }
    }

    /** The way an instance ends with a sibling branch still waiting. */
    enum EndSite {
        /** The failure route of the join is a {@code CompleteNode}. */
        COMPLETED(KeyedWaitFixture.FORK_FAILING_TO_COMPLETE),
        /**
         * A compensable dispatch, completed by a signal, ran before the fork, and the failure route of the join is a
         * {@code FailNode}.
         */
        COMPENSATED(KeyedWaitFixture.FORK_FAILING_TO_COMPENSATE);

        final String definitionId;

        EndSite(String definitionId) {
            this.definitionId = definitionId;
        }

        /** Whether a dispatch with a compensation waits for the signal that completes it before the fork. */
        boolean reservesFirst() {
            return this == COMPENSATED;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(EndSite.class)
    @DisplayName("an instance end with a sibling still open leaves no record")
    void instanceEndWithASiblingStillOpenLeavesNoRecord(EndSite site) {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given an ALL_REQUIRED fork whose branch a fails at a FailNode while branch b waits at a keyed wait, with
            // the failure route and the steps before the fork of the end site
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startFork(node, site.definitionId, "A-1", "B-1");

            // When the join decides and the instance is driven to its end
            node.startExecutor();
            node.awaitIdle(id);
            if (site.reservesFirst()) {
                f.deliverByKey(
                        node, CorrelatedSignal.of(ForkContract.class, SHIPPED, "A-1", new Payment("txn-1"), "d-1"));
                node.awaitIdle(id);
            }

            // Then the instance has the end site's status, branch b's token is still WAITING, and no record exists
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo(site.name());
            assertThat(f.branchTokens(id)).contains(new BranchRow("b", "WAITING", "wait-b"));
            assertThat(f.waitRecords(id)).isEmpty();
            WaitRecordInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a failed instance keeps its record, and a retry at the same wait keeps its wait id")
    void failedInstanceKeepsItsRecordAndRetryAtTheSameWaitKeepsItsWaitId() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given an instance at a keyed wait with a timeout whose mutator fails while a switch is on, and the
            // record's wait id
            Node node = f.node("engine").singleSegments().build();
            WorkflowInstanceId id = f.start(node, KeyedWaitFixture.BREAKABLE_TIMEOUT, "PAY-77", "acme");
            idle(node, id);
            WaitRecord before = only(f.waitRecords(id));

            // When the timeout fails the instance
            f.fireTimeout(id);
            idle(node, id);

            // Then, while FAILED, the record is present with the same wait id
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(only(f.waitRecords(id)).waitId()).isEqualTo(before.waitId());

            // And, when the switch is off and a retry alone runs, the instance waits again with the record, the same
            // wait id, and no second record
            f.timeoutPathBroken.set(false);
            f.retry(node, id);
            node.runOneSegment();
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
            assertThat(f.snapshot(id).getString("wait_type")).isEqualTo("SIGNAL");
            assertThat(only(f.waitRecords(id)).waitId()).isEqualTo(before.waitId());
            WaitRecordInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a fork failed at its join keeps its branch record through the retry")
    void forkFailedAtItsJoinKeepsItsBranchRecordThroughTheRetry() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given a fork whose branch a waits on a timer whose next step throws while a switch is on and whose
            // branch b waits at a keyed wait; the timer is due and has failed the instance, which holds its JOIN wait
            Node node = f.node("engine").singleSegments().build();
            WorkflowInstanceId id = f.startFork(node, KeyedWaitFixture.FORK_BREAKABLE_TIMER, "A-1", "B-1");
            idle(node, id);
            WaitRecord before = only(f.waitRecords(id));
            f.fireTimer(id);
            idle(node, id);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(f.snapshot(id).getString("wait_type")).isEqualTo("JOIN");

            // When the switch is turned off and a retry alone runs
            f.branchTimerPathBroken.set(false);
            f.retry(node, id);
            node.runOneSegment();

            // Then, after the retry, branch b's record is present with the same wait id, and no second record exists
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
            assertThat(only(f.waitRecords(id)).waitId()).isEqualTo(before.waitId());
            WaitRecordInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a retried start that reaches a keyed wait writes the record")
    void retriedStartThatReachesAKeyedWaitWritesTheRecord() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given a keyed definition whose initial-state callback throws while a switch is on, so the start fails
            // without state and leaves no record
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.start(node, KeyedWaitFixture.BREAKABLE_START, "PAY-77", "acme");
            node.startExecutor();
            awaitCondition(
                    "the start to fail",
                    IDLE,
                    () -> f.fixture.commandOf(id).state().equals("FAILED"));
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(f.waitRecords(id)).isEmpty();

            // When the switch is turned off and the instance is retried
            f.startBroken.set(false);
            f.retry(node, id);
            node.awaitIdle(id);

            // Then exactly one record exists for the wait, and the instance is WAITING there
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
            assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("wait-payment");
            assertThat(only(f.waitRecords(id)).stepId()).isEqualTo("wait-payment");
            WaitRecordInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a failure node keeps a sibling's record, and so does a retry that fails again")
    void failureNodeKeepsASiblingsRecordAndARetryThatFailsAgainChangesNothing() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given an ALL_REQUIRED fork with a failure route to a FailNode and no compensable step, whose branch a
            // fails while branch b waits at a keyed wait with its record
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startFork(node, KeyedWaitFixture.FORK_FAILING_TO_FAIL, "A-1", "B-1");
            node.startExecutor();
            node.awaitIdle(id);
            WaitRecord before = only(f.waitRecords(id));

            // When the join routes the instance to the FailNode
            // Then the instance is FAILED with a null wait and branch b's record is present with the same wait id
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(f.snapshot(id).getString("wait_type")).isNull();
            assertThat(only(f.waitRecords(id)).waitId()).isEqualTo(before.waitId());

            // And when a retry runs, so redrive runs the FailNode again, nothing has changed
            f.retry(node, id);
            node.awaitIdle(id);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(f.snapshot(id).getString("wait_type")).isNull();
            assertThat(only(f.waitRecords(id)).waitId()).isEqualTo(before.waitId());
            WaitRecordInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a failed join keeps a sibling's record through the retry it refuses")
    void failedJoinKeepsASiblingsRecordThroughTheRetryItRefuses() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given an ALL_REQUIRED fork with no failure route, whose branch a fails while branch b waits at a keyed
            // wait with its record; the instance is FAILED with a null wait
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startFork(node, KeyedWaitFixture.FORK_FAILING_NO_ROUTE, "A-1", "B-1");
            node.startExecutor();
            node.awaitIdle(id);
            WaitRecord before = only(f.waitRecords(id));
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(f.snapshot(id).getString("wait_type")).isNull();
            assertThat(f.storedErrorText(id)).contains("join_failed_no_failure_route");

            // When a retry runs, so redrive enters the JoinNode, which refuses
            f.retry(node, id);
            node.awaitIdle(id);

            // Then the RETRY command is FAILED, the instance is still FAILED, and branch b's record is present with
            // the same wait id
            assertThat(f.fixture.commandsOf(id))
                    .filteredOn(command -> command.kind().equals("RETRY"))
                    .extracting(CommandRow::state)
                    .containsExactly("FAILED");
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(only(f.waitRecords(id)).waitId()).isEqualTo(before.waitId());

            // And a cancel then leaves no record
            f.cancel(node, id);
            node.awaitIdle(id);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("CANCELLED");
            assertThat(f.waitRecords(id)).isEmpty();
            WaitRecordInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a sibling of a failed join keeps its record until its own signal")
    void siblingOfAFailedJoinKeepsItsRecordUntilItsOwnSignal() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given an ALL_REQUIRED fork whose failure route is a later wait, whose branch a fails while branch b waits
            // at a keyed wait; the instance has moved to the later wait
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startFork(node, KeyedWaitFixture.FORK_FAILING_TO_WAIT, "A-1", "B-1");
            node.startExecutor();
            node.awaitIdle(id);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
            assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("join-failed");

            // When a signal is delivered to branch b
            // Then before the delivery branch b's record is present
            assertThat(only(f.waitRecords(id)).branchId()).isEqualTo("b");
            f.deliverByKey(node, CorrelatedSignal.of(ForkContract.class, PRINTED, "B-1", new Payment("txn-1"), "d-1"));
            node.awaitIdle(id);

            // And after it the record is gone, branch b has ended and history holds BRANCH_LATE_RESULT_IGNORED
            assertThat(f.waitRecords(id)).isEmpty();
            assertThat(f.branchTokens(id))
                    .filteredOn(token -> token.branchId().equals("b"))
                    .extracting(BranchRow::status)
                    .containsExactly("COMPLETED");
            assertThat(f.fixture.historyOf(id)).contains("BRANCH_LATE_RESULT_IGNORED");
            WaitRecordInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a rejected migration keeps the record")
    void rejectedMigrationKeepsTheRecord() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given an instance at a keyed wait, a target version whose re-drive overruns the step cap, and the
            // record's wait id
            Node node = f.migratingNode("engine").maxStepsPerSegment(50).build();
            WorkflowInstanceId id = f.startFork(node, KeyedWaitFixture.MIGRATING_OVERRUN, "A-1", "B-1");
            node.startExecutor();
            node.awaitIdle(id);
            WaitRecord before = only(f.waitRecords(id));
            JsonObject snapshot = f.snapshot(id);

            // When the migration runs and is rejected
            f.migrate(node, id, 2);
            node.awaitIdle(id);

            // Then the instance is unchanged, and the record is present with the same wait id
            assertThat(f.fixture.commandsOf(id))
                    .filteredOn(command -> command.kind().equals("MIGRATION"))
                    .singleElement()
                    .satisfies(command -> {
                        assertThat(command.state()).isEqualTo("REJECTED");
                        assertThat(command.lastError()).startsWith(ReasonCodes.STEP_LIMIT);
                    });
            assertThat(f.snapshot(id)).isEqualTo(snapshot);
            assertThat(only(f.waitRecords(id)).waitId()).isEqualTo(before.waitId());
            WaitRecordInvariant.holds(f);
        }
    }

    /** Runs the executors until the instance is idle, then stops them. */
    private static void idle(Node node, WorkflowInstanceId id) {
        node.startExecutor();
        try {
            node.awaitIdle(id);
        } finally {
            node.stopExecutor();
        }
    }

    private static WaitRecord only(List<WaitRecord> records) {
        assertThat(records).hasSize(1);
        return records.getFirst();
    }

    private static WaitRecord recordOf(KeyedWaitFixture f, WorkflowInstanceId id, String branchId) {
        return only(f.waitRecords(id).stream()
                .filter(record -> record.branchId().equals(branchId))
                .toList());
    }
}
