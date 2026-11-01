// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.context.ContextScopeBinder;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.producer.KafkaProducerCaptureHook;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import dev.vertique.kafka.producer.KafkaProducerOperation;
import dev.vertique.kafka.producer.KafkaProducerSend;
import dev.vertique.kafka.producer.KafkaSendOrigin;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import io.vertx.kafka.client.producer.RecordMetadata;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Dead-lettering through the real {@link KafkaErrorHandler} and a real {@link KafkaProducerFactory}
 * whose wire send is captured: a failed record that carries a framework context header is
 * published, and the dead-letter record keeps the failed record's context rather than the context
 * that happens to be bound when the error is handled; and the failed record's headers are forwarded
 * as they were received, followed by the dead-letter headers.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class KafkaErrorHandlerDlqHeadersTest {

    private static final String RECORD_CORRELATION = "{\"id\":\"from-the-record\"}";

    /** Ambient value encoded under the same namespace the failed record carries. */
    record AmbientCorrelation(String id) implements ContextValue {}

    /** Ambient value encoded under a namespace the failed record does not carry. */
    record AmbientTenant(String id) implements ContextValue {}

    @Test
    @DisplayName("a record with a framework context header is dead-lettered with its own context, "
            + "the dead-letter headers, and nothing from the ambient context")
    void deadLettersRecordThatCarriesContextHeader(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = new DurableContextPropagator(
                new DurableContextMetadataRegistry(
                        Set.of(
                                encoder(AmbientCorrelation.class, "correlation", AmbientCorrelation::id),
                                encoder(AmbientTenant.class, "tenant", AmbientTenant::id)),
                        Set.of()),
                holder,
                new ContextScopeBinder(holder));
        RecordingHook hook = new RecordingHook();
        WireCapturingFactory factory = new WireCapturingFactory(vertx, propagator, hook);
        KafkaErrorHandler handler = new KafkaErrorHandler(
                KafkaTerminalOutcomeTest.entryFor(ErrorStrategy.DEAD_LETTER, "dlq-topic"), factory);

        KafkaConsumerRecord<String, byte[]> record = KafkaTerminalOutcomeTest.fakeRecord("src.topic", 3, 42L);
        KafkaRecordHeaders recordHeaders = new KafkaRecordHeaders(List.of(
                KafkaRecordHeader.ofUtf8("vertique-correlation", RECORD_CORRELATION),
                KafkaRecordHeader.ofUtf8("event-type", "order.created")));

        ((ContextInternal) vertx.getOrCreateContext()).duplicate().runOnContext(v -> {
            try (ContextHolder.Scope correlation =
                            holder.bind(AmbientCorrelation.class, new AmbientCorrelation("ambient"));
                    ContextHolder.Scope tenant = holder.bind(AmbientTenant.class, new AmbientTenant("acme"))) {
                // The ambient context is really installed, and differs from the record's.
                DurableMetadata ambient = propagator.capture(DispatchBoundary.KAFKA);
                assertEquals(Set.of("correlation", "tenant"), ambient.namespaces());

                handler.publishToDlq(record, new byte[] {1, 2, 3}, recordHeaders, new RuntimeException("boom"))
                        .onComplete(ctx.succeeding(published -> ctx.verify(() -> {
                            assertEquals(1, factory.wires.size(), "exactly one wire send");
                            assertEquals(
                                    new KafkaRecordHeaders(List.of(
                                            KafkaRecordHeader.ofUtf8("vertique-correlation", RECORD_CORRELATION),
                                            KafkaRecordHeader.ofUtf8("event-type", "order.created"),
                                            KafkaRecordHeader.ofUtf8("x-dlq-source-topic", "src.topic"),
                                            KafkaRecordHeader.ofUtf8("x-dlq-source-partition", "3"),
                                            KafkaRecordHeader.ofUtf8("x-dlq-source-offset", "42"),
                                            KafkaRecordHeader.ofUtf8("x-dlq-consumer", "test"),
                                            KafkaRecordHeader.ofUtf8("x-dlq-error", "RuntimeException: boom"))),
                                    factory.wires.get(0),
                                    "the record's headers in order, then the dead-letter headers");
                            assertTrue(
                                    factory.wires
                                            .get(0)
                                            .headers("vertique-tenant")
                                            .isEmpty(),
                                    "a context header that was not on the record must not be added");

                            assertEquals(List.of(KafkaSendOrigin.DLQ), factory.origins);
                            assertEquals(1, hook.sends.size(), "the capture hook still fires");
                            assertEquals(KafkaSendOrigin.DLQ, hook.sends.get(0).origin());
                            assertEquals(
                                    RECORD_CORRELATION,
                                    hook.sends.get(0).headers().asMap().get("vertique-correlation"));
                            ctx.completeNow();
                        })));
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("a dead-lettered record keeps repeated and binary headers in order, omits a null-valued "
            + "header, drops inbound consumer and error headers and keeps the source position it already names")
    void deadLettersHeadersAsReceived(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = new DurableContextPropagator(
                new DurableContextMetadataRegistry(Set.of(), Set.of()), holder, new ContextScopeBinder(holder));
        RecordingHook hook = new RecordingHook();
        WireCapturingFactory factory = new WireCapturingFactory(vertx, propagator, hook);
        KafkaErrorHandler handler = new KafkaErrorHandler(
                KafkaTerminalOutcomeTest.entryFor(ErrorStrategy.DEAD_LETTER, "dlq-topic"), factory);

        byte[] notUtf8 = {(byte) 0xFF, (byte) 0xFE, 0x00};
        KafkaConsumerRecord<String, byte[]> record = KafkaTerminalOutcomeTest.fakeRecord("src.topic", 3, 42L);
        KafkaRecordHeaders recordHeaders = new KafkaRecordHeaders(List.of(
                KafkaRecordHeader.ofUtf8("a", "1"),
                new KafkaRecordHeader("bin", Buffer.buffer(notUtf8)),
                KafkaRecordHeader.ofUtf8("x-dlq-error", "from an earlier dead-lettering"),
                KafkaRecordHeader.ofUtf8("a", "2"),
                new KafkaRecordHeader("nulled", null),
                KafkaRecordHeader.ofUtf8("vertique-correlation", RECORD_CORRELATION),
                KafkaRecordHeader.ofUtf8("x-dlq-source-topic", "older.topic"),
                KafkaRecordHeader.ofUtf8("x-dlq-source-partition", "9"),
                KafkaRecordHeader.ofUtf8("x-dlq-source-offset", "999"),
                KafkaRecordHeader.ofUtf8("x-dlq-consumer", "older-consumer"),
                KafkaRecordHeader.ofUtf8("x-dlq-error-detail", "not a dead-letter header of the framework"),
                new KafkaRecordHeader("a", null),
                KafkaRecordHeader.ofUtf8("empty", ""),
                KafkaRecordHeader.ofUtf8("a", "1")));

        handler.publishToDlq(record, new byte[] {1, 2, 3}, recordHeaders, new IllegalStateException("boom"))
                .onComplete(ctx.succeeding(published -> ctx.verify(() -> {
                    assertEquals(1, factory.wires.size(), "exactly one wire send");
                    assertEquals(
                            new KafkaRecordHeaders(List.of(
                                    KafkaRecordHeader.ofUtf8("a", "1"),
                                    new KafkaRecordHeader(
                                            "bin", Buffer.buffer(new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00})),
                                    KafkaRecordHeader.ofUtf8("a", "2"),
                                    KafkaRecordHeader.ofUtf8("vertique-correlation", RECORD_CORRELATION),
                                    KafkaRecordHeader.ofUtf8(
                                            "x-dlq-error-detail", "not a dead-letter header of the framework"),
                                    KafkaRecordHeader.ofUtf8("empty", ""),
                                    KafkaRecordHeader.ofUtf8("a", "1"),
                                    KafkaRecordHeader.ofUtf8("x-dlq-source-topic", "older.topic"),
                                    KafkaRecordHeader.ofUtf8("x-dlq-source-partition", "9"),
                                    KafkaRecordHeader.ofUtf8("x-dlq-source-offset", "999"),
                                    KafkaRecordHeader.ofUtf8("x-dlq-consumer", "test"),
                                    KafkaRecordHeader.ofUtf8("x-dlq-error", "IllegalStateException: boom"))),
                            factory.wires.get(0));
                    assertEquals(14, recordHeaders.entries().size(), "the failed record's headers are untouched");
                    assertEquals(1, hook.sends.size(), "the capture hook still fires");
                    assertEquals(factory.wires.get(0), hook.sends.get(0).headers());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("a record dead-lettered again and again keeps the position of the record it started as")
    void positionSurvivesAnyNumberOfDeadLetterings(Vertx vertx, VertxTestContext ctx) {
        WireCapturingFactory factory =
                new WireCapturingFactory(vertx, KafkaTestSupport.noOpPropagator(), new RecordingHook());
        KafkaErrorHandler handler = new KafkaErrorHandler(
                KafkaTerminalOutcomeTest.entryFor(ErrorStrategy.DEAD_LETTER, "dlq-topic"), factory);
        KafkaConsumerRecord<String, byte[]> original = KafkaTerminalOutcomeTest.fakeRecord("src.topic", 3, 42L);

        // When the record is dead-lettered, and its dead letter, read at its own position, is dead-lettered
        // twice more
        handler.publishToDlq(original, new byte[] {1}, KafkaRecordHeaders.empty(), new RuntimeException("one"))
                .compose(first -> handler.publishToDlq(
                        KafkaTerminalOutcomeTest.fakeRecord("dlq-topic", 0, 7L),
                        new byte[] {1},
                        factory.wires.get(0),
                        new RuntimeException("two")))
                .compose(second -> handler.publishToDlq(
                        KafkaTerminalOutcomeTest.fakeRecord("dlq-topic", 0, 19L),
                        new byte[] {1},
                        factory.wires.get(1),
                        new RuntimeException("three")))
                .onComplete(ctx.succeeding(done -> ctx.verify(() -> {
                    // Then each dead letter names the original position, once, and the newest failure
                    for (KafkaRecordHeaders wire : factory.wires) {
                        assertEquals(List.of("src.topic"), texts(wire, "x-dlq-source-topic"));
                        assertEquals(List.of("3"), texts(wire, "x-dlq-source-partition"));
                        assertEquals(List.of("42"), texts(wire, "x-dlq-source-offset"));
                        assertEquals(1, wire.headers("x-dlq-consumer").size());
                        assertEquals(1, wire.headers("x-dlq-error").size());
                    }
                    assertEquals(List.of("RuntimeException: three"), texts(factory.wires.get(2), "x-dlq-error"));
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("a record that names only part of a source position is given the position it is read at")
    void partialPositionIsReplaced(Vertx vertx, VertxTestContext ctx) {
        WireCapturingFactory factory =
                new WireCapturingFactory(vertx, KafkaTestSupport.noOpPropagator(), new RecordingHook());
        KafkaErrorHandler handler = new KafkaErrorHandler(
                KafkaTerminalOutcomeTest.entryFor(ErrorStrategy.DEAD_LETTER, "dlq-topic"), factory);
        KafkaRecordHeaders partial = new KafkaRecordHeaders(List.of(
                KafkaRecordHeader.ofUtf8("x-dlq-source-topic", "older.topic"),
                KafkaRecordHeader.ofUtf8("x-dlq-source-partition", "9")));

        handler.publishToDlq(
                        KafkaTerminalOutcomeTest.fakeRecord("src.topic", 3, 42L),
                        new byte[] {1},
                        partial,
                        new RuntimeException("boom"))
                .onComplete(ctx.succeeding(done -> ctx.verify(() -> {
                    assertEquals(List.of("src.topic"), texts(factory.wires.get(0), "x-dlq-source-topic"));
                    assertEquals(List.of("3"), texts(factory.wires.get(0), "x-dlq-source-partition"));
                    assertEquals(List.of("42"), texts(factory.wires.get(0), "x-dlq-source-offset"));
                    ctx.completeNow();
                })));
    }

    private static List<String> texts(KafkaRecordHeaders headers, String key) {
        return headers.headers(key).stream().map(KafkaRecordHeader::valueAsUtf8).toList();
    }

    private static <T extends ContextValue> DurableContextMetadataEncoder<T> encoder(
            Class<T> type, String namespace, java.util.function.Function<T, String> id) {
        return new DurableContextMetadataEncoder<>() {
            @Override
            public Class<T> type() {
                return type;
            }

            @Override
            public String namespace() {
                return namespace;
            }

            @Override
            public DurableMetadata encode(T value, DurableEncodeContext context) {
                return DurableMetadata.of(namespace, new JsonObject().put("id", id.apply(value)));
            }
        };
    }

    /** Records every send event. */
    static final class RecordingHook implements KafkaProducerCaptureHook {

        final List<KafkaProducerSend> sends = new CopyOnWriteArrayList<>();

        @Override
        public void onSend(KafkaProducerSend send) {
            sends.add(send);
        }
    }

    /**
     * A real {@link KafkaProducerFactory} whose wire send is replaced: it records the headers that
     * would go to the broker, fires the hooks and succeeds.
     */
    static final class WireCapturingFactory extends KafkaProducerFactory {

        final List<KafkaRecordHeaders> wires = new CopyOnWriteArrayList<>();
        final List<KafkaSendOrigin> origins = new CopyOnWriteArrayList<>();

        WireCapturingFactory(Vertx vertx, DurableContextPropagator propagator, KafkaProducerCaptureHook hook) {
            super(
                    vertx,
                    KafkaConfig.fromConfig(
                            new JsonObject().put("kafka", new JsonObject().put("bootstrap.servers", "localhost:9092")),
                            new DefaultConfigParser(DefaultConfigMapper.lenient())),
                    propagator,
                    KafkaTestSupport.jsonSerdeRegistry(),
                    Set.of(hook));
        }

        @Override
        protected Future<RecordMetadata> sendWire(
                String topic,
                String key,
                byte[] value,
                KafkaRecordHeaders wire,
                KafkaSendOrigin origin,
                KafkaProducerOperation operation) {
            wires.add(wire);
            origins.add(origin);
            fireHooks(origin, topic, key, value, wire, operation, Future.succeededFuture(null));
            return Future.succeededFuture(null);
        }
    }
}
