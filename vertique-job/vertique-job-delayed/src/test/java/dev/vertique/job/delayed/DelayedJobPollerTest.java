// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.context.ContextScopeBinder;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DeferredExecutionOrigin;
import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.eventbus.EventBusExceptionMapper;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.core.eventbus.Result;
import dev.vertique.job.DefaultJobContext;
import dev.vertique.job.JobCompletionHandler;
import dev.vertique.job.JobContext;
import dev.vertique.job.JobDispatchContext;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobInterceptor;
import dev.vertique.job.JobRepository;
import dev.vertique.job.JobState;
import dev.vertique.job.JobType;
import dev.vertique.job.LogEntry;
import dev.vertique.job.ProgressSnapshot;
import dev.vertique.job.delayed.config.DelayedJobQueueConfig;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Tests for {@link DelayedJobPoller}: lifecycle, poll-dispatch-complete cycle, concurrency
 * limit enforcement, backoff strategy resolution, and interceptor invocation.
 */
@DisplayName("DelayedJobPoller")
@ExtendWith({VertxExtension.class, MockitoExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class DelayedJobPollerTest {

    /** Creates a test {@link EventBusClient} from the given Vert.x instance. */
    private static EventBusClient testEventBusClient(Vertx vertx) {
        return new EventBusClient(vertx, new EventBusExceptionMapper());
    }

    /** Creates a no-op {@link DurableContextPropagator} with no encoders or decoders. */
    private static DurableContextPropagator noOpPropagator() {
        DefaultContextHolder holder = new DefaultContextHolder();
        return new DurableContextPropagator(
                new DurableContextMetadataRegistry(Set.of(), Set.of()), holder, new ContextScopeBinder(holder));
    }

    @Mock
    JobRepository repository;

    @Mock
    JobCompletionHandler completionHandler;

    // --- Helpers ---

    /** Creates a fast-polling config (short sleep delay for test speed). */
    private static DelayedJobQueueConfig fastConfig() {
        return DelayedJobQueueConfig.builder()
                .sleepDelayMs(50L)
                .maxConcurrentJobs(5)
                .backoffStrategy("LINEAR")
                .backoffBaseDelayMs(1000L)
                .backoffMaxDelayMs(60_000L)
                .build();
    }

    /** Creates a sample PROCESSING execution on its first attempt, with three attempts allowed. */
    private static JobExecution sampleExecution(String jobId, String handler) {
        return sampleExecution(jobId, handler, 3);
    }

    /**
     * Creates a sample PROCESSING execution on its first attempt with an explicit attempt budget.
     * Pass {@code maxAttempts = 1} to make a timeout dead-letter rather than retry.
     */
    private static JobExecution sampleExecution(String jobId, String handler, int maxAttempts) {
        return new JobExecution(
                UUID.randomUUID(),
                jobId,
                JobType.DELAYED,
                handler,
                "default",
                JobState.PROCESSING,
                0,
                maxAttempts,
                null,
                0,
                null,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                ProgressSnapshot.EMPTY,
                Map.of(),
                Map.of(),
                DurableMetadata.empty());
    }

    @BeforeEach
    void registerCodec(Vertx vertx) {
        try {
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.envelope"));
        } catch (IllegalStateException ignored) {
            // Already registered
        }
    }

    // --- Tests ---

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("starts without error")
        void startsWithoutError(Vertx vertx, VertxTestContext ctx) {
            when(repository.claimNextJob(anyString(), anyInt())).thenReturn(Future.succeededFuture(List.of()));

            DelayedJobPoller poller = new DelayedJobPoller(
                    "default",
                    fastConfig(),
                    repository,
                    completionHandler,
                    Set.of(),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting(),
                    noOpPropagator());

            vertx.deployVerticle(poller).onSuccess(id -> ctx.completeNow()).onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("stops cleanly")
        void stopsCleanly(Vertx vertx, VertxTestContext ctx) {
            when(repository.claimNextJob(anyString(), anyInt())).thenReturn(Future.succeededFuture(List.of()));

            DelayedJobPoller poller = new DelayedJobPoller(
                    "default",
                    fastConfig(),
                    repository,
                    completionHandler,
                    Set.of(),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting(),
                    noOpPropagator());

            vertx.deployVerticle(poller)
                    .compose(id -> vertx.undeploy(id))
                    .onSuccess(v -> ctx.completeNow())
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("exposes correct queue name")
        void exposesQueueName() {
            DelayedJobPoller poller = new DelayedJobPoller(
                    "my-queue",
                    fastConfig(),
                    repository,
                    completionHandler,
                    Set.of(),
                    null,
                    DispatchEnvelopeBuilder.forTesting(),
                    noOpPropagator());
            assertEquals("my-queue", poller.queue());
        }
    }

    @Nested
    @DisplayName("poll-dispatch-complete cycle")
    class PollDispatchComplete {

        @Test
        @DisplayName("dispatches claimed job and calls completion handler on success")
        void dispatchesAndCompletesSuccessfully(Vertx vertx, VertxTestContext ctx) {
            String handlerAddress = "test.delayed.handler";
            JobExecution execution = sampleExecution("job-1", handlerAddress);

            // First poll returns 1 job; subsequent polls (which may use different batchSize
            // due to in-flight count changes) always return empty to avoid repeated dispatch.
            when(repository.claimNextJob(anyString(), anyInt()))
                    .thenReturn(Future.succeededFuture(List.of(execution)))
                    .thenReturn(Future.succeededFuture(List.of()));

            // Causal terminal signal: the poller invokes the completion handler only after the
            // handler's reply has settled the dispatch, so this answer runs strictly after the
            // asserted state transition. By the time the answer executes, Mockito has already
            // recorded the invocation, so the original atLeastOnce expectation holds here.
            when(completionHandler.handleCompletion(any(), any(), any())).thenAnswer(invocation -> {
                ctx.verify(() -> verify(completionHandler, atLeastOnce()).handleCompletion(any(), any(), any()));
                ctx.completeNow();
                return Future.succeededFuture();
            });

            // Register a handler that simulates ServiceMethodInvoker fire-and-report:
            // reads the replyAddress from the DispatchEnvelope and sends the Result there
            vertx.eventBus().consumer(handlerAddress, msg -> {
                if (msg.body() instanceof DispatchEnvelope<?> receivedBody
                        && receivedBody.replyAddress().isPresent()) {
                    DispatchEnvelope<?> reply = DispatchEnvelope.of(
                            Result.success(null), dev.vertique.core.eventbus.DispatchMetadata.empty());
                    vertx.eventBus()
                            .send(
                                    receivedBody.replyAddress().orElseThrow(),
                                    reply,
                                    new DeliveryOptions().setCodecName("dispatch.envelope"));
                }
            });

            DelayedJobPoller poller = new DelayedJobPoller(
                    "default",
                    fastConfig(),
                    repository,
                    completionHandler,
                    Set.of(),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting(),
                    noOpPropagator());

            vertx.deployVerticle(poller).onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("claims with correct queue name and max concurrent slots")
        void claimsWithCorrectParams(Vertx vertx, VertxTestContext ctx) {
            when(repository.claimNextJob("special-queue", 3))
                    .thenReturn(Future.succeededFuture(List.of()))
                    .thenReturn(Future.succeededFuture(List.of()));

            DelayedJobQueueConfig cfg = DelayedJobQueueConfig.builder()
                    .sleepDelayMs(50L)
                    .maxConcurrentJobs(3)
                    .build();

            DelayedJobPoller poller = new DelayedJobPoller(
                    "special-queue",
                    cfg,
                    repository,
                    completionHandler,
                    Set.of(),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting(),
                    noOpPropagator());

            vertx.deployVerticle(poller).onFailure(ctx::failNow);

            // Give the poller time to poll at least once
            vertx.setTimer(300L, id -> {
                try {
                    verify(repository, atLeastOnce()).claimNextJob("special-queue", 3);
                    ctx.completeNow();
                } catch (Exception e) {
                    ctx.failNow(e);
                }
            });
        }

        @Test
        @DisplayName("binds DeferredExecutionOrigin(delayed-job, handler) into the dispatch context")
        void bindsDeferredExecutionOrigin(Vertx vertx, VertxTestContext ctx) {
            String handlerAddress = "test.origin.handler";
            JobExecution execution = sampleExecution("origin-job-1", handlerAddress);

            when(repository.claimNextJob(anyString(), anyInt()))
                    .thenReturn(Future.succeededFuture(List.of(execution)))
                    .thenReturn(Future.succeededFuture(List.of()));

            AtomicBoolean asserted = new AtomicBoolean(false);

            // Inspect the FQCN-keyed dispatch-context map carried in the DispatchEnvelope: the
            // delayed-job boundary must bind a DeferredExecutionOrigin proving deferred execution
            // (W2/A6), keyed alongside the JobDispatchContext entry. The reference is the handler's
            // event-bus address (a stable, resolver-mappable id), not the jobId.
            vertx.eventBus().consumer(handlerAddress, msg -> {
                if (!(msg.body() instanceof DispatchEnvelope<?> body) || !asserted.compareAndSet(false, true)) {
                    return;
                }
                Object origin = body.metadata().dispatchContext().get(DeferredExecutionOrigin.class.getName());
                ctx.verify(() -> {
                    assertInstanceOf(DeferredExecutionOrigin.class, origin);
                    DeferredExecutionOrigin deferredOrigin = (DeferredExecutionOrigin) origin;
                    assertEquals("delayed-job", deferredOrigin.kind());
                    assertEquals(handlerAddress, deferredOrigin.reference());
                });
                ctx.completeNow();
            });

            DelayedJobPoller poller = new DelayedJobPoller(
                    "default",
                    fastConfig(),
                    repository,
                    completionHandler,
                    Set.of(),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting(),
                    noOpPropagator());

            vertx.deployVerticle(poller).onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("origin reference is the stable handler address, not the auto-generated jobId")
        void originReferenceIsStableHandlerNotGeneratedJobId(Vertx vertx, VertxTestContext ctx) {
            String handlerAddress = "test.stable.handler";
            // Auto-generated form produced by DelayedJobService: "delayed-" + UUID. It is distinct
            // from the handler address and is NOT resolver-mappable, so the origin reference must be
            // the handler address instead (Codex W2).
            String generatedJobId = "delayed-" + UUID.randomUUID();
            JobExecution execution = sampleExecution(generatedJobId, handlerAddress);

            when(repository.claimNextJob(anyString(), anyInt()))
                    .thenReturn(Future.succeededFuture(List.of(execution)))
                    .thenReturn(Future.succeededFuture(List.of()));

            AtomicBoolean asserted = new AtomicBoolean(false);

            vertx.eventBus().consumer(handlerAddress, msg -> {
                if (!(msg.body() instanceof DispatchEnvelope<?> body) || !asserted.compareAndSet(false, true)) {
                    return;
                }
                Object origin = body.metadata().dispatchContext().get(DeferredExecutionOrigin.class.getName());
                ctx.verify(() -> {
                    assertInstanceOf(DeferredExecutionOrigin.class, origin);
                    DeferredExecutionOrigin deferredOrigin = (DeferredExecutionOrigin) origin;
                    assertEquals("delayed-job", deferredOrigin.kind());
                    assertEquals(handlerAddress, deferredOrigin.reference(), "reference must be the stable handler");
                    assertNotEquals(
                            generatedJobId, deferredOrigin.reference(), "reference must not be the generated jobId");
                });
                ctx.completeNow();
            });

            DelayedJobPoller poller = new DelayedJobPoller(
                    "default",
                    fastConfig(),
                    repository,
                    completionHandler,
                    Set.of(),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting(),
                    noOpPropagator());

            vertx.deployVerticle(poller).onFailure(ctx::failNow);
        }
    }

    @Nested
    @DisplayName("carrier binding (F5 row binding, PRD-ID-002 §14.6/A9/F5)")
    class CarrierBinding {

        /** Test-only ContextValue whose decoder records the DurableDecodeContext it is handed. */
        record RecCtx(String value) implements ContextValue {}

        @Test
        @DisplayName("dispatch builds the row's own carrier and threads it into decodeToDispatchContext")
        void dispatchBindsExecutionCarrierIntoDecodeContext(Vertx vertx, VertxTestContext ctx) {
            String handlerAddress = "test.carrier.handler";
            JobExecution execution = sampleExecution("carrier-job", handlerAddress);

            AtomicReference<DurableDecodeContext> observed = new AtomicReference<>();
            DurableContextMetadataDecoder<RecCtx> recordingDecoder = new DurableContextMetadataDecoder<>() {
                @Override
                public Class<RecCtx> type() {
                    return RecCtx.class;
                }

                @Override
                public String namespace() {
                    return "rec-ns";
                }

                @Override
                public ContextDecodeResult<RecCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                    observed.set(context);
                    return ContextDecodeResult.empty();
                }
            };
            DefaultContextHolder holder = new DefaultContextHolder();
            DurableContextPropagator propagator = new DurableContextPropagator(
                    new DurableContextMetadataRegistry(Set.of(), Set.of(recordingDecoder)),
                    holder,
                    new ContextScopeBinder(holder));

            when(repository.claimNextJob(anyString(), anyInt()))
                    .thenReturn(Future.succeededFuture(List.of(execution)))
                    .thenReturn(Future.succeededFuture(List.of()));

            // Causal terminal signal: consumer receipt. dispatch() threads the carrier through
            // decodeToDispatchContext before it sends the envelope, so by the time this message
            // arrives the recording decoder has already observed its DurableDecodeContext. The
            // handler never replies — we only need dispatch() to run decodeToDispatchContext.
            vertx.eventBus()
                    .consumer(
                            handlerAddress,
                            msg -> ctx.verify(() -> {
                                DurableDecodeContext decodeContext = observed.get();
                                assertNotNull(decodeContext, "decoder must have been invoked during dispatch");
                                assertTrue(
                                        decodeContext.carrier().isPresent(),
                                        "dispatch must thread the row's carrier into the decode context");
                                DurableCarrierDescriptor carrier =
                                        decodeContext.carrier().orElseThrow();
                                assertEquals(
                                        execution.id().toString(),
                                        carrier.carrierId(),
                                        "carrierId == executing row id");
                                assertEquals("delayed-job", carrier.target().kind());
                                assertEquals(
                                        handlerAddress,
                                        carrier.target().address(),
                                        "target address == executing row handler");
                                ctx.completeNow();
                            }));

            DelayedJobPoller poller = new DelayedJobPoller(
                    "default",
                    fastConfig(),
                    repository,
                    completionHandler,
                    Set.of(),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting(),
                    propagator);

            vertx.deployVerticle(poller).onFailure(ctx::failNow);
        }
    }

    @Nested
    @DisplayName("concurrency limit")
    class ConcurrencyLimit {

        @Test
        @DisplayName("starts with zero in-flight count")
        void initialInFlightIsZero() {
            // No mocks needed — we are testing the initial state before deployment
            DelayedJobPoller poller = new DelayedJobPoller(
                    "default",
                    fastConfig(),
                    repository,
                    completionHandler,
                    Set.of(),
                    null,
                    DispatchEnvelopeBuilder.forTesting(),
                    noOpPropagator());
            assertEquals(0, poller.inFlightCount());
        }
    }

    @Nested
    @DisplayName("interceptor invocation")
    class InterceptorInvocation {

        @Test
        @DisplayName("fires onDispatch and onComplete interceptor callbacks")
        void firesInterceptorCallbacks(Vertx vertx, VertxTestContext ctx) {
            String handlerAddress = "test.interceptor.handler";
            JobExecution execution = sampleExecution("intercepted-job", handlerAddress);

            // First poll returns 1 job; subsequent polls always return empty
            when(repository.claimNextJob(anyString(), anyInt()))
                    .thenReturn(Future.succeededFuture(List.of(execution)))
                    .thenReturn(Future.succeededFuture(List.of()));
            when(completionHandler.handleCompletion(any(), any(), any())).thenReturn(Future.succeededFuture());

            AtomicInteger dispatchCount = new AtomicInteger();
            AtomicInteger completeCount = new AtomicInteger();

            // One lax checkpoint per interceptor callback: the context completes only once both
            // callbacks have fired (lax preserves the original "at least once" tolerance).
            // onComplete is the FINAL callback — it fires after onDispatch on the completion
            // path — so it is the causal terminal signal and asserts both counters.
            Checkpoint dispatchFired = ctx.laxCheckpoint();
            Checkpoint completeFired = ctx.laxCheckpoint();

            JobInterceptor interceptor = new JobInterceptor() {
                @Override
                public void onDispatch(JobDispatchContext dispatchCtx) {
                    dispatchCount.incrementAndGet();
                    dispatchFired.flag();
                }

                @Override
                public void onComplete(
                        JobDispatchContext dispatchCtx, Result<?> result, Instant startTime, Instant endTime) {
                    completeCount.incrementAndGet();
                    ctx.verify(() -> {
                        assertTrue(dispatchCount.get() >= 1, "Expected at least 1 dispatch interceptor call");
                        assertTrue(completeCount.get() >= 1, "Expected at least 1 complete interceptor call");
                    });
                    completeFired.flag();
                }
            };

            // Simulate ServiceMethodInvoker fire-and-report: send Result to the replyAddress
            vertx.eventBus().consumer(handlerAddress, msg -> {
                if (msg.body() instanceof DispatchEnvelope<?> receivedBody
                        && receivedBody.replyAddress().isPresent()) {
                    DispatchEnvelope<?> reply = DispatchEnvelope.of(
                            Result.success(null), dev.vertique.core.eventbus.DispatchMetadata.empty());
                    vertx.eventBus()
                            .send(
                                    receivedBody.replyAddress().orElseThrow(),
                                    reply,
                                    new DeliveryOptions().setCodecName("dispatch.envelope"));
                }
            });

            DelayedJobPoller poller = new DelayedJobPoller(
                    "default",
                    fastConfig(),
                    repository,
                    completionHandler,
                    Set.of(interceptor),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting(),
                    noOpPropagator());

            vertx.deployVerticle(poller).onFailure(ctx::failNow);
        }
    }

    @Nested
    @DisplayName("backoff strategy resolution")
    class BackoffStrategyResolution {

        @Test
        @DisplayName("resolves FIXED strategy without error")
        void resolvesFixedStrategy(Vertx vertx, VertxTestContext ctx) {
            when(repository.claimNextJob(anyString(), anyInt())).thenReturn(Future.succeededFuture(List.of()));

            DelayedJobQueueConfig cfg = DelayedJobQueueConfig.builder()
                    .sleepDelayMs(50L)
                    .backoffStrategy("FIXED")
                    .build();

            DelayedJobPoller poller = new DelayedJobPoller(
                    "default",
                    cfg,
                    repository,
                    completionHandler,
                    Set.of(),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting(),
                    noOpPropagator());

            vertx.deployVerticle(poller)
                    .onSuccess(id -> {
                        vertx.setTimer(200, t -> ctx.completeNow());
                    })
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("resolves EXPONENTIAL strategy without error")
        void resolvesExponentialStrategy(Vertx vertx, VertxTestContext ctx) {
            when(repository.claimNextJob(anyString(), anyInt())).thenReturn(Future.succeededFuture(List.of()));

            DelayedJobQueueConfig cfg = DelayedJobQueueConfig.builder()
                    .sleepDelayMs(50L)
                    .backoffStrategy("EXPONENTIAL")
                    .build();

            DelayedJobPoller poller = new DelayedJobPoller(
                    "default",
                    cfg,
                    repository,
                    completionHandler,
                    Set.of(),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting(),
                    noOpPropagator());

            vertx.deployVerticle(poller)
                    .onSuccess(id -> {
                        vertx.setTimer(200, t -> ctx.completeNow());
                    })
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("resolves LINEAR strategy (default) without error")
        void resolvesLinearStrategy(Vertx vertx, VertxTestContext ctx) {
            when(repository.claimNextJob(anyString(), anyInt())).thenReturn(Future.succeededFuture(List.of()));

            DelayedJobQueueConfig cfg = DelayedJobQueueConfig.builder()
                    .sleepDelayMs(50L)
                    .backoffStrategy("LINEAR")
                    .build();

            DelayedJobPoller poller = new DelayedJobPoller(
                    "default",
                    cfg,
                    repository,
                    completionHandler,
                    Set.of(),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting(),
                    noOpPropagator());

            vertx.deployVerticle(poller)
                    .onSuccess(id -> {
                        vertx.setTimer(200, t -> ctx.completeNow());
                    })
                    .onFailure(ctx::failNow);
        }
    }

    // --- Consumer timeout tests ---

    @Nested
    @DisplayName("consumer timeout")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    class ConsumerTimeout {

        @Test
        @DisplayName(
                "retryable timeout: calls abandonAndScheduleRetry atomically, never calls completeExecution or handleCompletion")
        void retryableTimeoutCallsAbandonAndScheduleRetry(Vertx vertx, VertxTestContext ctx) {
            String handlerAddress = "test.timeout.handler";
            // attemptNumber=0, maxAttempts=3 — still retryable after one timeout
            JobExecution execution = sampleExecution("timeout-job", handlerAddress);

            when(repository.claimNextJob(anyString(), anyInt()))
                    .thenReturn(Future.succeededFuture(List.of(execution)))
                    .thenReturn(Future.succeededFuture(List.of()));
            // abandonAndScheduleRetry is the new atomic op for retryable timeouts
            when(repository.abandonAndScheduleRetry(
                            any(UUID.class), anyString(), anyString(), any(), any(Instant.class), anyInt()))
                    .thenReturn(Future.succeededFuture(Optional.of(execution)));

            // Handler that intentionally never replies — let the timeout fire
            vertx.eventBus().consumer(handlerAddress, msg -> {});

            // Poller with 300 ms execution timeout
            DelayedJobPoller poller = new DelayedJobPoller(
                    "default",
                    fastConfig(),
                    repository,
                    completionHandler,
                    Set.of(),
                    testEventBusClient(vertx),
                    300L,
                    0L,
                    DispatchEnvelopeBuilder.forTesting(),
                    noOpPropagator());

            vertx.deployVerticle(poller).onFailure(ctx::failNow);

            // Causal terminal signal: abandonAndScheduleRetry is the terminal write of the
            // retryable-timeout branch, which is mutually exclusive with the forbidden calls
            // below — once it is recorded the never() checks are conclusive without a quiet
            // window. Mockito's timeout verify blocks this JUnit worker thread, never a Vert.x
            // event-loop thread.
            // abandonAndScheduleRetry must be called — one atomic operation for timeout+retry
            verify(repository, timeout(2000).atLeastOnce())
                    .abandonAndScheduleRetry(
                            eq(execution.id()), anyString(), anyString(), any(), any(Instant.class), eq(1));
            // completeExecution(ABANDONED) must never be called — replaced by atomic op
            verify(repository, never())
                    .completeExecution(eq(execution.id()), eq(JobState.ABANDONED), anyString(), anyString(), any());
            // scheduleRetry must never be called separately — replaced by atomic op
            verify(repository, never()).scheduleRetry(any(), any(), anyInt());
            // handleCompletion is not called from the timeout path
            verify(completionHandler, never()).handleCompletion(any(), any(), any());
            ctx.completeNow();
        }

        @Test
        @DisplayName("exhausted timeout: writes DEAD_LETTER directly, never calls handleCompletion or scheduleRetry")
        void exhaustedTimeoutWritesDeadLetterDirectly(Vertx vertx, VertxTestContext ctx) {
            String handlerAddress = "test.timeout.exhausted.handler";
            // attemptNumber=2, maxAttempts=3 — no attempts remain after this timeout
            JobExecution execution = new JobExecution(
                    UUID.randomUUID(),
                    "timeout-exhausted-job",
                    JobType.DELAYED,
                    handlerAddress,
                    "default",
                    JobState.PROCESSING,
                    2,
                    3,
                    null,
                    0,
                    null,
                    Instant.now(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    ProgressSnapshot.EMPTY,
                    Map.of(),
                    Map.of(),
                    DurableMetadata.empty());

            when(repository.claimNextJob(anyString(), anyInt()))
                    .thenReturn(Future.succeededFuture(List.of(execution)))
                    .thenReturn(Future.succeededFuture(List.of()));
            when(repository.completeExecution(
                            any(UUID.class), eq(JobState.DEAD_LETTER), anyString(), anyString(), any()))
                    .thenReturn(Future.succeededFuture(Optional.of(execution)));

            // Handler that intentionally never replies — let the timeout fire
            vertx.eventBus().consumer(handlerAddress, msg -> {});

            // Poller with 300 ms execution timeout
            DelayedJobPoller poller = new DelayedJobPoller(
                    "default",
                    fastConfig(),
                    repository,
                    completionHandler,
                    Set.of(),
                    testEventBusClient(vertx),
                    300L,
                    0L,
                    DispatchEnvelopeBuilder.forTesting(),
                    noOpPropagator());

            vertx.deployVerticle(poller).onFailure(ctx::failNow);

            // Causal terminal signal: the direct DEAD_LETTER write is the terminal write of the
            // exhausted-timeout branch, which is mutually exclusive with the forbidden calls
            // below — once it is recorded the never() checks are conclusive without a quiet
            // window. Mockito's timeout verify blocks this JUnit worker thread, never a Vert.x
            // event-loop thread.
            // DEAD_LETTER must be written directly — one record, no ABANDONED step
            verify(repository, timeout(2000).atLeastOnce())
                    .completeExecution(eq(execution.id()), eq(JobState.DEAD_LETTER), anyString(), anyString(), any());
            // scheduleRetry must never be called — exhausted
            verify(repository, never()).scheduleRetry(any(), any(), anyInt());
            // abandonAndScheduleRetry must never be called — exhausted path goes to DEAD_LETTER
            verify(repository, never())
                    .abandonAndScheduleRetry(any(), any(), any(), any(), any(Instant.class), anyInt());
            // handleCompletion is not called from the timeout path
            verify(completionHandler, never()).handleCompletion(any(), any(), any());
            ctx.completeNow();
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
            String handlerAddress = "test.cancel.handler";
            JobExecution execution = sampleExecution("cancel-job", handlerAddress);

            when(repository.claimNextJob(anyString(), anyInt()))
                    .thenReturn(Future.succeededFuture(List.of(execution)))
                    .thenReturn(Future.succeededFuture(List.of()));
            when(completionHandler.handleCompletion(any(), any(), any())).thenReturn(Future.succeededFuture());

            // Handler that captures JobContext, publishes cancel, then checks the flag
            vertx.eventBus().consumer(handlerAddress, msg -> {
                if (msg.body() instanceof DispatchEnvelope<?> body) {
                    // Extract JobContext from the dispatch context map
                    dev.vertique.job.DefaultJobContext jobCtx = (dev.vertique.job.DefaultJobContext)
                            body.metadata().dispatchContext().get(JobContext.class.getName());
                    if (jobCtx != null) {
                        // Publish cancel on the event bus
                        vertx.eventBus().publish("job.cancel." + jobCtx.executionId(), "cancel");
                        // Give the cancel message time to be delivered before checking
                        vertx.setTimer(100, id -> {
                            // Causal terminal signal: this is the point where the flag transition
                            // becomes observable — assert and complete here, no outer deadline.
                            boolean cancelled = jobCtx.isCancelled();
                            // Reply to unblock the poller
                            if (body.replyAddress().isPresent()) {
                                vertx.eventBus()
                                        .send(
                                                body.replyAddress().orElseThrow(),
                                                DispatchEnvelope.of(
                                                        Result.success(null),
                                                        dev.vertique.core.eventbus.DispatchMetadata.empty()),
                                                new DeliveryOptions().setCodecName("dispatch.envelope"));
                            }
                            ctx.verify(() -> assertTrue(
                                    cancelled, "JobContext should have isCancelled=true after cancel signal"));
                            ctx.completeNow();
                        });
                    }
                }
            });

            DelayedJobPoller poller = new DelayedJobPoller(
                    "default",
                    fastConfig(),
                    repository,
                    completionHandler,
                    Set.of(),
                    testEventBusClient(vertx),
                    DispatchEnvelopeBuilder.forTesting(),
                    noOpPropagator());

            vertx.deployVerticle(poller).onFailure(ctx::failNow);
        }
    }

    // --- Job log durability tests ---

    /**
     * Proves the poller drains the per-execution {@link dev.vertique.job.JobLogger} buffer into
     * {@link JobRepository#saveLogs} while the execution is still running, and again on the paths
     * that end an execution.
     *
     * <p>Every test here is sleep-free: the {@code saveLogs} mock <em>is</em> the completion
     * signal. Each answer is guarded by a latch because the periodic flush timer keeps firing
     * after the assertion has been made.
     *
     * <p>{@link #persistsEntriesAppendedDuringAnInFlightTickWrite} is the end-to-end counterpart of
     * {@code JobLogFlusherTest.Drain}: it is the only test here whose {@code saveLogs} returns a
     * <em>pending</em> future, and therefore the only one that can observe the in-flight window an
     * ending-site flush used to lose.
     *
     * <p>The two {@code stop*} tests cover the verticle-shutdown flush site, which the
     * {@code "stops cleanly"} lifecycle test cannot reach: that test stubs {@code claimNextJob} to
     * return an empty list, so nothing is ever dispatched and {@code activeExecutions} is empty when
     * {@link DelayedJobPoller#stop(Promise)} runs. Both tests here hold an execution in flight by
     * never replying from the handler.
     */
    @Nested
    @DisplayName("job log flush")
    class JobLogFlush {

        @Test
        @DisplayName("flushes buffered log entries to the repository on the periodic progress tick")
        void flushesLogsOnProgressTick(Vertx vertx, VertxTestContext ctx) {
            String handlerAddress = "test.logflush.tick.handler";
            JobExecution execution = sampleExecution("log-tick-job", handlerAddress);

            when(repository.claimNextJob(anyString(), anyInt()))
                    .thenReturn(Future.succeededFuture(List.of(execution)))
                    .thenReturn(Future.succeededFuture(List.of()));

            AtomicBoolean asserted = new AtomicBoolean(false);
            when(repository.saveLogs(eq(execution.id()), any())).thenAnswer(invocation -> {
                List<LogEntry> batch = invocation.getArgument(1);
                if (asserted.compareAndSet(false, true)) {
                    ctx.verify(() -> assertTrue(
                            batch.stream().anyMatch(entry -> "hello from handler".equals(entry.message())),
                            "the periodic tick must flush the handler's buffered entry"));
                    ctx.completeNow();
                }
                return Future.succeededFuture();
            });

            // The handler logs once and NEVER replies. With executionTimeoutMs = 0 there is no
            // timeout path either, so the periodic tick is the only thing that can call saveLogs.
            AtomicBoolean logged = new AtomicBoolean(false);
            vertx.eventBus().consumer(handlerAddress, msg -> {
                if (!(msg.body() instanceof DispatchEnvelope<?> body) || !logged.compareAndSet(false, true)) {
                    return;
                }
                DefaultJobContext jobCtx =
                        (DefaultJobContext) body.metadata().dispatchContext().get(JobContext.class.getName());
                jobCtx.logger().info("hello from handler");
            });

            DelayedJobPoller poller = new DelayedJobPoller(
                    "default",
                    fastConfig(),
                    repository,
                    completionHandler,
                    Set.of(),
                    testEventBusClient(vertx),
                    0L,
                    100L,
                    DispatchEnvelopeBuilder.forTesting(),
                    noOpPropagator());

            vertx.deployVerticle(poller).onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("does not re-send entries a previous flush already acked")
        void doesNotResendAlreadyFlushedEntriesOnCompletion(Vertx vertx, VertxTestContext ctx) {
            String handlerAddress = "test.logflush.ack.handler";
            JobExecution execution = sampleExecution("log-ack-job", handlerAddress);

            when(repository.claimNextJob(anyString(), anyInt()))
                    .thenReturn(Future.succeededFuture(List.of(execution)))
                    .thenReturn(Future.succeededFuture(List.of()));
            when(completionHandler.handleCompletion(any(), any(), any())).thenReturn(Future.succeededFuture());

            AtomicReference<DefaultJobContext> contextRef = new AtomicReference<>();
            AtomicReference<String> replyAddressRef = new AtomicReference<>();

            // The handler logs "first" and does not reply yet — the saveLogs mock below drives
            // the sequencing, so no sleep is needed to order the tick flush before the reply.
            AtomicBoolean logged = new AtomicBoolean(false);
            vertx.eventBus().consumer(handlerAddress, msg -> {
                if (!(msg.body() instanceof DispatchEnvelope<?> body) || !logged.compareAndSet(false, true)) {
                    return;
                }
                DefaultJobContext jobCtx =
                        (DefaultJobContext) body.metadata().dispatchContext().get(JobContext.class.getName());
                contextRef.set(jobCtx);
                replyAddressRef.set(body.replyAddress().orElseThrow());
                jobCtx.logger().info("first");
            });

            AtomicInteger flushCount = new AtomicInteger();
            when(repository.saveLogs(eq(execution.id()), any())).thenAnswer(invocation -> {
                List<LogEntry> batch = invocation.getArgument(1);
                int call = flushCount.incrementAndGet();
                if (call == 1) {
                    // The tick flush claimed "first". Buffer one more entry, then let the handler
                    // "finish" by replying, so the next flush is driven by an execution ending.
                    contextRef.get().logger().info("second");
                    vertx.eventBus()
                            .send(
                                    replyAddressRef.get(),
                                    DispatchEnvelope.of(
                                            Result.success(null), dev.vertique.core.eventbus.DispatchMetadata.empty()),
                                    new DeliveryOptions().setCodecName("dispatch.envelope"));
                } else if (call == 2) {
                    ctx.verify(() -> {
                        assertFalse(
                                batch.stream().anyMatch(entry -> "first".equals(entry.message())),
                                "an acked entry must never be re-sent by a later flush");
                        assertTrue(
                                batch.stream().anyMatch(entry -> "second".equals(entry.message())),
                                "the entry buffered after the first flush must still be delivered");
                    });
                    ctx.completeNow();
                }
                return Future.succeededFuture();
            });

            DelayedJobPoller poller = new DelayedJobPoller(
                    "default",
                    fastConfig(),
                    repository,
                    completionHandler,
                    Set.of(),
                    testEventBusClient(vertx),
                    0L,
                    100L,
                    DispatchEnvelopeBuilder.forTesting(),
                    noOpPropagator());

            vertx.deployVerticle(poller).onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("flushes buffered log entries on the timeout path before dead-lettering")
        void flushesOnTimeoutBeforeDeadLetter(Vertx vertx, VertxTestContext ctx) {
            String handlerAddress = "test.logflush.timeout.handler";
            // attemptNumber = 0 with maxAttempts = 1 → the timeout dead-letters instead of retrying.
            JobExecution execution = sampleExecution("log-timeout-job", handlerAddress, 1);

            when(repository.claimNextJob(anyString(), anyInt()))
                    .thenReturn(Future.succeededFuture(List.of(execution)))
                    .thenReturn(Future.succeededFuture(List.of()));
            when(repository.completeExecution(
                            any(UUID.class), eq(JobState.DEAD_LETTER), anyString(), anyString(), any()))
                    .thenReturn(Future.succeededFuture(Optional.of(execution)));

            AtomicBoolean asserted = new AtomicBoolean(false);
            when(repository.saveLogs(eq(execution.id()), any())).thenAnswer(invocation -> {
                List<LogEntry> batch = invocation.getArgument(1);
                if (asserted.compareAndSet(false, true)) {
                    ctx.verify(() -> assertTrue(
                            batch.stream().anyMatch(entry -> "before the timeout".equals(entry.message())),
                            "the timeout path must flush the buffered entry"));
                    ctx.completeNow();
                }
                return Future.succeededFuture();
            });

            // The handler logs once and never replies; with progressFlushIntervalMs = 0 there is
            // no periodic tick, so only the timeout path can produce the flush.
            AtomicBoolean logged = new AtomicBoolean(false);
            vertx.eventBus().consumer(handlerAddress, msg -> {
                if (!(msg.body() instanceof DispatchEnvelope<?> body) || !logged.compareAndSet(false, true)) {
                    return;
                }
                DefaultJobContext jobCtx =
                        (DefaultJobContext) body.metadata().dispatchContext().get(JobContext.class.getName());
                jobCtx.logger().info("before the timeout");
            });

            DelayedJobPoller poller = new DelayedJobPoller(
                    "default",
                    fastConfig(),
                    repository,
                    completionHandler,
                    Set.of(),
                    testEventBusClient(vertx),
                    300L,
                    0L,
                    DispatchEnvelopeBuilder.forTesting(),
                    noOpPropagator());

            vertx.deployVerticle(poller).onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("persists entries appended while a tick write was still in flight when the execution ended")
        void persistsEntriesAppendedDuringAnInFlightTickWrite(Vertx vertx, VertxTestContext ctx) {
            String handlerAddress = "test.logflush.inflight.handler";
            JobExecution execution = sampleExecution("log-inflight-job", handlerAddress);

            when(repository.claimNextJob(anyString(), anyInt()))
                    .thenReturn(Future.succeededFuture(List.of(execution)))
                    .thenReturn(Future.succeededFuture(List.of()));

            // The tick's saveLogs is held pending until the completion consumer is already inside
            // its ending drain. Releasing it from a runOnContext scheduled inside handleCompletion
            // is what makes that ordering deterministic without a sleep: handleCompletion runs
            // inside the completion callback, and Vert.x cannot run the queued task until that
            // callback — including the finally-block drain — has returned.
            Promise<Void> heldWrite = Promise.promise();
            when(completionHandler.handleCompletion(any(), any(), any())).thenAnswer(invocation -> {
                vertx.runOnContext(v -> heldWrite.complete());
                return Future.succeededFuture();
            });

            AtomicReference<DefaultJobContext> contextRef = new AtomicReference<>();
            AtomicReference<String> replyAddressRef = new AtomicReference<>();
            AtomicBoolean logged = new AtomicBoolean(false);
            vertx.eventBus().consumer(handlerAddress, msg -> {
                if (!(msg.body() instanceof DispatchEnvelope<?> body) || !logged.compareAndSet(false, true)) {
                    return;
                }
                DefaultJobContext jobCtx =
                        (DefaultJobContext) body.metadata().dispatchContext().get(JobContext.class.getName());
                contextRef.set(jobCtx);
                replyAddressRef.set(body.replyAddress().orElseThrow());
                jobCtx.logger().info("before the tick");
            });

            AtomicInteger writes = new AtomicInteger();
            when(repository.saveLogs(eq(execution.id()), any())).thenAnswer(invocation -> {
                List<LogEntry> batch = invocation.getArgument(1);
                int call = writes.incrementAndGet();
                if (call == 1) {
                    // The tick has claimed "before the tick" and this write is now outstanding.
                    // Append an entry that the single-flight claim cannot see, then end the
                    // execution — the window a plain ending-site flush() drops on the floor.
                    contextRef.get().logger().info("during the in-flight write");
                    vertx.eventBus()
                            .send(
                                    replyAddressRef.get(),
                                    DispatchEnvelope.of(
                                            Result.success(null), dev.vertique.core.eventbus.DispatchMetadata.empty()),
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

            DelayedJobPoller poller = new DelayedJobPoller(
                    "default",
                    fastConfig(),
                    repository,
                    completionHandler,
                    Set.of(),
                    testEventBusClient(vertx),
                    0L,
                    100L,
                    DispatchEnvelopeBuilder.forTesting(),
                    noOpPropagator());

            vertx.deployVerticle(poller).onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("stop() drains the buffered logs of an execution that is still in flight")
        void stopDrainsBufferedLogsOfInFlightExecutions(Vertx vertx, VertxTestContext ctx) {
            String handlerAddress = "test.logflush.stop.handler";
            JobExecution execution = sampleExecution("log-stop-job", handlerAddress);

            when(repository.claimNextJob(anyString(), anyInt()))
                    .thenReturn(Future.succeededFuture(List.of(execution)))
                    .thenReturn(Future.succeededFuture(List.of()));

            AtomicBoolean asserted = new AtomicBoolean(false);
            when(repository.saveLogs(eq(execution.id()), any())).thenAnswer(invocation -> {
                List<LogEntry> batch = invocation.getArgument(1);
                if (asserted.compareAndSet(false, true)) {
                    ctx.verify(() -> assertTrue(
                            batch.stream().anyMatch(entry -> "before the shutdown".equals(entry.message())),
                            "stop() must drain the buffered entry of an execution that is still in flight"));
                    ctx.completeNow();
                }
                return Future.succeededFuture();
            });

            // The handler logs and NEVER replies, so the execution is still in activeExecutions when
            // stop() runs — the precondition the "stops cleanly" lifecycle test lacks. Completing
            // this promise from inside the handler is the sleep-free ordering signal: the undeploy
            // below cannot start until the entry is already buffered.
            Promise<Void> handlerLogged = Promise.promise();
            vertx.eventBus().consumer(handlerAddress, msg -> {
                if (!(msg.body() instanceof DispatchEnvelope<?> body)) {
                    return;
                }
                DefaultJobContext jobCtx =
                        (DefaultJobContext) body.metadata().dispatchContext().get(JobContext.class.getName());
                jobCtx.logger().info("before the shutdown");
                handlerLogged.tryComplete();
            });

            // executionTimeoutMs = 0 disables the timeout path and progressFlushIntervalMs = 0
            // disables the periodic tick; the handler never replies, so the completion consumer
            // never runs either. stop() is the only site left that can reach saveLogs.
            DelayedJobPoller poller = new DelayedJobPoller(
                    "default",
                    fastConfig(),
                    repository,
                    completionHandler,
                    Set.of(),
                    testEventBusClient(vertx),
                    0L,
                    0L,
                    DispatchEnvelopeBuilder.forTesting(),
                    noOpPropagator());

            vertx.deployVerticle(poller)
                    .compose(deploymentId -> handlerLogged.future().map(deploymentId))
                    .compose(vertx::undeploy)
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("stop() completes even when the cutoff flush never settles")
        @Timeout(value = 2, unit = TimeUnit.SECONDS)
        void stopCompletesEvenWhenTheCutoffFlushNeverSettles(Vertx vertx, VertxTestContext ctx) {
            String handlerAddress = "test.logflush.stop.wedged.handler";
            JobExecution execution = sampleExecution("log-stop-wedged-job", handlerAddress);

            when(repository.claimNextJob(anyString(), anyInt()))
                    .thenReturn(Future.succeededFuture(List.of(execution)))
                    .thenReturn(Future.succeededFuture(List.of()));

            // A wedged connection pool yields a write that never settles at all. recover() cannot
            // rescue that — only a time bound can — so this pins the .timeout() on the shutdown
            // hook: without it a stuck pool would hold undeploy open far past the drain's own
            // per-write bound, which the round cap then multiplies.
            Promise<Void> neverSettles = Promise.promise();
            when(repository.saveLogs(eq(execution.id()), any())).thenReturn(neverSettles.future());

            Promise<Void> handlerLogged = Promise.promise();
            vertx.eventBus().consumer(handlerAddress, msg -> {
                if (!(msg.body() instanceof DispatchEnvelope<?> body)) {
                    return;
                }
                DefaultJobContext jobCtx =
                        (DefaultJobContext) body.metadata().dispatchContext().get(JobContext.class.getName());
                jobCtx.logger().info("never reaches the repository");
                handlerLogged.tryComplete();
            });

            DelayedJobPoller poller = new DelayedJobPoller(
                    "default",
                    fastConfig(),
                    repository,
                    completionHandler,
                    Set.of(),
                    testEventBusClient(vertx),
                    0L,
                    0L,
                    DispatchEnvelopeBuilder.forTesting(),
                    noOpPropagator(),
                    200L);

            vertx.deployVerticle(poller)
                    .compose(deploymentId -> handlerLogged.future().map(deploymentId))
                    .compose(vertx::undeploy)
                    .onSuccess(v -> ctx.completeNow())
                    .onFailure(ctx::failNow);
        }
    }
}
