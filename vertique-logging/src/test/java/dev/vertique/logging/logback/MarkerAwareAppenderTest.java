// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.logging.logback;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.status.Status;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * Unit tests for {@link MarkerAwareAppender}.
 *
 * <p>Verifies marker-based routing to named child appenders, fall-through to the default appender,
 * recursive marker-hierarchy resolution, and startup validation (missing default, self-recursion, etc.).
 *
 * <p>No Vert.x context is needed — all tests use plain in-memory logging events and capturing appenders.
 */
class MarkerAwareAppenderTest {

    // --- Test infrastructure ---

    private LoggerContext loggerContext;

    /** Primary (default) capturing appender. */
    private CapturingAppender techAppender;

    /** Secondary capturing appender for marker-based routing. */
    private CapturingAppender auditAppender;

    /** Appender under test. */
    private MarkerAwareAppender router;

    @BeforeEach
    void setUp() {
        loggerContext = new LoggerContext();
        loggerContext.setName("test");

        techAppender = new CapturingAppender("TECH");
        techAppender.setContext(loggerContext);
        techAppender.start();

        auditAppender = new CapturingAppender("AUDIT");
        auditAppender.setContext(loggerContext);
        auditAppender.start();

        router = new MarkerAwareAppender();
        router.setName("ROUTER");
        router.setContext(loggerContext);
    }

    // --- Helpers ---

    /**
     * Creates a minimal log event with the given marker attached.
     *
     * @param marker the marker to add to the event, or {@code null} for no marker
     * @return a configured {@link LoggingEvent}
     */
    private LoggingEvent makeEvent(Marker marker) {
        LoggingEvent event = new LoggingEvent();
        event.setLoggerContext(loggerContext);
        event.setLevel(Level.INFO);
        event.setMessage("test");
        event.setLoggerName("test.logger");
        event.setTimeStamp(System.currentTimeMillis());
        if (marker != null) {
            event.addMarker(marker);
        }
        return event;
    }

    /**
     * Configures the router with both TECH and AUDIT appenders, sets the default to TECH, and starts it.
     */
    private void startRouter() {
        router.addAppender(techAppender);
        router.addAppender(auditAppender);
        router.setDefaultAppender("TECH");
        router.start();
        assertTrue(router.isStarted(), "router should have started successfully");
    }

    // --- Routing tests ---

    @Test
    @DisplayName("routes event with AUDIT marker to the AUDIT appender")
    void shouldRouteByMarkerName() {
        startRouter();
        Marker auditMarker = MarkerFactory.getMarker("AUDIT");

        router.doAppend(makeEvent(auditMarker));

        assertEquals(1, auditAppender.events().size(), "AUDIT appender should receive the event");
        assertEquals(0, techAppender.events().size(), "TECH appender should not receive the event");
    }

    @Test
    @DisplayName("routes event with no marker to the default appender")
    void shouldRouteToDefaultWhenNoMarker() {
        startRouter();

        router.doAppend(makeEvent(null));

        assertEquals(1, techAppender.events().size(), "TECH appender should receive the event");
        assertEquals(0, auditAppender.events().size(), "AUDIT appender should not receive the event");
    }

    @Test
    @DisplayName("routes event with unmatched marker to the default appender")
    void shouldRouteToDefaultWhenNoMatch() {
        startRouter();
        Marker unknown = MarkerFactory.getMarker("UNKNOWN_MARKER");

        router.doAppend(makeEvent(unknown));

        assertEquals(1, techAppender.events().size(), "TECH appender should receive the event as default");
        assertEquals(0, auditAppender.events().size(), "AUDIT appender should not receive the event");
    }

    @Test
    @DisplayName("routes event by child marker name via recursive marker hierarchy")
    void shouldRouteByChildMarkerName() {
        startRouter();

        // Parent marker contains the AUDIT child marker
        Marker child = MarkerFactory.getMarker("AUDIT");
        Marker parent = MarkerFactory.getMarker("PARENT_MARKER");
        parent.add(child);

        router.doAppend(makeEvent(parent));

        assertEquals(
                1, auditAppender.events().size(), "AUDIT appender should receive event via recursive marker hierarchy");
        assertEquals(0, techAppender.events().size(), "TECH appender should not receive the event");
    }

    // --- Startup validation tests ---

    @Test
    @DisplayName("fails to start when no defaultAppender is set")
    void shouldFailStartWithNoDefaultAppender() {
        router.addAppender(techAppender);
        // defaultAppender intentionally not set
        router.start();

        assertFalse(router.isStarted(), "router should not start without a default appender");
        assertHasError("requires <defaultAppender>");
    }

    @Test
    @DisplayName("fails to start when defaultAppender name does not match any appender-ref")
    void shouldFailStartWithUnresolvableDefault() {
        router.addAppender(techAppender);
        router.setDefaultAppender("NONEXISTENT");
        router.start();

        assertFalse(router.isStarted(), "router should not start with unresolvable default appender");
        assertHasError("not found among attached appenders");
    }

    @Test
    @DisplayName("fails to start when no appender-refs are configured")
    void shouldFailStartWithNoAppenders() {
        router.setDefaultAppender("TECH");
        // No appenders added
        router.start();

        assertFalse(router.isStarted(), "router should not start without any appender-refs");
        assertHasError("requires at least one appender-ref");
    }

    @Test
    @DisplayName("fails to start when the router is added to itself as an appender-ref")
    void shouldFailStartOnSelfRecursion() {
        router.addAppender(techAppender);
        // Add self as appender-ref — creates infinite recursion
        router.addAppender(router);
        router.setDefaultAppender("TECH");
        router.start();

        assertFalse(router.isStarted(), "router should not start with self-reference in appender-refs");
        assertHasError("self-recursion");
    }

    // --- Assertion helpers ---

    /**
     * Asserts that the router's status list contains an error whose message contains the given substring.
     *
     * @param substring the expected substring in an error status message
     */
    private void assertHasError(String substring) {
        boolean found = loggerContext.getStatusManager().getCopyOfStatusList().stream()
                .filter(s -> s.getEffectiveLevel() == Status.ERROR)
                .anyMatch(s -> s.getMessage() != null && s.getMessage().contains(substring));
        assertTrue(found, "Expected an error status containing: " + substring);
    }

    // --- Inner test double ---

    /**
     * Simple in-memory capturing appender used as a routing target in tests.
     */
    static class CapturingAppender extends AppenderBase<ILoggingEvent> {

        private final List<ILoggingEvent> captured = Collections.synchronizedList(new ArrayList<>());

        /**
         * Creates a new capturing appender with the given name.
         *
         * @param name the appender name, used for marker-based routing lookup
         */
        CapturingAppender(String name) {
            setName(name);
        }

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
