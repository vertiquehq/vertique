// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Management module providing health check endpoints on a dedicated HTTP server.
 *
 * <p>The {@link dev.vertique.management.ManagementVerticle} runs a separate HTTP server
 * on a configurable port (default 9090) exposing {@code /health/live} and {@code /health/ready}
 * endpoints. Health checks are auto-discovered via Dagger multibinding using the
 * {@link dev.vertique.core.health.Liveness @Liveness} and
 * {@link dev.vertique.core.health.Readiness @Readiness} qualifiers.
 *
 * <p>Include {@link dev.vertique.management.ManagementModule} in the application's Dagger
 * {@code @Component} to enable management endpoints.
 */
package dev.vertique.management;
