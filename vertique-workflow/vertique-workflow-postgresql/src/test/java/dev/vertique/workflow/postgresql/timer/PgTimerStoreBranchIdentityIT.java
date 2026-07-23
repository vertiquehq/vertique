// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.timer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.timer.TimerPurpose;
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import java.time.Instant;
import java.util.Optional;
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
 * Integration tests proving {@link PgTimerStore} round-trips the branch-identity columns
 * ({@code branch_token_id}, {@code fork_step_id}, {@code branch_id}) added by the V2
 * migration for PRD-WF-002.
 *
 * <p>The values are required end-to-end for AC #5 (branch-owned timers and branch-owned
 * signal timeouts) and for branch-owned task due-date / reminder timers under AC #4.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class PgTimerStoreBranchIdentityIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_test_timer_branch")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static PgTimerStore store;

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
        store = new PgTimerStore(new PgDbExceptionMapper());
        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        pool.query("TRUNCATE TABLE workflow_timers, workflow_history, workflow_dedup,"
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
        return pool.preparedQuery("INSERT INTO workflow_instances"
                        + " (id, definition_id, definition_version, plan_hash, version,"
                        + "  status, current_step_id, state_json, created_at, updated_at)"
                        + " VALUES ($1, 'branch-timer-def', 1, 'hash1', 0,"
                        + "  $2, 'join-step', '{}', NOW(), NOW())")
                .execute(Tuple.of(workflowId.value(), WorkflowStatus.WAITING.name()))
                .mapEmpty();
    }

    private static TimerRecord branchOwnedTimer(
            UUID timerId, WorkflowInstanceId workflowId, UUID branchTokenId, String forkStepId, String branchId) {
        return new TimerRecord(
                timerId,
                workflowId,
                "branch-timer-step",
                Instant.now().plusSeconds(60),
                TimerStatus.SCHEDULED,
                UUID.randomUUID(),
                Instant.now(),
                null,
                null,
                null,
                null,
                TimerPurpose.STANDALONE,
                null,
                branchTokenId,
                forkStepId,
                branchId,
                DurableMetadata.empty());
    }

    @Test
    @DisplayName("insertScheduled + lockForFiring round-trips branchTokenId, forkStepId, and branchId")
    void roundTripBranchIdentityColumns(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID timerId = UUID.randomUUID();
        UUID branchTokenId = UUID.randomUUID();
        TimerRecord record = branchOwnedTimer(timerId, workflowId, branchTokenId, "request-quotes", "fastship");

        seedInstance(workflowId)
                .compose(v -> pool.withTransaction(tx -> store.insertScheduled(record, tx)))
                .compose(v -> pool.withTransaction(tx -> store.lockForFiring(timerId, tx)))
                .onSuccess((Optional<TimerRecord> opt) -> ctx.verify(() -> {
                    assertTrue(opt.isPresent(), "lockForFiring must return the inserted timer row");
                    TimerRecord found = opt.get();
                    assertEquals(branchTokenId, found.branchTokenId(), "branch_token_id must round-trip");
                    assertEquals("request-quotes", found.forkStepId(), "fork_step_id must round-trip");
                    assertEquals("fastship", found.branchId(), "branch_id must round-trip");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("findScheduledByBranchToken isolates sibling branches in the same workflow instance")
    void findScheduledByBranchTokenIsolatesSiblingBranches(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID timerIdA = UUID.randomUUID();
        UUID timerIdB = UUID.randomUUID();
        UUID branchTokenA = UUID.randomUUID();
        UUID branchTokenB = UUID.randomUUID();
        TimerRecord recordA = branchOwnedTimer(timerIdA, workflowId, branchTokenA, "fork-step", "branch-a");
        TimerRecord recordB = branchOwnedTimer(timerIdB, workflowId, branchTokenB, "fork-step", "branch-b");

        seedInstance(workflowId)
                .compose(v -> pool.withTransaction(tx ->
                        store.insertScheduled(recordA, tx).compose(ignored -> store.insertScheduled(recordB, tx))))
                .compose(v -> pool.withTransaction(tx -> store.findScheduledByBranchToken(branchTokenA, tx)))
                .onSuccess(listA -> ctx.verify(() -> {
                    assertEquals(1, listA.size(), "branchA query must return exactly one timer");
                    assertEquals(timerIdA, listA.get(0).timerId(), "branchA query must return only branchA's timer");
                }))
                .compose(v -> pool.withTransaction(tx -> store.findScheduledByBranchToken(branchTokenB, tx)))
                .onSuccess(listB -> ctx.verify(() -> {
                    assertEquals(1, listB.size(), "branchB query must return exactly one timer");
                    assertEquals(timerIdB, listB.get(0).timerId(), "branchB query must return only branchB's timer");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("insertScheduled + lockForFiring preserves null branch identity for single-path timers")
    void roundTripSinglePathLeavesBranchIdentityNull(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID timerId = UUID.randomUUID();
        TimerRecord record = branchOwnedTimer(timerId, workflowId, null, null, null);

        seedInstance(workflowId)
                .compose(v -> pool.withTransaction(tx -> store.insertScheduled(record, tx)))
                .compose(v -> pool.withTransaction(tx -> store.lockForFiring(timerId, tx)))
                .onSuccess((Optional<TimerRecord> opt) -> ctx.verify(() -> {
                    assertTrue(opt.isPresent());
                    TimerRecord found = opt.get();
                    assertNull(found.branchTokenId(), "single-path timer must have null branch_token_id");
                    assertNull(found.forkStepId(), "single-path timer must have null fork_step_id");
                    assertNull(found.branchId(), "single-path timer must have null branch_id");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }
}
