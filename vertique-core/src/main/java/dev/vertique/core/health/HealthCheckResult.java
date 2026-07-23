// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.health;

import java.util.Map;

/**
 * Result of a single {@link HealthCheck} invocation, containing the health
 * {@link HealthStatus} and optional diagnostic data.
 *
 * <p>Use the static factory methods for convenience:
 * <ul>
 *   <li>{@link #up()} — healthy, no data</li>
 *   <li>{@link #up(Map)} — healthy, with data</li>
 *   <li>{@link #down()} — unhealthy, no data</li>
 *   <li>{@link #down(String)} — unhealthy, with error message</li>
 *   <li>{@link #down(Map)} — unhealthy, with data</li>
 * </ul>
 *
 * @param status the health status
 * @param data optional diagnostic key-value pairs (may be empty, never null)
 */
public record HealthCheckResult(HealthStatus status, Map<String, Object> data) {

    /**
     * Creates a result with a guaranteed non-null, unmodifiable data map.
     *
     * @param status the health status
     * @param data optional diagnostic data (null treated as empty)
     */
    public HealthCheckResult {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    /**
     * Returns a healthy result with no diagnostic data.
     *
     * @return a new UP result
     */
    public static HealthCheckResult up() {
        return new HealthCheckResult(HealthStatus.UP, Map.of());
    }

    /**
     * Returns a healthy result with diagnostic data.
     *
     * @param data diagnostic key-value pairs
     * @return a new UP result with data
     */
    public static HealthCheckResult up(Map<String, Object> data) {
        return new HealthCheckResult(HealthStatus.UP, data);
    }

    /**
     * Returns an unhealthy result with no diagnostic data.
     *
     * @return a new DOWN result
     */
    public static HealthCheckResult down() {
        return new HealthCheckResult(HealthStatus.DOWN, Map.of());
    }

    /**
     * Returns an unhealthy result with an error message.
     *
     * @param error the error description
     * @return a new DOWN result with the error in data
     */
    public static HealthCheckResult down(String error) {
        return new HealthCheckResult(HealthStatus.DOWN, Map.of("error", error));
    }

    /**
     * Returns an unhealthy result with diagnostic data.
     *
     * @param data diagnostic key-value pairs
     * @return a new DOWN result with data
     */
    public static HealthCheckResult down(Map<String, Object> data) {
        return new HealthCheckResult(HealthStatus.DOWN, data);
    }
}
