// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Vert.x-aware MDC (Mapped Diagnostic Context) support and structured logging utilities.
 *
 * <p>Owns the MDC types ({@link dev.vertique.logging.MDCContext},
 * {@link dev.vertique.logging.MDCContexts}, {@link dev.vertique.logging.DiagnosticContextSnapshot},
 * {@link dev.vertique.logging.MDCContextValueAdapter}, {@link dev.vertique.logging.MDC}) and a
 * Dagger module ({@link dev.vertique.logging.LoggingContextModule}) that registers MDC
 * service-dispatch propagation into the {@code vertique-context} substrate. MDC values are stored
 * in the Vert.x context-local slot owned by {@code dev.vertique.context.ContextLocalServiceProvider}
 * rather than in thread-local storage, so they survive event-loop hops and are correctly scoped
 * to each concurrent request.
 *
 * <p>Integrates with SLF4J and Logback through
 * {@link dev.vertique.logging.logback.VertxAwareAppender}, which captures the Vert.x MDC snapshot
 * on the event-loop thread before queuing log events for async delivery, ensuring that fields such
 * as request ID, correlation ID, and user identity appear consistently in structured log output
 * without leaking across requests.
 */
package dev.vertique.logging;
