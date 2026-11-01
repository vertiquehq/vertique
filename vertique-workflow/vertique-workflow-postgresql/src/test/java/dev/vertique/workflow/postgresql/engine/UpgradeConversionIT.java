// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.RaceSafety;
import dev.vertique.workflow.plan.RaceSafetyTargetContributor;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.postgresql.executor.CommandEligibilitySql;
import dev.vertique.workflow.postgresql.repository.PgBranchTokenRepository;
import dev.vertique.workflow.postgresql.repository.PgJoinStateRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Upgrades databases that hold running work written by the previous release and shows what the migration does with
 * it: each scheduled timer becomes one timer command that runs once, each scheduled branch retry becomes one retry
 * command that re-drives its branch once, every existing instance is marked as not yet baselined, and the live timer
 * jobs of the delayed-job table under its default name are deleted while its terminal history stays. Each scenario
 * owns its database and writes its rows with raw SQL in the previous schema's shape before the migration runs.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class UpgradeConversionIT {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(15);

    private static final String TIMERS_ID = "upgrade-timers";

    private static final String FORK_RETRY_ID = "upgrade-fork-retry";

    private static final String FORK_RACE_ID = "upgrade-fork-race";

    private static final String FIRE_HANDLER = "jobs/delayed/vertique.workflow.timer.fire/execute";

    private static final String OTHER_HANDLER = "jobs/delayed/some.other.job/execute";

    /** Every state of a delayed job, as the job module's state machine names them. */
    private static final List<String> JOB_STATES =
            List.of("ENQUEUED", "PROCESSING", "FAILED", "ABANDONED", "SUCCEEDED", "CANCELLED", "DEAD_LETTER");

    /** What stays in a job table after the live timer fire jobs are deleted. */
    private static final List<String> KEPT_JOB_ROWS = List.of(
            FIRE_HANDLER + "|SUCCEEDED",
            FIRE_HANDLER + "|CANCELLED",
            FIRE_HANDLER + "|DEAD_LETTER",
            OTHER_HANDLER + "|ENQUEUED");

    static final PostgresContainer hashProbeDb = new PostgresContainer()
            .withDatabaseName("wf_upgrade_hash_it")
            .withMigration("classpath:db/migration/workflow");

    static final PostgresContainer timersDb = new PostgresContainer().withDatabaseName("wf_upgrade_timers_it");

    static final PostgresContainer holdBackDb = new PostgresContainer().withDatabaseName("wf_upgrade_holdback_it");

    static final PostgresContainer branchesDb = new PostgresContainer().withDatabaseName("wf_upgrade_branches_it");

    static final PostgresContainer jobsDefaultDb = new PostgresContainer().withDatabaseName("wf_upgrade_jobs_a_it");

    static final PostgresContainer noJobsDb = new PostgresContainer().withDatabaseName("wf_upgrade_jobs_b_it");

    static final PostgresContainer jobsViewDb = new PostgresContainer().withDatabaseName("wf_upgrade_jobs_d_it");

    static final PostgresContainer jobsMappedDb = new PostgresContainer().withDatabaseName("wf_upgrade_jobs_c_it");

    /** How many times each branch step recorded its service call. */
    private static final Map<String, AtomicInteger> STEP_RUNS = new ConcurrentHashMap<>();

    private static final WorkflowSideEffectRecorder<SqlClient> STEP_COUNTER = new WorkflowSideEffectRecorder<>() {

        @Override
        public IntentKind kind() {
            return IntentKind.SERVICE;
        }

        @Override
        public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
            STEP_RUNS
                    .computeIfAbsent(intent.correlation().stepId(), step -> new AtomicInteger())
                    .incrementAndGet();
            return Future.succeededFuture(RecorderResult.empty());
        }
    };

    private static int runsOf(String stepId) {
        AtomicInteger runs = STEP_RUNS.get(stepId);
        return runs == null ? 0 : runs.get();
    }

    // --- Fixture types: nothing here carries a name annotation, so every name is the binary class name ---

    record TimersStart(String id) {}

    record TimersState(String id, String note) {}

    record ApproveDecision(String comment) {}

    record ShipSignal(String carrier) {}

    interface TimersContract {

        @WorkflowSignal("ship")
        Future<Void> ship(WorkflowInstanceId id, ShipSignal payload, @SignalDedupKey String dedupKey);
    }

    /** A human task {@code review}, then a wait for {@code ship}, then a timer {@code pause}, then done. */
    static final class TimersDefinition implements WorkflowDefinition<TimersState, TimersContract> {

        @Override
        public Class<TimersContract> contract() {
            return TimersContract.class;
        }

        @Override
        public Class<TimersState> stateType() {
            return TimersState.class;
        }

        @Override
        public String definitionId() {
            return TIMERS_ID;
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<TimersState> wf) {
            wf.init(TimersStart.class, start -> new TimersState(start.id(), "started"))
                    .initialStep("review")
                    .task("review")
                    .assignToRole("reviewers")
                    .decision("approve", ApproveDecision.class)
                    .onDecision((state, decision) -> new TimersState(state.id(), decision.comment()))
                    .toStep("await-ship")
                    .build()
                    .waitFor(
                            "await-ship",
                            "ship",
                            ShipSignal.class,
                            (state, signal) -> new TimersState(state.id(), signal.carrier()),
                            "pause")
                    .timer("pause", Duration.ofHours(1))
                    .toStep("done")
                    .complete("done");
        }
    }

    record ForkStart(String id) {}

    record ForkState(String id) {}

    record AfterSignal(String note) {}

    interface ForkRetryContract {}

    /** A fork whose branch {@code b1} counts every run of its step; the join needs both branches. */
    static final class ForkRetryDefinition implements WorkflowDefinition<ForkState, ForkRetryContract> {

        @Override
        public Class<ForkRetryContract> contract() {
            return ForkRetryContract.class;
        }

        @Override
        public Class<ForkState> stateType() {
            return ForkState.class;
        }

        @Override
        public String definitionId() {
            return FORK_RETRY_ID;
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<ForkState> wf) {
            wf.init(ForkStart.class, start -> new ForkState(start.id()))
                    .fork("fork")
                    .branch("b1", "work-b1")
                    .branch("b2", "end-b2")
                    .join("join")
                    .dispatch("work-b1", "work.service", state -> Map.of("id", state.id()), "end-b1")
                    .complete("end-b1")
                    .complete("end-b2")
                    .join("join")
                    .allRequired((state, results) -> state)
                    .toStep("done")
                    .endJoin()
                    .complete("done");
            wf.initialStep("fork");
        }
    }

    interface ForkRaceContract {

        @WorkflowSignal("after")
        Future<Void> after(WorkflowInstanceId id, AfterSignal payload, @SignalDedupKey String dedupKey);
    }

    /** A race fork whose first success wins; the losing branch {@code l} counts every run of its step. */
    static final class ForkRaceDefinition implements WorkflowDefinition<ForkState, ForkRaceContract> {

        @Override
        public Class<ForkRaceContract> contract() {
            return ForkRaceContract.class;
        }

        @Override
        public Class<ForkState> stateType() {
            return ForkState.class;
        }

        @Override
        public String definitionId() {
            return FORK_RACE_ID;
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<ForkState> wf) {
            wf.init(ForkStart.class, start -> new ForkState(start.id()))
                    .fork("fork")
                    .branch("w", "end-w", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .branch("l", "work-l", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .join("join")
                    .complete("end-w")
                    .dispatch("work-l", "work.service", state -> Map.of("id", state.id()), "end-l")
                    .complete("end-l")
                    .join("join")
                    .firstSuccess((state, results) -> state)
                    .toStep("hold")
                    .onFailure("failed")
                    .endJoin()
                    .waitForSignal("hold", "after", AfterSignal.class)
                    .onSignal((state, signal) -> state)
                    .toStepOnSignal("done")
                    .build()
                    .complete("done")
                    .complete("failed");
            wf.initialStep("fork");
        }
    }

    // --- Scenarios ---

    @Test
    void scheduledTimersBecomeCommandsThatRunOnce(Vertx vertx) throws Exception {
        // Given a database at the previous release's schema holding instance A waiting at a timer that is due, with
        // an enqueued fire job; instance B whose scheduled timeout belongs to a wait it has left; and instance C
        // waiting on a task whose due date is in the future, with a reminder that already fired
        Map<String, String> planHashes = currentPlanHashes(vertx);
        flyway(timersDb).target("2").load().migrate();
        createJobTables(timersDb, "public");
        UUID instanceA = UUID.randomUUID();
        UUID instanceB = UUID.randomUUID();
        UUID instanceC = UUID.randomUUID();
        UUID timerA = UUID.randomUUID();
        UUID timerB = UUID.randomUUID();
        UUID timerC = UUID.randomUUID();
        UUID firedReminderC = UUID.randomUUID();
        UUID taskC = UUID.randomUUID();
        Instant dueA = Instant.now().minus(Duration.ofMinutes(5)).truncatedTo(ChronoUnit.SECONDS);
        Instant dueB = Instant.now().minus(Duration.ofMinutes(3)).truncatedTo(ChronoUnit.SECONDS);
        Instant dueC = Instant.now().plus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);
        String carrier =
                "{\"context\":{\"correlation\":{\"id\":\"corr-1\"},\"identity-snapshot\":{\"subject\":\"alice\"}}}";
        try (Connection connection = connection(timersDb)) {
            String planHash = planHashes.get(TIMERS_ID);
            ReleasedRows.instanceAtTimer(connection, TIMERS_ID, instanceA, "pause", timerA, planHash);
            ReleasedRows.timer(connection, timerA, instanceA, "pause", "SCHEDULED", dueA, "STANDALONE", null, carrier);
            jobRow(connection, "job_executions", FIRE_HANDLER, "ENQUEUED");

            ReleasedRows.instanceAtSignal(connection, TIMERS_ID, instanceB, "await-ship", "ship", planHash);
            ReleasedRows.timer(
                    connection, timerB, instanceB, "await-ship", "SCHEDULED", dueB, "SIGNAL_TIMEOUT", null, null);

            ReleasedRows.instanceAtTask(
                    connection, TIMERS_ID, instanceC, taskC, planHash, ApproveDecision.class.getName(), "await-ship");
            ReleasedRows.timer(connection, timerC, instanceC, "review", "SCHEDULED", dueC, "TASK_DUE", taskC, carrier);
            ReleasedRows.dueDate(connection, taskC, timerC, dueC);
            ReleasedRows.timer(
                    connection,
                    firedReminderC,
                    instanceC,
                    "review",
                    "FIRED",
                    Instant.now().minus(Duration.ofDays(1)),
                    "TASK_REMINDER",
                    taskC,
                    null);
        }

        // When the database is migrated to the latest version
        flyway(timersDb).load().migrate();

        // Then each scheduled timer has exactly one timer command of its kind at its fire time, linked by command id
        try (Connection connection = connection(timersDb)) {
            assertThat(timerCommand(connection, timerA)).containsExactly("TIMER|FIRE|PENDING|1|-|" + dueA + "|" + dueA);
            assertThat(timerCommand(connection, timerB))
                    .containsExactly("TIMER|TIMEOUT|PENDING|1|-|" + dueB + "|" + dueB);
            assertThat(timerCommand(connection, timerC))
                    .containsExactly("TIMER|DUE|PENDING|1|" + taskC + "|" + dueC + "|" + dueC);
            assertThat(rows(connection, """
                            SELECT count(*) FROM workflow_timers t JOIN workflow_commands c ON c.id = t.command_id
                            WHERE t.timer_id IN ('%s', '%s', '%s') AND c.timer_id = t.timer_id
                            """.formatted(timerA, timerB, timerC))).containsExactly("3");
            assertThat(rows(connection, "SELECT count(*) FROM workflow_commands"))
                    .containsExactly("3");

            // And a timer that already fired gets no command
            assertThat(rows(connection, """
                            SELECT coalesce(command_id::text, '-') FROM workflow_timers WHERE timer_id = '%s'
                            """.formatted(firedReminderC))).containsExactly("-");

            // And each instance has a queue row that counts the converted command and no open signal
            assertThat(rows(connection, """
                            SELECT workflow_id || '|' || next_seq || '|' || open_signals
                            FROM workflow_command_queues
                            """))
                    .containsExactlyInAnyOrder(instanceA + "|2|0", instanceB + "|2|0", instanceC + "|2|0");

            // And every existing instance is marked as not yet baselined
            assertThat(rows(connection, "SELECT baseline_logged::text FROM workflow_instances"))
                    .containsExactly("false", "false", "false");

            // And the converted timers carry no identity snapshot but keep their other context
            assertThat(rows(connection, """
                            SELECT coalesce(metadata->'context'->'identity-snapshot', 'null'::jsonb)::text
                                   || '|' || (metadata->'context'->'correlation'->>'id')
                            FROM workflow_timers WHERE timer_id IN ('%s', '%s')
                            """.formatted(timerA, timerC))).containsExactly("null|corr-1", "null|corr-1");

            // And the live fire job of the old fire executor is gone
            assertThat(rows(connection, "SELECT count(*) FROM job_executions")).containsExactly("0");
        }

        // And when an executor engine runs the converted commands until it is idle
        Pool pool = openPool(vertx, timersDb);
        try {
            WorkflowEngineHandle engine = runningEngineOver(vertx, pool);
            await(engine.executor().start());
            try {
                await(engine.executionProbe().awaitAllIdle(IDLE_TIMEOUT));
            } finally {
                await(engine.executor().stop());
            }
        } finally {
            await(pool.close());
        }

        // Then A's command completed and A moved past the timer once
        try (Connection connection = connection(timersDb)) {
            assertThat(commandState(connection, timerA)).containsExactly("COMPLETE");
            assertThat(rows(connection, """
                            SELECT status || '|' || current_step_id FROM workflow_instances WHERE id = '%s'
                            """.formatted(instanceA))).containsExactly("COMPLETED|done");
            assertThat(rows(connection, """
                            SELECT count(*) FROM workflow_history
                            WHERE workflow_id = '%s' AND entry_type = 'TIMER_FIRED'
                            """.formatted(instanceA))).containsExactly("1");
            assertThat(rows(connection, "SELECT status FROM workflow_timers WHERE timer_id = '%s'".formatted(timerA)))
                    .containsExactly("FIRED");

            // And B's command settled as stale and B did not move
            assertThat(commandState(connection, timerB)).containsExactly("STALE");
            assertThat(rows(connection, """
                            SELECT status || '|' || current_step_id || '|' || wait_type || '|' || wait_key
                                   || '|' || (state_json = '{"id":"%s","note":"started"}'::jsonb)
                            FROM workflow_instances WHERE id = '%s'
                            """.formatted(instanceB, instanceB)))
                    .containsExactly("WAITING|await-ship|SIGNAL|ship|true");

            // And C's due-date command still waits for its due time and C did not move
            assertThat(timerCommand(connection, timerC))
                    .containsExactly("TIMER|DUE|PENDING|1|" + taskC + "|" + dueC + "|" + dueC);
            assertThat(rows(connection, """
                            SELECT status || '|' || current_step_id || '|' || wait_type
                            FROM workflow_instances WHERE id = '%s'
                            """.formatted(instanceC))).containsExactly("WAITING|review|TASK");
        }
    }

    @Test
    void retryScheduledBranchesReDriveTheirBranchOnce(Vertx vertx) throws Exception {
        // Given a database at the previous release's schema holding a fork instance whose branch b1 has a retry
        // scheduled in the past, and a second fork instance whose race join already decided while its losing branch l
        // still had a retry scheduled without a due time
        Map<String, String> planHashes = currentPlanHashes(vertx);
        STEP_RUNS.clear();
        flyway(branchesDb).target("2").load().migrate();
        UUID retrying = UUID.randomUUID();
        UUID decided = UUID.randomUUID();
        Instant retryDue = Instant.now().minus(Duration.ofMinutes(5)).truncatedTo(ChronoUnit.SECONDS);
        try (Connection connection = connection(branchesDb)) {
            ReleasedRows.instanceAtJoin(
                    connection,
                    FORK_RETRY_ID,
                    retrying,
                    "fork",
                    "join",
                    "ALL_REQUIRED",
                    "OPEN",
                    null,
                    planHashes.get(FORK_RETRY_ID));
            ReleasedRows.branchToken(connection, retrying, "fork", "b1", "work-b1", "RETRY_SCHEDULED", retryDue);
            ReleasedRows.branchToken(connection, retrying, "fork", "b2", "end-b2", "COMPLETED", null);

            ReleasedRows.instanceAtJoin(
                    connection,
                    FORK_RACE_ID,
                    decided,
                    "fork",
                    "hold",
                    "FIRST_SUCCESS",
                    "COMPLETED",
                    "w",
                    planHashes.get(FORK_RACE_ID));
            ReleasedRows.branchToken(connection, decided, "fork", "w", "end-w", "COMPLETED", null);
            ReleasedRows.branchToken(connection, decided, "fork", "l", "work-l", "RETRY_SCHEDULED", null);
            // The decided race left its instance at the wait after the join
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        UPDATE workflow_instances SET wait_type = 'SIGNAL', wait_key = 'after'
                        WHERE id = '%s'
                        """.formatted(decided));
            }
        }

        // When the database is migrated to the latest version
        flyway(branchesDb).load().migrate();

        // Then each scheduled retry became one branch-addressed retry command, and its branch runs again
        try (Connection connection = connection(branchesDb)) {
            assertThat(rows(connection, """
                            SELECT workflow_id || '|' || kind || '|' || state || '|' || (target_json->>'forkStepId')
                                   || '|' || (target_json->>'branchId')
                            FROM workflow_commands
                            """))
                    .containsExactlyInAnyOrder(retrying + "|RETRY|PENDING|fork|b1", decided + "|RETRY|PENDING|fork|l");
            assertThat(rows(connection, """
                            SELECT (available_at = '%s'::timestamptz)::text FROM workflow_commands
                            WHERE workflow_id = '%s'
                            """.formatted(retryDue, retrying))).containsExactly("true");
            assertThat(rows(connection, """
                            SELECT (available_at <= now())::text FROM workflow_commands WHERE workflow_id = '%s'
                            """.formatted(decided))).containsExactly("true");
            assertThat(rows(connection, """
                            SELECT branch_id || '|' || status FROM workflow_branch_tokens
                            WHERE status NOT IN ('COMPLETED')
                            """)).containsExactlyInAnyOrder("b1|RUNNING", "l|RUNNING");
            assertThat(rows(connection, "SELECT count(*) FROM workflow_branch_tokens WHERE status = 'RETRY_SCHEDULED'"))
                    .containsExactly("0");
            assertThat(rows(connection, "SELECT baseline_logged::text FROM workflow_instances"))
                    .containsExactly("false", "false");
        }

        // And when an executor engine runs the commands until it is idle
        Pool pool = openPool(vertx, branchesDb);
        try {
            WorkflowEngineHandle engine = runningEngineOver(vertx, pool);
            await(engine.executor().start());
            try {
                await(engine.executionProbe().awaitAllIdle(IDLE_TIMEOUT));
            } finally {
                await(engine.executor().stop());
            }
        } finally {
            await(pool.close());
        }

        // Then b1 ran its step once and the join proceeded
        assertThat(runsOf("work-b1")).isEqualTo(1);
        try (Connection connection = connection(branchesDb)) {
            assertThat(rows(connection, """
                            SELECT state FROM workflow_commands WHERE workflow_id = '%s'
                            """.formatted(retrying))).containsExactly("COMPLETE");
            assertThat(rows(connection, """
                            SELECT status FROM workflow_branch_tokens
                            WHERE workflow_id = '%s' AND branch_id = 'b1'
                            """.formatted(retrying))).containsExactly("COMPLETED");
            assertThat(rows(connection, """
                            SELECT status FROM workflow_join_states WHERE workflow_id = '%s'
                            """.formatted(retrying))).containsExactly("COMPLETED");
            assertThat(rows(connection, "SELECT status FROM workflow_instances WHERE id = '%s'".formatted(retrying)))
                    .containsExactly("COMPLETED");

            // And the command of the branch that lost the race settled as stale without running its step
            assertThat(rows(connection, """
                            SELECT state FROM workflow_commands WHERE workflow_id = '%s'
                            """.formatted(decided))).containsExactly("STALE");
            assertThat(runsOf("work-l")).isZero();
            assertThat(rows(connection, """
                            SELECT status || '|' || wait_type || '|' || wait_key FROM workflow_instances
                            WHERE id = '%s'
                            """.formatted(decided))).containsExactly("WAITING|SIGNAL|after");

            // And no branch is left in the retired retry status
            assertThat(rows(connection, "SELECT count(*) FROM workflow_branch_tokens WHERE status = 'RETRY_SCHEDULED'"))
                    .containsExactly("0");
        }
    }

    @Test
    void convertedTimeoutWaitsForAnEarlierPendingSignal(Vertx vertx) throws Exception {
        // Given an upgraded database holding an instance at a wait whose scheduled timeout was converted, and a
        // signal for that instance recorded before the timeout's fire time that is still pending
        Map<String, String> planHashes = currentPlanHashes(vertx);
        flyway(holdBackDb).target("2").load().migrate();
        UUID instance = UUID.randomUUID();
        UUID timer = UUID.randomUUID();
        Instant due = Instant.now().minus(Duration.ofMinutes(3)).truncatedTo(ChronoUnit.SECONDS);
        try (Connection connection = connection(holdBackDb)) {
            ReleasedRows.instanceAtSignal(
                    connection, TIMERS_ID, instance, "await-ship", "ship", planHashes.get(TIMERS_ID));
            ReleasedRows.timer(
                    connection, timer, instance, "await-ship", "SCHEDULED", due, "SIGNAL_TIMEOUT", null, null);
        }
        flyway(holdBackDb).load().migrate();
        CommandEligibilitySql eligibility = new CommandEligibilitySql(DbSchemas.empty());
        try (Connection connection = connection(holdBackDb)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO workflow_commands (workflow_id, seq, kind, target_json, state, recorded_at,
                                                       available_at)
                        VALUES ('%s', 2, 'SIGNAL', '{"name":"ship"}'::jsonb, 'PENDING',
                                '%s'::timestamptz - interval '1 minute', now())
                        """.formatted(instance, due));
            }

            // When the timeout's eligibility is evaluated
            // Then it is held back and not claimable while the earlier signal is pending
            assertThat(rows(
                            connection,
                            "SELECT " + eligibility.heldBack("c")
                                    + "::text FROM workflow_commands c WHERE c.timer_id = '%s'".formatted(timer)))
                    .containsExactly("true");
            assertThat(rows(
                            connection,
                            "SELECT " + eligibility.claimable("c")
                                    + "::text FROM workflow_commands c WHERE c.timer_id = '%s'".formatted(timer)))
                    .containsExactly("false");

            // And it is released once the signal has applied
            try (Statement statement = connection.createStatement()) {
                statement.execute("UPDATE workflow_commands SET state = 'COMPLETE' WHERE kind = 'SIGNAL'");
            }
            assertThat(rows(
                            connection,
                            "SELECT " + eligibility.claimable("c")
                                    + "::text FROM workflow_commands c WHERE c.timer_id = '%s'".formatted(timer)))
                    .containsExactly("true");
        }
    }

    @Test
    void migrationDeletesOnlyLiveTimerFireJobsUnderTheDefaultTableName() throws Exception {
        // Given three databases at the previous release's schema: (a) job tables under the default name next to the
        // workflow tables, holding a timer fire job in each state and an enqueued job of another handler; (b) no job
        // tables; (c) the same job rows in a separate schema
        flyway(jobsDefaultDb).target("2").load().migrate();
        createJobTables(jobsDefaultDb, "public");
        try (Connection connection = connection(jobsDefaultDb)) {
            seedJobRows(connection, "job_executions");
        }
        flyway(noJobsDb).target("2").load().migrate();
        flyway(jobsMappedDb).target("2").load().migrate();
        createJobTables(jobsMappedDb, "jobs");
        try (Connection connection = connection(jobsMappedDb)) {
            seedJobRows(connection, "jobs.job_executions");
        }

        // When each database is migrated to the latest version
        flyway(jobsDefaultDb).load().migrate();
        flyway(noJobsDb).load().migrate();
        flyway(jobsMappedDb).load().migrate();

        // Then in (a) no fire job is live while the terminal fire jobs and the other handler's job remain
        try (Connection connection = connection(jobsDefaultDb)) {
            assertThat(jobRows(connection, "job_executions")).containsExactlyInAnyOrderElementsOf(KEPT_JOB_ROWS);
        }

        // And (b) migrated without error to the latest version
        try (Connection connection = connection(noJobsDb)) {
            assertThat(rows(connection, "SELECT max(version::int)::text FROM flyway_schema_history WHERE success"))
                    .containsExactly("5");
        }

        // And in (c) the migration left every job row unchanged
        try (Connection connection = connection(jobsMappedDb)) {
            assertThat(jobRows(connection, "jobs.job_executions")).containsExactlyInAnyOrderElementsOf(allJobRows());

            // And the statement the module documents deletes exactly what (a) deleted once it names the schema
            try (Statement statement = connection.createStatement()) {
                statement.execute(documentedManualStatement("jobs"));
            }
            assertThat(jobRows(connection, "jobs.job_executions")).containsExactlyInAnyOrderElementsOf(KEPT_JOB_ROWS);
        }
    }

    @Test
    void migrationLeavesAJobTableNameThatResolvesToAViewAlone() throws Exception {
        // Given a database at the previous release's schema whose unqualified job_executions is a view over the job
        // table of another schema
        flyway(jobsViewDb).target("2").load().migrate();
        createJobTables(jobsViewDb, "jobs");
        try (Connection connection = connection(jobsViewDb)) {
            seedJobRows(connection, "jobs.job_executions");
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE VIEW public.job_executions AS SELECT * FROM jobs.job_executions");
            }
        }

        // When the database is migrated to the latest version
        flyway(jobsViewDb).load().migrate();

        // Then no job row was deleted through the view
        try (Connection connection = connection(jobsViewDb)) {
            assertThat(jobRows(connection, "jobs.job_executions")).containsExactlyInAnyOrderElementsOf(allJobRows());
        }
    }

    // --- Fixtures ---

    private static void seedJobRows(Connection connection, String table) throws SQLException {
        for (String state : JOB_STATES) {
            jobRow(connection, table, FIRE_HANDLER, state);
        }
        jobRow(connection, table, OTHER_HANDLER, "ENQUEUED");
    }

    private static List<String> allJobRows() {
        List<String> all = new ArrayList<>();
        for (String state : JOB_STATES) {
            all.add(FIRE_HANDLER + "|" + state);
        }
        all.add(OTHER_HANDLER + "|ENQUEUED");
        return all;
    }

    /** Inserts a job execution of the handler in the state into the job table. */
    private static void jobRow(Connection connection, String table, String handler, String state) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + table + " (job_id, job_type, handler, state) VALUES (?, 'DELAYED', ?, ?)")) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, handler);
            insert.setString(3, state);
            insert.executeUpdate();
        }
    }

    private static List<String> jobRows(Connection connection, String table) throws SQLException {
        return rows(connection, "SELECT handler || '|' || state FROM " + table);
    }

    /**
     * Reads the statement that the module documents for job tables outside the default name, with its schema placeholder
     * {@code <schema>} replaced by the given schema. It is the text from the {@code DELETE FROM <schema>.job_executions}
     * line to the first line that ends the statement with a semicolon.
     */
    private static String documentedManualStatement(String schema) throws IOException {
        Path moduleDoc = Path.of("src/main/resources/META-INF/vertique/module.md");
        List<String> lines = Files.readAllLines(moduleDoc);
        StringBuilder statement = new StringBuilder();
        boolean inside = false;
        for (String line : lines) {
            if (!inside && line.contains("DELETE FROM <schema>.job_executions")) {
                inside = true;
            }
            if (inside) {
                statement.append(line.strip()).append(' ');
                if (line.stripTrailing().endsWith(";")) {
                    return statement.toString().strip().replace("<schema>", schema);
                }
            }
        }
        throw new AssertionError("The module documentation holds no DELETE FROM <schema>.job_executions statement");
    }

    /** Creates the job module's tables in the schema with the job module's own migration. */
    private static void createJobTables(PostgresContainer container, String schema) {
        Flyway.configure()
                .dataSource(container.jdbcUrl(), container.username(), container.password())
                .locations("classpath:db/migration/job")
                .table("flyway_job_history")
                .schemas(schema)
                .defaultSchema(schema)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    /** The command rows of a timer as {@code kind|timer kind|state|seq|task|fire at|available at}. */
    private static List<String> timerCommand(Connection connection, UUID timerId) throws SQLException {
        return rows(connection, """
                SELECT kind || '|' || timer_kind || '|' || state || '|' || seq || '|' || coalesce(task_id::text, '-')
                       || '|' || to_char(fire_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                       || '|' || to_char(available_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                FROM workflow_commands WHERE timer_id = '%s'
                """.formatted(timerId));
    }

    private static List<String> commandState(Connection connection, UUID timerId) throws SQLException {
        return rows(connection, "SELECT state FROM workflow_commands WHERE timer_id = '%s'".formatted(timerId));
    }

    private static DefaultWorkflowRegistry registry() {
        WorkflowPlanValidator validator =
                new WorkflowPlanValidator(new DefaultRaceSafetyTargetRegistry(Set.<RaceSafetyTargetContributor>of(
                        builder -> builder.register("work.service", RaceSafety.IGNORE_LATE_RESULT_SAFE))));
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry(validator);
        registry.register(new TimersDefinition());
        registry.register(new ForkRetryDefinition());
        registry.register(new ForkRaceDefinition());
        return registry;
    }

    /** The plan hash the current code computes per definition, read from instances a fresh database stores. */
    private static Map<String, String> currentPlanHashes(Vertx vertx) throws Exception {
        Pool pool = openPool(vertx, hashProbeDb);
        try {
            PgDbExceptionMapper mapper = new PgDbExceptionMapper();
            WorkflowEngineHandle engine = PgWorkflowEngineTestSupport.create(
                    pool,
                    registry(),
                    new PgWorkflowInstanceRepository(pool, mapper),
                    new PgWorkflowHistoryRepository(pool, mapper),
                    new PgWorkflowDedupRepository(pool, mapper),
                    Set.of(STEP_COUNTER),
                    Set.of(IntentKind.WORKFLOW_EVENT),
                    new PgTimerStore(mapper),
                    new PgTaskStore(pool, mapper),
                    Clock.systemUTC(),
                    new PgBranchTokenRepository(pool, mapper),
                    new PgJoinStateRepository(pool, mapper));
            Map<String, Object> starts = Map.of(
                    TIMERS_ID, new TimersStart("probe"),
                    FORK_RETRY_ID, new ForkStart("probe"),
                    FORK_RACE_ID, new ForkStart("probe"));
            Map<String, String> contracts = Map.of(
                    TIMERS_ID, TimersContract.class.getName(),
                    FORK_RETRY_ID, ForkRetryContract.class.getName(),
                    FORK_RACE_ID, ForkRaceContract.class.getName());
            for (String definitionId : starts.keySet()) {
                WorkflowInstanceId probed = await(engine.start(new StartCommand(
                        definitionId,
                        contracts.get(definitionId),
                        starts.get(definitionId),
                        "idem-probe-" + definitionId,
                        null,
                        null,
                        null,
                        null,
                        null)));
                await(engine.executionProbe().awaitIdle(probed, IDLE_TIMEOUT));
            }
        } finally {
            await(pool.close());
        }
        Map<String, String> hashes = new HashMap<>();
        try (Connection connection = connection(hashProbeDb)) {
            for (String row : rows(connection, "SELECT definition_id || '|' || plan_hash FROM workflow_instances")) {
                String[] parts = row.split("\\|");
                hashes.put(parts[0], parts[1]);
            }
        }
        return hashes;
    }

    /** An engine whose executor, which the test starts, runs every command of the database. */
    private static WorkflowEngineHandle runningEngineOver(Vertx vertx, Pool pool) {
        return PgWorkflowEngineTestSupport.executorEngine(pool, vertx, registry())
                .recorders(Set.of(STEP_COUNTER))
                .executorConfig(new WorkflowExecutorConfig(1, 8, 100, 60))
                .build();
    }

    private static Pool openPool(Vertx vertx, PostgresContainer database) {
        DbPoolConfig config = database.toPoolConfig();
        return PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(4))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
    }

    private static FluentConfiguration flyway(PostgresContainer container) {
        return Flyway.configure()
                .dataSource(container.jdbcUrl(), container.username(), container.password())
                .locations("classpath:db/migration/workflow");
    }

    private static Connection connection(PostgresContainer container) throws SQLException {
        return DriverManager.getConnection(container.jdbcUrl(), container.username(), container.password());
    }

    private static List<String> rows(Connection connection, String sql) throws SQLException {
        try (Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }
}
