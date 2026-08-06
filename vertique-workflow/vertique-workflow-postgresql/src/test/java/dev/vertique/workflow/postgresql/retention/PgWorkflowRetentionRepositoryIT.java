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
import java.util.ArrayList;
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
                .onComplete(ctx.succeedingThenComplete());
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
     * Inserts an unarchived {@code workflow_instances} row directly via SQL, mirroring the seeding
     * helper in {@link PgWorkflowRetentionServiceIT}. Using SQL rather than the engine path keeps
     * these tests focused on the repository's archive/purge SQL behavior.
     *
     * @param id           the instance UUID
     * @param definitionId the definition id
     * @param status       the instance status string
     * @param completedAt  the {@code completed_at} timestamp (may be null for non-terminal rows)
     * @return a {@link Future} that completes when the row is inserted
     */
    private Future<Void> insertInstance(UUID id, String definitionId, String status, Instant completedAt) {
        return insertInstance(id, definitionId, status, completedAt, null);
    }

    /**
     * Inserts a {@code workflow_instances} row with an explicit {@code archived_at}, as
     * {@link PgWorkflowRetentionServiceIT}'s seeding helper does. Seeding {@code archived_at} from
     * Java is what lets purge tests put both sides of the {@code archived_at <= cutoff} comparison
     * on one clock; letting {@link PgWorkflowRetentionRepository#archiveBefore} stamp it instead
     * would put the row's timestamp on the database clock and the cutoff on the JVM's.
     *
     * @param id           the instance UUID
     * @param definitionId the definition id
     * @param status       the instance status string
     * @param completedAt  the {@code completed_at} timestamp (may be null for non-terminal rows)
     * @param archivedAt   the {@code archived_at} timestamp (null = not archived)
     * @return a {@link Future} that completes when the row is inserted
     */
    private Future<Void> insertInstance(
            UUID id, String definitionId, String status, Instant completedAt, Instant archivedAt) {
        String sql = "INSERT INTO workflow_instances"
                + " (id, definition_id, definition_version, plan_hash, version, status,"
                + " current_step_id, state_json, completed_at, archived_at)"
                + " VALUES ($1, $2, 1, 'hash', 0, $3, 'done', '{}', $4, $5)";
        return pool.preparedQuery(sql)
                .execute(Tuple.of(
                        id,
                        definitionId,
                        status,
                        completedAt != null ? utc(completedAt) : null,
                        archivedAt != null ? utc(archivedAt) : null))
                .<Void>mapEmpty();
    }

    /**
     * Reads the ids of every surviving {@code workflow_instances} row, so a purge test can assert
     * which rows were spared rather than only how many were deleted.
     *
     * @return a {@link Future} containing the surviving ids, ordered by id
     */
    private Future<List<UUID>> remainingInstanceIds() {
        return pool.query("SELECT id FROM workflow_instances ORDER BY id")
                .execute()
                .map(rs -> {
                    List<UUID> ids = new ArrayList<>();
                    rs.forEach(row -> ids.add(row.getUUID("id")));
                    return ids;
                });
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

    /**
     * Purge honours the {@code archived_at <= cutoff} bound in both directions. Every timestamp
     * here — the two seeded below the cutoff, the one seeded above it, and the cutoff itself —
     * derives from a single {@link Instant#now()} read, so the comparison has an hour of margin on
     * each side and involves no database clock. Do not reintroduce an
     * {@link PgWorkflowRetentionRepository#archiveBefore} call to produce {@code archived_at}: that
     * stamps the row from the database clock while the cutoff comes from the JVM's, leaving a
     * sub-millisecond margin that skew turns into a flake.
     */
    @Test
    @DisplayName("purgeArchivedBefore deletes archived rows at or below the cutoff and spares those above")
    void purgeArchivedBeforeRespectsExplicitCutoff(VertxTestContext ctx) {
        Instant cutoff = Instant.now();
        Instant belowCutoff = cutoff.minusSeconds(3_600);
        Instant aboveCutoff = cutoff.plusSeconds(3_600);
        UUID survivor = UUID.randomUUID();

        insertInstance(UUID.randomUUID(), "def-A", "COMPLETED", belowCutoff, belowCutoff)
                .compose(v -> insertInstance(UUID.randomUUID(), "def-A", "COMPLETED", belowCutoff, belowCutoff))
                .compose(v -> insertInstance(survivor, "def-A", "COMPLETED", belowCutoff, aboveCutoff))
                .compose(v -> repository.purgeArchivedBefore(utc(cutoff), null, 100))
                .compose(count -> {
                    ctx.verify(() -> assertEquals(2, count, "only rows with archived_at <= cutoff are purged"));
                    return remainingInstanceIds();
                })
                .onSuccess(remaining -> ctx.verify(() -> {
                    assertEquals(List.of(survivor), remaining, "the row archived after the cutoff survives");
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
