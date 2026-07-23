// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * OpenTelemetry REST server span enrichment adapter.
 *
 * <p>This package bridges the REST operation handler pipeline and the response interceptor pipeline
 * into OpenTelemetry span enrichment. It is observe-only: it enriches spans but never modifies the
 * request or response, and never submits audit records.
 *
 * <p>Zero-overhead when tracing is unconfigured: the module uses the OpenTelemetry API, which is a
 * no-op by default unless an SDK is installed. No config gate is provided by design — enrichment
 * is always attempted and silently becomes a no-op when no recording span is present.
 *
 * <p>Key classes:
 * <ul>
 *   <li>{@link dev.vertique.opentelemetry.rest.OpenTelemetryRestModule} — Dagger module contributing
 *       all components via multibinding; install alongside {@code RestCoreModule} (or
 *       {@code RestModule})</li>
 *   <li>{@link dev.vertique.opentelemetry.rest.ServerSpanEnrichmentContributor} — per-operation
 *       handler that renames the active span and records HTTP route and operationId attributes</li>
 *   <li>{@link dev.vertique.opentelemetry.rest.ServerSpanOutcomeInterceptor} — response interceptor
 *       that records span status and error type after each HTTP response</li>
 *   <li>{@link dev.vertique.opentelemetry.rest.RestSpanKeys} — package-private constants for
 *       routing-context data keys and custom {@link io.opentelemetry.api.common.AttributeKey}s</li>
 * </ul>
 */
package dev.vertique.opentelemetry.rest;
