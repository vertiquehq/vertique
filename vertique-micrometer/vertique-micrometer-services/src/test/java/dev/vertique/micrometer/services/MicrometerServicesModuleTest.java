// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.Result;
import dev.vertique.micrometer.MetricsConfig;
import dev.vertique.micrometer.MicrometerModule;
import dev.vertique.services.interceptor.ServiceDispatchContext;
import dev.vertique.services.interceptor.ServiceInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MicrometerServicesModule}.
 *
 * <p>Verifies two things:
 * <ol>
 *   <li><b>{@code @IntoSet} binding</b>: the module contributes {@link ServiceDispatchMetricsInterceptor}
 *       to the {@code Set<ServiceInterceptor>} multibinding.</li>
 *   <li><b>{@code @BindsOptionalOf MetricsConfig}</b>: the graph resolves when no
 *       {@link MetricsConfig} binding exists (empty optional, interceptor defaults to enabled) and
 *       when one does exist (present optional, interceptor honours {@code enabled=false}).</li>
 * </ol>
 */
class MicrometerServicesModuleTest {

    private static final Instant T0 = Instant.parse("2026-06-13T00:00:00Z");
    private static final Instant T1 = T0.plusMillis(42);

    private static ServiceDispatchContext ctx() {
        return new ServiceDispatchContext(
                "svc/op",
                "integration.svc.op",
                "test",
                "svc",
                "op",
                DispatchEnvelope.empty(),
                false,
                List.of(),
                List.of(),
                Map.of());
    }

    // =========================================================================
    // Test 1 — Set<ServiceInterceptor> contains the metrics interceptor
    // =========================================================================

    @Nested
    @DisplayName("Set<ServiceInterceptor> multibinding")
    class InterceptorMultibinding {

        @Test
        @DisplayName("contains exactly one interceptor which is ServiceDispatchMetricsInterceptor")
        void setContainsMetricsInterceptor() {
            AbsentConfigComponent component = DaggerMicrometerServicesModuleTest_AbsentConfigComponent.create();

            Set<ServiceInterceptor> interceptors = component.serviceInterceptors();

            assertEquals(1, interceptors.size(), "Set must contain exactly one interceptor");
            assertInstanceOf(
                    ServiceDispatchMetricsInterceptor.class,
                    interceptors.iterator().next(),
                    "The interceptor must be a ServiceDispatchMetricsInterceptor");
        }

        @Test
        @DisplayName("also resolves when installed alongside MicrometerModule")
        void setResolvesWithMicrometerModule() {
            MicrometerComponent component = DaggerMicrometerServicesModuleTest_MicrometerComponent.builder()
                    .testConfigModule(new TestConfigModule(new JsonObject()))
                    .build();

            Set<ServiceInterceptor> interceptors = component.serviceInterceptors();

            assertEquals(1, interceptors.size());
            assertInstanceOf(
                    ServiceDispatchMetricsInterceptor.class,
                    interceptors.iterator().next());
        }
    }

    // =========================================================================
    // Test 2 — @BindsOptionalOf MetricsConfig
    // =========================================================================

    @Nested
    @DisplayName("@BindsOptionalOf MetricsConfig")
    class OptionalMetricsConfig {

        @Test
        @DisplayName("graph resolves without MicrometerModule: Optional<MetricsConfig> is empty, interceptor records")
        void optionalIsEmptyWhenNoMetricsConfigBinding() {
            AbsentConfigComponent component = DaggerMicrometerServicesModuleTest_AbsentConfigComponent.create();

            assertTrue(component.metricsConfig().isEmpty(), "Optional<MetricsConfig> must be empty");

            ServiceInterceptor interceptor =
                    component.serviceInterceptors().iterator().next();
            interceptor.onTerminalComplete(ctx(), Result.success("ok"), T0, T1);

            assertEquals(
                    1,
                    component.meterRegistry().getMeters().size(),
                    "absent MetricsConfig must default to enabled and record a timer");
        }

        @Test
        @DisplayName("Optional<MetricsConfig> is present when MicrometerModule supplies it from config")
        void optionalIsPresentWhenMicrometerModuleInstalled() {
            MicrometerComponent component = DaggerMicrometerServicesModuleTest_MicrometerComponent.builder()
                    .testConfigModule(new TestConfigModule(
                            new JsonObject().put("metrics", new JsonObject().put("enabled", false))))
                    .build();

            Optional<MetricsConfig> config = component.metricsConfig();

            assertTrue(config.isPresent(), "Optional<MetricsConfig> must be present");
            assertFalse(config.get().enabled(), "metrics.enabled=false must flow through the optional");
        }

        @Test
        @DisplayName("present MetricsConfig with enabled=false => interceptor records nothing")
        void presentDisabledConfigSilencesInterceptor() {
            PresentConfigComponent component = DaggerMicrometerServicesModuleTest_PresentConfigComponent.create();

            assertTrue(component.metricsConfig().isPresent(), "Optional<MetricsConfig> must be present");

            ServiceInterceptor interceptor =
                    component.serviceInterceptors().iterator().next();
            interceptor.onTerminalComplete(ctx(), Result.success("ok"), T0, T1);

            assertEquals(
                    0,
                    component.meterRegistry().getMeters().size(),
                    "present MetricsConfig(enabled=false) must suppress recording");
        }
    }

    // =========================================================================
    // --- Test Dagger components ---
    // =========================================================================

    /**
     * Wires {@link MicrometerServicesModule} with a test {@link MeterRegistry} and no
     * {@link MetricsConfig} binding, so the {@code @BindsOptionalOf} resolves to empty.
     */
    @Singleton
    @Component(modules = {MicrometerServicesModule.class, TestRegistryModule.class})
    interface AbsentConfigComponent {

        Set<ServiceInterceptor> serviceInterceptors();

        Optional<MetricsConfig> metricsConfig();

        MeterRegistry meterRegistry();
    }

    /**
     * Wires {@link MicrometerServicesModule} with a test {@link MeterRegistry} and a
     * {@link MetricsConfig} with {@code enabled=false}, so the optional resolves to present.
     */
    @Singleton
    @Component(modules = {MicrometerServicesModule.class, TestRegistryModule.class, TestDisabledConfigModule.class})
    interface PresentConfigComponent {

        Set<ServiceInterceptor> serviceInterceptors();

        Optional<MetricsConfig> metricsConfig();

        MeterRegistry meterRegistry();
    }

    /**
     * Wires {@link MicrometerServicesModule} together with {@link MicrometerModule}, which
     * supplies both the {@link MeterRegistry} and the {@link MetricsConfig}.
     */
    @Singleton
    @Component(
            modules = {
                MicrometerModule.class,
                MicrometerServicesModule.class,
                ConfigParsingModule.class,
                TestConfigModule.class
            })
    interface MicrometerComponent {

        Set<ServiceInterceptor> serviceInterceptors();

        Optional<MetricsConfig> metricsConfig();
    }

    // =========================================================================
    // --- Test modules ---
    // =========================================================================

    /** Provides an isolated {@link SimpleMeterRegistry}. */
    @Module
    static final class TestRegistryModule {

        @Provides
        @Singleton
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    /** Provides a {@link MetricsConfig} with {@code enabled=false}. */
    @Module
    static final class TestDisabledConfigModule {

        @Provides
        @Singleton
        MetricsConfig metricsConfig() {
            return MetricsConfig.builder().enabled(false).build();
        }
    }

    /** Provides the {@code @VertxConfig JsonObject} from a caller-supplied value. */
    @Module
    static final class TestConfigModule {

        private final JsonObject config;

        TestConfigModule(JsonObject config) {
            this.config = config;
        }

        @Provides
        @Singleton
        @VertxConfig
        JsonObject vertxConfig() {
            return config;
        }
    }
}
