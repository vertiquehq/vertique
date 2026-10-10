// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDeliveryMetadata;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.inboxoutbox.PayloadCodec;
import dev.vertique.kafka.KafkaRecordHeader;
import dev.vertique.kafka.KafkaRecordHeaders;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies the routing and failure-classification logic of {@link KafkaOutboxDestinationHandler}.
 * Covers destination type declaration, topic/key mapping, null aggregate id, transport errors,
 * serialization failures, and which headers, durable context and entry id are given to the send.
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
            when(producerFactory.sendForOutbox(eq("orders-topic"), eq("order-abc"), eq(bytes), any(), any(), eq("77")))
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
        @DisplayName("passes the entry id to the producer send and never uses the send without one")
        void passesTheEntryIdToTheProducerSend() throws Exception {
            when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[] {1});
            when(producerFactory.sendForOutbox(any(), any(), any(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(null));

            OutboxPublishResult result =
                    handler.publish(makeEnvelope("order-123")).result();

            assertInstanceOf(OutboxPublishResult.Success.class, result);
            verify(producerFactory).sendForOutbox(eq("orders-topic"), eq("order-123"), any(), any(), any(), eq("77"));
            verify(producerFactory, never()).sendForOutbox(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("null aggregateId passes null key")
        void nullAggregateIdPassesNullKey() throws Exception {
            byte[] bytes = new byte[] {4, 5, 6};
            when(objectMapper.writeValueAsBytes(any())).thenReturn(bytes);
            when(producerFactory.sendForOutbox(eq("orders-topic"), isNull(), eq(bytes), any(), any(), eq("77")))
                    .thenReturn(Future.succeededFuture(null));

            OutboxPublishResult result = handler.publish(makeEnvelope(null)).result();

            assertInstanceOf(OutboxPublishResult.Success.class, result);
        }

        @Test
        @DisplayName("transport error returns RetryableFailure")
        void transportErrorReturnsRetryableFailure() throws Exception {
            when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[] {7, 8, 9});
            when(producerFactory.sendForOutbox(any(), any(), any(), any(), any(), any()))
                    .thenReturn(Future.failedFuture(new RuntimeException("Kafka broker unreachable")));

            OutboxPublishResult result =
                    handler.publish(makeEnvelope("order-123")).result();

            assertInstanceOf(OutboxPublishResult.RetryableFailure.class, result);
        }

        @Test
        @DisplayName("reserved-prefix header collision returns PermanentFailure (dead-letter, not retry)")
        void reservedHeaderCollisionReturnsPermanentFailure() throws Exception {
            when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[] {1});
            // KafkaProducerFactory.sendForOutbox returns a FAILED FUTURE (not a synchronous throw) when
            // an application header uses the reserved framework prefix.
            // The handler must classify that as permanent so the un-fixable row is dead-lettered.
            when(producerFactory.sendForOutbox(any(), any(), any(), any(), any(), any()))
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
        @DisplayName("a serialization failure of any exception type is a PermanentFailure result and sends nothing")
        void serializationFailureSendsNothing() throws Exception {
            when(objectMapper.writeValueAsBytes(any())).thenThrow(new RuntimeException("cannot serialize"));

            Future<OutboxPublishResult> published = handler.publish(makeEnvelope("order-123"));

            assertTrue(published.succeeded(), "the failure is a result, not a failed future");
            assertInstanceOf(OutboxPublishResult.PermanentFailure.class, published.result());
            verify(producerFactory, never()).sendForOutbox(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("scalar payload is unwrapped from PayloadCodec envelope before serialization")
        void scalarPayloadIsUnwrapped() throws Exception {
            // The scalar "hello" should be passed to Jackson as-is, not as {"_v": "hello"}
            when(objectMapper.writeValueAsBytes("hello")).thenReturn("\"hello\"".getBytes());
            when(producerFactory.sendForOutbox(eq("orders-topic"), isNull(), any(), any(), any(), eq("88")))
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

    // --- Record headers and durable context given to the send ---

    @Nested
    @DisplayName("record headers and durable context")
    class RecordHeaders {

        private OutboxEnvelope envelope(Map<String, String> headers, DurableMetadata context) {
            return new OutboxEnvelope(
                    101L,
                    "Order",
                    "order-headers",
                    "order.placed",
                    "orders-topic",
                    new JsonObject().put("orderId", "o-headers"),
                    headers,
                    new OutboxMetadata(context, OutboxDeliveryMetadata.empty()),
                    null,
                    0,
                    Instant.now());
        }

        private KafkaRecordHeaders sentHeaders() {
            ArgumentCaptor<KafkaRecordHeaders> sent = ArgumentCaptor.forClass(KafkaRecordHeaders.class);
            verify(producerFactory).sendForOutbox(any(), any(), any(), sent.capture(), any(), any());
            return sent.getValue();
        }

        @Test
        @DisplayName("the envelope's map is converted in its order and sent as the application headers alone")
        void convertsEnvelopeMapInOrder() throws Exception {
            when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[] {1});
            when(producerFactory.sendForOutbox(any(), any(), any(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(null));

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("zeta", "1");
            headers.put("alpha", "2");
            DurableMetadata context = DurableMetadata.of("correlation", new JsonObject().put("traceId", "t-1"));

            handler.publish(envelope(headers, context)).result();

            assertEquals(
                    List.of(KafkaRecordHeader.ofUtf8("zeta", "1"), KafkaRecordHeader.ofUtf8("alpha", "2")),
                    sentHeaders().entries(),
                    "the send is given the application headers alone; the factory appends the context headers");
        }

        @Test
        @DisplayName("the entry's durable context and entry id are given to the send")
        void givesTheDurableContextAndEntryIdToTheSend() throws Exception {
            when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[] {1, 2, 3});
            when(producerFactory.sendForOutbox(any(), any(), any(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(null));
            DurableMetadata context = DurableMetadata.of("correlation", new JsonObject().put("traceId", "t-123"));

            handler.publish(envelope(Map.of("ce-type", "order.placed"), context))
                    .result();

            ArgumentCaptor<DurableMetadata> sentContext = ArgumentCaptor.forClass(DurableMetadata.class);
            verify(producerFactory)
                    .sendForOutbox(
                            eq("orders-topic"),
                            eq("order-headers"),
                            eq(new byte[] {1, 2, 3}),
                            eq(KafkaRecordHeaders.of(Map.of("ce-type", "order.placed"))),
                            sentContext.capture(),
                            eq("101"));
            assertSame(context, sentContext.getValue(), "the stored durable context is passed on as is");
        }

        @Test
        @DisplayName("a null or empty envelope map is converted to no headers")
        void nullOrEmptyMapIsNoHeaders() throws Exception {
            when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[] {1});
            when(producerFactory.sendForOutbox(any(), any(), any(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(null));

            handler.publish(envelope(null, DurableMetadata.empty())).result();
            handler.publish(envelope(Map.of(), DurableMetadata.empty())).result();

            ArgumentCaptor<KafkaRecordHeaders> sent = ArgumentCaptor.forClass(KafkaRecordHeaders.class);
            verify(producerFactory, times(2)).sendForOutbox(any(), any(), any(), sent.capture(), any(), any());
            assertEquals(List.of(KafkaRecordHeaders.empty(), KafkaRecordHeaders.empty()), sent.getAllValues());
        }

        @Test
        @DisplayName("an envelope with a reserved-prefix header is sent as converted and is a permanent failure")
        void reservedPrefixHeaderIsSentAsConvertedAndFailsPermanently() throws Exception {
            when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[] {1});
            when(producerFactory.sendForOutbox(any(), any(), any(), any(), any(), any()))
                    .thenReturn(Future.failedFuture(new IllegalArgumentException(
                            "Application header uses reserved framework prefix 'vertique-': vertique-correlation")));

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("ce-type", "order.placed");
            headers.put("vertique-correlation", "{\"traceId\":\"forged\"}");
            DurableMetadata context = DurableMetadata.of("correlation", new JsonObject().put("traceId", "t-1"));

            OutboxPublishResult result =
                    handler.publish(envelope(headers, context)).result();

            assertInstanceOf(OutboxPublishResult.PermanentFailure.class, result);
            assertEquals(KafkaRecordHeaders.of(headers), sentHeaders());
        }

        @Test
        @DisplayName("a stored header map with a null value or a null key is a permanent failure and sends nothing")
        void nullHeaderValueOrKeyIsAPermanentFailure() {
            Map<String, String> nullValue = new LinkedHashMap<>();
            nullValue.put("ce-type", "order.placed");
            nullValue.put("ce-source", null);
            Map<String, String> nullKey = new LinkedHashMap<>();
            nullKey.put(null, "orphan");
            DurableMetadata context = DurableMetadata.of("correlation", new JsonObject().put("traceId", "t-1"));

            Future<OutboxPublishResult> first = handler.publish(envelope(nullValue, context));
            Future<OutboxPublishResult> second = handler.publish(envelope(nullKey, context));

            assertTrue(first.succeeded(), "the failure is a result, not a failed future");
            assertTrue(second.succeeded(), "the failure is a result, not a failed future");
            OutboxPublishResult.PermanentFailure valueFailure =
                    assertInstanceOf(OutboxPublishResult.PermanentFailure.class, first.result());
            assertTrue(valueFailure.message().contains("ce-source"), valueFailure.message());
            assertInstanceOf(OutboxPublishResult.PermanentFailure.class, second.result());
            verify(producerFactory, never()).sendForOutbox(any(), any(), any(), any(), any(), any());
        }
    }
}
