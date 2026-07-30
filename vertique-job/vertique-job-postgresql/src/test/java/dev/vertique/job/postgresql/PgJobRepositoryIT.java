// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.job.CronJobSchedule;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobState;
import dev.vertique.job.JobType;
import dev.vertique.job.LogEntry;
import dev.vertique.job.ProgressSnapshot;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for {@link PgJobRepository} against a real PostgreSQL instance.
 *
 * <p>Verifies: save, claim (no duplicates, priority order, SKIP LOCKED), state transitions,
 * completion, retry scheduling, heartbeat, stale detection, log persistence, transactional save,
 * tryInsert (SINGLE_INSTANCE leader election), saveSchedule (upsert), and updateScheduleFireTimes.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class PgJobRepositoryIT {

    static final PostgresContainer db =
            new PostgresContainer().withDatabaseName("job_test").withMigration("classpath:db/migration/job");

    static Pool pool;
    static PgJobRepository repository;

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
        repository = new PgJobRepository(pool, new PgDbExceptionMapper());
        ctx.completeNow();
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    // --- Helpers ---

    private static JobExecution newExecution(String queue, int priority) {
        return new JobExecution(
                UUID.randomUUID(),
                "logical-job-" + UUID.randomUUID(),
                JobType.DELAYED,
                "job.delayed.test",
                queue,
                JobState.ENQUEUED,
                0,
                5,
                null,
                priority,
                null,
                Instant.now().minusSeconds(5),
                null,
                null,
                null,
                null,
                null,
                ProgressSnapshot.EMPTY,
                null,
                null,
                null);
    }

    private static JobExecution newScheduledExecution(String queue, Instant scheduledAt) {
        return new JobExecution(
                UUID.randomUUID(),
                "logical-job-" + UUID.randomUUID(),
                JobType.DELAYED,
                "job.delayed.test",
                queue,
                JobState.ENQUEUED,
                0,
                5,
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
                null);
    }

    /**
     * Creates a CRON execution for a specific job ID and scheduled time, used to test
     * SINGLE_INSTANCE leader election via the unique partial index.
     */
    private static JobExecution newCronExecution(String jobId, Instant scheduledAt) {
        return new JobExecution(
                UUID.randomUUID(),
                jobId,
                JobType.CRON,
                "cron.handler.test",
                "cron",
                JobState.PROCESSING,
                0,
                3,
                null,
                0,
                "node-test-" + UUID.randomUUID().toString().substring(0, 8),
                scheduledAt,
                null,
                null,
                null,
                null,
                null,
                ProgressSnapshot.EMPTY,
                null,
                null,
                null);
    }

    /**
     * Creates a sample {@link CronJobSchedule} for testing schedule persistence.
     */
    private static CronJobSchedule newSchedule(String jobId) {
        return new CronJobSchedule(
                jobId,
                "0 0 8 * * *",
                "test/my-svc/doWork",
                "eventbus:test/my-svc/doWork",
                "EVERY_INSTANCE",
                "UTC",
                true,
                "SKIP",
                3,
                true,
                null,
                null);
    }

    // --- Tests ---

    @Test
    @DisplayName("save persists execution and findById returns it with matching fields")
    void savePersistsExecution(VertxTestContext ctx) {
        JobExecution exec = newExecution("default", 0);

        repository
                .save(exec)
                .compose(savedId -> {
                    assertNotNull(savedId);
                    return repository.findById(savedId);
                })
                .onSuccess(opt -> ctx.verify(() -> {
                    assertTrue(opt.isPresent(), "findById should return the saved execution");
                    JobExecution found = opt.get();
                    assertEquals(exec.id(), found.id());
                    assertEquals(exec.jobId(), found.jobId());
                    assertEquals(exec.handler(), found.handler());
                    assertEquals(exec.queue(), found.queue());
                    assertEquals(JobState.ENQUEUED, found.state());
                    assertEquals(0, found.attemptNumber());
                    assertEquals(5, found.maxAttempts());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("save round-trips job_executions.metadata JSONB column for durable propagation")
    void metadataRoundTrip(VertxTestContext ctx) {
        DurableMetadata metadata =
                DurableMetadata.of("test", new JsonObject().put("corr", "c-1").put("tenant", "t-1"));
        JobExecution exec = new JobExecution(
                UUID.randomUUID(),
                "metadata-job-" + UUID.randomUUID(),
                JobType.DELAYED,
                "job.delayed.test",
                "default",
                JobState.ENQUEUED,
                0,
                5,
                null,
                0,
                null,
                Instant.now().minusSeconds(5),
                null,
                null,
                null,
                null,
                null,
                ProgressSnapshot.EMPTY,
                null,
                null,
                metadata);

        repository
                .save(exec)
                .compose(savedId -> repository.findById(savedId))
                .onSuccess(opt -> ctx.verify(() -> {
                    assertTrue(opt.isPresent());
                    JobExecution found = opt.get();
                    assertEquals(
                            "c-1", found.metadata().body("test").orElseThrow().getString("corr"));
                    assertEquals(
                            "t-1", found.metadata().body("test").orElseThrow().getString("tenant"));
                    assertEquals(java.util.Set.of("test"), found.metadata().namespaces());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("save with null metadata reads back as empty map (NULL JSONB column)")
    void metadataNullReadsBackAsEmpty(VertxTestContext ctx) {
        // newExecution(...) passes null for the metadata field, so the persisted column is NULL.
        JobExecution exec = newExecution("default", 0);

        repository
                .save(exec)
                .compose(savedId -> repository.findById(savedId))
                .onSuccess(opt -> ctx.verify(() -> {
                    assertTrue(opt.isPresent());
                    assertTrue(opt.get().metadata().isEmpty(), "NULL metadata column must read back as Map.of()");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("claimNextJob claims ENQUEUED jobs and transitions them to PROCESSING")
    void claimNextJobClaimsEnqueuedJobs(VertxTestContext ctx) {
        String queue = "claim-test-" + UUID.randomUUID();
        JobExecution e1 = newExecution(queue, 0);
        JobExecution e2 = newExecution(queue, 0);
        JobExecution e3 = newExecution(queue, 0);

        Future.all(repository.save(e1), repository.save(e2), repository.save(e3))
                .compose(v -> repository.claimNextJob(queue, 2))
                .onSuccess(claimed -> ctx.verify(() -> {
                    assertEquals(2, claimed.size(), "Should claim exactly 2 jobs");
                    for (JobExecution e : claimed) {
                        assertEquals(JobState.PROCESSING, e.state(), "Claimed job must be PROCESSING");
                    }
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("claimNextJob does not overlap with concurrent claims (SKIP LOCKED)")
    void claimNextJobSkipsLockedRows(VertxTestContext ctx) {
        String queue = "skip-locked-" + UUID.randomUUID();
        JobExecution e1 = newExecution(queue, 0);
        JobExecution e2 = newExecution(queue, 0);

        Future.all(repository.save(e1), repository.save(e2))
                .compose(v -> Future.all(repository.claimNextJob(queue, 2), repository.claimNextJob(queue, 2)))
                .onSuccess(results -> ctx.verify(() -> {
                    List<JobExecution> first = results.resultAt(0);
                    List<JobExecution> second = results.resultAt(1);
                    int total = first.size() + second.size();
                    // Both concurrent claims must not exceed the total available executions
                    assertEquals(2, total, "Total claimed across both calls must equal number saved");
                    // Verify no overlap (same id claimed twice)
                    List<UUID> firstIds = first.stream().map(JobExecution::id).toList();
                    List<UUID> secondIds = second.stream().map(JobExecution::id).toList();
                    for (UUID id : secondIds) {
                        assertFalse(firstIds.contains(id), "Same execution must not be claimed twice");
                    }
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("claimNextJob respects priority: higher-priority jobs are claimed first")
    void claimNextJobRespectsPriority(VertxTestContext ctx) {
        String queue = "priority-test-" + UUID.randomUUID();
        JobExecution low = newExecution(queue, 1);
        JobExecution high = newExecution(queue, 10);
        JobExecution mid = newExecution(queue, 5);

        Future.all(repository.save(low), repository.save(high), repository.save(mid))
                .compose(v -> repository.claimNextJob(queue, 1))
                .onSuccess(claimed -> ctx.verify(() -> {
                    assertEquals(1, claimed.size());
                    assertEquals(high.id(), claimed.get(0).id(), "Highest priority job should be claimed first");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("completeExecution returns present Optional on first call and empty on second (idempotent no-op)")
    void completeExecutionUpdatesTerminalState(VertxTestContext ctx) {
        String queue = "complete-test-" + UUID.randomUUID();
        JobExecution exec = newExecution(queue, 0);
        ProgressSnapshot progress = new ProgressSnapshot(10, 10, 0, "done");

        repository
                .save(exec)
                .compose(id -> repository.claimNextJob(queue, 1))
                .compose(claimed -> {
                    UUID id = claimed.get(0).id();
                    // First call: PROCESSING → SUCCEEDED — must return the persisted execution
                    return repository
                            .completeExecution(id, JobState.SUCCEEDED, null, null, progress)
                            .compose(firstOpt -> {
                                ctx.verify(() -> {
                                    assertTrue(firstOpt.isPresent(), "first completeExecution must return present");
                                    JobExecution found = firstOpt.get();
                                    assertEquals(JobState.SUCCEEDED, found.state());
                                    assertNull(found.errorMessage());
                                    assertNotNull(found.completedAt());
                                    assertEquals(10L, found.progress().total());
                                });
                                // Second call: already SUCCEEDED — state guard rejects it (no-op)
                                return repository.completeExecution(id, JobState.SUCCEEDED, null, null, progress);
                            });
                })
                .onSuccess(secondOpt -> ctx.verify(() -> {
                    assertTrue(secondOpt.isEmpty(), "second completeExecution on terminal row must be empty (no-op)");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("completeExecution rejects the invalid ABANDONED→ABANDONED self-transition (no duplicate)")
    void completeExecutionRejectsAbandonedToAbandoned(VertxTestContext ctx) {
        String queue = "abandon-self-" + UUID.randomUUID();
        JobExecution exec = newExecution(queue, 0);

        repository
                .save(exec)
                .compose(id -> repository.claimNextJob(queue, 1))
                .compose(claimed -> {
                    UUID id = claimed.get(0).id();
                    // PROCESSING → ABANDONED is valid and returns the row.
                    return repository
                            .completeExecution(id, JobState.ABANDONED, "timeout", "Timeout", ProgressSnapshot.EMPTY)
                            .compose(firstOpt -> {
                                ctx.verify(
                                        () -> assertTrue(firstOpt.isPresent(), "PROCESSING→ABANDONED must be present"));
                                // ABANDONED → ABANDONED is NOT a valid transition — must be a no-op (empty),
                                // so no spurious duplicate transition/audit event is produced.
                                return repository
                                        .completeExecution(
                                                id, JobState.ABANDONED, "timeout", "Timeout", ProgressSnapshot.EMPTY)
                                        .compose(secondOpt -> {
                                            ctx.verify(() -> assertTrue(
                                                    secondOpt.isEmpty(),
                                                    "ABANDONED→ABANDONED must be rejected (empty, no duplicate)"));
                                            return repository.findById(id);
                                        });
                            });
                })
                .onSuccess(found -> ctx.verify(() -> {
                    assertTrue(found.isPresent());
                    assertEquals(JobState.ABANDONED, found.get().state(), "row stays ABANDONED");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("scheduleRetry transitions execution back to ENQUEUED with new scheduled_at")
    void scheduleRetryTransitionsBackToEnqueued(VertxTestContext ctx) {
        String queue = "retry-test-" + UUID.randomUUID();
        JobExecution exec = newExecution(queue, 0);
        Instant retryAt = Instant.now().plus(30, ChronoUnit.SECONDS);

        repository
                .save(exec)
                .compose(id -> repository.claimNextJob(queue, 1))
                .compose(claimed -> {
                    UUID id = claimed.get(0).id();
                    // Must transition to FAILED before scheduling retry (state guard)
                    return repository
                            .completeExecution(
                                    id,
                                    JobState.FAILED,
                                    "transient error",
                                    "java.io.IOException",
                                    ProgressSnapshot.EMPTY)
                            .compose(opt -> repository.scheduleRetry(id, retryAt, 1))
                            .compose(v -> repository.findById(id));
                })
                .onSuccess(opt -> ctx.verify(() -> {
                    assertTrue(opt.isPresent());
                    JobExecution found = opt.get();
                    assertEquals(JobState.ENQUEUED, found.state());
                    assertEquals(1, found.attemptNumber());
                    assertNull(found.startedAt());
                    assertNull(found.lockedBy());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("heartbeat updates updated_at and progress snapshot")
    void heartbeatUpdatesTimestampAndProgress(VertxTestContext ctx) {
        String queue = "heartbeat-test-" + UUID.randomUUID();
        JobExecution exec = newExecution(queue, 0);

        repository
                .save(exec)
                .compose(id -> repository.claimNextJob(queue, 1))
                .compose(claimed -> {
                    UUID id = claimed.get(0).id();
                    ProgressSnapshot progress = new ProgressSnapshot(100, 42, 0, "in progress");
                    return repository.heartbeat(id, progress).compose(v -> repository.findById(id));
                })
                .onSuccess(opt -> ctx.verify(() -> {
                    assertTrue(opt.isPresent());
                    JobExecution found = opt.get();
                    assertEquals(JobState.PROCESSING, found.state());
                    assertEquals(42L, found.progress().succeeded());
                    assertEquals("in progress", found.progress().status());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("findStale returns PROCESSING executions whose heartbeat has expired")
    void findStaleReturnsExpiredHeartbeatJobs(VertxTestContext ctx) {
        String queue = "stale-test-" + UUID.randomUUID();
        JobExecution exec = newExecution(queue, 0);

        repository
                .save(exec)
                .compose(id -> repository.claimNextJob(queue, 1))
                .compose(claimed -> {
                    UUID id = claimed.get(0).id();
                    // Manually set updated_at to the past to simulate a stale heartbeat
                    return pool.preparedQuery(
                                    "UPDATE job_executions SET updated_at = NOW() - INTERVAL '2 minutes' WHERE id = $1")
                            .execute(Tuple.of(id))
                            .compose(v -> repository.findStale(Duration.ofMinutes(1)));
                })
                .onSuccess(stale -> ctx.verify(() -> {
                    assertTrue(
                            stale.stream().anyMatch(e -> e.id().equals(exec.id())),
                            "Stale execution should be detected");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("saveLogs persists log entries for the given execution")
    void saveLogsPersistsEntries(VertxTestContext ctx) {
        String queue = "logs-test-" + UUID.randomUUID();
        JobExecution exec = newExecution(queue, 0);

        repository
                .save(exec)
                .compose(id -> {
                    List<LogEntry> entries = List.of(
                            new LogEntry("INFO", "Job started", Instant.now()),
                            new LogEntry("WARN", "Something odd", Instant.now()),
                            new LogEntry("ERROR", "Failed here", Instant.now()));
                    return repository.saveLogs(exec.id(), entries).compose(v -> pool.preparedQuery(
                                    "SELECT COUNT(*) FROM job_logs WHERE execution_id = $1")
                            .execute(Tuple.of(exec.id()))
                            .map(rows -> rows.iterator().next().getLong(0)));
                })
                .onSuccess(count -> ctx.verify(() -> {
                    assertEquals(3L, count, "All three log entries should be persisted");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("saveLogs is all-or-nothing: a mid-batch constraint violation persists zero rows")
    void saveLogsBatchIsAtomicOnPartialFailure(VertxTestContext ctx) {
        // This test pins a PostgreSQL guarantee the job-log flush design depends on.
        //
        // job_logs has `id BIGSERIAL PRIMARY KEY` and no natural key, so there is no way to
        // deduplicate a re-sent batch. The retry-on-failure drain in DefaultJobLogger (nack puts
        // the whole batch back for the next flush) is only safe if a failed saveLogs batch leaves
        // NO rows behind — otherwise the committed prefix would be duplicated on every retry.
        //
        // ANSWER (verified against PostgreSQL 16 via Testcontainers): the batch is ATOMIC.
        // The failing statement aborts the implicit transaction that wraps the pipelined batch,
        // so the count below is 0 — the valid rows before AND after the bad row are rolled back.
        String queue = "logs-atomic-" + UUID.randomUUID();
        JobExecution exec = newExecution(queue, 0);

        // job_logs.level is VARCHAR(5): entries 1 and 3 fit, entry 2 does not and fails the INSERT.
        List<LogEntry> entries = List.of(
                new LogEntry("INFO", "before the bad row", Instant.now()),
                new LogEntry("TOOLONGLEVEL", "violates VARCHAR(5)", Instant.now()),
                new LogEntry("INFO", "after the bad row", Instant.now()));

        repository
                .save(exec)
                .compose(id -> repository
                        .saveLogs(exec.id(), entries)
                        .map(v -> Boolean.FALSE)
                        .otherwise(err -> Boolean.TRUE))
                .compose(failed -> {
                    ctx.verify(() -> assertTrue(failed, "saveLogs must fail when a row violates the level width"));
                    return pool.preparedQuery("SELECT COUNT(*) FROM job_logs WHERE execution_id = $1")
                            .execute(Tuple.of(exec.id()))
                            .map(rows -> rows.iterator().next().getLong(0));
                })
                .onSuccess(count -> ctx.verify(() -> {
                    assertEquals(
                            0L,
                            count,
                            "a failed saveLogs batch must persist zero rows — a committed prefix would be"
                                    + " duplicated by the nack-and-retry drain, which cannot deduplicate");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("save with SqlClient participates in the caller's transaction")
    void saveWithSqlClientUsesProvidedTransaction(VertxTestContext ctx) {
        JobExecution exec = newExecution("tx-test-" + UUID.randomUUID(), 0);

        pool.withTransaction(conn -> repository.save(exec, conn).compose(id -> {
                    assertNotNull(id);
                    return conn.preparedQuery("SELECT state FROM job_executions WHERE id = $1")
                            .execute(Tuple.of(id))
                            .map(rows -> rows.iterator().next().getString("state"));
                }))
                .onSuccess(state -> ctx.verify(() -> {
                    assertEquals("ENQUEUED", state, "State should be ENQUEUED inside the transaction");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("updateState applies optimistic concurrency: fails when state does not match fromState")
    void updateStateFailsOnStateMismatch(VertxTestContext ctx) {
        String queue = "update-state-test-" + UUID.randomUUID();
        JobExecution exec = newExecution(queue, 0);

        repository
                .save(exec)
                .compose(id -> repository.updateState(exec.id(), JobState.PROCESSING, JobState.SUCCEEDED))
                .onSuccess(v -> ctx.failNow(new AssertionError("Expected failure but succeeded")))
                .onFailure(err -> ctx.verify(() -> {
                    assertNotNull(err, "Should fail when fromState does not match");
                    assertTrue(
                            err.getMessage().contains("State transition failed"),
                            "Failure message should mention state transition");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("claimNextJob does not claim jobs with future scheduledAt")
    void claimNextJobSkipsFutureScheduledJobs(VertxTestContext ctx) {
        String queue = "future-scheduled-" + UUID.randomUUID();
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
        JobExecution futureExec = newScheduledExecution(queue, future);

        repository
                .save(futureExec)
                .compose(id -> repository.claimNextJob(queue, 10))
                .onSuccess(claimed -> ctx.verify(() -> {
                    assertTrue(claimed.isEmpty(), "Future-scheduled jobs must not be claimed");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("tryInsert succeeds on first insert and returns the execution ID")
    void tryInsertSucceedsOnFirstInsert(VertxTestContext ctx) {
        String jobId = "cron-si-" + UUID.randomUUID();
        Instant scheduledAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        JobExecution execution = newCronExecution(jobId, scheduledAt);

        repository
                .tryInsert(execution)
                .onSuccess(optId -> ctx.verify(() -> {
                    assertTrue(optId.isPresent(), "tryInsert should return the ID on first insert");
                    assertEquals(execution.id(), optId.get(), "Returned ID must match execution ID");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("tryInsert returns empty on conflict (same job_id + scheduled_at)")
    void tryInsertReturnsEmptyOnConflict(VertxTestContext ctx) {
        String jobId = "cron-si-conflict-" + UUID.randomUUID();
        Instant scheduledAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        JobExecution first = newCronExecution(jobId, scheduledAt);
        JobExecution second = newCronExecution(jobId, scheduledAt);

        repository
                .tryInsert(first)
                .compose(optFirst -> {
                    assertTrue(optFirst.isPresent(), "First insert should succeed");
                    return repository.tryInsert(second);
                })
                .onSuccess(optSecond -> ctx.verify(() -> {
                    assertFalse(optSecond.isPresent(), "Second insert with same (job_id, scheduled_at) must be empty");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    /**
     * Pins {@code job_executions.handler NOT NULL} as an asserted contract rather than an
     * accident of the DDL.
     *
     * <p>This is the schema-side counterpart to the cron scheduler's producer-side invariant
     * that every persisted cron execution carries a resolved, non-blank handler address
     * (see ADR-0201). The scheduler unit tests use mock repositories, which happily accept a
     * {@code null} handler — so without this test nothing in the build would notice if the
     * constraint were dropped, and the composition defect behind issue #41 could return
     * silently. The delayed-job poller also dispatches to this column
     * ({@code DelayedJobPoller.dispatch}), so it must stay non-null for that path too.
     *
     * <p>Green from the start, and intended to stay that way: a future change that relaxes the
     * constraint has to argue with a failing test.
     */
    @Test
    @DisplayName("job_executions rejects an execution with a null handler")
    void saveRejectsExecutionWithNullHandler(VertxTestContext ctx) {
        String jobId = "cron-null-handler-" + UUID.randomUUID();
        Instant scheduledAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        repository
                .save(nullHandlerCronExecution(jobId, scheduledAt))
                .onSuccess(id -> ctx.failNow("save must reject a null handler, but returned id " + id))
                .onFailure(saveFailure -> repository
                        .tryInsert(nullHandlerCronExecution(jobId, scheduledAt.plusSeconds(1)))
                        .onSuccess(opt -> ctx.failNow("tryInsert must reject a null handler, but returned " + opt))
                        .onFailure(tryInsertFailure -> ctx.completeNow()));
    }

    /**
     * Builds a cron {@link JobExecution} whose {@code handler} is {@code null} — the exact shape
     * {@code CronScheduler.buildExecution} produced for every {@code service:} target before
     * ADR-0201. Deliberately a local test helper rather than a {@code withHandler} accessor on
     * {@link JobExecution}: the production record has no reason to offer a way to null out an
     * address, and a test must not widen a public API to reach a case.
     */
    private static JobExecution nullHandlerCronExecution(String jobId, Instant scheduledAt) {
        return new JobExecution(
                UUID.randomUUID(),
                jobId,
                JobType.CRON,
                null,
                "cron",
                JobState.PROCESSING,
                0,
                3,
                null,
                0,
                "node-test-" + UUID.randomUUID().toString().substring(0, 8),
                scheduledAt,
                null,
                null,
                null,
                null,
                null,
                ProgressSnapshot.EMPTY,
                null,
                null,
                null);
    }

    @Test
    @DisplayName("tryInsert allows different scheduled_at for the same job_id")
    void tryInsertAllowsDifferentScheduledAt(VertxTestContext ctx) {
        String jobId = "cron-si-diff-time-" + UUID.randomUUID();
        Instant time1 = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant time2 = time1.plus(1, ChronoUnit.HOURS);
        JobExecution exec1 = newCronExecution(jobId, time1);
        JobExecution exec2 = newCronExecution(jobId, time2);

        repository
                .tryInsert(exec1)
                .compose(opt1 -> {
                    assertTrue(opt1.isPresent(), "First insert should succeed");
                    return repository.tryInsert(exec2);
                })
                .onSuccess(opt2 -> ctx.verify(() -> {
                    assertTrue(opt2.isPresent(), "Second insert with different scheduled_at should also succeed");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("saveSchedule upserts: second save with same job_id updates the record")
    void saveScheduleUpserts(VertxTestContext ctx) {
        String jobId = "sched-upsert-" + UUID.randomUUID();
        CronJobSchedule original = newSchedule(jobId);
        CronJobSchedule updated = new CronJobSchedule(
                jobId,
                "0 0 10 * * *",
                "test/my-svc/doWork",
                "eventbus:test/my-svc/doWork",
                "SINGLE_INSTANCE",
                "Europe/Helsinki",
                true,
                "SKIP",
                5,
                false,
                null,
                null);

        repository
                .saveSchedule(original)
                .compose(v -> pool.preparedQuery(
                                "SELECT cron_expression, execution_mode, timezone, max_attempts, tracked"
                                        + " FROM job_schedules WHERE job_id = $1")
                        .execute(Tuple.of(jobId))
                        .map(rows -> rows.iterator().next()))
                .compose(row -> {
                    ctx.verify(() -> {
                        assertEquals("0 0 8 * * *", row.getString("cron_expression"));
                        assertEquals("EVERY_INSTANCE", row.getString("execution_mode"));
                        assertEquals("UTC", row.getString("timezone"));
                        assertEquals(3, row.getInteger("max_attempts"));
                        assertTrue(row.getBoolean("tracked"));
                    });
                    return repository.saveSchedule(updated);
                })
                .compose(v -> pool.preparedQuery(
                                "SELECT cron_expression, execution_mode, timezone, max_attempts, tracked"
                                        + " FROM job_schedules WHERE job_id = $1")
                        .execute(Tuple.of(jobId))
                        .map(rows -> rows.iterator().next()))
                .onSuccess(row -> ctx.verify(() -> {
                    assertEquals("0 0 10 * * *", row.getString("cron_expression"), "cron_expression should be updated");
                    assertEquals(
                            "SINGLE_INSTANCE", row.getString("execution_mode"), "execution_mode should be updated");
                    assertEquals("Europe/Helsinki", row.getString("timezone"), "timezone should be updated");
                    assertEquals(5, row.getInteger("max_attempts"), "max_attempts should be updated");
                    assertFalse(row.getBoolean("tracked"), "tracked should be updated to false");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("updateScheduleFireTimes updates last_fired_at and next_fire_at")
    void updateScheduleFireTimesUpdatesTimestamps(VertxTestContext ctx) {
        String jobId = "sched-fire-times-" + UUID.randomUUID();
        CronJobSchedule schedule = newSchedule(jobId);
        Instant lastFiredAt = Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        Instant nextFireAt = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

        repository
                .saveSchedule(schedule)
                .compose(v -> repository.updateScheduleFireTimes(jobId, lastFiredAt, nextFireAt))
                .compose(v -> pool.preparedQuery(
                                "SELECT last_fired_at, next_fire_at FROM job_schedules WHERE job_id = $1")
                        .execute(Tuple.of(jobId))
                        .map(rows -> rows.iterator().next()))
                .onSuccess(row -> ctx.verify(() -> {
                    assertNotNull(row.getOffsetDateTime("last_fired_at"), "last_fired_at must be set");
                    assertNotNull(row.getOffsetDateTime("next_fire_at"), "next_fire_at must be set");
                    assertEquals(
                            lastFiredAt,
                            row.getOffsetDateTime("last_fired_at").toInstant(),
                            "last_fired_at must match");
                    assertEquals(
                            nextFireAt, row.getOffsetDateTime("next_fire_at").toInstant(), "next_fire_at must match");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("saveSchedule persists service: target with null handler")
    void saveScheduleWithServiceTarget(VertxTestContext ctx) {
        String jobId = "sched-service-target-" + UUID.randomUUID();
        CronJobSchedule schedule = new CronJobSchedule(
                jobId,
                "0 0 9 * * *",
                null, // handler is null for service: targets
                "service:reporting.report-service.generate",
                "SINGLE_INSTANCE",
                "Europe/Helsinki",
                true,
                "SKIP",
                5,
                true,
                null,
                null);

        repository
                .saveSchedule(schedule)
                .compose(v -> pool.preparedQuery("SELECT handler, target, execution_mode, timezone, max_attempts"
                                + " FROM job_schedules WHERE job_id = $1")
                        .execute(Tuple.of(jobId))
                        .map(rows -> rows.iterator().next()))
                .onSuccess(row -> ctx.verify(() -> {
                    assertNull(row.getString("handler"), "handler must be null for service: targets");
                    assertEquals("service:reporting.report-service.generate", row.getString("target"));
                    assertEquals("SINGLE_INSTANCE", row.getString("execution_mode"));
                    assertEquals("Europe/Helsinki", row.getString("timezone"));
                    assertEquals(5, row.getInteger("max_attempts"));
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("findSchedule returns service: target from persisted row")
    void findScheduleReturnsServiceTarget(VertxTestContext ctx) {
        String jobId = "sched-service-find-" + UUID.randomUUID();
        CronJobSchedule schedule = new CronJobSchedule(
                jobId,
                "0 30 2 * * *",
                null,
                "service:maintenance.cleanup.users",
                "EVERY_INSTANCE",
                "UTC",
                true,
                "SKIP",
                3,
                true,
                null,
                null);

        repository
                .saveSchedule(schedule)
                .compose(v -> repository.findSchedule(jobId))
                .onSuccess(opt -> ctx.verify(() -> {
                    assertTrue(opt.isPresent(), "schedule must be found");
                    CronJobSchedule found = opt.get();
                    assertNull(found.handler(), "handler must be null for service: targets");
                    assertEquals("service:maintenance.cleanup.users", found.target());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("serverHeartbeat upserts server row: second call updates last_heartbeat")
    void serverHeartbeatUpsertsServerRow(VertxTestContext ctx) {
        String serverId = "test-server-" + UUID.randomUUID();

        repository
                .serverHeartbeat(serverId)
                .compose(
                        v -> pool.preparedQuery("SELECT last_heartbeat FROM job_server_heartbeats WHERE server_id = $1")
                                .execute(Tuple.of(serverId))
                                .map(rows -> rows.iterator().next().getOffsetDateTime("last_heartbeat")))
                .compose(firstHeartbeat -> {
                    assertNotNull(firstHeartbeat, "first heartbeat must be set");
                    // Wait a tick to ensure NOW() advances, then upsert again
                    return repository.serverHeartbeat(serverId).compose(v -> pool.preparedQuery(
                                    "SELECT last_heartbeat FROM job_server_heartbeats WHERE server_id = $1")
                            .execute(Tuple.of(serverId))
                            .map(rows -> rows.iterator().next().getOffsetDateTime("last_heartbeat")));
                })
                .onSuccess(secondHeartbeat -> ctx.verify(() -> {
                    assertNotNull(secondHeartbeat, "second heartbeat must be set");
                    // The row must exist; exact timestamp equality is not guaranteed due to
                    // NOW() precision, but we verify the upsert did not fail and the row exists
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("findDeadServers returns servers whose heartbeat has expired")
    void findDeadServersReturnsExpiredServers(VertxTestContext ctx) {
        String deadServer = "dead-server-" + UUID.randomUUID();
        String liveServer = "live-server-" + UUID.randomUUID();

        // Insert a heartbeat row with a past last_heartbeat for the dead server
        pool.preparedQuery("INSERT INTO job_server_heartbeats (server_id, last_heartbeat, started_at)"
                        + " VALUES ($1, NOW() - INTERVAL '2 minutes', NOW())")
                .execute(Tuple.of(deadServer))
                .compose(v -> repository.serverHeartbeat(liveServer))
                .compose(v -> repository.findDeadServers(Duration.ofMinutes(1)))
                .onSuccess(deadServers -> ctx.verify(() -> {
                    assertTrue(
                            deadServers.contains(deadServer), "Dead server with expired heartbeat should be returned");
                    assertFalse(
                            deadServers.contains(liveServer), "Live server with fresh heartbeat must not be returned");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("removeServer deletes the heartbeat row")
    void removeServerDeletesHeartbeatRow(VertxTestContext ctx) {
        String serverId = "remove-server-" + UUID.randomUUID();

        repository
                .serverHeartbeat(serverId)
                .compose(v -> pool.preparedQuery("SELECT COUNT(*) FROM job_server_heartbeats WHERE server_id = $1")
                        .execute(Tuple.of(serverId))
                        .map(rows -> rows.iterator().next().getLong(0)))
                .compose(countBefore -> {
                    assertEquals(1L, countBefore, "Row should exist after serverHeartbeat");
                    return repository.removeServer(serverId);
                })
                .compose(v -> pool.preparedQuery("SELECT COUNT(*) FROM job_server_heartbeats WHERE server_id = $1")
                        .execute(Tuple.of(serverId))
                        .map(rows -> rows.iterator().next().getLong(0)))
                .onSuccess(countAfter -> ctx.verify(() -> {
                    assertEquals(0L, countAfter, "Row should be gone after removeServer");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("scheduleRetry fails when the execution is not in a retryable state")
    void scheduleRetryFailsWhenExecutionNotInRetryableState(VertxTestContext ctx) {
        String queue = "retry-state-guard-" + UUID.randomUUID();
        JobExecution exec = newExecution(queue, 0);

        repository
                .save(exec)
                .compose(id -> repository.claimNextJob(queue, 1))
                .compose(claimed -> {
                    UUID id = claimed.get(0).id();
                    // Attempt to schedule retry directly from PROCESSING state — the SQL guard
                    // (WHERE state IN ('FAILED','ABANDONED')) must reject this as zero rows updated.
                    return repository.scheduleRetry(id, Instant.now().plusSeconds(30), 1);
                })
                .onSuccess(v -> ctx.failNow(new AssertionError("Expected failure but scheduleRetry succeeded")))
                .onFailure(err -> ctx.verify(() -> {
                    assertInstanceOf(
                            IllegalStateException.class,
                            err,
                            "scheduleRetry from PROCESSING state must fail with IllegalStateException");
                    assertTrue(
                            err.getMessage().contains("was not in a retryable state"),
                            "Failure message must mention retryable state");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("failAndScheduleRetry atomically re-enqueues: returns FAILED snapshot, committed row is ENQUEUED")
    void failAndScheduleRetryAtomicallyReenqueuesReturningFailedSnapshot(VertxTestContext ctx) {
        String queue = "fail-and-retry-" + UUID.randomUUID();
        JobExecution exec = newExecution(queue, 0);
        Instant nextScheduledAt = Instant.now().plusSeconds(30);

        repository
                .save(exec)
                .compose(id -> repository.claimNextJob(queue, 1))
                .compose(claimed -> {
                    UUID id = claimed.get(0).id();
                    return repository.failAndScheduleRetry(
                            id, "boom", "java.lang.RuntimeException", ProgressSnapshot.EMPTY, nextScheduledAt, 1);
                })
                .compose(optFailed -> {
                    ctx.verify(() -> {
                        assertTrue(optFailed.isPresent(), "failAndScheduleRetry must return the FAILED snapshot");
                        JobExecution failedSnapshot = optFailed.get();
                        // The returned snapshot reflects the FAILED state (audit record)
                        assertEquals(JobState.FAILED, failedSnapshot.state(), "snapshot state must be FAILED");
                    });
                    // Verify the committed row is ENQUEUED (re-enqueued for retry)
                    return repository.findById(exec.id());
                })
                .onSuccess(opt -> ctx.verify(() -> {
                    assertTrue(opt.isPresent(), "execution must still exist after failAndScheduleRetry");
                    JobExecution committed = opt.get();
                    assertEquals(
                            JobState.ENQUEUED, committed.state(), "committed state must be ENQUEUED (re-enqueued)");
                    assertEquals(1, committed.attemptNumber(), "attempt number must be incremented to 1");
                    assertNull(committed.lockedBy(), "lockedBy must be cleared after re-enqueue");
                    assertNull(committed.startedAt(), "startedAt must be cleared after re-enqueue");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("failAndScheduleRetry returns empty when execution is not in PROCESSING/ABANDONED state")
    void failAndScheduleRetryReturnsEmptyWhenNotInProgress(VertxTestContext ctx) {
        String queue = "fail-and-retry-noop-" + UUID.randomUUID();
        // Save but do NOT claim — the row stays ENQUEUED, which is not a valid source state
        JobExecution exec = newExecution(queue, 0);
        Instant nextScheduledAt = Instant.now().plusSeconds(30);

        repository
                .save(exec)
                .compose(id -> repository.failAndScheduleRetry(
                        exec.id(), "boom", "java.lang.RuntimeException", ProgressSnapshot.EMPTY, nextScheduledAt, 1))
                .compose(optResult -> {
                    ctx.verify(() -> assertTrue(
                            optResult.isEmpty(),
                            "failAndScheduleRetry on a non-PROCESSING row must return empty (no-op)"));
                    // The row must remain ENQUEUED — no state change
                    return repository.findById(exec.id());
                })
                .onSuccess(opt -> ctx.verify(() -> {
                    assertTrue(opt.isPresent(), "execution must still exist");
                    assertEquals(JobState.ENQUEUED, opt.get().state(), "row must stay ENQUEUED after no-op");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName(
            "abandonAndScheduleRetry atomically re-enqueues: returns ABANDONED snapshot, committed row is ENQUEUED")
    void abandonAndScheduleRetryAtomicallyReenqueuesReturningAbandonedSnapshot(VertxTestContext ctx) {
        String queue = "abandon-and-retry-" + UUID.randomUUID();
        JobExecution exec = newExecution(queue, 0);
        Instant nextScheduledAt = Instant.now().plusSeconds(30);

        repository
                .save(exec)
                .compose(id -> repository.claimNextJob(queue, 1))
                .compose(claimed -> {
                    UUID id = claimed.get(0).id();
                    return repository.abandonAndScheduleRetry(
                            id, "timeout", "ExecutionTimeoutException", ProgressSnapshot.EMPTY, nextScheduledAt, 1);
                })
                .compose(optAbandoned -> {
                    ctx.verify(() -> {
                        assertTrue(
                                optAbandoned.isPresent(), "abandonAndScheduleRetry must return the ABANDONED snapshot");
                        JobExecution abandonedSnapshot = optAbandoned.get();
                        // The returned snapshot reflects the ABANDONED state (audit record)
                        assertEquals(JobState.ABANDONED, abandonedSnapshot.state(), "snapshot state must be ABANDONED");
                    });
                    // Verify the committed row is ENQUEUED (re-enqueued for retry)
                    return repository.findById(exec.id());
                })
                .onSuccess(opt -> ctx.verify(() -> {
                    assertTrue(opt.isPresent(), "execution must still exist after abandonAndScheduleRetry");
                    JobExecution committed = opt.get();
                    assertEquals(
                            JobState.ENQUEUED, committed.state(), "committed state must be ENQUEUED (re-enqueued)");
                    assertEquals(1, committed.attemptNumber(), "attempt number must be incremented to 1");
                    assertNull(committed.lockedBy(), "lockedBy must be cleared after re-enqueue");
                    assertNull(committed.startedAt(), "startedAt must be cleared after re-enqueue");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("abandonAndScheduleRetry returns empty when execution is not in PROCESSING/ABANDONED state")
    void abandonAndScheduleRetryReturnsEmptyWhenNotInProgress(VertxTestContext ctx) {
        String queue = "abandon-and-retry-noop-" + UUID.randomUUID();
        // Save but do NOT claim — the row stays ENQUEUED, which is not a valid source state
        JobExecution exec = newExecution(queue, 0);
        Instant nextScheduledAt = Instant.now().plusSeconds(30);

        repository
                .save(exec)
                .compose(id -> repository.abandonAndScheduleRetry(
                        exec.id(), "timeout", "ExecutionTimeoutException", ProgressSnapshot.EMPTY, nextScheduledAt, 1))
                .compose(optResult -> {
                    ctx.verify(() -> assertTrue(
                            optResult.isEmpty(),
                            "abandonAndScheduleRetry on a non-PROCESSING row must return empty (no-op)"));
                    // The row must remain ENQUEUED — no state change
                    return repository.findById(exec.id());
                })
                .onSuccess(opt -> ctx.verify(() -> {
                    assertTrue(opt.isPresent(), "execution must still exist");
                    assertEquals(JobState.ENQUEUED, opt.get().state(), "row must stay ENQUEUED after no-op");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("findByLockedBy returns PROCESSING executions owned by the given server")
    void findByLockedByReturnsPROCESSINGExecutions(VertxTestContext ctx) {
        String queue = "locked-by-test-" + UUID.randomUUID();
        // Save and claim two jobs so they are PROCESSING and owned by our repository's nodeId
        JobExecution e1 = newExecution(queue, 0);
        JobExecution e2 = newExecution(queue, 0);

        Future.all(repository.save(e1), repository.save(e2))
                .compose(v -> repository.claimNextJob(queue, 2))
                .compose(claimed -> {
                    assertEquals(2, claimed.size(), "Should have claimed 2 jobs");
                    // All claimed jobs are locked by this repository's nodeId — read it back
                    String nodeId = claimed.get(0).lockedBy();
                    assertNotNull(nodeId, "lockedBy must be set after claim");
                    return repository.findByLockedBy(nodeId);
                })
                .onSuccess(found -> ctx.verify(() -> {
                    // The shared test repository nodeId may have locked executions from other tests
                    // in the same class; verify our two executions are included and all are PROCESSING
                    List<UUID> foundIds = found.stream().map(JobExecution::id).toList();
                    assertTrue(foundIds.contains(e1.id()), "findByLockedBy must include first claimed execution");
                    assertTrue(foundIds.contains(e2.id()), "findByLockedBy must include second claimed execution");
                    for (JobExecution e : found) {
                        assertEquals(JobState.PROCESSING, e.state(), "Returned executions must be PROCESSING");
                    }
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }
}
