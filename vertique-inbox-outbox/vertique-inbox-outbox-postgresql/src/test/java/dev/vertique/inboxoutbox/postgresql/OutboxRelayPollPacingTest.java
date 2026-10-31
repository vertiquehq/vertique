// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.inboxoutbox.OutboxEntryState;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.inboxoutbox.OutboxRecord;
import dev.vertique.inboxoutbox.OutboxRelayConfig;
import dev.vertique.inboxoutbox.OutboxRepository;
import dev.vertique.inboxoutbox.RelayCapabilities;
import dev.vertique.inboxoutbox.RelayStrategy;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
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
 * Tests the poll pacing of the deployed {@link OutboxRelay}: a claim that fills the capacity it asked
 * for is followed by another almost at once, a freed slot asks for a pass when the relay could not
 * take all the work it found, a claim that comes back short or empty keeps the interval and its
 * backoff, and there is never more than one claim in flight.
 *
 * <p>The relay is deployed as a real verticle against a mocked {@link OutboxRepository}. The polling
 * interval is long compared with the time each test allows for an early pass, so a second claim made
 * inside that window can only have been asked for, never waited for. A negative check (no second claim
 * yet) only gets more reliable on a slow host.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class OutboxRelayPollPacingTest {

    private static final String NODE = "pacing-node";
    private static final long INTERVAL_MS = 2_000L;

    @Mock
    OutboxRepository outboxRepository;

    /** Records and scripts the claims the relay makes. */
    private final ClaimLog claims = new ClaimLog();

    Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        when(outboxRepository.markPublished(anyLong(), anyString())).thenReturn(Future.succeededFuture(true));
        when(outboxRepository.claimBatch(anyInt(), anyString(), any()))
                .thenAnswer(invocation -> claims.next(invocation));
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Nested
    @DisplayName("delayAfterEmptyClaim")
    class EmptyClaimBackoff {

        @Test
        @DisplayName("doubles the delay up to four times the base interval")
        void doublesUpToFourTimesTheBase() {
            assertEquals(200L, OutboxRelay.delayAfterEmptyClaim(100L, 100L));
            assertEquals(400L, OutboxRelay.delayAfterEmptyClaim(200L, 100L));
            assertEquals(400L, OutboxRelay.delayAfterEmptyClaim(400L, 100L));
        }

        @Test
        @DisplayName("never exceeds sixty seconds")
        void neverExceedsSixtySeconds() {
            assertEquals(60_000L, OutboxRelay.delayAfterEmptyClaim(30_000L, 30_000L));
            assertEquals(60_000L, OutboxRelay.delayAfterEmptyClaim(60_000L, 60_000L));
        }

        @Test
        @DisplayName("the default 100 ms interval backs off 200, 400, 400 ms")
        void defaultIntervalSequence() {
            long delay = 100L;
            List<Long> seen = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                delay = OutboxRelay.delayAfterEmptyClaim(delay, 100L);
                seen.add(delay);
            }
            assertEquals(List.of(200L, 400L, 400L, 400L), seen);
        }
    }

    @Nested
    @DisplayName("draining a backlog")
    class Draining {

        @Test
        @DisplayName("a claim that fills the capacity it asked for is followed by another at once")
        void fullClaimIsFollowedByAnotherAtOnce() throws Exception {
            claims.script(List.of(record(1L), record(2L)), List.of(record(3L), record(4L)), List.of());

            deployRelay(2, INTERVAL_MS, envelope -> Future.succeededFuture(OutboxPublishResult.success()));

            claims.await(3, 15_000L);
            assertEquals(List.of(2, 2, 2), claims.asked());
            assertTrue(claims.gapMs(0, 1) < INTERVAL_MS / 2, "second claim waited " + claims.gapMs(0, 1) + " ms");
            assertTrue(claims.gapMs(1, 2) < INTERVAL_MS / 2, "third claim waited " + claims.gapMs(1, 2) + " ms");
            verify(outboxRepository, timeout(5_000L)).markPublished(4L, NODE);
        }

        @Test
        @DisplayName("a claim that comes back short keeps the interval")
        void shortClaimKeepsTheInterval() throws Exception {
            claims.script(List.of(record(1L)), List.of());

            deployRelay(4, INTERVAL_MS, envelope -> Future.succeededFuture(OutboxPublishResult.success()));

            claims.await(2, 15_000L);
            assertTrue(
                    claims.gapMs(0, 1) >= INTERVAL_MS - 100L,
                    "second claim came after " + claims.gapMs(0, 1) + " ms, before the interval");
        }

        @Test
        @DisplayName("empty claims back off: the delay doubles and does not reset")
        void emptyClaimsBackOff() throws Exception {
            claims.script(List.of());

            deployRelay(4, 200L, envelope -> Future.succeededFuture(OutboxPublishResult.success()));

            claims.await(4, 15_000L);
            // 200 ms base: the first empty claim doubles the delay to 400 ms, the second to 800 ms (the cap)
            assertTrue(claims.gapMs(0, 1) >= 380L, "gap after first empty claim: " + claims.gapMs(0, 1));
            assertTrue(claims.gapMs(1, 2) >= 780L, "gap after second empty claim: " + claims.gapMs(1, 2));
            assertTrue(claims.gapMs(2, 3) >= 780L, "gap after third empty claim: " + claims.gapMs(2, 3));
        }

        @Test
        @DisplayName("a failed claim keeps the interval")
        void failedClaimKeepsTheInterval() throws Exception {
            claims.scriptFailure(new IllegalStateException("pool closed"));

            deployRelay(4, INTERVAL_MS, envelope -> Future.succeededFuture(OutboxPublishResult.success()));

            claims.await(2, 15_000L);
            assertTrue(
                    claims.gapMs(0, 1) >= INTERVAL_MS - 100L, "second claim came after " + claims.gapMs(0, 1) + " ms");
        }

        @Test
        @DisplayName("with no free slot nothing is claimed, and a freed slot asks for the pass")
        void freedSlotAsksForThePass() throws Exception {
            List<Promise<OutboxPublishResult>> held = Collections.synchronizedList(new ArrayList<>());
            claims.script(List.of(record(1L), record(2L)), List.of(record(3L)), List.of());

            deployRelay(2, INTERVAL_MS, envelope -> {
                Promise<OutboxPublishResult> promise = Promise.promise();
                held.add(promise);
                return promise.future();
            });

            claims.await(1, 15_000L);
            awaitHeld(held, 2);
            // Both slots are held: nothing is claimed while they are.
            Thread.sleep(500L);
            assertEquals(1, claims.count());

            long completedAt = System.nanoTime();
            held.get(0).complete(OutboxPublishResult.success());

            claims.await(2, 15_000L);
            // One slot came back: the pass asks for exactly one row, well before the next interval.
            assertEquals(List.of(2, 1), claims.asked());
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(claims.timeNanos(1) - completedAt);
            assertTrue(waitedMs < INTERVAL_MS / 2, "freed slot waited " + waitedMs + " ms for the pass");
        }

        @Test
        @DisplayName("a release after a short claim does not ask for a pass")
        void releaseAfterShortClaimDoesNotAskForAPass() throws Exception {
            List<Promise<OutboxPublishResult>> held = Collections.synchronizedList(new ArrayList<>());
            claims.script(List.of(record(1L)), List.of());

            deployRelay(4, INTERVAL_MS, envelope -> {
                Promise<OutboxPublishResult> promise = Promise.promise();
                held.add(promise);
                return promise.future();
            });

            claims.await(1, 15_000L);
            awaitHeld(held, 1);
            held.get(0).complete(OutboxPublishResult.success());

            verify(outboxRepository, timeout(5_000L)).markPublished(1L, NODE);
            Thread.sleep(500L);
            assertEquals(1, claims.count());
        }

        @Test
        @DisplayName("claims never overlap while a backlog drains")
        void claimsNeverOverlap() throws Exception {
            AtomicInteger inFlightClaims = new AtomicInteger();
            AtomicInteger maxInFlightClaims = new AtomicInteger();
            doAnswer(invocation -> {
                        int now = inFlightClaims.incrementAndGet();
                        maxInFlightClaims.accumulateAndGet(now, Math::max);
                        int asked = invocation.getArgument(0);
                        int number = claims.record(asked);
                        List<OutboxRecord> rows = new ArrayList<>();
                        if (number <= 20) {
                            for (int i = 0; i < asked; i++) {
                                rows.add(record(number * 100L + i));
                            }
                        }
                        Promise<List<OutboxRecord>> settled = Promise.promise();
                        // the claim takes a few milliseconds, as a query does
                        vertx.setTimer(5L, id -> {
                            inFlightClaims.decrementAndGet();
                            settled.complete(rows);
                        });
                        return settled.future();
                    })
                    .when(outboxRepository)
                    .claimBatch(anyInt(), anyString(), any());

            deployRelay(4, 200L, envelope -> {
                Promise<OutboxPublishResult> promise = Promise.promise();
                vertx.setTimer(2L, id -> promise.complete(OutboxPublishResult.success()));
                return promise.future();
            });

            claims.await(22, 20_000L);
            assertEquals(1, maxInFlightClaims.get(), "a second claim started while one was in flight");
        }
    }

    @Nested
    @DisplayName("a NOTIFY wake-up")
    class Notification {

        @Test
        @DisplayName("during a claim does not overlap it and is answered by a pass when the claim comes back empty")
        void notifyDuringClaimIsAnsweredWithoutOverlap() throws Exception {
            Promise<List<OutboxRecord>> firstClaim = Promise.promise();
            AtomicInteger calls = new AtomicInteger();
            doAnswer(invocation -> {
                        int number = claims.record(invocation.getArgument(0));
                        calls.incrementAndGet();
                        return switch (number) {
                            case 1 -> firstClaim.future();
                            case 2 -> Future.succeededFuture(List.of(record(7L)));
                            default -> Future.succeededFuture(List.<OutboxRecord>of());
                        };
                    })
                    .when(outboxRepository)
                    .claimBatch(anyInt(), anyString(), any());

            ProbeRelay relay =
                    deployRelay(4, INTERVAL_MS, envelope -> Future.succeededFuture(OutboxPublishResult.success()));
            claims.await(1, 15_000L);

            runOnRelayContext(relay, relay::onNotification);

            // The first claim is still outstanding: the notification must not start a second one.
            Thread.sleep(500L);
            assertEquals(1, claims.count());

            long completedAt = System.nanoTime();
            firstClaim.complete(List.of());

            // The claim may have missed the row the notification announced: one pass follows at once.
            claims.await(2, 15_000L);
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(claims.timeNanos(1) - completedAt);
            assertTrue(waitedMs < INTERVAL_MS / 2, "the follow-up pass waited " + waitedMs + " ms");
            verify(outboxRepository, timeout(5_000L)).markPublished(7L, NODE);
        }

        @Test
        @DisplayName("while idle asks for a pass at once")
        void notifyWhileIdleAsksForAPass() throws Exception {
            claims.script(List.of(), List.of(record(9L)), List.of());

            ProbeRelay relay =
                    deployRelay(4, INTERVAL_MS, envelope -> Future.succeededFuture(OutboxPublishResult.success()));
            claims.await(1, 15_000L);
            // let the first claim settle and the interval timer be armed
            Thread.sleep(100L);

            long notifiedAt = System.nanoTime();
            runOnRelayContext(relay, relay::onNotification);

            claims.await(2, 15_000L);
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(claims.timeNanos(1) - notifiedAt);
            assertTrue(waitedMs < INTERVAL_MS / 2, "the notified pass waited " + waitedMs + " ms");
            verify(outboxRepository, timeout(5_000L)).markPublished(9L, NODE);
        }
    }

    // --- Helpers ---

    private static void awaitHeld(List<Promise<OutboxPublishResult>> held, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (held.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertEquals(count, held.size(), "publish calls the handler received");
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
        final void script(List<OutboxRecord>... results) {
            script = new ArrayList<>(List.of((Object[]) results));
        }

        void scriptFailure(Throwable failure) {
            script = List.of(failure);
        }

        int record(int capacity) {
            asked.add(capacity);
            times.add(System.nanoTime());
            return asked.size();
        }

        @SuppressWarnings("unchecked")
        Future<List<OutboxRecord>> next(org.mockito.invocation.InvocationOnMock invocation) {
            int number = record(invocation.getArgument(0));
            Object step = script.get(Math.min(number, script.size()) - 1);
            if (step instanceof Throwable failure) {
                return Future.failedFuture(failure);
            }
            return Future.succeededFuture((List<OutboxRecord>) step);
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

        void await(int count, long timeoutMs) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (asked.size() < count && System.nanoTime() < deadline) {
                Thread.sleep(5L);
            }
            assertTrue(asked.size() >= count, "claims made within " + timeoutMs + " ms: " + asked.size());
        }
    }

    private static void runOnRelayContext(ProbeRelay relay, Runnable action) {
        relay.relayContext().runOnContext(v -> action.run());
    }

    /** The relay under test, exposing the context it runs on so a test can act on it like a callback would. */
    private static final class ProbeRelay extends OutboxRelay {
        ProbeRelay(
                OutboxRelayConfig config,
                OutboxRepository repository,
                Map<DestinationType, OutboxDestinationHandler> handlers,
                RelayCapabilities capabilities,
                String nodeId) {
            super(config, repository, handlers, capabilities, null, nodeId);
        }

        Context relayContext() {
            return context;
        }
    }

    private ProbeRelay deployRelay(
            int batchSize, long pollingIntervalMs, Function<OutboxEnvelope, Future<OutboxPublishResult>> publish)
            throws Exception {
        OutboxDestinationHandler handler = new OutboxDestinationHandler() {
            @Override
            public DestinationType destinationType() {
                return DestinationType.SERVICE;
            }

            @Override
            public ClaimScope claimScope() {
                return ClaimScope.all();
            }

            @Override
            public Future<OutboxPublishResult> publish(OutboxEnvelope envelope) {
                return publish.apply(envelope);
            }
        };
        Map<DestinationType, OutboxDestinationHandler> handlers = Map.of(DestinationType.SERVICE, handler);
        OutboxRelayConfig config = OutboxRelayConfig.builder()
                .pollingIntervalMs(pollingIntervalMs)
                .batchSize(batchSize)
                .leaseTimeoutMs(30_000L)
                .maxAttempts(3)
                .backoffBaseDelayMs(1_000L)
                .backoffMaxDelayMs(60_000L)
                .strategy(RelayStrategy.POLLING)
                .build();
        ProbeRelay relay =
                new ProbeRelay(config, outboxRepository, handlers, OutboxRelay.deriveCapabilities(handlers), NODE);
        vertx.deployVerticle(relay).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        return relay;
    }

    private static OutboxRecord record(long id) {
        return new OutboxRecord(
                id,
                UUID.randomUUID(),
                "Order",
                "order-" + id,
                "order.placed",
                "my-service",
                DestinationType.SERVICE,
                new JsonObject().put("orderId", id),
                Map.of(),
                OutboxMetadata.empty(),
                null,
                Instant.now(),
                OutboxEntryState.PROCESSING,
                0,
                3,
                Instant.now(),
                NODE,
                null,
                null,
                null,
                Instant.now(),
                Instant.now());
    }
}
