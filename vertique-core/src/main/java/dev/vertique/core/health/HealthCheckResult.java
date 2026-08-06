// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.health;

import java.util.Map;
import java.util.Objects;

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
 *   <li>{@link #down(Throwable)} — unhealthy, described by a failure</li>
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
     * <p>A {@code null} error means "no diagnostic message available" and yields a result with
     * empty data, equivalent to {@link #down()}. This keeps the factory total for the common
     * {@code down(throwable.getMessage())} idiom, whose argument is null for any exception
     * constructed without a message.
     *
     * @param error the error description; may be {@code null}
     * @return a DOWN result carrying {@code error} in data, or with empty data when it is null
     */
    public static HealthCheckResult down(String error) {
        return new HealthCheckResult(HealthStatus.DOWN, error == null ? Map.of() : Map.of("error", error));
    }

    /**
     * Returns an unhealthy result describing the given failure.
     *
     * <p>The {@code error} entry is the throwable's {@linkplain Throwable#getMessage() message}
     * when non-null, and its {@linkplain Class#getName() fully qualified class name} otherwise —
     * including when {@code getMessage()} itself throws an {@link Exception}, since a health check
     * must be able to describe any failure it is handed. A blank message is passed through
     * verbatim; the cause chain is not walked.
     *
     * @param cause the failure to describe; must not be {@code null}
     * @return a DOWN result describing {@code cause}
     * @throws NullPointerException if {@code cause} is {@code null}
     */
    public static HealthCheckResult down(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        // getMessage() is overridable, so it is read exactly once and defensively: a second call
        // could observe a different value, and a throwable that cannot describe itself must still
        // produce a DOWN result rather than propagate a second, unrelated failure.
        String message;
        try {
            message = cause.getMessage();
        } catch (Exception e) {
            message = null;
        }
        return down(message != null ? message : cause.getClass().getName());
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
