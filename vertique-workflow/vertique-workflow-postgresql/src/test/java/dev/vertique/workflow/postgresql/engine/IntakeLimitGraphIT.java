// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static dev.vertique.workflow.postgresql.engine.RelayGraph.DEFINITION_ID;
import static dev.vertique.workflow.postgresql.engine.RelayGraph.EVENT_TYPE;
import static dev.vertique.workflow.postgresql.engine.RelayGraph.SIGNAL;
import static org.assertj.core.api.Assertions.assertThat;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.application.VertiqueApplicationHandle;
import dev.vertique.core.VertxModule;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntry;
import dev.vertique.workflow.di.WorkflowRecordingConfigModule;
import dev.vertique.workflow.engine.intake.IntakeAdmission;
import dev.vertique.workflow.engine.intake.IntakePressureSampler;
import dev.vertique.workflow.exception.WorkflowIntakeRefusedException;
import dev.vertique.workflow.executor.WorkflowExecutorEvent;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.IntakePressureChanged;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.engine.RelayGraph.Contract;
import dev.vertique.workflow.postgresql.engine.RelayGraph.Graph;
import dev.vertique.workflow.postgresql.engine.RelayGraph.Start;
import dev.vertique.workflow.services.signal.WorkflowSignalRequest;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies, over a dependency graph that runs only the recording role of the workflow (no executor, so nothing drains
 * the queue), that the node's own sampler reads the due commands and moves the node's admission: it refuses new starts
 * from the high mark, keeps refusing until the low mark, counts neither timers that are not yet due nor commands of
 * other states, reports each change, and never refuses a signal the outbox relay delivers.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
public class IntakeLimitGraphIT {

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("workflow_intake_limit_graph_it");

    private static final int HIGH = 5;
    private static final int LOW = 2;
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    @BeforeAll
    static void migrate() {
        migrate("classpath:db/migration/workflow", "flyway_workflow_history", false);
        migrate("classpath:db/migration/inbox-outbox", "flyway_inbox_outbox_history", true);
        migrate("classpath:db/migration/job", "flyway_job_history", true);
    }

    private static void migrate(String location, String historyTable, boolean baseline) {
        var flyway = Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations(location)
                .table(historyTable);
        if (baseline) {
            flyway.baselineOnMigrate(true).baselineVersion("0");
        }
        flyway.load().migrate();
    }

    // --- The graph ---

    /** Collects the events the node reports. */
    @Module
    static final class Observation {
        final List<WorkflowExecutorEvent> events = new CopyOnWriteArrayList<>();

        @Provides
        @IntoSet
        @Singleton
        WorkflowExecutorObserver observer() {
            return events::add;
        }
    }

    /** The recording role of the workflow with the intake limit configured. */
    @Singleton
    @Component(
            modules = {
                VertxModule.class,
                RelayGraph.Shared.class,
                RelayGraph.Infrastructure.class,
                WorkflowRecordingConfigModule.class,
                Observation.class
            })
    interface RecordingOnly extends Graph {

        IntakeAdmission intakeAdmission();

        Set<VerticleDeployment> verticleDeployments();

        @Component.Builder
        interface Builder {
            Builder vertxModule(VertxModule module);

            Builder infrastructure(RelayGraph.Infrastructure module);

            Builder observation(Observation module);

            RecordingOnly build();
        }
    }

    private static JsonObject config(boolean executor) {
        return config(executor, true);
    }

    private static JsonObject config(boolean executor, boolean intakeEnabled) {
        JsonObject config = RelayGraph.config(null);
        config.getJsonObject("workflow").getJsonObject("executor").put("enabled", executor);
        config.getJsonObject("workflow")
                .put(
                        "recording",
                        new JsonObject()
                                .put(
                                        "intake",
                                        new JsonObject()
                                                .put("enabled", intakeEnabled)
                                                .put("highMark", HIGH)
                                                .put("lowMark", LOW)
                                                .put("sampleIntervalMs", 100)));
        return config;
    }

    // --- Tests ---

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName(
            "a recording-only node refuses at the high mark, holds to the low mark, ignores future timers and relays signals")
    void aRecordingOnlyNodeLimitsItsOwnIntake(Vertx vertx) {
        try (Run run = Run.start(vertx)) {
            // Given a node that runs no executor, so every recorded start stays due
            // When starts are recorded until the node refuses
            WorkflowInstanceId first = run.start("first");
            // (the sampler of an idle node reads every 400 ms, so the starts are spread out)
            for (int started = 1; !run.refused("probe"); started++) {
                assertThat(started)
                        .as("starts recorded before the node refused")
                        .isLessThan(200);
                run.waitSamples(1);
            }

            // Then the node refused from its own reading of the due commands, and reported it once, with no payload
            assertThat(run.dueCommands()).isGreaterThanOrEqualTo(HIGH);
            assertThat(run.events()).hasSize(1);
            assertThat(run.events().getFirst()).satisfies(event -> {
                assertThat(event.refusing()).isTrue();
                assertThat(event.readFailed()).isFalse();
                assertThat(event.due()).isBetween((long) HIGH, HIGH + 1L);
            });

            // And a signal the outbox relay delivers is recorded all the same, without spending an attempt
            run.publishSignal(first, "relayed-1");
            run.awaitCondition("the relay to record the signal", () -> run.signalCommands(first) == 1);
            run.awaitCondition(
                    "the relay to publish the row", () -> run.outboxState().equals("PUBLISHED"));
            assertThat(run.outboxAttempt()).isZero();
            assertThat(run.outboxLastError()).isNull();

            // When the due commands fall between the marks, the node keeps refusing
            run.deleteDueCommandsDownTo(LOW + 1);
            run.waitSamples(6);
            assertThat(run.refused("between-marks")).isTrue();

            // And when they fall to the low mark, the node admits again and reports it
            run.deleteDueCommandsDownTo(LOW);
            run.awaitCondition("the node to admit again", () -> !run.refused("below-low"));
            assertThat(run.events()).hasSize(2);
            assertThat(run.events().get(1)).satisfies(event -> {
                assertThat(event.refusing()).isFalse();
                assertThat(event.readFailed()).isFalse();
            });

            // And timers and commands that are not yet due do not count, however many there are
            run.deleteDueCommandsDownTo(0);
            run.insertFutureCommands(HIGH * 4, first);
            run.waitSamples(8);
            assertThat(run.dueCommands()).isZero();
            assertThat(run.refused("future-timers")).isFalse();
            assertThat(run.events()).hasSize(2);
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("with the limit disabled, no sampler is deployed and every start is admitted however many are due")
    void aDisabledLimitDeploysNoSamplerAndAdmitsEverything(Vertx vertx) {
        try (Run run = Run.start(vertx, false)) {
            // Given a recording-only node whose intake limit is disabled
            // Then it deploys no sampler and its admission is the one that always accepts
            assertThat(run.deployments()).doesNotContain(IntakePressureSampler.DEPLOYMENT_NAME);
            assertThat(run.admission()).isSameAs(IntakeAdmission.alwaysAdmitting());

            // When far more commands than the high mark are due, and several sample intervals pass
            for (int started = 0; started < HIGH * 3; started++) {
                run.start("disabled-" + started);
            }
            run.waitSamples(10);

            // Then the commands are due, every further start is still admitted, and no change was reported
            assertThat(run.dueCommands()).isGreaterThanOrEqualTo(HIGH * 3L);
            assertThat(run.refused("probe")).isFalse();
            assertThat(run.events()).isEmpty();
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("with the limit enabled, the sampler is deployed and the admission follows its readings")
    void anEnabledLimitDeploysTheSampler(Vertx vertx) {
        try (Run run = Run.start(vertx, true)) {
            assertThat(run.deployments()).contains(IntakePressureSampler.DEPLOYMENT_NAME);
            assertThat(run.admission()).isNotSameAs(IntakeAdmission.alwaysAdmitting());
        }
    }

    // --- Fixture ---

    /** One started graph over the database. */
    private static final class Run implements AutoCloseable {
        private final Pool pool;
        private final RecordingOnly graph;
        private final VertiqueApplicationHandle<?> handle;
        private final Observation observation;
        private int counter;

        private Run(Pool pool, RecordingOnly graph, VertiqueApplicationHandle<?> handle, Observation observation) {
            this.pool = pool;
            this.graph = graph;
            this.handle = handle;
            this.observation = observation;
        }

        static Run start(Vertx vertx) {
            return start(vertx, true);
        }

        static Run start(Vertx vertx, boolean intakeEnabled) {
            PgConnectOptions options = RelayGraph.options(db);
            // A node that runs no executor publishes no definition, so a node that does publishes them first
            publishDefinitions(vertx, options);
            Pool admin = RelayGraph.pool(vertx, db.toPoolConfig(), options);
            try {
                await(admin.query("DELETE FROM outbox").execute());
                await(admin.query("DELETE FROM inbox").execute());
                await(admin.query("TRUNCATE workflow_commands, workflow_command_queues, workflow_history,"
                                + " workflow_dedup, workflow_timers, workflow_tasks, workflow_instances CASCADE")
                        .execute());
            } finally {
                await(admin.close());
            }
            Pool pool = RelayGraph.pool(vertx, db.toPoolConfig(), options);
            Observation observation = new Observation();
            RecordingOnly graph = graph(vertx, pool, options, observation, false, intakeEnabled);
            try {
                return new Run(pool, graph, await(RelayGraph.start(vertx, graph), Duration.ofSeconds(45)), observation);
            } catch (RuntimeException | Error failure) {
                await(pool.close());
                throw failure;
            }
        }

        private static RecordingOnly graph(
                Vertx vertx, Pool pool, PgConnectOptions options, Observation observation, boolean executor) {
            return graph(vertx, pool, options, observation, executor, true);
        }

        private static RecordingOnly graph(
                Vertx vertx,
                Pool pool,
                PgConnectOptions options,
                Observation observation,
                boolean executor,
                boolean intakeEnabled) {
            return DaggerIntakeLimitGraphIT_RecordingOnly.builder()
                    .vertxModule(new VertxModule(vertx, config(executor, intakeEnabled)))
                    .infrastructure(new RelayGraph.Infrastructure(pool, options))
                    .observation(observation)
                    .build();
        }

        private static void publishDefinitions(Vertx vertx, PgConnectOptions options) {
            Pool pool = RelayGraph.pool(vertx, db.toPoolConfig(), options);
            try {
                VertiqueApplicationHandle<?> publisher = await(
                        RelayGraph.start(vertx, graph(vertx, pool, options, new Observation(), true)),
                        Duration.ofSeconds(45));
                await(publisher.shutdown(), Duration.ofSeconds(30));
            } finally {
                await(pool.close());
            }
        }

        /** The names of the verticle deployments the node contributes. */
        Set<String> deployments() {
            return graph.verticleDeployments().stream()
                    .map(VerticleDeployment::name)
                    .collect(java.util.stream.Collectors.toSet());
        }

        /** The admission the engine of the node checks new starts and signals against. */
        IntakeAdmission admission() {
            return graph.intakeAdmission();
        }

        /** The changes of the intake limit the node reported; the other executor events are not of interest. */
        List<IntakePressureChanged> events() {
            return observation.events.stream()
                    .filter(IntakePressureChanged.class::isInstance)
                    .map(IntakePressureChanged.class::cast)
                    .toList();
        }

        /** Records a start in a transaction of its own. */
        WorkflowInstanceId start(String ref) {
            return await(
                    pool.withTransaction(tx -> graph.transactionalOperations().start(startCommand(ref), tx)));
        }

        /** Whether a start is refused; a start that is admitted is recorded. */
        boolean refused(String ref) {
            Throwable failure = await(pool.withTransaction(
                            tx -> graph.transactionalOperations().start(startCommand(ref + "-" + counter++), tx))
                    .<Throwable>map(id -> null)
                    .recover(Future::succeededFuture));
            if (failure != null) {
                assertThat(failure).isInstanceOf(WorkflowIntakeRefusedException.class);
            }
            return failure != null;
        }

        private static StartCommand startCommand(String ref) {
            return new StartCommand(
                    DEFINITION_ID, Contract.class.getName(), new Start(ref), ref, null, null, null, null, null);
        }

        long dueCommands() {
            return scalar("SELECT count(*) FROM workflow_commands WHERE state IN ('PENDING','DEFERRED')"
                    + " AND available_at <= now()");
        }

        /** Removes due commands, oldest first, until that many are left; the instances stay. */
        void deleteDueCommandsDownTo(int left) {
            long surplus = dueCommands() - left;
            if (surplus > 0) {
                await(pool.preparedQuery("DELETE FROM workflow_commands WHERE id IN (SELECT id FROM workflow_commands"
                                + " WHERE state IN ('PENDING','DEFERRED') AND available_at <= now()"
                                + " ORDER BY id LIMIT $1)")
                        .execute(Tuple.of(surplus)));
            }
        }

        /** Adds commands to the instance that become available only tomorrow, as timers do. */
        void insertFutureCommands(int count, WorkflowInstanceId id) {
            await(pool.preparedQuery("INSERT INTO workflow_commands (workflow_id, seq, kind, state, recorded_at,"
                            + " available_at) SELECT $1, 1000 + g, 'SIGNAL', 'PENDING', now(),"
                            + " now() + interval '1 day' FROM generate_series(1, $2) g")
                    .execute(Tuple.of(id.value(), count)));
        }

        /** Writes, in a transaction of its own, an outbox row that signals the instance through the signal endpoint. */
        void publishSignal(WorkflowInstanceId id, String key) {
            OutboxEntry entry = OutboxEntry.builder()
                    .destinationType(DestinationType.SERVICE)
                    .destination("workflow.signals.post")
                    .eventType(EVENT_TYPE)
                    .aggregateType("WorkflowInstance")
                    .aggregateId(id.value().toString())
                    .payload(WorkflowSignalRequest.instance(id, SIGNAL, Map.of("value", key), key))
                    .build();
            await(pool.withTransaction(tx -> graph.outboxService().publish(tx, entry)));
        }

        long signalCommands(WorkflowInstanceId id) {
            return scalar("SELECT count(*) FROM workflow_commands WHERE kind = 'SIGNAL' AND workflow_id = '"
                    + id.value() + "' AND seq < 1000");
        }

        String outboxState() {
            return await(pool.query("SELECT state FROM outbox WHERE destination = 'workflow.signals.post'")
                            .execute())
                    .iterator()
                    .next()
                    .getString("state");
        }

        long outboxAttempt() {
            return scalar("SELECT attempt FROM outbox WHERE destination = 'workflow.signals.post'");
        }

        String outboxLastError() {
            Row row = await(pool.query("SELECT last_error FROM outbox WHERE destination = 'workflow.signals.post'")
                            .execute())
                    .iterator()
                    .next();
            return row.getString("last_error");
        }

        /** Waits this many sample intervals. */
        void waitSamples(int samples) {
            try {
                Thread.sleep(samples * 100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted", e);
            }
        }

        private long scalar(String sql) {
            return await(pool.query(sql).execute()).iterator().next().getLong(0);
        }

        void awaitCondition(String description, BooleanSupplier condition) {
            long deadline = System.nanoTime() + PATIENCE.toNanos();
            while (System.nanoTime() < deadline) {
                if (condition.getAsBoolean()) {
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while waiting for " + description, e);
                }
            }
            throw new AssertionError("timed out waiting for " + description);
        }

        @Override
        public void close() {
            try {
                await(handle.shutdown(), Duration.ofSeconds(30));
            } finally {
                await(pool.close());
            }
        }

        private static <T> T await(Future<T> future) {
            return await(future, Duration.ofSeconds(20));
        }

        private static <T> T await(Future<T> future, Duration timeout) {
            try {
                return future.toCompletionStage().toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new AssertionError("the future failed", e.getCause());
            } catch (InterruptedException | java.util.concurrent.TimeoutException e) {
                throw new AssertionError("the future did not complete in " + timeout, e);
            }
        }
    }
}
