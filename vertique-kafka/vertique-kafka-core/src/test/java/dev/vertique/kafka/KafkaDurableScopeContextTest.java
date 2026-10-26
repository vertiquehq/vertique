// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.DurableMetadataHeaderCodec;
import dev.vertique.core.scope.ScopeContext;
import dev.vertique.core.scope.ScopeKey;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Proves typed Kafka exclusion and real handler ingress using the same effective runtime graph. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class KafkaDurableScopeContextTest {

    private static final ScopeContext ACME = new ScopeContext(new ScopeKey("acme"));
    private static final ScopeContext AMBIENT = new ScopeContext(new ScopeKey("ambient"));

    record Unrelated(String value) implements ContextValue {}

    @Module
    static class ReceiverModule {
        @Provides
        @IntoSet
        static DurableContextMetadataDecoder<?> unrelatedDecoder() {
            return new DurableContextMetadataDecoder<Unrelated>() {
                @Override
                public Class<Unrelated> type() {
                    return Unrelated.class;
                }

                @Override
                public String namespace() {
                    return "unrelated";
                }

                @Override
                public ContextDecodeResult<Unrelated> decode(DurableMetadata metadata, DurableDecodeContext context) {
                    return metadata.body(namespace())
                            .map(body -> new Unrelated(body.getString("value")))
                            .map(ContextDecodeResult::of)
                            .orElseGet(ContextDecodeResult::empty);
                }
            };
        }

        @Provides
        @Singleton
        static KafkaRecordDispatcher receiver(
                KafkaRecordHandler<Object> handler,
                KafkaSerdeRegistry serdes,
                InboundExecutionContextScope ingress,
                DispatchEnvelopeBuilder envelopes) {
            ConsumerEntry entry = new ConsumerEntry(
                    "scope-consumer",
                    mock(ResolvedKafkaConsumerConfig.class),
                    ConsumerEntry.Kind.HANDLER,
                    Object.class,
                    null,
                    null,
                    false,
                    List.of(),
                    handler,
                    null,
                    false,
                    null,
                    "json");
            return new KafkaRecordDispatcher(entry, Map.of(), serdes, null, null, null, ingress, envelopes);
        }
    }

    @Singleton
    @Component(modules = {KafkaModule.class, ContextRuntimeModule.class, ReceiverModule.class})
    interface KafkaGraph {
        ContextHolder holder();

        DurableContextPropagator propagator();

        KafkaRecordDispatcher receiver();

        @Component.Factory
        interface Factory {
            KafkaGraph create(@BindsInstance KafkaRecordHandler<Object> handler);
        }
    }

    @Test
    @DisplayName("the Kafka graph has an active scope encoder and decoder at WORKFLOW")
    void usesEffectiveScopeCodecAtSupportedBoundary(Vertx vertx, VertxTestContext ctx) {
        KafkaGraph graph = graph(ignored -> Future.succeededFuture());
        onDuplicate(vertx, ctx, () -> {
            DurableMetadata captured;
            try (ContextHolder.Scope scope = graph.holder().bind(ScopeContext.class, ACME)) {
                captured = graph.propagator().capture(DispatchBoundary.WORKFLOW);
            }
            assertEquals(
                    scopeBody(),
                    captured.body("scope").orElse(null),
                    "WORKFLOW must activate this Kafka graph's scope encoder");
            try (ContextHolder.Scope restored = graph.propagator().bindFrom(captured, DispatchBoundary.WORKFLOW)) {
                assertEquals(
                        ACME,
                        graph.holder().current(ScopeContext.class).orElseThrow(),
                        "WORKFLOW must activate this Kafka graph's scope decoder");
            }
            assertTrue(graph.holder().current(ScopeContext.class).isEmpty());
        });
    }

    @Test
    @DisplayName("Kafka captures no typed scope and ignores even a valid raw scope body on receipt")
    void doesNotCaptureOrDecodeScopeAtKafka(Vertx vertx, VertxTestContext ctx) {
        AtomicReference<KafkaGraph> reference = new AtomicReference<>();
        KafkaGraph graph = graph(ignored -> checked(() -> assertTrue(
                reference.get().holder().current(ScopeContext.class).isEmpty(),
                "Kafka must not decode a typed scope")));
        reference.set(graph);
        onDuplicateAsync(vertx, ctx, () -> {
            try (ContextHolder.Scope scope = graph.holder().bind(ScopeContext.class, ACME)) {
                assertFalse(graph.propagator().capture(DispatchBoundary.KAFKA).has("scope"));
            }
            assertFalse(graph.propagator()
                    .decodeToDispatchContext(savedScope(), DispatchBoundary.KAFKA)
                    .containsKey(ScopeContext.class.getName()));
            return dispatch(graph, savedScope()).eventually(() -> {
                graph.receiver().close();
                return Future.succeededFuture();
            });
        });
    }

    @Test
    @DisplayName("authoritative Kafka ingress clears ambient scope then restores it on handler completion")
    void clearsAndRestoresAmbientScopeAtAuthoritativeIngress(Vertx vertx, VertxTestContext ctx) {
        AtomicReference<KafkaGraph> reference = new AtomicReference<>();
        Promise<Void> handlerCompletion = Promise.promise();
        KafkaGraph graph = graph(ignored -> {
            try {
                assertTrue(
                        reference.get().holder().current(ScopeContext.class).isEmpty(),
                        "authoritative Kafka ingress must clear the pre-bound scope");
                Vertx.currentContext().runOnContext(unused -> handlerCompletion.complete());
                return handlerCompletion.future();
            } catch (Throwable failure) {
                return Future.failedFuture(failure);
            }
        });
        reference.set(graph);
        onDuplicateAsync(vertx, ctx, () -> {
            ContextHolder.Scope ambient = graph.holder().bind(ScopeContext.class, AMBIENT);
            return dispatch(graph, savedScope()).eventually(() -> {
                try {
                    assertEquals(
                            AMBIENT,
                            graph.holder().current(ScopeContext.class).orElseThrow(),
                            "Kafka completion must restore the prior scope");
                } finally {
                    ambient.close();
                    graph.receiver().close();
                }
                return Future.succeededFuture();
            });
        });
    }

    @Test
    @DisplayName("the application handler can bind and close its own scope after Kafka ingress")
    void applicationHandlerCanBindScopeAfterKafkaIngress(Vertx vertx, VertxTestContext ctx) {
        AtomicReference<KafkaGraph> reference = new AtomicReference<>();
        KafkaGraph graph = graph(ignored -> checked(() -> {
            ContextHolder holder = reference.get().holder();
            assertTrue(holder.current(ScopeContext.class).isEmpty());
            try (ContextHolder.Scope application = holder.bind(ScopeContext.class, ACME)) {
                assertEquals(ACME, holder.current(ScopeContext.class).orElseThrow());
            }
            assertTrue(
                    holder.current(ScopeContext.class).isEmpty(), "the application binding must unwind within ingress");
        }));
        reference.set(graph);
        onDuplicateAsync(vertx, ctx, () -> dispatch(graph, savedScope()).eventually(() -> {
            graph.receiver().close();
            return Future.succeededFuture();
        }));
    }

    @Test
    @DisplayName("Kafka still decodes unrelated durable context while excluding scope")
    void decodesUnrelatedContextAtKafka(Vertx vertx, VertxTestContext ctx) {
        AtomicReference<KafkaGraph> reference = new AtomicReference<>();
        KafkaGraph graph = graph(ignored -> checked(() -> {
            assertEquals(
                    new Unrelated("decoded"),
                    reference.get().holder().current(Unrelated.class).orElseThrow());
            assertTrue(reference.get().holder().current(ScopeContext.class).isEmpty());
        }));
        reference.set(graph);
        onDuplicateAsync(vertx, ctx, () -> dispatch(
                        graph, savedScope().with("unrelated", new JsonObject().put("value", "decoded")))
                .eventually(() -> {
                    try {
                        assertTrue(graph.holder().current(Unrelated.class).isEmpty());
                    } finally {
                        graph.receiver().close();
                    }
                    return Future.succeededFuture();
                }));
    }

    private static KafkaGraph graph(KafkaRecordHandler<Object> handler) {
        return DaggerKafkaDurableScopeContextTest_KafkaGraph.factory().create(handler);
    }

    private static Future<Void> dispatch(KafkaGraph graph, DurableMetadata metadata) {
        @SuppressWarnings("unchecked")
        KafkaConsumerRecord<String, byte[]> record = mock(KafkaConsumerRecord.class);
        when(record.topic()).thenReturn("scope-topic");
        when(record.key()).thenReturn("key");
        Map<String, String> headers = DurableMetadataHeaderCodec.toHeaders(metadata);
        return graph.receiver().dispatchToHandler("payload", record, KafkaRecordHeaders.of(headers), headers);
    }

    private static JsonObject scopeBody() {
        return new JsonObject().put("scopeKey", "acme");
    }

    private static DurableMetadata savedScope() {
        return DurableMetadata.of("scope", scopeBody());
    }

    private static Future<Void> checked(Runnable assertions) {
        try {
            assertions.run();
            return Future.succeededFuture();
        } catch (Throwable failure) {
            return Future.failedFuture(failure);
        }
    }

    private static void onDuplicate(Vertx vertx, VertxTestContext ctx, Runnable assertions) {
        onDuplicateAsync(vertx, ctx, () -> checked(assertions));
    }

    private static void onDuplicateAsync(
            Vertx vertx, VertxTestContext ctx, java.util.function.Supplier<Future<Void>> action) {
        ContextInternal duplicate = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        duplicate.runOnContext(ignored -> {
            try {
                action.get().onComplete(ctx.succeeding(unused -> ctx.completeNow()));
            } catch (Throwable failure) {
                ctx.failNow(failure);
            }
        });
    }
}
