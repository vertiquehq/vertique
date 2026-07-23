// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.inboxoutbox.PayloadCodec;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies the routing and failure-classification logic of {@link KafkaOutboxDestinationHandler}.
 * Covers destination type declaration, topic/key mapping, null aggregate id, transport errors,
 * and serialization failures.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KafkaOutboxDestinationHandler")
class KafkaOutboxDestinationHandlerTest {

    @Mock
    KafkaProducerFactory producerFactory;

    @Mock
    ObjectMapper objectMapper;

    KafkaOutboxDestinationHandler handler;

    @BeforeEach
    void setUp() {
        handler = new KafkaOutboxDestinationHandler(producerFactory, objectMapper);
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

    // --- destinationType ---

    @Test
    @DisplayName("destinationType returns KAFKA")
    void destinationTypeIsKafka() {
        assertEquals(DestinationType.KAFKA, handler.destinationType());
    }

    // --- publish ---

    @Nested
    @DisplayName("publish")
    class Publish {

        @Test
        @DisplayName("uses destination as topic and aggregateId as key")
        void usesDestinationAsTopicAndAggregateIdAsKey() throws Exception {
            byte[] bytes = new byte[] {1, 2, 3};
            when(objectMapper.writeValueAsBytes(any())).thenReturn(bytes);
            when(producerFactory.sendForOutbox(eq("orders-topic"), eq("order-abc"), eq(bytes), any(), any()))
                    .thenReturn(Future.succeededFuture(null));

            OutboxEnvelope envelope = new OutboxEnvelope(
                    77L,
                    "Order",
                    "order-abc",
                    "order.placed",
                    "orders-topic",
                    new JsonObject().put("orderId", "o-123"),
                    Map.of(),
                    OutboxMetadata.empty(),
                    null,
                    0,
                    Instant.now());

            OutboxPublishResult result = handler.publish(envelope).result();

            assertInstanceOf(OutboxPublishResult.Success.class, result);
        }

        @Test
        @DisplayName("null aggregateId passes null key")
        void nullAggregateIdPassesNullKey() throws Exception {
            byte[] bytes = new byte[] {4, 5, 6};
            when(objectMapper.writeValueAsBytes(any())).thenReturn(bytes);
            when(producerFactory.sendForOutbox(eq("orders-topic"), isNull(), eq(bytes), any(), any()))
                    .thenReturn(Future.succeededFuture(null));

            OutboxPublishResult result = handler.publish(makeEnvelope(null)).result();

            assertInstanceOf(OutboxPublishResult.Success.class, result);
        }

        @Test
        @DisplayName("transport error returns RetryableFailure")
        void transportErrorReturnsRetryableFailure() throws Exception {
            when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[] {7, 8, 9});
            when(producerFactory.sendForOutbox(any(), any(), any(), any(), any()))
                    .thenReturn(Future.failedFuture(new RuntimeException("Kafka broker unreachable")));

            OutboxPublishResult result =
                    handler.publish(makeEnvelope("order-123")).result();

            assertInstanceOf(OutboxPublishResult.RetryableFailure.class, result);
        }

        @Test
        @DisplayName("reserved-prefix header collision returns PermanentFailure (dead-letter, not retry)")
        void reservedHeaderCollisionReturnsPermanentFailure() throws Exception {
            when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[] {1});
            // KafkaProducerFactory.send returns a FAILED FUTURE (not a synchronous throw) when
            // mergeForEgress rejects an application header that uses the reserved framework prefix.
            // The handler must classify that as permanent so the un-fixable row is dead-lettered.
            when(producerFactory.sendForOutbox(any(), any(), any(), any(), any()))
                    .thenReturn(Future.failedFuture(new IllegalArgumentException(
                            "Application header uses reserved framework prefix 'vertique-': vertique-correlation")));

            OutboxPublishResult result =
                    handler.publish(makeEnvelope("order-123")).result();

            assertInstanceOf(OutboxPublishResult.PermanentFailure.class, result);
        }

        @Test
        @DisplayName("serialization error returns PermanentFailure")
        void serializationErrorReturnsPermanentFailure() throws Exception {
            when(objectMapper.writeValueAsBytes(any()))
                    .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("bad payload") {});

            OutboxPublishResult result =
                    handler.publish(makeEnvelope("order-123")).result();

            assertInstanceOf(OutboxPublishResult.PermanentFailure.class, result);
        }

        @Test
        @DisplayName("scalar payload is unwrapped from PayloadCodec envelope before serialization")
        void scalarPayloadIsUnwrapped() throws Exception {
            // The scalar "hello" should be passed to Jackson as-is, not as {"_v": "hello"}
            when(objectMapper.writeValueAsBytes("hello")).thenReturn("\"hello\"".getBytes());
            when(producerFactory.sendForOutbox(eq("orders-topic"), isNull(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(null));

            OutboxEnvelope scalarEnvelope = new OutboxEnvelope(
                    88L,
                    null,
                    null,
                    "test.event",
                    "orders-topic",
                    PayloadCodec.encode("hello"),
                    Map.of(),
                    OutboxMetadata.empty(),
                    null,
                    0,
                    Instant.now());

            OutboxPublishResult result = handler.publish(scalarEnvelope).result();

            assertInstanceOf(OutboxPublishResult.Success.class, result);
            // Verify Jackson received the unwrapped "hello", not the wrapper JsonObject
            verify(objectMapper).writeValueAsBytes("hello");
        }
    }
}
