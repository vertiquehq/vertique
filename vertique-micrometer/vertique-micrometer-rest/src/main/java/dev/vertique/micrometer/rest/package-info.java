// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Micrometer REST server metrics adapter.
 *
 * <p>This package bridges the REST request-completion event ({@link dev.vertique.rest.core.events.RestRequestCompletedEvent})
 * and the active-request lifecycle into Micrometer meters. It is observe-only: it records metrics
 * but never modifies the request or response, and never submits audit records.
 *
 * <p>Zero-overhead when metrics are unconfigured: before {@code VertiqueApplication} bootstrap the
 * backing registry is an empty composite whose recording is a no-op (NFR-TEL-003). When
 * {@code metrics.enabled=false} is supplied (via {@link dev.vertique.micrometer.MicrometerModule}
 * or an application-level binding), both the listener and interceptor skip all meter work
 * immediately.
 *
 * <p>Key classes:
 * <ul>
 *   <li>{@link dev.vertique.micrometer.rest.MicrometerRestModule} — Dagger module contributing all
 *       components via multibinding; install alongside {@code RestCoreModule} (or {@code RestModule})
 *       and {@link dev.vertique.micrometer.MicrometerModule}</li>
 *   <li>{@link dev.vertique.micrometer.rest.RestServerRequestMetricsListener} — records a per-request
 *       timer ({@value dev.vertique.micrometer.rest.RestServerRequestMetricsListener#METER_NAME})
 *       on each {@link dev.vertique.rest.core.events.RestRequestCompletedEvent}</li>
 *   <li>{@link dev.vertique.micrometer.rest.RestServerActiveRequestsInterceptor} — maintains a
 *       gauge ({@value dev.vertique.micrometer.rest.RestServerActiveRequestsInterceptor#METER_NAME})
 *       tracking in-flight HTTP requests</li>
 *   <li>{@link dev.vertique.micrometer.rest.HttpOutcome} — low-cardinality HTTP outcome bucket used
 *       as a meter tag</li>
 * </ul>
 */
package dev.vertique.micrometer.rest;
