// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dagger.multibindings.IntoSet;
import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.DurablePropagationMetadata;
import dev.vertique.core.scope.ScopeContext;
import dev.vertique.core.scope.ScopeKey;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Verifies conditional defaults without relying on any concrete built-in codec class. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ContextRuntimeModuleDurableScopeTest {

    record Other(String value) implements ContextValue {}

    record Second(String value) implements ContextValue {}

    private record Encoder<T extends ContextValue>(Class<T> type, String namespace)
            implements DurableContextMetadataEncoder<T> {
        @Override
        public DurableMetadata encode(T value, DurableEncodeContext context) {
            return DurableMetadata.of(namespace, new JsonObject().put("value", value.toString()));
        }
    }

    private record Decoder<T extends ContextValue>(Class<T> type, String namespace)
            implements DurableContextMetadataDecoder<T> {
        @Override
        public ContextDecodeResult<T> decode(DurableMetadata metadata, DurableDecodeContext context) {
            return ContextDecodeResult.empty();
        }
    }

    interface RuntimeGraph {
        DurableContextMetadataRegistry registry();

        Set<DurableContextMetadataEncoder<?>> rawEncoders();

        Set<DurableContextMetadataDecoder<?>> rawDecoders();

        ContextHolder holder();
    }

    @Singleton
    @Component(modules = ContextRuntimeModule.class)
    interface DefaultGraph extends RuntimeGraph {}

    private static final Encoder<ScopeContext> SCOPE_ENCODER = new Encoder<>(ScopeContext.class, "scope");
    private static final Encoder<ScopeContext> TYPE_ENCODER = new Encoder<>(ScopeContext.class, "custom-scope");
    private static final Encoder<Other> NAMESPACE_ENCODER = new Encoder<>(Other.class, "scope");
    private static final Decoder<ScopeContext> SCOPE_DECODER = new Decoder<>(ScopeContext.class, "scope");
    private static final Decoder<ScopeContext> TYPE_DECODER = new Decoder<>(ScopeContext.class, "custom-scope");
    private static final Decoder<Other> NAMESPACE_DECODER = new Decoder<>(Other.class, "scope");

    @Module
    static class ScopeEncoderClaim {
        @Provides
        @IntoSet
        static DurableContextMetadataEncoder<?> contribution() {
            return SCOPE_ENCODER;
        }
    }

    @Singleton
    @Component(modules = {ContextRuntimeModule.class, ScopeEncoderClaim.class})
    interface ScopeEncoderGraph extends RuntimeGraph {}

    @Module
    static class TypeEncoderClaim {
        @Provides
        @IntoSet
        static DurableContextMetadataEncoder<?> contribution() {
            return TYPE_ENCODER;
        }
    }

    @Singleton
    @Component(modules = {ContextRuntimeModule.class, TypeEncoderClaim.class})
    interface TypeEncoderGraph extends RuntimeGraph {}

    @Module
    static class NamespaceEncoderClaim {
        @Provides
        @IntoSet
        static DurableContextMetadataEncoder<?> contribution() {
            return NAMESPACE_ENCODER;
        }
    }

    @Singleton
    @Component(modules = {ContextRuntimeModule.class, NamespaceEncoderClaim.class})
    interface NamespaceEncoderGraph extends RuntimeGraph {}

    @Module
    static class ScopeDecoderClaim {
        @Provides
        @IntoSet
        static DurableContextMetadataDecoder<?> contribution() {
            return SCOPE_DECODER;
        }
    }

    @Singleton
    @Component(modules = {ContextRuntimeModule.class, ScopeDecoderClaim.class})
    interface ScopeDecoderGraph extends RuntimeGraph {}

    @Module
    static class TypeDecoderClaim {
        @Provides
        @IntoSet
        static DurableContextMetadataDecoder<?> contribution() {
            return TYPE_DECODER;
        }
    }

    @Singleton
    @Component(modules = {ContextRuntimeModule.class, TypeDecoderClaim.class})
    interface TypeDecoderGraph extends RuntimeGraph {}

    @Module
    static class NamespaceDecoderClaim {
        @Provides
        @IntoSet
        static DurableContextMetadataDecoder<?> contribution() {
            return NAMESPACE_DECODER;
        }
    }

    @Singleton
    @Component(modules = {ContextRuntimeModule.class, NamespaceDecoderClaim.class})
    interface NamespaceDecoderGraph extends RuntimeGraph {}

    record Contributions(
            Set<DurableContextMetadataEncoder<?>> encoders, Set<DurableContextMetadataDecoder<?>> decoders) {}

    @Module
    static class ContributionsModule {
        @Provides
        @ElementsIntoSet
        static Set<DurableContextMetadataEncoder<?>> encoders(Contributions supplied) {
            return supplied.encoders();
        }

        @Provides
        @ElementsIntoSet
        static Set<DurableContextMetadataDecoder<?>> decoders(Contributions supplied) {
            return supplied.decoders();
        }
    }

    @Singleton
    @Component(modules = {ContextRuntimeModule.class, ContributionsModule.class})
    interface ContributedGraph extends RuntimeGraph {
        @Component.Factory
        interface Factory {
            ContributedGraph create(@BindsInstance Contributions contributions);
        }
    }

    @Test
    @DisplayName("an unclaimed runtime adds exactly one effective pair while raw contributions stay empty")
    void addsExactlyOnePairWithoutACompetingContribution() {
        DefaultGraph graph = DaggerContextRuntimeModuleDurableScopeTest_DefaultGraph.create();
        DurableContextMetadataRegistry registry = graph.registry();
        assertEquals(1, registry.encoders().size(), "the normal graph must expose one built-in encoder");
        assertEquals(1, registry.decoders().size(), "the normal graph must expose one built-in decoder");
        DurableContextMetadataEncoder<?> encoder =
                registry.encoders().iterator().next();
        DurableContextMetadataDecoder<?> decoder =
                registry.decoders().iterator().next();
        assertEquals(ScopeContext.class, encoder.type());
        assertEquals("scope", encoder.namespace());
        assertEquals(ScopeContext.class, decoder.type());
        assertEquals("scope", decoder.namespace());
        assertEquals(Set.of(encoder), Set.copyOf(registry.encoders()));
        assertEquals(Set.of(decoder), Set.copyOf(registry.decoders()));
        assertEquals(Set.of(ScopeContext.class), registry.registeredTypes());
        assertEquals(Set.of(), graph.rawEncoders());
        assertEquals(Set.of(), graph.rawDecoders());
        assertSame(registry, graph.registry(), "the effective registry retains its singleton lifecycle");
    }

    @Test
    @DisplayName("each one-sided type or namespace claim suppresses both defaults and preserves exact contributions")
    void suppressesBothDefaultsForEveryTypeOrNamespaceClaim() {
        assertAll(
                () -> assertExact(
                        DaggerContextRuntimeModuleDurableScopeTest_ScopeEncoderGraph.create(),
                        Set.of(SCOPE_ENCODER),
                        Set.of()),
                () -> assertExact(
                        DaggerContextRuntimeModuleDurableScopeTest_TypeEncoderGraph.create(),
                        Set.of(TYPE_ENCODER),
                        Set.of()),
                () -> assertExact(
                        DaggerContextRuntimeModuleDurableScopeTest_NamespaceEncoderGraph.create(),
                        Set.of(NAMESPACE_ENCODER),
                        Set.of()),
                () -> assertExact(
                        DaggerContextRuntimeModuleDurableScopeTest_ScopeDecoderGraph.create(),
                        Set.of(),
                        Set.of(SCOPE_DECODER)),
                () -> assertExact(
                        DaggerContextRuntimeModuleDurableScopeTest_TypeDecoderGraph.create(),
                        Set.of(),
                        Set.of(TYPE_DECODER)),
                () -> assertExact(
                        DaggerContextRuntimeModuleDurableScopeTest_NamespaceDecoderGraph.create(),
                        Set.of(),
                        Set.of(NAMESPACE_DECODER)),
                () -> assertExact(
                        contributed(Set.of(TYPE_ENCODER), Set.of(NAMESPACE_DECODER)),
                        Set.of(TYPE_ENCODER),
                        Set.of(NAMESPACE_DECODER)));
    }

    @Test
    @DisplayName("unrelated codecs coexist unchanged with the single default pair")
    void preservesUnrelatedContributionsAlongsideDefaultPair() {
        Encoder<Other> encoder = new Encoder<>(Other.class, "other-encode");
        Decoder<Second> decoder = new Decoder<>(Second.class, "other-decode");
        ContributedGraph graph = contributed(Set.of(encoder), Set.of(decoder));
        DurableContextMetadataRegistry registry = graph.registry();
        assertEquals(2, registry.encoders().size(), "the unrelated encoder must coexist with the default");
        assertEquals(2, registry.decoders().size(), "the unrelated decoder must coexist with the default");
        DurableContextMetadataEncoder<?> scopeEncoder = registry.encoders().stream()
                .filter(value -> value.type().equals(ScopeContext.class))
                .findFirst()
                .orElseThrow();
        DurableContextMetadataDecoder<?> scopeDecoder = registry.decoders().stream()
                .filter(value -> value.type().equals(ScopeContext.class))
                .findFirst()
                .orElseThrow();
        assertEquals("scope", scopeEncoder.namespace());
        assertEquals("scope", scopeDecoder.namespace());
        assertEquals(Set.of(encoder, scopeEncoder), Set.copyOf(registry.encoders()));
        assertEquals(Set.of(decoder, scopeDecoder), Set.copyOf(registry.decoders()));
        assertSame(
                encoder,
                registry.encoders().stream()
                        .filter(value -> value.type().equals(Other.class))
                        .findFirst()
                        .orElseThrow());
        assertSame(
                decoder,
                registry.decoders().stream()
                        .filter(value -> value.type().equals(Second.class))
                        .findFirst()
                        .orElseThrow());
        assertEquals(Set.of(encoder), graph.rawEncoders());
        assertEquals(Set.of(decoder), graph.rawDecoders());
    }

    @Test
    @DisplayName("duplicate caller types and namespaces still fail before selecting defaults")
    void preservesDuplicateValidationForCallerContributions() {
        assertAll(
                () -> assertInvalid(Set.of(SCOPE_ENCODER, TYPE_ENCODER), Set.of(), "Duplicate durable encoder type"),
                () -> assertInvalid(
                        Set.of(SCOPE_ENCODER, NAMESPACE_ENCODER), Set.of(), "Duplicate durable encoder namespace"),
                () -> assertInvalid(Set.of(), Set.of(SCOPE_DECODER, TYPE_DECODER), "Duplicate durable decoder type"),
                () -> assertInvalid(
                        Set.of(), Set.of(SCOPE_DECODER, NAMESPACE_DECODER), "Duplicate durable decoder namespace"),
                () -> assertInvalid(
                        Set.of(new Encoder<>(Other.class, "one"), new Encoder<>(Other.class, "two")),
                        Set.of(SCOPE_DECODER),
                        "Duplicate durable encoder type"),
                () -> assertInvalid(
                        Set.of(SCOPE_ENCODER),
                        Set.of(new Decoder<>(Other.class, "shared"), new Decoder<>(Second.class, "shared")),
                        "Duplicate durable decoder namespace"));
    }

    @Test
    @DisplayName("direct registries and the testing envelope builder keep supplied-set semantics")
    void keepsDirectRegistryAndTestingBuilderSuppliedSetSemantics(Vertx vertx, VertxTestContext ctx) {
        DurableContextMetadataRegistry empty = new DurableContextMetadataRegistry(Set.of(), Set.of());
        assertEquals(Set.of(), Set.copyOf(empty.encoders()));
        assertEquals(Set.of(), Set.copyOf(empty.decoders()));
        assertEquals(Set.of(), empty.registeredTypes());
        DurableContextMetadataRegistry supplied =
                new DurableContextMetadataRegistry(Set.of(TYPE_ENCODER), Set.of(NAMESPACE_DECODER));
        assertEquals(Set.of(TYPE_ENCODER), Set.copyOf(supplied.encoders()));
        assertEquals(Set.of(NAMESPACE_DECODER), Set.copyOf(supplied.decoders()));
        DefaultGraph graph = DaggerContextRuntimeModuleDurableScopeTest_DefaultGraph.create();
        ContextInternal duplicate = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        duplicate.runOnContext(ignored -> {
            try (ContextHolder.Scope scope =
                            graph.holder().bind(ScopeContext.class, new ScopeContext(new ScopeKey("acme")));
                    ContextHolder.Scope raw = graph.holder()
                            .bind(
                                    DurablePropagationMetadata.class,
                                    new DurablePropagationMetadata(
                                            DispatchBoundary.WORKFLOW,
                                            DurableMetadata.of(
                                                    "scope",
                                                    new JsonObject()
                                                            .put("schemaVersion", 1)
                                                            .put("scopeKey", "acme"))))) {
                Map<String, Object> caller = Map.of(Other.class.getName(), new Other("caller"));
                assertEquals(
                        caller,
                        DispatchEnvelopeBuilder.forTesting()
                                .build("payload", caller, DispatchBoundary.SERVICE_DISPATCH)
                                .metadata()
                                .dispatchContext());
            } catch (Throwable failure) {
                ctx.failNow(failure);
                return;
            }
            ctx.completeNow();
        });
    }

    private static ContributedGraph contributed(
            Set<DurableContextMetadataEncoder<?>> encoders, Set<DurableContextMetadataDecoder<?>> decoders) {
        return DaggerContextRuntimeModuleDurableScopeTest_ContributedGraph.factory()
                .create(new Contributions(encoders, decoders));
    }

    private static void assertExact(
            RuntimeGraph graph,
            Set<DurableContextMetadataEncoder<?>> encoders,
            Set<DurableContextMetadataDecoder<?>> decoders) {
        assertAll(
                () -> assertEquals(encoders, Set.copyOf(graph.registry().encoders()), "effective encoder set"),
                () -> assertEquals(decoders, Set.copyOf(graph.registry().decoders()), "effective decoder set"),
                () -> assertEquals(encoders, graph.rawEncoders(), "raw encoder contributions"),
                () -> assertEquals(decoders, graph.rawDecoders(), "raw decoder contributions"));
    }

    private static void assertInvalid(
            Set<DurableContextMetadataEncoder<?>> encoders,
            Set<DurableContextMetadataDecoder<?>> decoders,
            String message) {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> contributed(encoders, decoders)
                .registry());
        assertTrue(failure.getMessage().contains(message), failure.getMessage());
    }
}
