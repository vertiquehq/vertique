// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * TP-001: {@code failureMode} has no default anywhere — every policy row, enabled or disabled,
 * must declare it explicitly, or typed config binding fails (contracts/rate-limit-runtime.md,
 * "Policy model"; FR-006, {@code spec.md} §5.5).
 */
class RateLimitPolicyValidationTest {

    private static final ConfigParser PARSER = new DefaultConfigParser(DefaultConfigMapper.lenient());

    @Test
    void shouldRequireExplicitFailureModeForEveryPolicyIncludingDisabled() {
        JsonObject enabledRowOmittingFailureMode = policyRowOmittingFailureMode("enabled-quota", true);
        JsonObject disabledRowOmittingFailureMode = policyRowOmittingFailureMode("disabled-quota", false);

        assertThatThrownBy(() -> PARSER.parse(enabledRowOmittingFailureMode, RateLimitPolicy.class))
                .as("enabled policy omitting failureMode")
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("failureMode");

        assertThatThrownBy(() -> PARSER.parse(disabledRowOmittingFailureMode, RateLimitPolicy.class))
                .as("disabled policy omitting failureMode")
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("failureMode");
    }

    /**
     * Builds a policy row omitting {@code failureMode}, with every other required field present —
     * a complete field set otherwise, so the only decisive variable is the omission itself.
     */
    private static JsonObject policyRowOmittingFailureMode(String name, boolean enabled) {
        return new JsonObject()
                .put("name", name)
                .put("enabled", enabled)
                .put("mode", "LOCAL")
                .put("revision", "r1")
                .put("defaultCost", 1)
                .put(
                        "algorithm",
                        new JsonObject()
                                .put("type", "TOKEN_BUCKET")
                                .put("capacity", 10)
                                .put(
                                        "refill",
                                        new JsonObject()
                                                .put("type", "GREEDY")
                                                .put("tokens", 10)
                                                .put("periodMs", 1_000)));
    }
}
