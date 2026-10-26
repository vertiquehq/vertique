// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextDecodeWarning;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.scope.ScopeContext;
import dev.vertique.core.scope.ScopeKey;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

/** Proves the effective runtime scope codecs through their existing SPI and propagator APIs. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ScopeContextDurableMetadataCodecsTest {

    @Singleton
    @Component(modules = {ContextRuntimeModule.class, UnrelatedModule.class})
    interface RuntimeGraph {
        ContextHolder holder();

        DurableContextMetadataRegistry registry();

        DurableContextPropagator propagator();
    }

    record Unrelated(String value) implements ContextValue {}

    @Module
    static class UnrelatedModule {
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

    @Test
    @DisplayName("the runtime pair writes the exact unversioned body and preserves every accepted key")
    void encodesExactBodyAndRoundTripsAcceptedKeys(Vertx vertx, VertxTestContext ctx) {
        RuntimeGraph graph = DaggerScopeContextDurableMetadataCodecsTest_RuntimeGraph.create();
        onDuplicate(vertx, ctx, () -> {
            assertFalse(graph.propagator().capture(DispatchBoundary.WORKFLOW).has("scope"));
            for (String key :
                    List.of("acme", "AcMe", "default", "a b", "a\u00a0b", "a/b", "\ud83c\udf31".repeat(255))) {
                ScopeContext expected = new ScopeContext(new ScopeKey(key));
                for (String producer :
                        List.of(DispatchBoundary.WORKFLOW, DispatchBoundary.OUTBOX, DispatchBoundary.DELAYED_JOB)) {
                    DurableMetadata captured;
                    try (ContextHolder.Scope ignored = graph.holder().bind(ScopeContext.class, expected)) {
                        captured = graph.propagator().capture(producer);
                    }
                    assertEquals(Set.of("scope"), captured.namespaces(), producer);
                    assertEquals(body(key), captured.body("scope").orElseThrow(), producer);
                    assertEquals(new JsonObject().put("scope", body(key)), captured.toJson());
                    for (String receiver : List.of(
                            DispatchBoundary.WORKFLOW, DispatchBoundary.OUTBOX_SERVICE, DispatchBoundary.DELAYED_JOB)) {
                        try (ContextHolder.Scope ignored = graph.propagator().bindFrom(captured, receiver)) {
                            assertEquals(
                                    expected,
                                    graph.holder().current(ScopeContext.class).orElseThrow(),
                                    receiver);
                        }
                        assertTrue(graph.holder().current(ScopeContext.class).isEmpty());
                    }
                }
            }
            DurableContextMetadataDecoder<?> decoder = scopeDecoder(graph);
            assertTrue(decoder.acceptsExplicitCarrier());
            assertEquals(
                    ContextDecodeResult.empty(),
                    decoder.decode(DurableMetadata.empty(), new DurableDecodeContext(DispatchBoundary.WORKFLOW)));
            assertEquals(
                    new ScopeContext(new ScopeKey("acme")),
                    decoder.decode(
                                    DurableMetadata.of("scope", body("acme")),
                                    new DurableDecodeContext(DispatchBoundary.WORKFLOW))
                            .value()
                            .orElseThrow());
            DurableMetadata explicit = DurableMetadata.of("scope", body("caller"));
            assertSame(explicit, graph.propagator().sanitizeInboundCarrier(explicit));
        });
    }

    @Test
    @DisplayName("invalid bodies yield only stable private warnings and leave unrelated decoders working")
    void rejectsInvalidBodiesWithoutLeakingInput() {
        RuntimeGraph graph = DaggerScopeContextDurableMetadataCodecsTest_RuntimeGraph.create();
        DurableContextMetadataDecoder<?> decoder = scopeDecoder(graph);
        List<RejectedBody> rejected = List.of(
                new RejectedBody("empty body", new JsonObject(), "scope-key-required"),
                new RejectedBody("missing key", new JsonObject().put("other", true), "scope-key-required"),
                new RejectedBody("null key", body("unused").putNull("scopeKey"), "scope-key-required"),
                new RejectedBody("numeric key", body("unused").put("scopeKey", 42), "scope-key-required"),
                new RejectedBody("boolean key", body("unused").put("scopeKey", true), "scope-key-required"),
                new RejectedBody(
                        "array key",
                        body("unused").put("scopeKey", new JsonArray().add("private-input")),
                        "scope-key-required"),
                new RejectedBody(
                        "object key",
                        body("unused").put("scopeKey", new JsonObject().put("private-input", true)),
                        "scope-key-required"),
                invalidKey("empty key", ""),
                invalidKey("blank key", " \u00a0"),
                invalidKey("leading whitespace", " private-input"),
                invalidKey("trailing whitespace", "private-input\u00a0"),
                invalidKey("control character", "private\ninput"),
                invalidKey("format character", "private\u200dinput"),
                invalidKey("unpaired surrogate", "private\ud800input"),
                invalidKey("too many code points", "x".repeat(256)));
        Logger logger = (Logger) LoggerFactory.getLogger("dev.vertique.context");
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> warnings = new ListAppender<>();
        warnings.start();
        logger.addAppender(warnings);
        logger.setLevel(Level.WARN);
        try {
            for (RejectedBody input : rejected) {
                assertRejected(graph, decoder, input);
            }
            assertEquals(
                    2, warnings.list.size(), "the propagator must log only one warning per stable rejection reason");
            for (ILoggingEvent warning : warnings.list) {
                assertEquals(Level.WARN, warning.getLevel());
                assertNull(warning.getThrowableProxy(), "rejected input must not leak through exception text");
                String message = warning.getFormattedMessage();
                assertTrue(message.contains("key='scope', value='null'"));
                assertTrue(Set.of("scope-key-required", "invalid-scope-key").stream()
                        .anyMatch(reason -> message.contains("reason='" + reason + "'")));
                assertFalse(message.contains("private"));
                assertFalse(message.contains("scopeKey"));
                assertFalse(message.contains("x".repeat(256)));
            }
        } finally {
            logger.setLevel(previousLevel);
            logger.detachAppender(warnings);
            warnings.stop();
        }
    }

    @Test
    @DisplayName("unknown scope body fields do not change the accepted exact key")
    void ignoresUnknownScopeBodyFields() {
        RuntimeGraph graph = DaggerScopeContextDurableMetadataCodecsTest_RuntimeGraph.create();
        DurableContextMetadataDecoder<?> decoder = scopeDecoder(graph);
        DurableMetadata metadata =
                DurableMetadata.of("scope", body("AcMe").put("futureField", new JsonObject().put("nested", true)));
        assertEquals(
                new ScopeContext(new ScopeKey("AcMe")),
                decoder.decode(metadata, new DurableDecodeContext(DispatchBoundary.WORKFLOW))
                        .value()
                        .orElse(null));
    }

    @Test
    @DisplayName("a WORKFLOW positive control precedes excluded capture and decode boundaries")
    void filtersCaptureAndDecodeAtUnsupportedBoundaries(Vertx vertx, VertxTestContext ctx) {
        RuntimeGraph graph = DaggerScopeContextDurableMetadataCodecsTest_RuntimeGraph.create();
        onDuplicate(vertx, ctx, () -> {
            ScopeContext acme = new ScopeContext(new ScopeKey("acme"));
            try (ContextHolder.Scope ambient = graph.holder().bind(ScopeContext.class, acme)) {
                DurableMetadata supported = graph.propagator().capture(DispatchBoundary.WORKFLOW);
                assertEquals(
                        body("acme"),
                        supported.body("scope").orElse(null),
                        "WORKFLOW must activate this graph's encoder");
                assertEquals(
                        acme,
                        graph.propagator()
                                .decodeToDispatchContext(supported, DispatchBoundary.WORKFLOW)
                                .get(ScopeContext.class.getName()),
                        "WORKFLOW must activate this graph's decoder");
                for (String boundary : List.of(
                        DispatchBoundary.KAFKA,
                        DispatchBoundary.OUTBOX_SERVICE,
                        "UNKNOWN",
                        DispatchBoundary.SERVICE_DISPATCH)) {
                    assertFalse(graph.propagator().capture(boundary).has("scope"), boundary);
                }
                for (String boundary : List.of(
                        DispatchBoundary.KAFKA,
                        DispatchBoundary.OUTBOX,
                        "UNKNOWN",
                        DispatchBoundary.SERVICE_DISPATCH)) {
                    for (DurableMetadata supplied :
                            List.of(supported, DurableMetadata.of("scope", body("acme")), DurableMetadata.empty())) {
                        assertEquals(
                                ContextDecodeResult.empty(),
                                scopeDecoder(graph).decode(supplied, new DurableDecodeContext(boundary)),
                                boundary);
                        try (ContextHolder.Scope ingress = graph.propagator().bindFrom(supplied, boundary)) {
                            assertTrue(
                                    graph.holder().current(ScopeContext.class).isEmpty(), boundary);
                        }
                        assertEquals(
                                acme, graph.holder().current(ScopeContext.class).orElseThrow());
                    }
                }
            }
        });
    }

    @Test
    @DisplayName("a caller scope namespace collides only while the scope encoder's type is bound")
    void rejectsCallerNamespaceCollisionWithCapturedScope(Vertx vertx, VertxTestContext ctx) {
        RuntimeGraph graph = DaggerScopeContextDurableMetadataCodecsTest_RuntimeGraph.create();
        onDuplicate(vertx, ctx, () -> {
            DurableMetadata caller = DurableMetadata.of("scope", body("caller"));
            assertEquals(
                    caller.toJson(),
                    graph.propagator()
                            .mergeCaptured(caller, DispatchBoundary.WORKFLOW)
                            .toJson());
            try (ContextHolder.Scope ignored =
                    graph.holder().bind(ScopeContext.class, new ScopeContext(new ScopeKey("acme")))) {
                assertThrows(IllegalStateException.class, () -> graph.propagator()
                        .mergeCaptured(caller, DispatchBoundary.WORKFLOW));
            }
        });
    }

    private static JsonObject body(String key) {
        return new JsonObject().put("scopeKey", key);
    }

    private static DurableContextMetadataDecoder<?> scopeDecoder(RuntimeGraph graph) {
        List<DurableContextMetadataDecoder<?>> decoders = graph.registry().decoders().stream()
                .filter(decoder -> decoder.type().equals(ScopeContext.class))
                .toList();
        assertEquals(
                1,
                decoders.size(),
                "the effective Dagger registry must supply one scope decoder before parser assertions");
        assertEquals("scope", decoders.getFirst().namespace());
        return decoders.getFirst();
    }

    private record RejectedBody(String rule, JsonObject body, String reason) {}

    private static RejectedBody invalidKey(String rule, String key) {
        return new RejectedBody(rule, body(key), "invalid-scope-key");
    }

    private static void assertRejected(
            RuntimeGraph graph, DurableContextMetadataDecoder<?> decoder, RejectedBody input) {
        DurableMetadata metadata = DurableMetadata.of("scope", input.body())
                .with("unrelated", new JsonObject().put("value", "still-decoded"));
        ContextDecodeResult<?> result = decoder.decode(metadata, new DurableDecodeContext(DispatchBoundary.WORKFLOW));
        assertTrue(result.value().isEmpty(), input.rule());
        assertEquals(List.of(new ContextDecodeWarning("scope", null, input.reason())), result.warnings(), input.rule());
        Map<String, Object> dispatched =
                graph.propagator().decodeToDispatchContext(metadata, DispatchBoundary.WORKFLOW);
        assertFalse(dispatched.containsKey(ScopeContext.class.getName()), input.rule());
        assertEquals(new Unrelated("still-decoded"), dispatched.get(Unrelated.class.getName()), input.rule());
    }

    private static void onDuplicate(Vertx vertx, VertxTestContext ctx, Runnable assertion) {
        ContextInternal duplicate = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        duplicate.runOnContext(ignored -> {
            try {
                assertion.run();
                ctx.completeNow();
            } catch (Throwable failure) {
                ctx.failNow(failure);
            }
        });
    }
}
