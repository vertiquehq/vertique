// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.inboxoutbox.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.eventbus.Result;
import dev.vertique.inboxoutbox.OutboxDeliveryMetadata;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxOutcome;
import dev.vertique.inboxoutbox.OutboxOutcomeConsumer;
import dev.vertique.inboxoutbox.OutboxPermanentFailure;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.inboxoutbox.OutboxRelayControl;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceRequestSender;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
        for (var forwarded : capture.getAllValues()) {
            var failure = assertInstanceOf(OutboxOutcome.Failure.class, forwarded);
            assertEquals(OutboxOutcome.Failure.Kind.RETRIES_EXHAUSTED, failure.kind());
            assertEquals(3, failure.attempts());
        }
        reset(consumer);
        when(consumer.awaits("handle")).thenReturn(Future.succeededFuture(true));
        when(consumer.onOutcome(any())).thenReturn(Future.succeededFuture());
        when(sender.send(any(), any())).thenReturn(Future.succeededFuture(Result.failure(new PermanentProblem())));
        assertInstanceOf(
                OutboxPublishResult.PermanentFailure.class,
                h.publish(row(0, true, false)).result());
        verify(consumer).onOutcome(capture.capture());
        assertEquals(OutboxOutcome.Failure.Kind.PERMANENT, ((OutboxOutcome.Failure) capture.getValue()).kind());
        assertEquals(1, capture.getValue().attempts());
        reset(consumer);
        when(consumer.awaits("handle")).thenReturn(Future.succeededFuture(true));
        when(consumer.onOutcome(any())).thenReturn(Future.succeededFuture());
        when(meta.payloadType()).thenAnswer(ignored -> UUID.class);
        assertInstanceOf(
                OutboxPublishResult.RetryableFailure.class,
                h.publish(row(2, true, false)).result());
        verify(consumer).onOutcome(capture.capture());
        assertEquals(Map.of(), capture.getValue().dispatchContext());
        assertEquals(OutboxOutcome.Failure.Kind.RETRIES_EXHAUSTED, ((OutboxOutcome.Failure) capture.getValue()).kind());
        assertEquals(3, capture.getValue().attempts());
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
        assertEquals(OutboxOutcome.Failure.Kind.RETRIES_EXHAUSTED, ((OutboxOutcome.Failure) capture.getValue()).kind());
        assertEquals(3, capture.getValue().attempts());
        reset(consumer);
        when(consumer.awaits("handle")).thenReturn(Future.succeededFuture(true));
        when(consumer.onOutcome(any())).thenReturn(Future.succeededFuture());
        when(meta.oneWay()).thenReturn(true);
        assertInstanceOf(
                OutboxPublishResult.PermanentFailure.class,
                h.publish(row(0, true, false)).result());
        verify(consumer).onOutcome(capture.capture());
        assertEquals(OutboxOutcome.Failure.Kind.PERMANENT, ((OutboxOutcome.Failure) capture.getValue()).kind());
        assertEquals(1, capture.getValue().attempts());
    }

    @Test
    void failedConsumerCannotChangeAServiceFailureDisposition() {
        ready();
        when(consumer.onOutcome(any())).thenReturn(Future.failedFuture("unavailable"));
        var h = handler(Optional.of(consumer));
        when(sender.send(any(), any())).thenReturn(Future.succeededFuture(Result.failure(new PermanentProblem())));
        assertInstanceOf(
                OutboxPublishResult.PermanentFailure.class,
                h.publish(row(0, true, false)).result());
        when(sender.send(any(), any())).thenReturn(Future.succeededFuture(Result.failure(new RuntimeException("bad"))));
        assertInstanceOf(
                OutboxPublishResult.RetryableFailure.class,
                h.publish(row(2, true, false)).result());
        var capture = org.mockito.ArgumentCaptor.forClass(OutboxOutcome.class);
        verify(consumer, times(2)).onOutcome(capture.capture());
        var permanent = assertInstanceOf(
                OutboxOutcome.Failure.class, capture.getAllValues().get(0));
        assertEquals(OutboxOutcome.Failure.Kind.PERMANENT, permanent.kind());
        assertEquals(1, permanent.attempts());
        var retryable = assertInstanceOf(
                OutboxOutcome.Failure.class, capture.getAllValues().get(1));
        assertEquals(OutboxOutcome.Failure.Kind.RETRIES_EXHAUSTED, retryable.kind());
        assertEquals(3, retryable.attempts());
    }

    @Test
    void failedSendFutureOnLastAttemptIsForwarded() {
        ready();
        when(consumer.onOutcome(any())).thenReturn(Future.succeededFuture());
        when(sender.send(any(), any())).thenReturn(Future.failedFuture("unavailable"));
        assertInstanceOf(
                OutboxPublishResult.RetryableFailure.class,
                handler(Optional.of(consumer)).publish(row(2, true, false)).result());
        var capture = org.mockito.ArgumentCaptor.forClass(OutboxOutcome.class);
        verify(consumer).onOutcome(capture.capture());
        var failure = assertInstanceOf(OutboxOutcome.Failure.class, capture.getValue());
        assertEquals(OutboxOutcome.Failure.Kind.RETRIES_EXHAUSTED, failure.kind());
        assertEquals(3, failure.attempts());
    }

    @Test
    void decodeFailureBeforeLastAttemptDoesNotForward() {
        when(consumer.awaits("handle")).thenReturn(Future.succeededFuture(true));
        when(meta.payloadType()).thenAnswer(ignored -> UUID.class);
        assertInstanceOf(
                OutboxPublishResult.RetryableFailure.class,
                handler(Optional.of(consumer)).publish(row(1, true, false)).result());
        verify(consumer, never()).onOutcome(any());
        verifyNoInteractions(sender);
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
    void replyFailuresHideExceptionMessages() {
        String secret = "SECRET-TOKEN-77";
        ready();
        var h = handler(Optional.of(consumer));

        when(sender.send(any(), any()))
                .thenReturn(Future.succeededFuture(Result.failure(new IllegalStateException(secret))));
        var serviceFailure = assertInstanceOf(
                OutboxPublishResult.RetryableFailure.class,
                h.publish(row(0, true, false)).result());
        assertFalse(serviceFailure.message().contains(secret));
        assertTrue(serviceFailure.cause() == null
                || !String.valueOf(serviceFailure.cause()).contains(secret));

        when(sender.send(any(), any())).thenReturn(Future.succeededFuture(Result.success("reply")));
        when(consumer.onOutcome(any())).thenReturn(Future.failedFuture(new IllegalStateException(secret)));
        var recordingFailure = assertInstanceOf(
                OutboxPublishResult.RetryableFailure.class,
                h.publish(row(0, true, false)).result());
        assertFalse(recordingFailure.message().contains(secret));
        assertTrue(recordingFailure.cause() == null
                || !String.valueOf(recordingFailure.cause()).contains(secret));
    }

    @Test
    void ordinaryFailuresKeepTheirOriginalMessage() {
        String secret = "SECRET-TOKEN-77";
        ready();
        when(sender.send(any(), any()))
                .thenReturn(Future.succeededFuture(Result.failure(new IllegalStateException(secret))));
        var ordinaryFailure = assertInstanceOf(
                OutboxPublishResult.RetryableFailure.class,
                handler(Optional.of(consumer)).publish(row(0, false, false)).result());
        assertEquals(secret, ordinaryFailure.message());
    }

    @Test
    void synchronousResolverAndWaitErrorsAreRedactedForReplyRows() {
        String secret = "SECRET-SYNC-77";
        var h = handler(Optional.of(consumer));
        var resolverError = new AssertionError(secret);
        when(resolver.resolve("test.target")).thenThrow(resolverError);
        var resolverResult = assertInstanceOf(
                OutboxPublishResult.RetryableFailure.class,
                h.publish(row(0, true, false)).result());
        assertFalse(resolverResult.message().contains(secret));
        assertTrue(resolverResult.cause() == null
                || !String.valueOf(resolverResult.cause()).contains(secret));
        assertSame(resolverError, assertThrows(AssertionError.class, () -> h.publish(row(0, false, false))));

        reset(resolver);
        when(resolver.resolve("test.target")).thenReturn(target);
        when(consumer.awaits("handle")).thenThrow(new AssertionError(secret));
        var waitResult = assertInstanceOf(
                OutboxPublishResult.RetryableFailure.class,
                h.publish(row(0, true, false)).result());
        assertFalse(waitResult.message().contains(secret));
        assertTrue(waitResult.cause() == null
                || !String.valueOf(waitResult.cause()).contains(secret));
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
