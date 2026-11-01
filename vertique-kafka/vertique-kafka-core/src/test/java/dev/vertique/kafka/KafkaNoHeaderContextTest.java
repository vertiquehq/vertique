// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static dev.vertique.kafka.KafkaTestSupport.configParser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.DurableMetadataHeaderCodec;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceTargetResolver;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a handler binding built with {@code noHeaderContext()} installs no durable context
 * from the record's headers, while a binding built without it still does.
 *
 * <p>Both dispatchers run over handler-kind entries that the registry builds from real bindings, in
 * a Dagger graph with the same runtime context wiring as {@link KafkaDurableScopeContextTest}. The
 * headers carry a body for a decoder registered in that graph, so a handler sees the decoded value
 * only when the dispatcher installed the header context.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class KafkaNoHeaderContextTest {

    /** Context value that only the headers carry. */
    record Unrelated(String value) implements ContextValue {}

    /** Payload type of the bindings; the dispatch below hands the handlers a placeholder value. */
    record Payload(String id) {}

    @Module
    static class DecoderModule {
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
    }

    /** The runtime context wiring of the Kafka module with the decoder above. */
    @Singleton
    @Component(modules = {KafkaModule.class, ContextRuntimeModule.class, DecoderModule.class})
    interface HeaderContextGraph {
        ContextHolder holder();

        InboundExecutionContextScope ingress();

        DispatchEnvelopeBuilder envelopes();
    }

    @Test
    @DisplayName("noHeaderContext installs no durable context for the handler")
    void noHeaderContextInstallsNoDurableContextForTheHandler(Vertx vertx, VertxTestContext ctx) {
        // Given: two dispatchers over handler-kind entries, one built with noHeaderContext and one without,
        // and headers that carry an 'unrelated' body 'decoded'
        HeaderContextGraph graph = DaggerKafkaNoHeaderContextTest_HeaderContextGraph.create();
        ContextHolder holder = graph.holder();
        AtomicReference<Optional<Unrelated>> seenByDefault = new AtomicReference<>();
        AtomicReference<Optional<Unrelated>> seenWithoutHeaderContext = new AtomicReference<>();
        KafkaRecordDispatcher byDefault = dispatcher(graph, false, message -> {
            seenByDefault.set(holder.current(Unrelated.class));
            return Future.succeededFuture();
        });
        KafkaRecordDispatcher withoutHeaderContext = dispatcher(graph, true, message -> {
            seenWithoutHeaderContext.set(holder.current(Unrelated.class));
            return Future.succeededFuture();
        });
        DurableMetadata metadata = DurableMetadata.of("unrelated", new JsonObject().put("value", "decoded"));

        // When: each dispatcher's dispatchToHandler runs with those headers on a duplicated context
        onDuplicateAsync(vertx, ctx, () -> dispatch(byDefault, metadata)
                .compose(ignored -> checked(() -> {
                    assertTrue(
                            holder.current(Unrelated.class).isEmpty(), "the default dispatch must release its scope");
                }))
                .compose(ignored -> dispatch(withoutHeaderContext, metadata))
                .compose(ignored -> checked(() -> {
                    // Then: the default handler sees the decoded value, the noHeaderContext handler sees none
                    assertEquals(
                            Optional.of(new Unrelated("decoded")),
                            seenByDefault.get(),
                            "the default entry must install the header context");
                    assertEquals(
                            Optional.empty(),
                            seenWithoutHeaderContext.get(),
                            "the noHeaderContext entry must install no header context");

                    // And: the holder is empty again after either returned
                    assertTrue(
                            holder.current(Unrelated.class).isEmpty(),
                            "no dispatch may leave the header context installed");
                }))
                .eventually(() -> {
                    byDefault.close();
                    withoutHeaderContext.close();
                    return Future.succeededFuture();
                }));
    }

    // --- Helpers ---

    /**
     * Builds a dispatcher over the handler-kind entry the registry makes of a handler binding.
     *
     * @param graph the runtime context wiring the dispatcher installs headers through
     * @param noHeaderContext whether the binding opts out of the header context
     * @param handler the binding's handler
     * @return the dispatcher
     */
    private static KafkaRecordDispatcher dispatcher(
            HeaderContextGraph graph, boolean noHeaderContext, KafkaRecordHandler<Payload> handler) {
        KafkaConsumerBinding.Builder<Payload> builder = KafkaConsumerBinding.builder("header-context", Payload.class)
                .topic("header-context.events")
                .groupId("header-context-group")
                .commitStrategy(CommitStrategy.MANUAL)
                .handler(handler);
        if (noHeaderContext) {
            builder.noHeaderContext();
        }
        ServiceContractRegistry services = ServiceContractRegistry.build(Set.of(), configParser());
        KafkaConsumerRegistry registry = KafkaConsumerRegistry.build(
                Set.of(builder.build()),
                Set.of(),
                services,
                ServiceTargetResolver.of(services),
                KafkaTestSupport.jsonSerdeRegistry(),
                KafkaConfig.fromConfig(new JsonObject().put("kafka", new JsonObject()), configParser()));
        ConsumerEntry entry = registry.entries().get(0);
        assertEquals(ConsumerEntry.Kind.HANDLER, entry.kind(), "a handler binding must make a handler-kind entry");
        return new KafkaRecordDispatcher(
                entry,
                Map.of(),
                KafkaTestSupport.jsonSerdeRegistry(),
                null,
                null,
                null,
                graph.ingress(),
                graph.envelopes());
    }

    private static Future<Void> dispatch(KafkaRecordDispatcher dispatcher, DurableMetadata metadata) {
        @SuppressWarnings("unchecked")
        KafkaConsumerRecord<String, byte[]> record = mock(KafkaConsumerRecord.class);
        when(record.topic()).thenReturn("header-context.events");
        when(record.key()).thenReturn("key");
        Map<String, String> headers = DurableMetadataHeaderCodec.toHeaders(metadata);
        return dispatcher.dispatchToHandler("payload", record, KafkaRecordHeaders.of(headers), headers);
    }

    private static Future<Void> checked(Runnable assertions) {
        try {
            assertions.run();
            return Future.succeededFuture();
        } catch (Throwable failure) {
            return Future.failedFuture(failure);
        }
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
