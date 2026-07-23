// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Vert.x-aware Logback appenders for correct MDC propagation and non-blocking I/O on event-loop threads.
 *
 * <p>Logback captures MDC from SLF4J's thread-local storage when creating {@code ILoggingEvent}. In Vert.x,
 * the real MDC lives in {@link io.vertx.core.spi.context.storage.ContextLocal} storage
 * ({@link dev.vertique.logging.MDC}), so logback events miss Vert.x MDC values unless
 * {@link dev.vertique.logging.MDC#syncToSlf4j()} is called manually before every log statement.
 * Additionally, logback appenders typically perform blocking I/O, which is unsafe on Vert.x event-loop threads.
 *
 * <p>This package provides two appenders:
 * <ul>
 *   <li>{@link dev.vertique.logging.logback.VertxAwareAppender} — enriches log events with the current
 *       Vert.x MDC on the event-loop thread, then hands off to a daemon worker thread for async I/O delivery.
 *       Safe to use on event-loop threads.</li>
 *   <li>{@link dev.vertique.logging.logback.MarkerAwareAppender} — routes log events to named appenders
 *       based on SLF4J marker names (with recursive marker-hierarchy support), falling back to a configured
 *       default appender when no marker matches.</li>
 * </ul>
 *
 * <p>These appenders require {@code logback-classic} on the classpath (declared optional in the module POM).
 * Applications using this module must include logback-classic as a compile or runtime dependency.
 */
package dev.vertique.logging.logback;
