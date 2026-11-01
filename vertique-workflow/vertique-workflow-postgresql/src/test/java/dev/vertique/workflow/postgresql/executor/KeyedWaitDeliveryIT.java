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
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.OrderContract;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.Payment;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.SignalCommand;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a wait which declares a key writes an open record in its segment's transaction, and that a delivery by
 * contract, signal name and key records the signal for the one instance that waits on it, while delivery by instance
 * id behaves as before.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class KeyedWaitDeliveryIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_keyed_wait_delivery_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String SIGNAL = "payment-captured";

    @Test
    @DisplayName("a keyed wait writes an open record when its segment commits")
    void keyedWaitWritesAnOpenRecordWhenItsSegmentCommits() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given an order definition whose wait is keyed by the payment reference, a started instance in scope
            // acme with payment reference PAY-77, and an executor
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startKeyed(node, "PAY-77");

            // When the start runs to the wait
            node.startExecutor();
            node.awaitIdle(id);

            // Then exactly one open record names the wait, the scope, the contract, the definition, the signal and
            // the key, with empty fork step and branch and a version 7 identifier
            assertThat(f.waitRecords(id)).singleElement().satisfies(record -> {
                assertThat(record.stepId()).isEqualTo("wait-payment");
                assertThat(record.forkStepId()).isEmpty();
                assertThat(record.branchId()).isEmpty();
                assertThat(record.scopeKey()).isEqualTo("acme");
                assertThat(record.contractName()).isEqualTo(f.contractNameOf(KeyedWaitFixture.ORDER));
                assertThat(record.definitionId()).isEqualTo(KeyedWaitFixture.ORDER);
                assertThat(record.signalName()).isEqualTo(SIGNAL);
                assertThat(record.correlationKey()).isEqualTo("PAY-77");
                assertThat(record.waitId().version()).isEqualTo(7);
            });

            // And the instance is waiting
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
        }
    }

    @Test
    @DisplayName("a keyed wait with a timeout writes the same record")
    void keyedWaitWithATimeoutWritesTheSameRecord() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given the same definition with a timeout on the wait
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.start(node, KeyedWaitFixture.ORDER_WITH_TIMEOUT, "PAY-77", "acme");

            // When the start runs to the wait
            node.startExecutor();
            node.awaitIdle(id);

            // Then one record with the same columns exists
            assertThat(f.waitRecords(id)).singleElement().satisfies(record -> {
                assertThat(record.stepId()).isEqualTo("wait-payment");
                assertThat(record.forkStepId()).isEmpty();
                assertThat(record.branchId()).isEmpty();
                assertThat(record.scopeKey()).isEqualTo("acme");
                assertThat(record.contractName()).isEqualTo(f.contractNameOf(KeyedWaitFixture.ORDER_WITH_TIMEOUT));
                assertThat(record.definitionId()).isEqualTo(KeyedWaitFixture.ORDER_WITH_TIMEOUT);
                assertThat(record.signalName()).isEqualTo(SIGNAL);
                assertThat(record.correlationKey()).isEqualTo("PAY-77");
                assertThat(record.waitId().version()).isEqualTo(7);
            });

            // And the instance carries a timeout timer
            assertThat(f.timeoutTimerOf(id)).isNotNull();
        }
    }

    @Test
    @DisplayName("a rolled-back segment leaves no record and its retry leaves exactly one")
    void rolledBackSegmentLeavesNoRecordAndItsRetryLeavesOne() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given a command repository decorator that fails the first completion of the start segment with a lost
            // connection, after the segment entered the wait, and a retry backoff long enough to look in between
            UnaryOperator<WorkflowCommandRepository<SqlClient>> failFirstCompletion = ExecutorFixture.intercepting(
                    "complete",
                    (call, member, arguments, real) -> call == 1
                            ? Future.failedFuture(
                                    FailingTransactions.connectionFailure().get())
                            : real.call());
            Node node = f.node("engine")
                    .commands(failFirstCompletion)
                    .transientBackoffMs(3_000, 3_000)
                    .build();
            WorkflowInstanceId id = f.startKeyed(node, "PAY-77");

            // When the start executes and fails once
            node.startExecutor();
            awaitCondition(
                    "the first attempt to fail",
                    IDLE,
                    () -> f.fixture.commandOf(id).transientCount() >= 1);

            // Then a second connection sees no record while the first attempt has failed
            assertThat(f.waitRecords(id)).isEmpty();

            // And, once the start is retried and the instance is idle, exactly one record exists
            awaitCondition("the retry to reach the wait", IDLE, () -> "WAITING"
                    .equals(f.fixture.instanceOf(id).status()));
            node.awaitIdle(id);
            assertThat(f.waitRecords(id)).hasSize(1);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
        }
    }

    @Test
    @DisplayName("a delivery by key resumes the instance that waits on it")
    void signalByKeyResumesTheInstanceThatWaitsOnTheKey() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given an instance waiting on PAY-77, another on PAY-78, a third at a keyed wait for another signal
            // with key PAY-77, a fourth of another contract waiting on a signal of the same name with key PAY-77,
            // and a running executor
            Node node = f.node("engine").build();
            WorkflowInstanceId first = f.startKeyed(node, "PAY-77");
            WorkflowInstanceId second = f.startKeyed(node, "PAY-78");
            WorkflowInstanceId otherSignal = f.start(node, KeyedWaitFixture.ORDER_REFUND, "PAY-77", "acme");
            WorkflowInstanceId otherContract = f.start(node, KeyedWaitFixture.INVOICE, "PAY-77", null);
            node.startExecutor();
            for (WorkflowInstanceId id : List.of(first, second, otherSignal, otherContract)) {
                node.awaitIdle(id);
            }

            // When a caller transaction delivers the signal by key and commits
            CorrelatedSignalReceipt receipt = f.deliverByKey(
                    node, CorrelatedSignal.of(OrderContract.class, SIGNAL, "PAY-77", new Payment("txn-1"), "d-1"));
            node.awaitIdle(first);

            // Then the receipt is RECORDED and names the first instance's definition
            assertThat(receipt.outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.RECORDED);
            assertThat(receipt.definitionId()).isEqualTo(KeyedWaitFixture.ORDER);

            // And the first instance left the wait with the payload applied and the signal in its history
            assertThat(f.fixture.instanceOf(first).status()).isEqualTo("COMPLETED");
            assertThat(String.valueOf(f.fixture.instanceOf(first).stateJson())).contains("txn-1");
            assertThat(f.fixture.historyOf(first)).contains("SIGNAL_RECEIVED");

            // And the other three are unchanged and still waiting
            for (WorkflowInstanceId id : List.of(second, otherSignal, otherContract)) {
                assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
                assertThat(f.fixture.historyOf(id)).doesNotContain("SIGNAL_RECEIVED");
                assertThat(f.signalCommands(id)).isEmpty();
            }

            // And one completed signal command holds the name and no branch address, under a hashed dedup key
            assertThat(f.signalCommands(first)).singleElement().satisfies(command -> {
                assertThat(command.state()).isEqualTo("COMPLETE");
                assertThat(command.target().getString("name")).isEqualTo(SIGNAL);
                assertThat(command.target().getString("forkStepId")).isNull();
                assertThat(command.target().getString("branchId")).isNull();
                assertThat(command.dedupKey()).matches("[0-9a-f]{64}").isNotEqualTo("d-1");
            });

            // And no dedup row of kind signal exists for the first instance
            assertThat(f.dedupRows(first, "signal")).isZero();
        }
    }

    @Test
    @DisplayName("delivery by instance id on a keyed wait is unchanged")
    void instanceIdDeliveryOnAKeyedWaitIsUnchanged() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given an instance waiting at the keyed wait
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startKeyed(node, "PAY-77");
            node.startExecutor();
            node.awaitIdle(id);

            // When a caller records the signal by instance id and the executor applies it
            f.signalInstance(node, id, SIGNAL, new Payment("txn-1"), "k-1");
            node.awaitIdle(id);

            // Then the signal applied as before
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("COMPLETED");
            assertThat(f.fixture.historyOf(id)).contains("SIGNAL_RECEIVED");

            // And the command keeps the raw dedup key, a dedup row of kind signal holds it, and no correlated row
            // exists
            List<SignalCommand> commands = f.signalCommands(id);
            assertThat(commands).singleElement().satisfies(command -> {
                assertThat(command.state()).isEqualTo("COMPLETE");
                assertThat(command.dedupKey()).isEqualTo("k-1");
            });
            assertThat(f.signalDedupRows(id, "k-1")).isEqualTo(1);
            assertThat(f.correlatedDedupRows(id)).isZero();

            // And a redelivery with the same key succeeds and adds no command
            f.signalInstance(node, id, SIGNAL, new Payment("txn-1"), "k-1");
            assertThat(f.signalCommands(id)).hasSameSizeAs(commands);
        }
    }
}
