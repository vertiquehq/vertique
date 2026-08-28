// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class RedisDeadlineCompatibilityTest {

    @Test
    void preservesFenceDurationContextRaceAndLateCompletion() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> RedisDeadline.withDeadline(vertx, Future.succeededFuture("ignored"), Duration.ZERO));

            Future<String> overflow = RedisDeadline.withDeadline(
                    vertx, Future.succeededFuture("backend-first"), Duration.ofSeconds(Long.MAX_VALUE));
            assertEquals(
                    "backend-first",
                    overflow.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS));

            Promise<String> backendFirst = Promise.promise();
            backendFirst.complete("backend-first");
            AtomicReference<Boolean> backendFirstOnEventLoop = new AtomicReference<>();
            CountDownLatch backendFirstSettled = new CountDownLatch(1);
            AtomicReference<Future<String>> backendFirstResult = new AtomicReference<>();
            vertx.runOnContext(ignored -> {
                Future<String> result = RedisDeadline.withDeadline(vertx, backendFirst.future(), Duration.ofSeconds(1));
                backendFirstResult.set(result);
                result.onComplete(completion -> {
                    backendFirstOnEventLoop.set(Context.isOnEventLoopThread());
                    backendFirstSettled.countDown();
                });
            });
            assertTrue(backendFirstSettled.await(2, TimeUnit.SECONDS));
            assertEquals(
                    "backend-first",
                    backendFirstResult
                            .get()
                            .toCompletionStage()
                            .toCompletableFuture()
                            .get(2, TimeUnit.SECONDS));
            assertTrue(Boolean.TRUE.equals(backendFirstOnEventLoop.get()));

            Promise<String> backend = Promise.promise();
            AtomicInteger completionCount = new AtomicInteger();
            Future<String> deadlineResult = RedisDeadline.withDeadline(vertx, backend.future(), Duration.ofNanos(1));
            deadlineResult.onComplete(ignored -> completionCount.incrementAndGet());
            ExecutionException failure = assertThrows(ExecutionException.class, () -> deadlineResult
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof TimeoutException);
            assertEquals("Redis operation exceeded deadline", failure.getCause().getMessage());
            assertFalse(backend.future().isComplete());
            assertTrue(backend.tryComplete("late-backend"));
            CountDownLatch lateCompletionObserved = new CountDownLatch(1);
            vertx.setTimer(50, ignored -> lateCompletionObserved.countDown());
            assertTrue(lateCompletionObserved.await(2, TimeUnit.SECONDS));
            assertEquals(1, completionCount.get());
            assertTrue(deadlineResult.failed());
            assertTrue(deadlineResult.cause() instanceof TimeoutException);
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }
}
