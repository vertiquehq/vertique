// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.failureOf;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.exception.WorkflowSignalAmbiguousException;
import dev.vertique.workflow.exception.WorkflowSignalNoMatchException;
import dev.vertique.workflow.history.WorkflowHistoryFailure;
import dev.vertique.workflow.ops.CorrelatedSignal;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.CorrelatedDeliveryIT.RejectedClient;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.ApprovalContract;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.Delivery;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.ForkContract;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.Payment;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.SignalCommand;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.Written;
import dev.vertique.workflow.postgresql.history.RecordedHistory;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Verifies that a delivery by business key reaches the one live instance that carries the key and whose pinned
 * definition names the contract and declares the signal for the instance, parks for an instance that is not yet at its
 * wait, and gives every delivery one defined outcome at record time, a refusal writing nothing; and that the entry
 * keeps its client guard and its history report.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class BusinessKeyDeliveryIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_business_key_delivery_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String SIGNAL = "approved";

    // --- Which instance receives the delivery ---

    @Test
    @DisplayName("a signal for an instance that has not run its start parks and applies when its wait is reached")
    void signalForAPendingInstanceParksAndAppliesWhenItsWaitIsReached() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given an instance with business key PO-42 whose start is recorded and not yet run, and executors stopped
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startPending(node, "PO-42");
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("PENDING");

            // When approved is delivered by business key PO-42, the executors start and the instance is idle, and then
            // received is delivered by instance id and the executors run to idle again
            Delivery delivery = f.deliverByBusinessKey(node, approval("PO-42", "d-1"));
            f.idle(node, id);
            List<SignalCommand> afterFirstIdle = f.signalCommands(id);
            String stepAfterFirstIdle = f.snapshot(id).getString("current_step_id");
            f.signalInstance(node, id, "received", new Payment("r-1"), "r-1");
            f.idle(node, id);

            // Then the receipt is RECORDED and the command's target is exactly the name
            assertThat(delivery.receiptOrFail().outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.RECORDED);
            assertThat(afterFirstIdle).singleElement().satisfies(command -> assertThat(command.target())
                    .isEqualTo(new JsonObject().put("name", SIGNAL)));

            // And after the first idle the command is PARKED while the instance waits at the first wait
            assertThat(afterFirstIdle).extracting(SignalCommand::state).containsExactly("PARKED");
            assertThat(stepAfterFirstIdle).isEqualTo("wait-received");

            // And after the second the command is COMPLETE, the instance is past the second wait and its history holds
            // SIGNAL_RECEIVED for approved
            assertThat(f.signalCommands(id)).extracting(SignalCommand::state).containsExactly("COMPLETE", "COMPLETE");
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("COMPLETED");
            assertThat(f.fixture.historyTextsOf(id))
                    .filteredOn(text -> text.startsWith("SIGNAL_RECEIVED") && text.contains("\"" + SIGNAL + "\""))
                    .hasSize(1);

            // And one correlated dedup row exists for the instance
            assertThat(f.dedupRows()).singleElement().satisfies(row -> assertThat(row.workflowId())
                    .isEqualTo(id.value()));
        }
    }

    @Test
    @DisplayName("a signal awaited only inside a branch is no match by business key")
    void signalAwaitedOnlyInsideABranchIsNoMatchByBusinessKey() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given a live instance with business key PO-42 whose pinned version awaits shipment-confirmed only at a
            // wait inside a fork branch, so its definition row lists the name under that branch and not among the
            // instance names
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startForkWithBusinessKey(node, KeyedWaitFixture.FORK, "PO-42");
            f.idle(node, id);
            assertThat(f.waitRecords(id)).hasSize(2);
            Written before = f.written();
            JsonObject snapshotBefore = f.snapshot(id);

            // When shipment-confirmed is delivered by business key PO-42
            Delivery delivery = f.deliverByBusinessKey(
                    node,
                    CorrelatedSignal.of(ForkContract.class, "shipment-confirmed", "PO-42", new Payment("s-1"), "d-1"));

            // Then the future fails with the no-match naming contract and signal
            assertThat(delivery.failureOrFail())
                    .isInstanceOfSatisfying(WorkflowSignalNoMatchException.class, failure -> {
                        assertThat(failure.contractName()).isEqualTo(f.contractNameOf(KeyedWaitFixture.FORK));
                        assertThat(failure.signalName()).isEqualTo("shipment-confirmed");
                    });

            // And nothing is written, and the instance is unchanged
            assertThat(f.written()).isEqualTo(before);
            assertThat(f.dedupRows()).isEmpty();
            assertThat(f.signalCommands(id)).isEmpty();
            assertThat(f.snapshot(id)).isEqualTo(snapshotBefore);
        }
    }

    @Test
    @DisplayName("only the instance whose version declares the signal is matched")
    void onlyTheInstanceWhoseVersionDeclaresTheSignalIsMatched() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given two live instances with business key PO-42 in scopes acme and globex, of two definitions of one
            // contract: the first definition's row declares approved among its instance names and the second's does not
            Node node = f.node("engine").build();
            WorkflowInstanceId declaring = f.startWithBusinessKey(node, KeyedWaitFixture.APPROVAL, "PO-42", "acme");
            WorkflowInstanceId silent =
                    f.startWithBusinessKey(node, KeyedWaitFixture.APPROVAL_NARROW, "PO-42", "globex");
            f.idle(node, declaring, silent);
            JsonObject silentBefore = f.snapshot(silent);

            // When approved is delivered by business key with no narrowing
            Delivery delivery = f.deliverByBusinessKey(node, approval("PO-42", "d-1"));

            // Then the first instance receives it, and the delivery is not ambiguous
            CorrelatedSignalReceipt receipt = delivery.receiptOrFail();
            assertThat(receipt.outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.RECORDED);
            assertThat(receipt.definitionId()).isEqualTo(KeyedWaitFixture.APPROVAL);
            assertThat(f.signalCommands(declaring)).hasSize(1);

            // And the second is unchanged
            assertThat(f.signalCommands(silent)).isEmpty();
            assertThat(f.snapshot(silent)).isEqualTo(silentBefore);
        }
    }

    @Test
    @DisplayName("a definition row without a name list counts as declaring the signal")
    void definitionRowWithoutANameListCountsAsDeclaringTheSignal() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given one live instance with business key PO-42 whose definition row has no name list, as a row written
            // before the column existed has none
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startPending(node, "PO-42");
            f.idle(node, id);
            f.setSignalNames(KeyedWaitFixture.APPROVAL, null);

            // When approved is delivered by business key
            Delivery delivery = f.deliverByBusinessKey(node, approval("PO-42", "d-1"));

            // Then the receipt is RECORDED and the command exists
            assertThat(delivery.receiptOrFail().outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.RECORDED);
            assertThat(f.signalCommands(id)).hasSize(1);
        }
    }

    // --- Ambiguity and no match ---

    @Test
    @DisplayName("two live instances on one business key are ambiguous until the delivery is narrowed")
    void twoLiveInstancesOnOneBusinessKeyAreAmbiguousUntilNarrowed() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given two live instances with business key PO-42, in scopes acme and globex, of a definition that
            // declares approved
            Node node = f.node("engine").build();
            WorkflowInstanceId inAcme = f.startWithBusinessKey(node, KeyedWaitFixture.APPROVAL, "PO-42", "acme");
            WorkflowInstanceId inGlobex = f.startWithBusinessKey(node, KeyedWaitFixture.APPROVAL, "PO-42", "globex");
            f.idle(node, inAcme, inGlobex);
            Written before = f.written();
            JsonObject acmeBefore = f.snapshot(inAcme);
            JsonObject globexBefore = f.snapshot(inGlobex);

            // When approved is delivered with no narrowing, then with the scope globex under a fresh dedup key
            Delivery unnarrowed = f.deliverByBusinessKey(node, approval("PO-42", "d-1"));
            Written afterUnnarrowed = f.written();
            Delivery narrowed =
                    f.deliverByBusinessKey(node, approval("PO-42", "d-2").withScope("globex"));

            // Then the first fails with the ambiguity naming the contract and the signal, and writes nothing
            assertThat(unnarrowed.failureOrFail())
                    .isInstanceOfSatisfying(WorkflowSignalAmbiguousException.class, failure -> {
                        assertThat(failure.contractName()).isEqualTo(f.contractNameOf(KeyedWaitFixture.APPROVAL));
                        assertThat(failure.signalName()).isEqualTo(SIGNAL);
                    });
            assertThat(afterUnnarrowed).isEqualTo(before);
            assertThat(f.snapshot(inAcme)).isEqualTo(acmeBefore);
            assertThat(f.snapshot(inGlobex)).isEqualTo(globexBefore);

            // And the second records for the globex instance only
            CorrelatedSignalReceipt receipt = narrowed.receiptOrFail();
            assertThat(receipt.outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.RECORDED);
            assertThat(f.signalCommands(inGlobex)).hasSize(1);
            assertThat(f.signalCommands(inAcme)).isEmpty();
            assertThat(f.dedupRows()).singleElement().satisfies(row -> assertThat(row.workflowId())
                    .isEqualTo(inGlobex.value()));
        }
    }

    @Test
    @DisplayName("an ended instance and an instance of another contract do not match")
    void endedInstanceAndInstanceOfAnotherContractDoNotMatch() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given business key PO-42 held by a completed instance of the contract, and business key PO-43 held by a
            // live instance of another contract that also declares approved
            Node node = f.node("engine").build();
            WorkflowInstanceId ended = f.startPending(node, "PO-42");
            f.idle(node, ended);
            f.signalInstance(node, ended, "received", new Payment("r-1"), "r-1");
            f.signalInstance(node, ended, SIGNAL, new Payment("a-1"), "a-1");
            f.idle(node, ended);
            assertThat(f.fixture.instanceOf(ended).status()).isEqualTo("COMPLETED");
            WorkflowInstanceId other = f.startWithBusinessKey(node, KeyedWaitFixture.AUDIT, "PO-43", "acme");
            f.idle(node, other);
            Written before = f.written();

            // When approved is delivered by PO-42 and by PO-43
            Delivery byEnded = f.deliverByBusinessKey(node, approval("PO-42", "d-1"));
            Delivery byOther = f.deliverByBusinessKey(node, approval("PO-43", "d-2"));

            // Then each fails with the no-match naming contract and signal
            for (Delivery delivery : List.of(byEnded, byOther)) {
                assertThat(delivery.failureOrFail())
                        .isInstanceOfSatisfying(WorkflowSignalNoMatchException.class, failure -> {
                            assertThat(failure.contractName()).isEqualTo(f.contractNameOf(KeyedWaitFixture.APPROVAL));
                            assertThat(failure.signalName()).isEqualTo(SIGNAL);
                        });
            }

            // And nothing is written
            assertThat(f.written()).isEqualTo(before);
            assertThat(f.dedupRows()).isEmpty();
            assertThat(f.signalCommands(other)).isEmpty();
        }
    }

    // --- Duplicates ---

    @Test
    @DisplayName("a redelivery answers duplicate also when a later instance holds the business key")
    void redeliveryAnswersDuplicateAlsoWhenALaterInstanceHoldsTheBusinessKey() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given instance A with business key PO-42 that received approved under dedup key d-1 and completed, and
            // then instance B started in another scope with the same business key, live and declaring approved
            Node node = f.node("engine").build();
            WorkflowInstanceId a = f.startWithBusinessKey(node, KeyedWaitFixture.APPROVAL, "PO-42", "acme");
            f.idle(node, a);
            f.deliverByBusinessKey(node, approval("PO-42", "d-1")).receiptOrFail();
            f.signalInstance(node, a, "received", new Payment("r-1"), "r-1");
            f.idle(node, a);
            assertThat(f.fixture.instanceOf(a).status()).isEqualTo("COMPLETED");
            WorkflowInstanceId b = f.startWithBusinessKey(node, KeyedWaitFixture.APPROVAL, "PO-42", "globex");
            f.idle(node, b);
            Written before = f.written();
            JsonObject bBefore = f.snapshot(b);

            // When the delivery d-1 is redelivered by PO-42
            CorrelatedSignalReceipt receipt =
                    f.deliverByBusinessKey(node, approval("PO-42", "d-1")).receiptOrFail();

            // Then the receipt is DUPLICATE with a null definition id
            assertThat(receipt.outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.DUPLICATE);
            assertThat(receipt.definitionId()).isNull();

            // And B has no command and is unchanged, and nothing is written
            assertThat(f.signalCommands(b)).isEmpty();
            assertThat(f.snapshot(b)).isEqualTo(bBefore);
            assertThat(f.written()).isEqualTo(before);
            assertThat(f.dedupRows()).singleElement().satisfies(row -> assertThat(row.workflowId())
                    .isEqualTo(a.value()));
        }
    }

    // --- The entry: client guard and history report ---

    @ParameterizedTest(name = "{0}")
    @EnumSource(RejectedClient.class)
    @DisplayName("a client that is not a caller's open transaction is rejected before the dedup find")
    void clientThatIsNotACallersOpenTransactionIsRejectedBeforeTheDedupFind(RejectedClient kind) {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given a live instance with business key PO-42 that received approved under dedup key d-1, so that a
            // correlated row exists, and the row counts as they stand
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startPending(node, "PO-42");
            f.idle(node, id);
            f.deliverByBusinessKey(node, approval("PO-42", "d-1")).receiptOrFail();
            assertThat(f.dedupRows()).hasSize(1);
            Written before = f.written();

            // When the d-1 delivery is made again through the client, and an unmatched delivery (PO-99, d-2) is made
            // through it
            SqlClient client = kind.open(node);
            Throwable replay;
            Throwable unmatched;
            try {
                replay = failureOf(
                        f.caller(() -> node.engine.signalByBusinessKey(approval("PO-42", "d-1"), client)),
                        Duration.ofSeconds(15));
                unmatched = failureOf(
                        f.caller(() -> node.engine.signalByBusinessKey(approval("PO-99", "d-2"), client)),
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
            assertThat(f.deliverByBusinessKey(node, approval("PO-42", "d-1"))
                            .receiptOrFail()
                            .outcome())
                    .isEqualTo(CorrelatedSignalReceipt.Outcome.DUPLICATE);
        }
    }

    @Test
    @DisplayName("refused and recorded deliveries are reported to the history listener once")
    void refusedAndRecordedDeliveriesAreReportedToTheHistoryListenerOnce() {
        RecordedHistory recorded = new RecordedHistory();
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given a history listener bound to the engine, a live instance with business key PO-42, and two live
            // instances with business key PO-43 in scopes acme and globex, all declaring approved
            Node node = f.node("engine").historyListeners(Set.of(recorded)).build();
            WorkflowInstanceId single = f.startWithBusinessKey(node, KeyedWaitFixture.APPROVAL, "PO-42", "acme");
            WorkflowInstanceId inAcme = f.startWithBusinessKey(node, KeyedWaitFixture.APPROVAL, "PO-43", "acme");
            WorkflowInstanceId inGlobex = f.startWithBusinessKey(node, KeyedWaitFixture.APPROVAL, "PO-43", "globex");
            f.idle(node, single, inAcme, inGlobex);
            awaitCondition(
                    "the start announcements to catch up", IDLE, () -> caughtUp(f, recorded, single, inAcme, inGlobex));
            assertThat(recorded.failures()).isEmpty();
            int announcedBefore = recorded.eventsOf(single).size();

            // When, in separate caller transactions, PO-99 (no match), PO-43 (ambiguous) and PO-42 (recorded) are
            // delivered, the recorded one committed and applied, and then delivered again (duplicate)
            Delivery noMatch = f.deliverByBusinessKey(node, approval("PO-99", "d-1"));
            Delivery ambiguous = f.deliverByBusinessKey(node, approval("PO-43", "d-2"));
            AtomicInteger announcedBeforeCommit = new AtomicInteger(-1);
            Delivery accepted = await(f.startBusinessKeyDelivery(
                    node,
                    approval("PO-42", "d-3"),
                    () -> announcedBeforeCommit.set(recorded.eventsOf(single).size())));
            f.idle(node, single);
            awaitCondition(
                    "the announcements to catch up", IDLE, () -> caughtUp(f, recorded, single, inAcme, inGlobex));
            int changesBeforeDuplicate = recorded.changes().size();
            Delivery duplicate = f.deliverByBusinessKey(node, approval("PO-42", "d-3"));
            f.idle(node, single);

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
                assertThat(failure.toString()).doesNotContain("PO-99", "PO-43");
            }

            // And the recorded delivery announced the history entries it caused once and in order, and nothing before
            // the caller committed
            assertThat(accepted.receiptOrFail().outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.RECORDED);
            assertThat(announcedBeforeCommit.get()).isEqualTo(announcedBefore);
            assertThat(announced(recorded, single)).isEqualTo(stored(f, single)).doesNotHaveDuplicates();
            assertThat(announced(recorded, single)).isSorted();

            // And the duplicate reports no failure and announces nothing further
            assertThat(duplicate.receiptOrFail().outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.DUPLICATE);
            assertThat(recorded.failures()).hasSize(2);
            assertThat(recorded.changes()).hasSize(changesBeforeDuplicate);
        }
    }

    // --- Helpers ---

    private static CorrelatedSignal approval(String businessKey, String dedupKey) {
        return CorrelatedSignal.of(ApprovalContract.class, SIGNAL, businessKey, new Payment("a-1"), dedupKey);
    }

    /** Whether the listener has been told every stored entry of each instance. */
    private static boolean caughtUp(KeyedWaitFixture f, RecordedHistory recorded, WorkflowInstanceId... ids) {
        for (WorkflowInstanceId id : ids) {
            if (!announced(recorded, id).equals(stored(f, id))) {
                return false;
            }
        }
        return true;
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
}
