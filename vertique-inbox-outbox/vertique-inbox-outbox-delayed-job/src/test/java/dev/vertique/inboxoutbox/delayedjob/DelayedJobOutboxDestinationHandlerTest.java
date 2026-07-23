// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.delayedjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.inboxoutbox.DelayedJobControl;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDeliveryMetadata;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.inboxoutbox.PayloadCodec;
import dev.vertique.job.delayed.DelayedJob;
import dev.vertique.job.delayed.DelayedJobService;
import dev.vertique.job.delayed.DelayedJobTargetResolver;
import dev.vertique.job.delayed.ResolvedDelayedJobTarget;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies the routing and failure-classification logic of {@link DelayedJobOutboxDestinationHandler}.
 * Covers destination type declaration, snapshot header reading, runAt mapping, resolver misses,
 * missing and malformed headers, and enqueue error classification.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DelayedJobOutboxDestinationHandler")
class DelayedJobOutboxDestinationHandlerTest {

    @Mock
    DelayedJobService delayedJobService;

    @Mock
    DelayedJobTargetResolver resolver;

    DelayedJobOutboxDestinationHandler handler;

    @BeforeEach
    void setUp() {
        handler = new DelayedJobOutboxDestinationHandler(delayedJobService, resolver);
    }

    // --- Helpers ---

    private ResolvedDelayedJobTarget makeTarget() {
        return new ResolvedDelayedJobTarget(
                "deliver-webhook", "deliver-webhook", "job/deliver-webhook/execute", "default", 5, 3);
    }

    /** Outbox metadata carrying the delayed-job scheduling snapshot in delivery.delayedJob. */
    private OutboxMetadata scheduledMetadata() {
        return new OutboxMetadata(
                DurableMetadata.empty(),
                new OutboxDeliveryMetadata(
                        Optional.empty(), Optional.of(new DelayedJobControl("high-priority", 10, 5))));
    }

    private OutboxEnvelope makeEnvelope(OutboxMetadata metadata, Instant scheduledAt) {
        return new OutboxEnvelope(
                99L,
                "Webhook",
                "webhook-456",
                "webhook.deliver",
                "deliver-webhook",
                new JsonObject().put("url", "https://example.com"),
                Map.of(),
                metadata,
                scheduledAt,
                0,
                Instant.now());
    }

    // --- destinationType ---

    @Test
    @DisplayName("destinationType returns DELAYED_JOB")
    void destinationTypeIsDelayedJob() {
        assertEquals(DestinationType.DELAYED_JOB, handler.destinationType());
    }

    // --- publish ---

    @Nested
    @DisplayName("publish")
    class Publish {

        @Test
        @DisplayName("reads scheduling defaults from delivery.delayedJob")
        void readsSnapshotHeadersForDelayedJob() {
            when(resolver.resolve("deliver-webhook")).thenReturn(makeTarget());
            ArgumentCaptor<DelayedJob> jobCaptor = forClass(DelayedJob.class);
            when(delayedJobService.enqueue(jobCaptor.capture())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            handler.publish(makeEnvelope(scheduledMetadata(), null)).result();

            DelayedJob captured = jobCaptor.getValue();
            assertEquals("high-priority", captured.queue());
            assertEquals(10, captured.priority());
            assertEquals(5, captured.maxAttempts());
        }

        @Test
        @DisplayName("resolver miss returns Unresolvable")
        void resolverMissReturnsUnresolvable() {
            when(resolver.resolve("deliver-webhook")).thenThrow(new IllegalArgumentException("not registered"));

            OutboxPublishResult result =
                    handler.publish(makeEnvelope(scheduledMetadata(), null)).result();

            assertInstanceOf(OutboxPublishResult.Unresolvable.class, result);
        }

        @Test
        @DisplayName("missing delivery.delayedJob returns PermanentFailure")
        void missingSnapshotHeadersReturnsPermanentFailure() {
            when(resolver.resolve("deliver-webhook")).thenReturn(makeTarget());

            // Envelope with no delivery.delayedJob scheduling snapshot
            OutboxEnvelope envelope = makeEnvelope(OutboxMetadata.empty(), null);

            OutboxPublishResult result = handler.publish(envelope).result();

            assertInstanceOf(OutboxPublishResult.PermanentFailure.class, result);
        }

        @Test
        @DisplayName("maps scheduledAt to runAt")
        void mapsScheduledAtToRunAt() {
            when(resolver.resolve("deliver-webhook")).thenReturn(makeTarget());
            ArgumentCaptor<DelayedJob> jobCaptor = forClass(DelayedJob.class);
            when(delayedJobService.enqueue(jobCaptor.capture())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            Instant runAt = Instant.now().plusSeconds(300);
            handler.publish(makeEnvelope(scheduledMetadata(), runAt)).result();

            assertEquals(runAt, jobCaptor.getValue().runAt());
        }

        @Test
        @DisplayName("enqueue failure returns RetryableFailure")
        void enqueueFailureReturnsRetryableFailure() {
            when(resolver.resolve("deliver-webhook")).thenReturn(makeTarget());
            when(delayedJobService.enqueue(any(DelayedJob.class)))
                    .thenReturn(Future.failedFuture(new RuntimeException("DB connection lost")));

            OutboxPublishResult result =
                    handler.publish(makeEnvelope(scheduledMetadata(), null)).result();

            assertInstanceOf(OutboxPublishResult.RetryableFailure.class, result);
        }

        @Test
        @DisplayName("IllegalArgumentException from enqueue returns PermanentFailure")
        void illegalArgumentFromEnqueueReturnsPermanentFailure() {
            when(resolver.resolve("deliver-webhook")).thenReturn(makeTarget());
            when(delayedJobService.enqueue(any(DelayedJob.class)))
                    .thenReturn(Future.failedFuture(new IllegalArgumentException("invalid handler name")));

            OutboxPublishResult result =
                    handler.publish(makeEnvelope(scheduledMetadata(), null)).result();

            assertInstanceOf(OutboxPublishResult.PermanentFailure.class, result);
        }

        @Test
        @DisplayName("scalar payload is unwrapped from PayloadCodec envelope before enqueue")
        void scalarPayloadIsUnwrapped() {
            when(resolver.resolve("deliver-webhook")).thenReturn(makeTarget());
            ArgumentCaptor<DelayedJob> jobCaptor = forClass(DelayedJob.class);
            when(delayedJobService.enqueue(jobCaptor.capture())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            // Simulate a scalar payload that went through PayloadCodec.encode("hello")
            OutboxEnvelope scalarEnvelope = new OutboxEnvelope(
                    99L,
                    null,
                    null,
                    "webhook.deliver",
                    "deliver-webhook",
                    PayloadCodec.encode("hello"),
                    Map.of(),
                    scheduledMetadata(),
                    null,
                    0,
                    Instant.now());

            handler.publish(scalarEnvelope).result();

            // The delayed job should receive the unwrapped "hello", not {"_v": "hello"}
            assertEquals("hello", jobCaptor.getValue().payload());
        }
    }
}
