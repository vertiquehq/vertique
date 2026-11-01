// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.engine.WorkflowTestTables;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
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
 * Integration tests of the {@code correlated} dedup row of {@link PgWorkflowDedupRepository} against a real PostgreSQL
 * instance: the first claim of a namespace and key inserts, a find reads the claimant, a claim by another instance does
 * not insert and leaves the first claimant in place, and the row goes with the instance it names.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgWorkflowDedupRepositoryCorrelatedIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_test_dedup_correlated")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static PgWorkflowInstanceRepository instanceRepository;
    static PgWorkflowDedupRepository dedupRepository;

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
        PgDbExceptionMapper mapper = new PgDbExceptionMapper();
        instanceRepository = new PgWorkflowInstanceRepository(pool, mapper);
        dedupRepository = new PgWorkflowDedupRepository(pool, mapper);
        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        WorkflowTestTables.truncating(
                        pool,
                        "TRUNCATE TABLE workflow_timers, workflow_history, workflow_dedup, workflow_instances RESTART IDENTITY CASCADE")
                .execute()
                .onComplete(ar -> ctx.completeNow());
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    /** Inserts a minimal instance so that the foreign key of {@code workflow_dedup.workflow_id} is satisfied. */
    private Future<Void> insertInstance(WorkflowInstanceId id) {
        WorkflowInstance inst = new WorkflowInstance(
                id,
                "order-fulfillment",
                1L,
                "abc123",
                0L,
                WorkflowStatus.RUNNING,
                null,
                null,
                "start",
                null,
                null,
                null,
                "{}",
                null,
                null,
                Instant.now(),
                Instant.now(),
                null);
        return pool.withTransaction(tx -> instanceRepository.insert(inst, tx));
    }

    @Test
    @DisplayName(
            "a claim inserts once, a find reads the claimant, a claim of another instance conflicts, and the delete cascades")
    void claimThenFindThenConflictAcrossInstancesThenCascade(VertxTestContext ctx) {
        // Given two instances, a namespace of 64 hex characters and a key of 64 hex characters
        WorkflowInstanceId a = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstanceId b = new WorkflowInstanceId(UUID.randomUUID());
        String namespace = "a".repeat(64);
        String key = "b".repeat(64);
        Boolean[] inserted = new Boolean[2];
        Object[] found = new Object[3];

        // When the first claim runs for A, a find, a second claim for B, a find again, and then A's instance row is
        // deleted and the find runs once more
        insertInstance(a)
                .compose(v -> insertInstance(b))
                .compose(v ->
                        pool.withTransaction(tx -> dedupRepository.claimOrResolveCorrelated(namespace, key, a, tx)))
                .compose(first -> {
                    inserted[0] = first;
                    return pool.withTransaction(tx -> dedupRepository.findCorrelated(namespace, key, tx));
                })
                .compose(read -> {
                    found[0] = read;
                    return pool.withTransaction(tx -> dedupRepository.claimOrResolveCorrelated(namespace, key, b, tx));
                })
                .compose(second -> {
                    inserted[1] = second;
                    return pool.withTransaction(tx -> dedupRepository.findCorrelated(namespace, key, tx));
                })
                .compose(read -> {
                    found[1] = read;
                    return pool.preparedQuery("DELETE FROM workflow_instances WHERE id = $1")
                            .execute(Tuple.of(a.value()));
                })
                .compose(deleted -> pool.withTransaction(tx -> dedupRepository.findCorrelated(namespace, key, tx)))
                .onSuccess(read -> ctx.verify(() -> {
                    found[2] = read;

                    // Then the first claim inserts and the find returns A
                    assertThat(inserted[0]).as("the first claim inserts").isTrue();
                    assertThat(found[0]).isEqualTo(Optional.of(a));

                    // And the second claim does not insert and the claimant is still A
                    assertThat(inserted[1]).as("the claim of B does not insert").isFalse();
                    assertThat(found[1]).isEqualTo(Optional.of(a));

                    // And once A's instance row is deleted, the find returns nothing
                    assertThat(found[2]).isEqualTo(Optional.empty());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }
}
