// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.BranchRow;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.Payment;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.SignalRow;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that an instance that has failed takes no signal at a branch wait: a signal addressed to one of its branches
 * and carrying no wait entry is parked while the instance is {@code FAILED}, the branch stays at its wait, and the
 * signal applies only after a retry has resumed the instance. A signal addressed to the instance cannot reach a branch
 * wait at all: the engine refuses it when it is recorded.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class FailedInstanceParkedSignalIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_failed_instance_parked_signal_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String PRINTED = "label-printed";

    @Test
    @DisplayName("an instance-id signal for a name only a branch waits for is refused at recording, failed or not")
    void instanceIdSignalForANameOnlyABranchWaitsForIsRefusedAtRecording() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given a fork whose branch a fails the instance and whose branch b waits for label-printed
            Node node = f.node("engine").singleSegments().build();
            WorkflowInstanceId id = failedFork(f, node);

            // When an instance-id signal of the name branch b waits for is recorded
            assertThatThrownBy(() -> f.signalInstance(node, id, PRINTED, new Payment("txn-1"), "s-1"))
                    .hasMessageContaining("does not declare it for the addressed wait");

            // Then no command was recorded and branch b still waits
            assertThat(f.signalRows(id)).isEmpty();
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(f.branchTokens(id)).contains(new BranchRow("b", "WAITING", "wait-b"));
            HeldCommandInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a branch-addressed signal without a wait entry parks while the instance is failed and the branch"
            + " keeps waiting")
    void branchAddressedSignalWithoutAWaitEntryParksWhileTheInstanceIsFailed() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given the same failed fork
            Node node = f.node("engine").singleSegments().build();
            WorkflowInstanceId id = failedFork(f, node);

            // When a signal addressed to branch b, naming no wait entry, is recorded and the executors run
            f.signalBranch(node, id, PRINTED, new Payment("txn-1"), "s-1", "fork", "b");
            f.idle(node, id);

            // Then it is parked, carries no wait entry, and branch b still waits
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            SignalRow command = only(f.signalRows(id));
            assertThat(command.state()).isEqualTo("PARKED");
            assertThat(command.waitId()).isNull();
            assertThat(f.branchTokens(id)).contains(new BranchRow("b", "WAITING", "wait-b"));
            assertThat(f.commandsWithHistory(id, "SIGNAL_APPLIED")).isEmpty();
            HeldCommandInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("the parked branch-addressed signal applies when a retry has resumed the instance")
    void theParkedBranchAddressedSignalAppliesWhenARetryHasResumedTheInstance() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given the failed fork with a branch-addressed signal parked
            Node node = f.node("engine").singleSegments().build();
            WorkflowInstanceId id = failedFork(f, node);
            f.signalBranch(node, id, PRINTED, new Payment("txn-1"), "s-1", "fork", "b");
            f.idle(node, id);
            assertThat(only(f.signalRows(id)).state()).isEqualTo("PARKED");

            // When the fault is removed and the retry runs
            f.branchTimerPathBroken.set(false);
            f.retry(node, id);
            node.runOneSegment();

            // Then the signal is applied in the retry's segment and branch b is past its wait
            SignalRow applied = only(f.signalRows(id));
            assertThat(applied.state()).isEqualTo("COMPLETE");
            assertThat(f.commandsWithHistory(id, "SIGNAL_APPLIED")).containsExactly(applied.id());
            assertThat(f.branchTokens(id)).contains(new BranchRow("b", "COMPLETED", "b-done"));
            HeldCommandInvariant.holds(f);
        }
    }

    @Test
    @DisplayName("a retry that fails the instance again at its failure node applies no parked signal to the branch"
            + " that still waits")
    void aRetryThatFailsTheInstanceAgainAppliesNoParkedSignalToTheBranchThatStillWaits() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given a fork that has failed at its failure node, with branch b still waiting for label-printed
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startFork(node, KeyedWaitFixture.FORK_FAILING_TO_FAIL_UNKEYED, "A-1", "B-1");
            f.idle(node, id);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(f.branchTokens(id)).contains(new BranchRow("b", "WAITING", "wait-b"));

            // And a signal for branch b, parked because the instance is failed
            f.signalBranch(node, id, PRINTED, new Payment("txn-1"), "s-1", "fork", "b");
            f.idle(node, id);
            assertThat(only(f.signalRows(id)).state()).isEqualTo("PARKED");

            // When a retry runs, and the instance fails at the same node again
            f.retry(node, id);
            f.idle(node, id);

            // Then the signal is still parked and branch b has not moved: nothing applies while the instance is failed
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(only(f.signalRows(id)).state()).isEqualTo("PARKED");
            assertThat(f.branchTokens(id)).contains(new BranchRow("b", "WAITING", "wait-b"));
            assertThat(f.commandsWithHistory(id, "SIGNAL_APPLIED")).isEmpty();
            HeldCommandInvariant.holds(f);
        }
    }

    private static WorkflowInstanceId failedFork(KeyedWaitFixture f, Node node) {
        WorkflowInstanceId id = f.startFork(node, KeyedWaitFixture.FORK_BREAKABLE_TIMER, "A-1", "B-1");
        f.idle(node, id);
        f.fireTimer(id);
        f.idle(node, id);
        assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
        assertThat(f.snapshot(id).getString("wait_type")).isEqualTo("JOIN");
        assertThat(f.branchTokens(id)).contains(new BranchRow("b", "WAITING", "wait-b"));
        return id;
    }

    private static <T> T only(List<T> items) {
        assertThat(items).hasSize(1);
        return items.getFirst();
    }
}
