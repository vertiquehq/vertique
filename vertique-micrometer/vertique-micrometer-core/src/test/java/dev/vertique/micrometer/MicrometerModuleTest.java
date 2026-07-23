// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import static org.junit.jupiter.api.Assertions.*;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.security.events.SecurityEventObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Dagger graph smoke test for {@link MicrometerModule}.
 *
 * <p>Builds a minimal test component per scenario to verify:
 * <ol>
 *   <li>The {@link MeterRegistry} binding is the exact {@link MeterRegistryHolder#registry()} instance.</li>
 *   <li>Empty config deserialization yields default {@link MetricsConfig} (enabled=true, cardinality 200).</li>
 *   <li>Disabled config ({@code {"metrics":{"enabled":false}}}) yields {@link MetricsConfig#enabled()} == false.</li>
 *   <li>The {@code Set<SecurityEventObserver>} contains exactly one entry, which is a
 *       {@link SecurityMetricsObserver}.</li>
 * </ol>
 */
class MicrometerModuleTest {

    // =========================================================================
    // Test 7 — MeterRegistry binding is the holder registry instance
    // =========================================================================

    @Nested
    @DisplayName("MeterRegistry binding is MeterRegistryHolder.registry() same reference")
    class MeterRegistryBinding {

        @Test
        @DisplayName("injected MeterRegistry is same reference as MeterRegistryHolder.registry()")
        void registryIsSameAsHolder() {
            TestComponent component = DaggerMicrometerModuleTest_TestComponent.builder()
                    .testConfigModule(new TestConfigModule(new JsonObject()))
                    .build();

            MeterRegistry injected = component.meterRegistry();
            assertSame(
                    MeterRegistryHolder.registry(), injected, "injected registry must be the holder registry instance");
        }
    }

    // =========================================================================
    // Test 8 — MetricsConfig deserialization
    // =========================================================================

    @Nested
    @DisplayName("MetricsConfig deserialization")
    class ConfigBinding {

        @Test
        @DisplayName("empty config yields MetricsConfig defaults: enabled=true, cardinality=200")
        void emptyConfigYieldsDefaults() {
            TestComponent component = DaggerMicrometerModuleTest_TestComponent.builder()
                    .testConfigModule(new TestConfigModule(new JsonObject()))
                    .build();

            MetricsConfig config = component.metricsConfig();
            assertTrue(config.enabled(), "default enabled must be true");
            assertEquals(200, config.cardinality().maxTagValuesPerKey(), "default cardinality must be 200");
        }

        @Test
        @DisplayName("{\"metrics\":{\"enabled\":false}} yields MetricsConfig.enabled()=false")
        void disabledConfigYieldsFalse() {
            JsonObject appConfig = new JsonObject().put("metrics", new JsonObject().put("enabled", false));
            TestComponent component = DaggerMicrometerModuleTest_TestComponent.builder()
                    .testConfigModule(new TestConfigModule(appConfig))
                    .build();

            assertFalse(
                    component.metricsConfig().enabled(),
                    "MetricsConfig.enabled() must be false when metrics.enabled=false");
        }
    }

    // =========================================================================
    // Test 9 — SecurityEventObserver multibinding contains one SecurityMetricsObserver
    // =========================================================================

    @Nested
    @DisplayName("SecurityEventObserver multibinding")
    class SecurityObserverMultibinding {

        @Test
        @DisplayName("Set<SecurityEventObserver> contains exactly one SecurityMetricsObserver")
        void setContainsSecurityMetricsObserver() {
            TestComponent component = DaggerMicrometerModuleTest_TestComponent.builder()
                    .testConfigModule(new TestConfigModule(new JsonObject()))
                    .build();

            Set<SecurityEventObserver> observers = component.securityEventObservers();
            assertEquals(1, observers.size(), "Set must contain exactly one SecurityEventObserver");
            assertInstanceOf(
                    SecurityMetricsObserver.class,
                    observers.iterator().next(),
                    "The observer must be a SecurityMetricsObserver");
        }
    }

    // =========================================================================
    // --- Test Dagger component ---
    // =========================================================================

    /**
     * Minimal Dagger component that exercises {@link MicrometerModule} wiring.
     *
     * <p>Combines {@link MicrometerModule} with a {@link TestConfigModule} that provides the
     * {@code @VertxConfig JsonObject} from a test-supplied value.
     */
    @Singleton
    @Component(modules = {MicrometerModule.class, ConfigParsingModule.class, TestConfigModule.class})
    interface TestComponent {

        /**
         * Returns the {@link MeterRegistry} provided by {@link MicrometerModule}.
         *
         * @return the meter registry; non-null when graph resolves
         */
        MeterRegistry meterRegistry();

        /**
         * Returns the deserialized {@link MetricsConfig}.
         *
         * @return the metrics configuration; non-null when graph resolves
         */
        MetricsConfig metricsConfig();

        /**
         * Returns the multibinding set of {@link SecurityEventObserver}s contributed by
         * {@link MicrometerModule}.
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
