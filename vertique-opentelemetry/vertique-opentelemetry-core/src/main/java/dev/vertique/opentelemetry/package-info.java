// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * OpenTelemetry SDK bootstrap (reuse-or-provision), Vert.x tracer installation,
 * trace-log correlation bridge, and security span events.
 *
 * <p>This package is the entry point for all OpenTelemetry integration in the Vertique framework.
 * It provides lifecycle management for the OpenTelemetry SDK, installs the Vert.x tracer so that
 * distributed trace context is propagated across async Vert.x event-loop boundaries, bridges
 * active span context into SLF4J MDC for trace-correlated log lines, and emits security-relevant
 * events as OpenTelemetry span events.
 */
package dev.vertique.opentelemetry;
