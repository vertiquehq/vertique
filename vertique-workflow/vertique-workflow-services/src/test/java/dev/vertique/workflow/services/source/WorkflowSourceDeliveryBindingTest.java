// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import static dev.vertique.workflow.services.source.DeliveryFixture.assertAmbientIs;
import static dev.vertique.workflow.services.source.DeliveryFixture.assertNothingBound;
import static dev.vertique.workflow.services.source.DeliveryFixture.await;
import static dev.vertique.workflow.services.source.DeliveryFixture.correlation;
import static dev.vertique.workflow.services.source.DeliveryFixture.envelope;
import static dev.vertique.workflow.services.source.DeliveryFixture.failureOf;
import static dev.vertique.workflow.services.source.DeliveryFixture.security;
import static dev.vertique.workflow.services.source.DeliveryFixture.signal;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.SecurityContext;
import dev.vertique.workflow.exception.WorkflowSignalNoMatchException;
import dev.vertique.workflow.ops.CorrelatedSignal;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt;
import dev.vertique.workflow.ops.SignalProvenance;
import dev.vertique.workflow.services.source.DeliveryFixture.Ambient;
import dev.vertique.workflow.services.source.DeliveryFixture.CallerMarker;
import dev.vertique.workflow.services.source.DeliveryFixture.Entry;
import dev.vertique.workflow.services.source.DeliveryFixture.Overlap;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies how {@link WorkflowSourceDelivery} records a correlated signal for a source: under the
 * envelope's own security context, correlation and origin, bound on a duplicate of the calling
 * context that is closed whichever way the recording ends, with the provenance the envelope
 * builds for the signal's dedup key, and with the returned future completing back on the context
 * the caller delivered from.
 *
 * <p>The operations are a fake that only records what it receives; the pool is a mock that applies
 * its function to a mocked connection. No database is involved.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class WorkflowSourceDeliveryBindingTest {

    private static final SignalProvenance KAFKA_PROVENANCE =
            SignalProvenance.kafka("carriers", "carrier.events", 3, 42);

    @Test
    @DisplayName("a key delivery records under the envelope's binding and carries its provenance")
    void keyDeliveryRecordsUnderTheEnvelopeBindingAndCarriesItsProvenance(Vertx vertx) {
        // Given a caller whose own context carries a different identity
        DeliveryFixture fixture = DeliveryFixture.succeeding(vertx);
        SecurityContext callerSecurity = security("caller");
        List<String> provenanceAskedFor = new CopyOnWriteArrayList<>();
        Function<String, SignalProvenance> provenance = dedupKey -> {
            provenanceAskedFor.add(dedupKey);
            return KAFKA_PROVENANCE;
        };
        SourceEnvelope envelope = envelope(security("kafka source: carriers"), correlation("C-1"), provenance);
        CorrelatedSignal signal = signal("PAY-77", "d-1");

        try (DeliveryFixture.Caller caller = fixture.callerOnDuplicate(callerSecurity)) {
            // When the signal is recorded from the caller's context
            Future<CorrelatedSignalReceipt> returned = fixture.record(caller, signal, false, envelope);

            // Then the operations saw the envelope's binding and not the caller's
            assertThat(await(returned)).isEqualTo(DeliveryFixture.RECEIPT);
            assertThat(fixture.observed().entries()).hasSize(1);
            Entry entry = fixture.observed().entries().get(0);
            assertThat(entry.method()).isEqualTo("signalByKey");
            assertAmbientIs(entry.atEntry(), envelope);
            assertThat(entry.atEntry().security().identity())
                    .as("the caller's identity is not the recording's")
                    .isNotEqualTo(callerSecurity.identity());

            // and ran on a duplicated context that is not the caller's
            assertThat(entry.context().isDuplicate()).isTrue();
            assertThat(entry.context()).isNotSameAs(caller.context());

            // and received the signal with the envelope's provenance set and nothing else changed
            assertThat(entry.signal()).isEqualTo(signal.withProvenance(KAFKA_PROVENANCE));
            assertThat(provenanceAskedFor).containsExactly("d-1");

            // and the connection the pool handed out, through the key method only
            assertThat(entry.transaction()).isSameAs(fixture.connection());
            assertThat(fixture.observed().callsTo("signalByBusinessKey")).isZero();
        }
    }

    @Test
    @DisplayName("a business-key delivery calls only the business-key method under the same binding")
    void businessKeyDeliveryCallsOnlyTheBusinessKeyMethodUnderTheSameBinding(Vertx vertx) {
        // Given an envelope with no correlation, whose provenance depends on the dedup key
        DeliveryFixture fixture = DeliveryFixture.succeeding(vertx);
        SecurityContext callerSecurity = security("caller");
        Function<String, SignalProvenance> provenance =
                dedupKey -> SignalProvenance.camel("carriers", "trigger-1", "hash-of-" + dedupKey);
        SourceEnvelope envelope = envelope(security("kafka source: carriers"), null, provenance);
        CorrelatedSignal signal = signal("ORDER-5", "d-2");

        try (DeliveryFixture.Caller caller = fixture.callerOnDuplicate(callerSecurity)) {
            // When the signal is recorded by business key
            Future<CorrelatedSignalReceipt> returned = fixture.record(caller, signal, true, envelope);

            // Then only the business-key method ran, once, under the envelope's identity and origin
            assertThat(await(returned)).isEqualTo(DeliveryFixture.RECEIPT);
            assertThat(fixture.observed().entries()).hasSize(1);
            Entry entry = fixture.observed().entries().get(0);
            assertThat(entry.method()).isEqualTo("signalByBusinessKey");
            assertThat(fixture.observed().callsTo("signalByKey")).isZero();
            assertAmbientIs(entry.atEntry(), envelope);

            // and no correlation was bound
            assertThat(entry.atEntry().correlation()).isNull();

            // and the signal carries the provenance the envelope answers for its own dedup key
            assertThat(entry.signal())
                    .isEqualTo(signal.withProvenance(SignalProvenance.camel("carriers", "trigger-1", "hash-of-d-2")));
        }
    }

    @Test
    @DisplayName("the binding is released once the recording succeeds")
    void bindingIsReleasedOnceTheRecordingSucceeds(Vertx vertx) {
        // Given a recording held open, and a caller that holds a binding of its own
        DeliveryFixture fixture = DeliveryFixture.holdingOpen(vertx);
        CallerMarker marker = new CallerMarker("caller");
        SourceEnvelope envelope =
                envelope(security("kafka source: carriers"), correlation("C-1"), dedupKey -> KAFKA_PROVENANCE);

        try (DeliveryFixture.Caller caller = fixture.callerOnDuplicate().binding(CallerMarker.class, marker)) {
            // When the recording is started, completed, and the returned future has completed
            Future<CorrelatedSignalReceipt> returned = fixture.record(caller, signal("PAY-77", "d-3"), false, envelope);
            Entry entry = fixture.observed().awaitEntry();
            try {
                // Then the recording's context holds the envelope's identity while it is open
                assertAmbientIs(fixture.ambientOn(entry.context()), envelope);

                entry.release();
                await(returned);
            } finally {
                entry.release();
            }

            // and, once the future completed, that same context holds none of the three values
            assertNothingBound(fixture.ambientOn(entry.context()));

            // and the caller's own binding is untouched
            assertThat(fixture.currentOn(caller, CallerMarker.class)).isEqualTo(marker);
        }
    }

    @Test
    @DisplayName("a failed recording reaches the caller unchanged and still releases the binding")
    void failedRecordingReachesTheCallerUnchangedAndReleasesTheBinding(Vertx vertx) {
        // Given one fake whose recording fails and one that throws synchronously
        WorkflowSignalNoMatchException failure = new WorkflowSignalNoMatchException("orders", "shipment-delivered");
        IllegalStateException thrown = new IllegalStateException("recording refused");
        DeliveryFixture failing = DeliveryFixture.failing(vertx, failure);
        DeliveryFixture throwing = DeliveryFixture.throwing(vertx, thrown);
        SourceEnvelope envelope =
                envelope(security("kafka source: carriers"), correlation("C-1"), dedupKey -> KAFKA_PROVENANCE);

        try (DeliveryFixture.Caller failingCaller = failing.callerOnDuplicate();
                DeliveryFixture.Caller throwingCaller = throwing.callerOnDuplicate()) {
            // When a signal is recorded through each
            Future<CorrelatedSignalReceipt> failedFuture =
                    failing.record(failingCaller, signal("PAY-77", "d-4"), false, envelope);
            Future<CorrelatedSignalReceipt> thrownFuture =
                    throwing.record(throwingCaller, signal("PAY-77", "d-5"), false, envelope);

            // Then each returned future fails with the very exception the fake produced
            assertThat(failureOf(failedFuture)).isSameAs(failure);
            assertThat(failureOf(thrownFuture)).isSameAs(thrown);

            // and neither delivery's context still holds a bound value
            assertAmbientIs(failing.observed().entries().get(0).atEntry(), envelope);
            assertAmbientIs(throwing.observed().entries().get(0).atEntry(), envelope);
            assertNothingBound(
                    failing.ambientOn(failing.observed().entries().get(0).context()));
            assertNothingBound(
                    throwing.ambientOn(throwing.observed().entries().get(0).context()));
        }
    }

    @Test
    @DisplayName("two overlapping deliveries never see each other's binding")
    void overlappingDeliveriesNeverSeeEachOthersBinding(Vertx vertx) {
        // Given two deliveries with their own identities and correlations
        DeliveryFixture fixture = DeliveryFixture.holdingOpen(vertx);
        CorrelationContext correlationA = correlation("C-A");
        CorrelationContext correlationB = correlation("C-B");
        SourceEnvelope alpha = envelope(security("kafka source: alpha"), correlationA, dedupKey -> KAFKA_PROVENANCE);
        SourceEnvelope beta = envelope(security("kafka source: beta"), correlationB, dedupKey -> KAFKA_PROVENANCE);

        // When A starts, B starts while A is open, then B completes and A completes, from a foreign context
        Overlap overlap = fixture.overlap(alpha, beta);

        // Then at entry and again after resumption each recording saw only its own values
        assertAmbientIs(overlap.first().atEntry(), alpha);
        assertAmbientIs(overlap.first().afterResumption(), alpha);
        assertAmbientIs(overlap.second().atEntry(), beta);
        assertAmbientIs(overlap.second().afterResumption(), beta);
        assertThat(overlap.first().atEntry().security().identity())
                .isNotEqualTo(overlap.second().atEntry().security().identity());

        // and they ran on two different duplicated contexts
        assertThat(overlap.first().context().isDuplicate()).isTrue();
        assertThat(overlap.second().context().isDuplicate()).isTrue();
        assertThat(overlap.first().context()).isNotSameAs(overlap.second().context());

        // and once both futures completed neither delivery context holds a value
        assertNothingBound(overlap.firstAfterCompletion());
        assertNothingBound(overlap.secondAfterCompletion());
    }

    @Test
    @DisplayName("the returned future completes on the context the caller delivered from")
    void returnedFutureCompletesOnTheContextTheCallerDeliveredFrom(Vertx vertx) {
        // Given a recording held open on a promise that belongs to an unrelated context B
        ContextInternal b = (ContextInternal) vertx.getOrCreateContext();
        DeliveryFixture fixture = DeliveryFixture.holdingOpenOn(vertx, b);
        SourceEnvelope envelope =
                envelope(security("kafka source: carriers"), correlation("C-1"), dedupKey -> KAFKA_PROVENANCE);

        // and the delivery starts from context A
        try (DeliveryFixture.Caller a = fixture.callerOnRoot()) {
            assertThat(a.context()).isNotSameAs(b);
            Future<CorrelatedSignalReceipt> returned = fixture.record(a, signal("PAY-77", "d-6"), false, envelope);
            Entry entry = fixture.observed().awaitEntry();

            // When a completion handler is registered on the returned future and B completes the recording
            AtomicReference<Context> ranOn = new AtomicReference<>();
            AtomicReference<Ambient> deliveryContextWhenItRan = new AtomicReference<>();
            CompletableFuture<Void> handled = new CompletableFuture<>();
            returned.onComplete(result -> {
                ranOn.set(Vertx.currentContext());
                deliveryContextWhenItRan.set(fixture.ambientDispatchedOn(entry.context()));
                handled.complete(null);
            });
            try {
                entry.release(b);
                DeliveryFixture.join(handled);
            } finally {
                entry.release();
            }

            // Then the handler ran on A, not on B and not on the delivery's duplicate
            assertThat(ranOn.get()).isSameAs(a.context());
            assertThat(ranOn.get()).isNotSameAs(b);
            assertThat(ranOn.get()).isNotSameAs(entry.context());

            // and the binding had already been closed when it ran
            assertThat(deliveryContextWhenItRan.get()).isNotNull();
            assertNothingBound(deliveryContextWhenItRan.get());
        }
    }

    @Test
    @DisplayName("a caller's ambient correlation never reaches a recording whose envelope has none")
    void aCallersAmbientCorrelationNeverReachesTheRecording(Vertx vertx) {
        // Given a caller whose own duplicated context holds a correlation, and an envelope that has none
        DeliveryFixture fixture = DeliveryFixture.succeeding(vertx);
        CorrelationContext callerCorrelation = correlation("C-caller");
        SourceEnvelope envelope = envelope(security("kafka source: carriers"), null, dedupKey -> KAFKA_PROVENANCE);

        try (DeliveryFixture.Caller caller =
                fixture.callerOnDuplicate().binding(CorrelationContext.class, callerCorrelation)) {
            assertThat(fixture.currentOn(caller, CorrelationContext.class))
                    .as("the caller holds its own correlation")
                    .isEqualTo(callerCorrelation);

            // When the signal is recorded from the caller's context
            Future<CorrelatedSignalReceipt> returned = fixture.record(caller, signal("PAY-77", "d-7"), false, envelope);

            // Then the recording saw no correlation, and the envelope's identity and origin
            assertThat(await(returned)).isEqualTo(DeliveryFixture.RECEIPT);
            assertThat(fixture.observed().entries()).hasSize(1);
            Entry entry = fixture.observed().entries().get(0);
            assertThat(entry.atEntry().correlation()).isNull();
            assertAmbientIs(entry.atEntry(), envelope);

            // and the caller's own correlation is untouched
            assertThat(fixture.currentOn(caller, CorrelationContext.class)).isEqualTo(callerCorrelation);
        }
    }
}
