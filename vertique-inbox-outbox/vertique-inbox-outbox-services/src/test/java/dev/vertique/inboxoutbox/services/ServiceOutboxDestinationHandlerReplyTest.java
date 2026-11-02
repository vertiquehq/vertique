// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.inboxoutbox.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.vertique.context.*;
import dev.vertique.core.eventbus.Result;
import dev.vertique.inboxoutbox.*;
import dev.vertique.services.*;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ServiceOutboxDestinationHandlerReplyTest {
    static class PermanentProblem extends RuntimeException implements OutboxPermanentFailure {}

    ServiceTargetResolver resolver = mock(ServiceTargetResolver.class);
    ServiceRequestSender sender = mock(ServiceRequestSender.class);
    DurableContextPropagator propagator = mock(DurableContextPropagator.class);
    DispatchEnvelopeBuilder builder = mock(DispatchEnvelopeBuilder.class);
    ServiceMethodMeta meta = mock(ServiceMethodMeta.class);
    ResolvedServiceTarget target =
            new ResolvedServiceTarget("test.target", null, "test", "target", "op", meta, "test/target/op");
    OutboxOutcomeConsumer consumer = mock(OutboxOutcomeConsumer.class);

    ServiceOutboxDestinationHandler handler(Optional<OutboxOutcomeConsumer> c) {
        when(resolver.resolve("test.target")).thenReturn(target);
        return new ServiceOutboxDestinationHandler(resolver, sender, propagator, builder, c);
    }

    OutboxEnvelope row(int attempt, boolean handle, boolean later) {
        Map<String, String> headers = new HashMap<>();
        if (handle) headers.put(OutboxOutcomeConsumer.HANDLE_HEADER, "handle");
        if (later) headers.put(OutboxOutcomeConsumer.MODE_HEADER, OutboxOutcomeConsumer.MODE_COMPLETES_LATER);
        var control = new OutboxRelayControl(42, UUID.randomUUID(), "event", "Order", "id", 3);
        var metadata = new OutboxMetadata(
                dev.vertique.core.context.DurableMetadata.empty(),
                new OutboxDeliveryMetadata(Optional.of(control), Optional.empty()));
        return new OutboxEnvelope(
                42,
                "Order",
                "id",
                "event",
                "test.target",
                new JsonObject().put("key", "value"),
                headers,
                metadata,
                null,
                attempt,
                Instant.now());
    }

    void ready() {
        when(consumer.awaits("handle")).thenReturn(Future.succeededFuture(true));
        when(propagator.decodeToDispatchContext(any(), any(), any())).thenReturn(Map.of("context", "value"));
        when(sender.send(any(), any())).thenReturn(Future.succeededFuture(Result.success("reply")));
    }

    @Test
    void successReturnsOnlyAfterTheConsumerRecordedIt() {
        ready();
        Promise<Void> recorded = Promise.promise();
        when(consumer.onOutcome(any())).thenReturn(recorded.future());
        var h = handler(Optional.of(consumer));
        var result = h.publish(row(1, true, false));
        assertFalse(result.isComplete());
        var capture = org.mockito.ArgumentCaptor.forClass(OutboxOutcome.class);
        verify(consumer).onOutcome(capture.capture());
        var outcome = assertInstanceOf(OutboxOutcome.Success.class, capture.getValue());
        assertEquals(42, outcome.entryId());
        assertEquals("test.target", outcome.destination());
        assertEquals("handle", outcome.handle());
        assertEquals(2, outcome.attempts());
        assertEquals("reply", outcome.value());
        assertEquals("value", outcome.dispatchContext().get("context"));
        recorded.complete();
        assertInstanceOf(OutboxPublishResult.Success.class, result.result());
        when(consumer.onOutcome(any())).thenReturn(Future.failedFuture("record failed"));
        assertInstanceOf(
                OutboxPublishResult.RetryableFailure.class,
                h.publish(row(0, true, false)).result());
        reset(consumer);
        when(consumer.awaits("handle")).thenReturn(Future.succeededFuture(true));
        assertInstanceOf(
                OutboxPublishResult.Success.class, h.publish(row(0, true, true)).result());
        verify(consumer, never()).onOutcome(any());
    }

    @Test
    void finalFailureIsForwardedAndItsResultStands() {
        when(consumer.awaits("handle")).thenReturn(Future.succeededFuture(true));
        when(consumer.onOutcome(any())).thenReturn(Future.succeededFuture(), Future.failedFuture("failed"));
        when(propagator.decodeToDispatchContext(any(), any(), any())).thenReturn(Map.of());
        when(sender.send(any(), any())).thenReturn(Future.succeededFuture(Result.failure(new RuntimeException("bad"))));
        var h = handler(Optional.of(consumer));
        assertInstanceOf(
                OutboxPublishResult.RetryableFailure.class,
                h.publish(row(1, true, false)).result());
        verify(consumer, never()).onOutcome(any());
        assertInstanceOf(
                OutboxPublishResult.RetryableFailure.class,
                h.publish(row(2, true, false)).result());
        assertInstanceOf(
                OutboxPublishResult.RetryableFailure.class,
                h.publish(row(2, true, true)).result());
        var capture = org.mockito.ArgumentCaptor.forClass(OutboxOutcome.class);
        verify(consumer, times(2)).onOutcome(capture.capture());
        assertEquals(OutboxOutcome.Failure.Kind.RETRIES_EXHAUSTED, ((OutboxOutcome.Failure) capture.getValue()).kind());
        reset(consumer);
        when(consumer.awaits("handle")).thenReturn(Future.succeededFuture(true));
        when(consumer.onOutcome(any())).thenReturn(Future.succeededFuture());
        when(sender.send(any(), any())).thenReturn(Future.succeededFuture(Result.failure(new PermanentProblem())));
        assertInstanceOf(
                OutboxPublishResult.PermanentFailure.class,
                h.publish(row(0, true, false)).result());
        verify(consumer).onOutcome(capture.capture());
        assertEquals(OutboxOutcome.Failure.Kind.PERMANENT, ((OutboxOutcome.Failure) capture.getValue()).kind());
        reset(consumer);
        when(consumer.awaits("handle")).thenReturn(Future.succeededFuture(true));
        when(consumer.onOutcome(any())).thenReturn(Future.succeededFuture());
        when(meta.payloadType()).thenAnswer(ignored -> UUID.class);
        assertInstanceOf(
                OutboxPublishResult.RetryableFailure.class,
                h.publish(row(2, true, false)).result());
        verify(consumer).onOutcome(capture.capture());
        assertEquals(Map.of(), capture.getValue().dispatchContext());
        reset(consumer);
        when(consumer.awaits("handle")).thenReturn(Future.succeededFuture(true));
        when(consumer.onOutcome(any())).thenReturn(Future.succeededFuture());
        when(meta.payloadType()).thenReturn(null);
        when(propagator.decodeToDispatchContext(any(), any(), any()))
                .thenThrow(new IllegalStateException("bad context"));
        assertInstanceOf(
                OutboxPublishResult.RetryableFailure.class,
                h.publish(row(2, true, false)).result());
        verify(consumer).onOutcome(capture.capture());
        assertEquals(Map.of(), capture.getValue().dispatchContext());
        reset(consumer);
        when(consumer.awaits("handle")).thenReturn(Future.succeededFuture(true));
        when(consumer.onOutcome(any())).thenReturn(Future.succeededFuture());
        when(meta.oneWay()).thenReturn(true);
        assertInstanceOf(
                OutboxPublishResult.PermanentFailure.class,
                h.publish(row(0, true, false)).result());
        verify(consumer).onOutcome(capture.capture());
        assertEquals(OutboxOutcome.Failure.Kind.PERMANENT, ((OutboxOutcome.Failure) capture.getValue()).kind());
    }

    @Test
    void closedOrFailedCheckMakesNoCall() {
        when(consumer.awaits("handle"))
                .thenReturn(
                        Future.succeededFuture(false),
                        Future.failedFuture("unavailable"),
                        Future.succeededFuture(true));
        var h = handler(Optional.of(consumer));
        assertInstanceOf(
                OutboxPublishResult.Success.class,
                h.publish(row(0, true, false)).result());
        assertInstanceOf(
                OutboxPublishResult.RetryableFailure.class,
                h.publish(row(2, true, false)).result());
        verifyNoInteractions(sender);
        verify(consumer, never()).onOutcome(any());
        when(propagator.decodeToDispatchContext(any(), any(), any())).thenReturn(Map.of());
        when(sender.send(any(), any())).thenReturn(Future.succeededFuture(Result.success(null)));
        when(consumer.onOutcome(any())).thenReturn(Future.succeededFuture());
        assertInstanceOf(
                OutboxPublishResult.Success.class,
                h.publish(row(0, true, false)).result());
        verify(sender).send(any(), any());
    }

    @Test
    void handleWithNoConsumerDefersWithNoCall() {
        assertInstanceOf(
                OutboxPublishResult.Unresolvable.class,
                handler(Optional.empty()).publish(row(0, true, false)).result());
        verifyNoInteractions(sender);
    }

    @Test
    void rowWithNoHandleNeverReachesTheConsumer() {
        when(propagator.decodeToDispatchContext(any(), any(), any())).thenReturn(Map.of());
        when(sender.send(any(), any()))
                .thenReturn(
                        Future.succeededFuture(Result.success(null)),
                        Future.succeededFuture(Result.failure(new RuntimeException("bad"))));
        var h = handler(Optional.of(consumer));
        assertInstanceOf(
                OutboxPublishResult.Success.class,
                h.publish(row(0, false, false)).result());
        assertInstanceOf(
                OutboxPublishResult.RetryableFailure.class,
                h.publish(row(2, false, false)).result());
        when(sender.send(any(), any())).thenReturn(Future.succeededFuture(Result.failure(new PermanentProblem())));
        assertInstanceOf(
                OutboxPublishResult.PermanentFailure.class,
                h.publish(row(0, false, false)).result());
        when(meta.oneWay()).thenReturn(true);
        assertInstanceOf(
                OutboxPublishResult.PermanentFailure.class,
                h.publish(row(0, false, false)).result());
        when(meta.oneWay()).thenReturn(false);
        when(meta.payloadType()).thenAnswer(ignored -> UUID.class);
        assertInstanceOf(
                OutboxPublishResult.RetryableFailure.class,
                h.publish(row(2, false, false)).result());
        when(meta.payloadType()).thenReturn(null);
        when(propagator.decodeToDispatchContext(any(), any(), any()))
                .thenThrow(new IllegalStateException("bad context"));
        assertInstanceOf(
                OutboxPublishResult.RetryableFailure.class,
                h.publish(row(2, false, false)).result());
        when(resolver.resolve("test.target")).thenThrow(new IllegalArgumentException("missing"));
        assertInstanceOf(
                OutboxPublishResult.Unresolvable.class,
                h.publish(row(0, false, false)).result());
        verifyNoInteractions(consumer);
    }
}
