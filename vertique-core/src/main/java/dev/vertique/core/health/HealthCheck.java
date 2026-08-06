// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.health;

import io.vertx.core.Future;

/**
 * SPI for health check indicators. Implementations report the health of a
 * component (database pool, downstream service, etc.) and are auto-discovered
 * via Dagger {@code Set<HealthCheck>} multibinding.
 *
 * <p>Classify checks using qualifier annotations:
 * <ul>
 *   <li>{@link Liveness @Liveness} — lightweight "is the process alive" checks</li>
 *   <li>{@link Readiness @Readiness} — dependency checks (DB pool, downstream services)</li>
 * </ul>
 *
 * <p>Example registration in a Dagger module:
 * <pre>{@code
 * @Provides @IntoSet @Readiness
 * HealthCheck databaseHealth(DatabaseHealthCheck check) {
 *     return check;
 * }
 * }</pre>
 *
 * @see HealthCheckResult
 * @see Liveness
 * @see Readiness
 */
public interface HealthCheck {

    /**
     * Returns the human-readable name of this health check, used as a key in
     * the aggregated health response (e.g., {@code "database"}, {@code "services"}).
     *
     * @return the check name (must be unique within its qualifier set)
     */
    String name();

    /**
     * Performs the health check and returns the result asynchronously.
     *
     * <p>Implementations should return a completed {@link Future} with an appropriate
     * {@link HealthCheckResult} rather than a failed future. If the check throws an
     * exception or returns a failed future, the management endpoint will report it
     * as {@link HealthStatus#DOWN} with the throwable's message, or its fully
     * qualified class name when it has no message.
     *
     * @return a future completing with the health check result
     */
    Future<HealthCheckResult> check();
}
