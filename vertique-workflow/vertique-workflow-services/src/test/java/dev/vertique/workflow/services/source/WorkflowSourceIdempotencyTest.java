// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import static dev.vertique.workflow.services.source.PlanFixture.INVOICE_SETTLED;
import static dev.vertique.workflow.services.source.PlanFixture.SHIPMENT_DELIVERED;
import static dev.vertique.workflow.services.source.PlanFixture.envelope;
import static dev.vertique.workflow.services.source.PlanFixture.ordersSignal;
import static dev.vertique.workflow.services.source.PlanFixture.shipment;
import static dev.vertique.workflow.services.source.PlanFixture.shipments;
import static dev.vertique.workflow.services.source.PlanFixture.twoSignalPlan;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.dedup.WorkflowDedupScopes;
import dev.vertique.workflow.exception.WorkflowIntakeRefusedException;
import dev.vertique.workflow.exception.WorkflowSignalAmbiguousException;
import dev.vertique.workflow.exception.WorkflowSignalLimitException;
import dev.vertique.workflow.exception.WorkflowSignalNoMatchException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.services.source.PlanFixture.BillingContract;
import dev.vertique.workflow.services.source.PlanFixture.OrdersContract;
import dev.vertique.workflow.services.source.PlanFixture.ShipmentDelivered;
import dev.vertique.workflow.services.source.SourceDisposition.Fate;
import dev.vertique.workflow.source.SignalMessage;
import dev.vertique.workflow.source.SignalNoMatch;
import dev.vertique.workflow.source.SignalSourceEvent.Outcome;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves that a source is idempotent through the inbox, so a record that runs again after a restart, a rebalance or a
 * dead-letter replay ends as it did the first time.
 *
 * <p>Verified:
 * <ul>
 *   <li>A recorded target commits its inbox row with the signal, and a replay is deduplicated without a second
 *       recording, whatever the wait looks like by then.</li>
 *   <li>A message of several targets is idempotent per target: the targets that finished keep their rows when a
 *       sibling fails, and a replay runs only the target that failed.</li>
 *   <li>A target that recorded nothing and is done, a no-match under an ignoring policy or an empty lookup, commits its
 *       row in a transaction of its own, so a replay stays ignored and cannot record a signal later.</li>
 *   <li>A failed target, and a no-match that is retried, leave no row: their next attempt runs normally.</li>
 *   <li>A refused target outranks the failures of its siblings, and a message that is refused is attempted again
 *       whole.</li>
 *   <li>The identity of a target is a bounded digest of the contract, the signal, the scope and the message's dedup
 *       key, and holds nothing of the source, the declaration position, the instance or the key it resolved to.</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
class WorkflowSourceIdempotencyTest {

    private static final String KEY = "PAY-77";

    /** The dedup key {@code twoSignalPlan} builds for the message with {@link #KEY}. */
    private static final String PLAN_DEDUP_KEY = "D-" + KEY;

    private static String idOf(String contract, String signal, String scope, String dedupKey) {
        return WorkflowDedupScopes.sourceInboxId(contract, signal, scope, dedupKey);
    }

    @Test
    @DisplayName("a recorded target commits its inbox row, and a replay is deduplicated without a second recording")
    void recordedTargetIsDeduplicatedOnReplay(Vertx vertx) {
        try (DeliveryRig rig = DeliveryRig.over(vertx)) {
            // When the message is delivered, and then delivered again as a replay
            SourceDisposition first = rig.deliver(ordersSignal(shipments()), shipment(KEY), envelope("D-1"));
            SourceDisposition replay = rig.deliver(ordersSignal(shipments()), shipment(KEY), envelope("D-1"));

            // Then the first recorded and committed its row, and the replay recorded nothing
            assertThat(first.fate()).isEqualTo(Fate.DONE);
            assertThat(replay.fate()).isEqualTo(Fate.DONE);
            assertThat(rig.outcomes()).containsExactly(Outcome.APPLIED, Outcome.DEDUPLICATED);
            assertThat(rig.calls()).hasSize(1);
            assertThat(rig.inbox().committed).containsExactly(idOf("orders", SHIPMENT_DELIVERED, null, "D-1"));
        }
    }

    @Test
    @DisplayName("a failure rolls the row back with the delivery, and the next attempt runs normally")
    void failedTargetLeavesNoRow(Vertx vertx) {
        try (DeliveryRig rig = DeliveryRig.over(vertx)
                .operations(DeliveryRig.raising(new IllegalStateException("database down")), DeliveryRig.recorded())) {
            SourceDisposition failed = rig.deliver(ordersSignal(shipments()), shipment(KEY), envelope("D-1"));
            assertThat(failed.fate()).isEqualTo(Fate.RETRY);
            assertThat(rig.inbox().committed).isEmpty();

            SourceDisposition retried = rig.deliver(ordersSignal(shipments()), shipment(KEY), envelope("D-1"));

            assertThat(retried.fate()).isEqualTo(Fate.DONE);
            assertThat(rig.outcomes()).containsExactly(Outcome.STAGE_FAILED, Outcome.APPLIED);
            assertThat(rig.inbox().committed).hasSize(1);
        }
    }

    @Test
    @DisplayName("a sibling's failure keeps the finished target's row, and the replay runs only the target that failed")
    void partialMultiTargetReplayRunsOnlyTheFailedTarget(Vertx vertx) {
        // Given a message of two targets, the first recorded and the second failing on the open-signal limit
        WorkflowSignalLimitException limit =
                new WorkflowSignalLimitException(new WorkflowInstanceId(UUID.randomUUID()), 50);
        try (DeliveryRig rig = DeliveryRig.over(vertx).operations(DeliveryRig.recorded(), DeliveryRig.raising(limit))) {
            SourceDisposition first = rig.deliver(twoSignalPlan(SignalNoMatch.IGNORE), shipment(KEY), envelope("D-1"));
            assertThat(first.fate()).isEqualTo(Fate.RETRY);
            assertThat(rig.inbox().committed).containsExactly(idOf("orders", SHIPMENT_DELIVERED, null, PLAN_DEDUP_KEY));
            assertThat(rig.calls()).hasSize(2);

            // When the record runs again and the limit has cleared
            rig.operations(DeliveryRig.recorded());
            SourceDisposition replay = rig.deliver(twoSignalPlan(SignalNoMatch.IGNORE), shipment(KEY), envelope("D-1"));

            // Then only the second target was recorded, and both rows are there
            assertThat(replay.fate()).isEqualTo(Fate.DONE);
            assertThat(rig.calls()).hasSize(3);
            assertThat(rig.calls().get(2).signal().signalName()).isEqualTo(INVOICE_SETTLED);
            assertThat(rig.inbox().committed)
                    .containsExactlyInAnyOrder(
                            idOf("orders", SHIPMENT_DELIVERED, null, PLAN_DEDUP_KEY),
                            idOf("billing", INVOICE_SETTLED, null, PLAN_DEDUP_KEY));
        }
    }

    @Test
    @DisplayName("two replays of a record with one target recorded and one failing record the finished target once")
    void twoReplaysOfAPartiallyRecordedMessage(Vertx vertx) {
        // Given a message of two targets with no dedupBy, so the dedup key is the one the transport gives its
        // position: the same for the record and for every replay of it
        SignalSourcePlan<ShipmentDelivered> plan = SignalSourcePlan.builder("carriers", ShipmentDelivered.class)
                .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                .key(ShipmentDelivered::carrierRef)
                .payload(PlanFixture::confirmation)
                .signal(BillingContract.class, INVOICE_SETTLED)
                .key(ShipmentDelivered::carrierRef)
                .payload(PlanFixture::confirmation)
                .build();
        WorkflowSignalLimitException limit =
                new WorkflowSignalLimitException(new WorkflowInstanceId(UUID.randomUUID()), 50);
        try (DeliveryRig rig = DeliveryRig.over(vertx).operations(DeliveryRig.recorded(), DeliveryRig.raising(limit))) {
            // When the first target is recorded and the second fails at the record, and again at the first replay
            rig.deliver(plan, shipment(KEY), envelope("source:3:42"));
            rig.operations(DeliveryRig.raising(limit));
            SourceDisposition firstReplay = rig.deliver(plan, shipment(KEY), envelope("source:3:42"));
            assertThat(firstReplay.fate()).isEqualTo(Fate.RETRY);

            // And: at the second replay the second target records
            rig.operations(DeliveryRig.recorded());
            SourceDisposition secondReplay = rig.deliver(plan, shipment(KEY), envelope("source:3:42"));

            // Then the finished target was recorded once, and the other was attempted at each run
            assertThat(secondReplay.fate()).isEqualTo(Fate.DONE);
            assertThat(rig.calls())
                    .extracting(call -> call.signal().signalName())
                    .containsExactly(SHIPMENT_DELIVERED, INVOICE_SETTLED, INVOICE_SETTLED, INVOICE_SETTLED);
            assertThat(rig.inbox().committed)
                    .containsExactlyInAnyOrder(
                            idOf("orders", SHIPMENT_DELIVERED, null, "source:3:42"),
                            idOf("billing", INVOICE_SETTLED, null, "source:3:42"));
        }
    }

    @Test
    @DisplayName(
            "a no-match under an ignoring policy commits its row in a transaction of its own, so a replay stays ignored")
    void ignoredNoMatchStaysIgnored(Vertx vertx) {
        try (DeliveryRig rig = DeliveryRig.over(vertx)
                .operations(
                        DeliveryRig.raising(new WorkflowSignalNoMatchException("orders", SHIPMENT_DELIVERED)),
                        DeliveryRig.recorded())) {
            // When the signal finds no wait and is ignored
            SourceDisposition ignored = rig.deliver(ordersSignal(shipments()), shipment(KEY), envelope("D-1"));

            // Then its row is committed after the recording transaction rolled back, in a second transaction
            assertThat(ignored.fate()).isEqualTo(Fate.DONE);
            assertThat(rig.outcomes()).containsExactly(Outcome.NO_MATCH);
            assertThat(rig.transactions()).isEqualTo(2);
            assertThat(rig.inbox().committed).containsExactly(idOf("orders", SHIPMENT_DELIVERED, null, "D-1"));

            // And: a replay, though a wait would match by now, records nothing
            SourceDisposition replay = rig.deliver(ordersSignal(shipments()), shipment(KEY), envelope("D-1"));
            assertThat(replay.fate()).isEqualTo(Fate.DONE);
            assertThat(rig.outcomes()).containsExactly(Outcome.NO_MATCH, Outcome.DEDUPLICATED);
            assertThat(rig.calls()).hasSize(1);
        }
    }

    @Test
    @DisplayName(
            "an early signal under RETRY leaves no row, is retried until the wait opens, and is then recorded once")
    void earlySignalUnderRetry(Vertx vertx) {
        try (DeliveryRig rig = DeliveryRig.over(vertx)
                .operations(
                        DeliveryRig.raising(new WorkflowSignalNoMatchException("orders", SHIPMENT_DELIVERED)),
                        DeliveryRig.recorded())) {
            SignalSourcePlan<ShipmentDelivered> retrying =
                    ordersSignal(shipments().onNoMatch(SignalNoMatch.RETRY));

            // When the signal arrives before the wait
            SourceDisposition early = rig.deliver(retrying, shipment(KEY), envelope("D-1"));
            assertThat(early.fate()).isEqualTo(Fate.RETRY);
            assertThat(rig.inbox().committed).isEmpty();

            // And: it is retried after the wait opened, and replayed after that
            SourceDisposition retried = rig.deliver(retrying, shipment(KEY), envelope("D-1"));
            SourceDisposition replay = rig.deliver(retrying, shipment(KEY), envelope("D-1"));

            // Then it was recorded once
            assertThat(retried.fate()).isEqualTo(Fate.DONE);
            assertThat(replay.fate()).isEqualTo(Fate.DONE);
            assertThat(rig.outcomes()).containsExactly(Outcome.NO_MATCH, Outcome.APPLIED, Outcome.DEDUPLICATED);
            assertThat(rig.calls()).hasSize(2);
        }
    }

    @Test
    @DisplayName("an empty lookup commits its row, so a replay that finds the key later still records nothing")
    void emptyLookupStaysIgnored(Vertx vertx) {
        try (DeliveryRig rig = DeliveryRig.over(vertx)) {
            AtomicReference<Optional<String>> answer = new AtomicReference<>(Optional.empty());
            SignalSourcePlan<ShipmentDelivered> plan = shipments()
                    .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                    .keyFrom(message -> Future.succeededFuture(answer.get()))
                    .payload(PlanFixture::confirmation)
                    .build();

            // When the lookup answers empty
            SourceDisposition ignored = rig.deliver(plan, shipment(KEY), envelope("D-1"));
            assertThat(ignored.fate()).isEqualTo(Fate.DONE);
            assertThat(rig.outcomes()).containsExactly(Outcome.IGNORED);
            assertThat(rig.calls()).isEmpty();
            assertThat(rig.inbox().committed).hasSize(1);

            // And: the record runs again and the lookup now answers a key
            answer.set(Optional.of(KEY));
            SourceDisposition replay = rig.deliver(plan, shipment(KEY), envelope("D-1"));

            // Then the signal is not recorded
            assertThat(replay.fate()).isEqualTo(Fate.DONE);
            assertThat(rig.outcomes()).containsExactly(Outcome.IGNORED, Outcome.DEDUPLICATED);
            assertThat(rig.calls()).isEmpty();
        }
    }

    @Test
    @DisplayName("an ignored target whose row cannot be written is retried, and the message runs again")
    void unwrittenIgnoredRowIsRetried(Vertx vertx) {
        try (DeliveryRig rig = DeliveryRig.over(vertx)
                .operations(DeliveryRig.raising(new WorkflowSignalNoMatchException("orders", SHIPMENT_DELIVERED)))) {
            // The recording transaction rolls back, then the inbox is down for the row of its own
            rig.inbox().failNextInsertAfter = 2;
            SourceDisposition first = rig.deliver(ordersSignal(shipments()), shipment(KEY), envelope("D-1"));

            assertThat(first.fate()).isEqualTo(Fate.RETRY);
            assertThat(rig.inbox().committed).isEmpty();

            SourceDisposition second = rig.deliver(ordersSignal(shipments()), shipment(KEY), envelope("D-1"));
            assertThat(second.fate()).isEqualTo(Fate.DONE);
            assertThat(rig.inbox().committed).hasSize(1);
        }
    }

    @Test
    @DisplayName(
            "a refused target outranks its siblings' failures, nothing is dead-lettered for them, and the message runs whole")
    void refusedOutranksFailure(Vertx vertx) {
        try (DeliveryRig rig = DeliveryRig.over(vertx)
                .operations(
                        DeliveryRig.raising(new WorkflowSignalAmbiguousException("orders", SHIPMENT_DELIVERED)),
                        DeliveryRig.raising(new WorkflowIntakeRefusedException()))) {
            // When one target fails for good and the other is refused by intake
            SourceDisposition first = rig.deliver(twoSignalPlan(SignalNoMatch.IGNORE), shipment(KEY), envelope("D-1"));

            // Then the message waits, so no target is dead-lettered, and no row was committed
            assertThat(first.fate()).isEqualTo(Fate.WAIT);
            assertThat(rig.inbox().committed).isEmpty();

            // And: once intake admits, both targets run again and decide
            rig.operations(
                    DeliveryRig.raising(new WorkflowSignalAmbiguousException("orders", SHIPMENT_DELIVERED)),
                    DeliveryRig.recorded());
            SourceDisposition second = rig.deliver(twoSignalPlan(SignalNoMatch.IGNORE), shipment(KEY), envelope("D-1"));
            assertThat(second.fate()).isEqualTo(Fate.TERMINAL);
            assertThat(rig.inbox().committed).containsExactly(idOf("billing", INVOICE_SETTLED, null, PLAN_DEDUP_KEY));
        }
    }

    @Test
    @DisplayName("the identity leaves out the source, the declaration position, the instance and the key")
    void identityIsIndependentOfTheSourceAndThePosition(Vertx vertx) {
        // Given two sources of different names that declare the same two targets in opposite order
        SignalSourcePlan<ShipmentDelivered> carriers = twoSignalPlan(SignalNoMatch.IGNORE);
        SignalSourcePlan<ShipmentDelivered> reordered = SignalSourcePlan.builder("replayer", ShipmentDelivered.class)
                .dedupBy(PlanFixture::dedupKeyOf)
                .signal(BillingContract.class, INVOICE_SETTLED)
                .key(s -> "OTHER-KEY")
                .payload(PlanFixture::confirmation)
                .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                .key(s -> "ANOTHER-KEY")
                .payload(PlanFixture::confirmation)
                .build();

        try (DeliveryRig first = DeliveryRig.over(vertx);
                DeliveryRig second = DeliveryRig.over(vertx)) {
            // When each delivers the same message
            first.deliver(carriers, shipment(KEY), envelope("D-1"));
            second.deliver(reordered, shipment(KEY), envelope("D-1"));

            // Then the same two identities were written
            assertThat(second.inbox().committed).containsExactlyInAnyOrderElementsOf(first.inbox().committed);
            assertThat(first.inbox().committed).hasSize(2);
        }
    }

    @Test
    @DisplayName("every inbox id is a 64 character digest, whatever the length of the dedup key")
    void idsAreBounded(Vertx vertx) {
        try (DeliveryRig rig = DeliveryRig.over(vertx)) {
            SignalMessage<ShipmentDelivered> message = shipment(KEY);

            rig.deliver(ordersSignal(shipments()), message, envelope("k".repeat(5_000)));

            assertThat(rig.inbox().committed).hasSize(1).allSatisfy(id -> assertThat(id)
                    .matches("[0-9a-f]{64}"));
            assertThat(rig.inbox().offered)
                    .allSatisfy(offer -> assertThat(offer).endsWith("@workflow-signal-sources"));
        }
    }

    @Test
    @DisplayName("the identity differs by contract, signal, scope and dedup key, and holds none of them in clear")
    void identityDiffersByEveryPart() {
        String base = idOf("orders", "shipment-delivered", null, "D-1");

        assertThat(idOf("billing", "shipment-delivered", null, "D-1")).isNotEqualTo(base);
        assertThat(idOf("orders", "invoice-settled", null, "D-1")).isNotEqualTo(base);
        assertThat(idOf("orders", "shipment-delivered", "tenant-a", "D-1")).isNotEqualTo(base);
        assertThat(idOf("orders", "shipment-delivered", null, "D-2")).isNotEqualTo(base);
        assertThat(idOf("orders", "shipment-delivered", null, "D-1")).isEqualTo(base);
        assertThat(base).doesNotContain("orders").doesNotContain("D-1");
        // The fields are separated, so moving a character between two of them gives another identity
        assertThat(idOf("orders", "shipment-delivered", null, "D-1"))
                .isNotEqualTo(idOf("orders", "shipment-delivere", null, "dD-1"));
    }
}
