// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Micrometer event bus service dispatch metrics adapter.
 *
 * <p>This package bridges the {@link dev.vertique.services.interceptor.ServiceInterceptor}
 * {@code onTerminalComplete} hook into Micrometer meters. It is observe-only: it records metrics
 * but never modifies the dispatch outcome, and never submits audit records.
 *
 * <p>Zero-overhead when metrics are unconfigured: before {@code VertiqueApplication} bootstrap the
 * backing registry is an empty composite whose recording is a no-op (NFR-TEL-003). When
 * {@code metrics.enabled=false} is supplied (via {@link dev.vertique.micrometer.MicrometerModule}
 * or an application-level binding), the interceptor skips all meter work immediately.
 *
 * <p>Key classes:
 * <ul>
 *   <li>{@link dev.vertique.micrometer.services.MicrometerServicesModule} — Dagger module contributing
 *       the interceptor via multibinding; install alongside {@link dev.vertique.services.DispatchModule}
 *       and {@link dev.vertique.micrometer.MicrometerModule}</li>
 *   <li>{@link dev.vertique.micrometer.services.ServiceDispatchMetricsInterceptor} — records a per-dispatch
 *       timer ({@value dev.vertique.micrometer.services.ServiceDispatchMetricsInterceptor#METER_NAME})
 *       on each terminal service outcome</li>
 * </ul>
 */
package dev.vertique.micrometer.services;
