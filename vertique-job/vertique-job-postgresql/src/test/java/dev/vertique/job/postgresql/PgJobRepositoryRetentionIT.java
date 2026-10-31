// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobState;
import dev.vertique.job.JobType;
import dev.vertique.job.ProgressSnapshot;
import dev.vertique.job.postgresql.maintenance.JobRetentionConfig;
import dev.vertique.job.postgresql.maintenance.JobRetentionService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
 * Integration tests for the job execution retention delete ({@link PgJobRepository#purgeTerminalExecutions})
 * and {@link JobRetentionService} against a real PostgreSQL instance.
 *
 * <p>Verifies that only terminal executions older than the retention age are deleted, that ones that can still
 * run or retry never are, that logs go with their execution, that a batch is bounded and oldest first, that a
 * cron execution is kept while a misfire recovery could still fire its tick, and that the migration created the
 * index the delete scans.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgJobRepositoryRetentionIT {

    static final PostgresContainer db =
            new PostgresContainer().withDatabaseName("job_retention_test").withMigration("classpath:db/migration/job");

    static Pool pool;
    static PgJobRepository repository;

    private static final Duration RETENTION = Duration.ofDays(7);

    @BeforeAll
    static void setUp(Vertx vertx) {
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(5))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
        repository = new PgJobRepository(pool, new PgDbExceptionMapper());
    }

    @BeforeEach
    void truncate(VertxTestContext ctx) {
        pool.query("TRUNCATE TABLE job_executions, job_schedules CASCADE")
                .execute()
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    // --- Helpers ---

    private static OffsetDateTime ago(Duration age) {
        return OffsetDateTime.ofInstant(Instant.now().minus(age), ZoneOffset.UTC);
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** Inserts an execution row with explicit timestamps; {@code completedAt} may be {@code null}. */
    private static Future<UUID> seed(
            String jobId,
            String type,
            JobState state,
            OffsetDateTime scheduledAt,
            OffsetDateTime completedAt,
            OffsetDateTime updatedAt) {
        UUID id = UUID.randomUUID();
        return pool.preparedQuery(
                        "INSERT INTO job_executions(id, job_id, job_type, handler, queue, state,"
                                + " scheduled_at, completed_at, updated_at) VALUES ($1, $2, $3, 'h', 'default', $4, $5, $6, $7)")
                .execute(Tuple.of(id, jobId, type, state.name(), scheduledAt, completedAt, updatedAt))
                .map(id);
    }

    private static Future<UUID> seedDelayed(JobState state, Duration finishedAgo) {
        OffsetDateTime finished = ago(finishedAgo);
        return seed("delayed-" + UUID.randomUUID(), "DELAYED", state, finished, finished, finished);
    }

    private static Future<List<UUID>> remaining() {
        return pool.query("SELECT id FROM job_executions").execute().map(rows -> {
            List<UUID> ids = new ArrayList<>();
            rows.forEach(row -> ids.add(row.getUUID("id")));
            return ids;
        });
    }

    // --- Tests ---

    @Test
    @DisplayName("deletes SUCCEEDED, CANCELLED and DEAD_LETTER executions past the retention and nothing else")
    void deletesOnlyTerminalStates(VertxTestContext ctx) {
        Duration old = Duration.ofDays(10);
        Future<List<UUID>> seeded = Future.join(
                        seedDelayed(JobState.SUCCEEDED, old),
                        seedDelayed(JobState.CANCELLED, old),
                        seedDelayed(JobState.DEAD_LETTER, old),
                        seedDelayed(JobState.ENQUEUED, old),
                        seedDelayed(JobState.PROCESSING, old),
                        seedDelayed(JobState.FAILED, old),
                        seedDelayed(JobState.ABANDONED, old))
                .map(joined -> List.of(
                        joined.<UUID>resultAt(0),
                        joined.<UUID>resultAt(1),
                        joined.<UUID>resultAt(2),
                        joined.<UUID>resultAt(3),
                        joined.<UUID>resultAt(4),
                        joined.<UUID>resultAt(5),
                        joined.<UUID>resultAt(6)));

        seeded.compose(ids -> repository.purgeTerminalExecutions(RETENTION, 100).compose(deleted -> remaining()
                        .map(left -> {
                            ctx.verify(() -> {
                                assertEquals(3, deleted);
                                assertEquals(4, left.size());
                                assertFalse(left.contains(ids.get(0)), "SUCCEEDED is purgeable");
                                assertFalse(left.contains(ids.get(1)), "CANCELLED is purgeable");
                                assertFalse(left.contains(ids.get(2)), "DEAD_LETTER is purgeable");
                                assertTrue(left.contains(ids.get(3)), "ENQUEUED is live");
                                assertTrue(left.contains(ids.get(4)), "PROCESSING is live");
                                assertTrue(left.contains(ids.get(5)), "FAILED can still retry");
                                assertTrue(left.contains(ids.get(6)), "ABANDONED can still retry");
                            });
                            return null;
                        })))
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("keeps a terminal execution that finished within the retention")
    void keepsRecentTerminalExecutions(VertxTestContext ctx) {
        seedDelayed(JobState.SUCCEEDED, Duration.ofDays(6))
                .compose(
                        id -> repository.purgeTerminalExecutions(RETENTION, 100).compose(deleted -> remaining()
                                .map(left -> {
                                    ctx.verify(() -> {
                                        assertEquals(0, deleted);
                                        assertEquals(List.of(id), left);
                                    });
                                    return null;
                                })))
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("ages an execution with no completed_at by updated_at")
    void agesByUpdatedAtWhenCompletedAtIsMissing(VertxTestContext ctx) {
        OffsetDateTime old = ago(Duration.ofDays(10));
        OffsetDateTime recent = ago(Duration.ofDays(1));
        seed("cancelled-old", "DELAYED", JobState.CANCELLED, old, null, old)
                .compose(oldId -> seed("cancelled-recent", "DELAYED", JobState.CANCELLED, recent, null, recent)
                        .compose(recentId -> repository
                                .purgeTerminalExecutions(RETENTION, 100)
                                .compose(deleted -> remaining().map(left -> {
                                    ctx.verify(() -> {
                                        assertEquals(1, deleted);
                                        assertEquals(List.of(recentId), left);
                                    });
                                    return null;
                                }))))
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("deletes the logs of a deleted execution and keeps the logs of a kept one")
    void deletesLogsWithTheirExecution(VertxTestContext ctx) {
        seedDelayed(JobState.SUCCEEDED, Duration.ofDays(10))
                .compose(purged -> seedDelayed(JobState.PROCESSING, Duration.ofDays(10))
                        .compose(kept -> pool.preparedQuery(
                                        "INSERT INTO job_logs(execution_id, level, message) VALUES ($1, 'INFO', 'a'), ($2, 'INFO', 'b')")
                                .execute(Tuple.of(purged, kept))
                                .compose(v -> repository.purgeTerminalExecutions(RETENTION, 100))
                                .compose(deleted -> pool.query("SELECT execution_id FROM job_logs")
                                        .execute()
                                        .map(rows -> {
                                            ctx.verify(() -> {
                                                assertEquals(1, deleted);
                                                assertEquals(1, rows.size());
                                                assertEquals(
                                                        kept,
                                                        rows.iterator().next().getUUID("execution_id"));
                                            });
                                            return null;
                                        }))))
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("a batch is bounded and takes the oldest executions first")
    void batchIsBoundedAndOldestFirst(VertxTestContext ctx) {
        seedDelayed(JobState.SUCCEEDED, Duration.ofDays(8))
                .compose(newest -> seedDelayed(JobState.SUCCEEDED, Duration.ofDays(30))
                        .compose(oldest -> seedDelayed(JobState.SUCCEEDED, Duration.ofDays(20))
                                .compose(middle -> repository
                                        .purgeTerminalExecutions(RETENTION, 2)
                                        .compose(first -> remaining().compose(afterFirst -> {
                                            ctx.verify(() -> {
                                                assertEquals(2, first);
                                                assertEquals(List.of(newest), afterFirst);
                                            });
                                            return repository.purgeTerminalExecutions(RETENTION, 2);
                                        }))
                                        .compose(second -> remaining().map(afterSecond -> {
                                            ctx.verify(() -> {
                                                assertEquals(1, second);
                                                assertTrue(afterSecond.isEmpty());
                                            });
                                            return null;
                                        })))))
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("a cron execution is kept while its fire time is after the schedule's last_fired_at")
    void cronExecutionIsKeptUntilItsTickIsBehindLastFiredAt(VertxTestContext ctx) {
        Instant lastFired = Instant.now().minus(Duration.ofDays(9)).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime finished = ago(Duration.ofDays(10));
        pool.preparedQuery("INSERT INTO job_schedules(job_id, cron_expression, execution_mode, last_fired_at)"
                        + " VALUES ('cron-a', '0 * * * * *', 'SINGLE_INSTANCE', $1)")
                .execute(Tuple.of(at(lastFired)))
                .compose(v ->
                        seed("cron-a", "CRON", JobState.SUCCEEDED, at(lastFired.minusSeconds(60)), finished, finished))
                .compose(before -> seed("cron-a", "CRON", JobState.SUCCEEDED, at(lastFired), finished, finished)
                        .compose(equal -> seed(
                                        "cron-a",
                                        "CRON",
                                        JobState.SUCCEEDED,
                                        at(lastFired.plusSeconds(60)),
                                        finished,
                                        finished)
                                .compose(after -> repository
                                        .purgeTerminalExecutions(RETENTION, 100)
                                        .compose(deleted -> remaining().map(left -> {
                                            ctx.verify(() -> {
                                                assertEquals(2, deleted);
                                                assertEquals(List.of(after), left);
                                            });
                                            return null;
                                        })))))
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("a purged cron fire time may run again, a kept one may not: the dedup index only holds what is kept")
    void keptCronExecutionStillBlocksItsFireTime(VertxTestContext ctx) {
        Instant lastFired = Instant.now().minus(Duration.ofDays(9)).truncatedTo(ChronoUnit.SECONDS);
        Instant keptTick = lastFired.plusSeconds(60);
        OffsetDateTime finished = ago(Duration.ofDays(10));
        pool.preparedQuery("INSERT INTO job_schedules(job_id, cron_expression, execution_mode, last_fired_at)"
                        + " VALUES ('cron-b', '0 * * * * *', 'SINGLE_INSTANCE', $1)")
                .execute(Tuple.of(at(lastFired)))
                .compose(v -> seed("cron-b", "CRON", JobState.SUCCEEDED, at(keptTick), finished, finished))
                .compose(kept -> repository
                        .purgeTerminalExecutions(RETENTION, 100)
                        .compose(deleted -> repository.tryInsert(cronExecution("cron-b", keptTick)))
                        .map(inserted -> {
                            ctx.verify(
                                    () -> assertTrue(
                                            inserted.isEmpty(),
                                            "a fire time after last_fired_at is a tick a recovery may fire; its row must still dedup"));
                            return null;
                        }))
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName(
            "a cron execution with no schedule row, or a schedule that never fired, follows the rule for its schedule")
    void cronWithoutScheduleHistory(VertxTestContext ctx) {
        OffsetDateTime finished = ago(Duration.ofDays(10));
        pool.preparedQuery("INSERT INTO job_schedules(job_id, cron_expression, execution_mode)"
                        + " VALUES ('never-fired', '0 * * * * *', 'SINGLE_INSTANCE')")
                .execute()
                .compose(v -> seed("never-fired", "CRON", JobState.SUCCEEDED, finished, finished, finished))
                .compose(kept -> seed("no-schedule", "CRON", JobState.SUCCEEDED, finished, finished, finished)
                        .compose(gone -> repository
                                .purgeTerminalExecutions(RETENTION, 100)
                                .compose(deleted -> remaining().map(left -> {
                                    ctx.verify(() -> {
                                        assertEquals(1, deleted, "only the row without a schedule is purgeable");
                                        assertEquals(List.of(kept), left);
                                    });
                                    return null;
                                }))))
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("the retention service deletes a backlog in several batches")
    void serviceDrainsABacklog(VertxTestContext ctx) {
        List<Future<UUID>> inserts = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            inserts.add(seedDelayed(JobState.SUCCEEDED, Duration.ofDays(10)));
        }
        JobRetentionService service = new JobRetentionService(
                repository,
                JobRetentionConfig.builder()
                        .retentionSeconds(RETENTION.toSeconds())
                        .batchSize(10)
                        .build());

        Future.join(inserts)
                .compose(v -> service.purgeFinishedExecutions())
                .compose(v -> remaining())
                .onSuccess(left -> {
                    ctx.verify(() -> assertTrue(left.isEmpty(), "all 25 executions are deleted across three batches"));
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("the migration created the partial index the delete scans")
    void retentionIndexExists(VertxTestContext ctx) {
        pool.query("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_job_executions_retention'")
                .execute()
                .onSuccess(rows -> {
                    ctx.verify(() -> {
                        assertEquals(1, rows.size());
                        String definition = rows.iterator().next().getString("indexdef");
                        assertTrue(definition.contains("COALESCE(completed_at, updated_at)"), definition);
                        assertTrue(definition.contains("SUCCEEDED"), definition);
                    });
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("failed and abandoned cron executions are purged under the same guard, delayed ones never")
    void failedAndAbandonedCronExecutionsArePurged(VertxTestContext ctx) {
        Instant lastFired = Instant.now().minus(Duration.ofDays(9)).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime finished = ago(Duration.ofDays(10));
        pool.preparedQuery("INSERT INTO job_schedules(job_id, cron_expression, execution_mode, last_fired_at)"
                        + " VALUES ('cron-c', '0 * * * * *', 'SINGLE_INSTANCE', $1)")
                .execute(Tuple.of(at(lastFired)))
                .compose(v ->
                        seed("cron-c", "CRON", JobState.FAILED, at(lastFired.minusSeconds(120)), finished, finished))
                .compose(a ->
                        seed("cron-c", "CRON", JobState.ABANDONED, at(lastFired.minusSeconds(60)), finished, finished))
                .compose(b -> seed("cron-c", "CRON", JobState.FAILED, at(lastFired.plusSeconds(60)), finished, finished)
                        .compose(keptCron -> seedDelayed(JobState.FAILED, Duration.ofDays(10))
                                .compose(keptDelayed -> repository
                                        .purgeTerminalExecutions(RETENTION, 100)
                                        .compose(deleted -> remaining().map(left -> {
                                            ctx.verify(() -> {
                                                assertEquals(2, deleted);
                                                assertEquals(2, left.size());
                                                assertTrue(
                                                        left.contains(keptCron), "a tick after last_fired_at is kept");
                                                assertTrue(left.contains(keptDelayed), "a delayed FAILED can retry");
                                            });
                                            return null;
                                        })))))
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("the batch delete is planned on the retention index")
    void purgeIsPlannedOnTheRetentionIndex(VertxTestContext ctx) {
        pool.query(
                        "INSERT INTO job_executions(job_id, job_type, handler, queue, state, scheduled_at, completed_at, updated_at)"
                                + " SELECT 'p' || g, 'DELAYED', 'h', 'default',"
                                + " CASE WHEN g % 3 = 1 THEN 'ENQUEUED' ELSE 'SUCCEEDED' END,"
                                + " NOW(),"
                                + " CASE WHEN g % 3 = 0 THEN NOW() - interval '20 days'"
                                + "      WHEN g % 3 = 2 THEN NOW() - interval '1 day' END,"
                                + " NOW() FROM generate_series(1, 30000) g")
                .execute()
                .compose(v -> pool.query("ANALYZE job_executions").execute())
                .compose(v -> pool.preparedQuery("EXPLAIN " + repository.purgeStatement())
                        .execute(Tuple.of(RETENTION.toSeconds(), 1000)))
                .onSuccess(plan -> {
                    StringBuilder text = new StringBuilder();
                    plan.forEach(row -> text.append(row.getString(0)).append('\n'));
                    ctx.verify(() -> {
                        assertTrue(text.toString().contains("idx_job_executions_retention"), text.toString());
                        assertFalse(text.toString().contains("Seq Scan on job_executions e"), text.toString());
                    });
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    private static JobExecution cronExecution(String jobId, Instant scheduledAt) {
        return new JobExecution(
                UUID.randomUUID(),
                jobId,
                JobType.CRON,
                "cron.handler",
                "default",
                JobState.PROCESSING,
                0,
                3,
                null,
                0,
                null,
                scheduledAt,
                null,
                null,
                null,
                null,
                null,
                ProgressSnapshot.EMPTY,
                null,
                null,
                DurableMetadata.empty());
    }
}
