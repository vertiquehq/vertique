// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Health check SPI for the Vert.x framework management module. Provides the
 * {@link dev.vertique.core.health.HealthCheck} interface for implementing
 * health indicators, the {@link dev.vertique.core.health.HealthCheckResult}
 * result record, and the {@link dev.vertique.core.health.Liveness} /
 * {@link dev.vertique.core.health.Readiness} Dagger qualifier annotations
 * for classifying checks.
 *
 * <p>Types live in {@code core} so that modules like {@code db-postgresql} and
 * {@code services} can implement health checks without depending on the
 * {@code management} module.
 */
package dev.vertique.core.health;
