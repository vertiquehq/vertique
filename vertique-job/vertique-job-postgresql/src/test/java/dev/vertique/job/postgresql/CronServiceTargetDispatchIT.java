// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.eventbus.EventBusExceptionMapper;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.job.CronJobSchedule;
import dev.vertique.job.cron.CronExpression;
import dev.vertique.job.cron.CronJobDefinition;
import dev.vertique.job.cron.CronScheduler;
import dev.vertique.job.cron.CronTargetReference;
import dev.vertique.job.cron.ExecutionMode;
import dev.vertique.job.cron.MisfirePolicy;
import dev.vertique.job.cron.OverlapPolicy;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceTargetResolver;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration coverage for the composition boundary behind issue #41: a real {@link CronScheduler}
 * dispatching a {@code SINGLE_INSTANCE}, {@code service:}-targeted cron job against a real
 * {@link PgJobRepository} backed by PostgreSQL.
 *
 * <p>{@code CronSchedulerTest} proves the scheduler resolves the effective handler address
 * correctly, but does so against mock repositories that happily accept a {@code null}
 * {@code JobExecution.handler()}. {@link PgJobRepositoryIT} proves the repository rejects a
 * {@code null} handler against the real {@code job_executions.handler NOT NULL} column, but always
 * supplies a non-null handler itself. Neither test alone could have caught a regression where the
 * scheduler persists a {@code null} handler for a {@code service:} target and the
 * {@code SINGLE_INSTANCE} leader-election {@code INSERT} silently fails against the real schema —
 * which is exactly what issue #41 was. This test wires both halves together.
 *
 * <p>The trigger is deliberately misfire recovery, not a one-second cron expression polled on the
 * wall clock: {@link CronScheduler#start()} runs misfire recovery synchronously as part of
 * starting up, for any {@code SINGLE_INSTANCE} job whose {@link MisfirePolicy} is not
 * {@code SKIP} and whose persisted {@link CronJobSchedule#lastFiredAt()} is non-null with a missed
 * fire since then. Persisting a schedule row whose {@code lastFiredAt} is two days in the past —
 * for a job whose normal cron fire time is hours away — makes the dispatch fire as soon as
 * {@code start()}'s one {@code findSchedule} query resolves, gated on that DB round trip rather
 * than on the clock.
 *
 * <p>The job definition below sets {@code handlerAddress = null} on a
 * {@link CronTargetReference.ServiceTarget} — the exact shape {@code CronJobRegistrar} produces for
 * an annotated {@code @CronJob} whose target is a service operation, since the runtime address for
 * a service target is only known once {@link ServiceTargetResolver} resolves it per fire.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class CronServiceTargetDispatchIT {

    /** Stable target id resolved by the stubbed {@link ServiceTargetResolver} below. */
    private static final String STABLE_TARGET_ID = "maintenance.cleanup-service.cleanup";

    /**
     * The event bus address {@link #STABLE_TARGET_ID} resolves to — deliberately different from
     * the target id so an assertion on the persisted handler cannot pass by echoing the target id
     * back as the address.
     */
    private static final String RESOLVED_ADDRESS = "maintenance/cleanup-service/cleanup";

    static final PostgresContainer db =
            new PostgresContainer().withDatabaseName("cron_dispatch_test").withMigration("classpath:db/migration/job");

    static Pool pool;
    static PgJobRepository repository;

    private CronScheduler scheduler;

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

    /** Registers the local {@link DispatchEnvelope} codec, tolerating a re-registration from a prior test class. */
    @BeforeEach
    void registerDispatchEnvelopeCodec(Vertx vertx) {
        try {
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.envelope"));
        } catch (IllegalStateException e) {
            // Already registered — safe to ignore
        }
    }

    @AfterEach
    void stopScheduler() {
        if (scheduler != null) {
            scheduler.stop();
        }
    }

    /**
     * Persists a {@code SINGLE_INSTANCE} {@code service:}-target job schedule with a stale
     * {@code lastFiredAt}, starts a real {@link CronScheduler}, and asserts — from inside the
     * dispatched consumer — that the leader-election insert succeeded and that the persisted
     * {@code job_executions} row carries the resolved handler address rather than {@code null}.
     */
    @Test
    @DisplayName("SINGLE_INSTANCE service-target cron job dispatches via misfire recovery and persists"
            + " the resolved handler, not null")
    void singleInstanceServiceTargetJobDispatchesAndPersistsResolvedHandler(Vertx vertx, VertxTestContext ctx) {
        String jobId = "cron-service-dispatch-" + UUID.randomUUID();

        ServiceTargetResolver resolver = mock(ServiceTargetResolver.class);
        when(resolver.resolve(STABLE_TARGET_ID))
                .thenReturn(new ResolvedServiceTarget(
                        STABLE_TARGET_ID, null, "maintenance", "cleanup-service", "cleanup", null, RESOLVED_ADDRESS));

        vertx.eventBus().consumer(RESOLVED_ADDRESS, msg -> {
            DispatchEnvelope<?> body = (DispatchEnvelope<?>) msg.body();
            body.replyAddress().ifPresent(replyAddress -> vertx.eventBus()
                    .send(
                            replyAddress,
                            DispatchEnvelope.of("done"),
                            new DeliveryOptions().setCodecName("dispatch.envelope")));

            // The consumer firing already proves the first half of the composition: the
            // SINGLE_INSTANCE leader-election INSERT succeeded against the real NOT NULL
            // job_executions.handler column. The second half — that the persisted row carries the
            // resolved address, not null — is proven by the query below, chained off arrival.
            pool.preparedQuery("SELECT COUNT(*) FROM job_executions WHERE job_id = $1")
                    .execute(Tuple.of(jobId))
                    .compose(countRows -> {
                        ctx.verify(() -> assertEquals(
                                1L,
                                countRows.iterator().next().getLong(0),
                                "exactly one job_executions row must exist for job_id=" + jobId));
                        return pool.preparedQuery("SELECT handler FROM job_executions WHERE job_id = $1")
                                .execute(Tuple.of(jobId));
                    })
                    .onSuccess(handlerRows -> ctx.verify(() -> {
                        assertEquals(
                                RESOLVED_ADDRESS,
                                handlerRows.iterator().next().getString("handler"),
                                "persisted handler must equal the resolved service-target address, not null");
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        });

        CronJobDefinition job = new CronJobDefinition(
                jobId,
                new CronExpression("0 0 3 * * *"), // next normal fire is hours away, not seconds
                new CronTargetReference.ServiceTarget(STABLE_TARGET_ID),
                null, // handlerAddress = null — the exact shape CronJobRegistrar derives for a service target
                ExecutionMode.SINGLE_INSTANCE,
                ZoneId.of("UTC"),
                3,
                null,
                OverlapPolicy.SKIP,
                true,
                Map.of(),
                MisfirePolicy.FIRE_NOW);

        Instant twoDaysAgo = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Instant nextFireHint = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        CronJobSchedule schedule = new CronJobSchedule(
                jobId,
                "0 0 3 * * *",
                null,
                "service:" + STABLE_TARGET_ID,
                "SINGLE_INSTANCE",
                "UTC",
                true,
                "SKIP",
                3,
                true,
                null,
                null);

        repository
                .saveSchedule(schedule)
                // saveSchedule's upsert does not touch last_fired_at/next_fire_at (see
                // PgJobRepository.SQL_SAVE_SCHEDULE) — updateScheduleFireTimes is required to make
                // a stale lastFiredAt visible to misfire recovery's findSchedule() read.
                .compose(v -> repository.updateScheduleFireTimes(jobId, twoDaysAgo, nextFireHint))
                .onSuccess(v -> {
                    scheduler = new CronScheduler(
                            vertx,
                            Set.of(),
                            repository,
                            resolver,
                            new EventBusClient(vertx, new EventBusExceptionMapper()),
                            DispatchEnvelopeBuilder.forTesting());
                    scheduler.register(job);
                    scheduler.start();
                })
                .onFailure(ctx::failNow);
    }
}
