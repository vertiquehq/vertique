// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.delayedjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.inboxoutbox.DelayedJobControl;
import dev.vertique.inboxoutbox.OutboxDeliveryMetadata;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxPublishResult;
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
 * Verifies the header-partitioning logic added to
 * {@link DelayedJobOutboxDestinationHandler#publish}: control headers are consumed for scheduling
 * defaults and stripped from the forwarded metadata; all other headers are passed through as
 * durable propagation metadata in {@link DelayedJob#metadata()}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DelayedJobOutboxDestinationHandler metadata routing")
class DelayedJobOutboxDestinationHandlerMetadataTest {

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

    /**
     * Builds an envelope with the three delayed-job control headers and the given durable
     * propagation context in {@link OutboxMetadata#context()}.
     *
     * @param context the durable propagation context to carry in the envelope metadata
     * @return the constructed outbox envelope
     */
    private OutboxEnvelope makeEnvelope(DurableMetadata context) {
        OutboxDeliveryMetadata delivery =
                new OutboxDeliveryMetadata(Optional.empty(), Optional.of(new DelayedJobControl("default", 5, 3)));
        return new OutboxEnvelope(
                99L,
                "Webhook",
                "webhook-1",
                "webhook.deliver",
                "deliver-webhook",
                new JsonObject().put("url", "https://example.com"),
                Map.of(),
                new OutboxMetadata(context, delivery),
                null,
                0,
                Instant.now());
    }

    // --- Test cases ---

    @Nested
    @DisplayName("durable context forwarding")
    class DurableContextForwarding {

        @Test
        @DisplayName("durable context from the outbox row is forwarded verbatim into DelayedJob.metadata")
        void contextForwardedIntoMetadata() {
            when(resolver.resolve("deliver-webhook")).thenReturn(makeTarget());
            ArgumentCaptor<DelayedJob> captor = forClass(DelayedJob.class);
            when(delayedJobService.enqueue(captor.capture())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            DurableMetadata context = DurableMetadata.of("correlation", new JsonObject().put("requestId", "c-1"));
            handler.publish(makeEnvelope(context)).result();

            assertEquals(context, captor.getValue().metadata(), "envelope durable context must be forwarded verbatim");
        }

        @Test
        @DisplayName("empty durable context produces an empty DelayedJob.metadata")
        void emptyContextProducesEmptyMetadata() {
            when(resolver.resolve("deliver-webhook")).thenReturn(makeTarget());
            ArgumentCaptor<DelayedJob> captor = forClass(DelayedJob.class);
            when(delayedJobService.enqueue(captor.capture())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            handler.publish(makeEnvelope(DurableMetadata.empty())).result();

            assertTrue(captor.getValue().metadata().isEmpty(), "no durable context → empty metadata");
        }

        @Test
        @DisplayName("control headers supply scheduling defaults (queue, priority, maxAttempts)")
        void controlHeadersSupplySchedulingDefaults() {
            when(resolver.resolve("deliver-webhook")).thenReturn(makeTarget());
            ArgumentCaptor<DelayedJob> captor = forClass(DelayedJob.class);
            when(delayedJobService.enqueue(captor.capture())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            handler.publish(makeEnvelope(DurableMetadata.empty())).result();

            DelayedJob job = captor.getValue();
            assertEquals("default", job.queue(), "queue must be read from x-delayed-job-queue control header");
            assertEquals(5, job.priority(), "priority must be read from x-delayed-job-priority control header");
            assertEquals(
                    3, job.maxAttempts(), "maxAttempts must be read from x-delayed-job-max-attempts control header");
        }

        @Test
        @DisplayName("multi-namespace durable context is forwarded intact")
        void multiNamespaceContextForwarded() {
            when(resolver.resolve("deliver-webhook")).thenReturn(makeTarget());
            ArgumentCaptor<DelayedJob> captor = forClass(DelayedJob.class);
            when(delayedJobService.enqueue(captor.capture())).thenReturn(Future.succeededFuture(UUID.randomUUID()));

            DurableMetadata context = DurableMetadata.of("correlation", new JsonObject().put("requestId", "c-3"))
                    .with("localization", new JsonObject().put("locale", "en-US"));
            handler.publish(makeEnvelope(context)).result();

            assertEquals(context, captor.getValue().metadata());
        }
    }

    @Nested
    @DisplayName("null and missing headers")
    class NullHeaders {

        @Test
        @DisplayName("null envelope headers are treated as empty — no NPE")
        void nullEnvelopeHeadersTreatedAsEmpty() {
            when(resolver.resolve("deliver-webhook")).thenReturn(makeTarget());

            // Build envelope with null headers
            OutboxEnvelope envelope = new OutboxEnvelope(
                    99L,
                    "Webhook",
                    "webhook-2",
                    "webhook.deliver",
                    "deliver-webhook",
                    new JsonObject(),
                    null, // null headers
                    OutboxMetadata.empty(),
                    null,
                    0,
                    Instant.now());

            // Missing control headers → permanent failure, but no NPE
            OutboxPublishResult result = handler.publish(envelope).result();
            assertInstanceOf(
                    OutboxPublishResult.PermanentFailure.class,
                    result,
                    "null headers must produce PermanentFailure without NPE");
        }
    }
}
