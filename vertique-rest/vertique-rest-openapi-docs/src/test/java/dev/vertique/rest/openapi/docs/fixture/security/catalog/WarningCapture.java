// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.catalog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Captures the events of one Logback logger through a {@link ListAppender}. {@link #attach(String)}
 * lowers the logger to {@code DEBUG} and adds the appender; {@link #detach()} removes the appender
 * and restores the logger's previous level. Reads and clears are synchronized on the appender, since
 * events arrive from event-loop threads.
 */
public final class WarningCapture {

    private final Logger logger;
    private final Level previousLevel;
    private final ListAppender<ILoggingEvent> appender;

    private WarningCapture(Logger logger, Level previousLevel, ListAppender<ILoggingEvent> appender) {
        this.logger = logger;
        this.previousLevel = previousLevel;
        this.appender = appender;
    }

    /**
     * Starts capturing a logger's events.
     *
     * @param loggerName the logger's name
     * @return the attached capture
     */
    public static WarningCapture attach(String loggerName) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new WarningCapture(logger, previousLevel, appender);
    }

    /** Stops capturing: detaches and stops the appender, then restores the logger's previous level. */
    public void detach() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    /** Forgets every event captured so far. */
    public void clear() {
        synchronized (appender) {
            appender.list.clear();
        }
    }

    /**
     * Returns the formatted messages of the {@code WARN} events captured so far, in capture order.
     *
     * @return the messages
     */
    public List<String> warnings() {
        List<ILoggingEvent> events;
        synchronized (appender) {
            events = List.copyOf(appender.list);
        }
        return events.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
