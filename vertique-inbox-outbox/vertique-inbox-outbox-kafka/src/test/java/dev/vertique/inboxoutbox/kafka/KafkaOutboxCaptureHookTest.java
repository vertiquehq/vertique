// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.DurableMetadataHeaderCodec;
import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.core.payload.PayloadKind;
import dev.vertique.core.payload.PayloadSource;
import dev.vertique.inboxoutbox.OutboxDeliveryMetadata;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies the {@link KafkaOutboxCaptureHook} SPI contract and its invocation by
 * {@link KafkaOutboxDestinationHandler}.
 *
 * <p>Covered:
 * <ul>
 *   <li>Default no-op {@link KafkaOutboxCaptureHook#onOutboxPublish} does not throw</li>
 *   <li>Hook implements {@link OrderedExtension} — default phase is {@link ExtensionPhase#APPLICATION}</li>
 *   <li>A registered hook is invoked exactly once per publish with the correct topic, key,
 *       serialized-value {@link PayloadSource}, headers, and {@link OutboxPublishResult}</li>
 *   <li>Hook is invoked for both success and retryable-failure outcomes</li>
 *   <li>A throwing hook does NOT change the returned {@link OutboxPublishResult}</li>
 *   <li>A throwing hook does not prevent subsequent hooks from running</li>
 *   <li>With no hooks the handler behaves identically to baseline (no-op)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KafkaOutboxCaptureHook")
class KafkaOutboxCaptureHookTest {

    @Mock
    KafkaProducerFactory producerFactory;

    @Mock
    ObjectMapper objectMapper;

    // --- Hook fixtures ---

    /** Records every {@link KafkaOutboxCaptureHook#onOutboxPublish} invocation. */
    static final class RecordingHook implements KafkaOutboxCaptureHook {

        record Capture(
                String topic,
                String key,
                PayloadSource value,
                Map<String, String> headers,
                OutboxPublishResult result,
                String entryId) {}

        final List<Capture> captures = new ArrayList<>();

        @Override
        public void onOutboxPublish(
                String topic,
                String key,
                PayloadSource value,
                Map<String, String> headers,
                OutboxPublishResult result,
                String entryId) {
            captures.add(new Capture(topic, key, value, headers, result, entryId));
        }
    }

    /** Hook that always throws — verifies throwing does not change the publish result. */
    static final class ThrowingHook implements KafkaOutboxCaptureHook {

        int callCount;

        @Override
        public void onOutboxPublish(
                String topic,
                String key,
                PayloadSource value,
                Map<String, String> headers,
                OutboxPublishResult result,
                String entryId) {
            callCount++;
            throw new RuntimeException("hook exploded");
        }
    }

    /** Low-priority recording hook used when two hooks are registered together. */
    static final class SecondaryRecordingHook implements KafkaOutboxCaptureHook {

        final List<String> seenTopics = new ArrayList<>();

        @Override
        public int priority() {
            return 1; // runs after ThrowingHook (priority 0)
        }

        @Override
        public void onOutboxPublish(
                String topic,
                String key,
                PayloadSource value,
                Map<String, String> headers,
                OutboxPublishResult result,
                String entryId) {
            seenTopics.add(topic);
        }
    }

    // --- Helpers ---

    private OutboxEnvelope makeEnvelope(String aggregateId) {
        return new OutboxEnvelope(
                77L,
                "Order",
                aggregateId,
                "order.placed",
                "orders-topic",
                new JsonObject().put("orderId", "o-123"),
                Map.of("ce-type", "order.placed"),
                OutboxMetadata.empty(),
                null,
                0,
                Instant.now());
    }

    // --- KafkaOutboxCaptureHook contract ---

    @Nested
    @DisplayName("KafkaOutboxCaptureHook contract")
    class HookContract {

        @Test
        @DisplayName("default onOutboxPublish is a no-op — does not throw")
        void defaultNoOp() {
            KafkaOutboxCaptureHook hook = new KafkaOutboxCaptureHook() {};
            // Should not throw regardless of arguments
            hook.onOutboxPublish("t", "k", null, Map.of(), OutboxPublishResult.success(), "42");
        }

        @Test
        @DisplayName("implements OrderedExtension — default phase is APPLICATION")
        void implementsOrderedExtension() {
            KafkaOutboxCaptureHook hook = new KafkaOutboxCaptureHook() {};
            assertEquals(ExtensionPhase.APPLICATION, hook.phase());
        }

        @Test
        @DisplayName("hooks are sortable by OrderedExtension comparator")
        void sortableByComparator() {
            KafkaOutboxCaptureHook high = new KafkaOutboxCaptureHook() {
                @Override
                public int priority() {
                    return 100;
                }
            };
            KafkaOutboxCaptureHook low = new KafkaOutboxCaptureHook() {
                @Override
                public int priority() {
                    return 0;
                }
            };

            List<KafkaOutboxCaptureHook> hooks = new ArrayList<>(List.of(high, low));
            hooks.sort(OrderedExtension.comparator());

            assertEquals(low, hooks.get(0), "lower priority must sort first");
            assertEquals(high, hooks.get(1));
        }
    }

    // --- Hook invocation on success ---

    @Nested
    @DisplayName("hook invocation on success")
    class HookInvocationOnSuccess {

        KafkaOutboxDestinationHandler handler;
        RecordingHook hook;

        @BeforeEach
        void setUp() throws Exception {
            hook = new RecordingHook();
            handler = new KafkaOutboxDestinationHandler(producerFactory, objectMapper, Set.of(hook));

            when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[] {1, 2, 3});
            when(producerFactory.sendForOutbox(any(), any(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(null));
        }

        @Test
        @DisplayName("hook is invoked exactly once on successful publish")
        void invokedExactlyOnceOnSuccess() {
            handler.publish(makeEnvelope("order-abc")).result();
            assertEquals(1, hook.captures.size());
        }

        @Test
        @DisplayName("hook receives the correct topic")
        void receivesCorrectTopic() {
            handler.publish(makeEnvelope("order-abc")).result();
            assertEquals("orders-topic", hook.captures.get(0).topic());
        }

        @Test
        @DisplayName("hook receives the correct key")
        void receivesCorrectKey() {
            handler.publish(makeEnvelope("order-abc")).result();
            assertEquals("order-abc", hook.captures.get(0).key());
        }

        @Test
        @DisplayName("hook receives null key when aggregateId is null")
        void receivesNullKeyForNullAggregateId() throws Exception {
            when(producerFactory.sendForOutbox(any(), isNull(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(null));
            handler.publish(makeEnvelope(null)).result();
            assertEquals(null, hook.captures.get(0).key());
        }

        @Test
        @DisplayName("hook receives a non-null BUFFERED PayloadSource over the serialized bytes")
        void receivesBufferedPayloadSource() {
            handler.publish(makeEnvelope("order-abc")).result();
            PayloadSource ps = hook.captures.get(0).value();
            assertNotNull(ps, "hook must receive a non-null PayloadSource");
            assertEquals(PayloadKind.BUFFERED, ps.kind());
        }

        @Test
        @DisplayName("hook receives a Success result on successful publish")
        void receivesSuccessResult() {
            OutboxPublishResult result =
                    handler.publish(makeEnvelope("order-abc")).result();
            assertInstanceOf(
                    OutboxPublishResult.Success.class, hook.captures.get(0).result());
            // Returned result is unchanged
            assertInstanceOf(OutboxPublishResult.Success.class, result);
        }

        @Test
        @DisplayName("hook receives the entryId as a string")
        void receivesEntryId() {
            handler.publish(makeEnvelope("order-abc")).result();
            assertEquals("77", hook.captures.get(0).entryId());
        }

        @Test
        @DisplayName("hook receives the envelope headers")
        void receivesHeaders() {
            handler.publish(makeEnvelope("order-abc")).result();
            assertEquals(Map.of("ce-type", "order.placed"), hook.captures.get(0).headers());
        }
    }

    // --- Hook invocation on retryable failure ---

    @Nested
    @DisplayName("hook invocation on retryable failure")
    class HookInvocationOnRetryableFailure {

        KafkaOutboxDestinationHandler handler;
        RecordingHook hook;

        @BeforeEach
        void setUp() throws Exception {
            hook = new RecordingHook();
            handler = new KafkaOutboxDestinationHandler(producerFactory, objectMapper, Set.of(hook));

            when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[] {4, 5});
            when(producerFactory.sendForOutbox(any(), any(), any(), any(), any()))
                    .thenReturn(Future.failedFuture(new RuntimeException("broker down")));
        }

        @Test
        @DisplayName("hook is invoked exactly once on retryable failure")
        void invokedExactlyOnceOnRetryableFailure() {
            handler.publish(makeEnvelope("order-x")).result();
            assertEquals(1, hook.captures.size());
        }

        @Test
        @DisplayName("hook receives a RetryableFailure result")
        void receivesRetryableFailureResult() {
            OutboxPublishResult result =
                    handler.publish(makeEnvelope("order-x")).result();
            assertInstanceOf(
                    OutboxPublishResult.RetryableFailure.class,
                    hook.captures.get(0).result());
            // Returned result is unchanged
            assertInstanceOf(OutboxPublishResult.RetryableFailure.class, result);
        }
    }

    // --- Hook invocation on permanent failure (serialization) ---

    @Nested
    @DisplayName("hook invocation on permanent failure")
    class HookInvocationOnPermanentFailure {

        KafkaOutboxDestinationHandler handler;
        RecordingHook hook;

        @BeforeEach
        void setUp() throws Exception {
            hook = new RecordingHook();
            handler = new KafkaOutboxDestinationHandler(producerFactory, objectMapper, Set.of(hook));

            when(objectMapper.writeValueAsBytes(any()))
                    .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("bad") {});
        }

        @Test
        @DisplayName("hook is invoked exactly once on serialization (permanent) failure")
        void invokedExactlyOnceOnPermanentFailure() {
            handler.publish(makeEnvelope("order-y")).result();
            assertEquals(1, hook.captures.size());
        }

        @Test
        @DisplayName("hook receives a PermanentFailure result on serialization error")
        void receivesPermanentFailureResult() {
            OutboxPublishResult result =
                    handler.publish(makeEnvelope("order-y")).result();
            assertInstanceOf(
                    OutboxPublishResult.PermanentFailure.class,
                    hook.captures.get(0).result());
            // Returned result is unchanged
            assertInstanceOf(OutboxPublishResult.PermanentFailure.class, result);
        }
    }

    // --- Throwing hook safety ---

    @Nested
    @DisplayName("throwing hook does not affect publish result")
    class ThrowingHookSafety {

        @Test
        @DisplayName("a throwing hook does not change the returned OutboxPublishResult")
        void throwingHookDoesNotChangeResult() throws Exception {
            ThrowingHook throwing = new ThrowingHook();
            KafkaOutboxDestinationHandler handler =
                    new KafkaOutboxDestinationHandler(producerFactory, objectMapper, Set.of(throwing));

            when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[] {1});
            when(producerFactory.sendForOutbox(any(), any(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(null));

            OutboxPublishResult result =
                    handler.publish(makeEnvelope("order-z")).result();

            assertEquals(1, throwing.callCount, "throwing hook must have been called");
            assertInstanceOf(OutboxPublishResult.Success.class, result, "result must still be Success");
        }

        @Test
        @DisplayName("a throwing hook does not prevent subsequent hooks from running")
        void throwingHookDoesNotBlockSubsequentHooks() throws Exception {
            ThrowingHook throwing = new ThrowingHook();
            SecondaryRecordingHook secondary = new SecondaryRecordingHook();
            KafkaOutboxDestinationHandler handler =
                    new KafkaOutboxDestinationHandler(producerFactory, objectMapper, Set.of(throwing, secondary));

            when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[] {2});
            when(producerFactory.sendForOutbox(any(), any(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(null));

            handler.publish(makeEnvelope("order-w")).result();

            assertEquals(1, throwing.callCount, "throwing hook must be called");
            assertEquals(1, secondary.seenTopics.size(), "secondary hook must run even after throwing hook");
            assertEquals("orders-topic", secondary.seenTopics.get(0));
        }
    }

    // --- No-hooks no-op ---

    @Nested
    @DisplayName("no hooks — no-op, classification unchanged")
    class NoHooks {

        @Test
        @DisplayName("with no hooks the publish result is still Success")
        void noHooksSuccessUnchanged() throws Exception {
            KafkaOutboxDestinationHandler handler =
                    new KafkaOutboxDestinationHandler(producerFactory, objectMapper, Set.of());

            when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[] {9});
            when(producerFactory.sendForOutbox(any(), any(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(null));

            OutboxPublishResult result =
                    handler.publish(makeEnvelope("order-nohook")).result();
            assertInstanceOf(OutboxPublishResult.Success.class, result);
        }

        @Test
        @DisplayName("with no hooks the publish result is still RetryableFailure on transport error")
        void noHooksRetryableUnchanged() throws Exception {
            KafkaOutboxDestinationHandler handler =
                    new KafkaOutboxDestinationHandler(producerFactory, objectMapper, Set.of());

            when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[] {9});
            when(producerFactory.sendForOutbox(any(), any(), any(), any(), any()))
                    .thenReturn(Future.failedFuture(new RuntimeException("down")));

            OutboxPublishResult result =
                    handler.publish(makeEnvelope("order-nohook")).result();
            assertInstanceOf(OutboxPublishResult.RetryableFailure.class, result);
        }
    }

    // --- Fix D: durable correlation headers reach the hook ---

    @Nested
    @DisplayName("durable correlation headers reach the hook (Fix D)")
    class DurableCorrelationHeaders {

        /**
         * Builds an envelope whose metadata context carries a durable namespace so that
         * {@link DurableMetadataHeaderCodec#mergeForEgress} would project at least one
         * {@code vertique-*} header onto the wire.
         */
        private OutboxEnvelope envelopeWithContext(DurableMetadata context) {
            OutboxMetadata metadata = new OutboxMetadata(context, OutboxDeliveryMetadata.empty());
            return new OutboxEnvelope(
                    99L,
                    "Order",
                    "order-ctx",
                    "order.placed",
                    "orders-topic",
                    new JsonObject().put("orderId", "o-ctx"),
                    Map.of("ce-type", "order.placed"),
                    metadata,
                    null,
                    0,
                    Instant.now());
        }

        @Test
        @DisplayName("hook headers on success include vertique-* entries from the durable context")
        void successHookHeadersIncludeDurableContext() throws Exception {
            RecordingHook hook = new RecordingHook();
            KafkaOutboxDestinationHandler handler =
                    new KafkaOutboxDestinationHandler(producerFactory, objectMapper, Set.of(hook));

            when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[] {1, 2, 3});
            when(producerFactory.sendForOutbox(any(), any(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(null));

            DurableMetadata context = DurableMetadata.of("correlation", new JsonObject().put("traceId", "t-123"));
            OutboxEnvelope envelope = envelopeWithContext(context);

            handler.publish(envelope).result();

            assertEquals(1, hook.captures.size());
            Map<String, String> hookHeaders = hook.captures.get(0).headers();
            // The hook must receive the merged wire headers — application + framework reserved keys
            String expectedReservedKey = DurableMetadataHeaderCodec.RESERVED_PREFIX + "correlation";
            assertTrue(
                    hookHeaders.containsKey(expectedReservedKey),
                    "hook headers must contain the projected vertique-* durable-context entry '" + expectedReservedKey
                            + "'; got: " + hookHeaders.keySet());
            // Application headers must also be present (not dropped by mergeForEgress)
            assertTrue(
                    hookHeaders.containsKey("ce-type"),
                    "hook headers must retain application headers alongside the framework keys");
            // fromHeaders must decode back to the same correlation
            DurableMetadata decoded = DurableMetadataHeaderCodec.fromHeaders(hookHeaders);
            assertEquals(context, decoded, "decoded context must match the original durable context");
        }

        @Test
        @DisplayName("hook headers on serialization failure include vertique-* entries from the durable context")
        void serializationFailureHookHeadersIncludeDurableContext() throws Exception {
            RecordingHook hook = new RecordingHook();
            KafkaOutboxDestinationHandler handler =
                    new KafkaOutboxDestinationHandler(producerFactory, objectMapper, Set.of(hook));

            when(objectMapper.writeValueAsBytes(any()))
                    .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("bad") {});

            DurableMetadata context = DurableMetadata.of("correlation", new JsonObject().put("traceId", "t-456"));
            OutboxEnvelope envelope = envelopeWithContext(context);

            handler.publish(envelope).result();

            assertEquals(1, hook.captures.size());
            assertInstanceOf(
                    OutboxPublishResult.PermanentFailure.class,
                    hook.captures.get(0).result());
            Map<String, String> hookHeaders = hook.captures.get(0).headers();
            String expectedReservedKey = DurableMetadataHeaderCodec.RESERVED_PREFIX + "correlation";
            assertTrue(
                    hookHeaders.containsKey(expectedReservedKey),
                    "hook headers on serialize-failure must contain the projected vertique-* durable-context entry");
            DurableMetadata decoded = DurableMetadataHeaderCodec.fromHeaders(hookHeaders);
            assertEquals(context, decoded, "decoded context must match on serialize-failure path");
        }

        @Test
        @DisplayName("hook headers on transport failure include vertique-* entries from the durable context")
        void transportFailureHookHeadersIncludeDurableContext() throws Exception {
            RecordingHook hook = new RecordingHook();
            KafkaOutboxDestinationHandler handler =
                    new KafkaOutboxDestinationHandler(producerFactory, objectMapper, Set.of(hook));

            when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[] {7, 8});
            when(producerFactory.sendForOutbox(any(), any(), any(), any(), any()))
                    .thenReturn(Future.failedFuture(new RuntimeException("broker down")));

            DurableMetadata context = DurableMetadata.of("correlation", new JsonObject().put("traceId", "t-789"));
            OutboxEnvelope envelope = envelopeWithContext(context);

            handler.publish(envelope).result();

            assertEquals(1, hook.captures.size());
            assertInstanceOf(
                    OutboxPublishResult.RetryableFailure.class,
                    hook.captures.get(0).result());
            Map<String, String> hookHeaders = hook.captures.get(0).headers();
            String expectedReservedKey = DurableMetadataHeaderCodec.RESERVED_PREFIX + "correlation";
            assertTrue(
                    hookHeaders.containsKey(expectedReservedKey),
                    "hook headers on transport-failure must contain the projected vertique-* durable-context entry");
            DurableMetadata decoded = DurableMetadataHeaderCodec.fromHeaders(hookHeaders);
            assertEquals(context, decoded, "decoded context must match on transport-failure path");
        }

        @Test
        @DisplayName("empty durable context produces no vertique-* headers in hook (no pollution)")
        void emptyContextProducesNoReservedHeaders() throws Exception {
            RecordingHook hook = new RecordingHook();
            KafkaOutboxDestinationHandler handler =
                    new KafkaOutboxDestinationHandler(producerFactory, objectMapper, Set.of(hook));

            when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[] {0});
            when(producerFactory.sendForOutbox(any(), any(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(null));

            // OutboxMetadata.empty() has DurableMetadata.empty() — no namespaces to project
            handler.publish(makeEnvelope("order-empty-ctx")).result();

            assertEquals(1, hook.captures.size());
            Map<String, String> hookHeaders = hook.captures.get(0).headers();
            long reservedCount = hookHeaders.keySet().stream()
                    .filter(DurableMetadataHeaderCodec::isReservedHeader)
                    .count();
            assertEquals(0, reservedCount, "empty context must produce no vertique-* headers in hook");
        }
    }
}
