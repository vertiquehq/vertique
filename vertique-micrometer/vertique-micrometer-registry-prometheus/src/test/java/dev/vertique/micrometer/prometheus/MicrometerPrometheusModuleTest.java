// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.prometheus;

import static org.junit.jupiter.api.Assertions.*;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.core.VertxConfig;
import dev.vertique.management.ManagementEndpointContributor;
import io.prometheus.metrics.tracer.common.SpanContext;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Dagger graph smoke test for {@link MicrometerPrometheusModule}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>{@code Optional<SpanContext>} is empty when no binding is provided.</li>
 *   <li>{@code Optional<SpanContext>} is present when a test binding is provided.</li>
 *   <li>{@link PrometheusScrapeConfig} defaults are resolved from empty config.</li>
 *   <li>{@code Set<ManagementEndpointContributor>} contains exactly the
 *       {@link PrometheusScrapeEndpoint}.</li>
 * </ul>
 */
class MicrometerPrometheusModuleTest {

    @AfterEach
    void clearBackend() {
        PrometheusBackend.clear();
    }

    // --- Optional<SpanContext> empty by default ---

    @Nested
    @DisplayName("Optional<SpanContext> is empty without a binding")
    class NoSpanContext {

        @Test
        @DisplayName("Optional<SpanContext> is empty when no SpanContext binding provided")
        void spanContextEmptyByDefault() {
            TestComponent component = DaggerMicrometerPrometheusModuleTest_TestComponent.builder()
                    .testConfigModule(new TestConfigModule(new JsonObject()))
                    .testVertxModule(new TestVertxModule())
                    .build();

            Optional<SpanContext> opt = component.spanContext();
            assertFalse(opt.isPresent(), "SpanContext must be empty when no binding is provided");
        }
    }

    // --- Optional<SpanContext> present with a test binding ---

    @Nested
    @DisplayName("Optional<SpanContext> is present with a binding")
    class WithSpanContext {

        @Test
        @DisplayName("Optional<SpanContext> is present when a SpanContext @Provides is added")
        void spanContextPresentWithBinding() {
            TestComponentWithSpanContext component =
                    DaggerMicrometerPrometheusModuleTest_TestComponentWithSpanContext.builder()
                            .testConfigModule(new TestConfigModule(new JsonObject()))
                            .testVertxModule(new TestVertxModule())
                            .testSpanContextModule(new TestSpanContextModule())
                            .build();

            Optional<SpanContext> opt = component.spanContext();
            assertTrue(opt.isPresent(), "SpanContext must be present when a binding is added");
        }
    }

    // --- PrometheusScrapeConfig defaults ---

    @Nested
    @DisplayName("PrometheusScrapeConfig from empty config")
    class ScrapeConfigDefaults {

        @Test
        @DisplayName("empty config yields path='/metrics' and exemplarsEnabled=false")
        void defaultConfig() {
            TestComponent component = DaggerMicrometerPrometheusModuleTest_TestComponent.builder()
                    .testConfigModule(new TestConfigModule(new JsonObject()))
                    .testVertxModule(new TestVertxModule())
                    .build();

            PrometheusScrapeConfig config = component.scrapeConfig();
            assertEquals("/metrics", config.path());
            assertFalse(config.exemplarsEnabled());
        }
    }

    // --- Set<ManagementEndpointContributor> contains the endpoint ---

    @Nested
    @DisplayName("Set<ManagementEndpointContributor> contains PrometheusScrapeEndpoint")
    class EndpointContributors {

        @Test
        @DisplayName("Set contains exactly one ManagementEndpointContributor which is PrometheusScrapeEndpoint")
        void setContainsEndpoint() {
            TestComponent component = DaggerMicrometerPrometheusModuleTest_TestComponent.builder()
                    .testConfigModule(new TestConfigModule(new JsonObject()))
                    .testVertxModule(new TestVertxModule())
                    .build();

            Set<ManagementEndpointContributor> contributors = component.endpointContributors();
            assertEquals(1, contributors.size(), "Set must contain exactly one contributor");
            assertInstanceOf(
                    PrometheusScrapeEndpoint.class,
                    contributors.iterator().next(),
                    "The contributor must be a PrometheusScrapeEndpoint");
        }
    }

    // =========================================================================
    // --- Dagger test components ---
    // =========================================================================

    /**
     * Minimal test component that wires {@link MicrometerPrometheusModule} with a test config.
     */
    @Singleton
    @Component(modules = {MicrometerPrometheusModule.class, TestConfigModule.class, TestVertxModule.class})
    interface TestComponent {

        /**
         * Returns the optional {@link SpanContext} binding.
         *
         * @return the optional; non-null
         */
        Optional<SpanContext> spanContext();

        /**
         * Returns the resolved {@link PrometheusScrapeConfig}.
         *
         * @return the config; non-null
         */
        PrometheusScrapeConfig scrapeConfig();

        /**
         * Returns the multibinding set of {@link ManagementEndpointContributor}.
         *
         * @return the set; non-null
         */
        Set<ManagementEndpointContributor> endpointContributors();
    }

    /**
     * Test component that also provides a {@link SpanContext} binding to verify the optional is
     * populated.
     */
    @Singleton
    @Component(
            modules = {
                MicrometerPrometheusModule.class,
                TestConfigModule.class,
                TestVertxModule.class,
                TestSpanContextModule.class
            })
    interface TestComponentWithSpanContext {

        /**
         * Returns the optional {@link SpanContext} binding.
         *
         * @return the optional; non-null
         */
        Optional<SpanContext> spanContext();
    }

    // =========================================================================
    // --- Test modules ---
    // =========================================================================

    /**
     * Provides the {@code @VertxConfig JsonObject} from a caller-supplied value.
     */
    @Module
    static final class TestConfigModule {

        private final JsonObject config;

        /**
         * Constructs the module with the given application configuration.
         *
         * @param config the application configuration; must not be null
         */
        TestConfigModule(JsonObject config) {
            this.config = config;
        }

        /**
         * Provides the application configuration as the {@code @VertxConfig} binding.
         *
         * @return the configuration; non-null
         */
        @Provides
        @Singleton
        @VertxConfig
        JsonObject vertxConfig() {
            return config;
        }
    }

    /**
     * Provides a real {@link Vertx} instance for the Dagger graph.
     */
    @Module
    static final class TestVertxModule {

        /**
         * Provides a real {@link Vertx} instance.
         *
         * @return a new Vertx instance; never null
         */
        @Provides
        @Singleton
        Vertx vertx() {
            return Vertx.vertx();
        }
    }

    /**
     * Provides a fake {@link SpanContext} to test the optional binding.
     */
    @Module
    static final class TestSpanContextModule {

        /**
         * Provides a stub {@link SpanContext}.
         *
         * @return a no-op span context; never null
         */
        @Provides
        @Singleton
        SpanContext spanContext() {
            return new SpanContext() {
                @Override
                public String getCurrentTraceId() {
                    return null;
                }

                @Override
                public String getCurrentSpanId() {
                    return null;
                }

                @Override
                public boolean isCurrentSpanSampled() {
                    return false;
                }

                @Override
                public void markCurrentSpanAsExemplar() {}
            };
        }
    }
}
