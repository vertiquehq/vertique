// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.engine.WorkflowTestTables;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.tasks.TaskAssignment;
import dev.vertique.workflow.tasks.TaskDecisionDescriptor;
import dev.vertique.workflow.tasks.TaskRecord;
import dev.vertique.workflow.tasks.TaskStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Verifies that the task reservation members move a task between OPEN and COMPLETING only by compare and set. */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgTaskReservationsIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_test_task_reservations")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName("markCompleting and reopen move a task only from the expected state and owner")
    void markCompletingAndReopenCompareAndSet(Vertx vertx) throws Exception {
        DbPoolConfig config = db.toPoolConfig();
        Pool pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(4))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
        try {
            // Given an open task
            PgDbExceptionMapper mapper = new PgDbExceptionMapper();
            PgTaskStore store = new PgTaskStore(pool, mapper);
            PgTaskReservations reservations = new PgTaskReservations(pool, mapper);
            UUID taskId = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            join(WorkflowTestTables.truncating(
                            pool,
                            "TRUNCATE TABLE workflow_timers, workflow_tasks, workflow_history, workflow_dedup,"
                                    + " workflow_instances RESTART IDENTITY CASCADE")
                    .execute());
            join(pool.withTransaction(
                    tx -> new PgWorkflowInstanceRepository(pool, mapper).insert(waitingInstance(workflowId), tx)));
            join(pool.withTransaction(tx -> store.insertOpen(openTask(taskId, workflowId), tx)));

            // When the task is marked 7, marked 8, reopened as 8, reopened as 7 and reopened as 7 again
            boolean firstMark =
                    join(pool.withTransaction(tx -> reservations.markCompleting(taskId.toString(), 7L, tx)));
            boolean secondMark =
                    join(pool.withTransaction(tx -> reservations.markCompleting(taskId.toString(), 8L, tx)));
            Row reserved = row(pool, taskId);
            boolean reopenByOther = join(pool.withTransaction(tx -> reservations.reopen(taskId.toString(), 8L, tx)));
            boolean reopenByOwner = join(pool.withTransaction(tx -> reservations.reopen(taskId.toString(), 7L, tx)));
            boolean reopenAgain = join(pool.withTransaction(tx -> reservations.reopen(taskId.toString(), 7L, tx)));

            // Then only the first mark and the owner's reopen succeed, and the task ends open without an owner
            assertThat(firstMark).as("first markCompleting").isTrue();
            assertThat(secondMark).as("markCompleting of a reserved task").isFalse();
            assertThat(reserved.getString("status")).isEqualTo("COMPLETING");
            assertThat(reserved.getLong("reservation_command_id")).isEqualTo(7L);
            assertThat(reopenByOther).as("reopen by another owner").isFalse();
            assertThat(reopenByOwner).as("reopen by the owner").isTrue();
            assertThat(reopenAgain).as("reopen of an open task").isFalse();
            Row settled = row(pool, taskId);
            assertThat(settled.getString("status")).isEqualTo(TaskStatus.OPEN.name());
            assertThat(settled.getLong("reservation_command_id")).isNull();
        } finally {
            join(pool.close());
        }
    }

    private static Row row(Pool pool, UUID taskId) throws Exception {
        return join(pool.preparedQuery("SELECT status, reservation_command_id FROM workflow_tasks WHERE task_id=$1")
                        .execute(Tuple.of(taskId)))
                .iterator()
                .next();
    }

    private static <T> T join(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }

    private static WorkflowInstance waitingInstance(WorkflowInstanceId workflowId) {
        return new WorkflowInstance(
                workflowId,
                "task-def",
                1L,
                "hash-abc",
                0L,
                WorkflowStatus.WAITING,
                null,
                null,
                "task-step",
                WaitType.TASK,
                null,
                null,
                "{}",
                null,
                null,
                Instant.now(),
                Instant.now(),
                null);
    }

    private static TaskRecord openTask(UUID taskId, WorkflowInstanceId workflowId) {
        return new TaskRecord(
                taskId,
                workflowId,
                "review-step",
                new TaskAssignment.Role("compliance"),
                TaskStatus.OPEN,
                List.of(new TaskDecisionDescriptor("approve", "java.lang.String", "ship")),
                null,
                null,
                null,
                null,
                null,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
