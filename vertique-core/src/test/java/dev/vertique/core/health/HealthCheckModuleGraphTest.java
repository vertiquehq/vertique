// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.health;

import static org.junit.jupiter.api.Assertions.*;

import dagger.Component;
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

    // --- Fixtures ---

    @Component(modules = HealthCheckModule.class)
    interface HealthGraphComponent {
        @Liveness
        Set<HealthCheck> livenessChecks();

        @Readiness
        Set<HealthCheck> readinessChecks();

        HealthCheckAggregator aggregator();
    }
}
