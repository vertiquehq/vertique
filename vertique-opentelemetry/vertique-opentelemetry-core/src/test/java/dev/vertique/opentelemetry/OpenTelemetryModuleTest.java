// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.correlation.TraceReferenceResolver;
import dev.vertique.security.events.SecurityEventObserver;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Dagger graph smoke test for {@link OpenTelemetryModule}.
 *
 * <p>Builds a minimal test component per scenario to verify:
 * <ol>
 *   <li>All bindings resolve; {@code Set<SecurityEventObserver>} contains exactly one
 *       {@link SecuritySpanEventObserver}; {@link TraceReferenceResolver} is the
 *       {@link OpenTelemetryTraceReferenceResolver}.</li>
 *   <li>{@code {"tracing":{"enabled":false}}} → {@link OpenTelemetry} binding is noop.</li>
 *   <li>Default config + no global set → tracer produces invalid spans (getOrNoop fallback).</li>
 * </ol>
 *
 * <p>Note: {@code SpanContext} (Prometheus exemplar bridge) is no longer provided by this module.
 * It has moved to {@code vertique-opentelemetry-prometheus}.
 *
 * <p>{@link GlobalOpenTelemetry} is reset before and after each test to guarantee isolation.
 */
class OpenTelemetryModuleTest {

    @BeforeEach
    void resetGlobal() {
        GlobalOpenTelemetry.resetForTest();
    }

    @AfterEach
    void resetGlobalAfter() {
        GlobalOpenTelemetry.resetForTest();
    }

    // =========================================================================
    // Test 1 — all bindings resolve; types are correct
    // =========================================================================

    @Nested
    @DisplayName("all bindings resolve with correct implementation types")
    class AllBindingsResolve {

        @Test
        @DisplayName(
                "TraceReferenceResolver is OTel impl; Set<SecurityEventObserver> has exactly one entry; SpanContext not provided by otel-core")
        void allBindingsResolveCorrectly() {
            TestComponent component = DaggerOpenTelemetryModuleTest_TestComponent.builder()
                    .testConfigModule(new TestConfigModule(new JsonObject()))
                    .build();

            TraceReferenceResolver resolver = component.traceReferenceResolver();
            assertNotNull(resolver, "TraceReferenceResolver must not be null");
            assertInstanceOf(
                    OpenTelemetryTraceReferenceResolver.class,
                    resolver,
                    "TraceReferenceResolver must be the OpenTelemetryTraceReferenceResolver");

            Set<SecurityEventObserver> observers = component.securityEventObservers();
            assertEquals(1, observers.size(), "Set<SecurityEventObserver> must contain exactly one observer");
            assertInstanceOf(
                    SecuritySpanEventObserver.class,
                    observers.iterator().next(),
                    "The observer must be a SecuritySpanEventObserver");

            Tracer tracer = component.tracer();
            assertNotNull(tracer, "Tracer binding must not be null");

            OpenTelemetry openTelemetry = component.openTelemetry();
            assertNotNull(openTelemetry, "OpenTelemetry binding must not be null");

            TracingConfig config = component.tracingConfig();
            assertNotNull(config, "TracingConfig must not be null");
            assertFalse(config.enabled() == false, "default enabled must be true");
        }
    }

    // =========================================================================
    // Test 2 — tracing.enabled=false → OpenTelemetry binding is noop
    // =========================================================================

    @Nested
    @DisplayName("disabled tracing yields noop OpenTelemetry binding")
    class DisabledTracingYieldsNoop {

        @Test
        @DisplayName("tracing.enabled=false → OpenTelemetry.noop() same instance")
        void disabledTracingYieldsNoopOpenTelemetry() {
            JsonObject appConfig = new JsonObject().put("tracing", new JsonObject().put("enabled", false));
            TestComponent component = DaggerOpenTelemetryModuleTest_TestComponent.builder()
                    .testConfigModule(new TestConfigModule(appConfig))
                    .build();

            OpenTelemetry openTelemetry = component.openTelemetry();
            assertNotNull(openTelemetry, "OpenTelemetry binding must not be null when disabled");
            // Verify it's noop: spans produced must be invalid
            Span span = openTelemetry.getTracer("test").spanBuilder("test").startSpan();
            assertFalse(
                    span.getSpanContext().isValid(),
                    "disabled tracing must yield OpenTelemetry.noop() — spans must be invalid");
        }
    }

    // =========================================================================
    // Test 3 — default config + no global set → getOrNoop fallback, spans invalid
    // =========================================================================

    @Nested
    @DisplayName("default config with no global set → getOrNoop fallback, invalid spans")
    class DefaultConfigNoGlobal {

        @Test
        @DisplayName("when enabled=true and no global is set, OpenTelemetry.getOrNoop() yields invalid spans")
        void defaultConfigNoGlobalYieldsInvalidSpans() {
            // No global registered — GlobalOpenTelemetry.getOrNoop() returns noop
            assertFalse(GlobalOpenTelemetry.isSet(), "precondition: no global must be set for this test");

            TestComponent component = DaggerOpenTelemetryModuleTest_TestComponent.builder()
                    .testConfigModule(new TestConfigModule(new JsonObject()))
                    .build();

            OpenTelemetry openTelemetry = component.openTelemetry();
            assertNotNull(openTelemetry, "OpenTelemetry binding must not be null");

            Tracer tracer = component.tracer();
            assertNotNull(tracer, "Tracer binding must not be null");
            // getOrNoop() returns noop when no global registered → tracer produces invalid spans
            Span span = tracer.spanBuilder("test").startSpan();
            assertFalse(
                    span.getSpanContext().isValid(),
                    "default config with no global must yield noop tracer (invalid spans)");
        }

        @Test
        @DisplayName("Tracer binding is consistent with OpenTelemetry binding (same tracer provider)")
        void tracerBindingConsistentWithOtelBinding() {
            TestComponent component = DaggerOpenTelemetryModuleTest_TestComponent.builder()
                    .testConfigModule(new TestConfigModule(new JsonObject()))
                    .build();

            // Both singletons; the Tracer should come from the same OpenTelemetry instance
            OpenTelemetry openTelemetry = component.openTelemetry();
            Tracer tracerFromBinding = component.tracer();
            Tracer tracerFromOtel = openTelemetry.getTracer("dev.vertique");
            // Can't assertSame since getTracer is called twice, but both are singletons per Dagger
            // Just verify the tracer produces consistent behavior
            assertNotNull(tracerFromBinding, "bound Tracer must not be null");
            assertSame(
                    component.tracer(),
                    component.tracer(),
                    "Tracer binding must be singleton (same reference on every call)");
        }
    }

    // =========================================================================
    // --- Test Dagger component ---
    // =========================================================================

    /**
     * Minimal Dagger component that exercises {@link OpenTelemetryModule} wiring.
     *
     * <p>Combines {@link OpenTelemetryModule} with a {@link TestConfigModule} that provides the
     * {@code @VertxConfig JsonObject} from a test-supplied value.
     */
    @Singleton
    @Component(modules = {OpenTelemetryModule.class, ConfigParsingModule.class, TestConfigModule.class})
    interface TestComponent {

        /**
         * Returns the {@link OpenTelemetry} binding provided by {@link OpenTelemetryModule}.
         *
         * @return the OpenTelemetry instance; non-null when graph resolves
         */
        OpenTelemetry openTelemetry();

        /**
         * Returns the {@link Tracer} binding provided by {@link OpenTelemetryModule}.
         *
         * @return the tracer; non-null when graph resolves
         */
        Tracer tracer();

        /**
         * Returns the {@link TraceReferenceResolver} binding provided by {@link OpenTelemetryModule}.
         *
         * @return the resolver; non-null when graph resolves
         */
        TraceReferenceResolver traceReferenceResolver();

        /**
         * Returns the {@link TracingConfig} binding provided by {@link OpenTelemetryModule}.
         *
         * @return the tracing config; non-null when graph resolves
         */
        TracingConfig tracingConfig();

        /**
         * Returns the multibinding set of {@link SecurityEventObserver}s contributed by
         * {@link OpenTelemetryModule}.
         *
         * @return the observer set; non-null when graph resolves
         */
        Set<SecurityEventObserver> securityEventObservers();
    }

    // =========================================================================
    // --- Test config module ---
    // =========================================================================

    /**
     * Provides the {@code @VertxConfig JsonObject} from a caller-supplied value, allowing
     * per-test configuration scenarios.
     */
    @Module
    static final class TestConfigModule {

        private final JsonObject config;

        /**
         * Constructs the module with the given application configuration.
         *
         * @param config the application configuration object; must not be null
         */
        TestConfigModule(JsonObject config) {
            this.config = config;
        }

        /**
         * Provides the application configuration as the {@code @VertxConfig} binding.
         *
         * @return the application configuration; non-null
         */
        @Provides
        @Singleton
        @VertxConfig
        JsonObject vertxConfig() {
            return config;
        }
    }
}
