// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
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
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import java.time.Instant;
import java.util.List;
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
 * Integration tests proving {@link PgTaskStore} round-trips the branch-identity columns
 * ({@code branch_token_id}, {@code fork_step_id}, {@code branch_id}) added by the V2
 * migration for PRD-WF-002.
 *
 * <p>These columns are nullable for backward compatibility with single-path workflows, but the
 * V1 implementation of {@code insertOpen}/{@code mapRow} dropped the values silently. AC #4
 * (branch-owned human tasks) requires that the values flow end-to-end.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class PgTaskStoreBranchIdentityIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_test_tasks_branch")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static PgTaskStore store;
    static dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository instanceRepo;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(4))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
        PgDbExceptionMapper exMapper = new PgDbExceptionMapper();
        store = new PgTaskStore(pool, exMapper);
        instanceRepo = new dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository(pool, exMapper);
        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        pool.query("TRUNCATE TABLE workflow_timers, workflow_tasks, workflow_history, workflow_dedup,"
                        + " workflow_branch_tokens, workflow_join_states, workflow_instances"
                        + " RESTART IDENTITY CASCADE")
                .execute()
                .onComplete(ar -> ctx.completeNow());
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    private Future<Void> seedInstance(WorkflowInstanceId workflowId) {
        WorkflowInstance inst = new WorkflowInstance(
                workflowId,
                "branch-task-def",
                1L,
                "hash-abc",
                0L,
                WorkflowStatus.WAITING,
                null,
                null,
                "join-step",
                WaitType.JOIN,
                null,
                null,
                "{}",
                null,
                null,
                Instant.now(),
                Instant.now(),
                null);
        return pool.withTransaction(tx -> instanceRepo.insert(inst, tx));
    }

    private static TaskRecord branchOwnedTask(
            UUID taskId, WorkflowInstanceId workflowId, UUID branchTokenId, String forkStepId, String branchId) {
        List<TaskDecisionDescriptor> decisions =
                List.of(new TaskDecisionDescriptor("approve", "java.lang.String", "next"));
        return new TaskRecord(
                taskId,
                workflowId,
                "branch-task-step",
                new TaskAssignment.Role("reviewers"),
                TaskStatus.OPEN,
                decisions,
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
                branchTokenId,
                forkStepId,
                branchId);
    }

    @Test
    @DisplayName("insertOpen + findById round-trips branchTokenId, forkStepId, and branchId")
    void roundTripBranchIdentityColumns(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID taskId = UUID.randomUUID();
        UUID branchTokenId = UUID.randomUUID();
        TaskRecord record = branchOwnedTask(taskId, workflowId, branchTokenId, "reserve-order", "inventory");

        seedInstance(workflowId)
                .compose(v -> pool.withTransaction(tx -> store.insertOpen(record, tx)))
                .compose(v -> pool.withTransaction(tx -> store.findById(taskId, tx)))
                .onSuccess(opt -> ctx.verify(() -> {
                    assertTrue(opt.isPresent(), "findById must return the inserted task row");
                    TaskRecord found = opt.get();
                    assertEquals(branchTokenId, found.branchTokenId(), "branch_token_id must round-trip");
                    assertEquals("reserve-order", found.forkStepId(), "fork_step_id must round-trip");
                    assertEquals("inventory", found.branchId(), "branch_id must round-trip");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("findOpenByBranchToken isolates sibling branches in the same workflow instance")
    void findOpenByBranchTokenIsolatesSiblingBranches(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID taskIdA = UUID.randomUUID();
        UUID taskIdB = UUID.randomUUID();
        UUID branchTokenA = UUID.randomUUID();
        UUID branchTokenB = UUID.randomUUID();
        TaskRecord recordA = branchOwnedTask(taskIdA, workflowId, branchTokenA, "fork-step", "branch-a");
        TaskRecord recordB = branchOwnedTask(taskIdB, workflowId, branchTokenB, "fork-step", "branch-b");

        seedInstance(workflowId)
                .compose(v -> pool.withTransaction(
                        tx -> store.insertOpen(recordA, tx).compose(ignored -> store.insertOpen(recordB, tx))))
                .compose(v -> pool.withTransaction(tx -> store.findOpenByBranchToken(branchTokenA, tx)))
                .onSuccess(listA -> ctx.verify(() -> {
                    assertEquals(1, listA.size(), "branchA query must return exactly one task");
                    assertEquals(taskIdA, listA.get(0).taskId(), "branchA query must return only branchA's task");
                }))
                .compose(v -> pool.withTransaction(tx -> store.findOpenByBranchToken(branchTokenB, tx)))
                .onSuccess(listB -> ctx.verify(() -> {
                    assertEquals(1, listB.size(), "branchB query must return exactly one task");
                    assertEquals(taskIdB, listB.get(0).taskId(), "branchB query must return only branchB's task");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("insertOpen + findById preserves null branch identity for single-path tasks")
    void roundTripSinglePathLeavesBranchIdentityNull(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID taskId = UUID.randomUUID();
        TaskRecord record = branchOwnedTask(taskId, workflowId, null, null, null);

        seedInstance(workflowId)
                .compose(v -> pool.withTransaction(tx -> store.insertOpen(record, tx)))
                .compose(v -> pool.withTransaction(tx -> store.findById(taskId, tx)))
                .onSuccess(opt -> ctx.verify(() -> {
                    assertTrue(opt.isPresent());
                    TaskRecord found = opt.get();
                    assertNull(found.branchTokenId(), "single-path task must have null branch_token_id");
                    assertNull(found.forkStepId(), "single-path task must have null fork_step_id");
                    assertNull(found.branchId(), "single-path task must have null branch_id");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }
}
