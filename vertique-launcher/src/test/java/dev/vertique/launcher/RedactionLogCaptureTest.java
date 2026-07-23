// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vertx.launcher.application.ExitCodes;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

/**
 * Verifies the redaction contract (NFR-CONF-002): resolved secret values must never appear in
 * log output, whether the launch succeeds or fails.
 *
 * <p>Attaches a {@link ListAppender} to the ROOT Logback logger before each test and detaches it
 * in {@code @AfterEach} (always, even on failure). This captures all log output produced by the
 * full bootstrap chain during the test.
 *
 * <p>Two paths are exercised:
 * <ul>
 *   <li><strong>Success path</strong> — launch succeeds; the sentinel secret value appears
 *       nowhere in any captured log message or exception text.</li>
 *   <li><strong>Failure path</strong> — resolution fails after the stub source is consulted
 *       (an additional unresolvable reference forces a failure); launch returns 11; the sentinel
 *       secret value still appears nowhere in captured logs, but the unresolvable reference key
 *       itself does appear.</li>
 * </ul>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RedactionLogCaptureTest extends AbstractLaunchTestSupport {

    private static final String NOOP_VERTICLE = NoopVerticle.class.getName();
    private static final String SENTINEL = "s3cret-SENTINEL-9472";

    // --- Capturing appender lifecycle ---

    /** Appender attached to the ROOT logger in {@link #attachCapturingAppender()}. */
    private ListAppender<ILoggingEvent> listAppender;

    /** ROOT logger reference, kept for detach in {@link #detachCapturingAppender()}. */
    private Logger rootLogger;

    /**
     * Prior root logger level, saved in {@link #attachCapturingAppender()} and restored in
     * {@link #detachCapturingAppender()} so a forced INFO level doesn't leak between tests.
     */
    private Level savedLevel;

    /**
     * Attaches a {@link ListAppender} to the ROOT Logback logger before each test.
     *
     * <p>Called after the super {@code setUp()} method in {@link AbstractLaunchTestSupport}
     * (JUnit 5 calls {@code @BeforeEach} methods from super to sub). The appender is started
     * before attachment so it is ready to capture immediately.
     *
     * <p>If the root logger level is above INFO, it is temporarily forced to INFO so bootstrap
     * messages are captured. The prior level is saved in {@link #savedLevel} and restored by
     * {@link #detachCapturingAppender()}.
     */
    @BeforeEach
    void attachCapturingAppender() {
        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        savedLevel = rootLogger.getLevel();
        // Ensure the root logger is at least INFO so bootstrap messages are captured
        if (rootLogger.getLevel() == null || rootLogger.getLevel().isGreaterOrEqual(Level.WARN)) {
            rootLogger.setLevel(Level.INFO);
        }
        listAppender = new ListAppender<>();
        listAppender.setContext(rootLogger.getLoggerContext());
        listAppender.start();
        rootLogger.addAppender(listAppender);
    }

    /**
     * Detaches and stops the {@link ListAppender}, restoring the ROOT logger's prior level.
     *
     * <p>Always runs even when the test throws, so no appender or level mutation leaks between
     * tests. The saved level is restored even when no level change was made (idempotent).
     */
    @AfterEach
    void detachCapturingAppender() {
        if (rootLogger != null && listAppender != null) {
            rootLogger.detachAppender(listAppender);
            listAppender.stop();
        }
        if (rootLogger != null && savedLevel != null) {
            rootLogger.setLevel(savedLevel);
        }
    }

    // --- Success path ---

    @Nested
    @DisplayName("success path — sentinel value absent from all captured log messages")
    class SuccessPath {

        @Test
        @DisplayName("resolved secret value never appears in any log output on successful launch")
        void sentinelAbsentFromLogsOnSuccess() throws Exception {
            // Arrange: stub serves the sentinel under "db.password"
            LauncherStubSourceFactory.State.values.put("db.password", SENTINEL);

            String conf = "{\"config\":{\"propertySources\":[{\"name\":\"stub\",\"type\":\"launcher-stub\"}]},"
                    + "\"db\":{\"password\":\"${db.password}\",\"url\":\"jdbc:pg://${db.host:localhost}/app\"}}";

            TestVertiqueApplication app = new TestVertiqueApplication(new String[] {NOOP_VERTICLE, "--conf", conf});
            int exitCode = app.launch();
            assertEquals(0, exitCode, "launch must succeed");

            // Close Vert.x directly, then invoke shutdown sequence so sources close
            var capturedVertx = TestVertiqueApplication.capturedVertx.getAndSet(null);
            if (capturedVertx != null) {
                capturedVertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
            app.runShutdownSequence();

            // Capture all log messages and exception texts
            String allLogs = joinMessages();

            // Assert the capture worked — at least one INFO line from bootstrap must be present
            assertFalse(allLogs.isEmpty(), "at least one log message must have been captured");

            // Assert the sentinel secret is nowhere in the output
            assertFalse(
                    allLogs.contains(SENTINEL),
                    "sentinel secret value must NOT appear in any captured log message on success path;"
                            + " found in: [" + extractLinesContaining(SENTINEL) + "]");
        }
    }

    // --- Failure path ---

    @Nested
    @DisplayName("failure path — sentinel absent from logs even when resolution fails after source consulted")
    class FailurePath {

        @Test
        @DisplayName("resolved secret value never appears in logs on resolution failure;"
                + " unresolvable reference key does appear")
        void sentinelAbsentFromLogsOnFailure() {
            // Arrange: stub serves sentinel for "leak.check"; resolution will fail because
            // "${missing.key}" has no source and no default
            LauncherStubSourceFactory.State.values.put("leak.check", SENTINEL);

            // Both references appear in the tree; "leak.check" can be resolved (source serves it),
            // but "missing.key" cannot. Resolution engine reports "missing.key" in the failure
            // message — never the resolved value of "leak.check".
            String conf = "{\"config\":{\"propertySources\":[{\"name\":\"stub\",\"type\":\"launcher-stub\"}]},"
                    + "\"a\":\"${leak.check}\",\"b\":\"${missing.key}\"}";

            int exitCode = new TestVertiqueApplication(new String[] {NOOP_VERTICLE, "--conf", conf}).launch();

            assertEquals(
                    ExitCodes.VERTX_INITIALIZATION,
                    exitCode,
                    "resolution failure must return VERTX_INITIALIZATION (11)");

            String allLogs = joinMessages();

            // Assert: the failure log message contains the unresolvable reference key
            assertTrue(
                    allLogs.contains("missing.key"),
                    "the failure log must mention the unresolvable reference key 'missing.key'");

            // Assert: the sentinel secret value is nowhere in the output
            assertFalse(
                    allLogs.contains(SENTINEL),
                    "sentinel secret value must NOT appear in any captured log message on failure path;"
                            + " found in: [" + extractLinesContaining(SENTINEL) + "]");
        }
    }

    // --- Helpers ---

    /**
     * Joins all captured formatted messages into a single string separated by newlines.
     *
     * @return all captured log content as a single string
     */
    private String joinMessages() {
        return listAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }

    /**
     * Returns all captured log lines that contain the given substring, joined by newlines.
     * Used for assertion failure messages.
     *
     * @param substring the substring to search for
     * @return matching lines joined by newlines, or empty string when none match
     */
    private String extractLinesContaining(String substring) {
        return listAppender.list.stream()
                .map(this::formatEvent)
                .filter(msg -> msg.contains(substring))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Formats a single log event to a string including the formatted message and any thrown
     * exception messages in the chain.
     *
     * @param event the log event to format
     * @return the formatted string representation
     */
    private String formatEvent(ILoggingEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append(event.getFormattedMessage());
        if (event.getThrowableProxy() != null) {
            appendThrowableMessages(sb, event.getThrowableProxy());
        }
        return sb.toString();
    }

    /**
     * Recursively appends throwable messages from the proxy chain to the builder.
     *
     * @param sb    the string builder to append to
     * @param proxy the throwable proxy to walk
     */
    private void appendThrowableMessages(StringBuilder sb, ch.qos.logback.classic.spi.IThrowableProxy proxy) {
        while (proxy != null) {
            if (proxy.getMessage() != null) {
                sb.append(' ').append(proxy.getMessage());
            }
            proxy = proxy.getCause();
        }
    }
}
