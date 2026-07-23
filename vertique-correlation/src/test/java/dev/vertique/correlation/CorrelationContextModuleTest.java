// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.InboundContextInitializer;
import dev.vertique.core.context.ServiceDispatchContextDecoder;
import dev.vertique.core.context.ServiceDispatchContextEncoder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdGenerator;
import dev.vertique.core.correlation.TraceReference;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dagger graph smoke test for {@link CorrelationContextModule}.
 *
 * <p>Constructs a minimal {@code @Component} bringing in {@link ContextRuntimeModule} (which
 * supplies the {@link ContextHolder} binding the factory transitively requires) plus
 * {@link CorrelationContextModule} and verifies:
 * <ul>
 *   <li>The graph compiles — {@code CorrelationContextFactory} and
 *       {@code CorrelationContextMutator} resolve via their {@code @Inject} constructors and
 *       {@code @BindsOptionalOf CorrelationIdGenerator} produces an empty Optional by default.</li>
 *   <li>When no application provider is supplied, the factory's effective generator is the
 *       framework default {@link Uuid4CorrelationIdGenerator#INSTANCE}.</li>
 *   <li>When an application-supplied provider is on the graph, the factory's effective
 *       generator is that application binding.</li>
 *   <li>{@code @BindsOptionalOf TraceReferenceResolver} produces an empty Optional by default;
 *       when a concrete binding is supplied it is present.</li>
 * </ul>
 */
class CorrelationContextModuleTest {

    @Test
    @DisplayName("graph resolves CorrelationContextFactory + Mutator with framework-default generator")
    void defaultGraphCompilesAndUsesDefaultGenerator() {
        DefaultGraph component = DaggerCorrelationContextModuleTest_DefaultGraph.create();
        CorrelationContextFactory factory = component.factory();
        CorrelationContextMutator mutator = component.mutator();

        assertNotNull(factory, "factory must be resolvable");
        assertNotNull(mutator, "mutator must be resolvable");
        assertSame(Uuid4CorrelationIdGenerator.INSTANCE, factory.generator());
    }

    @Test
    @DisplayName("app-supplied CorrelationIdGenerator binding wins over the default")
    void overrideGraphUsesAppGenerator() {
        OverrideGraph component = DaggerCorrelationContextModuleTest_OverrideGraph.create();
        assertSame(FixedGenerator.INSTANCE, component.factory().generator());
    }

    @Test
    @DisplayName(
            "graph contributes the CorrelationContext service-dispatch encoder/decoder, durable encoder/decoder, and seeder")
    void pr2ProvidersWiredIntoMultibindings() {
        DefaultGraph c = DaggerCorrelationContextModuleTest_DefaultGraph.create();

        // Service-dispatch encoder/decoder set contains the CorrelationContext pair built via
        // ServiceDispatchCodecs.snapshotEncoder/Decoder.
        assertTrue(
                c.serviceDispatchEncoders().stream().anyMatch(e -> e.type() == CorrelationContext.class),
                "service-dispatch encoder for CorrelationContext must be contributed");
        assertTrue(
                c.serviceDispatchDecoders().stream().anyMatch(d -> d.type() == CorrelationContext.class),
                "service-dispatch decoder for CorrelationContext must be contributed");

        // Durable encoder/decoder set contains the bespoke pair keyed by vertique-correlation.
        assertEquals(
                1L,
                c.durableEncoders().stream()
                        .filter(e -> e.type() == CorrelationContext.class)
                        .count(),
                "exactly one durable encoder for CorrelationContext");
        assertEquals(
                1L,
                c.durableDecoders().stream()
                        .filter(d -> d.type() == CorrelationContext.class)
                        .count(),
                "exactly one durable decoder for CorrelationContext");

        // First-ingress initializer set contains the seeder.
        assertTrue(
                c.inboundInitializers().stream().anyMatch(i -> i instanceof CorrelationContextSeeder),
                "CorrelationContextSeeder must be contributed as an InboundContextInitializer");
    }

    @Test
    @DisplayName("Optional<TraceReferenceResolver> resolves empty when no binding is supplied")
    void traceReferenceResolverAbsentByDefault() {
        DefaultGraph component = DaggerCorrelationContextModuleTest_DefaultGraph.create();
        Optional<TraceReferenceResolver> resolver = component.traceReferenceResolver();
        assertFalse(resolver.isPresent(), "TraceReferenceResolver must be absent by default");
    }

    @Test
    @DisplayName("Optional<TraceReferenceResolver> is present when an application binding is supplied")
    void traceReferenceResolverPresentWhenBound() {
        TraceResolverGraph component = DaggerCorrelationContextModuleTest_TraceResolverGraph.create();
        Optional<TraceReferenceResolver> resolver = component.traceReferenceResolver();
        assertTrue(resolver.isPresent(), "TraceReferenceResolver must be present when a binding is supplied");
        // The fixed resolver returns a known trace.
        Optional<TraceReference> trace = resolver.get().currentTrace();
        assertTrue(trace.isPresent(), "The test resolver must return a non-empty trace");
        assertEquals("trace-test-id", trace.get().traceId());
        assertEquals("span-test-id", trace.get().spanId());
        assertEquals("test", trace.get().source());
    }

    // --- Test graph wiring ---

    @Singleton
    @Component(modules = {ContextRuntimeModule.class, CorrelationContextModule.class})
    interface DefaultGraph {
        CorrelationContextFactory factory();

        CorrelationContextMutator mutator();

        Optional<TraceReferenceResolver> traceReferenceResolver();

        Set<ServiceDispatchContextEncoder<?>> serviceDispatchEncoders();

        Set<ServiceDispatchContextDecoder<?>> serviceDispatchDecoders();

        Set<DurableContextMetadataEncoder<?>> durableEncoders();

        Set<DurableContextMetadataDecoder<?>> durableDecoders();

        Set<InboundContextInitializer> inboundInitializers();
    }

    @Module
    abstract static class OverrideModule {
        @Provides
        @Singleton
        static CorrelationIdGenerator generator() {
            return FixedGenerator.INSTANCE;
        }
    }

    @Singleton
    @Component(modules = {ContextRuntimeModule.class, CorrelationContextModule.class, OverrideModule.class})
    interface OverrideGraph {
        CorrelationContextFactory factory();
    }

    @Module
    abstract static class TraceResolverModule {
        @Provides
        @Singleton
        static TraceReferenceResolver resolver() {
            return () -> Optional.of(new TraceReference("trace-test-id", "span-test-id", "test"));
        }
    }

    @Singleton
    @Component(modules = {ContextRuntimeModule.class, CorrelationContextModule.class, TraceResolverModule.class})
    interface TraceResolverGraph {
        Optional<TraceReferenceResolver> traceReferenceResolver();
    }

    static final class FixedGenerator implements CorrelationIdGenerator {
        static final FixedGenerator INSTANCE = new FixedGenerator();

        private FixedGenerator() {}

        @Override
        public String generate() {
            return "fixed-id";
        }
    }
}
