// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.logging.logback;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;

/**
 * Unit tests for {@link VertxAwareAppender}.
 *
 * <p>Verifies that Vert.x context-local MDC entries are correctly merged into log events,
 * that Vert.x MDC values win over SLF4J thread-local MDC on key collision, and that
 * events from non-Vert.x threads pass through unchanged.
 *
 * <p>The injected {@link Vertx} parameter is named {@code vertxInstance} throughout to avoid a
 * name-resolution conflict with the {@code dev.vertique.logging.MDC} fully-qualified class
 * reference used inside lambda bodies (the local variable {@code vertx} would shadow the package
 * prefix {@code vertx} in qualified names).
 *
 * <h2>Async drain pattern</h2>
 * {@link VertxAwareAppender} queues events on the caller thread and delivers them on a daemon worker
 * thread. Tests that run inside {@code runOnContext} use a {@link CountDownLatch} to signal when the
 * event has been queued, then drain the appender on the test thread (outside the event loop) by calling
 * {@link VertxAwareAppender#stop()}, which blocks until the worker thread has delivered all queued
 * events. This avoids blocking the event-loop thread with a long {@code join()} inside a handler.
 */
@ExtendWith(VertxExtension.class)
class VertxAwareAppenderTest {

    // --- Test infrastructure ---

    /** Logger context shared across tests. */
    private LoggerContext loggerContext;

    /** Appender under test, configured fresh for each test. */
    private VertxAwareAppender appender;

    /** Delegate capturing appender that collects events from the worker thread. */
    private CapturingAppender capturing;

    @BeforeEach
    void setUp() {
        loggerContext = new LoggerContext();
        loggerContext.setName("test");

        capturing = new CapturingAppender();
        capturing.setName("CAPTURE");
        capturing.setContext(loggerContext);
        capturing.start();

        appender = new VertxAwareAppender();
        appender.setName("ASYNC");
        appender.setContext(loggerContext);
        appender.addAppender(capturing);
        appender.start();
    }

    @AfterEach
    void tearDown() {
        // Stop the appender if a test did not already stop it
        if (appender.isStarted()) {
            appender.stop();
        }
        MDC.clear();
    }

    // --- Helpers ---

    /**
     * Creates a minimal {@link LoggingEvent} with the given message for use in tests.
     *
     * <p>The MDC property map is pre-populated with the current SLF4J MDC snapshot so that
     * {@link ch.qos.logback.classic.spi.LoggingEvent#prepareForDeferredProcessing()} does not
     * attempt to fetch it from the (uninitialized) {@code LoggerContext.getMDCAdapter()}, which
     * would throw a {@link NullPointerException} when using a bare test-only {@link LoggerContext}.
     *
     * @param message the log message
     * @return a configured {@link LoggingEvent} instance with pre-set MDC
     */
    private LoggingEvent makeEvent(String message) {
        LoggingEvent event = new LoggingEvent();
        event.setLoggerContext(loggerContext);
        event.setLevel(Level.INFO);
        event.setMessage(message);
        event.setLoggerName("test.logger");
        event.setTimeStamp(System.currentTimeMillis());
        // Pre-set MDC so prepareForDeferredProcessing() does not call loggerContext.getMDCAdapter()
        // (which is null in a bare test LoggerContext, causing NullPointerException).
        Map<String, String> slf4jMdc = MDC.getCopyOfContextMap();
        event.setMDCPropertyMap(slf4jMdc != null ? slf4jMdc : Collections.emptyMap());
        return event;
    }

    /**
     * Stops the appender, draining the worker queue, and returns captured events.
     *
     * <p>Must be called from the <strong>test thread</strong> (not from inside {@code runOnContext})
     * to avoid blocking the event-loop thread during the worker join.
     *
     * @return the list of events captured after draining
     */
    private List<ILoggingEvent> drainAndCapture() {
        appender.stop();
        return capturing.events();
    }

    // --- Tests ---

    @Test
    @DisplayName("neverBlock defaults to true on new instance")
    void shouldDefaultToNeverBlock() {
        assertTrue(new VertxAwareAppender().isNeverBlock());
    }

    @Test
    @DisplayName("merges Vert.x MDC into event when Vert.x context is active")
    void shouldMergeVertxMdc(Vertx vertxInstance, VertxTestContext testContext) throws Exception {
        // Queue the event from the event-loop thread (captures Vert.x MDC there).
        // A duplicated context is required because MDC writes are fail-fast on non-duplicated contexts.
        CountDownLatch queued = new CountDownLatch(1);
        ContextInternal dup = ((ContextInternal) vertxInstance.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            dev.vertique.logging.MDC.put("requestId", "req-123");
            appender.doAppend(makeEvent("hello"));
            dev.vertique.logging.MDC.clear();
            queued.countDown();
        });

        // Wait for the event to be queued, then drain on the test thread
        assertTrue(queued.await(5, TimeUnit.SECONDS), "event should be queued within 5 seconds");
        List<ILoggingEvent> events = drainAndCapture();

        assertEquals(1, events.size(), "expected one captured event");
        assertEquals("req-123", events.get(0).getMDCPropertyMap().get("requestId"));
        testContext.completeNow();
    }

    @Test
    @DisplayName("Vert.x MDC value wins over SLF4J MDC value on the same key")
    void shouldPreferVertxMdcOverSlf4j(Vertx vertxInstance, VertxTestContext testContext) throws Exception {
        CountDownLatch queued = new CountDownLatch(1);
        ContextInternal dup = ((ContextInternal) vertxInstance.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            // SLF4J thread-local MDC has the old value
            MDC.put("requestId", "slf4j-value");
            // Vert.x MDC has the authoritative value
            dev.vertique.logging.MDC.put("requestId", "vertx-value");
            appender.doAppend(makeEvent("hello"));
            // Clean up thread-local MDC so it doesn't leak
            MDC.clear();
            dev.vertique.logging.MDC.clear();
            queued.countDown();
        });

        assertTrue(queued.await(5, TimeUnit.SECONDS));
        List<ILoggingEvent> events = drainAndCapture();

        assertEquals(1, events.size());
        assertEquals(
                "vertx-value",
                events.get(0).getMDCPropertyMap().get("requestId"),
                "Vert.x MDC should override SLF4J MDC on same key");
        testContext.completeNow();
    }

    @Test
    @DisplayName("preserves original SLF4J MDC entries not overridden by Vert.x MDC")
    void shouldPreserveOriginalMdcEntries(Vertx vertxInstance, VertxTestContext testContext) throws Exception {
        CountDownLatch queued = new CountDownLatch(1);
        ContextInternal dup = ((ContextInternal) vertxInstance.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            MDC.put("slf4jKey", "slf4j-value");
            dev.vertique.logging.MDC.put("vertxKey", "vertx-value");
            appender.doAppend(makeEvent("hello"));
            MDC.clear();
            dev.vertique.logging.MDC.clear();
            queued.countDown();
        });

        assertTrue(queued.await(5, TimeUnit.SECONDS));
        List<ILoggingEvent> events = drainAndCapture();

        assertEquals(1, events.size());
        Map<String, String> mdc = events.get(0).getMDCPropertyMap();
        assertEquals("slf4j-value", mdc.get("slf4jKey"), "original SLF4J entry should be preserved");
        assertEquals("vertx-value", mdc.get("vertxKey"), "Vert.x entry should be present");
        testContext.completeNow();
    }

    @Test
    @DisplayName("passes event through unchanged when no Vert.x context is active")
    void shouldPassThroughWhenNoVertxContext(VertxTestContext testContext) {
        // Called directly on the test (Surefire) thread — no Vert.x context active
        MDC.put("slf4jKey", "plain-value");
        appender.doAppend(makeEvent("plain-thread"));
        MDC.clear();

        List<ILoggingEvent> events = drainAndCapture();

        assertEquals(1, events.size(), "event should be delivered even without Vert.x context");
        testContext.completeNow();
    }

    @Test
    @DisplayName("passes event through unchanged when Vert.x context has empty MDC")
    void shouldPassThroughWhenVertxMdcEmpty(Vertx vertxInstance, VertxTestContext testContext) throws Exception {
        CountDownLatch queued = new CountDownLatch(1);
        vertxInstance.runOnContext(v -> {
            // Vert.x context active but no MDC entries set
            appender.doAppend(makeEvent("no-mdc"));
            queued.countDown();
        });

        assertTrue(queued.await(5, TimeUnit.SECONDS));
        List<ILoggingEvent> events = drainAndCapture();

        assertEquals(1, events.size(), "event should be delivered even with empty Vert.x MDC");
        testContext.completeNow();
    }

    // --- Inner test double ---

    /**
     * Simple in-memory capturing appender used as the delegate for {@link VertxAwareAppender} in tests.
     */
    static class CapturingAppender extends AppenderBase<ILoggingEvent> {

        private final List<ILoggingEvent> captured = Collections.synchronizedList(new ArrayList<>());

        @Override
        protected void append(ILoggingEvent event) {
            captured.add(event);
        }

        /**
         * Returns an unmodifiable snapshot of the captured events.
         *
         * @return captured events in delivery order
         */
        List<ILoggingEvent> events() {
            return Collections.unmodifiableList(captured);
        }
    }
}
