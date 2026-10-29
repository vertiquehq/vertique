// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.history.PendingCommand;
import dev.vertique.workflow.ops.WorkflowExecutionProbe;
import dev.vertique.workflow.ops.WorkflowExecutionProbe.IdleReport;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.postgresql.engine.PgWorkflowEngineTestSupport;
import dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that the execution probe waits for runnable commands, stays quiet for commands an executor cannot claim,
 * and reports the commands it stopped on. Commands and tasks are inserted by SQL, so no executor runs and every idle
 * and non-idle state is fully controlled.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class WorkflowExecutionProbeIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_execution_probe_it")
            .withMigration("classpath:db/migration/workflow");

    static Vertx vertx;
    static Pool pool;
    static WorkflowExecutionProbe probe;

    @BeforeAll
    static void setUp(Vertx injected) throws Exception {
        vertx = injected;
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(5))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
        probe = PgExecutionProbeSupport.probe(pool, vertx);
        // Warm the pool so the first timed read does not pay for connecting.
        join(pool.query("SELECT 1").execute(), Duration.ofSeconds(10));
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    @BeforeEach
    void emptyTables() throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "TRUNCATE workflow_commands, workflow_tasks, workflow_command_queues, workflow_instances CASCADE");
        }
    }

    // --- Idle without commands ---

    @Test
    @DisplayName("An instance without commands is idle at once")
    void instanceWithoutCommandsIsIdleAtOnce() throws Exception {
        // Given a running instance without any command row
        WorkflowInstanceId id = insertInstance(1);

        // When awaitIdle and awaitAllIdle are called with a two second timeout
        long started = System.nanoTime();
        IdleReport single = join(probe.awaitIdle(id, Duration.ofSeconds(2)), Duration.ofMillis(500));
        IdleReport all = join(probe.awaitAllIdle(Duration.ofSeconds(2)), Duration.ofMillis(500));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        // Then both complete within 500 ms each with four empty lists
        assertThat(elapsedMillis).isLessThan(1000);
        assertThat(single).isEqualTo(new IdleReport(List.of(), List.of(), List.of(), List.of()));
        assertThat(all).isEqualTo(new IdleReport(List.of(), List.of(), List.of(), List.of()));
    }

    // --- Runnable commands and timeouts ---

    @Test
    @DisplayName("A runnable command holds the probe until it settles or the timeout")
    void runnableCommandHoldsTheProbeUntilSettled() throws Exception {
        // Given instance A with a pending signal available now
        WorkflowInstanceId a = insertInstance(1);
        long pendingId = command(a, 1, "SIGNAL", "PENDING").insert().id();

        // And instance B whose head is claimed under a lease one hour ahead (in flight)
        WorkflowInstanceId b = insertInstance(2);
        command(b, 1, "SIGNAL", "CLAIMED")
                .availableAt("now() - interval '10 seconds'")
                .leaseUntil("now() + interval '1 hour'")
                .insert();

        // And instance C whose head is claimed under a lease that has passed
        WorkflowInstanceId c = insertInstance(3);
        command(c, 1, "SIGNAL", "CLAIMED")
                .availableAt("now() - interval '10 seconds'")
                .leaseUntil("now() - interval '1 minute'")
                .insert();

        // When awaitIdle(A) starts and the command is settled after 300 ms
        Future<IdleReport> waitingForA = probe.awaitIdle(a, Duration.ofSeconds(2));
        Thread.sleep(250);
        assertThat(waitingForA.isComplete())
                .as("the probe must wait while the instance has a claimable command")
                .isFalse();
        Thread.sleep(50);
        settle(pendingId, "COMPLETE");

        // Then A's future completes after the update with an empty report
        IdleReport report = join(waitingForA, Duration.ofSeconds(2));
        assertThat(report).isEqualTo(new IdleReport(List.of(), List.of(), List.of(), List.of()));

        // And B, whose command is in flight, times out and the failure names B
        assertThatThrownBy(() -> join(probe.awaitIdle(b, Duration.ofMillis(300)), Duration.ofSeconds(3)))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class)
                .hasMessageContaining(b.value().toString())
                .hasMessageNotContaining(c.value().toString());

        // And C, whose claim has expired at the head and is claimable again, times out and the failure names C
        assertThatThrownBy(() -> join(probe.awaitIdle(c, Duration.ofMillis(300)), Duration.ofSeconds(3)))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class)
                .hasMessageContaining(c.value().toString())
                .hasMessageNotContaining(b.value().toString());
    }

    @Test
    @DisplayName("A non-positive timeout is refused")
    void nonPositiveTimeoutIsRefused() {
        // Given an instance
        WorkflowInstanceId id = insertInstance(1);

        // When awaitIdle or awaitAllIdle is called with a zero or negative timeout
        // Then the call is refused with IllegalArgumentException
        assertThatThrownBy(() -> probe.awaitIdle(id, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> probe.awaitIdle(id, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> probe.awaitAllIdle(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- Report contents and caps ---

    @Test
    @DisplayName("The report lists deferred, parked and not-yet-available commands")
    void reportsDeferredParkedAndNotYetAvailableCommands() throws Exception {
        // Given instance A with a deferred, a parked, a dead-lettered, a future timer and a settled command
        WorkflowInstanceId a = insertInstance(1);
        Fixture deferred = command(a, 1, "SIGNAL", "DEFERRED")
                .availableAt("now() + interval '1 hour'")
                .lastError("not runnable")
                .insert();
        Fixture parked = command(a, 2, "SIGNAL", "PARKED").insert();
        Fixture dead = command(a, 3, "SIGNAL", "DEAD").attempts(5).insert();
        Fixture timer = command(a, 4, "TIMER", "PENDING")
                .timerKind("FIRE")
                .fireAt("now() + interval '1 hour'")
                .availableAt("now() + interval '1 hour'")
                .insert();
        command(a, 5, "SIGNAL", "COMPLETE").insert();

        // And instance B with one parked command
        WorkflowInstanceId b = insertInstance(2);
        Fixture otherParked = command(b, 1, "SIGNAL", "PARKED").insert();

        // When awaitIdle(A) and then awaitAllIdle are called
        IdleReport single = join(probe.awaitIdle(a, Duration.ofSeconds(2)), Duration.ofSeconds(3));
        IdleReport all = join(probe.awaitAllIdle(Duration.ofSeconds(2)), Duration.ofSeconds(3));

        // Then awaitIdle completes with the commands it stopped on, the dead-lettered one only as stuck and nothing
        // for the settled one
        assertThat(single.deferred()).containsExactly(deferred.pending());
        assertThat(single.deferred().get(0).lastError()).isEqualTo("not runnable");
        assertThat(single.parked()).containsExactly(parked.pending());
        assertThat(single.notYetAvailable()).containsExactly(timer.pending());
        assertThat(single.stuck()).containsExactly(dead.pending());

        // And awaitAllIdle adds B's parked command after A's
        assertThat(all.deferred()).containsExactly(deferred.pending());
        assertThat(all.parked()).containsExactly(parked.pending(), otherParked.pending());
        assertThat(all.notYetAvailable()).containsExactly(timer.pending());
        assertThat(all.stuck()).containsExactly(dead.pending());
    }

    @Test
    @DisplayName("Each list of the report holds at most 1000 commands, the first in sequence order")
    void eachListOfTheReportIsBounded() throws Exception {
        // Given an instance with 1005 parked commands
        WorkflowInstanceId a = insertInstance(1);
        for (long seq = 1; seq <= 1005; seq++) {
            command(a, seq, "SIGNAL", "PARKED").insert();
        }

        // When the probe reports on it
        IdleReport report = join(probe.awaitIdle(a, Duration.ofSeconds(5)), Duration.ofSeconds(6));

        // Then the parked list stops at 1000 and starts with the first command
        assertThat(report.parked()).hasSize(1000);
        assertThat(report.parked().get(0).sequence()).isEqualTo(1L);
        assertThat(report.parked().get(999).sequence()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("A timeout longer than the cap is shortened, and the probe still answers")
    void timeoutAboveTheCapStillAnswers() throws Exception {
        // Given an idle instance
        WorkflowInstanceId a = insertInstance(1);

        // When the probe is asked to wait a day
        IdleReport report = join(probe.awaitIdle(a, Duration.ofDays(1)), Duration.ofSeconds(3));

        // Then it answers at once
        assertThat(report.parked()).isEmpty();
    }

    // --- Engine graph ---

    @Test
    @DisplayName("The engine graph provides the probe")
    void engineGraphProvidesTheProbe() throws Exception {
        // Given the engine and PostgreSQL modules assembled into a Dagger component with a probe accessor
        ProbeComponent component =
                DaggerWorkflowExecutionProbeIT_ProbeComponent.factory().create(pool, Clock.systemUTC(), vertx);

        // When the component's probe waits for all instances to be idle
        IdleReport fromGraph = join(component.probe().awaitAllIdle(Duration.ofSeconds(1)), Duration.ofSeconds(3));

        // Then it completes with an empty report
        assertThat(fromGraph).isEqualTo(new IdleReport(List.of(), List.of(), List.of(), List.of()));

        // And an engine built by the factory exposes a probe that does the same
        WorkflowEngineHandle handle = engineOver(pool);
        IdleReport fromHandle =
                join(handle.executionProbe().awaitAllIdle(Duration.ofSeconds(1)), Duration.ofSeconds(3));
        assertThat(fromHandle).isEqualTo(new IdleReport(List.of(), List.of(), List.of(), List.of()));
    }

    // --- Successor behind a deferred head ---

    @Test
    @DisplayName("A successor behind a deferred head is not runnable")
    void successorBehindADeferredHeadIsNotRunnable() throws Exception {
        // Given instance D with a deferred head available in an hour and a signal that is due behind it
        WorkflowInstanceId d = insertInstance(1);
        Fixture head = command(d, 1, "SIGNAL", "DEFERRED")
                .availableAt("now() + interval '1 hour'")
                .insert();
        Fixture successor = command(d, 2, "SIGNAL", "PENDING")
                .availableAt("now() - interval '1 second'")
                .insert();

        // And instance E with a claimed head under a live lease and a signal that is due behind it
        WorkflowInstanceId e = insertInstance(2);
        command(e, 1, "SIGNAL", "CLAIMED")
                .availableAt("now() - interval '10 seconds'")
                .leaseUntil("now() + interval '1 hour'")
                .insert();
        command(e, 2, "SIGNAL", "PENDING").insert();

        // When awaitIdle(D) is called
        IdleReport report = join(probe.awaitIdle(d, Duration.ofSeconds(2)), Duration.ofSeconds(3));

        // Then it completes at once, reporting the head as deferred and the successor as not yet available
        assertThat(report.deferred()).containsExactly(head.pending());
        assertThat(report.notYetAvailable()).containsExactly(successor.pending());
        assertThat(report.parked()).isEmpty();
        assertThat(report.stuck()).isEmpty();

        // And awaitIdle(E) times out because its head is in flight
        assertThatThrownBy(() -> join(probe.awaitIdle(e, Duration.ofMillis(300)), Duration.ofSeconds(3)))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
    }

    // --- Held-back due date ---

    @Test
    @DisplayName("A due date held back by a dead-lettered completion's reservation is not runnable")
    void dueDateHeldBackByADeadCompletionReservationIsNotRunnable() throws Exception {
        // Given instance F whose task is completing under a dead-lettered completion and whose due date is overdue
        WorkflowInstanceId f = insertInstance(1);
        UUID taskF = UUID.randomUUID();
        insertTask(f, taskF, "COMPLETING");
        Fixture deadCompletion =
                command(f, 1, "COMPLETION", "DEAD").taskId(taskF).insert();
        reserve(taskF, deadCompletion.id());
        Fixture dueF = command(f, 2, "TIMER", "PENDING")
                .timerKind("DUE")
                .taskId(taskF)
                .fireAt("now() - interval '1 second'")
                .availableAt("now() - interval '1 second'")
                .insert();

        // And instance G the same, but with its task still open
        WorkflowInstanceId g = insertInstance(2);
        UUID taskG = UUID.randomUUID();
        insertTask(g, taskG, "OPEN");
        command(g, 1, "COMPLETION", "DEAD").taskId(taskG).insert();
        command(g, 2, "TIMER", "PENDING")
                .timerKind("DUE")
                .taskId(taskG)
                .fireAt("now() - interval '1 second'")
                .availableAt("now() - interval '1 second'")
                .insert();

        // When awaitIdle(F) is called
        IdleReport report = join(probe.awaitIdle(f, Duration.ofSeconds(2)), Duration.ofSeconds(3));

        // Then it completes at once with the held-back due date as not yet available and the dead completion only as
        // stuck
        assertThat(report.notYetAvailable()).containsExactly(dueF.pending());
        assertThat(report.deferred()).isEmpty();
        assertThat(report.parked()).isEmpty();
        assertThat(report.stuck()).containsExactly(deadCompletion.pending());

        // And awaitIdle(G) times out because its due date is claimable
        assertThatThrownBy(() -> join(probe.awaitIdle(g, Duration.ofMillis(300)), Duration.ofSeconds(3)))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
    }

    // --- Dagger fixture for the graph proof ---

    /** Test-only providers the PostgreSQL engine graph needs besides the pool. */
    @Module
    static class ProbeTestModule {

        @Provides
        @Singleton
        static WorkflowPlanValidator planValidator() {
            return new WorkflowPlanValidator(new DefaultRaceSafetyTargetRegistry(Set.of()));
        }

        @Provides
        @Singleton
        static PgDbExceptionMapper pgDbExceptionMapper() {
            return new PgDbExceptionMapper();
        }
    }

    /** The engine and PostgreSQL modules with an accessor for the probe. */
    @Singleton
    @Component(modules = {WorkflowPostgresqlModule.class, ProbeTestModule.class})
    interface ProbeComponent {

        WorkflowExecutionProbe probe();

        @Component.Factory
        interface Factory {
            ProbeComponent create(@BindsInstance Pool pool, @BindsInstance Clock clock, @BindsInstance Vertx vertx);
        }
    }

    // --- Fixtures ---

    /** Builds an engine through the test support over the same pool. */
    static WorkflowEngineHandle engineOver(Pool pool) {
        PgDbExceptionMapper mapper = new PgDbExceptionMapper();
        return PgWorkflowEngineTestSupport.create(
                pool,
                new DefaultWorkflowRegistry(),
                new PgWorkflowInstanceRepository(pool, mapper),
                new PgWorkflowHistoryRepository(pool, mapper),
                new PgWorkflowDedupRepository(pool, mapper),
                Set.of(),
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(mapper),
                new PgTaskStore(pool, mapper),
                Clock.systemUTC());
    }

    /** A command row as inserted, with the values the report is expected to carry. */
    record Fixture(
            UUID instance,
            long id,
            long seq,
            String kind,
            String state,
            Instant availableAt,
            int attempts,
            String lastError) {

        PendingCommand pending() {
            return new PendingCommand(
                    new WorkflowInstanceId(instance), seq, kind, state, availableAt, attempts, lastError);
        }
    }

    /** Collects the columns of a command row; times are SQL expressions on the database clock. */
    static CommandBuilder command(WorkflowInstanceId instance, long seq, String kind, String state) {
        return new CommandBuilder(instance.value(), seq, kind, state);
    }

    static final class CommandBuilder {
        private final UUID instance;
        private final long seq;
        private final String kind;
        private final String state;
        private String availableAt = "now()";
        private String timerKind;
        private String fireAt;
        private String leaseUntil;
        private UUID taskId;
        private int attempts;
        private String lastError;

        CommandBuilder(UUID instance, long seq, String kind, String state) {
            this.instance = instance;
            this.seq = seq;
            this.kind = kind;
            this.state = state;
        }

        CommandBuilder availableAt(String sql) {
            this.availableAt = sql;
            return this;
        }

        CommandBuilder timerKind(String value) {
            this.timerKind = value;
            return this;
        }

        CommandBuilder fireAt(String sql) {
            this.fireAt = sql;
            return this;
        }

        CommandBuilder leaseUntil(String sql) {
            this.leaseUntil = sql;
            return this;
        }

        CommandBuilder taskId(UUID value) {
            this.taskId = value;
            return this;
        }

        CommandBuilder attempts(int value) {
            this.attempts = value;
            return this;
        }

        CommandBuilder lastError(String value) {
            this.lastError = value;
            return this;
        }

        Fixture insert() {
            String sql = """
                    INSERT INTO workflow_commands
                        (workflow_id, seq, kind, timer_kind, task_id, state, recorded_at, available_at, fire_at,
                         attempts, last_error, claim_token, lease_until)
                    VALUES (?::uuid, ?, ?, ?, ?::uuid, ?, now(), %s, %s, ?, ?, %s, %s)
                    RETURNING id, available_at
                    """.formatted(
                            availableAt,
                            fireAt == null ? "NULL" : fireAt,
                            leaseUntil == null ? "NULL" : "gen_random_uuid()",
                            leaseUntil == null ? "NULL" : leaseUntil);
            try (Connection connection = connection();
                    PreparedStatement insert = connection.prepareStatement(sql)) {
                insert.setString(1, instance.toString());
                insert.setLong(2, seq);
                insert.setString(3, kind);
                insert.setString(4, timerKind);
                insert.setString(5, taskId == null ? null : taskId.toString());
                insert.setString(6, state);
                insert.setInt(7, attempts);
                insert.setString(8, lastError);
                try (ResultSet result = insert.executeQuery()) {
                    result.next();
                    return new Fixture(
                            instance,
                            result.getLong(1),
                            seq,
                            kind,
                            state,
                            result.getObject(2, OffsetDateTime.class).toInstant(),
                            attempts,
                            lastError);
                }
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    /** Inserts a running instance whose id is derived from {@code n}, so ids order like their numbers. */
    static WorkflowInstanceId insertInstance(long n) {
        UUID id = new UUID(0L, n);
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO workflow_instances
                        (id, definition_id, definition_version, plan_hash, status, current_step_id, state_json)
                    VALUES ('%s', 'orders', 1, repeat('a', 64), 'RUNNING', 'wait', '{}'::jsonb)
                    """.formatted(id));
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        return new WorkflowInstanceId(id);
    }

    static void insertTask(WorkflowInstanceId instance, UUID taskId, String status) {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO workflow_tasks
                        (task_id, workflow_id, step_id, status, assignee_type, assignee_key, decisions_snapshot_json)
                    VALUES ('%s', '%s', 'approve', '%s', 'USER', 'alice', '[]'::jsonb)
                    """.formatted(taskId, instance.value(), status));
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static void reserve(UUID taskId, long commandId) {
        update("UPDATE workflow_tasks SET reservation_command_id = %d WHERE task_id = '%s'"
                .formatted(commandId, taskId));
    }

    static void settle(long commandId, String state) {
        update(("UPDATE workflow_commands SET state = '%s', settled_at = now(), claim_token = NULL,"
                        + " lease_until = NULL WHERE id = %d")
                .formatted(state, commandId));
    }

    private static void update(String sql) {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
    }

    /** Waits for a future from the test thread, which is not an event-loop thread. */
    private static <T> T join(Future<T> future, Duration wait) throws Exception {
        CompletableFuture<T> completable = future.toCompletionStage().toCompletableFuture();
        return completable.get(wait.toMillis(), TimeUnit.MILLISECONDS);
    }
}
