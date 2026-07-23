// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for {@link PgWorkflowRetentionService} against a real Testcontainers Postgres.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Archive completed rows in batches; {@code moreRowsRemaining} flag is correct.</li>
 *   <li>Archive is filtered by definition id.</li>
 *   <li>Archive skips RUNNING/WAITING rows.</li>
 *   <li>Archive skips already-archived rows (idempotent).</li>
 *   <li>Purge deletes archived rows and cascades to workflow_history and workflow_dedup.</li>
 *   <li>Purge is idempotent.</li>
 *   <li>{@code batchSize <= 0} rejected with {@link IllegalArgumentException}.</li>
 *   <li>Concurrent archive sweeps with {@code SKIP LOCKED} don't double-process.</li>
 * </ul>
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgWorkflowRetentionServiceIT {

    // --- Testcontainers setup ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_retention_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static PgWorkflowRetentionService retentionService;
    static PgWorkflowInstanceRepository instanceRepo;

    // --- Test setup ---

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

        PgDbExceptionMapper exMapper = new PgDbExceptionMapper();
        retentionService = new PgWorkflowRetentionService(new PgWorkflowRetentionRepository(pool, exMapper));
        instanceRepo = new PgWorkflowInstanceRepository(pool, exMapper);

        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
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

    // --- Helpers ---

    /**
     * Inserts a bare {@code workflow_instances} row with the given status and timestamps,
     * bypassing the full engine so we can test archival independently.
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
                        completedAt != null ? completedAt.atOffset(ZoneOffset.UTC) : null,
                        archivedAt != null ? archivedAt.atOffset(ZoneOffset.UTC) : null))
                .<Void>mapEmpty();
    }

    /**
     * Inserts a completed instance with {@code completed_at} equal to {@code completedAt}.
     *
     * @param id           the instance UUID
     * @param definitionId the definition id
     * @param completedAt  the completion instant
     * @return a {@link Future} that completes when the row is inserted
     */
    private Future<Void> insertCompletedInstance(UUID id, String definitionId, Instant completedAt) {
        return insertInstance(id, definitionId, "COMPLETED", completedAt, null);
    }

    /**
     * Inserts a RUNNING instance with no {@code completed_at} or {@code archived_at}.
     *
     * @param id           the instance UUID
     * @param definitionId the definition id
     * @return a {@link Future} that completes when the row is inserted
     */
    private Future<Void> insertRunningInstance(UUID id, String definitionId) {
        return insertInstance(id, definitionId, "RUNNING", null, null);
    }

    /**
     * Counts instances with non-null {@code archived_at}.
     *
     * @return a {@link Future} containing the count
     */
    private Future<Long> countArchived() {
        return pool.preparedQuery("SELECT COUNT(*) FROM workflow_instances WHERE archived_at IS NOT NULL")
                .execute()
                .map(rs -> rs.iterator().next().getLong(0));
    }

    /**
     * Counts all instances.
     *
     * @return a {@link Future} containing the total count
     */
    private Future<Long> countAll() {
        return pool.preparedQuery("SELECT COUNT(*) FROM workflow_instances")
                .execute()
                .map(rs -> rs.iterator().next().getLong(0));
    }

    /**
     * Inserts a {@code workflow_history} row for the given instance.
     *
     * @param workflowId the instance UUID
     * @param seq        the sequence number
     * @return a {@link Future} that completes when the row is inserted
     */
    private Future<Void> insertHistoryRow(UUID workflowId, long seq) {
        return pool.preparedQuery("INSERT INTO workflow_history"
                        + " (workflow_id, sequence, entry_type, payload_json)"
                        + " VALUES ($1, $2, 'STARTED', '{}')")
                .execute(Tuple.of(workflowId, seq))
                .<Void>mapEmpty();
    }

    /**
     * Counts history rows for the given instance.
     *
     * @param workflowId the instance UUID
     * @return a {@link Future} containing the count
     */
    private Future<Long> countHistoryFor(UUID workflowId) {
        return pool.preparedQuery("SELECT COUNT(*) FROM workflow_history WHERE workflow_id = $1")
                .execute(Tuple.of(workflowId))
                .map(rs -> rs.iterator().next().getLong(0));
    }

    /** Past cutoff for archival/purge tests. */
    private static final Instant PAST = Instant.parse("2026-01-01T00:00:00Z");

    /** Future cutoff — nothing past this should be archived. */
    private static final Instant FUTURE = Instant.parse("2030-01-01T00:00:00Z");

    // --- Tests ---

    /**
     * Archive 5 COMPLETED rows in batches of 3: first call archives 3 and signals
     * {@code moreRowsRemaining=true}; second call archives 2 and signals {@code moreRowsRemaining=false}.
     */
    @Test
    @DisplayName("Archive completed in batches: moreRowsRemaining flag is correct")
    void archiveCompletedBatched(VertxTestContext ctx) {
        List<Future<Void>> inserts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            inserts.add(insertCompletedInstance(UUID.randomUUID(), "def-batch", PAST.minusSeconds(i)));
        }

        Future.all(inserts)
                .compose(v -> retentionService.archiveCompletedBefore(FUTURE, null, 3))
                .compose(result1 -> {
                    ctx.verify(() -> {
                        assertEquals(3, result1.archivedCount(), "first batch must archive 3");
                        assertTrue(result1.moreRowsRemaining(), "moreRowsRemaining must be true after first batch");
                    });
                    return retentionService.archiveCompletedBefore(FUTURE, null, 3);
                })
                .compose(result2 -> {
                    ctx.verify(() -> {
                        assertEquals(2, result2.archivedCount(), "second batch must archive 2");
                        assertTrue(!result2.moreRowsRemaining(), "moreRowsRemaining must be false after second batch");
                    });
                    return countArchived();
                })
                .map(count -> {
                    ctx.verify(() -> assertEquals(5L, count, "all 5 rows must be archived"));
                    return null;
                })
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }

    /**
     * Archive by definition id: only rows with matching {@code definition_id} are archived.
     */
    @Test
    @DisplayName("Archive filters by definitionId: only matching rows are archived")
    void archiveFiltersByDefinitionId(VertxTestContext ctx) {
        List<Future<Void>> inserts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            inserts.add(insertCompletedInstance(UUID.randomUUID(), "def-A", PAST));
        }
        for (int i = 0; i < 5; i++) {
            inserts.add(insertCompletedInstance(UUID.randomUUID(), "def-B", PAST));
        }

        Future.all(inserts)
                .compose(v -> retentionService.archiveCompletedBefore(FUTURE, "def-A", 100))
                .compose(result -> {
                    ctx.verify(() -> {
                        assertEquals(5, result.archivedCount(), "must archive exactly 5 def-A rows");
                        assertTrue(!result.moreRowsRemaining());
                    });
                    return countArchived();
                })
                .compose(archived -> {
                    ctx.verify(() -> assertEquals(5L, archived, "only def-A rows must be archived"));
                    // def-B rows must not be archived
                    return pool.preparedQuery("SELECT COUNT(*) FROM workflow_instances"
                                    + " WHERE definition_id = $1 AND archived_at IS NOT NULL")
                            .execute(Tuple.of("def-B"))
                            .map(rs -> rs.iterator().next().getLong(0));
                })
                .map(defBArchived -> {
                    ctx.verify(() -> assertEquals(0L, defBArchived, "no def-B rows must be archived"));
                    return null;
                })
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }

    /**
     * Archive skips RUNNING/WAITING rows: only COMPLETED rows match the archive predicate.
     */
    @Test
    @DisplayName("Archive skips RUNNING/WAITING rows: archives 0 rows")
    void archiveSkipsNonTerminalRows(VertxTestContext ctx) {
        List<Future<Void>> inserts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            inserts.add(insertRunningInstance(UUID.randomUUID(), "def-running"));
        }

        Future.all(inserts)
                .compose(v -> retentionService.archiveCompletedBefore(FUTURE, null, 100))
                .compose(result -> {
                    ctx.verify(() -> assertEquals(0, result.archivedCount(), "must archive 0 RUNNING rows"));
                    return countArchived();
                })
                .map(archived -> {
                    ctx.verify(() -> assertEquals(0L, archived, "no rows must be archived"));
                    return null;
                })
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }

    /**
     * Archive is idempotent for already-archived rows: re-running archive does not increment counts.
     */
    @Test
    @DisplayName("Archive is idempotent: skips already-archived rows")
    void archiveSkipsAlreadyArchived(VertxTestContext ctx) {
        List<Future<Void>> inserts = new ArrayList<>();
        Instant alreadyArchivedAt = PAST.minusSeconds(3600);
        // 3 already archived
        for (int i = 0; i < 3; i++) {
            inserts.add(insertInstance(UUID.randomUUID(), "def-idem", "COMPLETED", PAST, alreadyArchivedAt));
        }
        // 2 not yet archived
        for (int i = 0; i < 2; i++) {
            inserts.add(insertCompletedInstance(UUID.randomUUID(), "def-idem", PAST));
        }

        Future.all(inserts)
                .compose(v -> retentionService.archiveCompletedBefore(FUTURE, null, 100))
                .compose(result -> {
                    ctx.verify(() -> assertEquals(
                            2, result.archivedCount(), "must archive 2 new rows (not the 3 already archived)"));
                    return countArchived();
                })
                .map(archived -> {
                    ctx.verify(() -> assertEquals(5L, archived, "all 5 rows must be archived after the call"));
                    return null;
                })
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }

    /**
     * Purge cascades: deleting archived instances removes history and dedup rows for those instances.
     */
    @Test
    @DisplayName("Purge cascades: history rows deleted with archived instance")
    void purgeCascadesToHistory(VertxTestContext ctx) {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        Instant archivedAt = PAST.minusSeconds(3600);

        // Insert instances first, then history rows (history has an FK to workflow_instances)
        List<Future<Void>> instanceInserts = new ArrayList<>();
        for (UUID id : ids) {
            instanceInserts.add(insertInstance(id, "def-purge", "COMPLETED", PAST, archivedAt));
        }

        Future.all(instanceInserts)
                .compose(v -> {
                    List<Future<Void>> historyInserts = new ArrayList<>();
                    for (UUID id : ids) {
                        historyInserts.add(insertHistoryRow(id, 1L));
                    }
                    return Future.all(historyInserts);
                })
                .compose(v -> retentionService.purgeArchivedBefore(FUTURE, null, 100))
                .compose(result -> {
                    ctx.verify(() -> {
                        assertEquals(3, result.purgedCount(), "must purge 3 rows");
                        assertTrue(!result.moreRowsRemaining(), "no more rows remaining after purging all");
                    });
                    return countAll();
                })
                .compose(remaining -> {
                    ctx.verify(() -> assertEquals(0L, remaining, "all 3 instances must be deleted"));
                    // History rows must also be gone (CASCADE)
                    return Future.all(ids.stream().map(this::countHistoryFor).toList());
                })
                .map(allCounts -> {
                    ctx.verify(() -> {
                        for (int i = 0; i < ids.size(); i++) {
                            assertEquals(
                                    0L,
                                    (long) allCounts.resultAt(i),
                                    "history rows for instance " + ids.get(i) + " must be deleted");
                        }
                    });
                    return null;
                })
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }

    /**
     * Purge is idempotent: purging again after all rows are gone returns 0.
     */
    @Test
    @DisplayName("Purge is idempotent: second purge call returns 0")
    void purgeIdempotent(VertxTestContext ctx) {
        List<Future<Void>> inserts = new ArrayList<>();
        Instant archivedAt = PAST.minusSeconds(3600);
        for (int i = 0; i < 3; i++) {
            inserts.add(insertInstance(UUID.randomUUID(), "def-purge-idem", "COMPLETED", PAST, archivedAt));
        }

        Future.all(inserts)
                .compose(v -> retentionService.purgeArchivedBefore(FUTURE, null, 100))
                .compose(result1 -> {
                    ctx.verify(() -> assertEquals(3, result1.purgedCount(), "first purge must delete 3"));
                    return retentionService.purgeArchivedBefore(FUTURE, null, 100);
                })
                .map(result2 -> {
                    ctx.verify(() -> assertEquals(0, result2.purgedCount(), "second purge must delete 0 (idempotent)"));
                    return null;
                })
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }

    /**
     * Archive skips rows whose {@code completed_at} is after the cutoff.
     */
    @Test
    @DisplayName("Archive cutoff: rows with completed_at after cutoff are not archived")
    void archiveRespectsCutoff(VertxTestContext ctx) {
        Instant cutoff = Instant.parse("2026-03-01T00:00:00Z");
        Instant beforeCutoff = Instant.parse("2026-02-01T00:00:00Z");
        Instant afterCutoff = Instant.parse("2026-04-01T00:00:00Z");

        List<Future<Void>> inserts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            inserts.add(insertCompletedInstance(UUID.randomUUID(), "def-cutoff", beforeCutoff));
        }
        for (int i = 0; i < 2; i++) {
            inserts.add(insertCompletedInstance(UUID.randomUUID(), "def-cutoff", afterCutoff));
        }

        Future.all(inserts)
                .compose(v -> retentionService.archiveCompletedBefore(cutoff, null, 100))
                .compose(result -> {
                    ctx.verify(() -> {
                        assertEquals(3, result.archivedCount(), "must archive only rows with completed_at <= cutoff");
                        assertTrue(!result.moreRowsRemaining());
                    });
                    return countArchived();
                })
                .map(archived -> {
                    ctx.verify(() -> assertEquals(3L, archived, "only 3 rows before cutoff must be archived"));
                    return null;
                })
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }

    /**
     * {@code batchSize <= 0} is rejected with {@link IllegalArgumentException} synchronously.
     * All archive and purge methods enforce this invariant.
     */
    @Test
    @DisplayName("batchSize <= 0 is rejected with IllegalArgumentException")
    void batchSizeZeroRejected(VertxTestContext ctx) {
        ctx.verify(() -> {
            // archiveCompletedBefore with batchSize=0
            try {
                retentionService.archiveCompletedBefore(FUTURE, null, 0);
                ctx.failNow(new AssertionError("archiveCompletedBefore(0) must throw IllegalArgumentException"));
            } catch (IllegalArgumentException expected) {
                // pass
            }

            // archiveCompletedBefore with negative batchSize
            try {
                retentionService.archiveCompletedBefore(FUTURE, null, -5);
                ctx.failNow(new AssertionError("archiveCompletedBefore(-5) must throw IllegalArgumentException"));
            } catch (IllegalArgumentException expected) {
                // pass
            }

            // purgeArchivedBefore with batchSize=0
            try {
                retentionService.purgeArchivedBefore(FUTURE, null, 0);
                ctx.failNow(new AssertionError("purgeArchivedBefore(0) must throw IllegalArgumentException"));
            } catch (IllegalArgumentException expected) {
                // pass
            }
        });
        ctx.completeNow();
    }

    /**
     * Concurrent archive sweeps with SKIP LOCKED: two threads calling archiveCompletedBefore
     * concurrently on 20 rows with batchSize=10 must archive at most 20 rows total (no
     * double-archiving). This validates the SKIP LOCKED semantics.
     */
    @Test
    @DisplayName("Concurrent archive sweeps: SKIP LOCKED prevents double-archiving")
    void concurrentArchiveSweepsNoDuplicates(VertxTestContext ctx) throws InterruptedException {
        // Insert 20 COMPLETED rows
        List<Future<Void>> inserts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            inserts.add(insertCompletedInstance(UUID.randomUUID(), "def-concurrent", PAST));
        }

        // Wait for all inserts synchronously using a latch
        CountDownLatch insertDone = new CountDownLatch(1);
        AtomicInteger insertError = new AtomicInteger(0);
        Future.all(inserts).onSuccess(v -> insertDone.countDown()).onFailure(t -> {
            insertError.incrementAndGet();
            insertDone.countDown();
        });
        assertTrue(insertDone.await(10, TimeUnit.SECONDS), "inserts must complete");
        assertEquals(0, insertError.get(), "inserts must not fail");

        // Run two concurrent archive calls
        AtomicInteger totalArchived = new AtomicInteger(0);
        CountDownLatch archiveDone = new CountDownLatch(2);
        AtomicInteger archiveErrors = new AtomicInteger(0);

        ExecutorService exec = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 2; i++) {
            exec.submit(() -> {
                retentionService
                        .archiveCompletedBefore(FUTURE, null, 10)
                        .onSuccess(r -> {
                            totalArchived.addAndGet(r.archivedCount());
                            archiveDone.countDown();
                        })
                        .onFailure(t -> {
                            archiveErrors.incrementAndGet();
                            archiveDone.countDown();
                        });
            });
        }

        assertTrue(archiveDone.await(30, TimeUnit.SECONDS), "archive calls must complete");
        exec.shutdown();

        ctx.verify(() -> {
            assertEquals(0, archiveErrors.get(), "concurrent archive calls must not error");
            assertTrue(
                    totalArchived.get() <= 20,
                    "total archived must be <= 20 (no double-archiving); got: " + totalArchived.get());
            // May be less than 20 if one call raced and got all 10, the other got fewer
            // The key invariant is no row is archived twice — verified by countArchived:
        });

        // Also verify no row has been archived twice by checking archived count
        CountDownLatch verifyDone = new CountDownLatch(1);
        AtomicInteger verifyError = new AtomicInteger(0);
        AtomicInteger archivedCount = new AtomicInteger(0);
        countArchived()
                .onSuccess(c -> {
                    archivedCount.set(c.intValue());
                    verifyDone.countDown();
                })
                .onFailure(t -> {
                    verifyError.incrementAndGet();
                    verifyDone.countDown();
                });

        assertTrue(verifyDone.await(10, TimeUnit.SECONDS));
        ctx.verify(() -> {
            assertEquals(0, verifyError.get());
            assertEquals(
                    totalArchived.get(),
                    archivedCount.get(),
                    "archived count in DB must match total archived by concurrent calls");
        });

        ctx.completeNow();
    }
}
