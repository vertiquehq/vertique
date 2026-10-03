// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.slf4j.LoggerFactory;

/**
 * Captures the documentation module's log lines for the lifetime of the capture. Attaching sets
 * the module's package logger to {@code DEBUG}; closing detaches the appender and restores the
 * previous level. Events arrive from worker threads, so the captured lines are held in a
 * thread-safe list and each event's message is resolved on the logging thread.
 *
 * <p>The classifiers match the document store's formats: a stored line is an {@code INFO} line
 * {@code The document of application '<app>' at mount '<mount>' is stored (source: ...)}; a
 * comparison line is a {@code DEBUG} line starting {@code Compared the snapshot of application
 * '<app>' at mount '<mount>'}; an assembly line is a {@code DEBUG} line starting {@code Assembled
 * the document of application '<app>' at mount '<mount>'}.
 */
public final class StoreLogCapture implements AutoCloseable {

    /** The package logger of the documentation module. */
    private static final String DOCS_LOGGER = "dev.vertique.rest.openapi.docs";

    private final Logger docsLogger;
    private final Level previousLevel;
    private final CapturingAppender appender;

    private StoreLogCapture(Logger docsLogger, Level previousLevel, CapturingAppender appender) {
        this.docsLogger = docsLogger;
        this.previousLevel = previousLevel;
        this.appender = appender;
    }

    /**
     * Attaches a capturing appender to the documentation module's package logger, remembers the
     * logger's previous level, and sets the level to {@code DEBUG}.
     *
     * @return the started capture; close it to detach the appender and restore the level
     */
    public static StoreLogCapture attach() {
        Logger logger = (Logger) LoggerFactory.getLogger(DOCS_LOGGER);
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        CapturingAppender capturing = new CapturingAppender();
        capturing.start();
        logger.addAppender(capturing);
        return new StoreLogCapture(logger, previous, capturing);
    }

    /**
     * Returns the {@code INFO} lines reporting that the document of the application at the mount
     * was stored, in the order they were logged.
     *
     * @param application the application name
     * @param mountPath the mount path
     * @return a snapshot of the matching messages
     */
    public List<String> storedLines(String application, String mountPath) {
        String keyword = "application '" + application + "' at mount '" + mountPath + "' is stored";
        return appender.messages(Level.INFO, message -> message.contains(keyword));
    }

    /**
     * Returns the {@code DEBUG} lines reporting that a snapshot of the application at the mount
     * was compared with the stored document, in the order they were logged.
     *
     * @param application the application name
     * @param mountPath the mount path
     * @return a snapshot of the matching messages
     */
    public List<String> comparisonLines(String application, String mountPath) {
        String prefix = "Compared the snapshot of application '" + application + "' at mount '" + mountPath + "'";
        return appender.messages(Level.DEBUG, message -> message.startsWith(prefix));
    }

    /**
     * Returns the {@code DEBUG} lines reporting that the document of the application at the mount
     * was assembled, in the order they were logged.
     *
     * @param application the application name
     * @param mountPath the mount path
     * @return a snapshot of the matching messages
     */
    public List<String> assemblyLines(String application, String mountPath) {
        String prefix = "Assembled the document of application '" + application + "' at mount '" + mountPath + "'";
        return appender.messages(Level.DEBUG, message -> message.startsWith(prefix));
    }

    /**
     * Returns every captured message of any level, in the order they were logged.
     *
     * @return a snapshot of all captured messages
     */
    public List<String> allMessages() {
        return appender.messages(null, message -> true);
    }

    /** Detaches the appender and restores the logger's previous level. */
    @Override
    public void close() {
        docsLogger.detachAppender(appender);
        appender.stop();
        docsLogger.setLevel(previousLevel);
    }

    /** Records each event's level and formatted message on the logging thread. */
    private static final class CapturingAppender extends ListAppender<ILoggingEvent> {

        private record Line(Level level, String message) {}

        private final List<Line> captured = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            captured.add(new Line(event.getLevel(), event.getFormattedMessage()));
        }

        List<String> messages(Level level, Predicate<String> filter) {
            return captured.stream()
                    .filter(line -> level == null || line.level() == level)
                    .map(Line::message)
                    .filter(filter)
                    .toList();
        }
    }
}
