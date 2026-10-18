// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;

/** Captures the events of the module's logger tree and restores the logger on {@link #close()}. */
final class CapturedLogs implements AutoCloseable {

    private final Logger logger;
    private final Level originalLevel;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private CapturedLogs(Logger logger, Level level) {
        this.logger = logger;
        this.originalLevel = logger.getLevel();
        logger.setLevel(level);
        appender.start();
        logger.addAppender(appender);
    }

    /**
     * Starts capturing events at {@code level} and above.
     *
     * @param level the lowest level to capture
     * @return the capture; close it to detach
     */
    static CapturedLogs attach(Level level) {
        return new CapturedLogs((Logger) LoggerFactory.getLogger("dev.vertique.workflow.schema"), level);
    }

    /**
     * The formatted messages of the INFO events whose message mentions the constraint supplement.
     *
     * @return the messages, lower cased
     */
    List<String> supplementLines() {
        return new ArrayList<>(appender.list)
                .stream()
                        .filter(event -> event.getLevel() == Level.INFO)
                        .map(event -> event.getFormattedMessage().toLowerCase(java.util.Locale.ROOT))
                        .filter(message -> message.contains("constraint supplement"))
                        .toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }
}
