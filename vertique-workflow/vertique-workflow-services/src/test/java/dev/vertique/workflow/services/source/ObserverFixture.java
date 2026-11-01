// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.extension.ObserverFailureReporter;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.source.SignalSourceEvent;
import dev.vertique.workflow.source.SignalSourceObserver;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.LoggerFactory;

/**
 * Shared setup of the observer proofs: a delivery bean over inert collaborators, in which a
 * mocked {@link ObserverFailureReporter} records what the bean hands it, and a capture of every log
 * record written while a proof runs.
 */
final class ObserverFixture {

    /** The bean under test and the reporter it was given. */
    record Built(WorkflowSignalSourceDelivery delivery, ObserverFailureReporter reporter) {}

    private ObserverFixture() {}

    /** Builds the bean with the observers, in the order given, and a reporter that records its calls. */
    static Built deliveryWith(SignalSourceObserver... observers) {
        ObserverFailureReporter reporter = mock(ObserverFailureReporter.class);
        Set<SignalSourceObserver> set = new LinkedHashSet<>(List.of(observers));
        @SuppressWarnings("unchecked")
        TransactionalWorkflowOperations<SqlClient> operations = mock(TransactionalWorkflowOperations.class);
        WorkflowSignalSourceDelivery delivery = new WorkflowSignalSourceDelivery(
                () -> operations,
                new FakeInbox(),
                mock(Pool.class),
                mock(Vertx.class),
                DeliveryFixture.scopeOver(new DefaultContextHolder()),
                set,
                reporter);
        return new Built(delivery, reporter);
    }

    static SignalSourceEvent recorded() {
        return new SignalSourceEvent(
                "carriers", "kafka", "orders", "shipment-delivered", "order-flow", SignalSourceEvent.Outcome.RECORDED);
    }

    /** An observer that remembers every event it is told about. */
    static final class RecordingObserver implements SignalSourceObserver {
        private final List<SignalSourceEvent> events = new ArrayList<>();

        @Override
        public synchronized void onOutcome(SignalSourceEvent event) {
            events.add(event);
        }

        synchronized List<SignalSourceEvent> events() {
            return List.copyOf(events);
        }
    }

    /** An observer that throws the one throwable it was given, every time. */
    static final class ThrowingObserver implements SignalSourceObserver {
        private final Throwable failure;

        ThrowingObserver(Throwable failure) {
            this.failure = failure;
        }

        Throwable failure() {
            return failure;
        }

        @Override
        public void onOutcome(SignalSourceEvent event) {
            ThrowingObserver.<RuntimeException>sneakyThrow(failure);
        }

        @SuppressWarnings("unchecked")
        private static <E extends Throwable> void sneakyThrow(Throwable failure) throws E {
            throw (E) failure;
        }
    }

    /** Collects every log record written anywhere while it is attached. */
    static final class LogCapture implements AutoCloseable {
        private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        private final Level previousLevel = root.getLevel();

        LogCapture() {
            appender.start();
            root.setLevel(Level.TRACE);
            root.addAppender(appender);
        }

        /** Every captured record, as the text a reader of the log would see, including any throwable. */
        List<String> records() {
            List<String> texts = new ArrayList<>();
            for (ILoggingEvent event : List.copyOf(appender.list)) {
                StringBuilder text = new StringBuilder(event.getFormattedMessage());
                for (Object argument : event.getArgumentArray() == null ? new Object[0] : event.getArgumentArray()) {
                    text.append(' ').append(argument);
                }
                if (event.getThrowableProxy() != null) {
                    text.append(' ').append(event.getThrowableProxy().getMessage());
                }
                texts.add(text.toString());
            }
            return texts;
        }

        /** Writes a record through the logging system, to show that the capture sees what is written. */
        void probe(String text) {
            LoggerFactory.getLogger(ObserverFixture.class).info(text);
        }

        @Override
        public void close() {
            root.detachAppender(appender);
            root.setLevel(previousLevel);
            appender.stop();
        }
    }
}
