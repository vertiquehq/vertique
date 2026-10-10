// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Tests for {@link ObserverFailureReporter}: the limited-rate error report of a {@link LinkageError}
 * and the class-name-only warning for every other failure. The reporter reads the time from a
 * counter the test advances.
 */
@DisplayName("ObserverFailureReporter")
class ObserverFailureReporterTest {

    private static final long INTERVAL_NANOS = ObserverFailureReporter.REPORT_INTERVAL.toNanos();

    /** Stand-in observer classes; only their identity matters. */
    private static final class FirstObserver {}

    private static final class SecondObserver {}

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private final AtomicLong now = new AtomicLong(Long.MAX_VALUE - 10);
    private ObserverFailureReporter reporter;

    @BeforeEach
    void captureLog() {
        logger = (Logger) LoggerFactory.getLogger("ObserverFailureReporterTest.site");
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        reporter = new ObserverFailureReporter(logger, "Test observer", now::get);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(appender);
        logger.setLevel(null);
        appender.stop();
    }

    @Nested
    @DisplayName("a LinkageError")
    class Linkage {

        @Test
        @DisplayName("is reported at ERROR with the throwable the first time")
        void firstFailureIsReported() {
            NoClassDefFoundError failure = new NoClassDefFoundError("com/example/Missing");

            reporter.report(FirstObserver.class, "onEvent", failure);

            List<ILoggingEvent> errors = eventsAt(Level.ERROR);
            assertEquals(1, errors.size());
            String message = errors.get(0).getFormattedMessage();
            assertTrue(message.startsWith("Test observer " + FirstObserver.class.getName()), message);
            assertTrue(message.contains("callback onEvent is unusable"), message);
            assertTrue(message.contains("; 0 further failure(s)"), message);
            assertNotNull(errors.get(0).getThrowableProxy(), "the throwable is attached to the report");
            assertEquals(
                    NoClassDefFoundError.class.getName(),
                    errors.get(0).getThrowableProxy().getClassName());
            assertTrue(eventsAt(Level.WARN).isEmpty(), "a linkage failure is not also logged at WARN");
        }

        @Test
        @DisplayName("is suppressed while the report interval has not passed")
        void failuresWithinTheIntervalAreSuppressed() {
            reporter.report(FirstObserver.class, "onEvent", new NoClassDefFoundError("a"));
            now.addAndGet(INTERVAL_NANOS - 1);
            reporter.report(FirstObserver.class, "onEvent", new NoClassDefFoundError("b"));
            reporter.report(FirstObserver.class, "onEvent", new NoClassDefFoundError("c"));

            assertEquals(1, eventsAt(Level.ERROR).size(), "only the first failure is reported");
            assertEquals(1, appender.list.size(), "a suppressed failure logs nothing at any level");
        }

        @Test
        @DisplayName("is reported again once the interval has passed, with the suppressed count")
        void failureAfterTheIntervalIsReportedWithTheSuppressedCount() {
            reporter.report(FirstObserver.class, "onEvent", new NoClassDefFoundError("a"));
            reporter.report(FirstObserver.class, "onEvent", new NoClassDefFoundError("b"));
            reporter.report(FirstObserver.class, "onEvent", new NoClassDefFoundError("c"));
            now.addAndGet(INTERVAL_NANOS);
            reporter.report(FirstObserver.class, "onEvent", new NoClassDefFoundError("d"));

            List<ILoggingEvent> errors = eventsAt(Level.ERROR);
            assertEquals(2, errors.size(), "the failure after the interval is reported");
            assertTrue(
                    errors.get(1).getFormattedMessage().contains("; 2 further failure(s)"),
                    errors.get(1).getFormattedMessage());
            assertNotNull(errors.get(1).getThrowableProxy());

            // The count starts again after a report.
            reporter.report(FirstObserver.class, "onEvent", new NoClassDefFoundError("e"));
            now.addAndGet(INTERVAL_NANOS);
            reporter.report(FirstObserver.class, "onEvent", new NoClassDefFoundError("f"));
            assertTrue(
                    eventsAt(Level.ERROR).get(2).getFormattedMessage().contains("; 1 further failure(s)"),
                    eventsAt(Level.ERROR).get(2).getFormattedMessage());
        }

        @Test
        @DisplayName("is throttled per observer class and callback independently")
        void pairsAreIndependent() {
            reporter.report(FirstObserver.class, "onEvent", new NoClassDefFoundError("a"));
            reporter.report(FirstObserver.class, "onOther", new NoClassDefFoundError("b"));
            reporter.report(SecondObserver.class, "onEvent", new NoClassDefFoundError("c"));
            reporter.report(FirstObserver.class, "onEvent", new NoClassDefFoundError("d"));

            List<String> reports = eventsAt(Level.ERROR).stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertEquals(3, reports.size(), "each pair is reported once; the repeat is suppressed");
            assertTrue(reports.get(0).contains(FirstObserver.class.getName() + " callback onEvent"), reports.get(0));
            assertTrue(reports.get(1).contains(FirstObserver.class.getName() + " callback onOther"), reports.get(1));
            assertTrue(reports.get(2).contains(SecondObserver.class.getName() + " callback onEvent"), reports.get(2));
        }

        @Test
        @DisplayName("is still reported at the limited rate for pairs beyond the tracked maximum")
        void pairsBeyondTheMaximumShareOneThrottle() {
            for (int i = 0; i < ObserverFailureReporter.MAX_TRACKED_CALLBACKS; i++) {
                reporter.report(FirstObserver.class, "callback-" + i, new NoClassDefFoundError("x"));
            }
            assertEquals(
                    ObserverFailureReporter.MAX_TRACKED_CALLBACKS,
                    eventsAt(Level.ERROR).size());

            reporter.report(SecondObserver.class, "overflow-1", new NoClassDefFoundError("x"));
            reporter.report(SecondObserver.class, "overflow-2", new NoClassDefFoundError("x"));
            assertEquals(
                    ObserverFailureReporter.MAX_TRACKED_CALLBACKS + 1,
                    eventsAt(Level.ERROR).size(),
                    "pairs beyond the maximum share one throttle");

            now.addAndGet(INTERVAL_NANOS);
            reporter.report(SecondObserver.class, "overflow-3", new NoClassDefFoundError("x"));
            assertEquals(
                    ObserverFailureReporter.MAX_TRACKED_CALLBACKS + 2,
                    eventsAt(Level.ERROR).size(),
                    "the shared throttle reports again after the interval");
        }
    }

    @Nested
    @DisplayName("any other failure")
    class Other {

        @Test
        @DisplayName("an Exception is logged at WARN by class name; its message and stack only at DEBUG")
        void exceptionIsLoggedByClassNameOnly() {
            assertClassNameOnlyWarning(new IllegalStateException("payload: secret-text"));
        }

        @Test
        @DisplayName("an AssertionError is logged at WARN by class name; its message and stack only at DEBUG")
        void assertionErrorIsLoggedByClassNameOnly() {
            assertClassNameOnlyWarning(new AssertionError("payload: secret-text"));
        }

        @Test
        @DisplayName("a StackOverflowError is logged at WARN by class name; its message and stack only at DEBUG")
        void stackOverflowErrorIsLoggedByClassNameOnly() {
            assertClassNameOnlyWarning(new StackOverflowError("payload: secret-text"));
        }

        @Test
        @DisplayName("is logged at WARN every time, with nothing at DEBUG when DEBUG is off")
        void everyFailureIsLoggedAndDebugCanBeOff() {
            logger.setLevel(Level.INFO);

            reporter.report(FirstObserver.class, "onEvent", new IllegalStateException("one"));
            reporter.report(FirstObserver.class, "onEvent", new IllegalStateException("two"));

            assertEquals(2, eventsAt(Level.WARN).size(), "a non-linkage failure is never suppressed");
            assertEquals(2, appender.list.size(), "nothing but the two warnings is logged");
        }

        private void assertClassNameOnlyWarning(Throwable failure) {
            reporter.report(FirstObserver.class, "onEvent", failure);

            List<ILoggingEvent> warnings = eventsAt(Level.WARN);
            assertEquals(1, warnings.size());
            String message = warnings.get(0).getFormattedMessage();
            assertTrue(message.startsWith("Test observer " + FirstObserver.class.getName()), message);
            assertTrue(message.contains("callback onEvent"), message);
            assertTrue(message.contains(failure.getClass().getName()), message);
            assertFalse(message.contains("secret-text"), "the failure's message is not on the WARN line: " + message);
            assertNull(warnings.get(0).getThrowableProxy(), "the throwable is not attached at WARN");

            List<ILoggingEvent> details = eventsAt(Level.DEBUG);
            assertEquals(1, details.size(), "the throwable is logged at DEBUG");
            assertNotNull(details.get(0).getThrowableProxy());
            assertEquals(
                    "payload: secret-text", details.get(0).getThrowableProxy().getMessage());
            assertTrue(eventsAt(Level.ERROR).isEmpty());
        }
    }

    private List<ILoggingEvent> eventsAt(Level level) {
        return appender.list.stream().filter(event -> event.getLevel() == level).toList();
    }
}
