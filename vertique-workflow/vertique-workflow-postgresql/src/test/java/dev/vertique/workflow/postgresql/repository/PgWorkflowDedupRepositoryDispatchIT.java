// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.dedup.WorkflowDedupScopes;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
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
 * Integration tests for the PRD-WF-002 dispatch dedup added to
 * {@link PgWorkflowDedupRepository#claimOrResolveDispatch}.
 *
 * <p>Verifies: first claim wins (returns true); second claim with the same {@code (scope, key)}
 * tuple returns false (already recorded); claims for different keys are independent.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgWorkflowDedupRepositoryDispatchIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_test_dedup_dispatch")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static PgWorkflowDedupRepository repo;

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
        repo = new PgWorkflowDedupRepository(pool, new PgDbExceptionMapper());
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

    @Test
    @DisplayName("first claim returns true; second claim with same key returns false")
    void firstWinsSecondLoses(VertxTestContext ctx) {
        WorkflowInstanceId wf = insertInstance();
        String scope = WorkflowDedupScopes.dispatchScope(wf);
        String key = WorkflowDedupScopes.dispatchKey("fork", "a", "step-a", "svc.foo", "reserve");
        pool.withTransaction(
                        tx -> repo.claimOrResolveDispatch(wf, scope, key, tx).compose(first -> {
                            assertTrue(first, "first claim should win");
                            return repo.claimOrResolveDispatch(wf, scope, key, tx);
                        }))
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertFalse(ar.result(), "second claim with same key should observe the existing row");
                    ctx.completeNow();
                });
    }

    @Test
    @DisplayName("different dispatch keys are independent")
    void differentKeysIndependent(VertxTestContext ctx) {
        WorkflowInstanceId wf = insertInstance();
        String scope = WorkflowDedupScopes.dispatchScope(wf);
        String keyA = WorkflowDedupScopes.dispatchKey("fork", "a", "step-a", "svc.foo", "reserve");
        String keyB = WorkflowDedupScopes.dispatchKey("fork", "b", "step-b", "svc.foo", "reserve");
        pool.withTransaction(tx -> repo.claimOrResolveDispatch(wf, scope, keyA, tx)
                        .compose(first -> repo.claimOrResolveDispatch(wf, scope, keyB, tx)))
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertTrue(ar.result(), "different keys should not collide");
                    ctx.completeNow();
                });
    }

    @Test
    @DisplayName("dispatch kind 'dispatch' coexists with signal/start/task kinds")
    void kindsAreIsolated(VertxTestContext ctx) {
        WorkflowInstanceId wf = insertInstance();
        String scope = WorkflowDedupScopes.dispatchScope(wf);
        String dispatchKey = WorkflowDedupScopes.dispatchKey("fork", "a", "step-a", "svc.foo", "reserve");
        // Same scope/key string, but different kind → different PK row.
        pool.withTransaction(tx -> repo.claimOrResolveDispatch(wf, scope, dispatchKey, tx)
                        .compose(v -> repo.claimOrResolveSignal(wf, dispatchKey, tx)))
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertEquals(true, ar.result(), "signal claim should win — different kind, no collision");
                    ctx.completeNow();
                });
    }
}
