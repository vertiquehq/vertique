// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import static dev.vertique.workflow.services.source.DeliveryFixture.assertAmbientIs;
import static dev.vertique.workflow.services.source.DeliveryFixture.await;
import static dev.vertique.workflow.services.source.DeliveryFixture.security;
import static dev.vertique.workflow.services.source.PlanFixture.DEFAULT_DEDUP_KEY;
import static dev.vertique.workflow.services.source.PlanFixture.INVOICE_SETTLED;
import static dev.vertique.workflow.services.source.PlanFixture.SHIPMENT_DELIVERED;
import static dev.vertique.workflow.services.source.PlanFixture.confirmation;
import static dev.vertique.workflow.services.source.PlanFixture.contributedEnvelope;
import static dev.vertique.workflow.services.source.PlanFixture.envelope;
import static dev.vertique.workflow.services.source.PlanFixture.keyedPlan;
import static dev.vertique.workflow.services.source.PlanFixture.message;
import static dev.vertique.workflow.services.source.PlanFixture.ordersSignal;
import static dev.vertique.workflow.services.source.PlanFixture.shipment;
import static dev.vertique.workflow.services.source.PlanFixture.shipments;
import static dev.vertique.workflow.services.source.PlanFixture.twoSignalPlan;
import static dev.vertique.workflow.services.source.PlanFixture.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import dev.vertique.workflow.exception.WorkflowSignalActorException;
import dev.vertique.workflow.exception.WorkflowSignalAmbiguousException;
import dev.vertique.workflow.exception.WorkflowSignalLimitException;
import dev.vertique.workflow.exception.WorkflowSignalNoMatchException;
import dev.vertique.workflow.ops.CorrelatedSignal;
import dev.vertique.workflow.ops.SignalProvenance;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.services.source.DeliveryRig.Answer;
import dev.vertique.workflow.services.source.DeliveryRig.Call;
import dev.vertique.workflow.services.source.LookupProbe.Seen;
import dev.vertique.workflow.services.source.ObserverFixture.RecordingObserver;
import dev.vertique.workflow.services.source.ObserverFixture.ThrowingObserver;
import dev.vertique.workflow.services.source.PlanFixture.BillingContract;
import dev.vertique.workflow.services.source.PlanFixture.OrdersContract;
import dev.vertique.workflow.services.source.PlanFixture.ShipmentDelivered;
import dev.vertique.workflow.services.source.SourceDisposition.Fate;
import dev.vertique.workflow.source.SignalMessage;
import dev.vertique.workflow.source.SignalNoMatch;
import dev.vertique.workflow.source.SignalSourceEvent;
import dev.vertique.workflow.source.SignalSourceEvent.Outcome;
import dev.vertique.workflow.source.SignalSourceRefusal;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.DatabaseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies how {@link WorkflowSourceDelivery#deliver} runs a {@link SignalSourcePlan} to one
 * {@link SourceDisposition} per message: the order of the stages, the context each stage runs on,
 * the outcome and fate of each cause, how the signals of one message are folded into one fate, and
 * that nothing of the message reaches a disposition, an event or a log record.
 *
 * <p>The operations are a fake that answers from a script, the pool is a mock that applies its
 * function to a mocked connection, and the context classes are those of {@code vertique-context}.
 * No broker or database is involved.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class WorkflowSourceDeliveryPlanTest {

    private static final String KEY = "PAY-77";

    /** A database error a payload with a NUL character raises, as the driver reports it. */
    static final class PayloadRefused extends DatabaseException {
        PayloadRefused() {
            super("invalid byte sequence", 0, "22P05");
        }
    }

    @Test
    @DisplayName("a source with any-scope and one with no scope stage deliver identically")
    void anyScopeAndNoScopeStageDeliverIdentically(Vertx vertx) {
        // Given two plans over the same message, one with any-scope and one with no scope stage
        SignalSourcePlan<ShipmentDelivered> anyScope = ordersSignal(shipments().anyScope());
        SignalSourcePlan<ShipmentDelivered> noScope = keyedPlan();

        List<List<CorrelatedSignal>> signals = new ArrayList<>();
        for (SignalSourcePlan<ShipmentDelivered> plan : List.of(anyScope, noScope)) {
            try (DeliveryRig rig = DeliveryRig.over(vertx)) {
                // When the plan delivers the message with key PAY-77
                SourceDisposition disposition = rig.deliver(plan, shipment(KEY), envelope());

                // Then the call reached signalByKey with the contract, signal, key, dedup key and no scope key
                assertThat(rig.calls()).hasSize(1);
                assertThat(rig.calls().get(0).method()).isEqualTo("signalByKey");
                CorrelatedSignal signal = rig.calls().get(0).signal();
                assertThat(signal.contract()).isEqualTo(OrdersContract.class);
                assertThat(signal.signalName()).isEqualTo(SHIPMENT_DELIVERED);
                assertThat(signal.key()).isEqualTo(KEY);
                assertThat(signal.dedupKey()).isEqualTo(DEFAULT_DEDUP_KEY);
                assertThat(signal.scopeKey()).isNull();

                // and the disposition is DONE with one APPLIED event
                assertThat(disposition.fate()).isEqualTo(Fate.DONE);
                assertThat(rig.outcomes()).containsExactly(Outcome.APPLIED);
                signals.add(rig.signals());
            }
        }

        // and the two calls were identical
        assertThat(signals.get(0)).isEqualTo(signals.get(1));
    }

    @Test
    @DisplayName("a rejected, empty or unanswered message concerns no workflow")
    void rejectedEmptyOrUnansweredMessageConcernsNoWorkflow(Vertx vertx) {
        // Given four plans: when rejects, a key that is null, a key that is empty, and a lookup that answers empty
        Map<String, SignalSourcePlan<ShipmentDelivered>> plans = new LinkedHashMap<>();
        plans.put("when rejects", ordersSignal(shipments().when(body -> false)));
        plans.put(
                "key null",
                shipments()
                        .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                        .key(body -> null)
                        .payload(PlanFixture::confirmation)
                        .build());
        plans.put(
                "key empty",
                shipments()
                        .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                        .key(body -> "")
                        .payload(PlanFixture::confirmation)
                        .build());
        plans.put(
                "lookup empty",
                shipments()
                        .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                        .keyFrom(message -> Future.succeededFuture(Optional.empty()))
                        .payload(PlanFixture::confirmation)
                        .build());

        plans.forEach((row, plan) -> {
            try (DeliveryRig rig = DeliveryRig.over(vertx)) {
                // When each delivers one message
                SourceDisposition disposition = rig.deliver(plan, shipment(), envelope());

                // Then it is DONE with one IGNORED event, and no operations call or transaction was made
                assertThat(disposition.fate()).as(row).isEqualTo(Fate.DONE);
                assertThat(rig.outcomes()).as(row).containsExactly(Outcome.IGNORED);
                assertThat(rig.calls()).as(row).isEmpty();
                assertThat(rig.transactions()).as(row).isZero();
            }
        });
    }

    @Test
    @DisplayName("a throwing stage or a blank dedup key is terminal and forwards the exception class only")
    void throwingStageOrBlankDedupKeyIsTerminalAndForwardsTheClassOnly(Vertx vertx) {
        // Given a table of plans, each with one defect
        RuntimeException secret = new IllegalStateException(KEY + " is secret");
        Map<String, Supplier<SignalSourcePlan<ShipmentDelivered>>> thrown = new LinkedHashMap<>();
        thrown.put(
                ".when",
                () -> ordersSignal(shipments().when(body -> {
                    throw secret;
                })));
        thrown.put(".key", () -> shipments()
                .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                .key(PlanFixture.throwing(secret))
                .payload(PlanFixture::confirmation)
                .build());
        thrown.put(".businessKey", () -> shipments()
                .dedupBy(PlanFixture::dedupKeyOf)
                .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                .businessKey(PlanFixture.throwing(secret))
                .payload(PlanFixture::confirmation)
                .build());
        thrown.put(".dedupBy", () -> ordersSignal(shipments().dedupBy(PlanFixture.throwing(secret))));
        thrown.put(".payload", () -> shipments()
                .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                .key(ShipmentDelivered::carrierRef)
                .payload(PlanFixture.throwing(secret))
                .build());
        thrown.put(".scope", () -> ordersSignal(shipments().scope(PlanFixture.throwing(secret))));
        thrown.put(".keyFrom", () -> shipments()
                .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                .keyFrom(PlanFixture.throwing(secret))
                .payload(PlanFixture::confirmation)
                .build());
        thrown.put(".businessKeyFrom", () -> shipments()
                .dedupBy(PlanFixture::dedupKeyOf)
                .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                .businessKeyFrom(PlanFixture.throwing(secret))
                .payload(PlanFixture::confirmation)
                .build());
        thrown.put(".dispatchAs", () -> ordersSignal(shipments().dispatchAs(PlanFixture.throwing(secret))));

        Map<String, Supplier<SignalSourcePlan<ShipmentDelivered>>> blank = new LinkedHashMap<>();
        blank.put(".dedupBy null", () -> ordersSignal(shipments().dedupBy(body -> null)));
        blank.put(".dedupBy blank", () -> ordersSignal(shipments().dedupBy(body -> "   ")));

        thrown.forEach((row, plan) -> {
            try (DeliveryRig rig = DeliveryRig.over(vertx)) {
                // When each delivers one message
                SourceDisposition disposition = rig.deliver(plan.get(), shipment(KEY), envelope());

                // Then a thrown stage is TERMINAL, forwards its class only and reports one STAGE_FAILED event
                assertThat(disposition.fate()).as(row).isEqualTo(Fate.TERMINAL);
                assertThat(disposition.errorType()).as(row).isEqualTo("IllegalStateException");
                assertThat(rig.outcomes()).as(row).containsExactly(Outcome.STAGE_FAILED);

                // and the operations were not called, and nothing out holds the key or the exception message
                assertThat(rig.calls()).as(row).isEmpty();
                rig.assertNothingLeaked(disposition, KEY, secret.getMessage());
            }
        });
        blank.forEach((row, plan) -> {
            try (DeliveryRig rig = DeliveryRig.over(vertx)) {
                SourceDisposition disposition = rig.deliver(plan.get(), shipment(KEY), envelope());

                // and a null or blank dedup key is TERMINAL, forwards BlankDedupKey and reports one INVALID_INPUT event
                assertThat(disposition.fate()).as(row).isEqualTo(Fate.TERMINAL);
                assertThat(disposition.errorType()).as(row).isEqualTo("BlankDedupKey");
                assertThat(rig.outcomes()).as(row).containsExactly(Outcome.INVALID_INPUT);
                assertThat(rig.calls()).as(row).isEmpty();
                rig.assertNothingLeaked(disposition, KEY);
            }
        });
    }

    @Test
    @DisplayName("no-match follows the policy and ambiguity is terminal")
    void noMatchFollowsThePolicyAndAmbiguityIsTerminal(Vertx vertx) {
        WorkflowSignalNoMatchException noMatch = new WorkflowSignalNoMatchException("orders", SHIPMENT_DELIVERED);
        SignalSourcePlan<ShipmentDelivered> retrying = ordersSignal(shipments().onNoMatch(SignalNoMatch.RETRY));
        SignalSourcePlan<ShipmentDelivered> mixed = shipments()
                .onNoMatch(SignalNoMatch.RETRY)
                .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                .key(ShipmentDelivered::carrierRef)
                .payload(PlanFixture::confirmation)
                .signal(BillingContract.class, INVOICE_SETTLED)
                .key(ShipmentDelivered::carrierRef)
                .onNoMatch(SignalNoMatch.IGNORE)
                .payload(PlanFixture::confirmation)
                .build();

        // Given operations that find no instance, a plan with the default policy delivers one message
        try (DeliveryRig rig = DeliveryRig.over(vertx).operationsRaising(noMatch)) {
            SourceDisposition disposition = rig.deliver(keyedPlan(), shipment(), envelope());

            // Then the default is DONE with one NO_MATCH event
            assertThat(disposition.fate()).isEqualTo(Fate.DONE);
            assertThat(rig.outcomes()).containsExactly(Outcome.NO_MATCH);
        }

        // and a plan with onNoMatch(RETRY) is RETRY with one NO_MATCH event
        try (DeliveryRig rig = DeliveryRig.over(vertx).operationsRaising(noMatch)) {
            SourceDisposition disposition = rig.deliver(retrying, shipment(), envelope());
            assertThat(disposition.fate()).isEqualTo(Fate.RETRY);
            assertThat(rig.outcomes()).containsExactly(Outcome.NO_MATCH);
        }

        // and in a two-signal plan the first signal retries and the second, with its own IGNORE, does not
        try (DeliveryRig rig = DeliveryRig.over(vertx).operationsRaising(noMatch)) {
            SourceDisposition disposition = rig.deliver(mixed, shipment(), envelope());

            // so the fold is RETRY, both signals were attempted and both outcomes are reported
            assertThat(disposition.fate()).isEqualTo(Fate.RETRY);
            assertThat(rig.calls()).hasSize(2);
            assertThat(rig.outcomes()).containsExactly(Outcome.NO_MATCH, Outcome.NO_MATCH);
        }

        // and an ambiguous match is TERMINAL with one AMBIGUOUS event
        try (DeliveryRig rig = DeliveryRig.over(vertx)
                .operationsRaising(new WorkflowSignalAmbiguousException("orders", SHIPMENT_DELIVERED))) {
            SourceDisposition disposition = rig.deliver(keyedPlan(), shipment(), envelope());
            assertThat(disposition.fate()).isEqualTo(Fate.TERMINAL);
            assertThat(rig.outcomes()).containsExactly(Outcome.AMBIGUOUS);
        }
    }

    @Test
    @DisplayName("refusals after the match retry or go straight to the dead-letter path")
    void refusalsAfterTheMatchRetryOrGoStraightToTheDeadLetterPath(Vertx vertx) {
        // Given operations that raise the open-signal limit for an instance
        WorkflowSignalLimitException limit =
                new WorkflowSignalLimitException(new WorkflowInstanceId(UUID.randomUUID()), 50);
        try (DeliveryRig rig = DeliveryRig.over(vertx).operationsRaising(limit)) {
            // When a message is delivered
            SourceDisposition disposition = rig.deliver(keyedPlan(), shipment(), envelope());

            // Then the limit is RETRY with one LIMITED event
            assertThat(disposition.fate()).isEqualTo(Fate.RETRY);
            assertThat(rig.outcomes()).containsExactly(Outcome.LIMITED);
        }

        // and an oversized payload is TERMINAL with one OVERSIZED event
        WorkflowInvalidPayloadException oversized = new WorkflowInvalidPayloadException("payload too large", List.of());
        try (DeliveryRig rig = DeliveryRig.over(vertx).operationsRaising(oversized)) {
            SourceDisposition disposition = rig.deliver(keyedPlan(), shipment(), envelope());
            assertThat(disposition.fate()).isEqualTo(Fate.TERMINAL);
            assertThat(rig.outcomes()).containsExactly(Outcome.OVERSIZED);
        }

        // and a duplicate receipt is DONE with one DEDUPLICATED event
        try (DeliveryRig rig = DeliveryRig.over(vertx).operations(DeliveryRig.duplicate())) {
            SourceDisposition disposition = rig.deliver(keyedPlan(), shipment(), envelope());
            assertThat(disposition.fate()).isEqualTo(Fate.DONE);
            assertThat(rig.outcomes()).containsExactly(Outcome.DEDUPLICATED);
        }
    }

    @Test
    @DisplayName("the most severe fate wins and every signal is attempted in order")
    void mostSevereFateWinsAndEverySignalIsAttemptedInOrder(Vertx vertx) {
        // Given a source naming two signals on two contracts and a table of what the two calls answer
        WorkflowSignalLimitException limit =
                new WorkflowSignalLimitException(new WorkflowInstanceId(UUID.randomUUID()), 50);
        record Row(String name, Answer first, Answer second, SignalNoMatch policy, Fate fate, List<Outcome> outcomes) {}
        List<Row> rows = List.of(
                new Row(
                        "applied then limit",
                        DeliveryRig.recorded(),
                        DeliveryRig.raising(limit),
                        SignalNoMatch.IGNORE,
                        Fate.RETRY,
                        List.of(Outcome.APPLIED, Outcome.LIMITED)),
                new Row(
                        "no-match under retry then ambiguous",
                        DeliveryRig.raising(new WorkflowSignalNoMatchException("orders", SHIPMENT_DELIVERED)),
                        DeliveryRig.raising(new WorkflowSignalAmbiguousException("billing", INVOICE_SETTLED)),
                        SignalNoMatch.RETRY,
                        Fate.TERMINAL,
                        List.of(Outcome.NO_MATCH, Outcome.AMBIGUOUS)),
                new Row(
                        "applied then applied",
                        DeliveryRig.recorded(),
                        DeliveryRig.recorded(),
                        SignalNoMatch.IGNORE,
                        Fate.DONE,
                        List.of(Outcome.APPLIED, Outcome.APPLIED)));

        for (Row row : rows) {
            try (DeliveryRig rig = DeliveryRig.over(vertx).operations(row.first(), row.second())) {
                // When the row delivers one message
                SourceDisposition disposition = rig.deliver(twoSignalPlan(row.policy()), shipment(KEY), envelope());

                // Then the fold is the most severe fate
                assertThat(disposition.fate()).as(row.name()).isEqualTo(row.fate());

                // and the second call started after the first call's future completed, though the first retried
                assertThat(rig.timeline()).as(row.name()).containsExactly("start:1", "end:1", "start:2", "end:2");

                // and each signal carries the message's dedup key
                assertThat(rig.signals())
                        .as(row.name())
                        .extracting(CorrelatedSignal::dedupKey)
                        .containsExactly("D-" + KEY, "D-" + KEY);

                // and each outcome is reported once, for its own contract and signal
                assertThat(rig.outcomes()).as(row.name()).containsExactlyElementsOf(row.outcomes());
                assertThat(rig.events())
                        .as(row.name())
                        .extracting(SignalSourceEvent::contractName, SignalSourceEvent::signalName)
                        .containsExactly(tuple("orders", SHIPMENT_DELIVERED), tuple("billing", INVOICE_SETTLED));
            }
        }
    }

    @Test
    @DisplayName("the scope answer narrows every signal and an empty answer attempts no match")
    void scopeAnswerNarrowsEverySignalAndAnEmptyAnswerAttemptsNoMatch(Vertx vertx) {
        // Given a two-signal plan whose scope answers acme
        SignalSourcePlan<ShipmentDelivered> scoped = shipments()
                .scope(message -> Optional.of("acme"))
                .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                .key(ShipmentDelivered::carrierRef)
                .payload(PlanFixture::confirmation)
                .signal(BillingContract.class, INVOICE_SETTLED)
                .key(ShipmentDelivered::carrierRef)
                .payload(PlanFixture::confirmation)
                .build();
        SignalSourcePlan<ShipmentDelivered> unanswered =
                ordersSignal(shipments().scope(message -> Optional.empty()));

        try (DeliveryRig rig = DeliveryRig.over(vertx)) {
            // When it delivers one message
            SourceDisposition disposition = rig.deliver(scoped, shipment(), envelope());

            // Then both calls carry scope key acme
            assertThat(disposition.fate()).isEqualTo(Fate.DONE);
            assertThat(rig.signals()).extracting(CorrelatedSignal::scopeKey).containsExactly("acme", "acme");
        }

        // and a plan whose scope answers empty makes no operations call
        try (DeliveryRig rig = DeliveryRig.over(vertx)) {
            SourceDisposition disposition = rig.deliver(unanswered, shipment(), envelope());

            // and is DONE with one SCOPE_EMPTY event that carries no contract or signal name
            assertThat(rig.calls()).isEmpty();
            assertThat(rig.transactions()).isZero();
            assertThat(disposition.fate()).isEqualTo(Fate.DONE);
            assertThat(rig.outcomes()).containsExactly(Outcome.SCOPE_EMPTY);
            assertThat(rig.events().get(0).contractName()).isNull();
            assertThat(rig.events().get(0).signalName()).isNull();
        }
    }

    @Test
    @DisplayName("a lookup runs on a fresh context with correlation, origin and scope and no identity")
    void lookupRunsOnAFreshContextWithCorrelationOriginAndScopeButNoIdentity(Vertx vertx) {
        // Given a caller whose own context holds a security context, and a plan with a scope and a business-key lookup
        try (DeliveryRig rig = DeliveryRig.over(vertx);
                DeliveryRig.Caller caller =
                        rig.callerOnDuplicate().binding(SecurityContext.class, security("caller"))) {
            LookupProbe probe = rig.probe();
            SignalSourcePlan<ShipmentDelivered> plan = shipments()
                    .dedupBy(PlanFixture::dedupKeyOf)
                    .scope(message -> Optional.of("acme"))
                    .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                    .businessKeyFrom(message -> {
                        probe.sample("lookup");
                        return Future.succeededFuture(Optional.of("ORDER-5"));
                    })
                    .payload(PlanFixture::confirmation)
                    .build();
            SourceEnvelope envelope = envelope();

            // When the plan delivers one message from the caller's context
            SourceDisposition disposition = await(caller.deliver(plan, shipment(), envelope));

            // Then the lookup saw correlation C-1, the Kafka origin and scope acme, and no security context
            assertThat(disposition.fate()).isEqualTo(Fate.DONE);
            Seen lookup = probe.seenBy("lookup");
            assertThat(lookup).isNotNull();
            assertThat(lookup.correlation()).isNotNull();
            assertThat(lookup.correlation().correlationId())
                    .isEqualTo(envelope.correlation().correlationId());
            assertThat(lookup.origin()).isEqualTo(envelope.origin());
            assertThat(lookup.scope()).isNotNull();
            assertThat(lookup.scope().scopeKey().value()).isEqualTo("acme");
            assertThat(lookup.security()).isNull();

            // and ran on a duplicated context that is neither the caller's nor the recording's
            assertThat(rig.calls()).hasSize(1);
            Call recording = rig.calls().get(0);
            assertThat(lookup.context().isDuplicate()).isTrue();
            assertThat(lookup.context()).isNotSameAs(caller.context());
            assertThat(lookup.context()).isNotSameAs(recording.context());

            // and the recording that follows saw the envelope's identity
            assertThat(recording.method()).isEqualTo("signalByBusinessKey");
            assertAmbientIs(recording.ambient(), envelope);
        }
    }

    @Test
    @DisplayName(
            "dispatchAs runs after when and dedupBy, is skipped when identity is contributed and an empty answer keeps the envelope identity")
    void dispatchAsRunsAfterWhenAndDedupByIsSkippedWhenIdentityIsContributedAndEmptyKeepsTheEnvelopeIdentity(
            Vertx vertx) {
        // Given a plan whose dispatchAs answers alice, and each stage notes when it ran
        try (DeliveryRig rig = DeliveryRig.over(vertx)) {
            SourceEnvelope envelope = envelope();

            // When it delivers one message
            SourceDisposition disposition =
                    rig.deliver(orderedPlan(rig, Optional.of(user("alice"))), shipment(), envelope);

            // Then dispatchAs ran after when and dedupBy and before scope and the lookup
            assertThat(disposition.fate()).isEqualTo(Fate.DONE);
            assertThat(rig.order()).containsExactly("when", "dedupBy", "dispatchAs", "scope", "lookup");

            // and the recording ran under alice, with the envelope's correlation and origin
            Call recording = rig.calls().get(0);
            assertThat(recording.ambient().security().identity().actor().id()).isEqualTo("alice");
            assertThat(recording.ambient().correlation().correlationId())
                    .isEqualTo(envelope.correlation().correlationId());
            assertThat(recording.ambient().origin()).isEqualTo(envelope.origin());
        }

        // and a dispatchAs that answers empty keeps the envelope's identity
        try (DeliveryRig rig = DeliveryRig.over(vertx)) {
            SourceEnvelope envelope = envelope();
            rig.deliver(orderedPlan(rig, Optional.empty()), shipment(), envelope);

            assertThat(rig.order()).containsExactly("when", "dedupBy", "dispatchAs", "scope", "lookup");
            assertAmbientIs(rig.calls().get(0).ambient(), envelope);
        }

        // and when the envelope's identity was contributed, dispatchAs never runs and the envelope's identity is kept
        try (DeliveryRig rig = DeliveryRig.over(vertx)) {
            SourceEnvelope contributed = contributedEnvelope();
            rig.deliver(orderedPlan(rig, Optional.of(user("alice"))), shipment(), contributed);

            assertThat(rig.order()).containsExactly("when", "dedupBy", "scope", "lookup");
            assertAmbientIs(rig.calls().get(0).ambient(), contributed);
        }
    }

    @Test
    @DisplayName("a failed, stalled or anonymous stage follows the fate rows")
    void failedStalledOrAnonymousStageFollowsTheFateRows(Vertx vertx) {
        Stalled stalled = new Stalled();
        try {
            // Given a keyFrom that fails with an exception whose message holds the key
            SignalSourcePlan<ShipmentDelivered> failedLookup = shipments()
                    .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                    .keyFrom(message -> Future.failedFuture(new IllegalStateException("lookup of " + KEY + " failed")))
                    .payload(PlanFixture::confirmation)
                    .build();
            // and a businessKeyFrom that never completes, with a 50 ms bound
            SignalSourcePlan<ShipmentDelivered> stalledLookup = shipments()
                    .dedupBy(PlanFixture::dedupKeyOf)
                    .lookupTimeoutMs(50)
                    .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                    .businessKeyFrom(message -> stalled.<Optional<String>>future())
                    .payload(PlanFixture::confirmation)
                    .build();
            // and a dispatchAs that fails, one that never completes with a 50 ms bound, and one that answers anonymous
            SignalSourcePlan<ShipmentDelivered> failedIdentity = ordersSignal(shipments()
                    .dispatchAs(message -> Future.failedFuture(new IllegalStateException("token abc-123 is invalid"))));
            SignalSourcePlan<ShipmentDelivered> stalledIdentity = ordersSignal(shipments()
                    .lookupTimeoutMs(50)
                    .dispatchAs(message -> stalled.<Optional<SecurityIdentity>>future()));
            SignalSourcePlan<ShipmentDelivered> anonymous = ordersSignal(shipments()
                    .dispatchAs(message -> Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()))));

            // When each delivers one message
            // Then the failed lookup is RETRY, reports STAGE_FAILED and forwards the exception class only
            try (DeliveryRig rig = DeliveryRig.over(vertx)) {
                SourceDisposition disposition = rig.deliver(failedLookup, shipment(KEY), envelope());
                assertThat(disposition.fate()).isEqualTo(Fate.RETRY);
                assertThat(disposition.errorType()).isEqualTo("IllegalStateException");
                assertThat(rig.outcomes()).containsExactly(Outcome.STAGE_FAILED);
                assertThat(rig.calls()).isEmpty();
                rig.assertNothingLeaked(disposition, KEY);
            }

            // and the stalled lookup is RETRY after about 50 ms, with STAGE_FAILED and no operations call
            try (DeliveryRig rig = DeliveryRig.over(vertx)) {
                long started = System.nanoTime();
                SourceDisposition disposition = rig.deliver(stalledLookup, shipment(KEY), envelope());
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertThat(disposition.fate()).isEqualTo(Fate.RETRY);
                assertThat(disposition.errorType()).isNotBlank();
                assertThat(rig.outcomes()).containsExactly(Outcome.STAGE_FAILED);
                assertThat(rig.calls()).isEmpty();
                assertThat(elapsedMs).as("a stall ends at the 50 ms bound").isBetween(40L, 2_000L);
                rig.assertNothingLeaked(disposition, KEY);
            }

            // and the failing dispatchAs is RETRY, with STAGE_FAILED and the exception class only
            try (DeliveryRig rig = DeliveryRig.over(vertx)) {
                SourceDisposition disposition = rig.deliver(failedIdentity, shipment(KEY), envelope());
                assertThat(disposition.fate()).isEqualTo(Fate.RETRY);
                assertThat(disposition.errorType()).isEqualTo("IllegalStateException");
                assertThat(rig.outcomes()).containsExactly(Outcome.STAGE_FAILED);
                assertThat(rig.calls()).isEmpty();
                rig.assertNothingLeaked(disposition, "abc-123");
            }

            // and the stalled dispatchAs is RETRY after about 50 ms
            try (DeliveryRig rig = DeliveryRig.over(vertx)) {
                long started = System.nanoTime();
                SourceDisposition disposition = rig.deliver(stalledIdentity, shipment(KEY), envelope());
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertThat(disposition.fate()).isEqualTo(Fate.RETRY);
                assertThat(rig.outcomes()).containsExactly(Outcome.STAGE_FAILED);
                assertThat(rig.calls()).isEmpty();
                assertThat(elapsedMs).as("a stall ends at the 50 ms bound").isBetween(40L, 2_000L);
            }

            // and the anonymous identity is TERMINAL, reports REFUSED_IDENTITY and makes no operations call
            try (DeliveryRig rig = DeliveryRig.over(vertx)) {
                SourceDisposition disposition = rig.deliver(anonymous, shipment(KEY), envelope());
                assertThat(disposition.fate()).isEqualTo(Fate.TERMINAL);
                assertThat(rig.outcomes()).containsExactly(Outcome.REFUSED_IDENTITY);
                assertThat(rig.calls()).isEmpty();
            }
        } finally {
            stalled.release();
        }
    }

    @Test
    @DisplayName("a refusal to capture an actor is terminal and forwards the class only")
    void actorExceptionIsTerminalAndForwardsTheClassOnly(Vertx vertx) {
        // Given operations that fail with the actor refusal
        try (DeliveryRig rig = DeliveryRig.over(vertx)
                .operationsRaising(new WorkflowSignalActorException("orders", SHIPMENT_DELIVERED))) {
            // When the plan delivers one message
            SourceDisposition disposition = rig.deliver(keyedPlan(), shipment(KEY), envelope());

            // Then it is TERMINAL with the exception's class as its error type
            assertThat(disposition.fate()).isEqualTo(Fate.TERMINAL);
            assertThat(disposition.errorType()).isEqualTo("WorkflowSignalActorException");

            // and one REFUSED_IDENTITY event is reported
            assertThat(rig.outcomes()).containsExactly(Outcome.REFUSED_IDENTITY);

            // and the operations were called once, so the failure was not retried
            assertThat(rig.calls()).hasSize(1);

            // and nothing out holds the key
            rig.assertNothingLeaked(disposition, KEY);
        }
    }

    @Test
    @DisplayName("a failure no fate row names is retried and forwards the class only")
    void failureNoFateRowNamesIsRetriedAndForwardsTheClassOnly(Vertx vertx) {
        // Given operations that fail after the match, standing for a database outage, and operations that throw
        IllegalStateException outage = new IllegalStateException("connection lost while recording " + KEY);
        Map<String, Answer> answers = new LinkedHashMap<>();
        answers.put("failed future", DeliveryRig.raising(outage));
        answers.put("synchronous throw", DeliveryRig.throwing(outage));

        answers.forEach((row, answer) -> {
            try (DeliveryRig rig = DeliveryRig.over(vertx).operations(answer)) {
                // When the plan delivers one message
                SourceDisposition disposition = rig.deliver(keyedPlan(), shipment(KEY), envelope());

                // Then it is RETRY with the exception's class as its error type
                assertThat(disposition.fate()).as(row).isEqualTo(Fate.RETRY);
                assertThat(disposition.errorType()).as(row).isEqualTo("IllegalStateException");

                // and one STAGE_FAILED event is reported, and no APPLIED event
                assertThat(rig.outcomes()).as(row).containsExactly(Outcome.STAGE_FAILED);

                // and nothing out holds the key
                rig.assertNothingLeaked(disposition, KEY);
            }
        });
    }

    @Test
    @DisplayName("outcomes reached before a signal is chosen reach observers with null contract and signal names")
    void preSignalOutcomesReachObserversWithNullContractAndSignalNames(Vertx vertx) {
        // Given a plan whose when rejects the message and a plan whose scope answers empty
        SignalSourcePlan<ShipmentDelivered> rejecting = ordersSignal(shipments().when(body -> false));
        SignalSourcePlan<ShipmentDelivered> unscoped = ordersSignal(shipments().scope(message -> Optional.empty()));
        Map<SignalSourcePlan<ShipmentDelivered>, Outcome> expected = new LinkedHashMap<>();
        expected.put(rejecting, Outcome.IGNORED);
        expected.put(unscoped, Outcome.SCOPE_EMPTY);

        expected.forEach((plan, outcome) -> {
            try (DeliveryRig rig = DeliveryRig.over(vertx)) {
                // When each delivers one message
                rig.deliver(plan, shipment(), envelope());

                // Then one event of the outcome is reported, with no contract or signal name and the source and
                // transport set
                assertThat(rig.events()).hasSize(1);
                SignalSourceEvent event = rig.events().get(0);
                assertThat(event.outcome()).isEqualTo(outcome);
                assertThat(event.contractName()).isNull();
                assertThat(event.signalName()).isNull();
                assertThat(event.source()).isEqualTo("carriers");
                assertThat(event.transport()).isEqualTo("kafka");

                // and no operations call is made
                assertThat(rig.calls()).isEmpty();
            }
        });
    }

    @Test
    @DisplayName("a key, scope or payload the type or database refuses is terminal and forwards nothing of the message")
    void keyScopeOrPayloadTheTypeOrDatabaseRefusesIsTerminalAndForwardsNothingOfTheMessage(Vertx vertx) {
        // Given a table of plans over one message, each with one refused input
        String longKey = "LONGKEY-" + "x".repeat(248);
        String exactKey = "K".repeat(255);
        record Input(
                String name,
                SignalSourcePlan<ShipmentDelivered> plan,
                SignalMessage<ShipmentDelivered> message,
                Answer answer,
                String errorType,
                int calls,
                List<String> fragments) {}
        List<Input> refused = List.of(
                new Input(
                        "key of 256 characters",
                        keyed(body -> longKey),
                        shipment(),
                        DeliveryRig.recorded(),
                        "IllegalArgumentException",
                        0,
                        List.of("LONGKEY")),
                new Input(
                        "whitespace-only key",
                        keyed(body -> "   "),
                        shipment(),
                        DeliveryRig.recorded(),
                        "IllegalArgumentException",
                        0,
                        List.of()),
                new Input(
                        "key with a NUL character",
                        keyed(body -> "PAY\u0000-77"),
                        shipment(),
                        DeliveryRig.recorded(),
                        "IllegalArgumentException",
                        0,
                        List.of("PAY")),
                new Input(
                        "scope with a control character",
                        ordersSignal(shipments().scope(message -> Optional.of("secret-scope\u0007"))),
                        shipment(),
                        DeliveryRig.recorded(),
                        "IllegalArgumentException",
                        0,
                        List.of("secret-scope")),
                new Input(
                        "payload holding NUL, refused by the database",
                        keyedPlan(),
                        message(new ShipmentDelivered(KEY, "ORDER-5", "secret-payload\u0000")),
                        DeliveryRig.raising(
                                new IllegalStateException("insert failed for secret-payload", new PayloadRefused())),
                        "PayloadRefused",
                        1,
                        List.of("secret-payload")));

        for (Input input : refused) {
            try (DeliveryRig rig = DeliveryRig.over(vertx).operations(input.answer())) {
                // When each delivers its message
                SourceDisposition disposition = rig.deliver(input.plan(), input.message(), envelope());

                // Then it is TERMINAL with one INVALID_INPUT event and the refusing exception's class as its error type
                assertThat(disposition.fate()).as(input.name()).isEqualTo(Fate.TERMINAL);
                assertThat(rig.outcomes()).as(input.name()).containsExactly(Outcome.INVALID_INPUT);
                assertThat(disposition.errorType()).as(input.name()).isEqualTo(input.errorType());

                // and the key and scope rows made no operations call, the payload row exactly one
                assertThat(rig.calls()).as(input.name()).hasSize(input.calls());

                // and nothing out holds any part of the key, scope or payload
                rig.assertNothingLeaked(disposition, input.fragments().toArray(String[]::new));
            }
        }

        // and a control: a key of exactly 255 characters is recorded
        try (DeliveryRig rig = DeliveryRig.over(vertx)) {
            SourceDisposition disposition = rig.deliver(keyed(body -> exactKey), shipment(), envelope());
            assertThat(disposition.fate()).isEqualTo(Fate.DONE);
            assertThat(rig.outcomes()).containsExactly(Outcome.APPLIED);
            assertThat(rig.calls()).hasSize(1);
        }
    }

    @Test
    @DisplayName("a dispatchAs that fails with the refusal marker is terminal and forwards its error type only")
    void dispatchAsFailingWithTheRefusalMarkerIsTerminalAndForwardsItsErrorTypeOnly(Vertx vertx) {
        // Given plans whose dispatchAs fails with the marker as a failed future and by throwing it
        Map<String, SignalSourcePlan<ShipmentDelivered>> markers = new LinkedHashMap<>();
        markers.put(
                "failed future",
                ordersSignal(shipments()
                        .dispatchAs(message -> Future.failedFuture(new SignalSourceRefusal("TokenRejected")))));
        markers.put("thrown", ordersSignal(shipments().dispatchAs(message -> {
            throw new SignalSourceRefusal("TokenRejected");
        })));

        markers.forEach((row, plan) -> {
            try (DeliveryRig rig = DeliveryRig.over(vertx)) {
                // When each delivers one message
                SourceDisposition disposition = rig.deliver(plan, shipment(KEY), envelope());

                // Then it is TERMINAL with the marker's error type and one REFUSED_IDENTITY event
                assertThat(disposition.fate()).as(row).isEqualTo(Fate.TERMINAL);
                assertThat(disposition.errorType()).as(row).isEqualTo("TokenRejected");
                assertThat(rig.outcomes()).as(row).containsExactly(Outcome.REFUSED_IDENTITY);

                // and no operations call was made
                assertThat(rig.calls()).as(row).isEmpty();
            }
        });

        // and a control that fails with an ordinary exception is RETRY with STAGE_FAILED
        SignalSourcePlan<ShipmentDelivered> control = ordersSignal(shipments()
                .dispatchAs(message -> Future.failedFuture(new IllegalStateException("token abc-123 is invalid"))));
        try (DeliveryRig rig = DeliveryRig.over(vertx)) {
            SourceDisposition disposition = rig.deliver(control, shipment(KEY), envelope());
            assertThat(disposition.fate()).isEqualTo(Fate.RETRY);
            assertThat(disposition.errorType()).isEqualTo("IllegalStateException");
            assertThat(rig.outcomes()).containsExactly(Outcome.STAGE_FAILED);

            // and nothing out holds the token
            rig.assertNothingLeaked(disposition, "abc-123");
        }
    }

    @Test
    @DisplayName("an outcome recording no command for a non-system identity is logged with names only")
    void outcomeRecordingNoCommandForANonSystemIdentityIsLoggedWithActorContractSignalAndOutcomeOnly(Vertx vertx) {
        // Given a plan whose dispatchAs answers user alice, and operations that answer no-match, ambiguity, then
        // recorded
        SignalSourcePlan<ShipmentDelivered> asAlice =
                ordersSignal(shipments().dispatchAs(message -> Future.succeededFuture(Optional.of(user("alice")))));
        try (DeliveryRig rig = DeliveryRig.over(vertx)
                .operations(
                        DeliveryRig.raising(new WorkflowSignalNoMatchException("orders", SHIPMENT_DELIVERED)),
                        DeliveryRig.raising(new WorkflowSignalAmbiguousException("orders", SHIPMENT_DELIVERED)),
                        DeliveryRig.recorded())) {

            // When the no-match is delivered
            int mark = rig.logCount();
            SourceDisposition noMatch = rig.deliver(asAlice, shipment(KEY), envelope());

            // Then one log record names alice, the contract, the signal and NO_MATCH
            assertThat(noMatch.fate()).isEqualTo(Fate.DONE);
            assertThat(rig.logsSince(mark).stream().filter(text -> text.contains("alice")))
                    .hasSize(1)
                    .allSatisfy(text -> assertThat(text)
                            .contains("orders")
                            .contains(SHIPMENT_DELIVERED)
                            .contains("NO_MATCH"));

            // and the ambiguity writes one record naming AMBIGUOUS
            mark = rig.logCount();
            SourceDisposition ambiguous = rig.deliver(asAlice, shipment(KEY), envelope());
            assertThat(ambiguous.fate()).isEqualTo(Fate.TERMINAL);
            assertThat(rig.logsSince(mark).stream().filter(text -> text.contains("alice")))
                    .hasSize(1)
                    .allSatisfy(text -> assertThat(text)
                            .contains("orders")
                            .contains(SHIPMENT_DELIVERED)
                            .contains("AMBIGUOUS"));

            // and the recorded delivery writes none
            mark = rig.logCount();
            SourceDisposition recorded = rig.deliver(asAlice, shipment(KEY), envelope());
            assertThat(recorded.fate()).isEqualTo(Fate.DONE);
            assertThat(rig.logsSince(mark)).noneMatch(text -> text.contains("alice"));

            // and no log record holds the key
            rig.assertNothingLeaked(recorded, KEY);
        }

        // and a plan that keeps the envelope's system identity writes none for a no-match
        SecurityContext system = envelope().security();
        String systemActor = system.identity().actor().id();
        try (DeliveryRig rig = DeliveryRig.over(vertx)
                .operationsRaising(new WorkflowSignalNoMatchException("orders", SHIPMENT_DELIVERED))) {
            int mark = rig.logCount();
            SourceDisposition disposition = rig.deliver(keyedPlan(), shipment(KEY), envelope());

            assertThat(disposition.fate()).isEqualTo(Fate.DONE);
            assertThat(rig.logsSince(mark))
                    .noneMatch(text -> text.contains(SHIPMENT_DELIVERED))
                    .noneMatch(text -> text.contains(systemActor));
            rig.assertNothingLeaked(disposition, KEY);
        }
    }

    @Test
    @DisplayName("every synchronous stage runs on a fresh context with correlation and origin and no identity")
    void synchronousStagesRunOnAFreshContextWithCorrelationAndOriginAndNoIdentity(Vertx vertx) {
        // Given a caller whose own context holds a security context, and two plans whose stages record what they see
        for (String keyStage : List.of("key", "businessKey")) {
            try (DeliveryRig rig = DeliveryRig.over(vertx);
                    DeliveryRig.Caller caller =
                            rig.callerOnDuplicate().binding(SecurityContext.class, security("caller"))) {
                LookupProbe probe = rig.probe();
                SignalSourcePlan.Builder<ShipmentDelivered> builder = shipments()
                        .when(body -> {
                            probe.sample("when");
                            return true;
                        })
                        .scope(message -> {
                            probe.sample("scope");
                            return Optional.of("acme");
                        })
                        .dedupBy(body -> {
                            probe.sample("dedupBy");
                            return PlanFixture.dedupKeyOf(body);
                        });
                SignalSourcePlan<ShipmentDelivered> plan = keyStage.equals("key")
                        ? builder.signal(OrdersContract.class, SHIPMENT_DELIVERED)
                                .key(body -> {
                                    probe.sample("key");
                                    return body.carrierRef();
                                })
                                .payload(body -> {
                                    probe.sample("payload");
                                    return confirmation(body);
                                })
                                .build()
                        : builder.signal(OrdersContract.class, SHIPMENT_DELIVERED)
                                .businessKey(body -> {
                                    probe.sample("key");
                                    return body.orderNo();
                                })
                                .payload(body -> {
                                    probe.sample("payload");
                                    return confirmation(body);
                                })
                                .build();
                SourceEnvelope envelope = envelope();

                // When each plan delivers one message from the caller's context
                SourceDisposition disposition = await(caller.deliver(plan, shipment(), envelope));
                assertThat(disposition.fate()).as(keyStage).isEqualTo(Fate.DONE);
                Call recording = rig.calls().get(0);

                // Then every stage ran on a duplicated context that is neither the caller's nor the recording's
                for (String stage : List.of("when", "scope", "dedupBy", "key", "payload")) {
                    String row = keyStage + " plan, stage " + stage;
                    Seen seen = probe.seenBy(stage);
                    assertThat(seen).as(row).isNotNull();
                    assertThat(seen.context().isDuplicate()).as(row).isTrue();
                    assertThat(seen.context()).as(row).isNotSameAs(caller.context());
                    assertThat(seen.context()).as(row).isNotSameAs(recording.context());

                    // and saw correlation C-1 and the Kafka origin and no security context
                    assertThat(seen.correlation()).as(row).isNotNull();
                    assertThat(seen.correlation().correlationId())
                            .as(row)
                            .isEqualTo(envelope.correlation().correlationId());
                    assertThat(seen.origin()).as(row).isEqualTo(envelope.origin());
                    assertThat(seen.security()).as(row).isNull();
                }

                // and the key stage also saw scope acme
                Seen key = probe.seenBy("key");
                assertThat(key.scope()).as(keyStage).isNotNull();
                assertThat(key.scope().scopeKey().value()).as(keyStage).isEqualTo("acme");

                // and the recording that follows saw the envelope's identity
                assertAmbientIs(recording.ambient(), envelope);
            }
        }
    }

    @Test
    @DisplayName("an excluded or blank-keyed message never reaches the dispatchAs function")
    void anExcludedOrBlankKeyedMessageNeverReachesTheDispatchAsFunction(Vertx vertx) {
        // Given a dispatchAs that notes each call and answers alice, in front of each of three defects and a control
        record Case(
                String name,
                Function<DeliveryRig, SignalSourcePlan<ShipmentDelivered>> plan,
                Fate fate,
                Outcome outcome,
                String errorType,
                int dispatchCalls) {}
        List<Case> cases = List.of(
                new Case(
                        "when rejects",
                        rig -> ordersSignal(asAlice(rig).when(body -> false)),
                        Fate.DONE,
                        Outcome.IGNORED,
                        null,
                        0),
                new Case(
                        "dedupBy blank",
                        rig -> ordersSignal(asAlice(rig).dedupBy(body -> "   ")),
                        Fate.TERMINAL,
                        Outcome.INVALID_INPUT,
                        "BlankDedupKey",
                        0),
                new Case(
                        "dedupBy throws",
                        rig -> ordersSignal(
                                asAlice(rig).dedupBy(PlanFixture.throwing(new IllegalStateException("boom")))),
                        Fate.TERMINAL,
                        Outcome.STAGE_FAILED,
                        "IllegalStateException",
                        0),
                new Case(
                        "control",
                        rig -> ordersSignal(asAlice(rig).when(body -> true).dedupBy(PlanFixture::dedupKeyOf)),
                        Fate.DONE,
                        Outcome.APPLIED,
                        null,
                        1));

        for (Case scenario : cases) {
            try (DeliveryRig rig = DeliveryRig.over(vertx)) {
                // When each delivers one message
                SourceDisposition disposition = rig.deliver(scenario.plan().apply(rig), shipment(), envelope());

                // Then the disposition and event are the case's, and dispatchAs ran as often as the case expects
                assertThat(disposition.fate()).as(scenario.name()).isEqualTo(scenario.fate());
                assertThat(disposition.errorType()).as(scenario.name()).isEqualTo(scenario.errorType());
                assertThat(rig.outcomes()).as(scenario.name()).containsExactly(scenario.outcome());
                assertThat(Collections.frequency(rig.order(), "dispatchAs"))
                        .as(scenario.name())
                        .isEqualTo(scenario.dispatchCalls());

                // and an excluded message made no operations call, while the control ran under alice
                assertThat(rig.calls()).as(scenario.name()).hasSize(scenario.dispatchCalls());
                if (scenario.dispatchCalls() == 1) {
                    assertThat(rig.calls()
                                    .get(0)
                                    .ambient()
                                    .security()
                                    .identity()
                                    .actor()
                                    .id())
                            .isEqualTo("alice");
                }
            }
        }
    }

    @Test
    @DisplayName("stages run on duplicates of one captured context and each next stage starts back on it")
    void stagesRunOnDuplicatesOfOneCapturedContextAndEachNextStageStartsBackOnIt(Vertx vertx) {
        // Given a delivery called from context A and futures that complete from a foreign context F
        try (DeliveryRig rig = DeliveryRig.over(vertx);
                DeliveryRig.Caller a = rig.callerOnRoot()) {
            ContextInternal f = rig.foreignContext();
            assertThat(f).isNotSameAs(a.context());
            LookupProbe probe = rig.probe();

            // and a two-signal plan whose dispatchAs and keyFrom complete on F, and whose other stages record their
            // context
            SignalSourcePlan<ShipmentDelivered> plan = shipments()
                    .dispatchAs(message -> rig.<Optional<SecurityIdentity>>completedFrom(f, Optional.empty()))
                    .scope(message -> {
                        probe.sample("scope");
                        return Optional.of("acme");
                    })
                    .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                    .keyFrom(message -> {
                        probe.sample("keyFrom");
                        return rig.completedFrom(f, Optional.of(message.body().carrierRef()));
                    })
                    .payload(body -> {
                        probe.sample("payload-1");
                        return confirmation(body);
                    })
                    .signal(BillingContract.class, INVOICE_SETTLED)
                    .key(body -> {
                        probe.sample("key");
                        return body.carrierRef();
                    })
                    .payload(body -> {
                        probe.sample("payload-2");
                        return confirmation(body);
                    })
                    .build();
            // and operations that complete each recording from F as well
            rig.operations(rig.recordedFrom(f));

            // When the plan delivers one message from A
            AtomicReference<Context> completedOn = new AtomicReference<>();
            Future<SourceDisposition> returned = a.deliver(plan, shipment(), envelope());
            returned.onComplete(result -> completedOn.set(Vertx.currentContext()));
            SourceDisposition disposition = await(returned);

            // Then the returned future completed on A
            assertThat(disposition.fate()).isEqualTo(Fate.DONE);
            assertThat(completedOn.get()).isSameAs(a.context());

            // and every application future did complete on F, so the proof exercises the hop back
            assertThat(rig.completedOn()).hasSize(4).allSatisfy(context -> assertThat(context)
                    .isSameAs(f));

            // and every stage ran on a duplicate of A, never on F and never on A itself
            Set<ContextInternal> used = Collections.newSetFromMap(new IdentityHashMap<>());
            for (String stage : List.of("scope", "keyFrom", "payload-1", "key", "payload-2")) {
                Seen seen = probe.seenBy(stage);
                assertThat(seen).as(stage).isNotNull();
                assertThat(seen.context().isDuplicate()).as(stage).isTrue();
                assertThat(seen.context().unwrap())
                        .as(stage)
                        .isSameAs(a.context().unwrap());
                assertThat(seen.context()).as(stage).isNotSameAs(a.context()).isNotSameAs(f);
                used.add(seen.context());
            }
            for (Call call : rig.calls()) {
                used.add(call.context());
            }

            // and no duplicate served two stages or calls
            assertThat(used).as("one duplicate per stage and call").hasSize(7);

            // and the second signal's operations call started after the first call's future completed
            assertThat(rig.timeline()).containsExactly("start:1", "end:1", "start:2", "end:2");
        }
    }

    @Test
    @DisplayName("an observer throwing an error changes no fate and stops no other observer")
    void anObserverThrowingAnErrorChangesNoFateAndStopsNoOtherObserver(Vertx vertx) {
        // Given a plan that retries on no-match, and the operations answers recorded, no-match, then ambiguity
        SignalSourcePlan<ShipmentDelivered> plan = ordersSignal(shipments().onNoMatch(SignalNoMatch.RETRY));
        Answer[] answers = {
            DeliveryRig.recorded(),
            DeliveryRig.raising(new WorkflowSignalNoMatchException("orders", SHIPMENT_DELIVERED)),
            DeliveryRig.raising(new WorkflowSignalAmbiguousException("orders", SHIPMENT_DELIVERED))
        };
        List<Fate> expected = List.of(Fate.DONE, Fate.RETRY, Fate.TERMINAL);

        // with no throwing observer the fates are the baseline
        List<Fate> baseline;
        try (DeliveryRig rig = DeliveryRig.over(vertx)) {
            baseline = deliverThree(rig, plan, answers);
        }
        assertThat(baseline).isEqualTo(expected);

        for (Throwable failure : List.of(new AssertionError(KEY), new LinkageError(KEY))) {
            String kind = failure.getClass().getSimpleName();
            RecordingObserver first = new RecordingObserver();
            RecordingObserver last = new RecordingObserver();
            ThrowingObserver thrower = new ThrowingObserver(failure);

            // When one message per answer is delivered with a set of a recorder, the thrower and a second recorder
            try (DeliveryRig rig = DeliveryRig.observing(vertx, first, thrower, last)) {
                List<Fate> fates = deliverThree(rig, plan, answers);

                // Then the fates are exactly the baseline, and every delivery future completed normally
                assertThat(fates).as(kind).isEqualTo(baseline);

                // and both recording observers received every event
                List<Outcome> outcomes = List.of(Outcome.APPLIED, Outcome.NO_MATCH, Outcome.AMBIGUOUS);
                assertThat(first.events())
                        .as(kind)
                        .extracting(SignalSourceEvent::outcome)
                        .isEqualTo(outcomes);
                assertThat(last.events())
                        .as(kind)
                        .extracting(SignalSourceEvent::outcome)
                        .isEqualTo(outcomes);

                // and the reporter received the error each time it was thrown
                verify(rig.reporter(), times(3)).report(ThrowingObserver.class, "onOutcome", failure);

                // and nothing out holds the error's text
                assertThat(rig.logs()).as(kind).noneMatch(text -> text.contains(KEY));
                assertThat(first.events().toString()).as(kind).doesNotContain(KEY);
            }
        }
    }

    @Test
    @DisplayName("the provenance function receives the dedup key resolved in this delivery")
    void provenanceFunctionReceivesTheDedupKeyResolvedInThisDelivery(Vertx vertx) {
        // Given a plan with a dedup key, one without, and a two-signal variant of the first
        SignalSourcePlan<ShipmentDelivered> withDedup = ordersSignal(shipments().dedupBy(PlanFixture::dedupKeyOf));
        SignalSourcePlan<ShipmentDelivered> twoSignals = twoSignalPlan(SignalNoMatch.IGNORE);

        // When the first delivers a message with carrier reference PAY-77
        try (DeliveryRig rig = DeliveryRig.over(vertx)) {
            rig.deliver(withDedup, shipment(KEY), envelope(rig.provenance()));

            // Then the function was called once with D-PAY-77, and the recording carries what it answered
            assertThat(rig.provenanceArguments()).containsExactly("D-" + KEY);
            assertThat(rig.signals().get(0).provenance())
                    .isEqualTo(SignalProvenance.camel("carriers", "t-1", "D-" + KEY));
        }

        // and the plan with no dedup key calls it with the envelope's default key
        try (DeliveryRig rig = DeliveryRig.over(vertx)) {
            rig.deliver(keyedPlan(), shipment(KEY), envelope(rig.provenance()));
            assertThat(rig.provenanceArguments()).containsExactly(DEFAULT_DEDUP_KEY);
            assertThat(rig.signals().get(0).provenance())
                    .isEqualTo(SignalProvenance.camel("carriers", "t-1", DEFAULT_DEDUP_KEY));
        }

        // and the two-signal variant calls it once per signal, each recording carrying its own answer
        try (DeliveryRig rig = DeliveryRig.over(vertx)) {
            rig.deliver(twoSignals, shipment(KEY), envelope(rig.provenance()));
            assertThat(rig.provenanceArguments()).containsExactly("D-" + KEY, "D-" + KEY);
            assertThat(rig.signals())
                    .extracting(CorrelatedSignal::provenance)
                    .containsOnly(SignalProvenance.camel("carriers", "t-1", "D-" + KEY));
        }

        // and the function is not called when when rejected the message
        try (DeliveryRig rig = DeliveryRig.over(vertx)) {
            rig.deliver(ordersSignal(shipments().when(body -> false)), shipment(KEY), envelope(rig.provenance()));
            assertThat(rig.provenanceArguments()).isEmpty();
        }
    }

    // --- helpers ---

    /** A plan with the orders signal, whose key is what the given function answers. */
    private static SignalSourcePlan<ShipmentDelivered> keyed(Function<ShipmentDelivered, String> key) {
        return shipments()
                .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                .key(key)
                .payload(PlanFixture::confirmation)
                .build();
    }

    /** A builder whose dispatchAs notes its call and answers alice. */
    private static SignalSourcePlan.Builder<ShipmentDelivered> asAlice(DeliveryRig rig) {
        return shipments().dispatchAs(message -> {
            rig.note("dispatchAs");
            return Future.succeededFuture(Optional.of(user("alice")));
        });
    }

    /** A plan whose every stage notes when it ran; its dispatchAs answers the given identity. */
    private static SignalSourcePlan<ShipmentDelivered> orderedPlan(
            DeliveryRig rig, Optional<SecurityIdentity> identity) {
        return shipments()
                .when(body -> {
                    rig.note("when");
                    return true;
                })
                .dedupBy(body -> {
                    rig.note("dedupBy");
                    return PlanFixture.dedupKeyOf(body);
                })
                .dispatchAs(message -> {
                    rig.note("dispatchAs");
                    return Future.succeededFuture(identity);
                })
                .scope(message -> {
                    rig.note("scope");
                    return Optional.of("acme");
                })
                .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                .keyFrom(message -> {
                    rig.note("lookup");
                    return Future.succeededFuture(Optional.of(message.body().carrierRef()));
                })
                .payload(PlanFixture::confirmation)
                .build();
    }

    /** Delivers three messages in turn, the operations answering one of the given answers to each. */
    private static List<Fate> deliverThree(
            DeliveryRig rig, SignalSourcePlan<ShipmentDelivered> plan, Answer[] answers) {
        rig.operations(answers);
        return List.of(
                rig.deliver(plan, shipment(KEY), envelope()).fate(),
                rig.deliver(plan, shipment(KEY), envelope()).fate(),
                rig.deliver(plan, shipment(KEY), envelope()).fate());
    }
}
