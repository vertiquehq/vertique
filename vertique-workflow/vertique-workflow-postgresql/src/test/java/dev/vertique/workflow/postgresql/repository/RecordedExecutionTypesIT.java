// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.tasks.TaskAssignment;
import dev.vertique.workflow.tasks.TaskDecisionDescriptor;
import dev.vertique.workflow.tasks.TaskReassignmentResult;
import dev.vertique.workflow.tasks.TaskRecord;
import dev.vertique.workflow.tasks.TaskStatus;
import dev.vertique.workflow.tasks.TaskTransition;
import dev.vertique.workflow.timer.TimerPurpose;
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for the public value types that carry the recorded-execution states and links:
 * {@code PENDING} instances, {@code COMPLETING} tasks with decision payload schemas, and timers
 * linked to a command. The rows are inserted by SQL or through the stores and read back through
 * the existing stores.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class RecordedExecutionTypesIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_test_recorded_types")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static PgWorkflowInstanceRepository instances;
    static PgTaskStore tasks;
    static PgTimerStore timers;

    @BeforeAll
    static void setUp(Vertx vertx) {
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(10))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
        PgDbExceptionMapper mapper = new PgDbExceptionMapper();
        instances = new PgWorkflowInstanceRepository(pool, mapper);
        tasks = new PgTaskStore(pool, mapper);
        timers = new PgTimerStore(mapper);
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        pool.query("TRUNCATE TABLE workflow_timers, workflow_tasks, workflow_history, workflow_dedup,"
                        + " workflow_definitions, workflow_instances RESTART IDENTITY CASCADE")
                .execute()
                .onSuccess(rows -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    // --- Fixtures ---

    /** Inserts an {@code AVAILABLE} code definition row. */
    private static Future<Void> codeRow(String id, long version) {
        return pool.preparedQuery("""
                        INSERT INTO workflow_definitions
                            (scope_key, definition_id, definition_version, kind, contract_name, state_type_name,
                             plan_hash, status, source_type, loaded_at)
                        VALUES ('default', $1, $2, 'CODE', 'example.Contract', 'example.State',
                                repeat('a', 64), 'AVAILABLE', 'types-test', now())
                        """).execute(Tuple.of(id, version)).mapEmpty();
    }

    /** Inserts a {@code PENDING} instance without state, step or subject, pinned to {@code order} v1. */
    private static Future<Void> insertPendingInstance(WorkflowInstanceId id, String key) {
        return pool.preparedQuery("""
                        INSERT INTO workflow_instances
                            (id, definition_id, definition_version, plan_hash, status, business_key,
                             current_step_id, state_json)
                        VALUES ($1, 'order', 1, repeat('a', 64), 'PENDING', $2, NULL, NULL)
                        """).execute(Tuple.of(id.value(), key)).mapEmpty();
    }

    /** Inserts a running instance so that tasks and timers can reference it. */
    private static Future<Void> insertRunningInstance(WorkflowInstanceId id) {
        return pool.preparedQuery("""
                        INSERT INTO workflow_instances
                            (id, definition_id, definition_version, plan_hash, version, status,
                             current_step_id, state_json, created_at, updated_at)
                        VALUES ($1, 'order', 1, repeat('a', 64), 0, 'RUNNING', 'start', '{}', now(), now())
                        """).execute(Tuple.of(id.value())).mapEmpty();
    }

    /** Inserts an {@code OPEN} task whose snapshot holds {@code approve} with a schema and {@code reject} without. */
    private static Future<Void> openTaskWithDecisions(UUID taskId, WorkflowInstanceId workflowId, JsonObject schema) {
        List<TaskDecisionDescriptor> decisions = List.of(
                new TaskDecisionDescriptor("approve", "java.lang.Object", "ship", schema),
                new TaskDecisionDescriptor("reject", "java.lang.Object", "notify", null));
        TaskRecord record = new TaskRecord(
                taskId,
                workflowId,
                "review-step",
                new TaskAssignment.Role("reviewer"),
                TaskStatus.OPEN,
                decisions,
                null, // dueAt
                null, // dueDateTimerId
                null, // completedAt
                null, // cancelledAt
                null, // expiredAt
                Instant.now(), // updatedAt
                null, // decisionName
                null, // decisionPayloadJson
                null, // completedBy
                null, // cancelledBy
                null, // reassignedBy
                null, // cancellationReason
                null, // reassignmentReason
                null, // subjectVersionAtCreation
                null, // branchTokenId
                null, // forkStepId
                null); // branchId
        return pool.withTransaction(tx -> tasks.insertOpen(record, tx));
    }

    /** Moves the task to {@code COMPLETING}, reserved by the given command. */
    private static Future<Void> markCompleting(UUID taskId, long commandId) {
        return pool.preparedQuery("UPDATE workflow_tasks SET status = 'COMPLETING', reservation_command_id = $2"
                        + " WHERE task_id = $1")
                .execute(Tuple.of(taskId, commandId))
                .mapEmpty();
    }

    /** Builds a scheduled timer carrying the given command link. */
    private static TimerRecord timer(UUID timerId, WorkflowInstanceId workflowId, Long commandId) {
        return new TimerRecord(
                timerId,
                workflowId,
                "wait",
                Instant.now().plusSeconds(60),
                TimerStatus.SCHEDULED,
                Instant.now(),
                null,
                null,
                null,
                null,
                TimerPurpose.STANDALONE,
                null,
                null, // branchTokenId
                null, // forkStepId
                null, // branchId
                DurableMetadata.empty(),
                commandId);
    }

    // --- Tests ---

    @Test
    @DisplayName("A PENDING instance without state or current step reads back as a non-terminal instance")
    void pendingInstanceReadsWithoutState(VertxTestContext ctx) {
        // Given an available code row and a PENDING instance row without state, step or subject
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());

        codeRow("order", 1)
                .compose(v -> insertPendingInstance(id, "order-1"))
                // When the instance is read through the repository
                .compose(v -> instances.findById(id))
                // Then it reads as PENDING with no state, step or subject, and PENDING is not terminal
                .onSuccess(found -> ctx.verify(() -> {
                    assertThat(found).isPresent();
                    WorkflowInstance instance = found.get();
                    assertThat(instance.status()).isEqualTo(WorkflowStatus.PENDING);
                    assertThat(instance.stateJson()).isNull();
                    assertThat(instance.currentStepId()).isNull();
                    assertThat(instance.subjectRef()).isNull();
                    assertThat(repositoryTerminalStatuses()).doesNotContain(WorkflowStatus.PENDING);
                    assertThat(engineIsTerminal(WorkflowStatus.PENDING)).isFalse();
                    assertThat(engineIsTerminal(WorkflowStatus.COMPLETED)).isTrue();
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("A COMPLETING task reads back with its decision schemas and refuses reassignment")
    void completingTaskReadsBackAndRefusesReassignment(VertxTestContext ctx) {
        // Given an instance and an open task whose snapshot holds approve with a schema and reject without one,
        // moved to COMPLETING by a command reservation
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID taskId = UUID.randomUUID();
        JsonObject schema = new JsonObject().put("type", "object").put("required", List.of("amount"));

        insertRunningInstance(workflowId)
                .compose(v -> openTaskWithDecisions(taskId, workflowId, schema))
                .compose(v -> markCompleting(taskId, 7L))
                // When the task is read and a reassignment is attempted
                .compose(v -> pool.withTransaction(tx -> tasks.findById(taskId, tx)))
                .compose(found -> {
                    ctx.verify(() -> {
                        // Then it reads as COMPLETING with the schema on approve and none on reject
                        assertThat(found).isPresent();
                        TaskRecord task = found.get();
                        assertThat(task.status()).isEqualTo(TaskStatus.COMPLETING);
                        assertThat(task.decisions())
                                .extracting(TaskDecisionDescriptor::name)
                                .containsExactly("approve", "reject");
                        assertThat(task.decisions().get(0).payloadSchema()).isEqualTo(schema);
                        assertThat(task.decisions().get(1).payloadSchema()).isNull();
                    });
                    return pool.withTransaction(tx -> tasks.reassign(
                            taskId,
                            new TaskAssignment.User("alice"),
                            new WorkflowActor.User("manager"),
                            "not allowed",
                            Instant.now(),
                            tx));
                })
                .compose(result -> {
                    // And the reassignment is refused as lost to a completed task
                    ctx.verify(() -> {
                        assertThat(result).isInstanceOf(TaskReassignmentResult.LostToTerminal.class);
                        assertThat(((TaskReassignmentResult.LostToTerminal) result).transition())
                                .isEqualTo(TaskTransition.LOST_TO_COMPLETED);
                    });
                    return pool.preparedQuery("SELECT status, assignee_type, assignee_key, reassignment_reason"
                                    + " FROM workflow_tasks WHERE task_id = $1")
                            .execute(Tuple.of(taskId));
                })
                // And the row is unchanged
                .onSuccess(rows -> ctx.verify(() -> {
                    Row row = rows.iterator().next();
                    assertThat(row.getString("status")).isEqualTo("COMPLETING");
                    assertThat(row.getString("assignee_type")).isEqualTo("ROLE");
                    assertThat(row.getString("assignee_key")).isEqualTo("reviewer");
                    assertThat(row.getString("reassignment_reason")).isNull();
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("Timers carry their command link, and a timer row of an earlier release reads back without one")
    void timerRecordsCarryTheirCommandLink(VertxTestContext ctx) {
        // Given an instance, a timer with command 42, and a timer row of an earlier release, which names a delayed job
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID linkedId = UUID.randomUUID();
        UUID delayedId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        insertRunningInstance(workflowId)
                // When the linked timer is inserted and read, and the earlier row is inserted by SQL and read
                .compose(v -> pool.withTransaction(tx -> timers.insertScheduled(timer(linkedId, workflowId, 42L), tx)))
                .compose(v -> pool.preparedQuery("""
                                INSERT INTO workflow_timers
                                    (timer_id, workflow_id, step_id, status, fire_at, scheduled_at,
                                     delayed_job_execution_id)
                                VALUES ($1, $2, 'wait', 'SCHEDULED', now(), now(), $3)
                                """).execute(Tuple.of(delayedId, workflowId.value(), executionId)))
                .compose(v -> pool.withTransaction(tx -> timers.findById(linkedId, tx)))
                .compose(linked -> {
                    // Then the linked timer reads its command, and the column holds 42
                    ctx.verify(() -> {
                        assertThat(linked).isPresent();
                        assertThat(linked.get().commandId()).isEqualTo(42L);
                    });
                    return pool.preparedQuery("SELECT command_id FROM workflow_timers WHERE timer_id = $1")
                            .execute(Tuple.of(linkedId));
                })
                .compose(rows -> {
                    ctx.verify(() -> assertThat(rows.iterator().next().getLong("command_id"))
                            .isEqualTo(42L));
                    return pool.withTransaction(tx -> timers.findById(delayedId, tx));
                })
                // And the earlier row reads and has no command
                .onSuccess(delayed -> ctx.verify(() -> {
                    assertThat(delayed).isPresent();
                    assertThat(delayed.get().commandId()).isNull();
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // --- Terminal-status accessors (the terminal sets are private to their owners) ---

    @SuppressWarnings("unchecked")
    private static Set<WorkflowStatus> repositoryTerminalStatuses() throws ReflectiveOperationException {
        Field field = PgWorkflowInstanceRepository.class.getDeclaredField("TERMINAL_STATUSES");
        field.setAccessible(true);
        return (Set<WorkflowStatus>) field.get(null);
    }

    private static boolean engineIsTerminal(WorkflowStatus status) throws ReflectiveOperationException {
        Method method = Class.forName("dev.vertique.workflow.engine.WorkflowEngine")
                .getDeclaredMethod("isTerminal", WorkflowStatus.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, status);
    }
}
