// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.context.ContextScopeBinder;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.DispatchMetadata;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.eventbus.EventBusExceptionMapper;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.core.eventbus.Result;
import dev.vertique.job.JobCompletionHandler;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobRepository;
import dev.vertique.job.JobState;
import dev.vertique.job.JobType;
import dev.vertique.job.ProgressSnapshot;
import dev.vertique.job.delayed.config.DelayedJobQueueConfig;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.junit5.VertxExtension;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Tests the poll pacing of {@link DelayedJobPoller}: a claim that fills the capacity it asked for is
 * followed by another almost at once, a freed slot asks for a pass when the last cycle could not take
 * all the work it found, a claim that comes back short or empty keeps the interval and its backoff,
 * and there is never more than one claim in flight.
 *
 * <p>The sleep delay is long compared with the time each test allows for an early pass, so a second
 * claim made inside that window can only have been asked for, never waited for. A negative check (no
 * second claim yet) only gets more reliable on a slow host.
 */
@DisplayName("DelayedJobPoller pacing")
@ExtendWith({VertxExtension.class, MockitoExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class DelayedJobPollerPacingTest {

    private static final String HANDLER = "test.pacing.handler";
    private static final long SLEEP_MS = 2_000L;

    @Mock
    JobRepository repository;

    @Mock
    JobCompletionHandler completionHandler;

    /** Records and scripts the claims the poller makes. */
    private final ClaimLog claims = new ClaimLog();

    /** Reply addresses of the dispatches the handler has received and not yet answered. */
    private final List<String> held = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp(Vertx vertx) {
        try {
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.envelope"));
        } catch (IllegalStateException ignored) {
            // Already registered
        }
        when(completionHandler.handleCompletion(any(), any(), any())).thenReturn(Future.succeededFuture());
        when(repository.claimNextJob(anyString(), anyInt())).thenAnswer(invocation -> claims.next(invocation));
    }

    @Nested
    @DisplayName("delayAfterEmptyClaim")
    class EmptyClaimBackoff {

        @Test
        @DisplayName("doubles the delay up to four times the base interval")
        void doublesUpToFourTimesTheBase() {
            assertEquals(1_000L, DelayedJobPoller.delayAfterEmptyClaim(500L, 500L));
            assertEquals(2_000L, DelayedJobPoller.delayAfterEmptyClaim(1_000L, 500L));
            assertEquals(2_000L, DelayedJobPoller.delayAfterEmptyClaim(2_000L, 500L));
        }

        @Test
        @DisplayName("never exceeds sixty seconds")
        void neverExceedsSixtySeconds() {
            assertEquals(60_000L, DelayedJobPoller.delayAfterEmptyClaim(30_000L, 30_000L));
            assertEquals(60_000L, DelayedJobPoller.delayAfterEmptyClaim(60_000L, 60_000L));
        }

        @Test
        @DisplayName("the default 1 s interval backs off 2, 4, 4 seconds")
        void defaultIntervalSequence() {
            long delay = 1_000L;
            List<Long> seen = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                delay = DelayedJobPoller.delayAfterEmptyClaim(delay, 1_000L);
                seen.add(delay);
            }
            assertEquals(List.of(2_000L, 4_000L, 4_000L, 4_000L), seen);
        }
    }

    @Nested
    @DisplayName("draining a backlog")
    class Draining {

        @Test
        @DisplayName("a claim that fills the capacity it asked for is followed by another at once")
        void fullClaimIsFollowedByAnotherAtOnce(Vertx vertx) {
            claims.script(List.of(execution("a"), execution("b")), List.of(execution("c"), execution("d")), List.of());

            answerEveryDispatchAtOnce(vertx);
            deploy(vertx, 2, SLEEP_MS);

            claims.await(3, 15_000L);
            assertEquals(List.of(2, 2, 2), claims.asked());
            assertTrue(claims.gapMs(0, 1) < SLEEP_MS / 2, "second claim waited " + claims.gapMs(0, 1) + " ms");
            assertTrue(claims.gapMs(1, 2) < SLEEP_MS / 2, "third claim waited " + claims.gapMs(1, 2) + " ms");
        }

        @Test
        @DisplayName("a claim that comes back short keeps the interval")
        void shortClaimKeepsTheInterval(Vertx vertx) {
            claims.script(List.of(execution("a")), List.of());

            answerEveryDispatchAtOnce(vertx);
            deploy(vertx, 4, SLEEP_MS);

            claims.await(2, 15_000L);
            assertTrue(
                    claims.gapMs(0, 1) >= SLEEP_MS - 100L,
                    "second claim came after " + claims.gapMs(0, 1) + " ms, before the interval");
        }

        @Test
        @DisplayName("empty claims back off: the delay doubles and does not reset")
        void emptyClaimsBackOff(Vertx vertx) {
            claims.script(List.of());

            deploy(vertx, 4, 200L);

            claims.await(4, 15_000L);
            // 200 ms base: the first empty claim doubles the delay to 400 ms, the second to 800 ms (the cap)
            assertTrue(claims.gapMs(0, 1) >= 380L, "gap after first empty claim: " + claims.gapMs(0, 1));
            assertTrue(claims.gapMs(1, 2) >= 780L, "gap after second empty claim: " + claims.gapMs(1, 2));
            assertTrue(claims.gapMs(2, 3) >= 780L, "gap after third empty claim: " + claims.gapMs(2, 3));
        }

        @Test
        @DisplayName("a failed claim keeps the interval")
        void failedClaimKeepsTheInterval(Vertx vertx) {
            claims.scriptFailure(new IllegalStateException("pool closed"));

            deploy(vertx, 4, SLEEP_MS);

            claims.await(2, 15_000L);
            assertTrue(claims.gapMs(0, 1) >= SLEEP_MS - 100L, "second claim came after " + claims.gapMs(0, 1) + " ms");
        }

        @Test
        @DisplayName("a claim that throws synchronously keeps polling at the interval")
        void throwingClaimKeepsPolling(Vertx vertx) {
            claims.scriptThrow(new IllegalStateException("pool closed"));

            deploy(vertx, 4, 200L);

            claims.await(3, 15_000L);
            assertTrue(claims.gapMs(0, 1) >= 180L, "second claim came after " + claims.gapMs(0, 1) + " ms");
        }

        @Test
        @DisplayName("with no free slot nothing is claimed, and a freed slot asks for the pass")
        void freedSlotAsksForThePass(Vertx vertx) throws Exception {
            claims.script(List.of(execution("a"), execution("b")), List.of(execution("c")), List.of());

            holdEveryDispatch(vertx);
            deploy(vertx, 2, SLEEP_MS);

            claims.await(1, 15_000L);
            awaitHeld(2);
            // Both slots are held: nothing is claimed while they are.
            Thread.sleep(500L);
            assertEquals(1, claims.count());

            long repliedAt = System.nanoTime();
            reply(vertx, held.get(0));

            claims.await(2, 15_000L);
            // One slot came back: the pass asks for exactly one job, well before the next interval.
            assertEquals(List.of(2, 1), claims.asked());
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(claims.timeNanos(1) - repliedAt);
            assertTrue(waitedMs < SLEEP_MS / 2, "freed slot waited " + waitedMs + " ms for the pass");
        }

        @Test
        @DisplayName("a release after a short claim does not ask for a pass")
        void releaseAfterShortClaimDoesNotAskForAPass(Vertx vertx) throws Exception {
            claims.script(List.of(execution("a")), List.of());

            holdEveryDispatch(vertx);
            deploy(vertx, 4, SLEEP_MS);

            claims.await(1, 15_000L);
            awaitHeld(1);
            reply(vertx, held.get(0));

            verify(completionHandler, timeout(5_000L)).handleCompletion(any(), any(), any());
            Thread.sleep(500L);
            assertEquals(1, claims.count());
        }

        @Test
        @DisplayName("claims never overlap while a backlog drains")
        void claimsNeverOverlap(Vertx vertx) {
            AtomicInteger inFlightClaims = new AtomicInteger();
            AtomicInteger maxInFlightClaims = new AtomicInteger();
            doAnswer(invocation -> {
                        int now = inFlightClaims.incrementAndGet();
                        maxInFlightClaims.accumulateAndGet(now, Math::max);
                        int number = claims.record(invocation.getArgument(1));
                        int asked = invocation.getArgument(1);
                        List<JobExecution> rows = new ArrayList<>();
                        if (number <= 20) {
                            for (int i = 0; i < asked; i++) {
                                rows.add(execution("job-" + number + "-" + i));
                            }
                        }
                        Promise<List<JobExecution>> settled = Promise.promise();
                        // the claim takes a few milliseconds, as a query does
                        vertx.setTimer(5L, id -> {
                            inFlightClaims.decrementAndGet();
                            settled.complete(rows);
                        });
                        return settled.future();
                    })
                    .when(repository)
                    .claimNextJob(anyString(), anyInt());

            answerEveryDispatchAtOnce(vertx);
            deploy(vertx, 4, 200L);

            claims.await(22, 20_000L);
            assertEquals(1, maxInFlightClaims.get(), "a second claim started while one was in flight");
        }
    }

    // --- Helpers ---

    private DelayedJobPoller deploy(Vertx vertx, int maxConcurrentJobs, long sleepDelayMs) {
        DelayedJobQueueConfig config = DelayedJobQueueConfig.builder()
                .sleepDelayMs(sleepDelayMs)
                .maxConcurrentJobs(maxConcurrentJobs)
                .backoffStrategy("LINEAR")
                .backoffBaseDelayMs(1_000L)
                .backoffMaxDelayMs(60_000L)
                .build();
        DefaultContextHolder holder = new DefaultContextHolder();
        DelayedJobPoller poller = new DelayedJobPoller(
                "default",
                config,
                repository,
                completionHandler,
                Set.of(),
                new EventBusClient(vertx, new EventBusExceptionMapper()),
                DispatchEnvelopeBuilder.forTesting(),
                new DurableContextPropagator(
                        new DurableContextMetadataRegistry(Set.of(), Set.of()),
                        holder,
                        new ContextScopeBinder(holder)));
        vertx.deployVerticle(poller).toCompletionStage().toCompletableFuture().join();
        return poller;
    }

    /** A handler that answers every dispatch with a success at once. */
    private void answerEveryDispatchAtOnce(Vertx vertx) {
        vertx.eventBus().consumer(HANDLER, msg -> {
            if (msg.body() instanceof DispatchEnvelope<?> body
                    && body.replyAddress().isPresent()) {
                reply(vertx, body.replyAddress().orElseThrow());
            }
        });
    }

    /** A handler that keeps every dispatch open until the test answers it. */
    private void holdEveryDispatch(Vertx vertx) {
        vertx.eventBus().consumer(HANDLER, msg -> {
            if (msg.body() instanceof DispatchEnvelope<?> body
                    && body.replyAddress().isPresent()) {
                held.add(body.replyAddress().orElseThrow());
            }
        });
    }

    private void awaitHeld(int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (held.size() < count && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        assertEquals(count, held.size(), "dispatches the handler received");
    }

    /**
     * Records every claim with its capacity and time, and plays a script of results: each claim takes
     * the next entry and the last entry repeats.
     */
    private static final class ClaimLog {
        private final List<Long> times = Collections.synchronizedList(new ArrayList<>());
        private final List<Integer> asked = Collections.synchronizedList(new ArrayList<>());
        private volatile List<Object> script = List.of(List.of());

        @SafeVarargs
        final void script(List<JobExecution>... results) {
            script = new ArrayList<>(List.of((Object[]) results));
        }

        void scriptFailure(Throwable failure) {
            script = List.of(failure);
        }

        void scriptThrow(RuntimeException failure) {
            script = List.of(failure, List.of());
        }

        int record(int capacity) {
            asked.add(capacity);
            times.add(System.nanoTime());
            return asked.size();
        }

        @SuppressWarnings("unchecked")
        Future<List<JobExecution>> next(org.mockito.invocation.InvocationOnMock invocation) {
            int number = record(invocation.getArgument(1));
            Object step = script.get(Math.min(number, script.size()) - 1);
            if (step instanceof RuntimeException thrown) {
                throw thrown;
            }
            if (step instanceof Throwable failure) {
                return Future.failedFuture(failure);
            }
            return Future.succeededFuture((List<JobExecution>) step);
        }

        int count() {
            return asked.size();
        }

        List<Integer> asked() {
            synchronized (asked) {
                return List.copyOf(asked);
            }
        }

        long timeNanos(int index) {
            return times.get(index);
        }

        long gapMs(int from, int to) {
            return TimeUnit.NANOSECONDS.toMillis(times.get(to) - times.get(from));
        }

        void await(int count, long timeoutMs) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (asked.size() < count && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            assertTrue(asked.size() >= count, "claims made within " + timeoutMs + " ms: " + asked.size());
        }
    }

    private static void reply(Vertx vertx, String replyAddress) {
        vertx.eventBus()
                .send(
                        replyAddress,
                        DispatchEnvelope.of(Result.success(null), DispatchMetadata.empty()),
                        new DeliveryOptions().setCodecName("dispatch.envelope"));
    }

    private static JobExecution execution(String jobId) {
        return new JobExecution(
                UUID.randomUUID(),
                jobId,
                JobType.DELAYED,
                HANDLER,
                "default",
                JobState.PROCESSING,
                0,
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
    }
}
