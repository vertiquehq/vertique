// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.logging.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggerContextVO;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;

/**
 * A package-private decorator around {@link ILoggingEvent} that overrides the MDC property map to merge
 * Vert.x context-local MDC values into the standard SLF4J thread-local MDC.
 *
 * <p>Logback captures MDC eagerly from the SLF4J thread-local when the event is created. On Vert.x
 * event-loop threads, the "real" MDC lives in context-local storage ({@link dev.vertique.logging.MDC})
 * and may not have been synchronised to the SLF4J thread-local. This wrapper is created by
 * {@link VertxAwareAppender#append(ILoggingEvent)} on the event-loop thread, eagerly merging the Vert.x
 * MDC snapshot into the event's MDC map so that the enriched data travels safely to the worker thread
 * where the actual I/O appender runs.
 *
 * <p>Vert.x MDC values take precedence over SLF4J thread-local MDC values for the same key.
 *
 * <p>All other {@link ILoggingEvent} methods delegate directly to the wrapped event.
 */
class VertxMdcLoggingEvent implements ILoggingEvent {

    // --- Delegate ---

    private final ILoggingEvent delegate;

    // --- Merged MDC ---

    /** Eagerly computed, unmodifiable merged MDC map (SLF4J base + Vert.x overlay). */
    private final Map<String, String> mergedMdc;

    /**
     * Creates a new wrapper that merges the given Vert.x MDC snapshot into the delegate event's MDC.
     *
     * @param delegate  the original logback event to wrap; all non-MDC methods delegate to it
     * @param vertxMdc  a snapshot of the current Vert.x context-local MDC; its values override any
     *                  identically-keyed entries in the SLF4J MDC captured by {@code delegate}
     */
    VertxMdcLoggingEvent(ILoggingEvent delegate, Map<String, String> vertxMdc) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        Map<String, String> originalMdc = delegate.getMDCPropertyMap();
        Map<String, String> merged = originalMdc != null ? new HashMap<>(originalMdc) : new HashMap<>();
        merged.putAll(vertxMdc);
        this.mergedMdc = Collections.unmodifiableMap(merged);
    }

    // --- MDC overrides ---

    /**
     * Returns the merged MDC map containing both the original SLF4J MDC entries and the Vert.x
     * context-local MDC entries. Vert.x values win on key collision.
     *
     * @return unmodifiable merged MDC map; never {@code null}
     */
    @Override
    public Map<String, String> getMDCPropertyMap() {
        return mergedMdc;
    }

    /**
     * Synonym for {@link #getMDCPropertyMap()}.
     *
     * @return unmodifiable merged MDC map; never {@code null}
     * @deprecated Use {@link #getMDCPropertyMap()} instead
     */
    @Override
    @Deprecated
    public Map<String, String> getMdc() {
        return mergedMdc;
    }

    // --- Delegating methods ---

    @Override
    public String getThreadName() {
        return delegate.getThreadName();
    }

    @Override
    public Level getLevel() {
        return delegate.getLevel();
    }

    @Override
    public String getMessage() {
        return delegate.getMessage();
    }

    @Override
    public Object[] getArgumentArray() {
        return delegate.getArgumentArray();
    }

    @Override
    public String getFormattedMessage() {
        return delegate.getFormattedMessage();
    }

    @Override
    public String getLoggerName() {
        return delegate.getLoggerName();
    }

    @Override
    public LoggerContextVO getLoggerContextVO() {
        return delegate.getLoggerContextVO();
    }

    @Override
    public IThrowableProxy getThrowableProxy() {
        return delegate.getThrowableProxy();
    }

    @Override
    public StackTraceElement[] getCallerData() {
        return delegate.getCallerData();
    }

    @Override
    public boolean hasCallerData() {
        return delegate.hasCallerData();
    }

    @Override
    public List<Marker> getMarkerList() {
        return delegate.getMarkerList();
    }

    @Override
    public long getTimeStamp() {
        return delegate.getTimeStamp();
    }

    @Override
    public int getNanoseconds() {
        return delegate.getNanoseconds();
    }

    @Override
    public Instant getInstant() {
        return delegate.getInstant();
    }

    @Override
    public long getSequenceNumber() {
        return delegate.getSequenceNumber();
    }

    @Override
    public List<KeyValuePair> getKeyValuePairs() {
        return delegate.getKeyValuePairs();
    }

    /**
     * Delegates to the wrapped event's {@code prepareForDeferredProcessing()} to capture thread name,
     * caller data, and any other state that must be snapshotted before the event leaves the caller thread.
     */
    @Override
    public void prepareForDeferredProcessing() {
        delegate.prepareForDeferredProcessing();
    }
}
