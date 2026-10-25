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
import dev.vertique.db.IsolationLevel;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.CommandWakeups;
import dev.vertique.workflow.engine.spi.CommandWakeups.Registration;
import dev.vertique.workflow.engine.spi.CommandWakeups.WakeupSink;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.executor.WorkflowExecutorEvent;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.QueueSnapshot;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import dev.vertique.workflow.history.PendingCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import jakarta.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
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
            ClaimTimeRecorder claimTimes = new ClaimTimeRecorder();
            // Given an executor whose poll is far away and an engine whose pool has two connections
            Node node = fixture.node("engine")
                    .pollIntervalMs(FAR_POLL_MS)
                    .poolSize(2)
                    .concurrency(1)
                    .listening()
                    .commands(claimTimes::decorate)
                    .build();
            node.startExecutor();

            // And the executor is idle with its listening connection open
            awaitCondition(
                    "the listening connection",
                    WOKEN,
                    () -> fixture.listenBackends().size() == 1);
            Duration databaseClockOffset = databaseClockOffset(fixture);

            // When a warm-up start wakes the delayed first pass and leaves the executor idle
            WorkflowInstanceId warmup = node.record(startOf("wake-up-warmup"));
            await(node.engine.executionProbe().awaitIdle(warmup, WOKEN));
            awaitCondition("the warm-up peek", WOKEN, claimTimes::hasPeeked);
            assertThat(fixture.commandOf(warmup).state()).isEqualTo("COMPLETE");
            assertThat(fixture.commandOf(warmup).attempts()).isEqualTo(1);
            assertThat(fixture.instanceOf(warmup).status()).isEqualTo("WAITING");

            // Then 50 measured starts run to their first wait within seconds, which only a wake-up can explain
            List<Long> latenciesMs = new ArrayList<>();
            for (int i = 0; i < LATENCY_RUNS; i++) {
                WorkflowInstanceId next = node.record(startOf("latency-" + i));
                await(node.engine.executionProbe().awaitIdle(next, WOKEN));
                assertThat(fixture.instanceOf(next).status()).isEqualTo("WAITING");
                CommandRow command = fixture.commandOf(next);
                assertThat(command.state()).isEqualTo("COMPLETE");
                assertThat(command.attempts()).isEqualTo(1);
                latenciesMs.add(latencyMs(command, claimTimes, databaseClockOffset));
            }
            Collections.sort(latenciesMs);
            System.out.printf(
                    "wake-up latency evidence: recorded-to-claimed over %d starts: p50=%d ms p95=%d ms max=%d ms%n",
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
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("clock skew does not lose scheduling, which stays separate from diagnostic report rows")
    void clockSkewDoesNotLoseEarliestAvailableWakeup() {
        for (long absoluteOffsetMs : List.of(-30_000L, 30_000L)) {
            try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
                // Exercise a genuinely deferred row before any capable node persists this fixture definition.
                AvailabilityReadRecorder deferredReads = new AvailabilityReadRecorder(absoluteOffsetMs);
                Node recorder = fixture.node("recorder-" + absoluteOffsetMs).build();
                Node deferredNode = fixture.node("deferred-" + absoluteOffsetMs)
                        .withRegistry(fixture.registryWithout(ExecutorFixture.SKELETON))
                        .pollIntervalMs(FAR_POLL_MS)
                        .listening()
                        .commands(deferredReads::decorate)
                        .wakeups(delegate -> (context, sink) ->
                                delegate.register(context, availableAt -> sink.wake(availableAt + absoluteOffsetMs)))
                        .build();
                deferredNode.startExecutor();
                awaitCondition(
                        "the deferred node's listening connection",
                        WOKEN,
                        () -> fixture.listenBackends().size() == 1);

                WorkflowInstanceId deferredId = recorder.record(startOf("deferred-db-relative-" + absoluteOffsetMs));
                awaitCondition("the command to defer", WOKEN, () -> fixture.commandOf(deferredId)
                        .state()
                        .equals("DEFERRED"));
                awaitCondition("the deferred timer to fire", Duration.ofSeconds(10), () -> {
                    CommandRow deferred = fixture.commandOf(deferredId);
                    return "DEFERRED".equals(deferred.state()) && deferred.deferralCount() >= 2;
                });
                assertThat(deferredReads.delays().stream()
                                .flatMap(Optional::stream)
                                .anyMatch(delay -> delay > 0))
                        .isTrue();
                assertThat(deferredReads.reportReads()).isZero();
                await(deferredNode.engine.executionProbe().awaitIdle(deferredId, WOKEN));
                assertThat(deferredReads.reportReads()).isPositive();
                deferredNode.stopExecutor();

                // Then exercise a PENDING row on a node whose registry contains its definition.
                AvailabilityReadRecorder reads = new AvailabilityReadRecorder(absoluteOffsetMs);
                Node node = fixture.node("engine-" + absoluteOffsetMs)
                        .pollIntervalMs(FAR_POLL_MS)
                        .listening()
                        .commands(reads::decorate)
                        .wakeups(delegate -> (context, sink) ->
                                delegate.register(context, availableAt -> sink.wake(availableAt + absoluteOffsetMs)))
                        .build();
                node.startExecutor();
                awaitCondition(
                        "the listening connection",
                        WOKEN,
                        () -> fixture.listenBackends().size() == 1);

                WorkflowInstanceId id = fixture.recordAvailableIn(
                        node, startOf("db-relative-" + absoluteOffsetMs), Duration.ofMillis(1_500));

                awaitCondition("the delayed command to complete", Duration.ofSeconds(8), () -> fixture.commandOf(id)
                        .state()
                        .equals("COMPLETE"));
                CommandRow command = fixture.commandOf(id);
                assertThat(command.settledAt()).isAfterOrEqualTo(command.availableAt());
                assertThat(reads.schedulingReads()).isPositive();
                assertThat(reads.delays().stream().flatMap(Optional::stream).anyMatch(delay -> delay > 0))
                        .isTrue();

                // The executor did not load report rows to schedule the timer. Only the probe below does so.
                assertThat(reads.reportReads()).isZero();
                await(node.engine.executionProbe().awaitIdle(id, WOKEN));
                assertThat(reads.reportReads()).isPositive();
            }
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("the notification queue usage reaches the executor observers")
    void reportsNotificationQueueUsageToExecutorObservers() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given a node with two executor contexts and a recording observer
            RecordingWakeups wakeups = new RecordingWakeups();
            WorkflowTransactionContextTracker transactions = new WorkflowTransactionContextTracker();
            PoolAccessRecorder poolAccess = new PoolAccessRecorder();
            RecordingExecutorObserver recording = new RecordingExecutorObserver(transactions);
            RecordingExecutorObserver instrumentedRecording = new RecordingExecutorObserver(transactions, poolAccess);
            FirstContextIdleCommands commands = new FirstContextIdleCommands(wakeups);
            Pool listenerPool = fixture.newPool(2);
            Node node = fixture.node("engine")
                    .instances(2)
                    .concurrency(1)
                    .pollIntervalMs(FAR_POLL_MS)
                    .observers(recording)
                    .wakeups(wakeups::decorate)
                    .queueUsageIntervalMs(200)
                    .commands(commands::decorate)
                    .runner(transactions::decorate)
                    .build();
            assertThat(listenerPool).isNotSameAs(node.pool);

            // And a session that listens inside a transaction, so that the notifications sent after it pile up
            PgConnection stalled = await(PgConnection.connect(fixture.vertx(), fixture.connectOptions()));
            Registration instrumentedListenerRegistration = null;
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

                // When the executor starts, its first context owns the listener and the second processes a start
                node.startExecutor();
                awaitCondition("two executor registrations", WOKEN, () -> wakeups.registrations() == 2);
                Context listenerContext = wakeups.firstRegisteredContext();
                transactions.listenerContext(listenerContext);
                awaitCondition(
                        "the listening connection",
                        WOKEN,
                        () -> fixture.listenBackends().size() == 1);

                // And the same listener class has a separately instrumented standalone pool read
                CommandNotificationListener instrumentedListener = new CommandNotificationListener(
                        fixture.vertx(),
                        fixture.connectOptions(),
                        poolAccess.wrap(listenerPool),
                        Set.of(instrumentedRecording),
                        FAR_POLL_MS,
                        200);
                instrumentedListenerRegistration = instrumentedListener.register(listenerContext, ignored -> {});

                // And queue usage is reported before workflow work starts
                awaitCondition("a queue snapshot that reports the usage", WOKEN, () -> recording.snapshots().stream()
                        .anyMatch(snapshot -> snapshot.notificationQueueUsage() > 0));

                // And the second executor holds its workflow transaction open after the segment body completes
                WorkflowInstanceId id = node.record(startOf("queue-usage"));
                awaitCondition("the workflow transaction to be held", WOKEN, transactions::isHeld);
                assertThat(transactions.transactionContext()).isNotNull().isNotSameAs(contextKey(listenerContext));
                int transactionsWhileHeld = transactions.transactionStarts();

                // Then the instrumented listener reports while the workflow transaction remains active
                awaitCondition(
                        "queue usage during the workflow transaction",
                        WOKEN,
                        () -> recording.observations().stream()
                                        .anyMatch(observation ->
                                                observation.snapshot().notificationQueueUsage() > 0
                                                        && observation.anyWorkflowTransactionActive())
                                && instrumentedRecording.observations().stream()
                                        .anyMatch(observation ->
                                                observation.snapshot().notificationQueueUsage() > 0
                                                        && observation.anyWorkflowTransactionActive()));

                // And that callback's pool read used no workflow transaction runner call or explicit pool transaction
                List<QueueSnapshot> snapshots = recording.snapshots();
                assertThat(snapshots).isNotEmpty();
                assertThat(snapshots).allSatisfy(snapshot -> assertThat(snapshot.notificationQueueUsage())
                        .isBetween(0.0, 1.0));
                assertThat(snapshots.stream().anyMatch(snapshot -> snapshot.notificationQueueUsage() > 0))
                        .isTrue();
                List<ObservedQueueSnapshot> duringWorkflowTransaction = recording.observations().stream()
                        .filter(observation -> observation.snapshot().notificationQueueUsage() > 0
                                && observation.anyWorkflowTransactionActive())
                        .toList();
                List<ObservedQueueSnapshot> instrumentedDuringWorkflowTransaction =
                        instrumentedRecording.observations().stream()
                                .filter(observation -> observation.snapshot().notificationQueueUsage() > 0
                                        && observation.anyWorkflowTransactionActive())
                                .toList();
                assertThat(duringWorkflowTransaction).isNotEmpty();
                assertThat(instrumentedDuringWorkflowTransaction).isNotEmpty();
                assertThat(poolAccess.queueUsageReads()).isGreaterThan(0);
                assertThat(poolAccess.withTransactionCalls()).isZero();
                assertThat(transactions.transactionStarts()).isEqualTo(transactionsWhileHeld);
                assertThat(instrumentedDuringWorkflowTransaction).allSatisfy(observation -> {
                    assertThat(observation.poolQueueUsageReadsAtCallback()).isPositive();
                    assertThat(observation.workflowRunnerTransactionStartsAtCallback())
                            .isEqualTo(transactionsWhileHeld);
                });

                // And the command settles after the held transaction is released
                transactions.releaseHeldTransaction();
                await(node.engine.executionProbe().awaitIdle(id, WOKEN));
                node.stopExecutor();
            } finally {
                transactions.releaseHeldTransaction();
                if (instrumentedListenerRegistration != null) {
                    instrumentedListenerRegistration.close();
                }
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

            // And a raw subscriber can count notifications sent to the shared channel
            List<String> notifications = new CopyOnWriteArrayList<>();
            PgConnection raw = await(PgConnection.connect(fixture.vertx(), fixture.connectOptions()));
            try {
                raw.notificationHandler(notification -> {
                    if ("vertique_workflow_commands".equals(notification.getChannel())) {
                        notifications.add(notification.getPayload());
                    }
                });
                await(raw.query("LISTEN vertique_workflow_commands").execute());

                // When one start is recorded and committed, which sends one notification
                WorkflowInstanceId id = node.record(startOf("shared"));

                // Then the start runs to its first wait
                await(node.engine.executionProbe().awaitIdle(id, WOKEN));

                // And all three instances observed the wake-up, each on its own context
                awaitCondition("every instance to be woken", WOKEN, wakeups::allWoken);
                assertThat(wakeups.registeredContexts()).doesNotHaveDuplicates().hasSize(3);
                assertThat(wakeups.wokenOnRegisteredContext()).isTrue();

                // And exactly one raw notification carried the command's available time
                awaitCondition("the command notification", WOKEN, () -> !notifications.isEmpty());
                sleep(300);
                assertThat(notifications).containsExactly(fixture.notificationPayloadOf(id));
                assertThat(notifications.get(0)).matches("\\d+");

                // And the command was claimed once and the instance entered its initial state once
                assertThat(fixture.commandOf(id).attempts()).isEqualTo(1);
                assertThat(fixture.initialStates).containsExactly("shared");
            } finally {
                await(raw.close());
            }

            // And after the raw subscriber closes, one backend of the node still listens on the channel
            awaitCondition(
                    "only the node listener to remain",
                    WOKEN,
                    () -> fixture.listenBackends().size() == 1);

            // And stopping the executor closes the listening connection
            node.stopExecutor();
            awaitCondition("the listening connection to close", WOKEN, () -> fixture.listenBackends()
                    .isEmpty());
        }
    }

    private static long latencyMs(CommandRow command, ClaimTimeRecorder claimTimes, Duration databaseClockOffset) {
        Instant claimedAt = claimTimes.claimedAt(command.id()).plus(databaseClockOffset);
        return Duration.between(command.recordedAt().toInstant(), claimedAt).toMillis();
    }

    /** Samples the offset between the database clock used by {@code recorded_at} and the test JVM clock. */
    private static Duration databaseClockOffset(ExecutorFixture fixture) {
        Instant before = Instant.now();
        Instant databaseNow = await(
                        fixture.observer().query("SELECT clock_timestamp()").execute())
                .iterator()
                .next()
                .getOffsetDateTime(0)
                .toInstant();
        Instant after = Instant.now();
        Instant midpoint = before.plusNanos(Duration.between(before, after).toNanos() / 2);
        return Duration.between(midpoint, databaseNow);
    }

    /** Observes when a successful repository claim completes, without including segment execution or settlement. */
    private static final class ClaimTimeRecorder {
        private final Map<Long, Instant> claimedAt = new ConcurrentHashMap<>();
        private volatile boolean peeked;

        @SuppressWarnings("unchecked")
        WorkflowCommandRepository<SqlClient> decorate(WorkflowCommandRepository<SqlClient> delegate) {
            return (WorkflowCommandRepository<SqlClient>) Proxy.newProxyInstance(
                    WorkflowCommandRepository.class.getClassLoader(),
                    new Class<?>[] {WorkflowCommandRepository.class},
                    (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(delegate, args);
                            if (method.getName().equals("peek") && result instanceof Future<?>) {
                                Future<List<CommandHead>> peek = (Future<List<CommandHead>>) result;
                                return peek.map(heads -> {
                                    peeked = true;
                                    return heads;
                                });
                            }
                            if (!method.getName().equals("claim") || !(result instanceof Future<?>)) {
                                return result;
                            }

                            CommandHead head = (CommandHead) args[0];
                            Future<Optional<ClaimedCommand>> claim = (Future<Optional<ClaimedCommand>>) result;
                            return claim.map(outcome -> {
                                if (outcome.isPresent() && outcome.get().result() == ClaimedCommand.Result.CLAIMED) {
                                    claimedAt.put(head.commandId(), Instant.now());
                                }
                                return outcome;
                            });
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        boolean hasPeeked() {
            return peeked;
        }

        Instant claimedAt(long commandId) {
            Instant claimTime = claimedAt.get(commandId);
            if (claimTime == null) {
                throw new AssertionError("no successful claim was observed for command " + commandId);
            }
            return claimTime;
        }
    }

    /** Records scheduler reads while shifting only the absolute timestamps consumed by the diagnostic API. */
    private static final class AvailabilityReadRecorder {
        private final long absoluteOffsetMs;
        private final AtomicInteger schedulingReads = new AtomicInteger();
        private final AtomicInteger reportReads = new AtomicInteger();
        private final List<Optional<Long>> delays = new CopyOnWriteArrayList<>();

        AvailabilityReadRecorder(long absoluteOffsetMs) {
            this.absoluteOffsetMs = absoluteOffsetMs;
        }

        @SuppressWarnings("unchecked")
        WorkflowCommandRepository<SqlClient> decorate(WorkflowCommandRepository<SqlClient> delegate) {
            return (WorkflowCommandRepository<SqlClient>) Proxy.newProxyInstance(
                    WorkflowCommandRepository.class.getClassLoader(),
                    new Class<?>[] {WorkflowCommandRepository.class},
                    (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(delegate, args);
                            if (method.getName().equals("findEarliestAvailabilityDelayMs")
                                    && result instanceof Future<?>) {
                                schedulingReads.incrementAndGet();
                                Future<Optional<Long>> delay = (Future<Optional<Long>>) result;
                                return delay.map(value -> {
                                    delays.add(value);
                                    return value;
                                });
                            }
                            if (method.getName().equals("findNotYetAvailable") && result instanceof Future<?>) {
                                reportReads.incrementAndGet();
                                Future<List<PendingCommand>> report = (Future<List<PendingCommand>>) result;
                                return report.map(commands -> commands.stream()
                                        .map(command -> new PendingCommand(
                                                command.instanceId(),
                                                command.sequence(),
                                                command.kind(),
                                                command.state(),
                                                command.availableAt().plusMillis(absoluteOffsetMs),
                                                command.attempts(),
                                                command.lastError()))
                                        .toList());
                            }
                            return result;
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        int schedulingReads() {
            return schedulingReads.get();
        }

        int reportReads() {
            return reportReads.get();
        }

        List<Optional<Long>> delays() {
            return delays;
        }
    }

    /** Whether a queue snapshot callback ran while an executor transaction was active. */
    private record ObservedQueueSnapshot(
            QueueSnapshot snapshot,
            boolean anyWorkflowTransactionActive,
            int poolQueueUsageReadsAtCallback,
            int workflowRunnerTransactionStartsAtCallback) {}

    /** Collects the queue snapshots and their transaction-context relation. */
    private static final class RecordingExecutorObserver implements WorkflowExecutorObserver {
        private final List<WorkflowExecutorEvent> events = new CopyOnWriteArrayList<>();
        private final List<ObservedQueueSnapshot> observations = new CopyOnWriteArrayList<>();
        private final WorkflowTransactionContextTracker transactions;
        private final PoolAccessRecorder poolAccess;

        RecordingExecutorObserver(WorkflowTransactionContextTracker transactions) {
            this(transactions, null);
        }

        RecordingExecutorObserver(WorkflowTransactionContextTracker transactions, PoolAccessRecorder poolAccess) {
            this.transactions = transactions;
            this.poolAccess = poolAccess;
        }

        @Override
        public void onEvent(WorkflowExecutorEvent event) {
            events.add(event);
            if (event instanceof QueueSnapshot snapshot) {
                observations.add(new ObservedQueueSnapshot(
                        snapshot,
                        transactions.hasActiveTransaction(),
                        poolAccess == null ? -1 : poolAccess.queueUsageReads(),
                        transactions.transactionStarts()));
            }
        }

        List<QueueSnapshot> snapshots() {
            return events.stream()
                    .filter(QueueSnapshot.class::isInstance)
                    .map(QueueSnapshot.class::cast)
                    .toList();
        }

        List<ObservedQueueSnapshot> observations() {
            return observations;
        }
    }

    /** Holds one executor segment transaction and records its Vert.x context until the test releases it. */
    private static final class WorkflowTransactionContextTracker {
        private final AtomicBoolean holdNextTransaction = new AtomicBoolean(true);
        private final AtomicBoolean releaseRequested = new AtomicBoolean();
        private final AtomicInteger transactionStarts = new AtomicInteger();
        private final Map<Object, AtomicInteger> activeContexts = new ConcurrentHashMap<>();
        private volatile Object listenerContext;
        private volatile Object transactionContext;
        private volatile Promise<Void> release;
        private volatile boolean held;

        WorkflowTransactionRunner<SqlClient> decorate(WorkflowTransactionRunner<SqlClient> delegate) {
            return new WorkflowTransactionRunner<>() {
                @Override
                public <R> Future<R> inTransaction(
                        @Nullable IsolationLevel level, Function<SqlClient, Future<R>> work) {
                    return runTrackedTransaction(work, wrapped -> delegate.inTransaction(level, wrapped));
                }

                @Override
                public <R> Future<R> inTransaction(
                        @Nullable IsolationLevel level, Duration deadline, Function<SqlClient, Future<R>> work) {
                    return runTrackedTransaction(work, wrapped -> delegate.inTransaction(level, deadline, wrapped));
                }
            };
        }

        void listenerContext(Context context) {
            listenerContext = contextKey(context);
        }

        private <R> Future<R> runTrackedTransaction(
                Function<SqlClient, Future<R>> work, Function<Function<SqlClient, Future<R>>, Future<R>> run) {
            Object key = contextKey(Vertx.currentContext());
            transactionStarts.incrementAndGet();
            boolean hold = key != null && key != listenerContext && holdNextTransaction.compareAndSet(true, false);
            if (key != null) {
                activeContexts
                        .computeIfAbsent(key, ignored -> new AtomicInteger())
                        .incrementAndGet();
            }
            if (hold) {
                transactionContext = key;
            }
            Promise<Void> gate = hold ? ((ContextInternal) Vertx.currentContext()).promise() : null;
            if (hold) {
                release = gate;
                if (releaseRequested.get()) {
                    gate.tryComplete();
                }
            }
            Function<SqlClient, Future<R>> wrapped = tx -> {
                Future<R> body;
                try {
                    body = work.apply(tx);
                } catch (RuntimeException | Error failure) {
                    return Future.failedFuture(failure);
                }
                if (!hold) {
                    return body;
                }
                return body.compose(result -> {
                    held = true;
                    return gate.future().map(result);
                });
            };
            Future<R> transaction;
            try {
                transaction = run.apply(wrapped);
            } catch (RuntimeException | Error failure) {
                if (key != null) {
                    transactionFinished(key);
                }
                return Future.failedFuture(failure);
            }
            return transaction.onComplete(ignored -> {
                if (key != null) {
                    transactionFinished(key);
                }
            });
        }

        private void transactionFinished(Object key) {
            AtomicInteger active = activeContexts.get(key);
            if (active != null && active.decrementAndGet() == 0) {
                activeContexts.remove(key, active);
            }
        }

        boolean isHeld() {
            return held;
        }

        Object transactionContext() {
            return transactionContext;
        }

        boolean hasActiveTransaction() {
            return activeContexts.values().stream().anyMatch(active -> active.get() > 0);
        }

        int transactionStarts() {
            return transactionStarts.get();
        }

        void releaseHeldTransaction() {
            releaseRequested.set(true);
            Promise<Void> gate = release;
            if (gate != null) {
                gate.tryComplete();
            }
        }
    }

    /** Observes queue reads made by the node-level listener, separately from the executor's workflow runner. */
    private static final class PoolAccessRecorder {
        private static final String QUEUE_USAGE_SQL = "SELECT pg_notification_queue_usage()";

        private final AtomicInteger queueUsageReads = new AtomicInteger();
        private final AtomicInteger withTransactionCalls = new AtomicInteger();

        Pool wrap(Pool delegate) {
            return (Pool) Proxy.newProxyInstance(
                    Pool.class.getClassLoader(), new Class<?>[] {Pool.class}, (proxy, method, args) -> {
                        if (method.getName().equals("query")
                                && args != null
                                && args.length > 0
                                && QUEUE_USAGE_SQL.equals(args[0])) {
                            queueUsageReads.incrementAndGet();
                        }
                        if (method.getName().equals("withTransaction")) {
                            withTransactionCalls.incrementAndGet();
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        int queueUsageReads() {
            return queueUsageReads.get();
        }

        int withTransactionCalls() {
            return withTransactionCalls.get();
        }
    }

    /** Leaves the executor registered with the listener idle so another executor claims the test start. */
    private static final class FirstContextIdleCommands {
        private final RecordingWakeups wakeups;

        FirstContextIdleCommands(RecordingWakeups wakeups) {
            this.wakeups = wakeups;
        }

        @SuppressWarnings("unchecked")
        WorkflowCommandRepository<SqlClient> decorate(WorkflowCommandRepository<SqlClient> delegate) {
            return (WorkflowCommandRepository<SqlClient>) Proxy.newProxyInstance(
                    WorkflowCommandRepository.class.getClassLoader(),
                    new Class<?>[] {WorkflowCommandRepository.class},
                    (proxy, method, args) -> {
                        Context context = Vertx.currentContext();
                        Context listener = wakeups.firstRegisteredContext();
                        if (method.getName().equals("peek")
                                && context != null
                                && contextKey(context) == contextKey(listener)) {
                            return Future.<List<CommandHead>>succeededFuture(List.of());
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }

    private static Object contextKey(Context context) {
        return context == null ? null : ((ContextInternal) context).unwrap();
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

        Context firstRegisteredContext() {
            return registered.isEmpty() ? null : registered.get(0).context;
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
