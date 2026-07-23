// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * Focused integration coverage for {@link PgWorkflowRetentionRepository}, the persistence component
 * that owns archive/purge SQL, transaction execution, and exception translation.
 *
 * <p>The {@link PgWorkflowRetentionService} layer (which wraps batch validation, status routing,
 * and result construction around this repository) has its own end-to-end coverage in
 * {@link PgWorkflowRetentionServiceIT}. These tests assert the repository's own contract directly
 * — primarily that archive/purge return raw row counts, that the SQL respects the
 * {@code completed_at} / {@code archived_at} cutoffs, and that the {@code definitionId} filter
 * works.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgWorkflowRetentionRepositoryIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_test_retention_repo")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static PgWorkflowRetentionRepository repository;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(8))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
        repository = new PgWorkflowRetentionRepository(pool, new PgDbExceptionMapper());
        ctx.completeNow();
    }

    @BeforeEach
    void truncate(VertxTestContext ctx) {
        pool.query("TRUNCATE TABLE workflow_timers, workflow_tasks, workflow_history,"
                        + " workflow_dedup, workflow_instances RESTART IDENTITY CASCADE")
                .execute()
                .onComplete(ar -> ctx.completeNow());
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }

    /**
     * Inserts a bare {@code workflow_instances} row directly via SQL, mirroring the seeding helper
     * in {@link PgWorkflowRetentionServiceIT}. Using SQL rather than the engine path keeps these
     * tests focused on the repository's archive/purge SQL behavior.
     */
    private Future<Void> insertInstance(UUID id, String definitionId, String status, Instant completedAt) {
        String sql = "INSERT INTO workflow_instances"
                + " (id, definition_id, definition_version, plan_hash, version, status,"
                + " current_step_id, state_json, completed_at)"
                + " VALUES ($1, $2, 1, 'hash', 0, $3, 'done', '{}', $4)";
        return pool.preparedQuery(sql)
                .execute(Tuple.of(id, definitionId, status, completedAt != null ? utc(completedAt) : null))
                .<Void>mapEmpty();
    }

    @Test
    @DisplayName("archiveBefore returns the row count and respects the completed_at cutoff")
    void archiveBeforeReturnsRowCount(VertxTestContext ctx) {
        Instant cutoff = Instant.now().minusSeconds(60);
        Instant inside = cutoff.minusSeconds(60);
        Instant after = cutoff.plusSeconds(60);

        insertInstance(UUID.randomUUID(), "def-A", "COMPLETED", inside)
                .compose(v -> insertInstance(UUID.randomUUID(), "def-A", "COMPLETED", inside))
                .compose(v -> insertInstance(UUID.randomUUID(), "def-A", "COMPLETED", after))
                .compose(v -> repository.archiveBefore("COMPLETED", utc(cutoff), null, 100))
                .onSuccess(count -> ctx.verify(() -> {
                    assertEquals(2, count, "rows with completed_at <= cutoff are archived");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("archiveBefore filters by definitionId")
    void archiveBeforeFiltersByDefinitionId(VertxTestContext ctx) {
        Instant cutoff = Instant.now().minusSeconds(60);
        Instant inside = cutoff.minusSeconds(60);

        insertInstance(UUID.randomUUID(), "def-A", "COMPLETED", inside)
                .compose(v -> insertInstance(UUID.randomUUID(), "def-B", "COMPLETED", inside))
                .compose(v -> insertInstance(UUID.randomUUID(), "def-A", "COMPLETED", inside))
                .compose(v -> repository.archiveBefore("COMPLETED", utc(cutoff), "def-A", 100))
                .onSuccess(count -> ctx.verify(() -> {
                    assertEquals(2, count, "only def-A rows are archived");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("archiveBefore caps the affected rows at the limit")
    void archiveBeforeRespectsLimit(VertxTestContext ctx) {
        Instant cutoff = Instant.now().minusSeconds(60);
        Instant inside = cutoff.minusSeconds(60);

        Future<Void> chain = Future.succeededFuture();
        for (int i = 0; i < 5; i++) {
            chain = chain.compose(v -> insertInstance(UUID.randomUUID(), "def-A", "COMPLETED", inside));
        }
        chain.compose(v -> repository.archiveBefore("COMPLETED", utc(cutoff), null, 3))
                .onSuccess(count -> ctx.verify(() -> {
                    assertEquals(3, count, "limit caps the affected row count");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("purgeArchivedBefore deletes archived rows whose archived_at is below the cutoff")
    void purgeArchivedBeforeDeletes(VertxTestContext ctx) {
        Instant ancient = Instant.now().minusSeconds(3_600);
        Instant cutoff = Instant.now().minusSeconds(60);

        insertInstance(UUID.randomUUID(), "def-A", "COMPLETED", ancient)
                .compose(v -> insertInstance(UUID.randomUUID(), "def-A", "COMPLETED", ancient))
                .compose(v -> repository.archiveBefore("COMPLETED", utc(cutoff), null, 100))
                .compose(archived -> {
                    ctx.verify(() -> assertEquals(2, archived));
                    return repository.purgeArchivedBefore(utc(Instant.now()), null, 100);
                })
                .onSuccess(count -> ctx.verify(() -> {
                    assertEquals(2, count, "all archived rows below cutoff are purged");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("repository constructs from PgSqlRepository superclass — pool/exception mapper inherited")
    void constructorWiringSmoke() {
        assertNotNull(repository, "repository should be constructed via super(pool, exceptionMapper)");
    }
}
