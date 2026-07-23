// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.config.JaxRsConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link WebValidationStrategy} parses {@link JaxRsConfig#validationMode()} strictly at
 * construction (startup), failing closed on any value other than the two camelCase literals
 * {@code "aggregate"} and {@code "failFast"}.
 *
 * <p>Before this hardening the strategy treated every value except exactly {@code "failFast"} as
 * aggregate, so a typo ({@code "agregate"}, {@code "FailFast"}, {@code "fail-fast"}) silently changed
 * validation behaviour rather than failing startup.
 */
class WebValidationStrategyModeParseTest {

    @Test
    @DisplayName(
            "An unknown validationMode fails startup with RestConfigurationException naming the value and allowed set")
    void unknownValidationModeFailsStartup() {
        JaxRsConfig config = JaxRsConfig.builder().validationMode("agregate").build();

        RestConfigurationException ex =
                assertThrows(RestConfigurationException.class, () -> new WebValidationStrategy(config));

        assertTrue(ex.getMessage().contains("agregate"), "message must name the bad value");
        assertTrue(ex.getMessage().contains("aggregate"), "message must list the allowed 'aggregate'");
        assertTrue(ex.getMessage().contains("failFast"), "message must list the allowed 'failFast'");
    }

    @Test
    @DisplayName("A wrong-case validationMode (FailFast) fails closed rather than silently behaving as aggregate")
    void wrongCaseValidationModeFailsStartup() {
        JaxRsConfig config = JaxRsConfig.builder().validationMode("FailFast").build();

        assertThrows(RestConfigurationException.class, () -> new WebValidationStrategy(config));
    }

    @Test
    @DisplayName("validationMode 'aggregate' constructs the strategy")
    void aggregateModeConstructs() {
        JaxRsConfig config = JaxRsConfig.builder().validationMode("aggregate").build();

        assertDoesNotThrow(() -> new WebValidationStrategy(config));
    }

    @Test
    @DisplayName("validationMode 'failFast' constructs the strategy")
    void failFastModeConstructs() {
        JaxRsConfig config = JaxRsConfig.builder().validationMode("failFast").build();

        assertDoesNotThrow(() -> new WebValidationStrategy(config));
    }

    @Test
    @DisplayName("The default config (validationMode defaults to 'aggregate') constructs the strategy")
    void defaultConfigConstructs() {
        assertDoesNotThrow(() -> new WebValidationStrategy(JaxRsConfig.builder().build()));
    }

    @Test
    @DisplayName("A blank validationMode defaults to aggregate (does not fail startup)")
    void blankValidationModeDefaultsToAggregate() {
        JaxRsConfig config = JaxRsConfig.builder().validationMode("   ").build();

        assertDoesNotThrow(() -> new WebValidationStrategy(config));
    }
}
