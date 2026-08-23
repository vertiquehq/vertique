// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Defines the deadline seam contract expected by Redis provider infrastructure. */
class RedisApiSpikeTest {
    private static final Duration DEADLINE = Duration.ofMillis(25);
    private static final long LATE_COMPLETION_MS = 100;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(2);

    @Test
    @DisplayName("deadline failure settles once and ignores late backend completion")
    void deadlineWrapperHasDefinedLateCompletionBehavior() throws Exception {
        // Given: a backend that remains controllable after the deadline has elapsed.
        Vertx vertx = Vertx.vertx();
        List<Long> timerIds = new ArrayList<>();
        Promise<String> backend = Promise.promise();
        CountDownLatch setupComplete = new CountDownLatch(1);
        CountDownLatch returnedFutureSettled = new CountDownLatch(1);
        CountDownLatch lateBackendCompleted = new CountDownLatch(1);
        AtomicReference<Future<String>> returnedFuture = new AtomicReference<>();
        AtomicInteger completionCount = new AtomicInteger();
        AtomicBoolean settledOnEventLoop = new AtomicBoolean();
        AtomicBoolean lateBackendTryCompleteResult = new AtomicBoolean();

        try {
            // When: the missing production seam is installed on an event-loop context.
            vertx.runOnContext(ignored -> {
                Future<String> deadlineFuture = RedisDeadline.withDeadline(vertx, backend.future(), DEADLINE);
                returnedFuture.set(deadlineFuture);
                deadlineFuture.onComplete(result -> {
                    settledOnEventLoop.set(Context.isOnEventLoopThread());
                    completionCount.incrementAndGet();
                    returnedFutureSettled.countDown();
                });
                timerIds.add(vertx.setTimer(LATE_COMPLETION_MS, ignoredTimer -> {
                    lateBackendTryCompleteResult.set(backend.tryComplete("late backend value"));
                    lateBackendCompleted.countDown();
                }));
                setupComplete.countDown();
            });

            assertTrue(setupComplete.await(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            assertTrue(
                    returnedFutureSettled.await(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "the deadline must settle the returned future");

            // Then: the deadline failure settles once on the event loop.
            assertEquals(1, completionCount.get(), "the deadline must settle the returned future once");
            assertTrue(settledOnEventLoop.get(), "deadline settlement must run on the event loop");
            assertInstanceOf(TimeoutException.class, returnedFuture.get().cause());

            // Completing the backend late is allowed; this test makes no claim about cancellation propagation.
            assertTrue(lateBackendCompleted.await(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            assertTrue(lateBackendTryCompleteResult.get(), "the backend must remain independently completable");
            assertEquals(1, completionCount.get(), "late backend completion must not settle the returned future again");
            assertTrue(returnedFuture.get().failed());
            assertInstanceOf(TimeoutException.class, returnedFuture.get().cause());
        } finally {
            timerIds.forEach(vertx::cancelTimer);
            vertx.close().toCompletionStage().toCompletableFuture().get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
