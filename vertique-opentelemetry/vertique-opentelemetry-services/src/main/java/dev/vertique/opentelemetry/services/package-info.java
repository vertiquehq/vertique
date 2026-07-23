// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * OpenTelemetry event bus service dispatch span enrichment adapter.
 *
 * <p>This package bridges the {@link dev.vertique.services.interceptor.ServiceInterceptor}
 * hooks into OpenTelemetry span enrichment. It is observe-only: it enriches the active
 * CONSUMER event-bus span but never modifies the dispatch outcome, and never submits audit records.
 *
 * <p>Span lifecycle note: no sender-side seam exists — spans are enriched only under a traced
 * parent (propagation mode: PROPAGATE). When no recording span is current, all operations are
 * silent no-ops via the OpenTelemetry API's built-in no-op implementation. Terminal-outcome
 * status is best-effort: the reply is sent before {@code onTerminalComplete} fires, so writes
 * to an already-ended span are safe no-ops per the OpenTelemetry API contract.
 *
 * <p>Key classes:
 * <ul>
 *   <li>{@link dev.vertique.opentelemetry.services.OpenTelemetryServicesModule} — Dagger module
 *       contributing the interceptor via multibinding; install alongside
 *       {@link dev.vertique.services.DispatchModule}</li>
 *   <li>{@link dev.vertique.opentelemetry.services.ServiceDispatchSpanEnrichmentInterceptor} —
 *       enriches the active CONSUMER span with service target and one-way attributes on dispatch,
 *       and records terminal outcome status on terminal complete</li>
 *   <li>{@link dev.vertique.opentelemetry.services.ServiceAttributes} — package-private constants
 *       for custom {@link io.opentelemetry.api.common.AttributeKey}s</li>
 * </ul>
 */
package dev.vertique.opentelemetry.services;
