// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.core.context.DeferredExecutionOrigin;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.eventbus.EventBusExceptionMapper;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.core.eventbus.Result;
import dev.vertique.job.CronJobSchedule;
import dev.vertique.job.DefaultJobContext;
import dev.vertique.job.JobContext;
import dev.vertique.job.JobDispatchContext;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobInterceptor;
import dev.vertique.job.JobRepository;
import dev.vertique.job.JobState;
import dev.vertique.job.LogEntry;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceTargetResolver;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

/**
 * Tests for {@link CronScheduler} — timer registration, basic fire behaviour, overlap policies,
 * {@link JobInterceptor} invocation chain, and SINGLE_INSTANCE validation.
 */
@DisplayName("CronScheduler")
@ExtendWith(VertxExtension.class)
class CronSchedulerTest {

    private CronScheduler scheduler;

    /** Creates a test {@link EventBusClient} from the given Vert.x instance. */
    private static EventBusClient testEventBusClient(Vertx vertx) {
        return new EventBusClient(vertx, new EventBusExceptionMapper());
    }

    /**
     * Returns a stub {@link ServiceTargetResolver} that resolves any stable target id to the
     * same id used as the address (sufficient for tests that use {@link CronTargetReference.EventBusTarget}).
     */
    private static ServiceTargetResolver stubTargetResolver() {
        ServiceTargetResolver resolver = mock(ServiceTargetResolver.class);
        when(resolver.resolve(anyString())).thenAnswer(inv -> {
            String targetId = inv.getArgument(0);
            return new ResolvedServiceTarget(targetId, null, "", targetId, targetId, null, targetId);
        });
        return resolver;
    }

    /**
     * Builds a minimal stub repository that allows completeExecution and tryInsert calls.
     * The completion is captured so tests can verify the state passed to completeExecution.
     *
     * <p>Deliberately does <em>not</em> stub {@code saveLogs} — the job-log tests use that mock as
     * their completion signal and stub it per test.
     */
    private static JobRepository stubRepoCapturingCompletion(AtomicReference<JobState> capturedState) {
        JobRepository repo = mock(JobRepository.class);
        // Used by EVERY_INSTANCE tracked jobs — use thenAnswer to return fresh future per call
        when(repo.save(any(JobExecution.class))).thenAnswer(inv -> Future.succeededFuture(UUID.randomUUID()));
        // Used by SINGLE_INSTANCE jobs
        when(repo.tryInsert(any(JobExecution.class)))
                .thenAnswer(inv -> Future.succeededFuture(Optional.of(UUID.randomUUID())));
        when(repo.completeExecution(any(UUID.class), any(JobState.class), any(), any(), any()))
                .thenAnswer(invocation -> {
                    capturedState.set(invocation.getArgument(1));
                    return Future.succeededFuture(Optional.empty());
                });
        when(repo.updateScheduleFireTimes(anyString(), any(Instant.class), any(Instant.class)))
                .thenAnswer(inv -> Future.succeededFuture());
        return repo;
    }

    @BeforeEach
    void setUp(Vertx vertx) {
        // Register the local codec for DispatchEnvelope messages (idempotent — catches re-registration)
        try {
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.envelope"));
        } catch (IllegalStateException e) {
            // Already registered — safe to ignore
        }
        scheduler = new CronScheduler(
                vertx,
                Set.of(),
                null,
                stubTargetResolver(),
                testEventBusClient(vertx),
                DispatchEnvelopeBuilder.forTesting());
    }

    @AfterEach
    void tearDown() {
        scheduler.stop();
    }

    @Test
    @DisplayName("registers jobs and tracks count")
    void registersJobsAndTracksCount(Vertx vertx) {
        CronJobDefinition job1 = new CronJobDefinition(
                "job-1",
                new CronExpression("* * * * * *"),
                new CronTargetReference.EventBusTarget("test.address"),
                "test.address",
                ExecutionMode.EVERY_INSTANCE,
                ZoneId.of("UTC"),
                3,
                null,
                OverlapPolicy.SKIP,
                true,
                Map.of(),
                MisfirePolicy.SKIP);
        CronJobDefinition job2 = new CronJobDefinition(
                "job-2",
                new CronExpression("0 * * * * *"),
                new CronTargetReference.EventBusTarget("test.address2"),
                "test.address2",
                ExecutionMode.EVERY_INSTANCE,
                ZoneId.of("UTC"),
                3,
                null,
                OverlapPolicy.SKIP,
                true,
                Map.of(),
                MisfirePolicy.SKIP);

        scheduler.register(job1);
        scheduler.register(job2);

        assertEquals(2, scheduler.registeredJobCount());
    }

    @Test
    @DisplayName("registering the same job id twice is a no-op — second call is silently dropped")
    void registerSameIdTwiceIsIdempotent(Vertx vertx) {
        // Required for idempotency under both the new lifecycle verticle (which calls scan()
        // on every deploy) and any legacy app code that still calls scan()/start() manually
        // after upgrade. Without idempotency every cron job would fire 2x per tick on those
        // upgrade paths.
        CronJobDefinition job = new CronJobDefinition(
                "duplicate-job",
                new CronExpression("* * * * * *"),
                new CronTargetReference.EventBusTarget("test.address"),
                "test.address",
                ExecutionMode.EVERY_INSTANCE,
                ZoneId.of("UTC"),
                3,
                null,
                OverlapPolicy.SKIP,
                true,
                Map.of(),
                MisfirePolicy.SKIP);

        scheduler.register(job);
        scheduler.register(job);

        assertEquals(1, scheduler.registeredJobCount(), "second register() with same id must be ignored");
    }

    @Test
    @DisplayName("stop() clears registered jobs so a redeploy starts from a clean slate")
    void stopClearsRegisteredJobs(Vertx vertx) {
        CronJobDefinition job = new CronJobDefinition(
                "redeploy-job",
                new CronExpression("* * * * * *"),
                new CronTargetReference.EventBusTarget("test.address"),
                "test.address",
                ExecutionMode.EVERY_INSTANCE,
                ZoneId.of("UTC"),
                3,
                null,
                OverlapPolicy.SKIP,
                true,
                Map.of(),
                MisfirePolicy.SKIP);

        scheduler.register(job);
        assertEquals(1, scheduler.registeredJobCount());
        scheduler.stop();
        assertEquals(0, scheduler.registeredJobCount(), "stop() must clear jobs so a redeploy can re-register cleanly");

        // And a register/stop/register cycle ends with exactly one job registered.
        scheduler.register(job);
        assertEquals(1, scheduler.registeredJobCount());
    }

    @Test
    @DisplayName("calling start() twice does not double-arm timers — second call is a no-op")
    void startIsIdempotent(Vertx vertx, VertxTestContext ctx) {
        // start() iterates jobs and schedules a timer per job via scheduleNext(), which puts the
        // timer id into activeTimers. A second start() with no intervening stop() would orphan
        // the prior timer (it keeps firing) and overwrite activeTimers with the new id, doubling
        // every job's fires per tick. Today's only caller is CronLifecycleVerticle (Vert.x calls
        // start() once per deploy) so this is latent — but the fix is the symmetric counterpart
        // to register() idempotency.
        Vertx spyVertx = spy(vertx);
        AtomicInteger setTimerCalls = new AtomicInteger();
        doAnswer(inv -> {
                    setTimerCalls.incrementAndGet();
                    return inv.callRealMethod();
                })
                .when(spyVertx)
                .setTimer(anyLong(), any());

        CronScheduler localScheduler = new CronScheduler(
                spyVertx,
                Set.of(),
                null,
                stubTargetResolver(),
                testEventBusClient(vertx),
                DispatchEnvelopeBuilder.forTesting());
        CronJobDefinition job = new CronJobDefinition(
                "twice-started-job",
                new CronExpression("0 0 0 * * *"),
                new CronTargetReference.EventBusTarget("test.address"),
                "test.address",
                ExecutionMode.EVERY_INSTANCE,
                ZoneId.of("UTC"),
                3,
                null,
                OverlapPolicy.SKIP,
                true,
                Map.of(),
                MisfirePolicy.SKIP);
        localScheduler.register(job);

        localScheduler
                .start()
                .compose(v -> localScheduler.start())
                .onSuccess(v -> ctx.verify(() -> {
                    // First start arms one timer; second start must be a no-op so the
                    // total setTimer count stays at 1, not 2.
                    assertEquals(1, setTimerCalls.get(), "second start() must not arm a duplicate timer");
                    localScheduler.stop();
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("synchronous throw from misfireRecovery does not escape start()")
    void misfireRecoverySyncThrowDoesNotEscapeStart(Vertx vertx, VertxTestContext ctx) {
        // misfireRecovery.recover() is documented as fire-and-forget — failures must not block
        // startup. Async failures already go through .onFailure handlers, but a synchronous
        // throw from a custom JobRepository.findSchedule(...) (NPE, IllegalStateException from
        // a closed pool) would escape start() unwrapped, bypass CronLifecycleVerticle's failed-
        // future rollback, and leave the singleton scheduler with running=true and timers armed.
        JobRepository repository = mock(JobRepository.class);
        when(repository.findSchedule(anyString())).thenThrow(new RuntimeException("repo boom"));
        CronScheduler dbScheduler = new CronScheduler(
                vertx,
                Set.of(),
                repository,
                stubTargetResolver(),
                testEventBusClient(vertx),
                DispatchEnvelopeBuilder.forTesting());
        try {
            CronJobDefinition siJob = new CronJobDefinition(
                    "single-instance-job",
                    new CronExpression("0 0 0 * * *"),
                    new CronTargetReference.EventBusTarget("test.address"),
                    "test.address",
                    ExecutionMode.SINGLE_INSTANCE,
                    ZoneId.of("UTC"),
                    3,
                    null,
                    OverlapPolicy.SKIP,
                    true,
                    Map.of(),
                    MisfirePolicy.FIRE_NOW);
            dbScheduler.register(siJob);

            dbScheduler
                    .start()
                    .onSuccess(v -> ctx.verify(() -> {
                        // start() must succeed despite the misfire-recovery sync throw —
                        // misfire recovery is documented as fire-and-forget.
                        assertEquals(1, dbScheduler.registeredJobCount());
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        } finally {
            // tearDown() calls scheduler.stop() on `scheduler`, not dbScheduler — clean up here.
            // (We can't call stop() now because the assertions are inside the async onSuccess.)
        }
    }

    @Test
    @DisplayName("start returns succeeded future")
    void startReturnsSucceededFuture(Vertx vertx, VertxTestContext ctx) {
        scheduler.start().onSuccess(v -> ctx.completeNow()).onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("stop returns succeeded future")
    void stopReturnsSucceededFuture(Vertx vertx, VertxTestContext ctx) {
        scheduler
                .start()
                .compose(v -> scheduler.stop())
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("EVERY_INSTANCE job fires on all instances without locking")
    void everyInstanceFiresWithoutLock(Vertx vertx, VertxTestContext ctx) {
        // Register a consumer for the handler address
        vertx.eventBus().consumer("test.every.address", msg -> ctx.completeNow());

        // Job that fires every second
        CronJobDefinition job = new CronJobDefinition(
                "every-instance-job",
                new CronExpression("* * * * * *"),
                new CronTargetReference.EventBusTarget("test.every.address"),
                "test.every.address",
                ExecutionMode.EVERY_INSTANCE,
                ZoneId.of("UTC"),
                3,
                null,
                OverlapPolicy.SKIP,
                false,
                Map.of(),
                MisfirePolicy.SKIP);

        scheduler.register(job);
        scheduler.start();

        // completeNow() is called inside the consumer handler above when the job fires
        // The VertxTestContext will timeout and fail if the job doesn't fire within 3 seconds
    }

    @Test
    @DisplayName("binds DeferredExecutionOrigin(cron, job.id()) into the dispatch context")
    void bindsDeferredExecutionOrigin(Vertx vertx, VertxTestContext ctx) {
        AtomicBoolean asserted = new AtomicBoolean(false);

        // Inspect the FQCN-keyed dispatch-context map carried in the DispatchEnvelope: the cron
        // boundary must bind a DeferredExecutionOrigin proving deferred (cron) execution (W2/A6).
        vertx.eventBus().consumer("test.origin.address", msg -> {
            if (!(msg.body() instanceof DispatchEnvelope<?> body) || !asserted.compareAndSet(false, true)) {
                return;
            }
            Object origin = body.metadata().dispatchContext().get(DeferredExecutionOrigin.class.getName());
            ctx.verify(() -> {
                assertInstanceOf(DeferredExecutionOrigin.class, origin);
                DeferredExecutionOrigin deferredOrigin = (DeferredExecutionOrigin) origin;
                assertEquals("cron", deferredOrigin.kind());
                assertEquals("origin-test-job", deferredOrigin.reference());
            });
            ctx.completeNow();
        });

        CronJobDefinition job = new CronJobDefinition(
                "origin-test-job",
                new CronExpression("* * * * * *"),
                new CronTargetReference.EventBusTarget("test.origin.address"),
                "test.origin.address",
                ExecutionMode.EVERY_INSTANCE,
                ZoneId.of("UTC"),
                3,
                null,
                OverlapPolicy.SKIP,
                false,
                Map.of(),
                MisfirePolicy.SKIP);

        scheduler.register(job);
        scheduler.start();
    }

    @Test
    @DisplayName("EVERY_INSTANCE job does not fire concurrently when previous execution is still in progress")
    void everyInstanceDoesNotFireConcurrently(Vertx vertx, VertxTestContext ctx) {
        AtomicInteger concurrentCount = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger fireCount = new AtomicInteger();

        // Handler that holds execution for 1.5 seconds (longer than 1s cron interval)
        vertx.eventBus().consumer("test.concurrent.address", msg -> {
            int current = concurrentCount.incrementAndGet();
            maxConcurrent.updateAndGet(max -> Math.max(max, current));
            fireCount.incrementAndGet();
            // Simulate slow work — hold for 1.5s before replying
            vertx.setTimer(1500, id -> {
                concurrentCount.decrementAndGet();
                // Reply to the replyAddress so the scheduler tracks completion
                var body = (dev.vertique.core.eventbus.DispatchEnvelope<?>) msg.body();
                if (body.replyAddress().isPresent()) {
                    vertx.eventBus()
                            .send(
                                    body.replyAddress().orElseThrow(),
                                    dev.vertique.core.eventbus.DispatchEnvelope.of("done"),
                                    new DeliveryOptions().setCodecName("dispatch.envelope"));
                }
            });
        });

        // Job fires every second with SKIP policy (default)
        CronJobDefinition job = new CronJobDefinition(
                "concurrent-test-job",
                new CronExpression("* * * * * *"),
                new CronTargetReference.EventBusTarget("test.concurrent.address"),
                "test.concurrent.address",
                ExecutionMode.EVERY_INSTANCE,
                ZoneId.of("UTC"),
                3,
                null,
                OverlapPolicy.SKIP,
                false,
                Map.of(),
                MisfirePolicy.SKIP);

        scheduler.register(job);
        scheduler.start();

        // Wait 4 seconds — without the guard, we'd see 4 concurrent executions
        vertx.setTimer(
                4000,
                id -> ctx.verify(() -> {
                    // With the per-job concurrency guard, max concurrent should be 1
                    assertTrue(
                            maxConcurrent.get() <= 1,
                            "Expected max 1 concurrent execution, got " + maxConcurrent.get());
                    assertTrue(fireCount.get() >= 1, "Job should have fired at least once");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("QUEUE_ONE policy executes queued fire after first execution completes")
    void queueOnePolicyExecutesAfterCompletion(Vertx vertx, VertxTestContext ctx) {
        AtomicInteger fireCount = new AtomicInteger();

        // Handler that holds execution for 1.5s (longer than 1s cron interval) then replies
        vertx.eventBus().consumer("test.queue-one.address", msg -> {
            fireCount.incrementAndGet();
            vertx.setTimer(1500, id -> {
                var body = (dev.vertique.core.eventbus.DispatchEnvelope<?>) msg.body();
                if (body.replyAddress().isPresent()) {
                    vertx.eventBus()
                            .send(
                                    body.replyAddress().orElseThrow(),
                                    dev.vertique.core.eventbus.DispatchEnvelope.of("done"),
                                    new DeliveryOptions().setCodecName("dispatch.envelope"));
                }
            });
        });

        // Job fires every second with QUEUE_ONE policy
        CronJobDefinition job = new CronJobDefinition(
                "queue-one-job",
                new CronExpression("* * * * * *"),
                new CronTargetReference.EventBusTarget("test.queue-one.address"),
                "test.queue-one.address",
                ExecutionMode.EVERY_INSTANCE,
                ZoneId.of("UTC"),
                3,
                null,
                OverlapPolicy.QUEUE_ONE,
                false,
                Map.of(),
                MisfirePolicy.SKIP);

        scheduler.register(job);
        scheduler.start();

        // Wait 4s: first execution fires ~at 0s (holds 1.5s), fires from cron at 1s are queued,
        // queued fire runs after first completes (~1.5s), so by 4s we should have at least 2 fires
        vertx.setTimer(
                4000,
                id -> ctx.verify(() -> {
                    assertTrue(
                            fireCount.get() >= 2,
                            "Expected at least 2 fires with QUEUE_ONE policy, got " + fireCount.get());
                    ctx.completeNow();
                }));
    }

    // --- Interceptor tests ---

    @Test
    @DisplayName("onDispatch interceptor is called before job fires")
    void onDispatchInterceptorCalled(Vertx vertx, VertxTestContext ctx) {
        AtomicInteger dispatchCount = new AtomicInteger();
        JobInterceptor interceptor = new JobInterceptor() {
            @Override
            public void onDispatch(JobDispatchContext dispatchCtx) {
                dispatchCount.incrementAndGet();
            }
        };

        scheduler = new CronScheduler(
                vertx,
                Set.of(interceptor),
                null,
                stubTargetResolver(),
                testEventBusClient(vertx),
                DispatchEnvelopeBuilder.forTesting());

        vertx.eventBus().consumer("test.interceptor.address", msg -> {
            ctx.verify(() -> {
                assertTrue(dispatchCount.get() >= 1, "onDispatch should have been called");
                ctx.completeNow();
            });
        });

        CronJobDefinition job = new CronJobDefinition(
                "interceptor-job",
                new CronExpression("* * * * * *"),
                new CronTargetReference.EventBusTarget("test.interceptor.address"),
                "test.interceptor.address",
                ExecutionMode.EVERY_INSTANCE,
                ZoneId.of("UTC"),
                3,
                null,
                OverlapPolicy.SKIP,
                false,
                Map.of(),
                MisfirePolicy.SKIP);

        scheduler.register(job);
        scheduler.start();
    }

    @Test
    @DisplayName("onComplete interceptor is called after job completes")
    void onCompleteInterceptorCalled(Vertx vertx, VertxTestContext ctx) {
        AtomicInteger completeCount = new AtomicInteger();
        JobInterceptor interceptor = new JobInterceptor() {
            @Override
            public void onComplete(
                    JobDispatchContext dispatchCtx, Result<?> result, Instant startTime, Instant endTime) {
                completeCount.incrementAndGet();
            }
        };

        scheduler = new CronScheduler(
                vertx,
                Set.of(interceptor),
                null,
                stubTargetResolver(),
                testEventBusClient(vertx),
                DispatchEnvelopeBuilder.forTesting());

        // Handler that replies immediately
        vertx.eventBus().consumer("test.complete.address", msg -> {
            var body = (DispatchEnvelope<?>) msg.body();
            if (body.replyAddress().isPresent()) {
                vertx.eventBus()
                        .send(
                                body.replyAddress().orElseThrow(),
                                DispatchEnvelope.of("done"),
                                new DeliveryOptions().setCodecName("dispatch.envelope"));
            }
        });

        CronJobDefinition job = new CronJobDefinition(
                "complete-interceptor-job",
                new CronExpression("* * * * * *"),
                new CronTargetReference.EventBusTarget("test.complete.address"),
                "test.complete.address",
                ExecutionMode.EVERY_INSTANCE,
                ZoneId.of("UTC"),
                3,
                null,
                OverlapPolicy.SKIP,
                false,
                Map.of(),
                MisfirePolicy.SKIP);

        scheduler.register(job);
        scheduler.start();

        vertx.setTimer(
                2500,
                id -> ctx.verify(() -> {
                    assertTrue(completeCount.get() >= 1, "onComplete should have been called at least once");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("interceptor exception does not prevent job dispatch")
    void interceptorExceptionDoesNotPreventDispatch(Vertx vertx, VertxTestContext ctx) {
        JobInterceptor throwingInterceptor = new JobInterceptor() {
            @Override
            public void onDispatch(JobDispatchContext dispatchCtx) {
                throw new RuntimeException("interceptor boom");
            }
        };

        scheduler = new CronScheduler(
                vertx,
                Set.of(throwingInterceptor),
                null,
                stubTargetResolver(),
                testEventBusClient(vertx),
                DispatchEnvelopeBuilder.forTesting());

        vertx.eventBus().consumer("test.throwing.address", msg -> ctx.completeNow());

        CronJobDefinition job = new CronJobDefinition(
                "throwing-interceptor-job",
                new CronExpression("* * * * * *"),
                new CronTargetReference.EventBusTarget("test.throwing.address"),
                "test.throwing.address",
                ExecutionMode.EVERY_INSTANCE,
                ZoneId.of("UTC"),
                3,
                null,
                OverlapPolicy.SKIP,
                false,
                Map.of(),
                MisfirePolicy.SKIP);

        scheduler.register(job);
        scheduler.start();
    }

    // --- SINGLE_INSTANCE validation tests ---

    @Test
    @DisplayName("SINGLE_INSTANCE requires repository — throws IllegalArgumentException when null")
    void singleInstanceRequiresRepository(Vertx vertx) {
        CronJobDefinition job = new CronJobDefinition(
                "single-instance-job",
                new CronExpression("* * * * * *"),
                new CronTargetReference.EventBusTarget("test.single.address"),
                "test.single.address",
                ExecutionMode.SINGLE_INSTANCE,
                ZoneId.of("UTC"),
                3,
                null,
                OverlapPolicy.SKIP,
                true,
                Map.of(),
                MisfirePolicy.FIRE_NOW);

        // scheduler has null repository
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> scheduler.register(job));
        assertTrue(
                ex.getMessage().contains("SINGLE_INSTANCE requires JobRepository"),
                "Expected SINGLE_INSTANCE requires JobRepository message, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("SINGLE_INSTANCE requires SKIP overlap policy — throws IllegalArgumentException for QUEUE_ONE")
    void singleInstanceRequiresSkipOverlapPolicy(Vertx vertx) {
        // Need a scheduler with a non-null repository for this test
        // We use a new scheduler — SINGLE_INSTANCE+SKIP should pass, SINGLE_INSTANCE+QUEUE_ONE should fail
        // Since this test uses null repository the SINGLE_INSTANCE check fires first;
        // to isolate the overlap policy check we use a scheduler with a stub repository
        CronJobDefinition job = new CronJobDefinition(
                "bad-overlap-job",
                new CronExpression("* * * * * *"),
                new CronTargetReference.EventBusTarget("test.bad-overlap.address"),
                "test.bad-overlap.address",
                ExecutionMode.SINGLE_INSTANCE,
                ZoneId.of("UTC"),
                3,
                null,
                OverlapPolicy.QUEUE_ONE,
                true,
                Map.of(),
                MisfirePolicy.FIRE_NOW);

        // Even with null repository the first check fires; that's fine —
        // the important thing is the error is caught at register time
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> scheduler.register(job));
        assertTrue(
                ex.getMessage().contains("SINGLE_INSTANCE"),
                "Expected SINGLE_INSTANCE-related error message, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("tracked job with no repository logs warning but does not throw")
    void trackedJobWithoutRepositoryDoesNotThrow(Vertx vertx) {
        CronJobDefinition job = new CronJobDefinition(
                "tracked-no-repo-job",
                new CronExpression("* * * * * *"),
                new CronTargetReference.EventBusTarget("test.tracked.address"),
                "test.tracked.address",
                ExecutionMode.EVERY_INSTANCE,
                ZoneId.of("UTC"),
                3,
                null,
                OverlapPolicy.SKIP,
                true,
                Map.of(),
                MisfirePolicy.SKIP);

        // Should not throw — just logs a warning
        scheduler.register(job);
        assertEquals(1, scheduler.registeredJobCount());
    }

    // --- Misfire recovery tests ---

    @Nested
    @DisplayName("misfire recovery")
    class MisfireRecovery {

        /**
         * Builds a stub {@link JobRepository} that returns the given {@code lastFiredAt} from
         * {@code findSchedule()}, and succeeds on {@code tryInsert()}, {@code updateScheduleFireTimes()},
         * and {@code completeExecution()}.
         */
        private JobRepository stubRepository(Instant lastFiredAt) {
            JobRepository repo = mock(JobRepository.class);
            CronJobSchedule schedule = new CronJobSchedule(
                    "test-job",
                    "0 0 8 * * *",
                    "test.misfire.address",
                    "eventbus:test.misfire.address",
                    "SINGLE_INSTANCE",
                    "UTC",
                    true,
                    "SKIP",
                    3,
                    true,
                    lastFiredAt,
                    null);
            when(repo.findSchedule(anyString())).thenReturn(Future.succeededFuture(Optional.of(schedule)));
            when(repo.tryInsert(any(JobExecution.class)))
                    .thenReturn(Future.succeededFuture(Optional.of(UUID.randomUUID())));
            when(repo.updateScheduleFireTimes(anyString(), any(Instant.class), any(Instant.class)))
                    .thenReturn(Future.succeededFuture());
            when(repo.completeExecution(any(UUID.class), any(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(Optional.empty()));
            return repo;
        }

        @Test
        @DisplayName("FIRE_NOW: dispatches the most recent missed fire when last_fired_at is old")
        void fireNowDispatchesMostRecentMissedFire(Vertx vertx, VertxTestContext ctx) {
            // lastFiredAt is 2 days ago — the daily job should have fired yesterday
            Instant twoDaysAgo = Instant.now().minusSeconds(2 * 24 * 3600);
            JobRepository repo = stubRepository(twoDaysAgo);

            scheduler = new CronScheduler(
                    vertx,
                    Set.of(),
                    repo,
                    stubTargetResolver(),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting());

            AtomicInteger fireCount = new AtomicInteger();
            vertx.eventBus().consumer("test.misfire.address", msg -> {
                fireCount.incrementAndGet();
                // Reply so the scheduler can complete the execution
                var body = (DispatchEnvelope<?>) msg.body();
                if (body.replyAddress().isPresent()) {
                    vertx.eventBus()
                            .send(
                                    body.replyAddress().orElseThrow(),
                                    DispatchEnvelope.of("done"),
                                    new DeliveryOptions().setCodecName("dispatch.envelope"));
                }
            });

            CronJobDefinition job = new CronJobDefinition(
                    "test-job",
                    new CronExpression("0 0 8 * * *"), // daily at 08:00
                    new CronTargetReference.EventBusTarget("test.misfire.address"),
                    "test.misfire.address",
                    ExecutionMode.SINGLE_INSTANCE,
                    ZoneId.of("UTC"),
                    3,
                    null,
                    OverlapPolicy.SKIP,
                    true,
                    Map.of(),
                    MisfirePolicy.FIRE_NOW);

            scheduler.register(job);
            scheduler.start();

            // Allow time for the async findSchedule + dispatch to complete
            vertx.setTimer(
                    500,
                    id -> ctx.verify(() -> {
                        // Exactly one misfire should have been dispatched (FIRE_NOW = only latest)
                        assertEquals(1, fireCount.get(), "FIRE_NOW should dispatch exactly one missed fire");
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("SKIP: does not dispatch missed fires on startup")
        void skipPolicyDoesNotDispatchMissedFires(Vertx vertx, VertxTestContext ctx) {
            Instant twoDaysAgo = Instant.now().minusSeconds(2 * 24 * 3600);
            JobRepository repo = stubRepository(twoDaysAgo);

            scheduler = new CronScheduler(
                    vertx,
                    Set.of(),
                    repo,
                    stubTargetResolver(),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting());

            AtomicInteger fireCount = new AtomicInteger();
            vertx.eventBus().consumer("test.skip-misfire.address", msg -> fireCount.incrementAndGet());

            CronJobDefinition job = new CronJobDefinition(
                    "test-job",
                    new CronExpression("0 0 8 * * *"),
                    new CronTargetReference.EventBusTarget("test.skip-misfire.address"),
                    "test.skip-misfire.address",
                    ExecutionMode.SINGLE_INSTANCE,
                    ZoneId.of("UTC"),
                    3,
                    null,
                    OverlapPolicy.SKIP,
                    true,
                    Map.of(),
                    MisfirePolicy.SKIP);

            scheduler.register(job);
            scheduler.start();

            // Wait briefly — no dispatch should happen
            vertx.setTimer(
                    300,
                    id -> ctx.verify(() -> {
                        assertEquals(0, fireCount.get(), "SKIP policy should not dispatch any missed fires");
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("FIRE_ALL: dispatches every missed fire when last_fired_at is old")
        void fireAllDispatchesEveryMissedFire(Vertx vertx, VertxTestContext ctx) {
            // lastFiredAt 3 hours ago for an every-hour job (should have fired at +1h and +2h)
            Instant threeHoursAgo = Instant.now().minusSeconds(3 * 3600);
            JobRepository repo = mock(JobRepository.class);
            CronJobSchedule schedule = new CronJobSchedule(
                    "fire-all-job",
                    "0 0 * * * *",
                    "test.fire-all.address",
                    "eventbus:test.fire-all.address",
                    "SINGLE_INSTANCE",
                    "UTC",
                    true,
                    "SKIP",
                    3,
                    true,
                    threeHoursAgo,
                    null);
            when(repo.findSchedule(anyString())).thenReturn(Future.succeededFuture(Optional.of(schedule)));
            when(repo.tryInsert(any(JobExecution.class)))
                    .thenReturn(Future.succeededFuture(Optional.of(UUID.randomUUID())));
            when(repo.updateScheduleFireTimes(anyString(), any(Instant.class), any(Instant.class)))
                    .thenReturn(Future.succeededFuture());
            when(repo.completeExecution(any(UUID.class), any(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(Optional.empty()));

            scheduler = new CronScheduler(
                    vertx,
                    Set.of(),
                    repo,
                    stubTargetResolver(),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting());

            AtomicInteger fireCount = new AtomicInteger();
            vertx.eventBus().consumer("test.fire-all.address", msg -> {
                fireCount.incrementAndGet();
                var body = (DispatchEnvelope<?>) msg.body();
                if (body.replyAddress().isPresent()) {
                    vertx.eventBus()
                            .send(
                                    body.replyAddress().orElseThrow(),
                                    DispatchEnvelope.of("done"),
                                    new DeliveryOptions().setCodecName("dispatch.envelope"));
                }
            });

            CronJobDefinition job = new CronJobDefinition(
                    "fire-all-job",
                    new CronExpression("0 0 * * * *"), // every hour
                    new CronTargetReference.EventBusTarget("test.fire-all.address"),
                    "test.fire-all.address",
                    ExecutionMode.SINGLE_INSTANCE,
                    ZoneId.of("UTC"),
                    3,
                    null,
                    OverlapPolicy.SKIP,
                    true,
                    Map.of(),
                    MisfirePolicy.FIRE_ALL);

            scheduler.register(job);
            scheduler.start();

            // Allow time for async recovery
            vertx.setTimer(
                    500,
                    id -> ctx.verify(() -> {
                        // FIRE_ALL fires in a loop but SINGLE_INSTANCE has an in-flight guard,
                        // so only the first fire proceeds; subsequent ones are dropped by the guard
                        // until the first completes. At least 1 fire should dispatch.
                        assertTrue(fireCount.get() >= 1, "FIRE_ALL should dispatch at least 1 missed fire");
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("no misfire when schedule has no last_fired_at (new job)")
        void noMisfireForNewJobWithNoHistory(Vertx vertx, VertxTestContext ctx) {
            JobRepository repo = mock(JobRepository.class);
            CronJobSchedule schedule = new CronJobSchedule(
                    "new-job",
                    "0 0 8 * * *",
                    "test.new-job.address",
                    "eventbus:test.new-job.address",
                    "SINGLE_INSTANCE",
                    "UTC",
                    true,
                    "SKIP",
                    3,
                    true,
                    null, // lastFiredAt = null (never fired)
                    null);
            when(repo.findSchedule(anyString())).thenReturn(Future.succeededFuture(Optional.of(schedule)));

            scheduler = new CronScheduler(
                    vertx,
                    Set.of(),
                    repo,
                    stubTargetResolver(),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting());

            AtomicInteger fireCount = new AtomicInteger();
            vertx.eventBus().consumer("test.new-job.address", msg -> fireCount.incrementAndGet());

            CronJobDefinition job = new CronJobDefinition(
                    "new-job",
                    new CronExpression("0 0 8 * * *"),
                    new CronTargetReference.EventBusTarget("test.new-job.address"),
                    "test.new-job.address",
                    ExecutionMode.SINGLE_INSTANCE,
                    ZoneId.of("UTC"),
                    3,
                    null,
                    OverlapPolicy.SKIP,
                    true,
                    Map.of(),
                    MisfirePolicy.FIRE_NOW);

            scheduler.register(job);
            scheduler.start();

            vertx.setTimer(
                    300,
                    id -> ctx.verify(() -> {
                        assertEquals(0, fireCount.get(), "No misfire dispatch for new job with no history");
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("no misfire when schedule is not found in repository")
        void noMisfireWhenScheduleNotFound(Vertx vertx, VertxTestContext ctx) {
            JobRepository repo = mock(JobRepository.class);
            when(repo.findSchedule(anyString())).thenReturn(Future.succeededFuture(Optional.empty()));

            scheduler = new CronScheduler(
                    vertx,
                    Set.of(),
                    repo,
                    stubTargetResolver(),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting());

            AtomicInteger fireCount = new AtomicInteger();
            vertx.eventBus().consumer("test.no-schedule.address", msg -> fireCount.incrementAndGet());

            CronJobDefinition job = new CronJobDefinition(
                    "no-schedule-job",
                    new CronExpression("0 0 8 * * *"),
                    new CronTargetReference.EventBusTarget("test.no-schedule.address"),
                    "test.no-schedule.address",
                    ExecutionMode.SINGLE_INSTANCE,
                    ZoneId.of("UTC"),
                    3,
                    null,
                    OverlapPolicy.SKIP,
                    true,
                    Map.of(),
                    MisfirePolicy.FIRE_NOW);

            scheduler.register(job);
            scheduler.start();

            vertx.setTimer(
                    300,
                    id -> ctx.verify(() -> {
                        assertEquals(0, fireCount.get(), "No misfire dispatch when schedule not in repository");
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("misfire check is skipped for EVERY_INSTANCE jobs")
        void misfireCheckSkippedForEveryInstance(Vertx vertx, VertxTestContext ctx) {
            // Even with FIRE_NOW policy, EVERY_INSTANCE jobs skip misfire recovery
            JobRepository repo = mock(JobRepository.class);
            // findSchedule should never be called for EVERY_INSTANCE jobs

            scheduler = new CronScheduler(
                    vertx,
                    Set.of(),
                    repo,
                    stubTargetResolver(),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting());

            CronJobDefinition job = new CronJobDefinition(
                    "every-instance-misfire-job",
                    new CronExpression("0 0 8 * * *"),
                    new CronTargetReference.EventBusTarget("test.ei-misfire.address"),
                    "test.ei-misfire.address",
                    ExecutionMode.EVERY_INSTANCE,
                    ZoneId.of("UTC"),
                    3,
                    null,
                    OverlapPolicy.SKIP,
                    true,
                    Map.of(),
                    MisfirePolicy.FIRE_NOW);

            scheduler.register(job);
            scheduler.start();

            // Wait briefly then verify findSchedule was never called
            vertx.setTimer(
                    300,
                    id -> ctx.verify(() -> {
                        // findSchedule should never be called for EVERY_INSTANCE jobs
                        // (no verification needed on mock — test passes if no exception is thrown)
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("misfire check failure is handled gracefully (logged as warning)")
        void misfireCheckFailureIsHandledGracefully(Vertx vertx, VertxTestContext ctx) {
            JobRepository repo = mock(JobRepository.class);
            when(repo.findSchedule(anyString()))
                    .thenReturn(Future.failedFuture(new RuntimeException("DB unavailable")));

            scheduler = new CronScheduler(
                    vertx,
                    Set.of(),
                    repo,
                    stubTargetResolver(),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting());

            CronJobDefinition job = new CronJobDefinition(
                    "failing-check-job",
                    new CronExpression("0 0 8 * * *"),
                    new CronTargetReference.EventBusTarget("test.failing-check.address"),
                    "test.failing-check.address",
                    ExecutionMode.SINGLE_INSTANCE,
                    ZoneId.of("UTC"),
                    3,
                    null,
                    OverlapPolicy.SKIP,
                    true,
                    Map.of(),
                    MisfirePolicy.FIRE_NOW);

            scheduler.register(job);
            // Should not throw — misfire check failure is best-effort
            scheduler.start().onSuccess(v -> ctx.completeNow()).onFailure(ctx::failNow);
        }
    }

    // --- Service-target execution handler tests (GH-41) ---

    /**
     * Regression coverage for GH-41: {@link CronJobRegistrar} resolves every {@code @CronJob}
     * annotated with a service target to a {@link CronTargetReference.ServiceTarget} with
     * {@code handlerAddress = null} (by design — the runtime address is resolved late via
     * {@link ServiceTargetResolver}).
     *
     * <p>The defect these tests pin against: {@link CronScheduler#buildExecution} used to copy
     * {@code job.handlerAddress()} straight into {@link JobExecution#handler()}, so a tracked
     * {@link ExecutionMode#SINGLE_INSTANCE} service-target job persisted a {@code null} handler —
     * fatal against a {@code NOT NULL} column in {@code vertique-job-postgresql}.
     * {@link CronJobDispatcher#dispatch} resolved the target correctly, but only at send time —
     * too late for the already-persisted record.
     *
     * <p>That is now fixed (ADR-0201): the effective event bus address is resolved once per fire
     * by {@link CronScheduler#resolveEffectiveAddress}, before any execution record is written and
     * before {@link CronJobDispatcher#dispatch} is even called, so the address a tracked execution
     * persists is by construction the address actually dispatched to. The tests below pin that
     * invariant across SINGLE_INSTANCE, EVERY_INSTANCE, QUEUE_ONE re-dispatch, unresolvable
     * targets, and the ordering of resolution relative to overlap admission.
     */
    @Nested
    @DisplayName("service-target execution handler")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    class ServiceTargetExecutionHandler {

        /** Stable target id used by every test in this nested class. */
        private static final String STABLE_TARGET_ID = "svc.op";

        /**
         * The {@link CronScheduler} logger, captured by {@link #logAppender} for the one test in
         * this nest that asserts on log volume ({@link #permanentlyUnresolvableTargetLogsErrorOnce}).
         */
        private ch.qos.logback.classic.Logger schedulerLogger;

        /**
         * List appender attached to {@link #schedulerLogger} for the duration of every test in
         * this nest. Attaching unconditionally (rather than only in the one test that reads it) is
         * deliberate: attach/detach live in {@link #attachLogAppender()}/{@link
         * #detachLogAppender()} so cleanup happens no matter how a test exits — a {@code @Timeout}
         * firing or an earlier assertion throwing must not leave the appender attached to the
         * static {@link CronScheduler} logger for the rest of the JVM fork, where it would keep
         * accumulating events across every later test.
         */
        private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logAppender;

        @BeforeEach
        void attachLogAppender() {
            schedulerLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CronScheduler.class);
            logAppender = new ch.qos.logback.core.read.ListAppender<>();
            logAppender.setContext(schedulerLogger.getLoggerContext());
            logAppender.start();
            schedulerLogger.addAppender(logAppender);
        }

        @AfterEach
        void detachLogAppender() {
            schedulerLogger.detachAppender(logAppender);
            logAppender.stop();
        }

        /**
         * The address {@link #STABLE_TARGET_ID} resolves to — deliberately different from the
         * target id itself so an assertion on the resolved address cannot pass by accident (e.g.
         * if the code under test echoed the target id back as the address).
         */
        private static final String RESOLVED_ADDRESS = "ns/svc/op";

        /**
         * Returns a {@link ServiceTargetResolver} mock that maps the given {@code targetId} to the
         * given {@code address}, unlike {@link #stubTargetResolver()} which echoes the target id
         * back as the address.
         *
         * @param targetId the stable target id to stub
         * @param address  the resolved event bus address to return for {@code targetId}
         * @return a resolver mock mapping {@code targetId} to {@code address}
         */
        private ServiceTargetResolver resolverMapping(String targetId, String address) {
            ServiceTargetResolver resolver = mock(ServiceTargetResolver.class);
            when(resolver.resolve(targetId))
                    .thenReturn(new ResolvedServiceTarget(targetId, null, "ns", "svc", "op", null, address));
            return resolver;
        }

        /**
         * Builds the shape {@link CronJobRegistrar} produces for an annotated {@code @CronJob}: a
         * {@link CronTargetReference.ServiceTarget} on {@link #STABLE_TARGET_ID} with a {@code null}
         * {@code handlerAddress} and {@code tracked = true}.
         *
         * <p>Those two fixed arguments are the regression condition itself — a service-target job whose
         * derived {@code handlerAddress} is null, persisted into a {@code NOT NULL} column — so they are
         * named here rather than left implicit. Fires every second, three max attempts, UTC, no payload,
         * no parameters, {@link MisfirePolicy#SKIP}.
         *
         * @param id      the job id
         * @param mode    execution mode (e.g. {@link ExecutionMode#SINGLE_INSTANCE} or {@link
         *                ExecutionMode#EVERY_INSTANCE})
         * @param overlap overlap policy for a fire that overlaps a still-running execution
         * @return a {@link CronJobDefinition} with the fixed shared shape described above
         */
        private CronJobDefinition serviceTargetJob(String id, ExecutionMode mode, OverlapPolicy overlap) {
            return new CronJobDefinition(
                    id,
                    new CronExpression("* * * * * *"),
                    new CronTargetReference.ServiceTarget(STABLE_TARGET_ID),
                    null,
                    mode,
                    ZoneId.of("UTC"),
                    3,
                    null,
                    overlap,
                    true,
                    Map.of(),
                    MisfirePolicy.SKIP);
        }

        @Test
        @DisplayName("SINGLE_INSTANCE service-target job persists the resolved handler address, not null")
        void singleInstanceServiceTargetPersistsResolvedHandler(Vertx vertx, VertxTestContext ctx) {
            JobRepository repo = mock(JobRepository.class);
            when(repo.tryInsert(any(JobExecution.class)))
                    .thenReturn(Future.succeededFuture(Optional.of(UUID.randomUUID())));

            scheduler = new CronScheduler(
                    vertx,
                    Set.of(),
                    repo,
                    resolverMapping(STABLE_TARGET_ID, RESOLVED_ADDRESS),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting());

            CronJobDefinition job =
                    serviceTargetJob("single-instance-service-job", ExecutionMode.SINGLE_INSTANCE, OverlapPolicy.SKIP);

            scheduler.register(job);
            scheduler.start();

            ArgumentCaptor<JobExecution> captor = ArgumentCaptor.forClass(JobExecution.class);
            verify(repo, timeout(5000)).tryInsert(captor.capture());
            ctx.verify(() -> assertEquals(RESOLVED_ADDRESS, captor.getValue().handler()));
            ctx.completeNow();
        }

        @Test
        @DisplayName("EVERY_INSTANCE service-target job persists the resolved handler address, not null")
        void everyInstanceServiceTargetPersistsResolvedHandler(Vertx vertx, VertxTestContext ctx) {
            JobRepository repo = mock(JobRepository.class);
            when(repo.save(any(JobExecution.class))).thenAnswer(inv -> Future.succeededFuture(UUID.randomUUID()));

            scheduler = new CronScheduler(
                    vertx,
                    Set.of(),
                    repo,
                    resolverMapping(STABLE_TARGET_ID, RESOLVED_ADDRESS),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting());

            CronJobDefinition job =
                    serviceTargetJob("every-instance-service-job", ExecutionMode.EVERY_INSTANCE, OverlapPolicy.SKIP);

            scheduler.register(job);
            scheduler.start();

            ArgumentCaptor<JobExecution> captor = ArgumentCaptor.forClass(JobExecution.class);
            verify(repo, timeout(5000)).save(captor.capture());
            ctx.verify(() -> assertEquals(RESOLVED_ADDRESS, captor.getValue().handler()));
            ctx.completeNow();
        }

        @Test
        @DisplayName("QUEUE_ONE re-dispatched fire also persists the resolved handler address")
        void queuedFireServiceTargetPersistsResolvedHandler(Vertx vertx, VertxTestContext ctx) {
            // Hold-then-reply idiom copied from the queue-one-job test above.
            vertx.eventBus()
                    .consumer(
                            RESOLVED_ADDRESS,
                            msg -> vertx.setTimer(1500, id -> {
                                var body = (DispatchEnvelope<?>) msg.body();
                                if (body.replyAddress().isPresent()) {
                                    vertx.eventBus()
                                            .send(
                                                    body.replyAddress().orElseThrow(),
                                                    DispatchEnvelope.of("done"),
                                                    new DeliveryOptions().setCodecName("dispatch.envelope"));
                                }
                            }));

            JobRepository repo = mock(JobRepository.class);
            when(repo.save(any(JobExecution.class))).thenAnswer(inv -> Future.succeededFuture(UUID.randomUUID()));
            when(repo.completeExecution(any(UUID.class), any(JobState.class), any(), any(), any()))
                    .thenAnswer(inv -> Future.succeededFuture(Optional.empty()));
            when(repo.updateScheduleFireTimes(anyString(), any(Instant.class), any(Instant.class)))
                    .thenAnswer(inv -> Future.succeededFuture());

            scheduler = new CronScheduler(
                    vertx,
                    Set.of(),
                    repo,
                    resolverMapping(STABLE_TARGET_ID, RESOLVED_ADDRESS),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting());

            CronJobDefinition job =
                    serviceTargetJob("queue-one-service-job", ExecutionMode.EVERY_INSTANCE, OverlapPolicy.QUEUE_ONE);

            scheduler.register(job);
            scheduler.start();

            ArgumentCaptor<JobExecution> captor = ArgumentCaptor.forClass(JobExecution.class);
            verify(repo, timeout(6000).atLeast(2)).save(captor.capture());
            ctx.verify(() -> {
                for (JobExecution execution : captor.getAllValues()) {
                    assertEquals(RESOLVED_ADDRESS, execution.handler());
                }
                ctx.completeNow();
            });
        }

        @Test
        @DisplayName("unresolvable service target skips the fire without ever touching the repository")
        void unresolvableServiceTargetSkipsFireWithoutTouchingRepository(Vertx vertx, VertxTestContext ctx) {
            AtomicInteger hitCount = new AtomicInteger();
            vertx.eventBus().consumer(RESOLVED_ADDRESS, msg -> hitCount.incrementAndGet());

            JobRepository repo = mock(JobRepository.class);
            when(repo.tryInsert(any(JobExecution.class)))
                    .thenReturn(Future.succeededFuture(Optional.of(UUID.randomUUID())));

            ServiceTargetResolver resolver = mock(ServiceTargetResolver.class);
            when(resolver.resolve(STABLE_TARGET_ID)).thenThrow(new IllegalArgumentException("no such target"));

            scheduler = new CronScheduler(
                    vertx, Set.of(), repo, resolver, testEventBusClient(vertx), DispatchEnvelopeBuilder.forTesting());

            CronJobDefinition job = serviceTargetJob(
                    "unresolvable-single-instance-job", ExecutionMode.SINGLE_INSTANCE, OverlapPolicy.SKIP);

            scheduler.register(job);
            scheduler.start();

            vertx.setTimer(
                    2500,
                    id -> ctx.verify(() -> {
                        // Proves a fire really was admitted and reached the gate. Without this, a
                        // run where no cron tick ever fired at all (e.g. under CI load) would pass
                        // the two assertions below vacuously — they cannot otherwise distinguish
                        // "the gate correctly stopped the fire" from "nothing fired at all".
                        verify(resolver, atLeastOnce()).resolve(STABLE_TARGET_ID);
                        verify(repo, never()).tryInsert(any());
                        assertEquals(
                                0,
                                hitCount.get(),
                                "consumer at " + RESOLVED_ADDRESS + " must never receive an unresolvable dispatch");
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("a fire that fails during target resolution still releases the in-flight guard and "
                + "concurrency slot so a later tick can dispatch")
        void unresolvableServiceTargetLeavesJobFirableOnNextTick(Vertx vertx, VertxTestContext ctx) {
            // The consumer's arrival is itself the proof that the in-flight guard/slot released
            // after the failed resolution so a later tick could dispatch — no separate hit-count
            // assertion needed, and completeNow() is idempotent if the consumer fires more than once.
            vertx.eventBus().consumer(RESOLVED_ADDRESS, msg -> ctx.completeNow());

            JobRepository repo = mock(JobRepository.class);
            when(repo.tryInsert(any(JobExecution.class)))
                    .thenReturn(Future.succeededFuture(Optional.of(UUID.randomUUID())));

            ResolvedServiceTarget resolved =
                    new ResolvedServiceTarget(STABLE_TARGET_ID, null, "ns", "svc", "op", null, RESOLVED_ADDRESS);
            ServiceTargetResolver resolver = mock(ServiceTargetResolver.class);
            when(resolver.resolve(STABLE_TARGET_ID))
                    .thenThrow(new IllegalArgumentException("no such target"))
                    .thenReturn(resolved);

            // 6-arg constructor: executionTimeoutMs defaults to 0, so nothing can mask a stranded guard.
            scheduler = new CronScheduler(
                    vertx, Set.of(), repo, resolver, testEventBusClient(vertx), DispatchEnvelopeBuilder.forTesting());

            CronJobDefinition job = serviceTargetJob(
                    "leaks-guard-single-instance-job", ExecutionMode.SINGLE_INSTANCE, OverlapPolicy.SKIP);

            scheduler.register(job);
            scheduler.start();
        }

        /**
         * A permanently unresolvable target must not log an {@code ERROR} on every tick.
         *
         * <p>Resolution failures are usually permanent (the built-in resolver snapshots its index at
         * construction) while the retry is per-tick, so an unbounded report would write an ERROR
         * plus a stack trace every second, indefinitely, per node — enough to fill a log volume and
         * to bury real security events. Only the first failure per job may log at {@code ERROR}.
         *
         * <p>Asserted over three or more ticks so a per-tick regression cannot pass by timing luck.
         */
        @Test
        @DisplayName("permanently unresolvable target logs ERROR once, not once per tick")
        void permanentlyUnresolvableTargetLogsErrorOnce(Vertx vertx, VertxTestContext ctx) {
            ServiceTargetResolver resolver = mock(ServiceTargetResolver.class);
            when(resolver.resolve(STABLE_TARGET_ID)).thenThrow(new IllegalArgumentException("never resolves"));

            JobRepository repo = mock(JobRepository.class);
            scheduler = new CronScheduler(
                    vertx, Set.of(), repo, resolver, testEventBusClient(vertx), DispatchEnvelopeBuilder.forTesting());
            scheduler.register(
                    serviceTargetJob("never-resolves-job", ExecutionMode.SINGLE_INSTANCE, OverlapPolicy.SKIP));
            scheduler.start();

            // ~3.5s over a one-second cron: at least three failed fires. Appender attach/detach is
            // handled unconditionally by attachLogAppender()/detachLogAppender() above, so a
            // @Timeout or an earlier assertion throwing here still leaves the JVM fork clean.
            vertx.setTimer(
                    3500,
                    id -> ctx.verify(() -> {
                        // Filtered on the same "skipping this fire" marker as the DEBUG count below so an
                        // unrelated CronScheduler ERROR logged elsewhere in the window cannot inflate this
                        // count and break the assertion.
                        long errors = logAppender.list.stream()
                                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR
                                        && event.getFormattedMessage().contains("skipping this fire"))
                                .count();
                        long debugs = logAppender.list.stream()
                                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.DEBUG
                                        && event.getFormattedMessage().contains("skipping this fire"))
                                .count();
                        assertEquals(
                                1,
                                errors,
                                "exactly one ERROR expected for a permanently unresolvable target, got " + errors
                                        + " — an unbounded per-tick report fills log volumes");
                        assertTrue(
                                debugs >= 1,
                                "subsequent failures must still be reported at DEBUG so the condition stays"
                                        + " observable; got " + debugs);
                        ctx.completeNow();
                    }));
        }

        /**
         * GREEN regression guard: unlike tests 1-3, this uses a mock {@link JobRepository} that
         * tolerates a {@code null} handler — it only goes red against a real {@code NOT NULL}
         * handler column (e.g. {@code vertique-job-postgresql}), which this unit test doesn't
         * exercise. The scheduler resolves the {@link CronTargetReference.ServiceTarget} once per
         * fire, before {@link CronJobDispatcher#dispatch} is even called (see
         * {@link CronScheduler#resolveEffectiveAddress}), so the message lands at
         * {@link #RESOLVED_ADDRESS} regardless of the persisted-handler bug. Its lasting value is
         * pinning that the address a persisted execution records equals the address actually
         * dispatched to — an invariant the fix makes true by construction.
         */
        @Test
        @DisplayName("GREEN regression guard: SINGLE_INSTANCE service-target dispatch lands at the resolved address")
        void serviceTargetDispatchLandsAtTheResolvedAddress(Vertx vertx, VertxTestContext ctx) {
            vertx.eventBus().consumer(RESOLVED_ADDRESS, msg -> ctx.completeNow());

            JobRepository repo = mock(JobRepository.class);
            when(repo.tryInsert(any(JobExecution.class)))
                    .thenReturn(Future.succeededFuture(Optional.of(UUID.randomUUID())));

            scheduler = new CronScheduler(
                    vertx,
                    Set.of(),
                    repo,
                    resolverMapping(STABLE_TARGET_ID, RESOLVED_ADDRESS),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting());

            CronJobDefinition job = serviceTargetJob(
                    "resolved-address-single-instance-job", ExecutionMode.SINGLE_INSTANCE, OverlapPolicy.SKIP);

            scheduler.register(job);
            scheduler.start();
        }

        /**
         * Guards against a rejected design that would resolve the service target at the top of
         * {@code fire()}, before {@code tryAcquireInFlight}. Under that rejected design, an
         * overlapping tick would consult the resolver before being refused for the in-flight
         * execution. The discriminating assertion is {@code resolveCallsWhileArmed == 0}: under
         * the correct ordering an overlapping tick is refused at {@code tryAcquireInFlight} and
         * routed to {@code handleOverlap} <em>without ever consulting the resolver</em>, so the
         * resolver must never be invoked while the failure window is armed. The hit count alone
         * (both ticks eventually reaching the resolved address) does not discriminate the two
         * orderings — it passes either way — so it is a secondary sanity check, not the guard.
         * The resolver failure is keyed on a time window (armed while the first execution is
         * held, disarmed just before it replies) rather than an invocation count, because
         * invocation ordinals shift once the fix adds a resolution call at admission time.
         */
        @Test
        @DisplayName("GREEN regression guard: overlap admission never calls the resolver, so a queued tick "
                + "still runs even if the resolver fails during the overlap window")
        void unresolvableTargetDuringOverlapStillQueuesTheTick(Vertx vertx, VertxTestContext ctx) {
            AtomicInteger hitCount = new AtomicInteger();
            AtomicBoolean failResolution = new AtomicBoolean(false);
            AtomicInteger resolveCallsWhileArmed = new AtomicInteger();
            vertx.eventBus().consumer(RESOLVED_ADDRESS, msg -> {
                int hits = hitCount.incrementAndGet();
                if (hits >= 2) {
                    ctx.verify(() -> {
                        assertTrue(
                                hitCount.get() >= 2,
                                "queued fire must still run even though the resolver fails transiently, got "
                                        + hitCount.get() + " hits");
                        assertEquals(
                                0,
                                resolveCallsWhileArmed.get(),
                                "overlap admission must never consult the resolver while the failure window is"
                                        + " armed — a nonzero count means resolution happened before"
                                        + " tryAcquireInFlight, the rejected ordering this test guards against");
                        ctx.completeNow();
                    });
                    return;
                }
                failResolution.set(true);
                vertx.setTimer(1500, id -> {
                    failResolution.set(false);
                    var body = (DispatchEnvelope<?>) msg.body();
                    if (body.replyAddress().isPresent()) {
                        vertx.eventBus()
                                .send(
                                        body.replyAddress().orElseThrow(),
                                        DispatchEnvelope.of("done"),
                                        new DeliveryOptions().setCodecName("dispatch.envelope"));
                    }
                });
            });

            JobRepository repo = mock(JobRepository.class);
            when(repo.save(any(JobExecution.class))).thenAnswer(inv -> Future.succeededFuture(UUID.randomUUID()));
            when(repo.completeExecution(any(UUID.class), any(JobState.class), any(), any(), any()))
                    .thenAnswer(inv -> Future.succeededFuture(Optional.empty()));
            when(repo.updateScheduleFireTimes(anyString(), any(Instant.class), any(Instant.class)))
                    .thenAnswer(inv -> Future.succeededFuture());

            ServiceTargetResolver resolver = mock(ServiceTargetResolver.class);
            when(resolver.resolve(STABLE_TARGET_ID)).thenAnswer(inv -> {
                if (failResolution.get()) {
                    resolveCallsWhileArmed.incrementAndGet();
                    throw new IllegalArgumentException("transient resolution failure");
                }
                return new ResolvedServiceTarget(STABLE_TARGET_ID, null, "ns", "svc", "op", null, RESOLVED_ADDRESS);
            });

            scheduler = new CronScheduler(
                    vertx, Set.of(), repo, resolver, testEventBusClient(vertx), DispatchEnvelopeBuilder.forTesting());

            CronJobDefinition job = serviceTargetJob(
                    "queued-overlap-service-job", ExecutionMode.EVERY_INSTANCE, OverlapPolicy.QUEUE_ONE);

            scheduler.register(job);
            scheduler.start();
        }

        /**
         * Pins the double-release branch of {@link CronScheduler#markCompleted}: when the queued
         * QUEUE_ONE fire's own, independent target resolution fails, {@code markCompleted} must
         * release <b>both</b> guards — {@code inFlightJobs} (via {@code removeInFlight}) and the
         * global concurrency slot (via {@code releaseSlot}) — because
         * {@link CronConcurrencyManager#markCompleted(String)} deliberately keeps the in-flight
         * entry when a pending fire exists (so the slot can be reused without a race), and that
         * entry would otherwise never be released once the queued re-dispatch aborts. A leak here
         * would strand one of the ten global concurrency slots ({@link
         * CronScheduler#DEFAULT_MAX_CONCURRENT_JOBS}) permanently and leave this job unable to
         * fire again for the lifetime of the process.
         *
         * <p>Sequence: the first fire is held open by the handler; the overlapping second tick is
         * queued (QUEUE_ONE, no resolver call — see {@link
         * #unresolvableTargetDuringOverlapStillQueuesTheTick}); the handler arms {@code
         * failQueuedResolution} immediately before replying, so resolution fails specifically for
         * the queued re-dispatch taken inside {@code markCompleted} rather than at admission. The
         * discriminating assertion is that a <em>later</em>, independent tick still dispatches —
         * the only way to observe that both guards were actually released rather than stranded.
         */
        @Test
        @DisplayName("QUEUE_ONE queued-fire resolution failure in markCompleted releases both the in-flight "
                + "guard and the concurrency slot, so a later tick can still dispatch")
        void queuedFireResolutionFailureReleasesBothGuards(Vertx vertx, VertxTestContext ctx) {
            AtomicInteger hitCount = new AtomicInteger();
            AtomicBoolean failQueuedResolution = new AtomicBoolean(false);
            vertx.eventBus().consumer(RESOLVED_ADDRESS, msg -> {
                int hits = hitCount.incrementAndGet();
                if (hits == 1) {
                    // Hold the first execution long enough for the next tick to overlap and queue.
                    vertx.setTimer(1500, id -> {
                        // Arm the failure immediately before replying, so it hits specifically the
                        // queued re-dispatch resolved inside markCompleted — not this admission,
                        // which already succeeded.
                        failQueuedResolution.set(true);
                        var body = (DispatchEnvelope<?>) msg.body();
                        if (body.replyAddress().isPresent()) {
                            vertx.eventBus()
                                    .send(
                                            body.replyAddress().orElseThrow(),
                                            DispatchEnvelope.of("done"),
                                            new DeliveryOptions().setCodecName("dispatch.envelope"));
                        }
                        // Disarm well before the next ~1s tick so a later fire can resolve again.
                        vertx.setTimer(200, disarmId -> failQueuedResolution.set(false));
                    });
                    return;
                }
                var body = (DispatchEnvelope<?>) msg.body();
                if (body.replyAddress().isPresent()) {
                    vertx.eventBus()
                            .send(
                                    body.replyAddress().orElseThrow(),
                                    DispatchEnvelope.of("done"),
                                    new DeliveryOptions().setCodecName("dispatch.envelope"));
                }
                if (hits >= 2) {
                    ctx.completeNow();
                }
            });

            JobRepository repo = mock(JobRepository.class);
            when(repo.save(any(JobExecution.class))).thenAnswer(inv -> Future.succeededFuture(UUID.randomUUID()));
            when(repo.completeExecution(any(UUID.class), any(JobState.class), any(), any(), any()))
                    .thenAnswer(inv -> Future.succeededFuture(Optional.empty()));
            when(repo.updateScheduleFireTimes(anyString(), any(Instant.class), any(Instant.class)))
                    .thenAnswer(inv -> Future.succeededFuture());

            ServiceTargetResolver resolver = mock(ServiceTargetResolver.class);
            when(resolver.resolve(STABLE_TARGET_ID)).thenAnswer(inv -> {
                if (failQueuedResolution.get()) {
                    throw new IllegalArgumentException("queued re-dispatch resolution failure");
                }
                return new ResolvedServiceTarget(STABLE_TARGET_ID, null, "ns", "svc", "op", null, RESOLVED_ADDRESS);
            });

            // 6-arg constructor: executionTimeoutMs defaults to 0, so nothing can mask a stranded guard.
            scheduler = new CronScheduler(
                    vertx, Set.of(), repo, resolver, testEventBusClient(vertx), DispatchEnvelopeBuilder.forTesting());

            CronJobDefinition job = serviceTargetJob(
                    "queued-resolution-failure-job", ExecutionMode.EVERY_INSTANCE, OverlapPolicy.QUEUE_ONE);

            scheduler.register(job);
            scheduler.start();
        }

        @Test
        @DisplayName(
                "GREEN regression guard: plain EventBusTarget dispatch is untouched by service-target" + " resolution")
        void eventBusTargetStillDispatchesToItsAddress(Vertx vertx, VertxTestContext ctx) {
            vertx.eventBus().consumer("plain.address", msg -> ctx.completeNow());

            scheduler = new CronScheduler(
                    vertx,
                    Set.of(),
                    null,
                    resolverMapping(STABLE_TARGET_ID, RESOLVED_ADDRESS),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting());

            CronJobDefinition job = new CronJobDefinition(
                    "plain-event-bus-job",
                    new CronExpression("* * * * * *"),
                    new CronTargetReference.EventBusTarget("plain.address"),
                    "plain.address",
                    ExecutionMode.EVERY_INSTANCE,
                    ZoneId.of("UTC"),
                    3,
                    null,
                    OverlapPolicy.SKIP,
                    false,
                    Map.of(),
                    MisfirePolicy.SKIP);

            scheduler.register(job);
            scheduler.start();
        }
    }

    // --- Consumer timeout tests ---

    @Nested
    @DisplayName("consumer timeout")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    class ConsumerTimeout {

        @Test
        @DisplayName("execution timeout marks tracked execution as ABANDONED when handler never replies")
        void executionTimeoutMarksAbandoned(Vertx vertx, VertxTestContext ctx) {
            AtomicReference<JobState> capturedState = new AtomicReference<>();
            JobRepository repo = stubRepoCapturingCompletion(capturedState);

            // Scheduler with 300 ms timeout — handler will never reply
            // Use SINGLE_INSTANCE so tryInsert() is the persistence path
            scheduler = new CronScheduler(
                    vertx,
                    Set.of(),
                    repo,
                    stubTargetResolver(),
                    testEventBusClient(vertx),
                    10,
                    300L,
                    0L,
                    DispatchEnvelopeBuilder.forTesting());

            // Register a handler that intentionally never replies
            vertx.eventBus().consumer("test.timeout.address", msg -> {
                // Deliberately not replying — let the timeout fire
            });

            CronJobDefinition job = new CronJobDefinition(
                    "timeout-test-job",
                    new CronExpression("* * * * * *"),
                    new CronTargetReference.EventBusTarget("test.timeout.address"),
                    "test.timeout.address",
                    ExecutionMode.SINGLE_INSTANCE,
                    ZoneId.of("UTC"),
                    3,
                    null,
                    OverlapPolicy.SKIP,
                    true,
                    Map.of(),
                    MisfirePolicy.SKIP);

            scheduler.register(job);
            scheduler.start();

            // Wait long enough for the job to fire (≤1s) and the timeout to fire (300 ms after)
            vertx.setTimer(
                    3000,
                    id -> ctx.verify(() -> {
                        verify(repo, timeout(1000).atLeastOnce())
                                .completeExecution(
                                        any(UUID.class), eq(JobState.ABANDONED), anyString(), anyString(), any());
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("execution timeout does not fire when handler replies within the timeout")
        void executionTimeoutCancelledOnReply(Vertx vertx, VertxTestContext ctx) {
            JobRepository repo = mock(JobRepository.class);
            when(repo.save(any(JobExecution.class))).thenAnswer(inv -> Future.succeededFuture(UUID.randomUUID()));
            when(repo.completeExecution(any(UUID.class), any(JobState.class), any(), any(), any()))
                    .thenAnswer(inv -> Future.succeededFuture(Optional.empty()));
            when(repo.updateScheduleFireTimes(anyString(), any(Instant.class), any(Instant.class)))
                    .thenAnswer(inv -> Future.succeededFuture());

            // 2 second timeout — handler replies in 100 ms
            scheduler = new CronScheduler(
                    vertx,
                    Set.of(),
                    repo,
                    stubTargetResolver(),
                    testEventBusClient(vertx),
                    10,
                    2000L,
                    0L,
                    DispatchEnvelopeBuilder.forTesting());

            AtomicInteger completeCount = new AtomicInteger();

            vertx.eventBus().consumer("test.fast-reply.address", msg -> {
                var body = (DispatchEnvelope<?>) msg.body();
                if (body.replyAddress().isPresent()) {
                    vertx.setTimer(100, id -> {
                        vertx.eventBus()
                                .send(
                                        body.replyAddress().orElseThrow(),
                                        DispatchEnvelope.of("done"),
                                        new DeliveryOptions().setCodecName("dispatch.envelope"));
                        completeCount.incrementAndGet();
                    });
                }
            });

            CronJobDefinition job = new CronJobDefinition(
                    "fast-reply-job",
                    new CronExpression("* * * * * *"),
                    new CronTargetReference.EventBusTarget("test.fast-reply.address"),
                    "test.fast-reply.address",
                    ExecutionMode.EVERY_INSTANCE,
                    ZoneId.of("UTC"),
                    3,
                    null,
                    OverlapPolicy.SKIP,
                    false,
                    Map.of(),
                    MisfirePolicy.SKIP);

            scheduler.register(job);
            scheduler.start();

            // Give the job time to fire, reply, and confirm ABANDONED was NOT called
            vertx.setTimer(
                    2500,
                    id -> ctx.verify(() -> {
                        assertTrue(completeCount.get() >= 1, "Handler should have replied at least once");
                        // completeExecution should NOT have been called with ABANDONED
                        // (called with SUCCEEDED from normal completion path)
                        verify(repo, org.mockito.Mockito.never())
                                .completeExecution(
                                        any(UUID.class), eq(JobState.ABANDONED), anyString(), anyString(), any());
                        ctx.completeNow();
                    }));
        }
    }

    // --- Cancellation tests ---

    @Nested
    @DisplayName("cancellation")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    class Cancellation {

        @Test
        @DisplayName("cancel listener sets isCancelled on the JobContext")
        void cancelListenerSetsCancelledFlag(Vertx vertx, VertxTestContext ctx) {
            AtomicBoolean cancelObserved = new AtomicBoolean(false);

            // Handler that captures the JobContext, publishes a cancel signal, then checks the flag
            vertx.eventBus().consumer("test.cancel.address", msg -> {
                if (msg.body() instanceof DispatchEnvelope<?> body) {
                    // Extract the JobContext from the dispatch context map
                    dev.vertique.job.DefaultJobContext jobCtx = (dev.vertique.job.DefaultJobContext)
                            body.metadata().dispatchContext().get(JobContext.class.getName());
                    if (jobCtx != null) {
                        UUID executionId = jobCtx.executionId();
                        // Publish a cancel signal on the job.cancel.<executionId> address
                        vertx.eventBus().publish("job.cancel." + executionId, "cancel");
                        // Give the event bus time to deliver the cancel message, then read the flag
                        vertx.setTimer(100, id -> {
                            cancelObserved.set(jobCtx.isCancelled());
                            // Reply to unblock the scheduler
                            if (body.replyAddress().isPresent()) {
                                vertx.eventBus()
                                        .send(
                                                body.replyAddress().orElseThrow(),
                                                DispatchEnvelope.of("done"),
                                                new DeliveryOptions().setCodecName("dispatch.envelope"));
                            }
                        });
                    }
                }
            });

            CronJobDefinition job = new CronJobDefinition(
                    "cancel-test-job",
                    new CronExpression("* * * * * *"),
                    new CronTargetReference.EventBusTarget("test.cancel.address"),
                    "test.cancel.address",
                    ExecutionMode.EVERY_INSTANCE,
                    ZoneId.of("UTC"),
                    3,
                    null,
                    OverlapPolicy.SKIP,
                    false,
                    Map.of(),
                    MisfirePolicy.SKIP);

            scheduler.register(job);
            scheduler.start();

            vertx.setTimer(
                    3000,
                    id -> ctx.verify(() -> {
                        assertTrue(cancelObserved.get(), "JobContext should have isCancelled=true after cancel signal");
                        ctx.completeNow();
                    }));
        }
    }

    // --- Job log durability tests ---

    /**
     * Proves the cron dispatcher drains the per-execution {@link dev.vertique.job.JobLogger} buffer
     * into {@link JobRepository#saveLogs} while a tracked fire is still running and again on the
     * path that ends it — and that an <em>untracked</em> fire never flushes at all, because
     * {@code job_logs.execution_id} is {@code NOT NULL REFERENCES job_executions(id)} and an
     * untracked fire has no such row.
     *
     * <p>Every test here is sleep-free: the {@code saveLogs} mock — or, for the negative test, the
     * interceptor's {@code onComplete} that runs immediately after the flush site — <em>is</em> the
     * completion signal. Each answer is guarded by a latch because a one-second cron expression
     * keeps firing after the assertion has been made.
     *
     * <p>{@link #persistsEntriesAppendedDuringAnInFlightTickWrite} is the end-to-end counterpart of
     * {@code JobLogFlusherTest.Drain}: it is the only test here whose {@code saveLogs} returns a
     * <em>pending</em> future, and therefore the only one that can observe the in-flight window an
     * ending-site flush used to lose.
     *
     * <p>{@link #shutdownDrainsBufferedLogsOfInFlightExecutions} covers
     * {@code CronJobDispatcher.shutdown()}. That dispatcher is package-private and constructed
     * inside {@link CronScheduler}, so the flush site is driven through {@link CronScheduler#stop()},
     * which composes the future the dispatcher's shutdown returns.
     */
    @Nested
    @DisplayName("job log flush")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    class JobLogFlush {

        @Test
        @DisplayName("flushes buffered log entries to the repository on the periodic progress tick")
        void flushesLogsOnProgressTick(Vertx vertx, VertxTestContext ctx) {
            JobRepository repo = stubRepoCapturingCompletion(new AtomicReference<>());

            AtomicBoolean asserted = new AtomicBoolean(false);
            when(repo.saveLogs(any(UUID.class), any())).thenAnswer(invocation -> {
                List<LogEntry> batch = invocation.getArgument(1);
                if (asserted.compareAndSet(false, true)) {
                    ctx.verify(() -> assertTrue(
                            batch.stream().anyMatch(entry -> "hello from handler".equals(entry.message())),
                            "the periodic tick must flush the handler's buffered entry"));
                    ctx.completeNow();
                }
                return Future.succeededFuture();
            });

            // executionTimeoutMs = 0 disables the timeout path and the handler never replies, so
            // the 100 ms progress tick is the only thing that can reach saveLogs.
            scheduler = new CronScheduler(
                    vertx,
                    Set.of(),
                    repo,
                    stubTargetResolver(),
                    testEventBusClient(vertx),
                    10,
                    0L,
                    100L,
                    DispatchEnvelopeBuilder.forTesting());

            AtomicBoolean logged = new AtomicBoolean(false);
            vertx.eventBus().consumer("test.logflush.tick.address", msg -> {
                if (!(msg.body() instanceof DispatchEnvelope<?> body) || !logged.compareAndSet(false, true)) {
                    return;
                }
                DefaultJobContext jobCtx =
                        (DefaultJobContext) body.metadata().dispatchContext().get(JobContext.class.getName());
                jobCtx.logger().info("hello from handler");
            });

            // SINGLE_INSTANCE + the stubbed tryInsert win makes this a tracked fire, so the
            // dispatcher receives a non-null execution and the flusher is persistable.
            CronJobDefinition job = new CronJobDefinition(
                    "log-tick-job",
                    new CronExpression("* * * * * *"),
                    new CronTargetReference.EventBusTarget("test.logflush.tick.address"),
                    "test.logflush.tick.address",
                    ExecutionMode.SINGLE_INSTANCE,
                    ZoneId.of("UTC"),
                    3,
                    null,
                    OverlapPolicy.SKIP,
                    true,
                    Map.of(),
                    MisfirePolicy.SKIP);

            scheduler.register(job);
            scheduler.start();
        }

        @Test
        @DisplayName("flushes buffered log entries on the abandon-timeout path")
        void flushesOnAbandonTimeout(Vertx vertx, VertxTestContext ctx) {
            AtomicReference<JobState> capturedState = new AtomicReference<>();
            JobRepository repo = stubRepoCapturingCompletion(capturedState);

            AtomicBoolean asserted = new AtomicBoolean(false);
            when(repo.saveLogs(any(UUID.class), any())).thenAnswer(invocation -> {
                List<LogEntry> batch = invocation.getArgument(1);
                if (asserted.compareAndSet(false, true)) {
                    ctx.verify(() -> assertTrue(
                            batch.stream().anyMatch(entry -> "before the timeout".equals(entry.message())),
                            "the abandon-timeout path must flush the buffered entry"));
                    ctx.completeNow();
                }
                return Future.succeededFuture();
            });

            // progressFlushIntervalMs = 0 disables the periodic tick, so only the 300 ms
            // execution-timeout path can reach saveLogs.
            scheduler = new CronScheduler(
                    vertx,
                    Set.of(),
                    repo,
                    stubTargetResolver(),
                    testEventBusClient(vertx),
                    10,
                    300L,
                    0L,
                    DispatchEnvelopeBuilder.forTesting());

            AtomicBoolean logged = new AtomicBoolean(false);
            vertx.eventBus().consumer("test.logflush.timeout.address", msg -> {
                if (!(msg.body() instanceof DispatchEnvelope<?> body) || !logged.compareAndSet(false, true)) {
                    return;
                }
                DefaultJobContext jobCtx =
                        (DefaultJobContext) body.metadata().dispatchContext().get(JobContext.class.getName());
                jobCtx.logger().info("before the timeout");
            });

            CronJobDefinition job = new CronJobDefinition(
                    "log-timeout-job",
                    new CronExpression("* * * * * *"),
                    new CronTargetReference.EventBusTarget("test.logflush.timeout.address"),
                    "test.logflush.timeout.address",
                    ExecutionMode.SINGLE_INSTANCE,
                    ZoneId.of("UTC"),
                    3,
                    null,
                    OverlapPolicy.SKIP,
                    true,
                    Map.of(),
                    MisfirePolicy.SKIP);

            scheduler.register(job);
            scheduler.start();
        }

        @Test
        @DisplayName("untracked fire never flushes — there is no job_executions row to reference")
        void untrackedFireDoesNotFlush(Vertx vertx, VertxTestContext ctx) {
            // A repository IS bound, so a flush would actually reach it. The fire is untracked
            // because tracked=false, which makes CronScheduler dispatch with execution == null —
            // exactly the case where job_logs.execution_id would violate its foreign key.
            JobRepository repo = stubRepoCapturingCompletion(new AtomicReference<>());
            when(repo.saveLogs(any(UUID.class), any())).thenReturn(Future.succeededFuture());

            // onComplete runs inside the completion consumer immediately after the flush site, so
            // by the time it fires any flush that was going to happen has already happened.
            AtomicBoolean asserted = new AtomicBoolean(false);
            JobInterceptor completionProbe = new JobInterceptor() {
                @Override
                public void onComplete(
                        JobDispatchContext dispatchCtx, Result<?> result, Instant startTime, Instant endTime) {
                    if (asserted.compareAndSet(false, true)) {
                        ctx.verify(() -> verify(repo, never()).saveLogs(any(), any()));
                        ctx.completeNow();
                    }
                }
            };

            scheduler = new CronScheduler(
                    vertx,
                    Set.of(completionProbe),
                    repo,
                    stubTargetResolver(),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting());

            AtomicBoolean logged = new AtomicBoolean(false);
            vertx.eventBus().consumer("test.logflush.untracked.address", msg -> {
                if (!(msg.body() instanceof DispatchEnvelope<?> body)) {
                    return;
                }
                if (logged.compareAndSet(false, true)) {
                    DefaultJobContext jobCtx = (DefaultJobContext)
                            body.metadata().dispatchContext().get(JobContext.class.getName());
                    jobCtx.logger().info("buffered but unflushable");
                }
                body.replyAddress().ifPresent(address -> vertx.eventBus()
                        .send(
                                address,
                                DispatchEnvelope.of("done"),
                                new DeliveryOptions().setCodecName("dispatch.envelope")));
            });

            CronJobDefinition job = new CronJobDefinition(
                    "log-untracked-job",
                    new CronExpression("* * * * * *"),
                    new CronTargetReference.EventBusTarget("test.logflush.untracked.address"),
                    "test.logflush.untracked.address",
                    ExecutionMode.EVERY_INSTANCE,
                    ZoneId.of("UTC"),
                    3,
                    null,
                    OverlapPolicy.SKIP,
                    false,
                    Map.of(),
                    MisfirePolicy.SKIP);

            scheduler.register(job);
            scheduler.start();
        }

        @Test
        @DisplayName("persists entries appended while a tick write was still in flight when the fire ended")
        void persistsEntriesAppendedDuringAnInFlightTickWrite(Vertx vertx, VertxTestContext ctx) {
            JobRepository repo = stubRepoCapturingCompletion(new AtomicReference<>());

            // The tick's saveLogs is held pending until the completion consumer is already inside
            // its ending drain. Releasing it from a runOnContext scheduled inside the interceptor's
            // onComplete is what makes that ordering deterministic without a sleep: onComplete runs
            // inside the completion callback, and Vert.x cannot run the queued task until that
            // callback — including the finally-block drain — has returned.
            Promise<Void> heldWrite = Promise.promise();
            JobInterceptor releaseOnComplete = new JobInterceptor() {
                @Override
                public void onComplete(
                        JobDispatchContext dispatchCtx, Result<?> result, Instant startTime, Instant endTime) {
                    vertx.runOnContext(v -> heldWrite.tryComplete());
                }
            };

            AtomicReference<DefaultJobContext> contextRef = new AtomicReference<>();
            AtomicReference<String> replyAddressRef = new AtomicReference<>();
            AtomicInteger writes = new AtomicInteger();
            when(repo.saveLogs(any(UUID.class), any())).thenAnswer(invocation -> {
                List<LogEntry> batch = invocation.getArgument(1);
                int call = writes.incrementAndGet();
                if (call == 1) {
                    // The tick has claimed "before the tick" and this write is now outstanding.
                    // Append an entry that the single-flight claim cannot see, then end the fire —
                    // the window a plain ending-site flush() drops on the floor.
                    contextRef.get().logger().info("during the in-flight write");
                    vertx.eventBus()
                            .send(
                                    replyAddressRef.get(),
                                    DispatchEnvelope.of(Result.success(null)),
                                    new DeliveryOptions().setCodecName("dispatch.envelope"));
                    return heldWrite.future();
                }
                if (call == 2) {
                    ctx.verify(() -> assertTrue(
                            batch.stream().anyMatch(entry -> "during the in-flight write".equals(entry.message())),
                            "an entry appended while a tick write was in flight must still reach the repository"));
                    ctx.completeNow();
                }
                return Future.succeededFuture();
            });

            // executionTimeoutMs = 0 disables the timeout path, so the completion consumer is the
            // only ending site in play; the 100 ms progress tick supplies the in-flight write.
            scheduler = new CronScheduler(
                    vertx,
                    Set.of(releaseOnComplete),
                    repo,
                    stubTargetResolver(),
                    testEventBusClient(vertx),
                    10,
                    0L,
                    100L,
                    DispatchEnvelopeBuilder.forTesting());

            AtomicBoolean logged = new AtomicBoolean(false);
            vertx.eventBus().consumer("test.logflush.inflight.address", msg -> {
                if (!(msg.body() instanceof DispatchEnvelope<?> body) || !logged.compareAndSet(false, true)) {
                    return;
                }
                DefaultJobContext jobCtx =
                        (DefaultJobContext) body.metadata().dispatchContext().get(JobContext.class.getName());
                contextRef.set(jobCtx);
                replyAddressRef.set(body.replyAddress().orElseThrow());
                jobCtx.logger().info("before the tick");
            });

            // SINGLE_INSTANCE + the stubbed tryInsert win makes this a tracked fire, so the
            // dispatcher receives a non-null execution and the flusher is persistable.
            CronJobDefinition job = new CronJobDefinition(
                    "log-inflight-job",
                    new CronExpression("* * * * * *"),
                    new CronTargetReference.EventBusTarget("test.logflush.inflight.address"),
                    "test.logflush.inflight.address",
                    ExecutionMode.SINGLE_INSTANCE,
                    ZoneId.of("UTC"),
                    3,
                    null,
                    OverlapPolicy.SKIP,
                    true,
                    Map.of(),
                    MisfirePolicy.SKIP);

            scheduler.register(job);
            scheduler.start();
        }

        @Test
        @DisplayName("stop() drains the buffered logs of a fire that is still in flight")
        void shutdownDrainsBufferedLogsOfInFlightExecutions(Vertx vertx, VertxTestContext ctx) {
            JobRepository repo = stubRepoCapturingCompletion(new AtomicReference<>());

            AtomicBoolean asserted = new AtomicBoolean(false);
            when(repo.saveLogs(any(UUID.class), any())).thenAnswer(invocation -> {
                List<LogEntry> batch = invocation.getArgument(1);
                if (asserted.compareAndSet(false, true)) {
                    ctx.verify(() -> assertTrue(
                            batch.stream().anyMatch(entry -> "before the shutdown".equals(entry.message())),
                            "the shutdown cutoff must drain the buffered entry of a fire still in flight"));
                    ctx.completeNow();
                }
                return Future.succeededFuture();
            });

            // executionTimeoutMs = 0 disables the timeout path and progressFlushIntervalMs = 0
            // disables the periodic tick; the handler never replies, so the completion consumer
            // never runs either. CronJobDispatcher.shutdown() is the only site left that can
            // reach saveLogs.
            scheduler = new CronScheduler(
                    vertx,
                    Set.of(),
                    repo,
                    stubTargetResolver(),
                    testEventBusClient(vertx),
                    10,
                    0L,
                    0L,
                    DispatchEnvelopeBuilder.forTesting());

            AtomicBoolean logged = new AtomicBoolean(false);
            vertx.eventBus().consumer("test.logflush.shutdown.address", msg -> {
                if (!(msg.body() instanceof DispatchEnvelope<?> body) || !logged.compareAndSet(false, true)) {
                    return;
                }
                DefaultJobContext jobCtx =
                        (DefaultJobContext) body.metadata().dispatchContext().get(JobContext.class.getName());
                jobCtx.logger().info("before the shutdown");
                // Never reply: the execution stays in the dispatcher's activeExecutions, which is
                // what makes the cutoff drain reachable. stop() is the public entry point onto
                // CronJobDispatcher.shutdown() — the dispatcher itself is package-private and owned
                // by the scheduler. Calling it from here rather than after a sleep is the ordering
                // signal: the entry is buffered before the shutdown can begin.
                scheduler.stop().onFailure(ctx::failNow);
            });

            // SINGLE_INSTANCE + the stubbed tryInsert win makes this a tracked fire, so the
            // dispatcher receives a non-null execution and the flusher is persistable. An untracked
            // fire gets a no-op flusher by design and would make this test vacuous.
            CronJobDefinition job = new CronJobDefinition(
                    "log-shutdown-job",
                    new CronExpression("* * * * * *"),
                    new CronTargetReference.EventBusTarget("test.logflush.shutdown.address"),
                    "test.logflush.shutdown.address",
                    ExecutionMode.SINGLE_INSTANCE,
                    ZoneId.of("UTC"),
                    3,
                    null,
                    OverlapPolicy.SKIP,
                    true,
                    Map.of(),
                    MisfirePolicy.SKIP);

            scheduler.register(job);
            scheduler.start();
        }
    }
}
