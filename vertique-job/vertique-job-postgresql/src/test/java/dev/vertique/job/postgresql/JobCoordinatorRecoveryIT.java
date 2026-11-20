// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.deploy.VerticleDeployer;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.deploy.VerticleDeploymentManager;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobNodeId;
import dev.vertique.job.JobState;
import dev.vertique.job.JobType;
import dev.vertique.job.ProgressSnapshot;
import dev.vertique.job.dagger.JobCoordinatorModule;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end dead-node recovery against a real PostgreSQL instance, with each node built from the
 * production job modules and its verticles deployed and undeployed through the framework's
 * {@link VerticleDeploymentManager}, which orders them the way the application runner does.
 *
 * <p>Node A claims a delayed execution and then dies: its Vert.x instance is closed, so its heartbeat
 * timer stops without the clean-shutdown path running. Node B's coordinator then finds A's heartbeat
 * expired, recovers the execution A claimed (re-enqueued with the next attempt) and removes A's
 * heartbeat row. Node B also deploys a claimer at the pollers' priority that claims one more execution
 * while it is being undeployed; the coordinator undeploys after it, sees the claim, and keeps B's
 * heartbeat row so the claim stays recoverable.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class JobCoordinatorRecoveryIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("job_coordinator_test")
            .withMigration("classpath:db/migration/job");

    /**
     * Fast coordinator timings. The heartbeat timeout is bound in whole seconds by the dead-server
     * query, so it stays at two seconds, ten heartbeat intervals.
     */
    private static final JsonObject CONFIG = new JsonObject()
            .put(
                    "job",
                    new JsonObject()
                            .put(
                                    "coordinator",
                                    new JsonObject()
                                            .put("nodeHeartbeatIntervalMs", 200)
                                            .put("nodeHeartbeatTimeoutMs", 2_000)
                                            .put("scanIntervalMs", 250)));

    static Pool pool;

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
        ctx.completeNow();
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    @DisplayName("a surviving node recovers the delayed execution a dead node claimed")
    void survivingNodeRecoversDeadNodesClaim(Vertx vertx) throws Exception {
        Vertx nodeAVertx = Vertx.vertx();
        NodeComponent nodeA =
                DaggerJobCoordinatorRecoveryIT_NodeComponent.factory().create(nodeAVertx, CONFIG, pool);
        await(manager(nodeAVertx, nodeA.deployments()).deployAll());

        String queue = "recovery-" + UUID.randomUUID();
        UUID executionId = await(nodeA.repository().save(delayedExecution(queue)));
        List<JobExecution> claimed = await(nodeA.repository().claimNextJob(queue, 1));
        assertEquals(1, claimed.size(), "node A must claim the execution");
        assertEquals(nodeA.nodeId(), claimed.get(0).lockedBy(), "claim must be stamped with node A's identity");
        assertTrue(heartbeatServers().contains(nodeA.nodeId()), "node A must heartbeat under the same identity");

        // Node A dies: its timers stop and its clean-shutdown path never runs.
        await(nodeAVertx.close());

        NodeComponent nodeB =
                DaggerJobCoordinatorRecoveryIT_NodeComponent.factory().create(vertx, CONFIG, pool);
        String lateQueue = "late-claim-" + UUID.randomUUID();
        UUID lateExecutionId = await(nodeB.repository().save(delayedExecution(lateQueue)));
        AtomicReference<JobExecution> lateClaim = new AtomicReference<>();
        Set<VerticleDeployment> nodeBDeployments = new HashSet<>(nodeB.deployments());
        nodeBDeployments.add(VerticleDeployment.of(
                "claims-on-stop",
                () -> new ClaimOnStopVerticle(nodeB.repository(), lateQueue, lateClaim),
                LifecyclePhase.SERVICES,
                100));
        VerticleDeploymentManager nodeBManager = manager(vertx, nodeBDeployments);
        await(nodeBManager.deployAll());

        JobExecution recovered = awaitState(nodeB, executionId, JobState.ENQUEUED);
        assertEquals(1, recovered.attemptNumber(), "recovery must re-enqueue with the next attempt");
        assertNull(recovered.lockedBy(), "the re-enqueued execution must be unclaimed");
        assertEquals("NodeCrashException", recovered.errorType());
        awaitCondition(() -> !heartbeatServers().contains(nodeA.nodeId()), "node A's heartbeat row must be removed");

        await(nodeBManager.undeployAll());
        assertEquals(lateExecutionId, lateClaim.get().id(), "the claimer must claim while it undeploys");
        assertEquals(nodeB.nodeId(), lateClaim.get().lockedBy());
        assertTrue(
                heartbeatServers().contains(nodeB.nodeId()),
                "the coordinator stops after the claimer, sees its claim and keeps node B's heartbeat row");
    }

    // --- Helpers ---

    private static VerticleDeploymentManager manager(Vertx vertx, Set<VerticleDeployment> deployments) {
        return new VerticleDeploymentManager(new VerticleDeployer(vertx), deployments);
    }

    /** Stands in for a delayed-job poller that claims one more execution as it is undeployed. */
    private static final class ClaimOnStopVerticle extends AbstractVerticle {

        private final PgJobRepository repository;
        private final String queue;
        private final AtomicReference<JobExecution> claim;

        ClaimOnStopVerticle(PgJobRepository repository, String queue, AtomicReference<JobExecution> claim) {
            this.repository = repository;
            this.queue = queue;
            this.claim = claim;
        }

        @Override
        public void stop(Promise<Void> stopPromise) {
            repository
                    .claimNextJob(queue, 1)
                    .onSuccess(claimed -> claim.set(claimed.get(0)))
                    .<Void>mapEmpty()
                    .onComplete(stopPromise);
        }
    }

    private static JobExecution delayedExecution(String queue) {
        return new JobExecution(
                UUID.randomUUID(),
                "recovery-job-" + UUID.randomUUID(),
                JobType.DELAYED,
                "job.delayed.recovery",
                queue,
                JobState.ENQUEUED,
                0,
                3,
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
        return StreamSupport.stream(
                        await(pool.query("SELECT server_id FROM job_server_heartbeats")
                                        .execute())
                                .spliterator(),
                        false)
                .map((Row row) -> row.getString("server_id"))
                .toList();
    }

    private static JobExecution awaitState(NodeComponent node, UUID executionId, JobState expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        JobExecution last = null;
        while (System.nanoTime() < deadline) {
            Optional<JobExecution> found = await(node.repository().findById(executionId));
            last = found.orElse(null);
            if (last != null && last.state() == expected) {
                return last;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("execution never reached " + expected + "; last seen: "
                + (last == null ? "absent" : last.state() + " locked by " + last.lockedBy()));
    }

    private static void awaitCondition(CheckedCondition condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (condition.holds()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError(message);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean holds() throws Exception;
    }

    // --- One node's graph ---

    @Singleton
    @Component(
            modules = {
                JobPostgresqlModule.class,
                JobCoordinatorModule.class,
                ConfigParsingModule.class,
                ExceptionMapperModule.class
            })
    interface NodeComponent {

        @JobNodeId
        String nodeId();

        PgJobRepository repository();

        Set<VerticleDeployment> deployments();

        @Component.Factory
        interface Factory {
            NodeComponent create(
                    @BindsInstance Vertx vertx,
                    @BindsInstance @VertxConfig JsonObject config,
                    @BindsInstance Pool pool);
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
