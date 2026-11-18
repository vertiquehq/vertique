// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.job.JobCoordinator;
import dev.vertique.job.JobCoordinatorConfig;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobRepository;
import dev.vertique.job.JobState;
import dev.vertique.job.JobType;
import dev.vertique.job.ProgressSnapshot;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Dead-node recovery writes are fenced by the claim, against a real PostgreSQL instance.
 *
 * <p>A dead node's executions are recovered by a coordinator that reads them with
 * {@code findByLockedBy} and writes each recovery afterwards. The first test places a re-claim
 * between that read and the writes: after the read returns, every orphan is re-enqueued and claimed
 * again by a live node. The coordinator runs through the production {@code NotifyingJobRepository}
 * decorator, and the recovery writes (retry, dead-letter and cron abandon) must leave every new claim
 * untouched. The second test makes the re-claim by the dead node's own identity, at a new attempt,
 * as a node that stalled past the heartbeat timeout and woke would: only the attempt predicate tells the
 * stale write from the live claim. The third test is the control: with no re-claim, the same coordinator
 * recovers all three.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class JobCoordinatorRecoveryFenceIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("job_recovery_fence_test")
            .withMigration("classpath:db/migration/job");

    private static final String DEAD_NODE = "dead-node";
    private static final String LIVE_NODE = "live-node";

    static Pool pool;
    static PgDbExceptionMapper mapper = new PgDbExceptionMapper();

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new io.vertx.sqlclient.PoolOptions().setMaxSize(10))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
        ctx.completeNow();
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    @DisplayName("an orphan claimed again by another node between the coordinator's read and its write is left"
            + " untouched")
    void reclaimedOrphansAreLeftAlone(Vertx vertx) throws Exception {
        assertReclaimedOrphansAreLeftAlone(vertx, LIVE_NODE);
    }

    @Test
    @DisplayName("an orphan the same node re-claims at a new attempt between the coordinator's read and its write"
            + " is left untouched")
    void orphansReclaimedByTheSameNodeAreLeftAlone(Vertx vertx) throws Exception {
        // A node that stalled past the heartbeat timeout wakes and claims its own orphan again: the row has
        // the same locked_by and a new attempt, so only the attempt tells the stale write apart.
        assertReclaimedOrphansAreLeftAlone(vertx, DEAD_NODE);
    }

    private void assertReclaimedOrphansAreLeftAlone(Vertx vertx, String reclaimingNode) throws Exception {
        Orphans orphans = claimOrphansAsDeadNode();
        PgJobRepository reclaimingRepository = new PgJobRepository(pool, mapper, DbSchemas.empty(), reclaimingNode);
        AtomicInteger reads = new AtomicInteger();

        // The coordinator's repository is the production decorator over the real repository; after its
        // read returns, the reclaiming node claims each orphan again.
        JobRepository decorated = decoratedRepository();
        JobRepository interleaving = mock(JobRepository.class, withSettings().defaultAnswer(delegatesTo(decorated)));
        doAnswer(invocation -> decorated.findByLockedBy(DEAD_NODE).compose(read -> {
                    if (reads.getAndIncrement() > 0) {
                        return Future.succeededFuture(read);
                    }
                    return reclaim(reclaimingRepository, orphans).map(read);
                }))
                .when(interleaving)
                .findByLockedBy(DEAD_NODE);

        runCoordinatorUntilDeadNodeIsHandled(vertx, interleaving);

        assertTrue(reads.get() >= 1, "the interleaving read must have happened");
        for (UUID id : orphans.all()) {
            JobExecution row = await(decorated.findById(id)).orElseThrow();
            assertEquals(JobState.PROCESSING, row.state(), "the new claim must stay PROCESSING");
            assertEquals(reclaimingNode, row.lockedBy(), "the new claim must keep its owner");
            assertNull(row.errorType(), "recovery must not have recorded a crash on the new claim");
        }
        assertEquals(
                1,
                await(decorated.findById(orphans.retryable())).orElseThrow().attemptNumber(),
                "the re-claim's attempt must not be advanced again by recovery");
    }

    @Test
    @DisplayName("control: without a re-claim recovery retries, dead-letters and abandons the three orphans")
    void unclaimedOrphansAreRecovered(Vertx vertx) throws Exception {
        Orphans orphans = claimOrphansAsDeadNode();
        JobRepository decorated = decoratedRepository();

        runCoordinatorUntilDeadNodeIsHandled(vertx, decorated);

        JobExecution retried = await(decorated.findById(orphans.retryable())).orElseThrow();
        assertEquals(JobState.ENQUEUED, retried.state());
        assertEquals(1, retried.attemptNumber());
        assertNull(retried.lockedBy());
        assertEquals(
                JobState.DEAD_LETTER,
                await(decorated.findById(orphans.exhausted())).orElseThrow().state());
        assertEquals(
                JobState.ABANDONED,
                await(decorated.findById(orphans.cron())).orElseThrow().state());
    }

    // --- Scenario ---

    private record Orphans(UUID retryable, UUID exhausted, UUID cron) {
        List<UUID> all() {
            return List.of(retryable, exhausted, cron);
        }
    }

    /** Saves one execution per recovery branch and claims all three as the dead node. */
    private Orphans claimOrphansAsDeadNode() throws Exception {
        PgJobRepository deadRepository = new PgJobRepository(pool, mapper, DbSchemas.empty(), DEAD_NODE);
        String queue = "fence-" + UUID.randomUUID();
        UUID retryable = await(deadRepository.save(execution(JobType.DELAYED, queue, 0, 3)));
        UUID exhausted = await(deadRepository.save(execution(JobType.DELAYED, queue, 2, 3)));
        UUID cron = await(deadRepository.save(execution(JobType.CRON, queue, 0, 3)));
        List<JobExecution> claimed = await(deadRepository.claimNextJob(queue, 10));
        assertEquals(3, claimed.size(), "the dead node must claim all three executions");
        return new Orphans(retryable, exhausted, cron);
    }

    /** Puts each orphan back on the queue, at the next attempt, and claims it again as the given node. */
    private Future<Void> reclaim(PgJobRepository reclaimingRepository, Orphans orphans) {
        return pool.preparedQuery("UPDATE job_executions SET state = 'ENQUEUED', locked_by = NULL, started_at = NULL,"
                        + " attempt = attempt + 1, scheduled_at = NOW() - interval '1 second'"
                        + " WHERE id = ANY($1) RETURNING queue")
                .execute(Tuple.of(orphans.all().toArray(UUID[]::new)))
                .compose(rows -> {
                    String queue = rows.iterator().next().getString("queue");
                    return reclaimingRepository.claimNextJob(queue, 10);
                })
                .map(claimed -> {
                    assertEquals(3, claimed.size(), "the reclaiming node must claim all three again");
                    return null;
                });
    }

    private void runCoordinatorUntilDeadNodeIsHandled(Vertx vertx, JobRepository repository) throws Exception {
        await(pool.query("DELETE FROM job_server_heartbeats").execute());
        await(pool.preparedQuery("INSERT INTO job_server_heartbeats (server_id, last_heartbeat, started_at)"
                        + " VALUES ($1, NOW() - interval '1 hour', NOW() - interval '2 hours')")
                .execute(Tuple.of(DEAD_NODE)));
        JobCoordinator coordinator = new JobCoordinator(
                vertx,
                repository,
                JobCoordinatorConfig.builder()
                        .nodeHeartbeatIntervalMs(200)
                        .nodeHeartbeatTimeoutMs(2_000)
                        .scanIntervalMs(1_000)
                        .build(),
                "surviving-node-" + UUID.randomUUID());
        await(coordinator.start());
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (heartbeatServers().contains(DEAD_NODE)) {
                assertTrue(System.nanoTime() < deadline, "the dead node's heartbeat row was never removed");
                Thread.sleep(100);
            }
        } finally {
            await(coordinator.stop());
        }
        assertFalse(heartbeatServers().contains(DEAD_NODE), "recovery completes and removes the dead node's row");
    }

    private static JobExecution execution(JobType type, String queue, int attempt, int maxAttempts) {
        return new JobExecution(
                UUID.randomUUID(),
                "fence-job-" + UUID.randomUUID(),
                type,
                "job.fence",
                queue,
                JobState.ENQUEUED,
                attempt,
                maxAttempts,
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
                null);
    }

    private static List<String> heartbeatServers() throws Exception {
        List<String> servers = new java.util.ArrayList<>();
        for (Row row :
                await(pool.query("SELECT server_id FROM job_server_heartbeats").execute())) {
            servers.add(row.getString("server_id"));
        }
        return servers;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static JobRepository decoratedRepository() {
        return DaggerJobCoordinatorRecoveryFenceIT_NodeComponent.factory()
                .create(pool)
                .jobRepository();
    }

    // --- The production repository graph ---

    @Singleton
    @Component(modules = {JobPostgresqlModule.class, ExceptionMapperModule.class})
    interface NodeComponent {

        /** The unqualified binding: the notifying decorator over the real repository. */
        JobRepository jobRepository();

        @Component.Factory
        interface Factory {
            NodeComponent create(@BindsInstance Pool pool);
        }
    }

    @Module
    static final class ExceptionMapperModule {

        @Provides
        @Singleton
        static PgDbExceptionMapper pgDbExceptionMapper() {
            return new PgDbExceptionMapper();
        }
    }
}
