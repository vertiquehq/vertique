// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Health check SPI and in-process evaluation. Provides the
 * {@link dev.vertique.core.health.HealthCheck} interface for implementing
 * health indicators, the {@link dev.vertique.core.health.HealthCheckResult}
 * result record, the {@link dev.vertique.core.health.Liveness} /
 * {@link dev.vertique.core.health.Readiness} Dagger qualifier annotations
 * for classifying checks, and the {@link dev.vertique.core.health.HealthCheckAggregator}
 * that evaluates a qualified set into a {@link dev.vertique.core.health.HealthReport}.
 *
 * <p>Types live in {@code core} so that any module can implement health checks,
 * and anything in the process can evaluate them, without depending on the
 * module that serves them over HTTP.
 */
package dev.vertique.core.health;
