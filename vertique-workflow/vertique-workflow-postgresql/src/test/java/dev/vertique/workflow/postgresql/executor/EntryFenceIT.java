// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.CorrelatedSignal;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.BranchRow;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.EndingContract;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.ForkContract;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.OrderContract;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.Payment;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.SignalRow;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.TimerRecord;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.WaitRecord;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Verifies that a correlated command applies only to the wait entry it matched. The target of a command recorded for a
 * wait carries the wait's {@code waitId}; the command applies only while that entry is open, parks while the entry is
 * held, and settles stale, with a history entry and a log entry, once the entry has closed, whatever the instance's
 * status. A command that applies is never settled by the close its own application makes, a failed instance never
 * drains a correlated command, and a migration that closes an entry cancels the entry's timeout. Instance-id delivery
 * keeps its behaviour. Every case ends by checking the invariant of the held commands.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class EntryFenceIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_entry_fence_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String PAID = "payment-captured";
    private static final String SHIPPED = "shipment-confirmed";
    private static final String PRINTED = "label-printed";
    private static final String COMMAND_STALE = "COMMAND_STALE";
    private static final String SIGNAL_RECEIVED = "SIGNAL_RECEIVED";

    @Test
    @DisplayName("a command recorded past the fire time settles stale when the timeout enters a wait on another key")
    void commandRecordedPastTheFireTimeSettlesStaleWhenTheTimeoutEntersAWaitOnAnotherKey() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given an instance at a keyed wait with a timeout whose path returns to the step under another key,
            // executors stopped, the timeout's fire time passed, and a correlated delivery recorded after it
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.start(node, KeyedWaitFixture.ORDER_TIMEOUT_REKEY, "PAY-77", "acme");
            f.idle(node, id);
            WaitRecord first = only(f.waitRecords(id));
            f.fireTimeout(id);
            f.deliverByKey(node, paid("PAY-77", "d-1"));

            // When the executors start and the instance is idle
            f.idle(node, id);

            // Then the command's target carries the entry it matched, the timeout ran first, and the command is stale,
            // logged and recorded in the history once
            SignalRow command = only(f.signalRows(id));
            assertThat(command.waitId()).isEqualTo(first.waitId().toString());
            assertStaleOnce(f, id, command);
            assertStaleLogged(f, id, command, PAID);

            // And the instance waits at the step under the timeout's new key, unchanged by the signal
            WaitRecord second = only(f.waitRecords(id));
            assertThat(second.waitId()).isNotEqualTo(first.waitId());
            assertThat(second.correlationKey()).isEqualTo("PAY-77-2");
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
            assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("wait-payment");
            assertUnchangedBySignal(f, id);
            assertThat(f.openSignals(id)).isZero();
            assertNoKeyLeaked(f, id, "PAY-77");

            // And when the same delivery is made again, the replay answers duplicate and applies nothing
            assertReplayAnswersDuplicate(f, node, id, paid("PAY-77", "d-1"));
            assertThat(only(f.waitRecords(id)).waitId()).isEqualTo(second.waitId());
            HeldCommandInvariant.holds(f);
        }
    }

    @Test
    @DisplayName(
            "a command recorded past the fire time settles stale when the same step is entered again on the same key")
    void commandRecordedPastTheFireTimeSettlesStaleWhenTheSameStepIsEnteredAgainOnTheSameKey() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given an instance at a keyed wait with a timeout whose path returns to the same step under the same key,
            // executors stopped, the fire time passed, and a correlated delivery recorded after it
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.start(node, KeyedWaitFixture.ORDER_TIMEOUT_REWAIT, "PAY-77", "acme");
            f.idle(node, id);
            WaitRecord first = only(f.waitRecords(id));
            f.fireTimeout(id);
            f.deliverByKey(node, paid("PAY-77", "d-1"));

            // When the executors start and the instance is idle
            f.idle(node, id);

            // Then the command's target names the old entry
            SignalRow command = only(f.signalRows(id));
            assertThat(command.waitId()).isEqualTo(first.waitId().toString());

            // And the new entry equals the old one in step and key but has another wait id
            WaitRecord second = only(f.waitRecords(id));
            assertThat(second.stepId()).isEqualTo(first.stepId());
            assertThat(second.correlationKey()).isEqualTo(first.correlationKey());
            assertThat(second.waitId()).isNotEqualTo(first.waitId());

            // And the command is stale once, and the instance is unchanged by it
            assertStaleOnce(f, id, command);
            assertStaleLogged(f, id, command, PAID);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
            assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("wait-payment");
            assertUnchangedBySignal(f, id);
            assertThat(only(f.waitRecords(id)).waitId()).isEqualTo(second.waitId());

            // And when the same delivery is made again, the replay answers duplicate although the new entry matches
            // its key, and applies nothing
            assertReplayAnswersDuplicate(f, node, id, paid("PAY-77", "d-1"));
            assertThat(only(f.waitRecords(id)).waitId()).isEqualTo(second.waitId());
            HeldCommandInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a migration that re-enters the wait makes a queued command stale, and its replay answers duplicate")
    void migrationReentryMakesAQueuedCommandStaleAndItsReplayAnswersDuplicate() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given an instance of version 1 at a keyed wait, a migration to version 2 recorded and not yet run, and a
            // correlated delivery on the key recorded behind it, executors stopped
            Node node = f.migratingNode("engine").build();
            WorkflowInstanceId id = f.startFork(node, KeyedWaitFixture.MIGRATING, "A-1", "B-1");
            f.idle(node, id);
            WaitRecord first = only(f.waitRecords(id));
            f.migrate(node, id, 2);
            CorrelatedSignalReceipt recorded = f.deliverByKey(node, shipped("A-1", "d-1"));
            assertThat(recorded.outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.RECORDED);

            // When the executors run the migration and then the signal; and then the same delivery is made again
            f.idle(node, id);
            CorrelatedSignalReceipt replay = f.deliverByKey(node, shipped("A-1", "d-1"));

            // Then the command's target names the old entry
            SignalRow command = only(f.signalRows(id));
            assertThat(command.waitId()).isEqualTo(first.waitId().toString());

            // And the migration re-entered the wait under a new wait id
            WaitRecord second = only(f.waitRecords(id));
            assertThat(second.waitId()).isNotEqualTo(first.waitId());
            assertThat(second.stepId()).isEqualTo("w2");

            // And the command is stale, logged and the instance is unchanged by it
            assertStaleOnce(f, id, command);
            assertStaleLogged(f, id, command, SHIPPED);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
            assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("w2");
            assertUnchangedBySignal(f, id);

            // And the replay answers duplicate, adds no command and applies nothing
            assertThat(replay.outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.DUPLICATE);
            f.idle(node, id);
            assertThat(f.signalRows(id)).hasSize(1);
            assertUnchangedBySignal(f, id);
            assertThat(f.openSignals(id)).isZero();
            HeldCommandInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a command parked behind a failed timeout stays blocked on retry and settles stale when the timeout"
            + " re-enters the wait")
    void commandParkedBehindAFailedTimeoutStaysBlockedOnRetryAndSettlesStaleWhenTheTimeoutReentersTheWait() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given an instance at a keyed wait whose timeout path throws while a switch is on and otherwise returns to
            // the wait; the timeout has failed the instance, which is FAILED and holds its entry
            Node node = f.node("engine").singleSegments().build();
            WorkflowInstanceId id = f.start(node, KeyedWaitFixture.BREAKABLE_TIMEOUT_REWAIT, "PAY-77", "acme");
            f.idle(node, id);
            WaitRecord first = only(f.waitRecords(id));
            f.fireTimeout(id);
            f.idle(node, id);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(only(f.waitRecords(id)).waitId()).isEqualTo(first.waitId());

            // And a correlated delivery on the key, necessarily past the fire time, parks on the entry
            f.deliverByKey(node, paid("PAY-77", "d-1"));
            f.idle(node, id);
            SignalRow command = only(f.signalRows(id));
            assertThat(command.state()).isEqualTo("PARKED");
            assertThat(command.waitId()).isEqualTo(first.waitId().toString());
            HeldCommandInvariant.holds(f);

            // When the switch is turned off and the retry alone runs
            f.timeoutPathBroken.set(false);
            f.retry(node, id);
            node.runOneSegment();

            // Then the instance waits at the same entry and the command stays parked, because the timeout offered
            // again still blocks it
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
            assertThat(f.snapshot(id).getString("wait_type")).isEqualTo("SIGNAL");
            assertThat(only(f.waitRecords(id)).waitId()).isEqualTo(first.waitId());
            assertThat(only(f.signalRows(id)).state()).isEqualTo("PARKED");
            HeldCommandInvariant.holds(f);

            // And when the timeout runs, alone, it leaves the entry and enters the wait again: the command is settled
            // stale by that segment, once, and the new entry is unchanged by the signal
            node.runOneSegment();
            SignalRow settled = only(f.signalRows(id));
            assertStaleOnce(f, id, settled);
            WaitRecord second = only(f.waitRecords(id));
            assertThat(second.waitId()).isNotEqualTo(first.waitId());
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
            assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("wait-payment");
            assertUnchangedBySignal(f, id);
            assertThat(f.openSignals(id)).isZero();
            HeldCommandInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a command parked on a fork failed at its join applies when the same entry resumes")
    void commandParkedOnAForkFailedAtItsJoinAppliesWhenTheSameEntryResumes() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given a fork whose branch a waits on a timer whose next step throws while a switch is on and whose
            // branch b waits at a keyed wait with no timeout; the timer has failed the instance, which holds its JOIN
            // wait
            Node node = f.node("engine").singleSegments().build();
            WorkflowInstanceId id = f.startFork(node, KeyedWaitFixture.FORK_BREAKABLE_TIMER, "A-1", "B-1");
            f.idle(node, id);
            WaitRecord entry = only(f.waitRecords(id));
            f.fireTimer(id);
            f.idle(node, id);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(f.snapshot(id).getString("wait_type")).isEqualTo("JOIN");

            // And a correlated delivery on branch b's key parks on its entry
            f.deliverByKey(node, printed("B-1", "d-1"));
            f.idle(node, id);
            SignalRow command = only(f.signalRows(id));
            assertThat(command.state()).isEqualTo("PARKED");
            assertThat(command.waitId()).isEqualTo(entry.waitId().toString());
            assertThat(only(f.waitRecords(id)).waitId()).isEqualTo(entry.waitId());
            HeldCommandInvariant.holds(f);

            // When the switch is turned off and the retry alone runs
            f.branchTimerPathBroken.set(false);
            f.retry(node, id);
            node.runOneSegment();

            // Then, in the retry's segment, the command was applied at the entry it matched, and branch b is past the
            // wait
            SignalRow applied = only(f.signalRows(id));
            assertThat(applied.waitId()).isEqualTo(entry.waitId().toString());
            assertThat(applied.state()).isEqualTo("COMPLETE");
            assertThat(f.commandsWithHistory(id, "SIGNAL_APPLIED")).containsExactly(applied.id());
            assertThat(f.commandsWithHistory(id, COMMAND_STALE)).isEmpty();
            assertThat(f.branchTokens(id)).contains(new BranchRow("b", "COMPLETED", "b-done"));
            assertThat(f.openSignals(id)).isZero();
            HeldCommandInvariant.holds(f);
        }
    }

    @Test
    @DisplayName(
            "a command parked behind a dead-lettered timeout settles stale when the retried timeout leaves the wait")
    void commandParkedBehindADeadLetteredTimeoutSettlesStaleWhenTheRetriedTimeoutLeavesTheWait() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given two instances waiting at keyed waits whose timeouts are dead-lettered, one whose timeout returns to
            // the wait and one whose timeout ends the instance, and a correlated command parked behind each timeout
            Node node = f.deadLettering("engine").build();
            WorkflowInstanceId returning = f.start(node, KeyedWaitFixture.ORDER_TIMEOUT_REWAIT, "PAY-1", "acme");
            WorkflowInstanceId ending = f.start(node, KeyedWaitFixture.ORDER_WITH_TIMEOUT, "PAY-2", "acme");
            f.idle(node, returning, ending);
            WaitRecord returningEntry = only(f.waitRecords(returning));
            WaitRecord endingEntry = only(f.waitRecords(ending));
            f.deadLetterTimeouts(node, null, returning, ending);
            f.deliverByKey(node, paid("PAY-1", "d-1"));
            f.deliverByKey(node, paid("PAY-2", "d-2"));
            f.idle(node, returning, ending);
            assertThat(only(f.signalRows(returning)).state()).isEqualTo("PARKED");
            assertThat(only(f.signalRows(ending)).state()).isEqualTo("PARKED");
            assertThat(only(f.signalRows(returning)).waitId())
                    .isEqualTo(returningEntry.waitId().toString());
            assertThat(only(f.signalRows(ending)).waitId())
                    .isEqualTo(endingEntry.waitId().toString());
            HeldCommandInvariant.holds(f);

            // When a retry is recorded for each, which offers the timeout again, and the instances run to idle
            f.retry(node, returning);
            f.retry(node, ending);
            f.idle(node, returning, ending);

            // Then the first command is stale, and the instance waits under a new entry unchanged by the signal
            assertStaleOnce(f, returning, only(f.signalRows(returning)));
            assertThat(f.fixture.instanceOf(returning).status()).isEqualTo("WAITING");
            assertThat(only(f.waitRecords(returning)).waitId()).isNotEqualTo(returningEntry.waitId());
            assertUnchangedBySignal(f, returning);
            assertThat(f.openSignals(returning)).isZero();

            // And the second is stale, not rejected, because the entry closed before the instance ended
            SignalRow endedWith = only(f.signalRows(ending));
            assertStaleOnce(f, ending, endedWith);
            assertThat(f.fixture.instanceOf(ending).status()).isEqualTo("COMPLETED");
            assertThat(f.commandsWithHistory(ending, "COMMAND_REJECTED")).isEmpty();
            assertUnchangedBySignal(f, ending);
            assertThat(f.openSignals(ending)).isZero();
            HeldCommandInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a cancelled instance cancels its parked correlated command through the ended-instance outcomes")
    void cancelledInstanceCancelsItsParkedCorrelatedCommandThroughTheEndedInstanceOutcomes() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given an instance FAILED by its timeout path at a keyed wait, with a correlated command parked on its
            // entry
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.start(node, KeyedWaitFixture.BREAKABLE_TIMEOUT, "PAY-77", "acme");
            f.idle(node, id);
            f.fireTimeout(id);
            f.idle(node, id);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            f.deliverByKey(node, paid("PAY-77", "d-1"));
            f.idle(node, id);
            assertThat(only(f.signalRows(id)).state()).isEqualTo("PARKED");
            HeldCommandInvariant.holds(f);

            // When the instance is cancelled
            f.cancel(node, id);
            f.idle(node, id);

            // Then the command is cancelled, as an instance-addressed signal is, not stale, and settled once
            SignalRow command = only(f.signalRows(id));
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("CANCELLED");
            assertThat(command.state()).isEqualTo("CANCELLED");
            assertThat(command.row().settledAt()).isNotNull();
            assertThat(f.commandsWithHistory(id, COMMAND_STALE)).isEmpty();
            assertThat(f.commandsWithHistory(id, "COMMAND_REJECTED")).isEmpty();
            assertThat(f.openSignals(id)).isZero();
            assertThat(f.waitRecords(id)).isEmpty();
            HeldCommandInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a migration of a live waiting instance settles its parked correlated command stale")
    void migrationOfALiveWaitingInstanceSettlesItsParkedCorrelatedCommandStale() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given an instance of version 1 that is WAITING at a keyed wait with its timeout dead-lettered, a
            // correlated delivery recorded after the fire time that parks on the entry, and a migration to version 2,
            // which keeps the wait, recorded behind it
            Node node =
                    f.deadLettering(f.migratingNode("engine").singleSegments()).build();
            WorkflowInstanceId id = f.startFork(node, KeyedWaitFixture.MIGRATING_TIMED, "A-1", "B-1");
            f.idle(node, id);
            WaitRecord first = only(f.waitRecords(id));
            f.deadLetterTimeouts(node, null, id);
            f.deliverByKey(node, shipped("A-1", "d-1"));
            f.idle(node, id);
            SignalRow parked = only(f.signalRows(id));
            assertThat(parked.state()).isEqualTo("PARKED");
            assertThat(parked.waitId()).isEqualTo(first.waitId().toString());
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
            HeldCommandInvariant.holds(f);
            f.migrate(node, id, 2);

            // When the executor runs the migration, alone
            node.runOneSegment();

            // Then the instance is live and waits under the new entry
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
            WaitRecord second = only(f.waitRecords(id));
            assertThat(second.waitId()).isNotEqualTo(first.waitId());
            assertThat(second.stepId()).isEqualTo("w");

            // And the command is stale, not rejected, not cancelled and not parked, settled by that segment once, and
            // the instance is unchanged by it
            SignalRow settled = only(f.signalRows(id));
            assertStaleOnce(f, id, settled);
            assertThat(f.commandsWithHistory(id, "COMMAND_REJECTED")).isEmpty();
            assertUnchangedBySignal(f, id);
            assertThat(f.openSignals(id)).isZero();
            HeldCommandInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a parked correlated command that applies is not settled stale by its own close, and the others on the"
            + " entry are")
    void parkedCorrelatedCommandThatAppliesIsNotSettledStaleByItsOwnCloseWhileTheOthersOnTheEntryAre() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given a fork whose branch a is failed at a timer and whose branch b waits at a keyed wait that its own
            // signal enters again under a new entry; two correlated deliveries on b's key, P then Q, park on the entry
            Node node = f.node("engine").singleSegments().build();
            WorkflowInstanceId id = f.startFork(node, KeyedWaitFixture.FORK_BREAKABLE_TIMER_LOOPING, "A-1", "B-1");
            f.idle(node, id);
            WaitRecord entry = only(f.waitRecords(id));
            f.fireTimer(id);
            f.idle(node, id);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            f.deliverByKey(node, printed("B-1", "d-p"));
            f.deliverByKey(node, printed("B-1", "d-q"));
            f.idle(node, id);
            List<SignalRow> parked = f.signalRows(id);
            assertThat(parked).extracting(SignalRow::state).containsExactly("PARKED", "PARKED");
            assertThat(parked)
                    .extracting(SignalRow::waitId)
                    .containsOnly(entry.waitId().toString());
            HeldCommandInvariant.holds(f);

            // When the switch is turned off and the retry resumes the instance; the drain applies P
            f.branchTimerPathBroken.set(false);
            f.retry(node, id);
            node.runOneSegment();

            // Then P is complete, applied from the parked state, and not settled stale by the close of its own
            // application (a branch signal's application is recorded as applied)
            SignalRow p = f.signalRows(id).get(0);
            SignalRow q = f.signalRows(id).get(1);
            assertThat(p.state()).isEqualTo("COMPLETE");
            assertThat(f.commandsWithHistory(id, "SIGNAL_APPLIED")).containsExactly(p.id());
            assertThat(f.commandsWithHistory(id, COMMAND_STALE)).doesNotContain(p.id());

            // And Q is stale, once, and the new entry of the wait, whose key equals Q's, is unchanged by Q
            assertStaleOnce(f, id, q);
            WaitRecord reentered = only(f.waitRecords(id));
            assertThat(reentered.waitId()).isNotEqualTo(entry.waitId());
            assertThat(reentered.correlationKey()).isEqualTo("B-1");
            assertThat(f.openSignals(id)).isZero();
            HeldCommandInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a correlated command for an entry that closed before the instance failed settles stale instead of"
            + " parking")
    void correlatedCommandForAnEntryThatClosedBeforeTheInstanceFailedSettlesStaleInsteadOfParking() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given two instances at a keyed wait whose signal leads to a failure node with no compensable step; for
            // the first, an instance-id signal S recorded first and a correlated delivery X behind it; for the second,
            // an instance-id signal S2 first and another instance-id signal Y behind it; executors stopped
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.start(node, KeyedWaitFixture.ORDER_FAILS_ON_SIGNAL, "PAY-77", "acme");
            WorkflowInstanceId other = f.start(node, KeyedWaitFixture.ORDER_FAILS_ON_SIGNAL, "PAY-88", "acme");
            f.idle(node, id, other);
            WaitRecord entry = only(f.waitRecords(id));
            f.signalInstance(node, id, PAID, new Payment("txn-1"), "s-1");
            f.deliverByKey(node, paid("PAY-77", "d-1"));
            f.signalInstance(node, other, PAID, new Payment("txn-1"), "s-2");
            f.signalInstance(node, other, PAID, new Payment("txn-2"), "y-1");

            // When the executors run S and then X
            f.idle(node, id, other);

            // Then S applied and closed the entry, and the instance is FAILED with no open record
            List<SignalRow> signals = f.signalRows(id);
            assertThat(signals.get(0).state()).isEqualTo("COMPLETE");
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(f.waitRecords(id)).isEmpty();

            // And X, whose target names the closed entry, is stale, logged and recorded once, not parked
            SignalRow x = signals.get(1);
            assertThat(x.waitId()).isEqualTo(entry.waitId().toString());
            assertStaleOnce(f, id, x);
            assertStaleLogged(f, id, x, PAID);
            assertThat(f.commandsWithHistory(id, SIGNAL_RECEIVED))
                    .containsExactly(signals.get(0).id());
            assertThat(f.openSignals(id)).isZero();

            // And an instance-id signal in the same place is parked, as the FAILED row of the outcome table has it
            assertThat(f.fixture.instanceOf(other).status()).isEqualTo("FAILED");
            assertThat(f.signalRows(other)).extracting(SignalRow::state).containsExactly("COMPLETE", "PARKED");
            HeldCommandInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a branch correlated command for an entry that closed before the instance failed settles stale instead"
            + " of parking")
    void branchCorrelatedCommandForAnEntryThatClosedBeforeTheInstanceFailedSettlesStaleInsteadOfParking() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given a fork whose branch a waits on a timer that fails the instance and whose branch b waits at a keyed
            // wait that its own signal enters again; two correlated deliveries on b's key, P then Q, both naming the
            // first entry, on a node that runs one segment at a time
            Node node = f.node("engine").singleSegments().build();
            WorkflowInstanceId id = f.startFork(node, KeyedWaitFixture.FORK_BREAKABLE_TIMER_LOOPING, "A-1", "B-1");
            f.idle(node, id);
            WaitRecord entry = only(f.waitRecords(id));
            f.deliverByKey(node, printed("B-1", "d-p"));
            f.deliverByKey(node, printed("B-1", "d-q"));

            // When one segment applies P, so branch b leaves the first entry; the timer of branch a is made due, so it
            // is ahead of Q in the queue, and fails the instance; and then Q runs
            node.runOneSegment();
            SignalRow p = f.signalRows(id).get(0);
            assertThat(p.state()).isEqualTo("COMPLETE");
            assertThat(only(f.waitRecords(id)).waitId()).isNotEqualTo(entry.waitId());
            f.fireTimer(id);
            node.runOneSegment();
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            node.runOneSegment();

            // Then Q is stale, once, and not parked; the branch and the instance are unchanged by it
            SignalRow q = f.signalRows(id).get(1);
            assertThat(q.waitId()).isEqualTo(entry.waitId().toString());
            assertStaleOnce(f, id, q);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(f.commandsWithHistory(id, "COMMAND_COMPLETED"))
                    .contains(p.id())
                    .doesNotContain(q.id());
            assertThat(f.openSignals(id)).isZero();
            HeldCommandInvariant.holds(f);
        }
    }

    @Test
    @DisplayName(
            "a sibling's parked command stays parked through a failed join's refused retry and is cancelled with the"
                    + " instance")
    void siblingsParkedCommandOutlivesARefusedRetryOfAFailedJoinAndIsCancelledWithTheInstance() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given an ALL_REQUIRED fork with no failure route whose branch a fails while branch b waits at a keyed
            // wait; the instance is FAILED with a null main wait; a correlated delivery on b's key parks on the entry
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startFork(node, KeyedWaitFixture.FORK_FAILING_NO_ROUTE, "A-1", "B-1");
            f.idle(node, id);
            WaitRecord entry = only(f.waitRecords(id));
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(f.snapshot(id).getString("wait_type")).isNull();
            f.deliverByKey(node, printed("B-1", "d-1"));
            f.idle(node, id);
            SignalRow command = only(f.signalRows(id));
            assertThat(command.waitId()).isEqualTo(entry.waitId().toString());
            assertThat(command.state()).isEqualTo("PARKED");

            // When a retry runs, so redrive enters the JoinNode, which refuses
            f.retry(node, id);
            f.idle(node, id);

            // Then the RETRY command is FAILED, the instance is FAILED and the command is still parked on the entry
            assertThat(f.fixture.commandsOf(id))
                    .filteredOn(c -> c.kind().equals("RETRY"))
                    .extracting(CommandRow::state)
                    .containsExactly("FAILED");
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(only(f.signalRows(id)).state()).isEqualTo("PARKED");
            assertThat(only(f.waitRecords(id)).waitId()).isEqualTo(entry.waitId());
            HeldCommandInvariant.holds(f);

            // And when the instance is cancelled, the command is cancelled and no record remains
            f.cancel(node, id);
            f.idle(node, id);
            assertThat(only(f.signalRows(id)).state()).isEqualTo("CANCELLED");
            assertThat(f.waitRecords(id)).isEmpty();
            assertThat(f.openSignals(id)).isZero();
            HeldCommandInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a race that supersedes a branch settles its parked command stale")
    void raceThatSupersedesABranchSettlesItsParkedCommandStale() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given a FIRST_SUCCESS fork whose branch b2 waits at a keyed wait with its timeout dead-lettered, and a
            // correlated delivery on b2's key that parks on the entry
            Node node = f.deadLettering(f.node("engine").singleSegments()).build();
            WorkflowInstanceId id = f.startFork(node, KeyedWaitFixture.RACE_TIMED, "A-1", "B-1");
            f.idle(node, id);
            WaitRecord loser = f.waitRecords(id).stream()
                    .filter(record -> record.branchId().equals("b2"))
                    .findFirst()
                    .orElseThrow();
            f.deadLetterTimeouts(node, "b2", id);
            f.deliverByKey(node, printed("B-1", "d-1"));
            f.idle(node, id);
            SignalRow parked = only(f.signalRows(id));
            assertThat(parked.state()).isEqualTo("PARKED");
            assertThat(parked.waitId()).isEqualTo(loser.waitId().toString());
            HeldCommandInvariant.holds(f);

            // When a signal completes branch b1, and the join supersedes branch b2
            f.deliverByKey(node, shipped("A-1", "d-2"));
            node.runOneSegment();

            // Then branch b2 is superseded, and the command is stale, once, settled by the winner's segment
            assertThat(f.branchTokens(id)).contains(new BranchRow("b2", "SUPERSEDED", "wait-b"));
            assertStaleOnce(f, id, f.signalRows(id).get(0));
            assertThat(f.signalRows(id).get(0).waitId())
                    .isEqualTo(loser.waitId().toString());
            assertThat(f.openSignals(id)).isZero();
            HeldCommandInvariant.holds(f);
        }
    }

    /** The way an instance ends with a sibling branch still waiting. */
    enum EndCase {
        /** The failure route of the join is a {@code CompleteNode}. */
        COMPLETED(KeyedWaitFixture.FORK_ENDING_COMPLETED, false),
        /** A compensable dispatch ran before the fork and the failure route of the join is a {@code FailNode}. */
        COMPENSATED(KeyedWaitFixture.FORK_ENDING_COMPENSATED, true);

        final String definitionId;
        final boolean reservesFirst;

        EndCase(String definitionId, boolean reservesFirst) {
            this.definitionId = definitionId;
            this.reservesFirst = reservesFirst;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(EndCase.class)
    @DisplayName("an end with a sibling's command parked rejects it through the ended-instance outcomes")
    void endWithASiblingsCommandParkedRejectsItThroughTheEndedInstanceOutcomes(EndCase end) {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given an ALL_REQUIRED fork whose branch a waits for a signal and then fails, and whose branch b waits at
            // a
            // keyed wait with a dead-lettered timeout and a correlated command parked on the entry
            Node node = f.deadLettering("engine").build();
            WorkflowInstanceId id = f.startFork(node, end.definitionId, "A-1", "B-1");
            f.idle(node, id);
            if (end.reservesFirst) {
                f.deliverByKey(node, reserved("A-1", "d-0"));
                f.idle(node, id);
            }
            WaitRecord entry = f.waitRecords(id).stream()
                    .filter(record -> record.branchId().equals("b"))
                    .findFirst()
                    .orElseThrow();
            f.deadLetterTimeouts(node, "b", id);
            f.deliverByKey(node, labelled("B-1", "d-1"));
            f.idle(node, id);
            SignalRow parked = f.signalRows(id).getLast();
            assertThat(parked.state()).isEqualTo("PARKED");
            assertThat(parked.waitId()).isEqualTo(entry.waitId().toString());

            // When a signal drives branch a to its failure, and the join routes the instance to its end
            f.deliverByKey(node, go("A-1", "d-2"));
            f.idle(node, id);

            // Then the instance has the end's status, and the command is rejected with its history, not stale
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo(end.name());
            SignalRow rejected = f.signalRows(id).stream()
                    .filter(row -> row.id() == parked.id())
                    .findFirst()
                    .orElseThrow();
            assertThat(rejected.state()).isEqualTo("REJECTED");
            assertThat(f.commandsWithHistory(id, "COMMAND_REJECTED")).contains(parked.id());
            assertThat(f.commandsWithHistory(id, COMMAND_STALE)).doesNotContain(parked.id());
            assertThat(f.openSignals(id)).isZero();
            assertThat(f.waitRecords(id)).isEmpty();
            HeldCommandInvariant.holds(f);
        }
    }

    /** Whether the waiting sibling of a failed fork has a timeout. */
    enum SiblingTimeout {
        /** The sibling waits with no timeout. */
        NONE(KeyedWaitFixture.FORK_FAILING_TO_FAIL),
        /** The sibling's timeout came due and was settled stale while the instance was FAILED. */
        TIMED(KeyedWaitFixture.FORK_FAILING_TO_FAIL_TIMED);

        final String definitionId;

        SiblingTimeout(String definitionId) {
            this.definitionId = definitionId;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(SiblingTimeout.class)
    @DisplayName("the drain skips a correlated command while a retry fails the instance again")
    void drainSkipsACorrelatedCommandWhileARetryFailsTheInstanceAgain(SiblingTimeout sibling) {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given an ALL_REQUIRED fork with a failure route to a FailNode and no compensable step, whose branch a
            // fails
            // while branch b waits at a keyed wait; the instance is FAILED with a null main wait
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startFork(node, sibling.definitionId, "A-1", "B-1");
            f.idle(node, id);
            WaitRecord entry = only(f.waitRecords(id));
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(f.snapshot(id).getString("wait_type")).isNull();

            // And, in the timed case, the sibling's timeout came due and settled stale while the instance was FAILED
            if (sibling == SiblingTimeout.TIMED) {
                f.fireTimeout(id, "b");
                f.idle(node, id);
                assertThat(f.timerCommands(id)).extracting(CommandRow::state).containsExactly("STALE");
            }

            // And a correlated delivery on b's key parks on the entry, in the timed case past the fire time
            f.deliverByKey(node, printed("B-1", "d-1"));
            f.idle(node, id);
            SignalRow command = only(f.signalRows(id));
            assertThat(command.state()).isEqualTo("PARKED");
            assertThat(command.waitId()).isEqualTo(entry.waitId().toString());

            // When a retry runs, so redrive runs the failure node again and the segment drains
            f.retry(node, id);
            f.idle(node, id);

            // Then the instance is FAILED, the command is still parked on the entry without having been applied, and
            // branch b still waits there under the same wait id
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(only(f.signalRows(id)).state()).isEqualTo("PARKED");
            assertThat(f.commandsWithHistory(id, "SIGNAL_APPLIED")).isEmpty();
            assertThat(f.branchTokens(id)).contains(new BranchRow("b", "WAITING", "wait-b"));
            assertThat(only(f.waitRecords(id)).waitId()).isEqualTo(entry.waitId());
            HeldCommandInvariant.holds(f);

            // And when the instance is cancelled, the command is cancelled
            f.cancel(node, id);
            f.idle(node, id);
            assertThat(only(f.signalRows(id)).state()).isEqualTo("CANCELLED");
            assertThat(f.openSignals(id)).isZero();
            HeldCommandInvariant.holds(f);
        }
    }

    @Test
    @DisplayName(
            "a migration exit cancels a dead-lettered timeout of the closed entry, so the re-entered wait takes its"
                    + " delivery")
    void migrationExitCancelsADeadLetteredTimeoutSoTheReenteredWaitTakesItsDelivery() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given an instance of version 1 at a keyed timed wait whose timeout is dead-lettered, and a migration to
            // version 2 that keeps the step and the key
            Node node = f.deadLettering(f.migratingNode("engine")).build();
            WorkflowInstanceId id = f.startFork(node, KeyedWaitFixture.MIGRATING_TIMED, "A-1", "B-1");
            f.idle(node, id);
            WaitRecord first = only(f.waitRecords(id));
            f.deadLetterTimeouts(node, null, id);
            TimerRecord oldTimer = only(f.timerRecords(id));
            CommandRow oldCommand = only(f.timerCommands(id));
            assertThat(oldCommand.state()).isEqualTo("DEAD");

            // When the migration runs and enters the new entry
            f.migrate(node, id, 2);
            f.idle(node, id);

            // Then the old timer row and its command are cancelled, with one TIMER_CANCELLED entry, and the new wait
            // has
            // a timer of its own
            WaitRecord second = only(f.waitRecords(id));
            assertThat(second.waitId()).isNotEqualTo(first.waitId());
            assertThat(f.timerRecords(id))
                    .filteredOn(timer -> timer.timerId().equals(oldTimer.timerId()))
                    .extracting(TimerRecord::status)
                    .containsExactly("CANCELLED");
            assertThat(f.timerCommands(id))
                    .filteredOn(command -> command.id() == oldCommand.id())
                    .extracting(CommandRow::state)
                    .containsExactly("CANCELLED");
            assertThat(f.historyCount(id, "TIMER_CANCELLED")).isEqualTo(1);
            assertThat(f.timerRecords(id))
                    .filteredOn(timer -> !timer.timerId().equals(oldTimer.timerId()))
                    .extracting(TimerRecord::status)
                    .containsExactly("SCHEDULED");
            assertThat(f.timerCommands(id)).extracting(CommandRow::state).doesNotContain("DEAD");

            // And a correlated delivery on the key, recorded past the old fire time, names the new entry and applies at
            // it
            f.deliverByKey(node, shipped("A-1", "d-1"));
            f.idle(node, id);
            SignalRow command = only(f.signalRows(id));
            assertThat(command.waitId()).isEqualTo(second.waitId().toString());
            assertThat(command.state()).isEqualTo("COMPLETE");
            assertThat(f.commandsWithHistory(id, SIGNAL_RECEIVED)).containsExactly(command.id());
            assertThat(f.timerCommands(id)).extracting(CommandRow::state).doesNotContain("DEAD");
            HeldCommandInvariant.holds(f);
        }
    }

    @Test
    @DisplayName(
            "an instance-id signal keeps today's behaviour in the unkeyed guard cells and applies after a migration"
                    + " into a keyed wait")
    void instanceIdSignalKeepsTodaysBehaviourInTheUnkeyedGuardCellsAndAppliesAfterAMigrationIntoAKeyedWait() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            Node node = f.deadLettering(f.migratingNode("engine")).build();

            // Given an unkeyed fork failed at its failure node, an unkeyed timed wait and a keyed timed wait, each of
            // the
            // last two with its timeout dead-lettered and a migration to version 2 that keeps the wait
            WorkflowInstanceId forked = f.startFork(node, KeyedWaitFixture.FORK_FAILING_TO_FAIL_UNKEYED, "A-1", "B-1");
            WorkflowInstanceId unkeyed = f.startFork(node, KeyedWaitFixture.MIGRATING_TIMED_UNKEYED, "A-2", "B-2");
            WorkflowInstanceId keyed = f.startFork(node, KeyedWaitFixture.MIGRATING_TIMED, "A-3", "B-3");
            f.idle(node, forked, unkeyed, keyed);
            assertThat(f.fixture.instanceOf(forked).status()).isEqualTo("FAILED");
            assertThat(f.waitRecords(unkeyed)).isEmpty();
            assertThat(f.waitRecords(forked)).isEmpty();
            f.deadLetterTimeouts(node, null, unkeyed, keyed);
            TimerRecord unkeyedTimer = only(f.timerRecords(unkeyed));
            CommandRow unkeyedTimeout = only(f.timerCommands(unkeyed));
            TimerRecord keyedTimer = only(f.timerRecords(keyed));
            CommandRow keyedTimeout = only(f.timerCommands(keyed));

            // When the first is retried after an instance-id signal parked for its branch; the second is migrated and
            // an
            // instance-id signal is recorded for the re-entered wait past the old fire time; the third likewise
            f.signalBranch(node, forked, PRINTED, new Payment("txn-1"), "i-1", "fork", "b");
            f.idle(node, forked);
            assertThat(only(f.signalRows(forked)).state()).isEqualTo("PARKED");
            f.retry(node, forked);
            f.migrate(node, unkeyed, 2);
            f.migrate(node, keyed, 2);
            f.idle(node, forked, unkeyed, keyed);
            f.signalInstance(node, unkeyed, SHIPPED, new Payment("txn-2"), "i-2");
            f.signalInstance(node, keyed, SHIPPED, new Payment("txn-3"), "i-3");
            f.idle(node, unkeyed, keyed);

            // Then the first signal applied in the retry's segment while the instance stayed FAILED, as it does today
            SignalRow first = only(f.signalRows(forked));
            assertThat(first.state()).isEqualTo("COMPLETE");
            assertThat(f.fixture.instanceOf(forked).status()).isEqualTo("FAILED");
            assertThat(first.waitId()).isNull();

            // And the second is parked behind the old timeout, which stays scheduled and dead-lettered, as it does
            // today
            SignalRow second = only(f.signalRows(unkeyed));
            assertThat(second.state()).isEqualTo("PARKED");
            assertThat(second.waitId()).isNull();
            assertThat(f.timerRecords(unkeyed))
                    .filteredOn(timer -> timer.timerId().equals(unkeyedTimer.timerId()))
                    .extracting(TimerRecord::status)
                    .containsExactly("SCHEDULED");
            assertThat(f.timerCommands(unkeyed))
                    .filteredOn(command -> command.id() == unkeyedTimeout.id())
                    .extracting(CommandRow::state)
                    .containsExactly("DEAD");

            // And the third applies at the re-entered wait, because the migration cancelled the old timer and its
            // command
            SignalRow third = only(f.signalRows(keyed));
            assertThat(third.state()).isEqualTo("COMPLETE");
            assertThat(third.waitId()).isNull();
            assertThat(f.timerRecords(keyed))
                    .filteredOn(timer -> timer.timerId().equals(keyedTimer.timerId()))
                    .extracting(TimerRecord::status)
                    .containsExactly("CANCELLED");
            assertThat(f.timerCommands(keyed))
                    .filteredOn(command -> command.id() == keyedTimeout.id())
                    .extracting(CommandRow::state)
                    .containsExactly("CANCELLED");
            HeldCommandInvariant.holds(f);
        }
    }

    // --- Signals ---

    private static CorrelatedSignal paid(String key, String dedupKey) {
        return CorrelatedSignal.of(OrderContract.class, PAID, key, new Payment("txn-1"), dedupKey);
    }

    private static CorrelatedSignal shipped(String key, String dedupKey) {
        return CorrelatedSignal.of(ForkContract.class, SHIPPED, key, new Payment("txn-1"), dedupKey);
    }

    private static CorrelatedSignal printed(String key, String dedupKey) {
        return CorrelatedSignal.of(ForkContract.class, PRINTED, key, new Payment("txn-1"), dedupKey);
    }

    private static CorrelatedSignal labelled(String key, String dedupKey) {
        return CorrelatedSignal.of(EndingContract.class, PRINTED, key, new Payment("txn-1"), dedupKey);
    }

    private static CorrelatedSignal go(String key, String dedupKey) {
        return CorrelatedSignal.of(EndingContract.class, "a-go", key, new Payment("txn-1"), dedupKey);
    }

    private static CorrelatedSignal reserved(String key, String dedupKey) {
        return CorrelatedSignal.of(EndingContract.class, "stock-reserved", key, new Payment("txn-1"), dedupKey);
    }

    // --- Assertions ---

    /** The command is stale, settled, and has exactly one {@code COMMAND_STALE} history entry in the instance. */
    private static void assertStaleOnce(KeyedWaitFixture f, WorkflowInstanceId id, SignalRow command) {
        SignalRow current = f.signalRows(id).stream()
                .filter(row -> row.id() == command.id())
                .findFirst()
                .orElseThrow();
        assertThat(current.state()).as("state of command %d", command.id()).isEqualTo("STALE");
        assertThat(current.row().settledAt()).isNotNull();
        assertThat(f.commandsWithHistory(id, COMMAND_STALE))
                .as("COMMAND_STALE entries naming command %d", command.id())
                .containsOnlyOnce(command.id());
    }

    /** The log names the instance, the signal and the command of a command that settled stale. */
    private static void assertStaleLogged(
            KeyedWaitFixture f, WorkflowInstanceId id, SignalRow command, String signalName) {
        assertThat(f.loggedEntries(Level.INFO))
                .as("a log entry naming the instance, the signal and the command")
                .anyMatch(entry -> entry.contains(id.value().toString())
                        && entry.contains(signalName)
                        && entry.contains(String.valueOf(command.id())));
    }

    /** A delivery made again answers duplicate, adds no command and applies nothing. */
    private static void assertReplayAnswersDuplicate(
            KeyedWaitFixture f, Node node, WorkflowInstanceId id, CorrelatedSignal replayed) {
        CorrelatedSignalReceipt replay = f.deliverByKey(node, replayed);
        assertThat(replay.outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.DUPLICATE);
        f.idle(node, id);
        assertThat(f.signalRows(id)).hasSize(1);
        assertUnchangedBySignal(f, id);
        assertThat(f.openSignals(id)).isZero();
    }

    /** No signal was received and no payload of a signal reached the state of the instance. */
    private static void assertUnchangedBySignal(KeyedWaitFixture f, WorkflowInstanceId id) {
        assertThat(f.historyCount(id, SIGNAL_RECEIVED)).isZero();
        assertThat(String.valueOf(f.fixture.instanceOf(id).stateJson())).doesNotContain("txn-");
    }

    /** The key appears in no stored error text and no log entry. */
    private static void assertNoKeyLeaked(KeyedWaitFixture f, WorkflowInstanceId id, String key) {
        assertThat(f.storedErrorText(id)).doesNotContain(key);
        assertThat(f.loggedText(Level.ALL)).doesNotContain(key);
    }

    private static <T> T only(List<T> items) {
        assertThat(items).hasSize(1);
        return items.getFirst();
    }
}
