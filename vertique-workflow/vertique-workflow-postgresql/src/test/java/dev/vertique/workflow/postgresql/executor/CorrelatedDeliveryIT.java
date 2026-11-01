// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.failureOf;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.exception.WorkflowSignalAmbiguousException;
import dev.vertique.workflow.exception.WorkflowSignalNoMatchException;
import dev.vertique.workflow.history.WorkflowHistoryFailure;
import dev.vertique.workflow.ops.CorrelatedSignal;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.BranchRow;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.ChainContract;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.DedupRow;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.Delivery;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.ForkContract;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.OrderContract;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.Payment;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.WaitRecord;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.Written;
import dev.vertique.workflow.postgresql.history.RecordedHistory;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Verifies that a delivery by contract, signal name and key gives every delivery one defined outcome at record time:
 * a receipt for the instance or branch that waits, a duplicate for a dedup key already claimed, and a typed refusal
 * for no match, an ambiguity or a wait that closed in between, each refusal writing nothing; that the dedup row is
 * keyed by hashes and is unique across instances; and that the entry keeps its client guard and its history report.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CorrelatedDeliveryIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_correlated_delivery_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String SIGNAL = "payment-captured";

    // --- Which instance receives the delivery ---

    @Test
    @DisplayName("the signal runs in the scope of the instance that waits")
    void deliveryRunsInTheScopeOfTheInstanceThatWaits() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given an instance in scope acme, started by a caller whose context holds that scope, waiting on PAY-77
            Node node = f.node("engine").carryContext().build();
            WorkflowInstanceId id = f.startInScope(node, KeyedWaitFixture.ORDER_SCOPE_PROBE, "PAY-77", "acme");
            idle(node, id);
            JsonObject scopeColumnsBefore = f.scopeColumns(id);

            // When a delivery that names no scope is recorded, from a caller whose context holds none, and applied
            Delivery delivery = f.deliver(node, order("PAY-77", "d-1"));
            idle(node, id);

            // Then the receipt is RECORDED
            assertThat(delivery.receiptOrFail().outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.RECORDED);

            // And one correlated dedup row exists with the instance as its workflow id
            assertThat(f.dedupRows()).singleElement().satisfies(row -> assertThat(row.workflowId())
                    .isEqualTo(id.value()));

            // And the reducer saw scope acme
            assertThat(f.observedScopes).containsExactly("acme");

            // And the instance's own scope columns are unchanged
            assertThat(f.scopeColumns(id)).isEqualTo(scopeColumnsBefore);
            assertThat(scopeColumnsBefore.getString("scope_key")).isEqualTo("acme");
        }
    }

    @Test
    @DisplayName("a narrowing definition id or scope selects the named instance")
    void narrowingByDefinitionOrScopeSelectsTheNamedInstance() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given two instances of one contract waiting on PAY-77: one of the order definition in scope acme, one of
            // the order-with-timeout definition in scope globex
            Node node = f.node("engine").build();
            WorkflowInstanceId inAcme = f.start(node, KeyedWaitFixture.ORDER, "PAY-77", "acme");
            WorkflowInstanceId inGlobex = f.start(node, KeyedWaitFixture.ORDER_WITH_TIMEOUT, "PAY-77", "globex");
            idle(node, inAcme, inGlobex);

            // When one delivery names the globex definition, another under a fresh dedup key names scope acme, and a
            // third under a fresh key names neither
            Delivery byDefinition =
                    f.deliver(node, order("PAY-77", "d-1").withDefinition(KeyedWaitFixture.ORDER_WITH_TIMEOUT));
            Delivery byScope = f.deliver(node, order("PAY-77", "d-2").withScope("acme"));
            Written beforeUnnarrowed = f.written();
            Delivery unnarrowed = f.deliver(node, order("PAY-77", "d-3"));

            // Then the first records for the globex instance only, carrying its definition id
            assertThat(byDefinition.receiptOrFail().definitionId()).isEqualTo(KeyedWaitFixture.ORDER_WITH_TIMEOUT);

            // And the second records for the acme instance only, carrying its definition id
            assertThat(byScope.receiptOrFail().definitionId()).isEqualTo(KeyedWaitFixture.ORDER);
            assertThat(f.signalCommands(inGlobex)).hasSize(1);
            assertThat(f.signalCommands(inAcme)).hasSize(1);

            // And the third fails as ambiguous and writes nothing
            assertThat(unnarrowed.failureOrFail()).isInstanceOf(WorkflowSignalAmbiguousException.class);
            assertThat(f.written()).isEqualTo(beforeUnnarrowed);
        }
    }

    @Test
    @DisplayName("a delivery names no definition and finds the second definition's instance")
    void deliveryNamesNoDefinitionAndFindsTheSecondDefinitionsInstance() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given two definitions of one contract, the first with an instance waiting on PAY-78 and the second with
            // the only open record on PAY-77
            Node node = f.node("engine").build();
            WorkflowInstanceId first = f.start(node, KeyedWaitFixture.ORDER, "PAY-78", "acme");
            WorkflowInstanceId second = f.start(node, KeyedWaitFixture.ORDER_WITH_TIMEOUT, "PAY-77", "acme");
            node.startExecutor();
            node.awaitIdle(first);
            node.awaitIdle(second);

            // When the signal is delivered with neither definition nor scope
            Delivery delivery = f.deliver(node, order("PAY-77", "d-1"));
            node.awaitIdle(second);

            // Then the second definition's instance receives it, and the receipt names that definition
            CorrelatedSignalReceipt receipt = delivery.receiptOrFail();
            assertThat(receipt.outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.RECORDED);
            assertThat(receipt.definitionId()).isEqualTo(KeyedWaitFixture.ORDER_WITH_TIMEOUT);
            assertThat(f.fixture.instanceOf(second).status()).isEqualTo("COMPLETED");
            assertThat(f.fixture.historyOf(second)).contains("SIGNAL_RECEIVED");

            // And the first definition's instance is unchanged
            assertThat(f.signalCommands(first)).isEmpty();
            assertThat(f.fixture.instanceOf(first).status()).isEqualTo("WAITING");
        }
    }

    // --- Ambiguity and no match ---

    @Test
    @DisplayName("equal keys in two scopes are ambiguous")
    void equalKeysInTwoScopesAreAmbiguous() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given instances in scopes acme and globex, both waiting on PAY-77
            Node node = f.node("engine").build();
            WorkflowInstanceId inAcme = f.start(node, KeyedWaitFixture.ORDER, "PAY-77", "acme");
            WorkflowInstanceId inGlobex = f.start(node, KeyedWaitFixture.ORDER, "PAY-77", "globex");
            idle(node, inAcme, inGlobex);
            Written before = f.written();
            JsonObject acmeBefore = f.snapshot(inAcme);
            JsonObject globexBefore = f.snapshot(inGlobex);

            // When the signal is delivered with no scope
            Delivery delivery = f.deliver(node, order("PAY-77", "d-1"));

            // Then the future fails with the ambiguity naming the contract and the signal
            assertThat(delivery.failureOrFail())
                    .isInstanceOfSatisfying(WorkflowSignalAmbiguousException.class, failure -> {
                        assertThat(failure.contractName()).isEqualTo(f.contractNameOf(KeyedWaitFixture.ORDER));
                        assertThat(failure.signalName()).isEqualTo(SIGNAL);
                    });

            // And nothing is written, and both instances are unchanged
            assertThat(f.written()).isEqualTo(before);
            assertThat(f.snapshot(inAcme)).isEqualTo(acmeBefore);
            assertThat(f.snapshot(inGlobex)).isEqualTo(globexBefore);
        }
    }

    @Test
    @DisplayName("no open record fails as no-match and writes nothing")
    void noOpenRecordFailsAsNoMatchAndWritesNothing() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given no instance waiting on PAY-99, and one waiting on PAY-77
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startKeyed(node, "PAY-77");
            idle(node, id);
            Written before = f.written();

            // When the signal is delivered with key PAY-99, and the caller commits afterwards
            Delivery delivery = f.deliver(node, order("PAY-99", "d-1"));

            // Then the future fails with the no-match naming the contract and the signal, rather than hanging
            assertThat(delivery.failureOrFail())
                    .isInstanceOfSatisfying(WorkflowSignalNoMatchException.class, failure -> {
                        assertThat(failure.contractName()).isEqualTo(f.contractNameOf(KeyedWaitFixture.ORDER));
                        assertThat(failure.signalName()).isEqualTo(SIGNAL);
                    });

            // And nothing is written, even though the caller committed
            assertThat(f.written()).isEqualTo(before);
            assertThat(f.dedupRows()).isEmpty();
        }
    }

    @Test
    @DisplayName("the record of an ended instance is no match and never makes a live delivery ambiguous")
    void recordOfAnEndedInstanceIsNoMatchAndNeverAmbiguous() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given an instance the engine cancelled, whose deleted record an older node's cancel would have left
            Node node = f.node("engine").build();
            WorkflowInstanceId ended = f.startKeyed(node, "PAY-77");
            idle(node, ended);
            WaitRecord record = only(f.waitRecords(ended));
            f.cancel(node, ended);
            assertThat(f.fixture.instanceOf(ended).status()).isEqualTo("CANCELLED");
            leaveStrayRecord(f, ended, record);
            assertThat(f.waitRecords(ended)).hasSize(1);
            Written before = f.written();

            // When the signal is delivered, with no other instance waiting
            Delivery first = f.deliver(node, order("PAY-77", "d-1"));

            // Then it fails as no-match and writes nothing
            assertThat(first.failureOrFail()).isInstanceOf(WorkflowSignalNoMatchException.class);
            assertThat(f.written()).isEqualTo(before);

            // And, with a live instance on the same key, the live instance receives the signal and the delivery is
            // not ambiguous
            WorkflowInstanceId live = f.start(node, KeyedWaitFixture.ORDER, "PAY-77", "globex");
            idle(node, live);
            Delivery second = f.deliver(node, order("PAY-77", "d-2"));
            assertThat(second.receiptOrFail().outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.RECORDED);
            assertThat(f.signalCommands(live)).hasSize(1);
            assertThat(f.signalCommands(ended)).isEmpty();
        }
    }

    @Test
    @DisplayName("two open records on one key fail as ambiguous and write nothing")
    void twoOpenRecordsWithOneKeyFailAsAmbiguousAndWriteNothing() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given two definitions of one contract, each with an instance waiting on PAY-77
            Node node = f.node("engine").build();
            WorkflowInstanceId first = f.start(node, KeyedWaitFixture.ORDER, "PAY-77", "acme");
            WorkflowInstanceId second = f.start(node, KeyedWaitFixture.ORDER_WITH_TIMEOUT, "PAY-77", "acme");
            idle(node, first, second);
            Written before = f.written();

            // When the signal is delivered with no narrowing
            Delivery delivery = f.deliver(node, order("PAY-77", "d-1"));

            // Then it fails with the ambiguity, and no command and no dedup row exist
            assertThat(delivery.failureOrFail()).isInstanceOf(WorkflowSignalAmbiguousException.class);
            assertThat(f.written()).isEqualTo(before);
            assertThat(f.signalCommands(first)).isEmpty();
            assertThat(f.signalCommands(second)).isEmpty();
            assertThat(f.dedupRows()).isEmpty();
        }
    }

    @Test
    @DisplayName("two branches of one instance on one key fail as ambiguous")
    void twoBranchesOfOneInstanceOnOneKeyFailAsAmbiguous() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given a fork whose two branches wait at keyed waits that compute the same key from the parent state
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.forkWithEqualBranchKeys(node, "SHIP-1");
            idle(node, id);
            assertThat(f.waitRecords(id)).hasSize(2);
            Written before = f.written();
            List<BranchRow> branchesBefore = f.branchTokens(id);

            // When the signal is delivered
            Delivery delivery = f.deliver(
                    node,
                    CorrelatedSignal.of(
                            ForkContract.class, "shipment-confirmed", "SHIP-1", new Payment("txn-1"), "d-1"));

            // Then it fails as ambiguous, no command exists, and neither branch is resumed
            assertThat(delivery.failureOrFail()).isInstanceOf(WorkflowSignalAmbiguousException.class);
            assertThat(f.written()).isEqualTo(before);
            assertThat(f.signalCommands(id)).isEmpty();
            assertThat(f.branchTokens(id)).containsExactlyInAnyOrderElementsOf(branchesBefore);
            assertThat(f.waitRecords(id)).hasSize(2);
        }
    }

    // --- Duplicates ---

    @Test
    @DisplayName("a redelivery after the signal applied answers duplicate and changes nothing")
    void redeliveryAfterTheSignalAppliedAnswersDuplicateAndChangesNothing() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given a delivery with dedup key d-1 that was recorded and applied, so that its record is closed
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startKeyed(node, "PAY-77");
            node.startExecutor();
            node.awaitIdle(id);
            f.deliver(node, order("PAY-77", "d-1")).receiptOrFail();
            node.awaitIdle(id);
            assertThat(f.waitRecords(id)).isEmpty();
            Written before = f.written();
            JsonObject snapshotBefore = f.snapshot(id);

            // When the same delivery arrives again
            CorrelatedSignalReceipt receipt =
                    f.deliver(node, order("PAY-77", "d-1")).receiptOrFail();
            node.awaitIdle(id);

            // Then the receipt is DUPLICATE with a null definition id
            assertThat(receipt.outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.DUPLICATE);
            assertThat(receipt.definitionId()).isNull();

            // And no command is added and the instance is unchanged
            assertThat(f.written()).isEqualTo(before);
            assertThat(f.snapshot(id)).isEqualTo(snapshotBefore);

            // And the single dedup row is of kind correlated in the default scope, naming the matched instance
            assertThat(f.dedupRows()).singleElement().satisfies(row -> {
                assertThat(row.kind()).isEqualTo("correlated");
                assertThat(row.scopeKey()).isEqualTo("default");
                assertThat(row.workflowId()).isEqualTo(id.value());
            });
        }
    }

    @Test
    @DisplayName("a redelivery after another instance waits on the key answers duplicate and leaves it unchanged")
    void redeliveryAfterAnotherInstanceWaitsOnTheKeyAnswersDuplicateAndLeavesItUnchanged() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given instance A that applied a delivery on PAY-77 with dedup key d-1, and instance B that then entered
            // a wait on PAY-77
            Node node = f.node("engine").build();
            WorkflowInstanceId a = f.startKeyed(node, "PAY-77");
            node.startExecutor();
            node.awaitIdle(a);
            f.deliver(node, order("PAY-77", "d-1")).receiptOrFail();
            node.awaitIdle(a);
            WorkflowInstanceId b = f.startKeyed(node, "PAY-77");
            node.awaitIdle(b);
            JsonObject bBefore = f.snapshot(b);

            // When the delivery with d-1 is redelivered
            CorrelatedSignalReceipt receipt =
                    f.deliver(node, order("PAY-77", "d-1")).receiptOrFail();
            node.awaitIdle(b);

            // Then the receipt is DUPLICATE, and B has no command, is unchanged and still waits
            assertThat(receipt.outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.DUPLICATE);
            assertThat(f.signalCommands(b)).isEmpty();
            assertThat(f.snapshot(b)).isEqualTo(bBefore);
            assertThat(f.fixture.instanceOf(b).status()).isEqualTo("WAITING");
            assertThat(f.waitRecords(b)).hasSize(1);
        }
    }

    @Test
    @DisplayName("a delivery with no match leaves no row, so a later delivery of the same event records")
    void noMatchWritesNoDedupRowSoALaterDeliveryOfTheSameEventRecords() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given no instance waiting on PAY-77
            Node node = f.node("engine").build();

            // When the delivery d-1 fails as no-match
            Delivery first = f.deliver(node, order("PAY-77", "d-1"));

            // Then it left no dedup row
            assertThat(first.failureOrFail()).isInstanceOf(WorkflowSignalNoMatchException.class);
            assertThat(f.dedupRows()).isEmpty();

            // And, once an instance enters the wait, the same delivery records a command with a RECORDED receipt
            WorkflowInstanceId id = f.startKeyed(node, "PAY-77");
            idle(node, id);
            Delivery second = f.deliver(node, order("PAY-77", "d-1"));
            assertThat(second.receiptOrFail().outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.RECORDED);
            assertThat(f.signalCommands(id)).hasSize(1);
        }
    }

    @Test
    @DisplayName("the dedup row is keyed by a hashed namespace and key and separates narrowing scopes")
    void dedupRowIsKeyedByHashedNamespaceAndKeyAndSeparatesNarrowingScopes() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given instances in scopes acme and globex waiting on different keys, a later instance in scope acme, and
            // a dedup key of 1,000 characters
            Node node = f.node("engine").build();
            WorkflowInstanceId acme = f.start(node, KeyedWaitFixture.ORDER, "PAY-A1", "acme");
            WorkflowInstanceId globex = f.start(node, KeyedWaitFixture.ORDER, "PAY-G1", "globex");
            WorkflowInstanceId laterAcme = f.start(node, KeyedWaitFixture.ORDER, "PAY-A2", "acme");
            idle(node, acme, globex, laterAcme);
            String dLong = "d-long-" + "x".repeat(993);
            assertThat(dLong).hasSize(1000);

            // When the same dedup key is recorded narrowed to acme, narrowed to globex, and not narrowed
            Delivery narrowedToAcme = f.deliver(node, order("PAY-A1", dLong).withScope("acme"));
            Delivery narrowedToGlobex = f.deliver(node, order("PAY-G1", dLong).withScope("globex"));
            Delivery unnarrowed = f.deliver(node, order("PAY-A1", dLong));

            // Then all three are recorded, the long dedup key accepted
            assertThat(narrowedToAcme.receiptOrFail().outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.RECORDED);
            assertThat(narrowedToGlobex.receiptOrFail().outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.RECORDED);
            assertThat(unnarrowed.receiptOrFail().outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.RECORDED);

            // And three correlated rows exist, with a 64-hex namespace and a 64-hex key that equal neither the dedup
            // key nor any key value
            List<DedupRow> rows = f.dedupRows();
            assertThat(rows).hasSize(3);
            assertThat(rows).allSatisfy(row -> {
                assertThat(row.namespace()).matches("[0-9a-f]{64}");
                assertThat(row.key()).matches("[0-9a-f]{64}").isNotEqualTo(dLong);
                assertThat(row.scopeKey()).isEqualTo("default");
            });

            // And the three namespaces differ, while the key is the hash of the dedup key in all three
            assertThat(rows.stream().map(DedupRow::namespace).distinct().count())
                    .isEqualTo(3);
            assertThat(rows.stream().map(DedupRow::key).distinct()).containsExactly(sha256Hex(dLong));

            // And the rows name the acme instance twice and the globex instance once, and the commands hold the same
            // hashed key
            assertThat(rows)
                    .extracting(DedupRow::workflowId)
                    .containsExactlyInAnyOrder(acme.value(), acme.value(), globex.value());
            assertThat(f.signalCommands(acme))
                    .allSatisfy(command -> assertThat(command.dedupKey()).isEqualTo(sha256Hex(dLong)));
            assertThat(f.signalCommands(globex))
                    .allSatisfy(command -> assertThat(command.dedupKey()).isEqualTo(sha256Hex(dLong)));

            // And a second delivery narrowed to acme, on a later instance, shares the namespace of the first and is a
            // duplicate that records nothing
            Written beforeSecond = f.written();
            CorrelatedSignalReceipt second =
                    f.deliver(node, order("PAY-A2", dLong).withScope("acme")).receiptOrFail();
            assertThat(second.outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.DUPLICATE);
            assertThat(f.signalCommands(laterAcme)).isEmpty();
            assertThat(f.written()).isEqualTo(beforeSecond);
            assertThat(f.dedupRows()).hasSize(3);
        }
    }

    // --- Races ---

    @Test
    @DisplayName("two concurrent deliveries of one event record one command")
    void concurrentDeliveriesOfOneEventRecordOneCommand() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given one instance waiting on PAY-77, and a second transaction holding that instance's execution lock
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startKeyed(node, "PAY-77");
            idle(node, id);
            ExecutorFixture.HeldLock holder = f.fixture.holdExecutionLock(id);
            try {
                // When two deliveries with the same dedup key are started, both match and block on the lock, and the
                // holder commits
                Future<Delivery> first = f.startDelivery(node, order("PAY-77", "d-1"));
                Future<Delivery> second = f.startDelivery(node, order("PAY-77", "d-1"));
                awaitCondition(
                        "both deliveries to wait for the execution lock",
                        IDLE,
                        () -> f.fixture.ungrantedAdvisoryLocks() == 2);
                holder.commit();
                Delivery firstResult = await(first);
                Delivery secondResult = await(second);

                // Then one receipt is RECORDED and one is DUPLICATE
                assertThat(List.of(
                                firstResult.receiptOrFail().outcome(),
                                secondResult.receiptOrFail().outcome()))
                        .containsExactlyInAnyOrder(
                                CorrelatedSignalReceipt.Outcome.RECORDED, CorrelatedSignalReceipt.Outcome.DUPLICATE);

                // And one command and one dedup row exist, and one signal is open
                assertThat(f.signalCommands(id)).hasSize(1);
                assertThat(f.dedupRows()).hasSize(1);
                assertThat(f.openSignals(id)).isEqualTo(1);
            } finally {
                holder.release();
            }
        }
    }

    @Test
    @DisplayName("a wait that closes between the match and the record answers no-match and writes nothing")
    void waitClosedBetweenMatchAndRecordAnswersNoMatchAndWritesNothing() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given one instance waiting on PAY-77, a second transaction holding its execution lock, and a delivery
            // started so that it matches and blocks on the lock
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startKeyed(node, "PAY-77");
            idle(node, id);
            Written before = f.written();
            ExecutorFixture.HeldLock holder = f.fixture.holdExecutionLock(id);
            try {
                Future<Delivery> delivery = f.startDelivery(node, order("PAY-77", "d-1"));
                awaitCondition(
                        "the delivery to wait for the execution lock",
                        IDLE,
                        () -> f.fixture.ungrantedAdvisoryLocks() == 1);

                // When the holder deletes the wait's record, as the instance leaving the wait would, and commits
                holder.execute("DELETE FROM workflow_signal_waits WHERE instance_id = $1", Tuple.of(id.value()));
                holder.commit();

                // Then the delivery fails with no-match, and no command and no dedup row exist
                assertThat(await(delivery).failureOrFail()).isInstanceOf(WorkflowSignalNoMatchException.class);
                assertThat(f.written()).isEqualTo(before);
                assertThat(f.dedupRows()).isEmpty();
            } finally {
                holder.release();
            }
        }
    }

    @Test
    @DisplayName("two attempts of one event that matched two instances record one command")
    void twoAttemptsOfOneEventThatMatchedTwoInstancesRecordOneCommand() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given instance A waiting on PAY-A and instance B on PAY-B, and an engine whose command repository holds
            // each recording, under its own instance's lock and after its second dedup find, until both reached that
            // point
            AfterSecondFindGate afterSecondFind = new AfterSecondFindGate(f.fixture.vertx(), 2, Duration.ofSeconds(10));
            Node node = f.node("engine").commands(afterSecondFind.hold()).build();
            WorkflowInstanceId a = f.start(node, KeyedWaitFixture.ORDER, "PAY-A", "acme");
            WorkflowInstanceId b = f.start(node, KeyedWaitFixture.ORDER, "PAY-B", "globex");
            idle(node, a, b);

            // When two deliveries with the same dedup key start in separate transactions, one on each key
            Future<Delivery> viaA = f.startDelivery(node, order("PAY-A", "d-1"));
            Future<Delivery> viaB = f.startDelivery(node, order("PAY-B", "d-1"));
            Delivery resultViaA = await(viaA, Duration.ofSeconds(30));
            Delivery resultViaB = await(viaB, Duration.ofSeconds(30));

            // Then no attempt failed, so none ended with a database error
            assertThat(resultViaA.failure()).isNull();
            assertThat(resultViaB.failure()).isNull();

            // And exactly one receipt is RECORDED and the other DUPLICATE with a null definition id
            List<CorrelatedSignalReceipt> receipts = List.of(resultViaA.receiptOrFail(), resultViaB.receiptOrFail());
            assertThat(receipts)
                    .extracting(CorrelatedSignalReceipt::outcome)
                    .containsExactlyInAnyOrder(
                            CorrelatedSignalReceipt.Outcome.RECORDED, CorrelatedSignalReceipt.Outcome.DUPLICATE);
            assertThat(receipts.stream()
                            .filter(receipt -> receipt.outcome() == CorrelatedSignalReceipt.Outcome.DUPLICATE)
                            .map(CorrelatedSignalReceipt::definitionId))
                    .containsOnlyNulls();

            // And across A and B exactly one command and one correlated row exist, the row naming the instance that
            // took the command
            List<WorkflowInstanceId> withCommand = List.of(a, b).stream()
                    .filter(id -> !f.signalCommands(id).isEmpty())
                    .toList();
            assertThat(withCommand).hasSize(1);
            WorkflowInstanceId recorded = withCommand.getFirst();
            WorkflowInstanceId other = recorded.equals(a) ? b : a;
            assertThat(f.signalCommands(recorded)).hasSize(1);
            assertThat(f.dedupRows()).singleElement().satisfies(row -> assertThat(row.workflowId())
                    .isEqualTo(recorded.value()));

            // And the other instance has no command and no open signal, so the slot its attempt took was lowered
            assertThat(f.signalCommands(other)).isEmpty();
            assertThat(f.openSignals(other)).isZero();
            assertThat(f.openSignals(recorded)).isEqualTo(1);
        }
    }

    // --- Rejection at the next wait ---

    @Test
    @DisplayName("a redelivery of a command rejected at a bad-key wait answers duplicate")
    void redeliveryOfACommandRejectedAtABadKeyWaitAnswersDuplicate() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given an instance at the keyed wait-a whose application reaches wait-b with a blank computed key
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.start(node, KeyedWaitFixture.CHAIN, "PAY-1", KeyedWaitFixture.SCOPE);
            node.startExecutor();
            node.awaitIdle(id);
            JsonObject snapshotBefore = f.snapshot(id);
            CorrelatedSignal signal =
                    CorrelatedSignal.of(ChainContract.class, "a-done", "PAY-1", new Payment("   "), "d-1");

            // When the delivery is recorded and the command runs and is rejected
            assertThat(f.deliver(node, signal).receiptOrFail().outcome())
                    .isEqualTo(CorrelatedSignalReceipt.Outcome.RECORDED);
            node.awaitIdle(id);

            // Then the command is REJECTED and the instance is unchanged
            assertThat(f.signalCommands(id)).singleElement().satisfies(command -> assertThat(command.state())
                    .isEqualTo("REJECTED"));
            assertThat(f.snapshot(id)).isEqualTo(snapshotBefore);
            Written before = f.written();

            // And the same delivery made again answers DUPLICATE and adds no command, and the correlated row stays
            CorrelatedSignalReceipt again = f.deliver(node, signal).receiptOrFail();
            node.awaitIdle(id);
            assertThat(again.outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.DUPLICATE);
            assertThat(f.written()).isEqualTo(before);
            assertThat(f.dedupRows()).singleElement().satisfies(row -> assertThat(row.workflowId())
                    .isEqualTo(id.value()));
        }
    }

    // --- What never carries the key ---

    @Test
    @DisplayName("errors, logs and rows never carry the key")
    void errorsLogsAndRowsNeverCarryTheKey() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given a distinctive key SECRET-KEY-31 on a wait in each of two scopes, and an unmatched key
            // SECRET-MISS-32
            String secret = "SECRET-KEY-31";
            String miss = "SECRET-MISS-32";
            Node node = f.node("engine").build();
            WorkflowInstanceId acme = f.start(node, KeyedWaitFixture.ORDER, secret, "acme");
            WorkflowInstanceId globex = f.start(node, KeyedWaitFixture.ORDER, secret, "globex");
            idle(node, acme, globex);
            List<String> historyBefore = historyOf(f, acme, globex);

            // When a no-match, an ambiguity, a recorded delivery and a duplicate are produced
            Delivery noMatch = f.deliver(node, order(miss, "d-1"));
            Delivery ambiguous = f.deliver(node, order(secret, "d-2"));
            Delivery recorded = f.deliver(node, order(secret, "d-3").withScope("acme"));
            Delivery duplicate = f.deliver(node, order(secret, "d-3").withScope("acme"));
            assertThat(recorded.receiptOrFail().outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.RECORDED);
            assertThat(duplicate.receiptOrFail().outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.DUPLICATE);

            // And each exception is rendered through getMessage, toString and printStackTrace
            Throwable noMatchFailure = noMatch.failureOrFail();
            Throwable ambiguousFailure = ambiguous.failureOrFail();
            assertThat(noMatchFailure).isInstanceOf(WorkflowSignalNoMatchException.class);
            assertThat(ambiguousFailure).isInstanceOf(WorkflowSignalAmbiguousException.class);
            List<String> renderings = new ArrayList<>();
            for (Throwable failure : List.of(noMatchFailure, ambiguousFailure)) {
                renderings.add(String.valueOf(failure.getMessage()));
                renderings.add(failure.toString());
                renderings.add(stackTraceOf(failure));
            }

            // Then neither key appears in any rendering, log line, history written since, dedup row or signal command
            // row
            assertNoKeyAnywhere(f, renderings, historyBefore, acme, globex, secret, miss);

            // And the exceptions expose contractName() and signalName() only
            for (Class<?> type :
                    List.of(WorkflowSignalNoMatchException.class, WorkflowSignalAmbiguousException.class)) {
                assertThat(Modifier.isFinal(type.getModifiers())).isTrue();
                assertThat(java.util.Arrays.stream(type.getDeclaredMethods())
                                .filter(method -> Modifier.isPublic(method.getModifiers()))
                                .map(Method::getName))
                        .containsExactlyInAnyOrder("contractName", "signalName");
            }
        }
    }

    // --- The entry: client guard and history report ---

    /** The three clients that are not a caller's open transaction. */
    enum RejectedClient {
        /** The pool itself. */
        POOL {
            @Override
            SqlClient open(Node node) {
                return node.pool;
            }

            @Override
            void release(SqlClient client) {}
        },
        /** A connection taken from the pool with no transaction begun. */
        CONNECTION_WITHOUT_TRANSACTION {
            @Override
            SqlClient open(Node node) {
                return await(node.pool.getConnection());
            }

            @Override
            void release(SqlClient client) {
                await(((SqlConnection) client).close());
            }
        },
        /** A connection on which the test issued a textual BEGIN. */
        CONNECTION_WITH_TEXTUAL_BEGIN {
            @Override
            SqlClient open(Node node) {
                SqlConnection connection = await(node.pool.getConnection());
                await(connection.query("BEGIN").execute());
                return connection;
            }

            @Override
            void release(SqlClient client) {
                SqlConnection connection = (SqlConnection) client;
                await(connection.query("ROLLBACK").execute());
                await(connection.close());
            }
        };

        abstract SqlClient open(Node node);

        abstract void release(SqlClient client);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(RejectedClient.class)
    @DisplayName("a client that is not a caller's open transaction is rejected before the dedup find")
    void clientThatIsNotACallersOpenTransactionIsRejectedBeforeTheDedupFind(RejectedClient kind) {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given one instance waiting on PAY-77 whose delivery with dedup key d-1 was recorded and applied, so that
            // a correlated row exists, and the row counts as they stand
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startKeyed(node, "PAY-77");
            node.startExecutor();
            node.awaitIdle(id);
            f.deliver(node, order("PAY-77", "d-1")).receiptOrFail();
            node.awaitIdle(id);
            assertThat(f.dedupRows()).hasSize(1);
            Written before = f.written();

            // When the d-1 delivery is made again through the client, and an unmatched delivery (PAY-99, d-2) is made
            // through it
            SqlClient client = kind.open(node);
            Throwable replay;
            Throwable unmatched;
            try {
                replay = failureOf(
                        f.caller(() -> node.engine.signalByKey(order("PAY-77", "d-1"), client)),
                        Duration.ofSeconds(15));
                unmatched = failureOf(
                        f.caller(() -> node.engine.signalByKey(order("PAY-99", "d-2"), client)),
                        Duration.ofSeconds(15));
            } finally {
                kind.release(client);
            }

            // Then both futures fail with IllegalArgumentException, not with a DUPLICATE receipt and not with a
            // no-match, so no read happened before the guard
            assertThat(replay).isInstanceOf(IllegalArgumentException.class);
            assertThat(unmatched).isInstanceOf(IllegalArgumentException.class);

            // And the row counts and the open signals are as before
            assertThat(f.written()).isEqualTo(before);

            // And the same d-1 delivery through a real caller transaction still answers DUPLICATE
            assertThat(f.deliver(node, order("PAY-77", "d-1")).receiptOrFail().outcome())
                    .isEqualTo(CorrelatedSignalReceipt.Outcome.DUPLICATE);
        }
    }

    @Test
    @DisplayName("refused and recorded deliveries are reported to the history listener once")
    void refusedAndRecordedDeliveriesAreReportedToTheHistoryListenerOnce() {
        RecordedHistory recorded = new RecordedHistory();
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given a history listener bound to the engine, one instance waiting on PAY-77, and two instances in scopes
            // acme and globex waiting on PAY-55
            Node node = f.node("engine").historyListeners(Set.of(recorded)).build();
            WorkflowInstanceId single = f.start(node, KeyedWaitFixture.ORDER, "PAY-77", "acme");
            WorkflowInstanceId inAcme = f.start(node, KeyedWaitFixture.ORDER, "PAY-55", "acme");
            WorkflowInstanceId inGlobex = f.start(node, KeyedWaitFixture.ORDER, "PAY-55", "globex");
            idle(node, single, inAcme, inGlobex);
            awaitCondition("the start announcements to catch up", IDLE, () -> announced(recorded, single)
                    .equals(stored(f, single)));
            assertThat(recorded.failures()).isEmpty();

            // When, in separate caller transactions, PAY-99 (no match), PAY-55 (ambiguous) and PAY-77 (recorded) are
            // delivered, the recorded one committed and applied, and then delivered again (duplicate)
            Delivery noMatch = f.deliver(node, order("PAY-99", "d-1"));
            Delivery ambiguous = f.deliver(node, order("PAY-55", "d-2"));
            Delivery accepted = f.deliver(node, order("PAY-77", "d-3"));
            idle(node, single);
            awaitCondition("the announcements to catch up", IDLE, () -> announced(recorded, single)
                    .equals(stored(f, single)));
            int changesBeforeDuplicate = recorded.changes().size();
            Delivery duplicate = f.deliver(node, order("PAY-77", "d-3"));
            idle(node, single);

            // Then the listener holds exactly two failures, both of action SIGNAL, one of each refusal type, each with
            // its own attempt id
            assertThat(noMatch.failureOrFail()).isInstanceOf(WorkflowSignalNoMatchException.class);
            assertThat(ambiguous.failureOrFail()).isInstanceOf(WorkflowSignalAmbiguousException.class);
            awaitCondition(
                    "both refusals to be reported",
                    IDLE,
                    () -> recorded.failures().size() >= 2);
            List<WorkflowHistoryFailure> failures = recorded.failures();
            assertThat(failures).hasSize(2);
            assertThat(failures).allSatisfy(failure -> assertThat(failure.action())
                    .isEqualTo(WorkflowHistoryFailure.Action.SIGNAL));
            assertThat(failures)
                    .extracting(WorkflowHistoryFailure::errorType)
                    .containsExactlyInAnyOrder("WorkflowSignalNoMatchException", "WorkflowSignalAmbiguousException");
            assertThat(failures.stream().map(WorkflowHistoryFailure::attemptId).distinct())
                    .hasSize(2);

            // And the text of neither contains the keys
            for (WorkflowHistoryFailure failure : failures) {
                assertThat(failure.toString()).doesNotContain("PAY-99", "PAY-55");
            }

            // And the recorded delivery announced the history entries it caused once and in order
            assertThat(accepted.receiptOrFail().outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.RECORDED);
            assertThat(announced(recorded, single)).isEqualTo(stored(f, single)).doesNotHaveDuplicates();
            assertThat(announced(recorded, single)).isSorted();

            // And the duplicate reports no failure and announces nothing further
            assertThat(duplicate.receiptOrFail().outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.DUPLICATE);
            assertThat(recorded.failures()).hasSize(2);
            assertThat(recorded.changes()).hasSize(changesBeforeDuplicate);
        }
    }

    @Test
    @DisplayName("a failed instance holding an open record takes the delivery and keeps it unapplied")
    void failedInstanceHoldingAnOpenRecordTakesTheDeliveryAndKeepsItUnapplied() {
        try (KeyedWaitFixture f = KeyedWaitFixture.openWithExits(db.toPoolConfig())) {
            // Given an instance that failed while holding its keyed wait on PAY-77, and executors stopped
            Node node = f.node("engine").singleSegments().build();
            WorkflowInstanceId id = f.start(node, KeyedWaitFixture.BREAKABLE_TIMEOUT, "PAY-77", "acme");
            idle(node, id);
            f.fireTimeout(id);
            idle(node, id);
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            List<WaitRecord> recordsBefore = f.waitRecords(id);
            assertThat(recordsBefore).hasSize(1);

            // When a delivery on PAY-77 is recorded in a caller transaction that commits
            Delivery delivery = f.deliver(node, order("PAY-77", "d-1"));

            // Then the receipt is RECORDED and carries the instance's definition id, and it was not a no-match
            CorrelatedSignalReceipt receipt = delivery.receiptOrFail();
            assertThat(receipt.outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.RECORDED);
            assertThat(receipt.definitionId()).isEqualTo(KeyedWaitFixture.BREAKABLE_TIMEOUT);

            // And one SIGNAL command exists for the instance, neither completed nor rejected
            assertThat(f.signalCommands(id)).singleElement().satisfies(command -> assertThat(command.state())
                    .isNotIn("COMPLETE", "REJECTED"));

            // And one correlated dedup row names the instance
            assertThat(f.dedupRows()).singleElement().satisfies(row -> assertThat(row.workflowId())
                    .isEqualTo(id.value()));

            // And the instance is still FAILED and its open record is unchanged
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
            assertThat(f.waitRecords(id)).isEqualTo(recordsBefore);
        }
    }

    // --- Helpers ---

    private static CorrelatedSignal order(String key, String dedupKey) {
        return CorrelatedSignal.of(OrderContract.class, SIGNAL, key, new Payment("txn-1"), dedupKey);
    }

    /** Starts the executor, waits until every instance is idle, and stops the executor. */
    private static void idle(Node node, WorkflowInstanceId... ids) {
        node.startExecutor();
        try {
            for (WorkflowInstanceId id : ids) {
                node.awaitIdle(id);
            }
        } finally {
            node.stopExecutor();
        }
    }

    private static WaitRecord only(List<WaitRecord> records) {
        assertThat(records).hasSize(1);
        return records.getFirst();
    }

    /** Leaves the record of an ended instance behind, as the cancel of an older node would have left it. */
    private static void leaveStrayRecord(KeyedWaitFixture f, WorkflowInstanceId id, WaitRecord record) {
        await(f.fixture
                .observer()
                .preparedQuery("INSERT INTO workflow_signal_waits (wait_id, instance_id, step_id, fork_step_id,"
                        + " branch_id, scope_key, contract_name, definition_id, signal_name, correlation_key,"
                        + " created_at) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, clock_timestamp())")
                .execute(
                        Tuple.of(UUID.randomUUID(), id.value(), record.stepId(), record.forkStepId(), record.branchId())
                                .addString(record.scopeKey())
                                .addString(record.contractName())
                                .addString(record.definitionId())
                                .addString(record.signalName())
                                .addString(record.correlationKey())));
    }

    private static List<String> historyOf(KeyedWaitFixture f, WorkflowInstanceId... ids) {
        List<String> texts = new ArrayList<>();
        for (WorkflowInstanceId id : ids) {
            texts.addAll(f.fixture.historyTextsOf(id));
        }
        return texts;
    }

    /**
     * Fails when a key appears in a rendering of an exception, in a log line, in a history entry written after the
     * given entries, in a correlated dedup row, or in a signal command row.
     */
    private static void assertNoKeyAnywhere(
            KeyedWaitFixture f,
            List<String> renderings,
            List<String> historyBefore,
            WorkflowInstanceId first,
            WorkflowInstanceId second,
            String... keys) {
        List<String> history = new ArrayList<>(historyOf(f, first, second));
        history.removeAll(historyBefore);
        StringBuilder rows = new StringBuilder();
        for (DedupRow row : f.dedupRows()) {
            rows.append(row).append('\n');
        }
        for (Row row : await(f.fixture
                .observer()
                .query("SELECT to_jsonb(c)::text AS row_text FROM workflow_commands c WHERE kind = 'SIGNAL'")
                .execute())) {
            rows.append(row.getString("row_text")).append('\n');
        }
        for (String key : keys) {
            assertThat(renderings).as("exception renderings for %s", key).noneMatch(text -> text.contains(key));
            assertThat(f.loggedText(Level.TRACE)).as("log lines for %s", key).doesNotContain(key);
            assertThat(history).as("history written since, for %s", key).noneMatch(text -> text.contains(key));
            assertThat(rows.toString()).as("dedup and command rows for %s", key).doesNotContain(key);
        }
    }

    private static String stackTraceOf(Throwable failure) {
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static List<Long> announced(RecordedHistory recorded, WorkflowInstanceId id) {
        return recorded.eventsOf(id).stream()
                .map(event -> event.entryInfo().sequence())
                .collect(Collectors.toList());
    }

    /** The sequences of the instance's history, without the state baselines that are not announced. */
    private static List<Long> stored(KeyedWaitFixture f, WorkflowInstanceId id) {
        List<Long> sequences = new ArrayList<>();
        for (Row row : await(f.fixture
                .observer()
                .preparedQuery("SELECT sequence FROM workflow_history WHERE workflow_id = $1"
                        + " AND entry_type <> 'STATE_BASELINE' ORDER BY sequence")
                .execute(Tuple.of(id.value())))) {
            sequences.add(row.getLong("sequence"));
        }
        return sequences;
    }

    /**
     * Holds each recording of a signal after its second dedup find, under its instance's execution lock, until the given
     * number of recordings arrived there, and fails the attempts when they do not all arrive within the bound. The hold
     * sits on the open-signal raise, which follows the second find and precedes the claim.
     */
    private static final class AfterSecondFindGate {
        private final Promise<Void> open = Promise.promise();
        private final AtomicInteger arrived = new AtomicInteger();
        private final int parties;

        AfterSecondFindGate(Vertx vertx, int parties, Duration bound) {
            this.parties = parties;
            vertx.setTimer(
                    bound.toMillis(),
                    timer -> open.tryFail(new AssertionError("only " + arrived.get() + " of " + parties
                            + " recordings reached the hold after the second dedup find within " + bound)));
        }

        @SuppressWarnings("unchecked")
        UnaryOperator<WorkflowCommandRepository<SqlClient>> hold() {
            return ExecutorFixture.intercepting("raiseOpenSignals", (call, member, arguments, real) -> {
                if (arrived.incrementAndGet() == parties) {
                    open.tryComplete();
                }
                return open.future().<Object>compose(released -> {
                    try {
                        return (Future<Object>) real.call();
                    } catch (Exception failure) {
                        return Future.failedFuture(failure);
                    }
                });
            });
        }
    }
}
