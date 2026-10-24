// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.sleep;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.startOf;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.engine.spi.CommandWakeups;
import dev.vertique.workflow.engine.spi.CommandWakeups.Registration;
import dev.vertique.workflow.engine.spi.CommandWakeups.WakeupSink;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.executor.WorkflowExecutorEvent;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.QueueSnapshot;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a committed command that becomes pending or deferred wakes the executors of every node through
 * PostgreSQL notifications: an idle executor starts it long before its poll, the notification carries only the
 * available time, a lost listening connection leaves the executors polling, the notification queue usage reaches the
 * executor observers, and one notification wakes every instance of a node, which spend one attempt on one head.
 */
@ExtendWith(DatabaseExtension.class)
class CommandWakeUpIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_command_wake_up_it")
            .withMigration("classpath:db/migration/workflow");

    /** A poll interval no test waits for, so that only a wake-up can start a command. */
    private static final long FAR_POLL_MS = 600_000;

    private static final Duration WOKEN = Duration.ofSeconds(5);
    private static final int LATENCY_RUNS = 50;

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("an idle executor runs a committed start long before the poll interval")
    void idleExecutorRunsACommittedStartLongBeforeThePollInterval() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given an executor whose poll is far away and an engine whose pool has two connections
            Node node = fixture.node("engine")
                    .pollIntervalMs(FAR_POLL_MS)
                    .poolSize(2)
                    .concurrency(1)
                    .listening()
                    .build();
            node.startExecutor();

            // And the executor is idle with its listening connection open
            awaitCondition(
                    "the listening connection",
                    WOKEN,
                    () -> fixture.listenBackends().size() == 1);

            // When a start is recorded and committed
            WorkflowInstanceId id = node.record(startOf("woken"));

            // Then the start has run to its first wait within seconds, which only a wake-up can explain
            await(node.engine.executionProbe().awaitIdle(id, WOKEN));
            assertThat(fixture.instanceOf(id).status()).isEqualTo("WAITING");
            CommandRow command = fixture.commandOf(id);
            assertThat(command.state()).isEqualTo("COMPLETE");
            assertThat(command.attempts()).isEqualTo(1);

            // And the time from recording to settling is kept as evidence over further starts
            List<Long> latenciesMs = new ArrayList<>();
            latenciesMs.add(latencyMs(command));
            for (int i = 1; i < LATENCY_RUNS; i++) {
                WorkflowInstanceId next = node.record(startOf("latency-" + i));
                await(node.engine.executionProbe().awaitIdle(next, WOKEN));
                latenciesMs.add(latencyMs(fixture.commandOf(next)));
            }
            Collections.sort(latenciesMs);
            System.out.printf(
                    "wake-up latency evidence: recorded-to-settled over %d starts: p50=%d ms p95=%d ms max=%d ms%n",
                    latenciesMs.size(),
                    latenciesMs.get(latenciesMs.size() / 2),
                    latenciesMs.get((int) Math.ceil(latenciesMs.size() * 0.95) - 1),
                    latenciesMs.get(latenciesMs.size() - 1));
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("the notification carries only the available time")
    void notificationPayloadIsOnlyTheAvailableTime() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given the node's listener with a recording sink registered on a context of its own
            Node node = fixture.node("engine").build();
            CommandNotificationListener listener = new CommandNotificationListener(
                    fixture.vertx(), fixture.connectOptions(), fixture.newPool(2), Set.of(), FAR_POLL_MS, FAR_POLL_MS);
            List<Long> hints = new CopyOnWriteArrayList<>();
            List<Context> hintContexts = new CopyOnWriteArrayList<>();
            Context context = fixture.vertx().getOrCreateContext();
            Registration registration = listener.register(context, availableAt -> {
                hintContexts.add(Vertx.currentContext());
                hints.add(availableAt);
            });
            PgConnection raw = null;
            try {
                awaitCondition(
                        "the listening connection",
                        WOKEN,
                        () -> fixture.listenBackends().size() == 1);

                // And a raw subscriber that sees the payload as PostgreSQL delivers it
                List<String> payloads = new CopyOnWriteArrayList<>();
                raw = await(PgConnection.connect(fixture.vertx(), fixture.connectOptions()));
                raw.notificationHandler(notification -> {
                    if ("vertique_workflow_commands".equals(notification.getChannel())) {
                        payloads.add(notification.getPayload());
                    }
                });
                await(raw.query("LISTEN vertique_workflow_commands").execute());

                // When a start is recorded and the test checks before and after its commit
                SqlConnection connection = await(node.pool.getConnection());
                Transaction transaction = await(connection.begin());
                WorkflowInstanceId id = await(node.engine.start(startOf("announced"), connection));
                sleep(500);

                // Then no hint arrived before the commit
                assertThat(hints).isEmpty();
                assertThat(payloads).isEmpty();

                await(transaction.commit());
                connection.close();
                awaitCondition("the hint", WOKEN, () -> !hints.isEmpty() && !payloads.isEmpty());
                sleep(300);

                // And exactly one arrived after it, equal to the command's available time in epoch milliseconds
                String expected = fixture.notificationPayloadOf(id);
                assertThat(hints).containsExactly(Long.parseLong(expected));

                // And the raw payload is only digits: no instance id, no kind
                assertThat(payloads).containsExactly(expected);
                assertThat(payloads.get(0)).matches("\\d+");

                // And the sink ran on the context it was registered with
                assertThat(hintContexts).hasSize(1);
                assertThat(((ContextInternal) hintContexts.get(0)).unwrap())
                        .isSameAs(((ContextInternal) context).unwrap());
            } finally {
                registration.close();
                if (raw != null) {
                    raw.close();
                }
            }

            // And the listener closes its connection when its last registration is closed
            awaitCondition("the listening connection to close", WOKEN, () -> fixture.listenBackends()
                    .isEmpty());
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a lost listener falls back to polling and the earliest available time wakes the executor")
    void lostListenerFallsBackToPollingAndEarliestAvailableWakesTheExecutor() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given a first engine with two instances that poll every 500 ms, and a record of every peek by context
            PeekRecorder peeks = new PeekRecorder();
            Node first = fixture.node("first")
                    .instances(2)
                    .pollIntervalMs(500)
                    .listening()
                    .commands(peeks::decorate)
                    .build();
            first.startExecutor();
            awaitCondition(
                    "the listening connection",
                    WOKEN,
                    () -> fixture.listenBackends().size() == 1);
            int lostBackend = fixture.listenBackends().get(0);

            // When its single listening backend is terminated and two starts are recorded
            peeks.reset();
            assertThat(fixture.terminateBackend(lostBackend)).isTrue();
            WorkflowInstanceId one = first.record(startOf("polled-1"));
            WorkflowInstanceId two = first.record(startOf("polled-2"));

            // Then both starts execute, by polling
            first.awaitIdle(one);
            first.awaitIdle(two);
            for (WorkflowInstanceId id : List.of(one, two)) {
                CommandRow command = fixture.commandOf(id);
                assertThat(command.state()).isEqualTo("COMPLETE");
                assertThat(command.attempts()).isEqualTo(1);
            }

            // And both instances keep polling: each peeks at least once after the termination
            awaitCondition("both instances to peek", WOKEN, () -> peeks.contexts() >= 2);

            // And the listener reconnects within poll intervals, with a new backend
            awaitCondition("the listener to reconnect", WOKEN, () -> {
                List<Integer> backends = fixture.listenBackends();
                return backends.size() == 1 && backends.get(0) != lostBackend;
            });

            // And it logged one warning for the loss
            List<ILoggingEvent> warnings = fixture.logged(Level.WARN).stream()
                    .filter(event -> event.getLoggerName().endsWith("CommandNotificationListener"))
                    .toList();
            assertThat(warnings).hasSize(1);

            // And it never opened a transaction
            for (int backend : fixture.listenBackends()) {
                assertThat(fixture.inTransaction(backend)).isFalse();
            }
            first.stopExecutor();
            awaitCondition("the first listener to close", WOKEN, () -> fixture.listenBackends()
                    .isEmpty());

            // Given a second engine that polls only every ten minutes
            Node second = fixture.node("second")
                    .pollIntervalMs(FAR_POLL_MS)
                    .listening()
                    .build();
            second.startExecutor();
            awaitCondition(
                    "the second listening connection",
                    WOKEN,
                    () -> fixture.listenBackends().size() == 1);

            // When a start is recorded whose command becomes available one second after it is recorded
            WorkflowInstanceId later = fixture.recordAvailableIn(second, startOf("later"), Duration.ofSeconds(1));

            // Then it runs when its time comes, long before the poll and never before its time
            awaitCondition("the start to run at its available time", WOKEN, () -> fixture.commandOf(later)
                    .state()
                    .equals("COMPLETE"));
            CommandRow command = fixture.commandOf(later);
            assertThat(command.settledAt()).isAfterOrEqualTo(command.availableAt());
            assertThat(Duration.between(command.availableAt(), command.settledAt()))
                    .isLessThan(Duration.ofSeconds(3));

            // And its listener never opened a transaction
            for (int backend : fixture.listenBackends()) {
                assertThat(fixture.inTransaction(backend)).isFalse();
            }
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("the notification queue usage reaches the executor observers")
    void reportsNotificationQueueUsageToExecutorObservers() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given an engine with a recording observer and a short queue-usage read interval
            RecordingExecutorObserver recording = new RecordingExecutorObserver();
            Node node = fixture.node("engine")
                    .observers(recording)
                    .queueUsageIntervalMs(200)
                    .build();

            // And a session that listens inside a transaction, so that the notifications sent after it pile up
            PgConnection stalled = await(PgConnection.connect(fixture.vertx(), fixture.connectOptions()));
            try {
                await(stalled.query("LISTEN queue_usage_probe").execute());
                await(stalled.query("BEGIN").execute());
                for (int i = 0; i < 40; i++) {
                    await(fixture.observer()
                            .query("SELECT pg_notify('queue_usage_probe', repeat('x', 7000))")
                            .execute());
                }
                assertThat(await(fixture.observer()
                                        .query("SELECT pg_notification_queue_usage()")
                                        .execute())
                                .iterator()
                                .next()
                                .getDouble(0))
                        .isGreaterThan(0.0);

                // When the executor runs for two read intervals
                node.startExecutor();
                awaitCondition("a queue snapshot that reports the usage", WOKEN, () -> recording.snapshots().stream()
                        .anyMatch(snapshot -> snapshot.notificationQueueUsage() > 0));
                sleep(400);
                node.stopExecutor();

                // Then every snapshot reports a usage between 0 and 1 inclusive, and the database's usage reached it
                List<QueueSnapshot> snapshots = recording.snapshots();
                assertThat(snapshots).isNotEmpty();
                assertThat(snapshots).allSatisfy(snapshot -> assertThat(snapshot.notificationQueueUsage())
                        .isBetween(0.0, 1.0));
                assertThat(snapshots.stream().anyMatch(snapshot -> snapshot.notificationQueueUsage() > 0))
                        .isTrue();
            } finally {
                await(stalled.query("ROLLBACK").execute());
                stalled.close();
            }

            // And the reads left no session inside a transaction
            assertThat(await(fixture.observer()
                                    .query("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                                            + " AND pid <> pg_backend_pid() AND state LIKE 'idle in transaction%'")
                                    .execute())
                            .iterator()
                            .next()
                            .getLong(0))
                    .isZero();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("three instances woken by one notification spend one attempt on one head")
    void threeInstancesWokenByOneNotificationSpendOneAttempt() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given one engine with three instances, a poll far away, and a record of each instance's wake-ups
            RecordingWakeups wakeups = new RecordingWakeups();
            Node node = fixture.node("engine")
                    .instances(3)
                    .pollIntervalMs(FAR_POLL_MS)
                    .wakeups(wakeups::decorate)
                    .build();
            node.startExecutor();

            // And every instance is registered, idle, and the node has one listening connection
            awaitCondition("three registrations", WOKEN, () -> wakeups.registrations() == 3);
            awaitCondition(
                    "the listening connection",
                    WOKEN,
                    () -> fixture.listenBackends().size() == 1);

            // When one start is recorded and committed, which sends one notification
            WorkflowInstanceId id = node.record(startOf("shared"));

            // Then the start runs to its first wait
            await(node.engine.executionProbe().awaitIdle(id, WOKEN));

            // And all three instances observed the wake-up, each on its own context
            awaitCondition("every instance to be woken", WOKEN, wakeups::allWoken);
            assertThat(wakeups.registeredContexts()).doesNotHaveDuplicates().hasSize(3);
            assertThat(wakeups.wokenOnRegisteredContext()).isTrue();

            // And one backend of the node listens on the channel
            assertThat(fixture.listenBackends()).hasSize(1);

            // And the command was claimed once and the instance entered its initial state once
            assertThat(fixture.commandOf(id).attempts()).isEqualTo(1);
            assertThat(fixture.initialStates).containsExactly("shared");

            // And stopping the executor closes the listening connection
            node.stopExecutor();
            awaitCondition("the listening connection to close", WOKEN, () -> fixture.listenBackends()
                    .isEmpty());
        }
    }

    private static long latencyMs(CommandRow command) {
        return Duration.between(command.recordedAt(), command.settledAt()).toMillis();
    }

    /** Collects the queue snapshots it receives. */
    private static final class RecordingExecutorObserver implements WorkflowExecutorObserver {
        private final List<WorkflowExecutorEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onEvent(WorkflowExecutorEvent event) {
            events.add(event);
        }

        List<QueueSnapshot> snapshots() {
            return events.stream()
                    .filter(QueueSnapshot.class::isInstance)
                    .map(QueueSnapshot.class::cast)
                    .toList();
        }
    }

    /** Wraps a wake-up source and records, for every registration, its context and the contexts it was woken on. */
    private static final class RecordingWakeups {
        private final List<Registered> registered = new CopyOnWriteArrayList<>();

        CommandWakeups decorate(CommandWakeups delegate) {
            return (context, sink) -> {
                Registered entry = new Registered(context);
                registered.add(entry);
                WakeupSink recording = availableAt -> {
                    entry.wokenOn.add(Vertx.currentContext());
                    sink.wake(availableAt);
                };
                return delegate.register(context, recording);
            };
        }

        int registrations() {
            return registered.size();
        }

        boolean allWoken() {
            return registered.size() == 3 && registered.stream().allMatch(entry -> !entry.wokenOn.isEmpty());
        }

        List<Object> registeredContexts() {
            return registered.stream()
                    .map(entry -> (Object) ((ContextInternal) entry.context).unwrap())
                    .toList();
        }

        boolean wokenOnRegisteredContext() {
            return registered.stream().allMatch(entry -> entry.wokenOn.stream()
                    .allMatch(woken -> woken != null
                            && ((ContextInternal) woken).unwrap() == ((ContextInternal) entry.context).unwrap()));
        }

        private static final class Registered {
            final Context context;
            final List<Context> wokenOn = new CopyOnWriteArrayList<>();

            Registered(Context context) {
                this.context = context;
            }
        }
    }

    /** Records the context of every peek a command repository receives from an executor instance. */
    private static final class PeekRecorder {
        private final List<Object> contexts = new CopyOnWriteArrayList<>();

        @SuppressWarnings("unchecked")
        WorkflowCommandRepository<SqlClient> decorate(WorkflowCommandRepository<SqlClient> delegate) {
            return (WorkflowCommandRepository<SqlClient>) Proxy.newProxyInstance(
                    WorkflowCommandRepository.class.getClassLoader(),
                    new Class<?>[] {WorkflowCommandRepository.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("peek") && Vertx.currentContext() != null) {
                            Object unwrapped = ((ContextInternal) Vertx.currentContext()).unwrap();
                            if (!contexts.contains(unwrapped)) {
                                contexts.add(unwrapped);
                            }
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        /** Forgets the peeks so far; only peeks after this call count. */
        void reset() {
            contexts.clear();
        }

        /** The number of distinct contexts that peeked since the last reset. */
        int contexts() {
            return contexts.size();
        }
    }
}
