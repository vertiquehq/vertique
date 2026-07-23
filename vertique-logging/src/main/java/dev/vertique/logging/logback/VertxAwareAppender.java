// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.logging.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AsyncAppenderBase;
import dev.vertique.logging.MDC;
import java.util.Map;

/**
 * A Vert.x-aware asynchronous logback appender that merges Vert.x context-local MDC values into each
 * log event before handing it off to a daemon worker thread for I/O delivery.
 *
 * <p>Logback captures MDC from SLF4J's thread-local storage at event creation time. On Vert.x event-loop
 * threads, the application stores request-scoped diagnostic data in context-local storage via
 * {@link dev.vertique.logging.MDC}, which is not synchronised to the SLF4J thread-local by default.
 * This appender reads the Vert.x MDC on the event-loop thread — before the event is queued — and wraps
 * the event in a {@link VertxMdcLoggingEvent} so that the enriched MDC travels safely to the worker thread.
 *
 * <p>Because the actual I/O (console, file, network) happens on the worker thread, the event-loop thread
 * is never blocked by I/O operations. The queue is non-blocking by default ({@code neverBlock=true}): when
 * the queue is full, events are silently discarded rather than blocking the event loop. This default can be
 * overridden in the XML configuration if blocking is preferred.
 *
 * <p>This appender extends {@link AsyncAppenderBase}, which provides:
 * <ul>
 *   <li>A configurable {@code queueSize} (default 256)</li>
 *   <li>A single daemon worker thread for sequential delivery</li>
 *   <li>Graceful drain on {@code stop()} up to {@code maxFlushTime} milliseconds</li>
 *   <li>A configurable {@code discardingThreshold} for low-priority event dropping</li>
 * </ul>
 *
 * <h2>Example logback.xml configuration</h2>
 * <pre>{@code
 * <configuration>
 *
 *   <!-- Console appender performing the actual I/O on the worker thread -->
 *   <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
 *     <encoder>
 *       <pattern>%d{HH:mm:ss} %-5level [%X{requestId}] %logger{36} - %msg%n</pattern>
 *     </encoder>
 *   </appender>
 *
 *   <!-- Vert.x-aware async wrapper: merges Vert.x MDC, queues for worker-thread delivery -->
 *   <appender name="ASYNC" class="dev.vertique.logging.logback.VertxAwareAppender">
 *     <queueSize>512</queueSize>
 *     <!-- neverBlock defaults to true; set to false to block when the queue is full -->
 *     <neverBlock>true</neverBlock>
 *     <appender-ref ref="CONSOLE"/>
 *   </appender>
 *
 *   <root level="INFO">
 *     <appender-ref ref="ASYNC"/>
 *   </root>
 *
 * </configuration>
 * }</pre>
 *
 * @see VertxMdcLoggingEvent
 * @see dev.vertique.logging.MDC
 */
public class VertxAwareAppender extends AsyncAppenderBase<ILoggingEvent> {

    /**
     * Creates a new {@code VertxAwareAppender} with {@code neverBlock} defaulting to {@code true}.
     *
     * <p>The {@code neverBlock=true} default ensures that a full queue never blocks the Vert.x event loop;
     * events are silently discarded instead. Override this in XML configuration if blocking is acceptable.
     */
    public VertxAwareAppender() {
        setNeverBlock(true);
    }

    /**
     * Captures thread name, caller data, and other deferred-processing state from the event on the
     * caller thread before the event is queued to the worker thread.
     *
     * <p>Overrides the no-op in {@link AsyncAppenderBase} to ensure that thread-sensitive data is
     * snapshotted while still on the event-loop thread.
     *
     * @param event the event to prepare; its {@code prepareForDeferredProcessing()} is called
     */
    @Override
    protected void preprocess(ILoggingEvent event) {
        event.prepareForDeferredProcessing();
    }

    /**
     * Enriches the event with the current Vert.x context-local MDC, then queues it for worker-thread
     * delivery.
     *
     * <p>This method runs on the caller (typically event-loop) thread. It reads the Vert.x MDC snapshot
     * via {@link MDC#getCopyOfContextMap()}. If the snapshot is non-empty, the event is wrapped in a
     * {@link VertxMdcLoggingEvent} so the merged MDC is visible to downstream appenders on the worker
     * thread. When no Vert.x context is active (e.g., calls from plain threads), the event passes through
     * unchanged.
     *
     * @param event the log event to enrich and queue
     */
    @Override
    protected void append(ILoggingEvent event) {
        Map<String, String> vertxMdc = MDC.getCopyOfContextMap();
        if (!vertxMdc.isEmpty()) {
            event = new VertxMdcLoggingEvent(event, vertxMdc);
        }
        super.append(event);
    }
}
