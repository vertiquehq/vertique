// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.repository.PgBranchTokenRepository;
import dev.vertique.workflow.postgresql.repository.PgJoinStateRepository;
import dev.vertique.workflow.state.BranchStatus;
import dev.vertique.workflow.state.BranchToken;
import dev.vertique.workflow.state.BranchTokenFilter;
import dev.vertique.workflow.state.JoinPolicyType;
import dev.vertique.workflow.state.JoinState;
import dev.vertique.workflow.state.JoinStateStatus;
import dev.vertique.workflow.state.WaitType;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
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
 * Integration tests for {@link PgBranchTokenQueryService} (PRD-WF-002 §7.6 / NFR-WF-PAR-003).
 *
 * <p>Covers the read API end-to-end against a real PostgreSQL container:
 * <ul>
 *   <li>{@code findByWorkflow} with null filter, status filter, waitType filter, forkStepId filter,
 *       combined filters, and an empty-result case.</li>
 *   <li>{@code findJoinState} present + absent.</li>
 *   <li>{@code findOpenJoinStates} returning only OPEN states ordered by creation.</li>
 * </ul>
 *
 * <p>Fixtures are inserted directly via the underlying repositories so the IT exercises the query
 * service's filtering and routing logic in isolation from the engine.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgBranchTokenQueryServiceIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_test_branch_query_service")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static PgBranchTokenRepository branchTokens;
    static PgJoinStateRepository joinStates;
    static PgBranchTokenQueryService service;

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
        PgDbExceptionMapper ex = new PgDbExceptionMapper();
        branchTokens = new PgBranchTokenRepository(pool, ex);
        joinStates = new PgJoinStateRepository(pool, ex);
        service = new PgBranchTokenQueryService(pool, branchTokens, joinStates);
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

    // --- helpers ---

    private static WorkflowInstanceId insertInstance() {
        UUID id = UUID.randomUUID();
        pool.preparedQuery("INSERT INTO workflow_instances (id, definition_id, definition_version, plan_hash, version,"
                        + " status, current_step_id, state_json) VALUES ($1, $2, $3, $4, $5, $6, $7, $8::jsonb)")
                .execute(Tuple.of(id, "branch-query-test", 1L, "hash", 0L, "RUNNING", "step", "{}"))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        return new WorkflowInstanceId(id);
    }

    private static BranchToken token(
            WorkflowInstanceId wf,
            String forkStepId,
            String branchId,
            BranchStatus status,
            WaitType waitType,
            String waitKey) {
        Instant now = Instant.now();
        return new BranchToken(
                UUID.randomUUID(),
                wf,
                forkStepId,
                branchId,
                "step-" + branchId,
                status,
                waitType,
                waitKey,
                null,
                null,
                null,
                null,
                0,
                3,
                null,
                null,
                null,
                null,
                0L,
                now,
                now,
                DurableMetadata.empty());
    }

    private static JoinState joinState(
            WorkflowInstanceId wf, String forkStepId, String joinStepId, JoinStateStatus status, Instant createdAt) {
        return new JoinState(
                wf, forkStepId, joinStepId, JoinPolicyType.ALL_REQUIRED, status, null, null, 0L, createdAt, createdAt);
    }

    private static <T> T await(io.vertx.core.Future<T> future) {
        return future.toCompletionStage().toCompletableFuture().join();
    }

    private static void insertTokens(BranchToken... tokens) {
        await(pool.withTransaction(tx -> {
            io.vertx.core.Future<Void> chain = io.vertx.core.Future.succeededFuture();
            for (BranchToken t : tokens) {
                chain = chain.compose(v -> branchTokens.insert(t, tx));
            }
            return chain;
        }));
    }

    private static void insertJoinStates(JoinState... states) {
        await(pool.withTransaction(tx -> {
            io.vertx.core.Future<Void> chain = io.vertx.core.Future.succeededFuture();
            for (JoinState js : states) {
                chain = chain.compose(v -> joinStates.insert(js, tx));
            }
            return chain;
        }));
    }

    // --- tests ---

    @Test
    @DisplayName("findByWorkflow returns every branch when filter is null")
    void findByWorkflowReturnsAllBranchesWhenFilterIsNull(VertxTestContext ctx) {
        WorkflowInstanceId wf = insertInstance();
        insertTokens(
                token(wf, "fork", "a", BranchStatus.RUNNING, null, null),
                token(wf, "fork", "b", BranchStatus.COMPLETED, null, null));
        service.findByWorkflow(wf, null)
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), () -> "query failed: " + ar.cause());
                    List<BranchToken> result = ar.result();
                    assertEquals(2, result.size());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("findByWorkflow narrows by status")
    void findByWorkflowFiltersByStatus(VertxTestContext ctx) {
        WorkflowInstanceId wf = insertInstance();
        insertTokens(
                token(wf, "fork", "a", BranchStatus.RUNNING, null, null),
                token(wf, "fork", "b", BranchStatus.COMPLETED, null, null),
                token(wf, "fork", "c", BranchStatus.WAITING, WaitType.SIGNAL, "signal-c"));
        BranchTokenFilter filter = new BranchTokenFilter(BranchStatus.COMPLETED, null, null);
        service.findByWorkflow(wf, filter)
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded());
                    assertEquals(1, ar.result().size());
                    assertEquals("b", ar.result().get(0).branchId());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("findByWorkflow narrows by waitType")
    void findByWorkflowFiltersByWaitType(VertxTestContext ctx) {
        WorkflowInstanceId wf = insertInstance();
        insertTokens(
                token(wf, "fork", "a", BranchStatus.WAITING, WaitType.SIGNAL, "signal-a"),
                token(wf, "fork", "b", BranchStatus.WAITING, WaitType.JOIN, "fork"),
                token(wf, "fork", "c", BranchStatus.RUNNING, null, null));
        BranchTokenFilter filter = new BranchTokenFilter(null, WaitType.SIGNAL, null);
        service.findByWorkflow(wf, filter)
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded());
                    assertEquals(1, ar.result().size());
                    assertEquals("a", ar.result().get(0).branchId());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("findByWorkflow scopes to a specific fork via forkStepId")
    void findByWorkflowFiltersByForkStepId(VertxTestContext ctx) {
        WorkflowInstanceId wf = insertInstance();
        insertTokens(
                token(wf, "fork-1", "a", BranchStatus.RUNNING, null, null),
                token(wf, "fork-1", "b", BranchStatus.COMPLETED, null, null),
                token(wf, "fork-2", "c", BranchStatus.RUNNING, null, null));
        BranchTokenFilter filter = new BranchTokenFilter(null, null, "fork-2");
        service.findByWorkflow(wf, filter)
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded());
                    assertEquals(1, ar.result().size());
                    assertEquals("c", ar.result().get(0).branchId());
                    assertEquals("fork-2", ar.result().get(0).forkStepId());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("findByWorkflow combines forkStepId + status")
    void findByWorkflowCombinesFilters(VertxTestContext ctx) {
        WorkflowInstanceId wf = insertInstance();
        insertTokens(
                token(wf, "fork-1", "a", BranchStatus.RUNNING, null, null),
                token(wf, "fork-1", "b", BranchStatus.COMPLETED, null, null),
                token(wf, "fork-2", "c", BranchStatus.RUNNING, null, null),
                token(wf, "fork-2", "d", BranchStatus.COMPLETED, null, null));
        BranchTokenFilter filter = new BranchTokenFilter(BranchStatus.COMPLETED, null, "fork-1");
        service.findByWorkflow(wf, filter)
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded());
                    assertEquals(1, ar.result().size());
                    assertEquals("b", ar.result().get(0).branchId());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("findByWorkflow returns an empty list when no row matches")
    void findByWorkflowReturnsEmptyWhenNoMatch(VertxTestContext ctx) {
        WorkflowInstanceId wf = insertInstance();
        insertTokens(token(wf, "fork", "a", BranchStatus.RUNNING, null, null));
        BranchTokenFilter filter = new BranchTokenFilter(BranchStatus.FAILED, null, null);
        service.findByWorkflow(wf, filter)
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded());
                    assertTrue(ar.result().isEmpty());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("findJoinState returns the row when present, empty otherwise")
    void findJoinStateReturnsPresentAndAbsent(VertxTestContext ctx) {
        WorkflowInstanceId wf = insertInstance();
        Instant now = Instant.now();
        insertJoinStates(joinState(wf, "fork", "join", JoinStateStatus.OPEN, now));
        service.findJoinState(wf, "fork", "join")
                .compose(present -> {
                    ctx.verify(() -> {
                        assertTrue(present.isPresent());
                        assertEquals(JoinStateStatus.OPEN, present.get().status());
                    });
                    return service.findJoinState(wf, "fork", "missing-join");
                })
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded());
                    assertFalse(ar.result().isPresent());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("findOpenJoinStates returns only OPEN states ordered by creation")
    void findOpenJoinStatesReturnsOnlyOpen(VertxTestContext ctx) {
        WorkflowInstanceId wf = insertInstance();
        Instant earlier = Instant.now().minusSeconds(60);
        Instant later = Instant.now();
        insertJoinStates(
                joinState(wf, "fork-completed", "join-c", JoinStateStatus.COMPLETED, earlier),
                joinState(wf, "fork-failed", "join-f", JoinStateStatus.FAILED, earlier),
                joinState(wf, "fork-open-1", "join-1", JoinStateStatus.OPEN, earlier),
                joinState(wf, "fork-open-2", "join-2", JoinStateStatus.OPEN, later));
        service.findOpenJoinStates(wf)
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded());
                    List<JoinState> open = ar.result();
                    assertEquals(2, open.size());
                    assertTrue(
                            open.stream().allMatch(j -> j.status() == JoinStateStatus.OPEN),
                            "every returned row must be OPEN");
                    assertEquals("fork-open-1", open.get(0).forkStepId(), "ordered by createdAt ascending");
                    assertEquals("fork-open-2", open.get(1).forkStepId());
                    ctx.completeNow();
                }));
    }
}
