// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.source;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link ConfigPropertySources#closeAllReverse(List)}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Sources are closed in reverse declaration order.</li>
 *   <li>A throwing source close does not prevent remaining sources from being closed.</li>
 *   <li>A source that throws {@link Error} (not just {@link Exception}) does not prevent
 *       remaining sources from being closed.</li>
 *   <li>Empty list is handled without error.</li>
 *   <li>Close failures are logged at ERROR with source name + exception class simple name only
 *       (no message text, no stack trace at any level — the provider message is untrusted).</li>
 * </ul>
 */
class ConfigPropertySourcesTest {

    // --- Fixtures ---

    /** Simple {@link ConfigPropertySource} stub that records its close call order. */
    private static final class RecordingSource implements ConfigPropertySource {

        private final String sourceName;
        private final List<String> closeOrder;
        private final Throwable throwOnClose;

        RecordingSource(String sourceName, List<String> closeOrder, Throwable throwOnClose) {
            this.sourceName = sourceName;
            this.closeOrder = closeOrder;
            this.throwOnClose = throwOnClose;
        }

        RecordingSource(String sourceName, List<String> closeOrder) {
            this(sourceName, closeOrder, null);
        }

        @Override
        public String name() {
            return sourceName;
        }

        @Override
        public Optional<String> lookup(String key) {
            return Optional.empty();
        }

        @Override
        public void close() {
            closeOrder.add(sourceName);
            if (throwOnClose instanceof RuntimeException re) {
                throw re;
            }
            if (throwOnClose instanceof Error e) {
                throw e;
            }
        }
    }

    // --- Tests ---

    @Nested
    @DisplayName("closeAllReverse: sources are closed in reverse declaration order")
    class ReverseOrderTests {

        @Test
        @DisplayName("three sources → closed in reverse declaration order (s3, s2, s1)")
        void reverseOrder() {
            List<String> closeOrder = new ArrayList<>();
            List<ConfigPropertySource> sources = List.of(
                    new RecordingSource("s1", closeOrder),
                    new RecordingSource("s2", closeOrder),
                    new RecordingSource("s3", closeOrder));

            ConfigPropertySources.closeAllReverse(sources);

            assertEquals(List.of("s3", "s2", "s1"), closeOrder, "must close in reverse declaration order");
        }

        @Test
        @DisplayName("empty list → no-op; no exception thrown")
        void emptyListIsNoOp() {
            assertDoesNotThrow(() -> ConfigPropertySources.closeAllReverse(List.of()));
        }
    }

    @Nested
    @DisplayName("closeAllReverse: throwing close does not prevent remaining sources from closing")
    class ThrowingCloseTests {

        @Test
        @DisplayName("RuntimeException from s2 → s1 still closed; no exception propagated")
        void runtimeExceptionInMiddleSourceContinues() {
            List<String> closeOrder = new ArrayList<>();
            List<ConfigPropertySource> sources = List.of(
                    new RecordingSource("s1", closeOrder),
                    new RecordingSource("s2-throws", closeOrder, new RuntimeException("close failure")),
                    new RecordingSource("s3", closeOrder));

            // Must not propagate any exception
            assertDoesNotThrow(() -> ConfigPropertySources.closeAllReverse(sources));

            // All three must have been attempted
            assertEquals(
                    List.of("s3", "s2-throws", "s1"), closeOrder, "all sources must be closed despite s2 throwing");
        }

        @Test
        @DisplayName("Error from s2 → s1 still closed; Error does not propagate")
        void errorInMiddleSourceContinues() {
            List<String> closeOrder = new ArrayList<>();
            List<ConfigPropertySource> sources = List.of(
                    new RecordingSource("s1", closeOrder),
                    new RecordingSource("s2-error", closeOrder, new Error("close error")),
                    new RecordingSource("s3", closeOrder));

            // An Error from close() must be caught and not rethrown
            assertDoesNotThrow(() -> ConfigPropertySources.closeAllReverse(sources));

            assertEquals(
                    List.of("s3", "s2-error", "s1"),
                    closeOrder,
                    "all sources must be closed despite s2 throwing Error");
        }
    }

    // --- Log shape for close failures (item 7) ---

    @Nested
    @DisplayName("closeAllReverse: close failure log shape")
    class CloseFailureLogShape {

        private ListAppender<ILoggingEvent> appender;
        private Logger capturedLogger;
        private Level savedLevel;

        /** Attaches a list appender to the {@link ConfigPropertySources} logger. */
        private void attachLog() {
            capturedLogger = (Logger) LoggerFactory.getLogger(ConfigPropertySources.class);
            savedLevel = capturedLogger.getLevel();
            appender = new ListAppender<>();
            appender.start();
            capturedLogger.addAppender(appender);
            capturedLogger.setLevel(Level.ALL);
        }

        /** Detaches the appender and restores the original level after each test. */
        @AfterEach
        void detachLog() {
            if (capturedLogger != null && appender != null) {
                capturedLogger.detachAppender(appender);
                capturedLogger.setLevel(savedLevel);
            }
        }

        @Test
        @DisplayName("ERROR log contains source name and exception simple class name — NOT the exception message")
        void errorLogContainsNameAndClassOnly() {
            attachLog();

            List<String> closeOrder = new ArrayList<>();
            RuntimeException closeEx = new RuntimeException("disk full");
            List<ConfigPropertySource> sources = List.of(new RecordingSource("my-source", closeOrder, closeEx));

            assertDoesNotThrow(() -> ConfigPropertySources.closeAllReverse(sources));

            List<ILoggingEvent> errorLogs = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR)
                    .toList();

            assertEquals(1, errorLogs.size(), "exactly one ERROR log expected");
            ILoggingEvent errorEvent = errorLogs.get(0);
            String msg = errorEvent.getFormattedMessage();

            assertTrue(msg.contains("my-source"), "ERROR log must contain source name; got: " + msg);
            assertTrue(
                    msg.contains("RuntimeException"),
                    "ERROR log must contain exception simple class name; got: " + msg);
            // The exception message (third-party text) must NOT appear in the ERROR line
            assertFalse(
                    msg.contains("disk full"),
                    "ERROR log must NOT contain exception message (third-party text); got: " + msg);

            // The ERROR event must NOT carry a throwable (no stack trace at ERROR level)
            assertFalse(
                    errorEvent.getThrowableProxy() != null,
                    "ERROR log must NOT attach a throwable (no stack trace at ERROR level)");
        }

        @Test
        @DisplayName("provider exception message and stack trace do NOT appear in captured output at any level")
        void providerMessageAndStackAbsentAtAllLevels() {
            attachLog();

            List<String> closeOrder = new ArrayList<>();
            RuntimeException closeEx = new RuntimeException("connection closed");
            List<ConfigPropertySource> sources = List.of(new RecordingSource("debug-src", closeOrder, closeEx));

            assertDoesNotThrow(() -> ConfigPropertySources.closeAllReverse(sources));

            // The provider's exception message must NOT appear in any log event at any level.
            // Third-party close() message text is untrusted and potentially log-unsafe; the
            // framework emits only source name + exception class simple name.
            String allMessages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertFalse(
                    allMessages.contains("connection closed"),
                    "provider exception message must NOT appear in captured output at any level; got: " + allMessages);

            // No log event at any level must attach a throwable (no stack trace anywhere).
            boolean anyWithThrowable = appender.list.stream().anyMatch(e -> e.getThrowableProxy() != null);
            assertFalse(anyWithThrowable, "provider stack trace must NOT appear in captured output at any level");
        }

        @Test
        @DisplayName("log messages at ERROR level do not contain stack trace frames")
        void errorLogDoesNotContainStackFrames() {
            attachLog();

            List<String> closeOrder = new ArrayList<>();
            RuntimeException closeEx = new RuntimeException("io error");
            List<ConfigPropertySource> sources = List.of(new RecordingSource("frame-src", closeOrder, closeEx));

            assertDoesNotThrow(() -> ConfigPropertySources.closeAllReverse(sources));

            String errorMessages = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR)
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));

            // Stack frame lines start with "at " — they must not appear in the ERROR message
            assertFalse(
                    errorMessages.contains("\tat "),
                    "ERROR log message must not contain stack trace frames; got: " + errorMessages);
        }
    }
}
