// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.sleep;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.startOf;
import static dev.vertique.workflow.postgresql.executor.FailingTransactions.connectionFailure;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.executor.WorkflowExecutorEvent;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.CommandSettled;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.QueueSnapshot;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.TransientRetry;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that the executor reports settled, retried and dead-lettered commands to its observers, that no event
 * carries an instance or a payload, and that an observer that throws changes no outcome.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ExecutorObserverIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_executor_observer_it")
            .withMigration("classpath:db/migration/workflow");

    private static final int LEASE_SECONDS = 5;

    @Test
    @DisplayName("observers receive settled, retried and dead-lettered events")
    void observersReceiveSettledRetriedAndDeadLetteredEvents() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given a recording observer, an observer that throws on every event, and a single attempt
            RecordingExecutorObserver recording = new RecordingExecutorObserver();
            ThrowingExecutorObserver throwing = new ThrowingExecutorObserver();
            CommandFaults faults = new CommandFaults();
            Node node = fixture.node("engine")
                    .leaseSeconds(LEASE_SECONDS)
                    .maxAttempts(1)
                    .transientBackoffMs(10, 50)
                    .observers(recording, throwing)
                    .commands(faults.decorator())
                    .build();

            // And one start that completes, one that fails transiently twice and then completes, and one whose every
            // segment is abandoned as a crash would
            WorkflowInstanceId completes = node.record(startOf("completes"));
            WorkflowInstanceId flaky = node.record(startOf("flaky"));
            WorkflowInstanceId crashes = node.record(startOf("crashes"));
            faults.failSegmentsOf(flaky, 2, connectionFailure());
            faults.crashEverySegmentOf(crashes);

            // When the executor runs until idle
            node.startExecutor();
            awaitCondition(
                    "the starts to settle",
                    Duration.ofSeconds(40),
                    () -> fixture.commandOf(completes).state().equals("COMPLETE")
                            && fixture.commandOf(flaky).state().equals("COMPLETE")
                            && fixture.commandOf(crashes).state().equals("DEAD"));
            awaitCondition(
                    "a queue snapshot that counts the dead-lettered command", IDLE, () -> recording.events().stream()
                            .anyMatch(
                                    event -> event instanceof QueueSnapshot snapshot && snapshot.deadLettered() == 1));
            node.stopExecutor();
            sleep(300);

            // Then the observer that throws changed no outcome
            assertThat(fixture.instanceOf(completes).status()).isEqualTo("WAITING");
            assertThat(fixture.commandOf(completes).attempts()).isEqualTo(1);
            assertThat(fixture.commandOf(flaky).attempts()).isEqualTo(1);
            assertThat(fixture.instanceOf(crashes).status()).isEqualTo("FAILED");

            // And the recording observer saw two completed starts, both retries, and the dead letter
            List<WorkflowExecutorEvent> events = recording.events();
            assertThat(events.stream()
                            .filter(event -> event instanceof CommandSettled settled
                                    && settled.kind().equals("START")
                                    && settled.outcome().equals("COMPLETE"))
                            .count())
                    .isEqualTo(2);
            assertThat(events.stream()
                            .filter(event -> event instanceof TransientRetry retry
                                    && retry.kind().equals("START"))
                            .map(event -> ((TransientRetry) event).transientCount()))
                    .containsExactly(1, 2);
            assertThat(events.stream()
                            .filter(event -> event instanceof CommandSettled settled
                                    && settled.kind().equals("START")
                                    && settled.outcome().equals("DEAD_LETTERED"))
                            .count())
                    .isEqualTo(1);
            assertThat(events.stream()
                            .filter(event -> event instanceof QueueSnapshot snapshot && snapshot.deadLettered() == 1))
                    .isNotEmpty();

            // And no event carries an instance id or a payload
            for (WorkflowExecutorEvent event : events) {
                String text = event.toString();
                for (WorkflowInstanceId id : List.of(completes, flaky, crashes)) {
                    assertThat(text).doesNotContain(id.value().toString());
                }
                assertThat(text).doesNotContain("completes", "flaky", "crashes");
            }

            // And every call of the throwing observer was caught and logged at WARN, naming the observer class
            assertThat(throwing.calls()).isEqualTo(events.size());
            List<String> warnings = fixture.logged(Level.WARN).stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains(ThrowingExecutorObserver.class.getSimpleName()))
                    .toList();
            assertThat(warnings).hasSize(throwing.calls());
        }
    }

    /** Collects the events it receives, in order. */
    static final class RecordingExecutorObserver implements WorkflowExecutorObserver {
        private final List<WorkflowExecutorEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onEvent(WorkflowExecutorEvent event) {
            events.add(event);
        }

        List<WorkflowExecutorEvent> events() {
            return List.copyOf(events);
        }
    }

    /** Throws on every event. */
    static final class ThrowingExecutorObserver implements WorkflowExecutorObserver {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void onEvent(WorkflowExecutorEvent event) {
            calls.incrementAndGet();
            throw new IllegalStateException("the observer failed");
        }

        int calls() {
            return calls.get();
        }
    }
}
