// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.CorrelatedSignal;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.BranchRow;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.ForkContract;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.Payment;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.SignalCommand;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.WaitRecord;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a keyed signal wait inside a fork branch writes its own open record, with the fork step and the branch,
 * in the transaction that enters the wait, and that a delivery by key addresses that branch.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class BranchWaitRecordIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_branch_wait_record_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName("each keyed branch wait writes its own record")
    void eachKeyedBranchWaitWritesItsOwnRecord() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given a fork with branches b1 and b2, b1 waiting at wait-a for shipment-confirmed keyed by refA and b2
            // at wait-b for label-printed keyed by refB, and an instance started with refA A-1 and refB B-1
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startFork(node, KeyedWaitFixture.FORK, "A-1", "B-1");

            // When the start runs and the instance is idle
            node.startExecutor();
            node.awaitIdle(id);

            // Then two records exist, one for each branch, with the fork step, the branch, the step, the signal and
            // the key of its wait
            List<WaitRecord> records = f.waitRecords(id);
            assertThat(records)
                    .extracting(
                            WaitRecord::forkStepId,
                            WaitRecord::branchId,
                            WaitRecord::stepId,
                            WaitRecord::signalName,
                            WaitRecord::correlationKey)
                    .containsExactlyInAnyOrder(
                            tuple("fork", "b1", "wait-a", "shipment-confirmed", "A-1"),
                            tuple("fork", "b2", "wait-b", "label-printed", "B-1"));

            // And their identifiers differ and none is addressed to the main path
            assertThat(records).extracting(WaitRecord::waitId).doesNotHaveDuplicates();
            assertThat(records).noneMatch(record -> record.forkStepId().isEmpty());
            assertThat(records).noneMatch(record -> record.branchId().isEmpty());
        }
    }

    @Test
    @DisplayName("a keyed branch wait with a timeout writes its record")
    void keyedBranchWaitWithATimeoutWritesItsRecord() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given the same fork with a timeout of an hour on the wait of b1
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startFork(node, KeyedWaitFixture.FORK_WITH_TIMEOUT, "A-1", "B-1");

            // When the start runs to the waits
            node.startExecutor();
            node.awaitIdle(id);

            // Then the record of b1 exists with its branch address
            assertThat(f.waitRecords(id))
                    .filteredOn(record -> record.branchId().equals("b1"))
                    .singleElement()
                    .satisfies(record -> {
                        assertThat(record.forkStepId()).isEqualTo("fork");
                        assertThat(record.stepId()).isEqualTo("wait-a");
                        assertThat(record.signalName()).isEqualTo("shipment-confirmed");
                        assertThat(record.correlationKey()).isEqualTo("A-1");
                    });

            // And a timeout timer is scheduled for the branch
            assertThat(f.branchTimeoutTimerOf(id, "b1")).isNotNull();
        }
    }

    @Test
    @DisplayName("a delivery by key addresses the branch that waits on the key")
    void signalByKeyAddressesTheBranchThatWaitsOnTheKey() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given the fork of the first test with an executor, both branches waiting
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startFork(node, KeyedWaitFixture.FORK, "A-1", "B-1");
            node.startExecutor();
            node.awaitIdle(id);

            // When a caller delivers shipment-confirmed with key A-1 by key and the instance is idle again
            CorrelatedSignalReceipt receipt = f.deliverByKey(
                    node,
                    CorrelatedSignal.of(ForkContract.class, "shipment-confirmed", "A-1", new Payment("txn-1"), "d-1"));
            node.awaitIdle(id);

            // Then the signal is recorded for the branch it waits in
            assertThat(receipt.outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.RECORDED);
            assertThat(f.signalCommands(id))
                    .singleElement()
                    .extracting(SignalCommand::target)
                    .satisfies(target -> {
                        assertThat(target.getString("name")).isEqualTo("shipment-confirmed");
                        assertThat(target.getString("forkStepId")).isEqualTo("fork");
                        assertThat(target.getString("branchId")).isEqualTo("b1");
                    });

            // And b1 has left its wait with the payload applied, while b2 still waits at wait-b
            assertThat(f.branchTokens(id))
                    .extracting(BranchRow::branchId, BranchRow::status, BranchRow::currentStepId)
                    .containsExactlyInAnyOrder(tuple("b1", "COMPLETED", "a-done"), tuple("b2", "WAITING", "wait-b"));
            assertThat(String.valueOf(f.fixture.instanceOf(id).stateJson())).contains("txn-1");

            // And the history records the branch's resumption
            assertThat(f.fixture.historyOf(id)).contains("BRANCH_COMPLETED", "COMMAND_COMPLETED");
        }
    }
}
