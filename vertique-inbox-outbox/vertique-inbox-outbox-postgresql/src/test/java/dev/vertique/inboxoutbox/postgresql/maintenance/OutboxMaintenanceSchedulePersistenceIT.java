// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.job.CronJobSchedule;
import dev.vertique.job.JobRepository;
import dev.vertique.job.cron.CronJobRegistrar;
import dev.vertique.job.cron.CronScheduler;
import dev.vertique.job.cron.ExecutionMode;
import dev.vertique.job.cron.config.CronConfig;
import dev.vertique.job.postgresql.PgJobRepository;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceTargetResolver;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test that proves the {@code @CronJob} methods on
 * {@link OutboxMaintenanceCron} are discovered, validated, and persisted to the
 * cron-schedule table against a real PostgreSQL instance.
 *
 * <p>Scope is intentionally narrow: registration + persistence only, not live fire. The
 * full deployment lifecycle (INFRA → services → SERVICES → EDGE) is exercised by the cron
 * lifecycle verticle's own tests in {@code vertique-job-cron}; this IT focuses on the
 * hand-off between {@link CronJobRegistrar#scan()} and {@link JobRepository#saveSchedule} for
 * the migrated maintenance jobs.
 *
 * <p>{@link CronJobRegistrar} persists schedules fire-and-forget, so the assertions poll
 * {@link JobRepository#findSchedule(String)} until both rows appear within a bounded timeout.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class OutboxMaintenanceSchedulePersistenceIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("outbox_maintenance_test")
            .withMigration("classpath:db/migration/job");

    static Pool pool;
    static JobRepository jobRepository;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(4))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
        jobRepository = new PgJobRepository(pool, new PgDbExceptionMapper());
        ctx.completeNow();
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    @DisplayName("scan() persists outbox-stale-lease-recovery and outbox-cleanup schedule rows")
    void persistsBothMaintenanceSchedules(Vertx vertx, VertxTestContext ctx) {
        OutboxMaintenanceCron impl = new OutboxMaintenanceCron(() -> null);
        ServiceContractRegistry registry =
                ServiceContractRegistry.build(Set.of(impl), new DefaultConfigParser(DefaultConfigMapper.lenient()));
        ServiceTargetResolver resolver = stubTargetResolver();

        // Real CronScheduler — registration only, never .start() so no timers are armed.
        CronScheduler scheduler = new CronScheduler(
                vertx,
                Set.of(),
                jobRepository,
                resolver,
                null,
                dev.vertique.context.DispatchEnvelopeBuilder.forTesting());

        new CronJobRegistrar(
                        scheduler,
                        registry,
                        resolver,
                        CronConfig.fromConfig(new JsonObject(), new DefaultConfigParser(DefaultConfigMapper.lenient())),
                        jobRepository)
                .scan();

        // saveSchedule(...) is fire-and-forget. Poll until both rows land or the test timeout
        // trips.
        pollForSchedule(vertx, "outbox-stale-lease-recovery")
                .compose(stale -> {
                    assertEquals("*/30 * * * * *", stale.cronExpression());
                    assertEquals(ExecutionMode.SINGLE_INSTANCE.name(), stale.executionMode());
                    return pollForSchedule(vertx, "outbox-cleanup");
                })
                .onComplete(ctx.succeeding(cleanup -> {
                    assertEquals("0 0 */6 * * *", cleanup.cronExpression());
                    assertEquals(ExecutionMode.SINGLE_INSTANCE.name(), cleanup.executionMode());
                    ctx.completeNow();
                }));
    }

    /**
     * Polls {@link JobRepository#findSchedule(String)} every 50 ms (up to ~5 s) until the row
     * for {@code jobId} appears. Returns the persisted schedule when it does.
     */
    private static io.vertx.core.Future<CronJobSchedule> pollForSchedule(Vertx vertx, String jobId) {
        io.vertx.core.Promise<CronJobSchedule> promise = io.vertx.core.Promise.promise();
        pollOnce(vertx, jobId, 0, promise);
        return promise.future();
    }

    private static void pollOnce(
            Vertx vertx, String jobId, int attempts, io.vertx.core.Promise<CronJobSchedule> promise) {
        jobRepository.findSchedule(jobId).onFailure(promise::fail).onSuccess(opt -> {
            Optional<CronJobSchedule> result = opt;
            if (result.isPresent()) {
                promise.complete(result.get());
                return;
            }
            if (attempts >= 100) {
                promise.fail("schedule '" + jobId + "' did not appear within ~5s");
                return;
            }
            vertx.setTimer(50, id -> pollOnce(vertx, jobId, attempts + 1, promise));
        });
    }

    private static ServiceTargetResolver stubTargetResolver() {
        ServiceTargetResolver resolver = mock(ServiceTargetResolver.class);
        when(resolver.resolve(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return new ResolvedServiceTarget(id, null, "", id, id, null, id);
        });
        return resolver;
    }
}
