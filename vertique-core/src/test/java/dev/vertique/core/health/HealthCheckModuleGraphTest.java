// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.health;

import static org.junit.jupiter.api.Assertions.*;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link HealthCheckModule} is self-contained: a Dagger component whose only module is
 * {@code HealthCheckModule} resolves both qualified check sets, empty, and a
 * {@link HealthCheckAggregator} with the default timeout, so anything in the process can inject the
 * aggregator together with the set it wants to evaluate.
 *
 * <p>The component is unscoped on purpose: the aggregator binding must not force a scope on the
 * components that include the module.
 */
class HealthCheckModuleGraphTest {

    @Test
    @DisplayName("both qualified sets resolve empty when nothing contributes")
    void setsResolveEmpty() {
        HealthGraphComponent component = DaggerHealthCheckModuleGraphTest_HealthGraphComponent.create();

        assertTrue(component.livenessChecks().isEmpty(), "liveness set must be empty by default");
        assertTrue(component.readinessChecks().isEmpty(), "readiness set must be empty by default");
    }

    @Test
    @DisplayName("the aggregator resolves with the default per-check timeout")
    void aggregatorResolves() {
        HealthGraphComponent component = DaggerHealthCheckModuleGraphTest_HealthGraphComponent.create();

        HealthCheckAggregator aggregator = component.aggregator();

        assertNotNull(aggregator);
        assertEquals(HealthCheckAggregator.DEFAULT_CHECK_TIMEOUT_SECONDS, aggregator.checkTimeoutSeconds());
        assertTrue(aggregator.evaluate(component.readinessChecks()).result().isUp(), "an empty set evaluates UP");
    }

    // --- Timeout from the application configuration ---

    private static HealthCheckAggregator aggregatorFor(JsonObject config) {
        return DaggerHealthCheckModuleGraphTest_ConfiguredComponent.builder()
                .configModule(new ConfigModule(config))
                .build()
                .aggregator();
    }

    @Test
    @DisplayName("the injected aggregator honours management.healthCheckTimeoutSeconds")
    void aggregatorHonoursTheConfiguredTimeout() {
        JsonObject config = new JsonObject().put("management", new JsonObject().put("healthCheckTimeoutSeconds", 12));

        assertEquals(12L, aggregatorFor(config).checkTimeoutSeconds());
    }

    @Test
    @DisplayName("a numeric string, as property sources deliver it, is read like the management probes read it")
    void aggregatorReadsANumericString() {
        JsonObject config =
                new JsonObject().put("management", new JsonObject().put("healthCheckTimeoutSeconds", " 10 "));

        assertEquals(10L, aggregatorFor(config).checkTimeoutSeconds());
    }

    @Test
    @DisplayName("an integral floating-point timeout is accepted as the whole number it denotes")
    void aggregatorAcceptsAnIntegralFloatingPointTimeout() {
        JsonObject config = new JsonObject().put("management", new JsonObject().put("healthCheckTimeoutSeconds", 7.0));

        assertEquals(7L, aggregatorFor(config).checkTimeoutSeconds());
    }

    @Test
    @DisplayName("a bound configuration without the key keeps the default timeout")
    void aggregatorKeepsTheDefaultWhenTheKeyIsAbsent() {
        assertEquals(
                HealthCheckAggregator.DEFAULT_CHECK_TIMEOUT_SECONDS,
                aggregatorFor(new JsonObject()).checkTimeoutSeconds());
        assertEquals(
                HealthCheckAggregator.DEFAULT_CHECK_TIMEOUT_SECONDS,
                aggregatorFor(new JsonObject().put("management", new JsonObject().put("port", 9090)))
                        .checkTimeoutSeconds());
    }

    @Test
    @DisplayName("a zero, negative or non-numeric timeout fails with a configuration error naming the key")
    void aggregatorRejectsAnInvalidTimeout() {
        for (Object bad : new Object[] {0, -3, "0", "soon", true, 2.5, 2.5f, "2.5"}) {
            JsonObject config =
                    new JsonObject().put("management", new JsonObject().put("healthCheckTimeoutSeconds", bad));

            ConfigurationException e = assertThrows(ConfigurationException.class, () -> aggregatorFor(config));

            assertTrue(e.getMessage().startsWith("management.healthCheckTimeoutSeconds must be"), e.getMessage());
            assertFalse(e.getMessage().contains("soon"), "the rejected text is never echoed");
        }
    }

    // --- Fixtures ---

    @Module
    static final class ConfigModule {
        private final JsonObject config;

        ConfigModule(JsonObject config) {
            this.config = config;
        }

        @Provides
        @VertxConfig
        JsonObject config() {
            return config;
        }
    }

    @Component(modules = {HealthCheckModule.class, ConfigModule.class})
    interface ConfiguredComponent {
        HealthCheckAggregator aggregator();
    }

    @Component(modules = HealthCheckModule.class)
    interface HealthGraphComponent {
        @Liveness
        Set<HealthCheck> livenessChecks();

        @Readiness
        Set<HealthCheck> readinessChecks();

        HealthCheckAggregator aggregator();
    }
}
