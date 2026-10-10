// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.context.ContextScopeBinder;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.payload.PayloadSource;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.producer.KafkaProducer;
import dev.vertique.kafka.producer.KafkaProducerCaptureHook;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import dev.vertique.kafka.producer.KafkaProducerOperation;
import dev.vertique.kafka.producer.KafkaProducerSend;
import dev.vertique.kafka.producer.KafkaSendOrigin;
import dev.vertique.kafka.producer.Topic;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import dev.vertique.kafka.serialization.KafkaSerdeProvider;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.kafka.serialization.KafkaSerializer;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.kafka.client.producer.RecordMetadata;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Record headers on the producer side, through a real {@link KafkaProducerFactory} whose wire send
 * is captured: the accepted and rejected {@link KafkaProducer @KafkaProducer} method shapes, the
 * headers that reach the wire and their order, the sends that fail because of a header, and the
 * headers the serializer and the capture hooks are given.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class KafkaProducerHeadersTest {

    private static final byte[] BINARY = {(byte) 0xff, 0x00, (byte) 0xfe};

    // --- Producer interfaces ---

    /** The four accepted shapes, plus the two that look ambiguous. */
    @KafkaProducer(name = "shapes")
    interface ShapeProducer {

        /**
         * @param value the record value
         * @return a future of the record metadata
         */
        @Topic("t.value")
        Future<RecordMetadata> value(String value);

        /**
         * @param key   the record key
         * @param value the record value
         * @return a future of the record metadata
         */
        @Topic("t.key-value")
        Future<RecordMetadata> keyValue(String key, Integer value);

        /**
         * @param value   the record value
         * @param headers the record headers
         * @return a future of the record metadata
         */
        @Topic("t.value-headers")
        Future<RecordMetadata> valueHeaders(Integer value, KafkaRecordHeaders headers);

        /**
         * @param key     the record key
         * @param value   the record value
         * @param headers the record headers
         * @return a future of the record metadata
         */
        @Topic("t.key-value-headers")
        Future<RecordMetadata> keyValueHeaders(String key, Integer value, KafkaRecordHeaders headers);

        /**
         * A {@code String} followed by headers is a value with headers, not a key.
         *
         * @param value   the record value
         * @param headers the record headers
         * @return a future of the record metadata
         */
        @Topic("t.string-value-headers")
        Future<RecordMetadata> stringValueHeaders(String value, KafkaRecordHeaders headers);

        /**
         * A {@code String} followed by a {@code Map} is a key and a {@code Map} value.
         *
         * @param key   the record key
         * @param value the record value
         * @return a future of the record metadata
         */
        @Topic("t.key-map-value")
        Future<RecordMetadata> keyMapValue(String key, Map<String, String> value);
    }

    /** A {@code Map} where the header parameter goes, after a value. */
    @KafkaProducer(name = "mapAfterValue")
    interface MapAfterValueProducer {

        /**
         * @param value   the record value
         * @param headers a text map, which is not accepted as headers
         * @return a future of the record metadata
         */
        @Topic("t.rejected")
        Future<RecordMetadata> publishWithMap(Integer value, Map<String, String> headers);
    }

    /** A {@code Map} where the header parameter goes, after a key and a value. */
    @KafkaProducer(name = "mapAfterKeyValue")
    interface MapAfterKeyValueProducer {

        /**
         * @param key     the record key
         * @param value   the record value
         * @param headers a text map, which is not accepted as headers
         * @return a future of the record metadata
         */
        @Topic("t.rejected")
        Future<RecordMetadata> publishKeyedWithMap(String key, Integer value, Map<String, String> headers);
    }

    /** Headers where the value goes. */
    @KafkaProducer(name = "headersValue")
    interface HeadersValueProducer {

        /**
         * @param key     the record key
         * @param value   headers, which are not accepted as a value
         * @param headers the record headers
         * @return a future of the record metadata
         */
        @Topic("t.rejected")
        Future<RecordMetadata> publishHeadersAsValue(String key, KafkaRecordHeaders value, KafkaRecordHeaders headers);
    }

    /** Only headers, so no value at all. */
    @KafkaProducer(name = "headersOnly")
    interface HeadersOnlyProducer {

        /**
         * @param headers the record headers
         * @return a future of the record metadata
         */
        @Topic("t.rejected")
        Future<RecordMetadata> publishHeadersOnly(KafkaRecordHeaders headers);
    }

    /** Two parameters before the headers, the first of which is not a {@code String} key. */
    @KafkaProducer(name = "nonStringKey")
    interface NonStringKeyProducer {

        /**
         * @param key   a key of a type that is not accepted
         * @param value the record value
         * @return a future of the record metadata
         */
        @Topic("t.rejected")
        Future<RecordMetadata> publishWithIntegerKey(Integer key, Integer value);
    }

    /** Headers before the value. */
    @KafkaProducer(name = "headersFirst")
    interface HeadersFirstProducer {

        /**
         * @param headers the record headers, in the wrong position
         * @param value   the record value
         * @return a future of the record metadata
         */
        @Topic("t.rejected")
        Future<RecordMetadata> publishHeadersFirst(KafkaRecordHeaders headers, Integer value);
    }

    /** No parameters. */
    @KafkaProducer(name = "noParameters")
    interface NoParametersProducer {

        /**
         * @return a future of the record metadata
         */
        @Topic("t.rejected")
        Future<RecordMetadata> publishNothing();
    }

    /** More parameters than any shape has. */
    @KafkaProducer(name = "tooMany")
    interface TooManyParametersProducer {

        /**
         * @param key     the record key
         * @param value   the record value
         * @param extra   a parameter no shape has
         * @param headers the record headers
         * @return a future of the record metadata
         */
        @Topic("t.rejected")
        Future<RecordMetadata> publishTooMany(String key, Integer value, Integer extra, KafkaRecordHeaders headers);
    }

    /** Two methods with the same name: their topic and serializer could not be told apart. */
    @KafkaProducer(name = "overloaded")
    interface OverloadedProducer {

        /**
         * @param value the record value
         * @return a future of the record metadata
         */
        @Topic("t.plain")
        Future<RecordMetadata> send(Integer value);

        /**
         * @param value   the record value
         * @param headers the record headers
         * @return a future of the record metadata
         */
        @Topic("t.with-headers")
        Future<RecordMetadata> send(Integer value, KafkaRecordHeaders headers);
    }

    /** Ambient value that the propagator encodes into a context header. */
    record AmbientTenant(String id) implements ContextValue {}

    // --- Accepted shapes ---

    @Nested
    @DisplayName("accepted method shapes")
    class AcceptedShapes {

        @Test
        @DisplayName("(V) sends the value with no key and no headers")
        void valueOnly(Vertx vertx) {
            WireCapturingFactory factory = factory(vertx);
            assertTrue(factory.create(ShapeProducer.class).value("hello").succeeded());

            Wire wire = factory.onlyWire();
            assertEquals("t.value", wire.topic());
            assertNull(wire.key());
            assertEquals("hello", text(wire.value()));
            assertSame(KafkaRecordHeaders.empty(), wire.headers());
            assertSame(KafkaRecordHeaders.empty(), factory.serializer.onlyHeaders());
        }

        @Test
        @DisplayName("(String, V) sends the key and the value with no headers")
        void keyAndValue(Vertx vertx) {
            WireCapturingFactory factory = factory(vertx);
            assertTrue(factory.create(ShapeProducer.class).keyValue("k", 7).succeeded());

            Wire wire = factory.onlyWire();
            assertEquals("t.key-value", wire.topic());
            assertEquals("k", wire.key());
            assertEquals("7", text(wire.value()));
            assertSame(KafkaRecordHeaders.empty(), wire.headers());
        }

        @Test
        @DisplayName("(V, KafkaRecordHeaders) sends the value and the headers with no key")
        void valueAndHeaders(Vertx vertx) {
            WireCapturingFactory factory = factory(vertx);
            KafkaRecordHeaders headers = KafkaRecordHeaders.of(Map.of("event-type", "order.created"));
            assertTrue(
                    factory.create(ShapeProducer.class).valueHeaders(7, headers).succeeded());

            Wire wire = factory.onlyWire();
            assertEquals("t.value-headers", wire.topic());
            assertNull(wire.key());
            assertEquals("7", text(wire.value()));
            assertEquals(headers, wire.headers());
        }

        @Test
        @DisplayName("(String, V, KafkaRecordHeaders) sends the key, the value and the headers")
        void keyValueAndHeaders(Vertx vertx) {
            WireCapturingFactory factory = factory(vertx);
            KafkaRecordHeaders headers = KafkaRecordHeaders.of(Map.of("event-type", "order.created"));
            assertTrue(factory.create(ShapeProducer.class)
                    .keyValueHeaders("k", 7, headers)
                    .succeeded());

            Wire wire = factory.onlyWire();
            assertEquals("t.key-value-headers", wire.topic());
            assertEquals("k", wire.key());
            assertEquals("7", text(wire.value()));
            assertEquals(headers, wire.headers());
        }

        @Test
        @DisplayName("(String, KafkaRecordHeaders) is a String value with headers, not a key")
        void stringValueAndHeaders(Vertx vertx) {
            WireCapturingFactory factory = factory(vertx);
            KafkaRecordHeaders headers = KafkaRecordHeaders.of(Map.of("event-type", "order.created"));
            assertTrue(factory.create(ShapeProducer.class)
                    .stringValueHeaders("payload", headers)
                    .succeeded());

            Wire wire = factory.onlyWire();
            assertEquals("t.string-value-headers", wire.topic());
            assertNull(wire.key(), "the String is the value, so the record has no key");
            assertEquals("payload", text(wire.value()));
            assertEquals(headers, wire.headers());
            assertEquals(String.class, factory.serializer.types.get("t.string-value-headers"));
        }

        @Test
        @DisplayName("(String, Map) is still a key and a Map value")
        void keyAndMapValue(Vertx vertx) {
            WireCapturingFactory factory = factory(vertx);
            assertTrue(factory.create(ShapeProducer.class)
                    .keyMapValue("k", Map.of("a", "b"))
                    .succeeded());

            Wire wire = factory.onlyWire();
            assertEquals("t.key-map-value", wire.topic());
            assertEquals("k", wire.key());
            assertEquals("{a=b}", text(wire.value()));
            assertSame(KafkaRecordHeaders.empty(), wire.headers());
            assertEquals(Map.class, factory.serializer.types.get("t.key-map-value"));
        }

        @Test
        @DisplayName("a null header argument means no headers")
        void nullHeaderArgument(Vertx vertx) {
            WireCapturingFactory factory = factory(vertx);
            ShapeProducer producer = factory.create(ShapeProducer.class);
            assertTrue(producer.valueHeaders(7, null).succeeded());
            assertTrue(producer.keyValueHeaders("k", 7, null).succeeded());

            assertEquals(2, factory.wires.size());
            assertSame(KafkaRecordHeaders.empty(), factory.wires.get(0).headers());
            assertSame(KafkaRecordHeaders.empty(), factory.wires.get(1).headers());
            assertEquals(
                    List.of(KafkaRecordHeaders.empty(), KafkaRecordHeaders.empty()),
                    factory.serializer.headers,
                    "the serializer is never given null headers");
        }
    }

    // --- Rejected shapes ---

    @Nested
    @DisplayName("rejected method shapes fail proxy creation")
    class RejectedShapes {

        @Test
        @DisplayName("a Map after the value names KafkaRecordHeaders.of(Map)")
        void mapAfterValue(Vertx vertx) {
            IllegalArgumentException e = rejected(vertx, MapAfterValueProducer.class);
            assertTrue(e.getMessage().contains("publishWithMap"), e.getMessage());
            assertTrue(e.getMessage().contains("KafkaRecordHeaders.of(Map)"), e.getMessage());
        }

        @Test
        @DisplayName("a Map after the key and the value names KafkaRecordHeaders.of(Map)")
        void mapAfterKeyAndValue(Vertx vertx) {
            IllegalArgumentException e = rejected(vertx, MapAfterKeyValueProducer.class);
            assertTrue(e.getMessage().contains("publishKeyedWithMap"), e.getMessage());
            assertTrue(e.getMessage().contains("KafkaRecordHeaders.of(Map)"), e.getMessage());
        }

        @Test
        @DisplayName("a KafkaRecordHeaders value type")
        void headersAsValue(Vertx vertx) {
            IllegalArgumentException e = rejected(vertx, HeadersValueProducer.class);
            assertTrue(e.getMessage().contains("publishHeadersAsValue"), e.getMessage());
            assertTrue(e.getMessage().contains("KafkaRecordHeaders"), e.getMessage());
        }

        @Test
        @DisplayName("headers without a value")
        void headersOnly(Vertx vertx) {
            IllegalArgumentException e = rejected(vertx, HeadersOnlyProducer.class);
            assertTrue(e.getMessage().contains("publishHeadersOnly"), e.getMessage());
        }

        @Test
        @DisplayName("two parameters whose first is not a String key")
        void nonStringKey(Vertx vertx) {
            IllegalArgumentException e = rejected(vertx, NonStringKeyProducer.class);
            assertTrue(e.getMessage().contains("publishWithIntegerKey"), e.getMessage());
        }

        @Test
        @DisplayName("headers before the value")
        void headersFirst(Vertx vertx) {
            IllegalArgumentException e = rejected(vertx, HeadersFirstProducer.class);
            assertTrue(e.getMessage().contains("publishHeadersFirst"), e.getMessage());
        }

        @Test
        @DisplayName("no parameters")
        void noParameters(Vertx vertx) {
            IllegalArgumentException e = rejected(vertx, NoParametersProducer.class);
            assertTrue(e.getMessage().contains("publishNothing"), e.getMessage());
        }

        @Test
        @DisplayName("more parameters than any shape has")
        void tooManyParameters(Vertx vertx) {
            IllegalArgumentException e = rejected(vertx, TooManyParametersProducer.class);
            assertTrue(e.getMessage().contains("publishTooMany"), e.getMessage());
        }

        @Test
        @DisplayName("two methods with the same name, naming both and the unique-name rule")
        void overloadedMethods(Vertx vertx) {
            IllegalArgumentException e = rejected(vertx, OverloadedProducer.class);
            assertTrue(e.getMessage().contains("#send(Integer)"), e.getMessage());
            assertTrue(e.getMessage().contains("#send(Integer, KafkaRecordHeaders)"), e.getMessage());
            assertTrue(e.getMessage().contains("producer method names must be unique"), e.getMessage());
        }

        private IllegalArgumentException rejected(Vertx vertx, Class<?> producerInterface) {
            WireCapturingFactory factory = factory(vertx);
            IllegalArgumentException e =
                    assertThrows(IllegalArgumentException.class, () -> factory.create(producerInterface));
            assertTrue(
                    e.getMessage().contains(producerInterface.getSimpleName()),
                    "the message names the interface: " + e.getMessage());
            return e;
        }
    }

    // --- Headers on the wire ---

    @Nested
    @DisplayName("headers on the wire")
    class WireHeaders {

        @Test
        @DisplayName("repeated and binary application headers arrive in order, followed by the context headers")
        void applicationHeadersInOrderThenContext(Vertx vertx) {
            WireCapturingFactory factory = factory(vertx);
            KafkaRecordHeaders application = new KafkaRecordHeaders(List.of(
                    KafkaRecordHeader.ofUtf8("trace", "first"),
                    new KafkaRecordHeader("signature", Buffer.buffer(BINARY)),
                    KafkaRecordHeader.ofUtf8("trace", "second"),
                    KafkaRecordHeader.ofUtf8("empty", "")));
            DurableMetadata context = DurableMetadata.of("correlation", new JsonObject().put("id", "c-1"))
                    .with("tenant", new JsonObject().put("id", "acme"));

            assertTrue(
                    factory.send("t", "k", new byte[] {1}, application, context).succeeded());

            List<KafkaRecordHeader> wire = factory.onlyWire().headers().entries();
            assertEquals(application.entries(), wire.subList(0, 4), "application headers first, in their order");
            assertArrayEquals(BINARY, wire.get(1).value().getBytes(), "the binary value is not re-encoded");
            assertEquals(
                    Set.of(
                            KafkaRecordHeader.ofUtf8("vertique-correlation", "{\"id\":\"c-1\"}"),
                            KafkaRecordHeader.ofUtf8("vertique-tenant", "{\"id\":\"acme\"}")),
                    new HashSet<>(wire.subList(4, wire.size())),
                    "then one text header per context namespace");
            assertEquals(6, wire.size());
        }

        @Test
        @DisplayName("the outbox send has the same order and uses the given context")
        void outboxSend(Vertx vertx) {
            WireCapturingFactory factory = factory(vertx);
            KafkaRecordHeaders application = new KafkaRecordHeaders(
                    List.of(KafkaRecordHeader.ofUtf8("trace", "first"), KafkaRecordHeader.ofUtf8("trace", "second")));
            DurableMetadata context = DurableMetadata.of("correlation", new JsonObject().put("id", "c-1"));

            assertTrue(factory.sendForOutbox("t", "k", new byte[] {1}, application, context)
                    .succeeded());

            Wire wire = factory.onlyWire();
            assertEquals(KafkaSendOrigin.OUTBOX, wire.origin());
            assertEquals(
                    List.of(
                            KafkaRecordHeader.ofUtf8("trace", "first"),
                            KafkaRecordHeader.ofUtf8("trace", "second"),
                            KafkaRecordHeader.ofUtf8("vertique-correlation", "{\"id\":\"c-1\"}")),
                    wire.headers().entries());
        }

        @Test
        @DisplayName("a proxy send appends the ambient context after the application headers")
        void proxySendAppendsAmbientContext(Vertx vertx, VertxTestContext ctx) {
            DefaultContextHolder holder = new DefaultContextHolder();
            DurableContextPropagator propagator = new DurableContextPropagator(
                    new DurableContextMetadataRegistry(Set.of(tenantEncoder()), Set.of()),
                    holder,
                    new ContextScopeBinder(holder));
            WireCapturingFactory factory = new WireCapturingFactory(vertx, propagator, Set.of());
            ShapeProducer producer = factory.create(ShapeProducer.class);
            KafkaRecordHeaders application = new KafkaRecordHeaders(
                    List.of(KafkaRecordHeader.ofUtf8("trace", "first"), KafkaRecordHeader.ofUtf8("trace", "second")));

            ((ContextInternal) vertx.getOrCreateContext()).duplicate().runOnContext(v -> {
                try (ContextHolder.Scope tenant = holder.bind(AmbientTenant.class, new AmbientTenant("acme"))) {
                    assertTrue(producer.keyValueHeaders("k", 7, application).succeeded());
                    ctx.verify(() -> {
                        assertEquals(
                                List.of(
                                        KafkaRecordHeader.ofUtf8("trace", "first"),
                                        KafkaRecordHeader.ofUtf8("trace", "second"),
                                        KafkaRecordHeader.ofUtf8("vertique-tenant", "{\"id\":\"acme\"}")),
                                factory.onlyWire().headers().entries());
                        assertEquals(
                                List.of(application),
                                factory.serializer.headers,
                                "the serializer is given the application headers");
                    });
                    ctx.completeNow();
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            });
        }

        @Test
        @DisplayName("null headers on the send methods mean no headers")
        void nullHeadersOnSendMethods(Vertx vertx) {
            WireCapturingFactory factory = factory(vertx);
            assertTrue(factory.send("t", "k", new byte[] {1}, null).succeeded());
            assertTrue(factory.send("t", "k", new byte[] {1}, null, DurableMetadata.empty())
                    .succeeded());
            assertTrue(factory.sendForOutbox("t", "k", new byte[] {1}, null, DurableMetadata.empty())
                    .succeeded());

            assertEquals(3, factory.wires.size());
            for (Wire wire : factory.wires) {
                assertSame(KafkaRecordHeaders.empty(), wire.headers());
            }
        }

        @Test
        @DisplayName("the dead-letter send forwards reserved, repeated and binary headers verbatim")
        void deadLetterSendForwardsVerbatim(Vertx vertx) {
            WireCapturingFactory factory = factory(vertx);
            KafkaRecordHeaders headers = new KafkaRecordHeaders(List.of(
                    KafkaRecordHeader.ofUtf8("vertique-correlation", "{\"id\":\"from-the-record\"}"),
                    KafkaRecordHeader.ofUtf8("x-dlq-source-topic", "orders"),
                    KafkaRecordHeader.ofUtf8("trace", "first"),
                    new KafkaRecordHeader("signature", Buffer.buffer(BINARY)),
                    KafkaRecordHeader.ofUtf8("trace", "second")));

            assertTrue(factory.sendForDlq("dlq", "k", new byte[] {1}, headers).succeeded());

            Wire wire = factory.onlyWire();
            assertEquals(KafkaSendOrigin.DLQ, wire.origin());
            assertSame(headers, wire.headers());
        }
    }

    // --- Sends a header fails ---

    @Nested
    @DisplayName("sends that a header fails")
    class FailedSends {

        @Test
        @DisplayName(
                "a reserved-prefix application header fails send, the context send, the outbox send and a proxy send")
        void reservedPrefixHeader(Vertx vertx) {
            WireCapturingFactory factory = factory(vertx);
            KafkaRecordHeaders headers = new KafkaRecordHeaders(List.of(
                    KafkaRecordHeader.ofUtf8("event-type", "order.created"),
                    KafkaRecordHeader.ofUtf8("vertique-correlation", "{}")));

            List<Future<RecordMetadata>> sends = List.of(
                    factory.send("t", "k", new byte[] {1}, headers),
                    factory.send("t", "k", new byte[] {1}, headers, DurableMetadata.empty()),
                    factory.sendForOutbox("t", "k", new byte[] {1}, headers, DurableMetadata.empty()),
                    factory.create(ShapeProducer.class).valueHeaders(7, headers));

            for (Future<RecordMetadata> send : sends) {
                assertTrue(send.failed());
                assertInstanceOf(IllegalArgumentException.class, send.cause());
                assertEquals(
                        "Application header uses reserved framework prefix 'vertique-': vertique-correlation",
                        send.cause().getMessage());
            }
            assertTrue(factory.wires.isEmpty(), "nothing reaches the wire");
        }

        @Test
        @DisplayName("a null-valued header fails every send, the dead-letter send included, naming the key")
        void nullValuedHeader(Vertx vertx) {
            WireCapturingFactory factory = factory(vertx);
            KafkaRecordHeaders headers = new KafkaRecordHeaders(List.of(
                    KafkaRecordHeader.ofUtf8("event-type", "order.created"), new KafkaRecordHeader("tombstone", null)));

            List<Future<RecordMetadata>> sends = List.of(
                    factory.send("t", "k", new byte[] {1}, headers),
                    factory.send("t", "k", new byte[] {1}, headers, DurableMetadata.empty()),
                    factory.sendForOutbox("t", "k", new byte[] {1}, headers, DurableMetadata.empty()),
                    factory.sendForDlq("t", "k", new byte[] {1}, headers),
                    factory.create(ShapeProducer.class).valueHeaders(7, headers));

            for (Future<RecordMetadata> send : sends) {
                assertTrue(send.failed());
                assertInstanceOf(IllegalArgumentException.class, send.cause());
                assertTrue(
                        send.cause().getMessage().contains("tombstone"),
                        send.cause().getMessage());
            }
            assertTrue(factory.wires.isEmpty(), "nothing reaches the wire");
        }

        @Test
        @DisplayName("the dead-letter send refuses headers without the source-topic header and points to send")
        void deadLetterSendWithoutSourceTopicHeader(Vertx vertx) {
            WireCapturingFactory factory = factory(vertx);
            KafkaRecordHeaders forged = new KafkaRecordHeaders(List.of(
                    KafkaRecordHeader.ofUtf8("vertique-correlation", "{\"id\":\"forged\"}"),
                    KafkaRecordHeader.ofUtf8("x-dlq-consumer", "orders")));

            List<Future<RecordMetadata>> sends = List.of(
                    factory.sendForDlq("t", "k", new byte[] {1}, null),
                    factory.sendForDlq("t", "k", new byte[] {1}, KafkaRecordHeaders.empty()),
                    factory.sendForDlq("t", "k", new byte[] {1}, forged));

            for (Future<RecordMetadata> send : sends) {
                assertTrue(send.failed());
                assertInstanceOf(IllegalArgumentException.class, send.cause());
                assertEquals(
                        "sendForDlq is the framework's dead-letter path and requires the 'x-dlq-source-topic'"
                                + " header; application sends must use send",
                        send.cause().getMessage());
            }
            assertTrue(factory.wires.isEmpty(), "nothing reaches the wire");
        }
    }

    // --- What the serializer and the hooks are given ---

    @Nested
    @DisplayName("the serializer and the capture hooks")
    class SerializerAndHooks {

        @Test
        @DisplayName("the serializer and both hook forms receive the same KafkaRecordHeaders")
        void sameHeadersEverywhere(Vertx vertx) {
            EventHook eventHook = new EventHook();
            PositionalHook positionalHook = new PositionalHook();
            WireCapturingFactory factory = new WireCapturingFactory(
                    vertx, KafkaTestSupport.noOpPropagator(), Set.of(eventHook, positionalHook));
            KafkaRecordHeaders headers = new KafkaRecordHeaders(List.of(
                    KafkaRecordHeader.ofUtf8("trace", "first"),
                    new KafkaRecordHeader("signature", Buffer.buffer(BINARY)),
                    KafkaRecordHeader.ofUtf8("trace", "second")));

            assertTrue(factory.create(ShapeProducer.class)
                    .keyValueHeaders("k", 7, headers)
                    .succeeded());

            assertSame(headers, factory.serializer.onlyHeaders(), "the serializer is given the caller's headers");
            assertSame(headers, factory.onlyWire().headers(), "no context, so the wire headers are the caller's");
            assertEquals(1, eventHook.sends.size());
            assertSame(headers, eventHook.sends.get(0).headers());
            assertEquals(1, positionalHook.headers.size());
            assertSame(headers, positionalHook.headers.get(0));
        }

        @Test
        @DisplayName("both hook forms receive the wire headers, context headers included")
        void hooksReceiveWireHeaders(Vertx vertx) {
            EventHook eventHook = new EventHook();
            PositionalHook positionalHook = new PositionalHook();
            WireCapturingFactory factory = new WireCapturingFactory(
                    vertx, KafkaTestSupport.noOpPropagator(), Set.of(eventHook, positionalHook));
            KafkaRecordHeaders application = KafkaRecordHeaders.of(Map.of("event-type", "order.created"));
            DurableMetadata context = DurableMetadata.of("correlation", new JsonObject().put("id", "c-1"));

            assertTrue(
                    factory.send("t", "k", new byte[] {1}, application, context).succeeded());

            KafkaRecordHeaders wire = factory.onlyWire().headers();
            assertEquals(
                    List.of(
                            KafkaRecordHeader.ofUtf8("event-type", "order.created"),
                            KafkaRecordHeader.ofUtf8("vertique-correlation", "{\"id\":\"c-1\"}")),
                    wire.entries());
            assertSame(wire, eventHook.sends.get(0).headers());
            assertSame(wire, positionalHook.headers.get(0));
        }
    }

    // --- Helpers ---

    private static WireCapturingFactory factory(Vertx vertx) {
        return new WireCapturingFactory(vertx, KafkaTestSupport.noOpPropagator(), Set.of());
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static DurableContextMetadataEncoder<AmbientTenant> tenantEncoder() {
        return new DurableContextMetadataEncoder<>() {
            @Override
            public Class<AmbientTenant> type() {
                return AmbientTenant.class;
            }

            @Override
            public String namespace() {
                return "tenant";
            }

            @Override
            public DurableMetadata encode(AmbientTenant value, DurableEncodeContext context) {
                return DurableMetadata.of("tenant", new JsonObject().put("id", value.id()));
            }
        };
    }

    /** One captured wire send. */
    record Wire(String topic, String key, byte[] value, KafkaRecordHeaders headers, KafkaSendOrigin origin) {}

    /** Hook overriding the send-event form. */
    static final class EventHook implements KafkaProducerCaptureHook {

        final List<KafkaProducerSend> sends = new CopyOnWriteArrayList<>();

        @Override
        public void onSend(KafkaProducerSend send) {
            sends.add(send);
        }
    }

    /** Hook overriding the positional form. */
    static final class PositionalHook implements KafkaProducerCaptureHook {

        final List<KafkaRecordHeaders> headers = new CopyOnWriteArrayList<>();

        @Override
        public void onSend(
                KafkaSendOrigin origin,
                String topic,
                String key,
                PayloadSource value,
                KafkaRecordHeaders headers,
                Method producerMethod,
                AsyncResult<RecordMetadata> result) {
            this.headers.add(headers);
        }
    }

    /**
     * A {@code json} serde provider whose serializer writes {@code String.valueOf(value)} and records
     * the headers it is given and the value type each topic's serializer was built for.
     */
    static final class RecordingSerdeProvider implements KafkaSerdeProvider {

        final List<KafkaRecordHeaders> headers = new CopyOnWriteArrayList<>();
        final Map<String, Class<?>> types = new java.util.concurrent.ConcurrentHashMap<>();

        KafkaRecordHeaders onlyHeaders() {
            assertEquals(1, headers.size(), "exactly one serialize call");
            return headers.get(0);
        }

        @Override
        public String format() {
            return "json";
        }

        @Override
        public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) {
            return (value, topic, recordHeaders) -> {
                headers.add(recordHeaders);
                types.put(topic, type);
                return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
            };
        }

        @Override
        public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) {
            throw new UnsupportedOperationException("not needed for producer tests");
        }
    }

    /**
     * A real {@link KafkaProducerFactory} whose wire send is replaced: it records what would go to
     * the broker, fires the hooks and succeeds.
     */
    static final class WireCapturingFactory extends KafkaProducerFactory {

        final List<Wire> wires = new CopyOnWriteArrayList<>();
        final RecordingSerdeProvider serializer;

        WireCapturingFactory(Vertx vertx, DurableContextPropagator propagator, Set<KafkaProducerCaptureHook> hooks) {
            this(vertx, propagator, hooks, new RecordingSerdeProvider());
        }

        private WireCapturingFactory(
                Vertx vertx,
                DurableContextPropagator propagator,
                Set<KafkaProducerCaptureHook> hooks,
                RecordingSerdeProvider serializer) {
            super(
                    vertx,
                    KafkaConfig.fromConfig(
                            new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", "localhost:9092")),
                            new DefaultConfigParser(DefaultConfigMapper.lenient())),
                    propagator,
                    new KafkaSerdeRegistry(Set.of(serializer)),
                    hooks);
            this.serializer = serializer;
        }

        Wire onlyWire() {
            assertEquals(1, wires.size(), "exactly one wire send");
            return wires.get(0);
        }

        @Override
        protected Future<RecordMetadata> sendWire(
                String topic,
                String key,
                byte[] value,
                KafkaRecordHeaders wire,
                KafkaSendOrigin origin,
                KafkaProducerOperation operation) {
            wires.add(new Wire(topic, key, value, wire, origin));
            fireHooks(origin, topic, key, value, wire, operation, Future.succeededFuture(null));
            return Future.succeededFuture(null);
        }
    }
}
