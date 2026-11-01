// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * Records, in memory, every log record the framework's own loggers write, so that a proof can show that no key, header
 * value or payload value reaches a log. The module's test class path carries no logging backend, so this provider is
 * the backend of the test JVM. Only loggers under {@code dev.vertique} record; the others discard.
 *
 * <p>A record is kept as text: the formatted message followed by the class and message of the throwable and of each of
 * its causes. Read the records through {@link #mark()} and {@link #since(int)}.
 */
public final class RecordingLoggerProvider implements SLF4JServiceProvider {

    private static final List<String> RECORDS = new ArrayList<>();

    private final ILoggerFactory loggers = RecordingLogger::new;
    private final IMarkerFactory markers = new BasicMarkerFactory();
    private final MDCAdapter mdc = new NOPMDCAdapter();

    /**
     * Returns the position after the last record written so far.
     *
     * @return a position to hand to {@link #since(int)}
     */
    public static int mark() {
        synchronized (RECORDS) {
            return RECORDS.size();
        }
    }

    /**
     * Returns the records written after the position.
     *
     * @param mark a position {@link #mark()} returned
     * @return the records, oldest first
     */
    public static List<String> since(int mark) {
        synchronized (RECORDS) {
            return List.copyOf(RECORDS.subList(Math.min(mark, RECORDS.size()), RECORDS.size()));
        }
    }

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggers;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markers;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdc;
    }

    @Override
    public String getRequestedApiVersion() {
        return "2.0.99";
    }

    @Override
    public void initialize() {
        // nothing to prepare
    }

    private static final class RecordingLogger extends LegacyAbstractLogger {

        private static final long serialVersionUID = 1L;

        private final boolean recording;

        RecordingLogger(String name) {
            this.name = name;
            this.recording = name.startsWith("dev.vertique");
        }

        @Override
        public boolean isTraceEnabled() {
            return recording;
        }

        @Override
        public boolean isDebugEnabled() {
            return recording;
        }

        @Override
        public boolean isInfoEnabled() {
            return recording;
        }

        @Override
        public boolean isWarnEnabled() {
            return recording;
        }

        @Override
        public boolean isErrorEnabled() {
            return recording;
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return null;
        }

        @Override
        protected void handleNormalizedLoggingCall(
                Level level, Marker marker, String messagePattern, Object[] arguments, Throwable throwable) {
            StringBuilder text = new StringBuilder(MessageFormatter.arrayFormat(messagePattern, arguments, throwable)
                    .getMessage());
            Throwable cause = throwable;
            for (int depth = 0; cause != null && depth < 16; depth++) {
                text.append(" | ")
                        .append(cause.getClass().getName())
                        .append(": ")
                        .append(cause.getMessage());
                cause = cause.getCause() == cause ? null : cause.getCause();
            }
            synchronized (RECORDS) {
                RECORDS.add(text.toString());
            }
        }
    }
}
