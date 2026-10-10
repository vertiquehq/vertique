// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.config;

import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;

/**
 * The deadline on the action {@link dev.vertique.security.authz.Authorizer} call a service dispatch
 * makes, read from the same {@code security.authz.gateDeadlineMs} key that bounds the REST,
 * WebSocket and MCP authorization gates, so one operator setting bounds every transport.
 *
 * <p>An authorizer whose future never completes would otherwise leave the service call, and the
 * caller waiting on it, hanging. When the deadline elapses the call is denied with {@code
 * INTERNAL_AUTHZ_ERROR}, exactly as for an authorizer that throws or fails.
 *
 * @param gateDeadlineMs the bound, in milliseconds, on each authorizer call; must be positive
 */
public record ServiceAuthorizationConfig(long gateDeadlineMs) {

    /** Default deadline, matching {@code security.authz.gateDeadlineMs}'s default in the REST modules. */
    public static final long DEFAULT_GATE_DEADLINE_MS = 5_000L;

    private static final String KEY = "gateDeadlineMs";

    /**
     * Validates that the deadline is positive.
     *
     * @throws ConfigurationException if {@code gateDeadlineMs} is not positive
     */
    public ServiceAuthorizationConfig {
        if (gateDeadlineMs <= 0) {
            throw new ConfigurationException("security.authz.gateDeadlineMs must be > 0, got: " + gateDeadlineMs);
        }
    }

    /**
     * Returns the framework default.
     *
     * @return a configuration carrying {@link #DEFAULT_GATE_DEADLINE_MS}
     */
    public static ServiceAuthorizationConfig defaults() {
        return new ServiceAuthorizationConfig(DEFAULT_GATE_DEADLINE_MS);
    }

    /**
     * Reads {@code security.authz.gateDeadlineMs} from the application configuration, defaulting when
     * it is absent.
     *
     * @param rootConfig the application configuration; must not be {@code null}
     * @return the configuration
     * @throws ConfigurationException if the configured value is not a positive number
     */
    public static ServiceAuthorizationConfig fromConfig(JsonObject rootConfig) {
        JsonObject section = JsonConfigPaths.navigateObject(rootConfig, "security", "authz");
        Object raw = section.getValue(KEY);
        if (raw == null) {
            return defaults();
        }
        if (!(raw instanceof Number number)) {
            throw new ConfigurationException("security.authz.gateDeadlineMs must be a number, got: " + raw);
        }
        return new ServiceAuthorizationConfig(number.longValue());
    }
}
