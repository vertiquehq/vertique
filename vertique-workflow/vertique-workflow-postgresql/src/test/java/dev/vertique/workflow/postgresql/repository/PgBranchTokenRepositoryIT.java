// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.BranchStatus;
import dev.vertique.workflow.state.BranchToken;
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
 * Integration tests for {@link PgBranchTokenRepository} against a real PostgreSQL instance.
 *
 * <p>Covers insert + findById round-trip, optimistic update (success and stale-version),
 * findByWorkflowAndFork ordering, findRecoverable selection by status + due time, and stale-running
 * detection.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgBranchTokenRepositoryIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_test_branch_tokens")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static PgBranchTokenRepository repo;

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
        repo = new PgBranchTokenRepository(pool, new PgDbExceptionMapper());
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
                .execute(Tuple.of(id, "test-def", 1L, "hash", 0L, "RUNNING", "step", "{}"))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        return new WorkflowInstanceId(id);
    }

    private static BranchToken makeToken(WorkflowInstanceId wf, String branchId, BranchStatus status) {
        return makeToken(wf, branchId, status, Instant.now());
    }

    private static BranchToken makeToken(WorkflowInstanceId wf, String branchId, BranchStatus status, Instant ts) {
        return new BranchToken(
                UUID.randomUUID(),
                wf,
                "fork",
                branchId,
                "step-" + branchId,
                status,
                null,
                null,
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
                ts,
                ts,
                DurableMetadata.empty());
    }

    // --- tests ---

    @Test
    @DisplayName("insert + findById round-trip preserves all branch token fields")
    void insertFindByIdRoundTrip(VertxTestContext ctx) {
        WorkflowInstanceId wf = insertInstance();
        BranchToken token = makeToken(wf, "a", BranchStatus.RUNNING);
        pool.withTransaction(tx -> repo.insert(token, tx).compose(v -> repo.findById(token.id(), tx)))
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertTrue(ar.result().isPresent(), "row should be present");
                    BranchToken loaded = ar.result().get();
                    assertEquals(token.id(), loaded.id());
                    assertEquals("a", loaded.branchId());
                    assertEquals("fork", loaded.forkStepId());
                    assertEquals(BranchStatus.RUNNING, loaded.status());
                    assertEquals(3, loaded.maxAttempts());
                    assertEquals(0L, loaded.version());
                    ctx.completeNow();
                });
    }

    @Test
    @DisplayName("updateOptimistic with matching version returns 1 and persists changes")
    void updateOptimisticSuccess(VertxTestContext ctx) {
        WorkflowInstanceId wf = insertInstance();
        BranchToken token = makeToken(wf, "a", BranchStatus.RUNNING);
        pool.withTransaction(tx -> repo.insert(token, tx)
                        .compose(v -> {
                            BranchToken next = token.withStatus(BranchStatus.COMPLETED, 1L, Instant.now());
                            return repo.updateOptimistic(next, 0L, tx);
                        })
                        .compose(rowCount -> {
                            assertEquals(1, rowCount);
                            return repo.findById(token.id(), tx);
                        }))
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertEquals(BranchStatus.COMPLETED, ar.result().get().status());
                    assertEquals(1L, ar.result().get().version());
                    ctx.completeNow();
                });
    }

    @Test
    @DisplayName("updateOptimistic with stale version returns 0 and does not persist")
    void updateOptimisticStale(VertxTestContext ctx) {
        WorkflowInstanceId wf = insertInstance();
        BranchToken token = makeToken(wf, "a", BranchStatus.RUNNING);
        pool.withTransaction(tx -> repo.insert(token, tx).compose(v -> {
                    BranchToken next = token.withStatus(BranchStatus.FAILED, 1L, Instant.now());
                    return repo.updateOptimistic(next, 99L, tx);
                }))
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
    @DisplayName("findByWorkflowAndFork returns rows ordered by created_at then branch_id")
    void findByWorkflowAndForkOrdered(VertxTestContext ctx) {
        WorkflowInstanceId wf = insertInstance();
        Instant t0 = Instant.parse("2026-05-10T00:00:00Z");
        BranchToken a = makeToken(wf, "a", BranchStatus.RUNNING, t0);
        BranchToken b = makeToken(wf, "b", BranchStatus.RUNNING, t0.plusSeconds(1));
        BranchToken c = makeToken(wf, "c", BranchStatus.RUNNING, t0.plusSeconds(2));
        pool.withTransaction(tx -> repo.insert(a, tx)
                        .compose(v -> repo.insert(b, tx))
                        .compose(v -> repo.insert(c, tx))
                        .compose(v -> repo.findByWorkflowAndFork(wf, "fork", tx)))
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    List<BranchToken> rows = ar.result();
                    assertEquals(3, rows.size());
                    assertEquals("a", rows.get(0).branchId());
                    assertEquals("b", rows.get(1).branchId());
                    assertEquals("c", rows.get(2).branchId());
                    ctx.completeNow();
                });
    }

    @Test
    @DisplayName("findRecoverable selects RETRY_SCHEDULED branches whose next_retry_at is at or before now")
    void findRecoverableSelectsDue(VertxTestContext ctx) {
        WorkflowInstanceId wf = insertInstance();
        Instant now = Instant.parse("2026-05-10T00:00:00Z");
        BranchToken due = makeToken(wf, "due", BranchStatus.RETRY_SCHEDULED, now);
        BranchToken dueLater = makeToken(wf, "later", BranchStatus.RETRY_SCHEDULED, now);
        BranchToken running = makeToken(wf, "run", BranchStatus.RUNNING, now);

        BranchToken dueWithRetry = withRetry(due, now.minusSeconds(5));
        BranchToken laterWithRetry = withRetry(dueLater, now.plusSeconds(60));

        pool.withTransaction(tx -> repo.insert(dueWithRetry, tx)
                        .compose(v -> repo.insert(laterWithRetry, tx))
                        .compose(v -> repo.insert(running, tx))
                        .compose(v -> repo.findRecoverable(now, 10, tx)))
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    List<BranchToken> rows = ar.result();
                    assertEquals(1, rows.size());
                    assertEquals("due", rows.get(0).branchId());
                    ctx.completeNow();
                });
    }

    @Test
    @DisplayName("findStaleRunning selects RUNNING rows whose updated_at is older than threshold")
    void findStaleRunningSelectsOld(VertxTestContext ctx) {
        WorkflowInstanceId wf = insertInstance();
        Instant tStale = Instant.parse("2026-05-09T00:00:00Z");
        Instant tFresh = Instant.parse("2026-05-10T00:00:00Z");
        BranchToken stale = makeToken(wf, "stale", BranchStatus.RUNNING, tStale);
        BranchToken fresh = makeToken(wf, "fresh", BranchStatus.RUNNING, tFresh);

        pool.withTransaction(tx -> repo.insert(stale, tx)
                        .compose(v -> repo.insert(fresh, tx))
                        .compose(v -> repo.findStaleRunning(tFresh.minusSeconds(60), 10, tx)))
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    List<BranchToken> rows = ar.result();
                    assertEquals(1, rows.size());
                    assertEquals("stale", rows.get(0).branchId());
                    ctx.completeNow();
                });
    }

    @Test
    @DisplayName("(workflowId, forkStepId, branchId) UNIQUE rejects duplicate branch ids in the same fork")
    void uniqueBranchIdRejectsDuplicate(VertxTestContext ctx) {
        WorkflowInstanceId wf = insertInstance();
        BranchToken first = makeToken(wf, "a", BranchStatus.RUNNING);
        BranchToken duplicate = new BranchToken(
                UUID.randomUUID(),
                wf,
                "fork",
                "a",
                "step-a",
                BranchStatus.RUNNING,
                null,
                null,
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
                Instant.now(),
                Instant.now(),
                DurableMetadata.empty());
        pool.withTransaction(tx -> repo.insert(first, tx).compose(v -> repo.insert(duplicate, tx)))
                .onComplete(ar -> {
                    if (ar.succeeded()) {
                        ctx.failNow(new AssertionError("duplicate insert should have failed"));
                        return;
                    }
                    assertFalse(ar.cause().getMessage().isEmpty());
                    ctx.completeNow();
                });
    }

    private static BranchToken withRetry(BranchToken token, Instant nextRetryAt) {
        return new BranchToken(
                token.id(),
                token.workflowId(),
                token.forkStepId(),
                token.branchId(),
                token.currentStepId(),
                token.status(),
                token.waitType(),
                token.waitKey(),
                token.waitAuxId(),
                token.resultJson(),
                token.errorType(),
                token.errorMessage(),
                token.attemptCount(),
                token.maxAttempts(),
                nextRetryAt,
                token.lastErrorType(),
                token.lastErrorMessage(),
                token.lastErrorAt(),
                token.version(),
                token.createdAt(),
                token.updatedAt(),
                DurableMetadata.empty());
    }
}
