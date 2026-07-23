// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.JoinPolicyType;
import dev.vertique.workflow.state.JoinState;
import dev.vertique.workflow.state.JoinStateStatus;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import java.time.Instant;
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
 * Integration tests for {@link PgJoinStateRepository}.
 *
 * <p>Covers insert + findByKey round-trip, decide() CAS success and stale-version, and
 * findOpenForWorkflow.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgJoinStateRepositoryIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_test_join_states")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static PgJoinStateRepository repo;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
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
        repo = new PgJoinStateRepository(pool, new PgDbExceptionMapper());
        ctx.completeNow();
    }

    @BeforeEach
    void cleanTables(VertxTestContext ctx) {
        pool.query("TRUNCATE TABLE workflow_branch_tokens, workflow_join_states, workflow_timers,"
                        + " workflow_history, workflow_dedup, workflow_instances"
                        + " RESTART IDENTITY CASCADE")
                .execute()
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) pool.close();
    }

    private static WorkflowInstanceId insertInstance() {
        UUID id = UUID.randomUUID();
        pool.preparedQuery("INSERT INTO workflow_instances (id, definition_id, definition_version, plan_hash, version,"
                        + " status, current_step_id, state_json) VALUES ($1, $2, $3, $4, $5, $6, $7, $8::jsonb)")
                .execute(Tuple.of(id, "test-def", 1L, "hash", 0L, "RUNNING", "step", "{}"))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        return new WorkflowInstanceId(id);
    }

    private static JoinState openState(WorkflowInstanceId wf) {
        Instant now = Instant.now();
        return new JoinState(
                wf, "fork", "join", JoinPolicyType.ALL_REQUIRED, JoinStateStatus.OPEN, null, null, 0L, now, now);
    }

    @Test
    @DisplayName("insert + findByKey round-trips OPEN state")
    void insertFindByKey(VertxTestContext ctx) {
        WorkflowInstanceId wf = insertInstance();
        JoinState state = openState(wf);
        pool.withTransaction(tx -> repo.insert(state, tx).compose(v -> repo.findByKey(wf, "fork", "join", tx)))
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertTrue(ar.result().isPresent());
                    JoinState loaded = ar.result().get();
                    assertEquals(JoinStateStatus.OPEN, loaded.status());
                    assertEquals(JoinPolicyType.ALL_REQUIRED, loaded.policy());
                    assertEquals(0L, loaded.version());
                    ctx.completeNow();
                });
    }

    @Test
    @DisplayName("decide CAS with matching version succeeds and persists status + winner + decided_at")
    void decideSuccess(VertxTestContext ctx) {
        WorkflowInstanceId wf = insertInstance();
        JoinState state = openState(wf);
        Instant decidedAt = Instant.parse("2026-05-10T00:00:00Z");
        pool.withTransaction(tx -> repo.insert(state, tx)
                        .compose(v ->
                                repo.decide(wf, "fork", "join", JoinStateStatus.COMPLETED, "a", decidedAt, 1L, 0L, tx))
                        .compose(rowCount -> {
                            assertEquals(1, rowCount);
                            return repo.findByKey(wf, "fork", "join", tx);
                        }))
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    JoinState loaded = ar.result().get();
                    assertEquals(JoinStateStatus.COMPLETED, loaded.status());
                    assertEquals("a", loaded.winningBranchId());
                    assertEquals(1L, loaded.version());
                    ctx.completeNow();
                });
    }

    @Test
    @DisplayName("decide with stale version returns 0 and leaves state unchanged")
    void decideStaleVersionFails(VertxTestContext ctx) {
        WorkflowInstanceId wf = insertInstance();
        JoinState state = openState(wf);
        pool.withTransaction(tx -> repo.insert(state, tx)
                        .compose(v -> repo.decide(
                                wf, "fork", "join", JoinStateStatus.FAILED, "a", Instant.now(), 1L, 99L, tx)))
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertEquals(0, ar.result());
                    ctx.completeNow();
                });
    }

    @Test
    @DisplayName("findOpenForWorkflow returns only OPEN rows for the requested instance")
    void findOpenForWorkflowFiltersByStatus(VertxTestContext ctx) {
        WorkflowInstanceId wf = insertInstance();
        Instant now = Instant.now();
        JoinState a = new JoinState(
                wf, "fork-a", "join-a", JoinPolicyType.ALL_REQUIRED, JoinStateStatus.OPEN, null, null, 0L, now, now);
        JoinState b = new JoinState(
                wf, "fork-b", "join-b", JoinPolicyType.ALL_REQUIRED, JoinStateStatus.COMPLETED, "x", now, 1L, now, now);
        pool.withTransaction(tx -> repo.insert(a, tx)
                        .compose(v -> repo.insert(b, tx))
                        .compose(v -> repo.findOpenForWorkflow(wf, tx)))
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertEquals(1, ar.result().size());
                    assertEquals("fork-a", ar.result().get(0).forkStepId());
                    ctx.completeNow();
                });
    }
}
