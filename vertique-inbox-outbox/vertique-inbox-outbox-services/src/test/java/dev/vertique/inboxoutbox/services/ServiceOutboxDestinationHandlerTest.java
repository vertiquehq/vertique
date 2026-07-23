// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DeferredExecutionOrigin;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.Result;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDeliveryMetadata;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxPermanentFailure;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.inboxoutbox.OutboxRelayControl;
import dev.vertique.inboxoutbox.TransactionalMessageContext;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceRequestSender;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies the routing and failure-classification logic of {@link ServiceOutboxDestinationHandler}.
 * Covers destination type declaration, successful relay, unresolvable targets, one-way rejection,
 * permanent vs. retryable failure classification, transport errors, and dispatch context propagation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceOutboxDestinationHandler")
class ServiceOutboxDestinationHandlerTest {

    @Mock
    ServiceTargetResolver resolver;

    @Mock
    ServiceRequestSender sender;

    ServiceOutboxDestinationHandler handler;

    @BeforeEach
    void setUp() {
        dev.vertique.context.DefaultContextHolder holder = new dev.vertique.context.DefaultContextHolder();
        dev.vertique.context.DurableContextPropagator propagator = new dev.vertique.context.DurableContextPropagator(
                new dev.vertique.context.DurableContextMetadataRegistry(java.util.Set.of(), java.util.Set.of()),
                holder,
                new dev.vertique.context.ContextScopeBinder(holder));
        dev.vertique.context.ServiceDispatchContextCapturer capturer =
                new dev.vertique.context.ServiceDispatchContextCapturer(
                        new dev.vertique.context.ServiceDispatchContextRegistry(java.util.Set.of(), java.util.Set.of()),
                        holder);
        dev.vertique.context.DispatchEnvelopeBuilder envelopeBuilder =
                new dev.vertique.context.DispatchEnvelopeBuilder(capturer);
        handler = new ServiceOutboxDestinationHandler(resolver, sender, propagator, envelopeBuilder);
    }

    // --- Helpers ---

    /**
     * Creates a resolved target with a minimal meta mock where only {@code oneWay()} is stubbed.
     * Other meta fields (type/name/operation/resilience) are only accessed inside the real
     * {@link ServiceRequestSender}, which is mocked in these tests and never invoked.
     */
    private ResolvedServiceTarget makeTarget(boolean oneWay) {
        ServiceMethodMeta meta = mock(ServiceMethodMeta.class);
        when(meta.oneWay()).thenReturn(oneWay);
        return new ResolvedServiceTarget("test.target", null, "test", "target", "op", meta, "test/target/op");
    }

    private OutboxEnvelope makeEnvelope() {
        return new OutboxEnvelope(
                42L,
                "Order",
                "order-123",
                "order.placed",
                "test.target",
                new JsonObject().put("key", "value"),
                Map.of("x-custom", "header-val"),
                OutboxMetadata.empty(),
                null,
                0,
                Instant.now());
    }

    /**
     * Builds an envelope shaped like a real relay-built envelope: {@link OutboxMetadata#delivery()}
     * carries an {@link OutboxRelayControl} whose {@link OutboxRelayControl#carrierId()} is the given
     * id — mirroring what {@code OutboxRelay.buildEnvelope} always projects from the row's first-class
     * {@code carrier_id} column (PRD identity-002 F3b).
     */
    private OutboxEnvelope makeEnvelopeWithCarrier(UUID carrierId) {
        OutboxRelayControl relayControl = new OutboxRelayControl(42L, carrierId, "order.placed", "Order", "order-123");
        OutboxMetadata metadata = new OutboxMetadata(
                DurableMetadata.empty(), new OutboxDeliveryMetadata(Optional.of(relayControl), Optional.empty()));
        return new OutboxEnvelope(
                42L,
                "Order",
                "order-123",
                "order.placed",
                "test.target",
                new JsonObject().put("key", "value"),
                Map.of("x-custom", "header-val"),
                metadata,
                null,
                0,
                Instant.now());
    }

    // --- destinationType ---

    @Test
    @DisplayName("destinationType returns SERVICE")
    void destinationTypeIsService() {
        assertEquals(DestinationType.SERVICE, handler.destinationType());
    }

    // --- publish ---

    @Nested
    @DisplayName("publish")
    class Publish {

        @Test
        @DisplayName("successful service reply returns Success")
        void successfulReplyReturnsSuccess() {
            ResolvedServiceTarget target = makeTarget(false);
            when(resolver.resolve("test.target")).thenReturn(target);
            when(sender.send(eq(target), any())).thenReturn(Future.succeededFuture(Result.success(null)));

            OutboxPublishResult result = handler.publish(makeEnvelope()).result();

            assertInstanceOf(OutboxPublishResult.Success.class, result);
        }

        @Test
        @DisplayName("resolver miss returns Unresolvable")
        void resolverMissReturnsUnresolvable() {
            when(resolver.resolve("test.target")).thenThrow(new IllegalArgumentException("not found"));

            OutboxPublishResult result = handler.publish(makeEnvelope()).result();

            assertInstanceOf(OutboxPublishResult.Unresolvable.class, result);
        }

        @Test
        @DisplayName("@OneWay target returns PermanentFailure")
        void oneWayTargetReturnsPermanentFailure() {
            ResolvedServiceTarget target = makeTarget(true);
            when(resolver.resolve("test.target")).thenReturn(target);

            OutboxPublishResult result = handler.publish(makeEnvelope()).result();

            assertInstanceOf(OutboxPublishResult.PermanentFailure.class, result);
        }

        @Test
        @DisplayName("service failure with OutboxPermanentFailure cause returns PermanentFailure")
        void serviceFailureWithPermanentMarkerReturnsPermanentFailure() {
            ResolvedServiceTarget target = makeTarget(false);
            when(resolver.resolve("test.target")).thenReturn(target);

            Throwable cause = new PermanentValidationException("validation failed");
            when(sender.send(eq(target), any())).thenReturn(Future.succeededFuture(Result.failure(cause)));

            OutboxPublishResult result = handler.publish(makeEnvelope()).result();

            assertInstanceOf(OutboxPublishResult.PermanentFailure.class, result);
        }

        @Test
        @DisplayName("service failure without marker returns RetryableFailure")
        void serviceFailureWithoutMarkerReturnsRetryableFailure() {
            ResolvedServiceTarget target = makeTarget(false);
            when(resolver.resolve("test.target")).thenReturn(target);
            when(sender.send(eq(target), any()))
                    .thenReturn(Future.succeededFuture(Result.failure(new RuntimeException("transient error"))));

            OutboxPublishResult result = handler.publish(makeEnvelope()).result();

            assertInstanceOf(OutboxPublishResult.RetryableFailure.class, result);
        }

        @Test
        @DisplayName("transport error returns RetryableFailure")
        void transportErrorReturnsRetryableFailure() {
            ResolvedServiceTarget target = makeTarget(false);
            when(resolver.resolve("test.target")).thenReturn(target);
            when(sender.send(eq(target), any()))
                    .thenReturn(Future.failedFuture(new RuntimeException("event bus timeout")));

            OutboxPublishResult result = handler.publish(makeEnvelope()).result();

            assertInstanceOf(OutboxPublishResult.RetryableFailure.class, result);
        }

        @Test
        @DisplayName("propagates TransactionalMessageContext in DispatchEnvelope dispatch context")
        void propagatesTransactionalMessageContextInDispatchContext() {
            ResolvedServiceTarget target = makeTarget(false);
            when(resolver.resolve("test.target")).thenReturn(target);

            ArgumentCaptor<DispatchEnvelope<?>> bodyCaptor = forClass(DispatchEnvelope.class);
            when(sender.send(eq(target), bodyCaptor.capture()))
                    .thenReturn(Future.succeededFuture(Result.success(null)));

            OutboxEnvelope envelope = makeEnvelope();
            handler.publish(envelope).result();

            DispatchEnvelope<?> capturedBody = bodyCaptor.getValue();
            assertNotNull(capturedBody, "DispatchEnvelope must not be null");

            Object ctx = capturedBody.metadata().dispatchContext().get(TransactionalMessageContext.class.getName());
            assertInstanceOf(TransactionalMessageContext.class, ctx);

            TransactionalMessageContext txCtx = (TransactionalMessageContext) ctx;
            assertEquals(envelope.entryId(), txCtx.messageId());
            assertEquals(envelope.eventType(), txCtx.eventType());
            assertEquals(envelope.aggregateType(), txCtx.aggregateType());
            assertEquals(envelope.aggregateId(), txCtx.aggregateId());
        }

        @Test
        @DisplayName("binds DeferredExecutionOrigin(outbox-relay, eventType) in DispatchEnvelope dispatch context")
        void bindsDeferredExecutionOriginInDispatchContext() {
            ResolvedServiceTarget target = makeTarget(false);
            when(resolver.resolve("test.target")).thenReturn(target);

            ArgumentCaptor<DispatchEnvelope<?>> bodyCaptor = forClass(DispatchEnvelope.class);
            when(sender.send(eq(target), bodyCaptor.capture()))
                    .thenReturn(Future.succeededFuture(Result.success(null)));

            OutboxEnvelope envelope = makeEnvelope();
            handler.publish(envelope).result();

            DispatchEnvelope<?> capturedBody = bodyCaptor.getValue();
            assertNotNull(capturedBody, "DispatchEnvelope must not be null");

            Object origin = capturedBody.metadata().dispatchContext().get(DeferredExecutionOrigin.class.getName());
            assertInstanceOf(DeferredExecutionOrigin.class, origin);

            DeferredExecutionOrigin deferredOrigin = (DeferredExecutionOrigin) origin;
            assertEquals("outbox-relay", deferredOrigin.kind());
            assertEquals(envelope.eventType(), deferredOrigin.reference());
        }

        @Test
        @DisplayName("blank eventType falls back to kind so the DeferredExecutionOrigin ctor never throws in publish()")
        void blankEventTypeFallsBackToKind() {
            ResolvedServiceTarget target = makeTarget(false);
            when(resolver.resolve("test.target")).thenReturn(target);

            ArgumentCaptor<DispatchEnvelope<?>> bodyCaptor = forClass(DispatchEnvelope.class);
            when(sender.send(eq(target), bodyCaptor.capture()))
                    .thenReturn(Future.succeededFuture(Result.success(null)));

            // Blank eventType: the strict DeferredExecutionOrigin ctor would throw synchronously out
            // of publish() (violating its always-completes contract) without the fallback guard.
            OutboxEnvelope blankEventType = new OutboxEnvelope(
                    42L,
                    "Order",
                    "order-123",
                    "   ",
                    "test.target",
                    new JsonObject().put("key", "value"),
                    Map.of("x-custom", "header-val"),
                    OutboxMetadata.empty(),
                    null,
                    0,
                    Instant.now());

            OutboxPublishResult result = handler.publish(blankEventType).result();

            // publish() completed (did not throw) despite the blank eventType.
            assertInstanceOf(OutboxPublishResult.Success.class, result);

            DispatchEnvelope<?> capturedBody = bodyCaptor.getValue();
            assertNotNull(capturedBody, "DispatchEnvelope must not be null");

            Object origin = capturedBody.metadata().dispatchContext().get(DeferredExecutionOrigin.class.getName());
            assertInstanceOf(DeferredExecutionOrigin.class, origin);

            DeferredExecutionOrigin deferredOrigin = (DeferredExecutionOrigin) origin;
            assertEquals("outbox-relay", deferredOrigin.kind());
            assertEquals("outbox-relay", deferredOrigin.reference(), "blank eventType must fall back to the kind");
        }
    }

    // --- F3b: per-row carrier reproduction ---

    @Nested
    @DisplayName("F3b per-row carrier reproduction (PRD identity-002 §14.6/A9)")
    class CarrierReproduction {

        /** Namespace used by the recording test decoder. */
        private static final String NS = "test-namespace";

        /** Minimal typed durable value the recording decoder produces. */
        record TestValue(String value) implements ContextValue {}

        /**
         * Builds a handler wired with a propagator carrying the given recording decoder, so the
         * {@link DurableDecodeContext} threaded into {@link #decode} can be captured and asserted.
         */
        private ServiceOutboxDestinationHandler handlerWithDecoder(DurableContextMetadataDecoder<TestValue> decoder) {
            dev.vertique.context.DefaultContextHolder holder = new dev.vertique.context.DefaultContextHolder();
            dev.vertique.context.DurableContextPropagator recordingPropagator =
                    new dev.vertique.context.DurableContextPropagator(
                            new DurableContextMetadataRegistry(Set.of(), Set.of(decoder)),
                            holder,
                            new dev.vertique.context.ContextScopeBinder(holder));
            dev.vertique.context.ServiceDispatchContextCapturer capturer =
                    new dev.vertique.context.ServiceDispatchContextCapturer(
                            new dev.vertique.context.ServiceDispatchContextRegistry(Set.of(), Set.of()), holder);
            dev.vertique.context.DispatchEnvelopeBuilder envelopeBuilder =
                    new dev.vertique.context.DispatchEnvelopeBuilder(capturer);
            return new ServiceOutboxDestinationHandler(resolver, sender, recordingPropagator, envelopeBuilder);
        }

        @Test
        @DisplayName("carrier reproduced from OutboxRelayControl.carrierId() matches the produce-side shape: "
                + "carrierId == the row's carrier id, target kind == outbox-relay, address == carrierId")
        void reproducesCarrierFromRelayControl() {
            UUID carrierId = UUID.randomUUID();
            AtomicReference<DurableDecodeContext> observed = new AtomicReference<>();
            DurableContextMetadataDecoder<TestValue> recordingDecoder = new DurableContextMetadataDecoder<>() {
                @Override
                public Class<TestValue> type() {
                    return TestValue.class;
                }

                @Override
                public String namespace() {
                    return NS;
                }

                @Override
                public ContextDecodeResult<TestValue> decode(DurableMetadata metadata, DurableDecodeContext context) {
                    observed.set(context);
                    return ContextDecodeResult.empty();
                }
            };
            ServiceOutboxDestinationHandler recordingHandler = handlerWithDecoder(recordingDecoder);

            ResolvedServiceTarget target = makeTarget(false);
            when(resolver.resolve("test.target")).thenReturn(target);
            when(sender.send(eq(target), any())).thenReturn(Future.succeededFuture(Result.success(null)));

            recordingHandler.publish(makeEnvelopeWithCarrier(carrierId)).result();

            assertNotNull(observed.get(), "decoder must have been invoked");
            assertTrue(observed.get().carrier().isPresent(), "the outbox-service boundary must thread a real carrier");
            assertEquals(
                    carrierId.toString(),
                    observed.get().carrier().orElseThrow().carrierId(),
                    "reproduced carrierId must equal the row's OutboxRelayControl.carrierId()");
            assertEquals(
                    "outbox-relay",
                    observed.get().carrier().orElseThrow().target().kind(),
                    "target kind must be the canonical outbox-relay string (F7a)");
            assertEquals(
                    carrierId.toString(),
                    observed.get().carrier().orElseThrow().target().address(),
                    "target address must equal the carrierId (both sides derive it identically)");
        }

        @Test
        @DisplayName("no OutboxRelayControl on the envelope falls back to the carrier-less decode overload"
                + " (preserves the F3a fail-closed sentinel path rather than throwing)")
        void fallsBackToCarrierLessDecodeWhenRelayControlAbsent() {
            AtomicReference<DurableDecodeContext> observed = new AtomicReference<>();
            DurableContextMetadataDecoder<TestValue> recordingDecoder = new DurableContextMetadataDecoder<>() {
                @Override
                public Class<TestValue> type() {
                    return TestValue.class;
                }

                @Override
                public String namespace() {
                    return NS;
                }

                @Override
                public ContextDecodeResult<TestValue> decode(DurableMetadata metadata, DurableDecodeContext context) {
                    observed.set(context);
                    return ContextDecodeResult.empty();
                }
            };
            ServiceOutboxDestinationHandler recordingHandler = handlerWithDecoder(recordingDecoder);

            ResolvedServiceTarget target = makeTarget(false);
            when(resolver.resolve("test.target")).thenReturn(target);
            when(sender.send(eq(target), any())).thenReturn(Future.succeededFuture(Result.success(null)));

            // makeEnvelope() (no relay control) mirrors a hand-built fixture missing OutboxRelayControl.
            recordingHandler.publish(makeEnvelope()).result();

            assertNotNull(observed.get(), "decoder must have been invoked");
            assertTrue(
                    observed.get().carrier().isEmpty(),
                    "with no OutboxRelayControl, decode must fall back to the carrier-less overload");
        }

        @Test
        @DisplayName("two envelopes with different carrierIds reproduce different carriers")
        void differentCarrierIdsReproduceDifferentCarriers() {
            AtomicReference<DurableDecodeContext> observed = new AtomicReference<>();
            DurableContextMetadataDecoder<TestValue> recordingDecoder = new DurableContextMetadataDecoder<>() {
                @Override
                public Class<TestValue> type() {
                    return TestValue.class;
                }

                @Override
                public String namespace() {
                    return NS;
                }

                @Override
                public ContextDecodeResult<TestValue> decode(DurableMetadata metadata, DurableDecodeContext context) {
                    observed.set(context);
                    return ContextDecodeResult.empty();
                }
            };
            ServiceOutboxDestinationHandler recordingHandler = handlerWithDecoder(recordingDecoder);

            ResolvedServiceTarget target = makeTarget(false);
            when(resolver.resolve("test.target")).thenReturn(target);
            when(sender.send(eq(target), any())).thenReturn(Future.succeededFuture(Result.success(null)));

            UUID carrierA = UUID.randomUUID();
            recordingHandler.publish(makeEnvelopeWithCarrier(carrierA)).result();
            String reproducedA = observed.get().carrier().orElseThrow().carrierId();

            UUID carrierB = UUID.randomUUID();
            recordingHandler.publish(makeEnvelopeWithCarrier(carrierB)).result();
            String reproducedB = observed.get().carrier().orElseThrow().carrierId();

            assertEquals(carrierA.toString(), reproducedA);
            assertEquals(carrierB.toString(), reproducedB);
            assertNotEquals(reproducedA, reproducedB, "carriers reproduced for different rows must not match");
        }
    }

    // --- Test doubles ---

    /** Permanent failure marker exception for testing. */
    static class PermanentValidationException extends RuntimeException implements OutboxPermanentFailure {
        PermanentValidationException(String message) {
            super(message);
        }
    }
}
