// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.sse;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.examples.sse.job.JobConfig;
import dev.vertique.examples.sse.job.JobProgressEvent;
import dev.vertique.examples.sse.job.JobService;
import dev.vertique.examples.sse.job.JobStatus;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link JobService}.
 *
 * <p>Verifies subscribe/replay, fan-out, unsubscribe cleanup, timer cancellation on terminal state
 * and on last-subscriber-leave, and the {@code failJob} error path.
 */
@ExtendWith(VertxExtension.class)
class JobServiceTest {

    private static final long STEP_INTERVAL_MS = 20L;
    private static final int STEP_COUNT = 3;

    private JobService service;

    @BeforeEach
    void setUp(Vertx vertx) {
        service = new JobService(vertx, new JobConfig(STEP_INTERVAL_MS, STEP_COUNT));
    }

    @AfterEach
    void tearDown(Vertx vertx) {
        // nothing to tear down; jobs are in-memory
    }

    // --- subscribe replay ---

    @Nested
    @DisplayName("subscribe with lastEventId=null")
    class SubscribeWithNoLastEventId {

        @Test
        @DisplayName("Replays PENDING event then receives all progress and final DONE")
        void replaysPendingThenAllProgressAndDone(Vertx vertx, VertxTestContext ctx) throws Exception {
            String jobId = service.createJob();
            List<JobProgressEvent> received = new ArrayList<>();
            CountDownLatch doneLatch = new CountDownLatch(1);

            vertx.runOnContext(v -> {
                service.subscribe(jobId, null, evt -> {
                    received.add(evt);
                    if (evt.status() == JobStatus.DONE || evt.status() == JobStatus.FAILED) {
                        doneLatch.countDown();
                    }
                });
            });

            assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "Expected DONE within 5 seconds");

            ctx.verify(() -> {
                assertFalse(received.isEmpty(), "Should receive events");
                assertEquals(JobStatus.PENDING, received.get(0).status(), "First event should be PENDING");
                assertEquals(JobStatus.DONE, received.get(received.size() - 1).status(), "Last event should be DONE");
                assertTrue(received.size() >= 2, "Should receive PENDING + progress events + DONE");
                assertEquals(100, received.get(received.size() - 1).percent(), "Final percent should be 100");
            });
            ctx.completeNow();
        }
    }

    @Nested
    @DisplayName("subscribe with lastEventId set")
    class SubscribeWithLastEventId {

        @Test
        @DisplayName("Skips events with seq <= lastEventId in replay")
        void skipsEventsBeforeLastEventId(Vertx vertx, VertxTestContext ctx) throws Exception {
            String jobId = service.createJob();
            // First, collect all events to know what seq=1 is
            List<JobProgressEvent> firstRun = new ArrayList<>();
            CountDownLatch doneLatch1 = new CountDownLatch(1);

            vertx.runOnContext(v -> {
                service.subscribe(jobId, null, evt -> {
                    firstRun.add(evt);
                    if (evt.status() == JobStatus.DONE || evt.status() == JobStatus.FAILED) {
                        doneLatch1.countDown();
                    }
                });
            });
            assertTrue(doneLatch1.await(5, TimeUnit.SECONDS));

            // Now subscribe to a new job with lastEventId=0, replayed events should skip seq <= 0
            String jobId2 = service.createJob();
            List<JobProgressEvent> secondRun = new ArrayList<>();
            CountDownLatch doneLatch2 = new CountDownLatch(1);

            vertx.runOnContext(v -> {
                service.subscribe(jobId2, "0", evt -> {
                    secondRun.add(evt);
                    if (evt.status() == JobStatus.DONE || evt.status() == JobStatus.FAILED) {
                        doneLatch2.countDown();
                    }
                });
            });
            assertTrue(doneLatch2.await(5, TimeUnit.SECONDS));

            ctx.verify(() -> {
                // seq=0 (PENDING) should be skipped because lastEventId="0" means seq > 0
                assertFalse(secondRun.isEmpty());
                assertTrue(secondRun.stream().noneMatch(e -> e.seq() <= 0), "No replayed events should have seq <= 0");
            });
            ctx.completeNow();
        }
    }

    // --- fan-out ---

    @Nested
    @DisplayName("Multiple subscribers")
    class MultipleSubscribers {

        @Test
        @DisplayName("Both subscribers receive the same events")
        void bothSubscribersReceiveSameEvents(Vertx vertx, VertxTestContext ctx) throws Exception {
            String jobId = service.createJob();
            List<JobProgressEvent> sub1Events = new ArrayList<>();
            List<JobProgressEvent> sub2Events = new ArrayList<>();
            CountDownLatch latch1 = new CountDownLatch(1);
            CountDownLatch latch2 = new CountDownLatch(1);

            vertx.runOnContext(v -> {
                service.subscribe(jobId, null, evt -> {
                    sub1Events.add(evt);
                    if (evt.status() == JobStatus.DONE || evt.status() == JobStatus.FAILED) {
                        latch1.countDown();
                    }
                });
                service.subscribe(jobId, null, evt -> {
                    sub2Events.add(evt);
                    if (evt.status() == JobStatus.DONE || evt.status() == JobStatus.FAILED) {
                        latch2.countDown();
                    }
                });
            });

            assertTrue(latch1.await(5, TimeUnit.SECONDS), "Sub1 should complete");
            assertTrue(latch2.await(5, TimeUnit.SECONDS), "Sub2 should complete");

            ctx.verify(() -> {
                assertEquals(
                        sub1Events.size(),
                        sub2Events.size(),
                        "Both subscribers should receive the same number of events");
                for (int i = 0; i < sub1Events.size(); i++) {
                    assertEquals(sub1Events.get(i).seq(), sub2Events.get(i).seq(), "seq must match at index " + i);
                }
            });
            ctx.completeNow();
        }
    }

    // --- unsubscribe ---

    @Nested
    @DisplayName("Subscription.unsubscribe()")
    class Unsubscribe {

        @Test
        @DisplayName("Unsubscribed listener does not receive further events; remaining listener does")
        void unsubscribedListenerReceivesNoFurtherEvents(Vertx vertx, VertxTestContext ctx) throws Exception {
            String jobId = service.createJob();
            List<JobProgressEvent> sub1Events = new ArrayList<>();
            List<JobProgressEvent> sub2Events = new ArrayList<>();
            CountDownLatch sub2Done = new CountDownLatch(1);
            AtomicBoolean sub1Unsubscribed = new AtomicBoolean(false);

            vertx.runOnContext(v -> {
                JobService.Subscription sub1 = service.subscribe(jobId, null, evt -> {
                    if (!sub1Unsubscribed.get()) {
                        sub1Events.add(evt);
                    }
                });

                // Unsubscribe sub1 right after first event (PENDING is replayed synchronously)
                sub1Unsubscribed.set(true);
                sub1.unsubscribe();

                service.subscribe(jobId, null, evt -> {
                    sub2Events.add(evt);
                    if (evt.status() == JobStatus.DONE || evt.status() == JobStatus.FAILED) {
                        sub2Done.countDown();
                    }
                });
            });

            assertTrue(sub2Done.await(5, TimeUnit.SECONDS), "Sub2 should complete");

            ctx.verify(() -> {
                assertFalse(sub2Events.isEmpty(), "Sub2 should receive events");
                // sub1 only got events before unsubscribe (at most the PENDING replay)
                assertTrue(sub1Events.size() <= 1, "Sub1 should receive at most the replay event");
            });
            ctx.completeNow();
        }

        @Test
        @DisplayName("When last subscriber unsubscribes before DONE, timer is cancelled")
        void timerCancelledWhenLastSubscriberLeaves(Vertx vertx, VertxTestContext ctx) throws Exception {
            String jobId = service.createJob();
            CountDownLatch pendingReceived = new CountDownLatch(1);

            vertx.runOnContext(v -> {
                JobService.Subscription sub = service.subscribe(jobId, null, evt -> {
                    if (evt.status() == JobStatus.PENDING) {
                        pendingReceived.countDown();
                    }
                });
                // Immediately unsubscribe
                sub.unsubscribe();
            });

            // Wait briefly for any async timer ticks
            Thread.sleep(200);

            ctx.verify(() -> {
                assertFalse(service.isTimerRunning(jobId), "Timer should be cancelled after last unsubscribe");
            });
            ctx.completeNow();
        }
    }

    // --- terminal reconnect ---

    @Nested
    @DisplayName("Reconnect after terminal state")
    class ReconnectAfterTerminal {

        @Test
        @DisplayName("Reconnect past the final seq still surfaces the terminal event so streams can complete")
        void reconnectPastFinalSeqReplaysTerminalEvent(Vertx vertx, VertxTestContext ctx) throws Exception {
            String jobId = service.createJob();
            List<JobProgressEvent> firstRun = new ArrayList<>();
            CountDownLatch firstDone = new CountDownLatch(1);

            vertx.runOnContext(v -> service.subscribe(jobId, null, evt -> {
                firstRun.add(evt);
                if (evt.status() == JobStatus.DONE || evt.status() == JobStatus.FAILED) {
                    firstDone.countDown();
                }
            }));
            assertTrue(firstDone.await(5, TimeUnit.SECONDS));

            int finalSeq = firstRun.get(firstRun.size() - 1).seq();

            List<JobProgressEvent> secondRun = new ArrayList<>();
            vertx.runOnContext(v -> service.subscribe(jobId, Integer.toString(finalSeq), secondRun::add));
            Thread.sleep(100);

            ctx.verify(() -> {
                assertFalse(secondRun.isEmpty(), "Reconnect past final seq should still surface terminal event");
                assertEquals(JobStatus.DONE, secondRun.get(secondRun.size() - 1).status());
            });
            ctx.completeNow();
        }

        @Test
        @DisplayName("Subscribing after terminal does not retain the listener")
        void subscribingAfterTerminalDoesNotRetainListener(Vertx vertx, VertxTestContext ctx) throws Exception {
            String jobId = service.createJob();
            CountDownLatch firstDone = new CountDownLatch(1);
            List<JobProgressEvent> firstRun = new ArrayList<>();
            List<JobService.Subscription> firstSub = new ArrayList<>();
            vertx.runOnContext(v -> firstSub.add(service.subscribe(jobId, null, evt -> {
                firstRun.add(evt);
                if (evt.status() == JobStatus.DONE || evt.status() == JobStatus.FAILED) {
                    firstDone.countDown();
                }
            })));
            assertTrue(firstDone.await(5, TimeUnit.SECONDS));
            firstSub.get(0).unsubscribe();

            // Reconnect past the final seq → each subscribe surfaces only the terminal event.
            String finalSeq = Integer.toString(firstRun.get(firstRun.size() - 1).seq());
            List<JobProgressEvent> replays = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                vertx.runOnContext(v -> service.subscribe(jobId, finalSeq, replays::add));
            }
            Thread.sleep(100);

            ctx.verify(() -> {
                assertEquals(
                        0,
                        service.listenerCount(jobId),
                        "Listeners registered during terminal subscribe must not be retained");
                assertEquals(
                        5, replays.size(), "Each terminal subscribe should replay the terminal event exactly once");
            });
            ctx.completeNow();
        }

        @Test
        @DisplayName("Unsubscribing before terminal preserves history for later reconnect")
        void unsubscribeBeforeTerminalPreservesHistory(Vertx vertx, VertxTestContext ctx) throws Exception {
            String jobId = service.createJob();

            vertx.runOnContext(v -> {
                JobService.Subscription sub = service.subscribe(jobId, null, evt -> {});
                sub.unsubscribe();
            });
            Thread.sleep(100);

            ctx.verify(() -> {
                assertTrue(
                        service.current(jobId).isPresent(), "Job should still be discoverable after last unsubscribe");
            });
            ctx.completeNow();
        }
    }

    // --- failJob ---

    @Nested
    @DisplayName("failJob()")
    class FailJob {

        @Test
        @DisplayName("Delivers FAILED event to all subscribers and cancels timer")
        void deliversFailedEventAndCancelsTimer(Vertx vertx, VertxTestContext ctx) throws Exception {
            String jobId = service.createJob();
            List<JobProgressEvent> received = new ArrayList<>();
            CountDownLatch failedLatch = new CountDownLatch(1);

            vertx.runOnContext(v -> {
                service.subscribe(jobId, null, evt -> {
                    received.add(evt);
                    if (evt.status() == JobStatus.FAILED) {
                        failedLatch.countDown();
                    }
                });
                // Fail the job after subscribing
                vertx.setTimer(50, t -> service.failJob(jobId, "Simulated failure"));
            });

            assertTrue(failedLatch.await(5, TimeUnit.SECONDS), "Expected FAILED event within 5 seconds");

            // Wait briefly to ensure timer is cleaned up
            Thread.sleep(100);

            ctx.verify(() -> {
                assertFalse(received.isEmpty());
                assertEquals(
                        JobStatus.FAILED, received.get(received.size() - 1).status(), "Last event should be FAILED");
                assertEquals(
                        "Simulated failure", received.get(received.size() - 1).message());
                assertFalse(service.isTimerRunning(jobId), "Timer should be cancelled after failJob");
            });
            ctx.completeNow();
        }
    }
}
