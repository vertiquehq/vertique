// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import dev.vertique.kafka.KafkaTerminalOutcomeTest.CapturingConsumerControl;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.interceptor.KafkaTerminalOutcome;
import dev.vertique.kafka.producer.KafkaProducerCaptureHook;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import dev.vertique.kafka.producer.KafkaProducerOperation;
import dev.vertique.kafka.producer.KafkaProducerSend;
import dev.vertique.kafka.producer.KafkaSendOrigin;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import io.vertx.kafka.client.producer.RecordMetadata;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * that happens to be bound when the error is handled.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class KafkaErrorHandlerDlqHeadersTest {

    private static final String RECORD_CORRELATION = "{\"id\":\"from-the-record\"}";

    private static final List<String> DLQ_HEADERS = List.of(
            "x-dlq-source-topic", "x-dlq-source-partition", "x-dlq-source-offset", "x-dlq-consumer", "x-dlq-error");

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
        Map<String, String> recordHeaders = new HashMap<>();
        recordHeaders.put("vertique-correlation", RECORD_CORRELATION);
        recordHeaders.put("event-type", "order.created");
        CapturingConsumerControl control = new CapturingConsumerControl();

        ((ContextInternal) vertx.getOrCreateContext()).duplicate().runOnContext(v -> {
            try (ContextHolder.Scope correlation =
                            holder.bind(AmbientCorrelation.class, new AmbientCorrelation("ambient"));
                    ContextHolder.Scope tenant = holder.bind(AmbientTenant.class, new AmbientTenant("acme"))) {
                // The ambient context is really installed, and differs from the record's.
                DurableMetadata ambient = propagator.capture(DispatchBoundary.KAFKA);
                assertEquals(Set.of("correlation", "tenant"), ambient.namespaces());

                handler.handleError(record, new byte[] {1, 2, 3}, recordHeaders, new RuntimeException("boom"), control)
                        .onComplete(ctx.succeeding(outcome -> ctx.verify(() -> {
                            assertEquals(KafkaTerminalOutcome.DLQ_PUBLISHED, outcome);
                            assertTrue(control.commitCalled, "the offset is committed after the publish");

                            assertEquals(1, factory.wires.size(), "exactly one wire send");
                            Map<String, String> wire = factory.wires.get(0).asMap();
                            assertEquals(RECORD_CORRELATION, wire.get("vertique-correlation"));
                            assertEquals("order.created", wire.get("event-type"));
                            assertEquals("src.topic", wire.get("x-dlq-source-topic"));
                            assertEquals("3", wire.get("x-dlq-source-partition"));
                            assertEquals("42", wire.get("x-dlq-source-offset"));
                            assertEquals("test", wire.get("x-dlq-consumer"));
                            assertEquals("RuntimeException: boom", wire.get("x-dlq-error"));
                            assertFalse(
                                    wire.containsKey("vertique-tenant"),
                                    "a context header that was not on the record must not be added");
                            Set<String> expectedKeys = new java.util.HashSet<>(DLQ_HEADERS);
                            expectedKeys.addAll(recordHeaders.keySet());
                            assertEquals(expectedKeys, wire.keySet());

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
