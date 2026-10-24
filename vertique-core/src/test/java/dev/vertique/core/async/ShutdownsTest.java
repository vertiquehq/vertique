// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(VertxExtension.class)
class ShutdownsTest {

    @Nested
    @DisplayName("awaitCondition()")
    class AwaitCondition {

        @Test
        @DisplayName("returns immediately when the condition is already true")
        void succeedsImmediately(Vertx vertx) {
            Future<Void> result = Shutdowns.awaitCondition(vertx, () -> true, 1L, 20L, "drain");

            assertTrue(result.succeeded());
        }

        @Test
        @DisplayName("completes when the condition becomes true")
        void succeedsWhenConditionChanges(Vertx vertx) throws Exception {
            AtomicBoolean ready = new AtomicBoolean();
            vertx.setTimer(10L, id -> ready.set(true));

            Shutdowns.awaitCondition(vertx, ready::get, 1L, 500L, "drain")
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(1, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("times out while the condition remains false")
        void timesOut(Vertx vertx) throws Exception {
            AtomicInteger checks = new AtomicInteger();
            Throwable failure = awaitFailure(Shutdowns.awaitCondition(
                    vertx,
                    () -> {
                        checks.incrementAndGet();
                        return false;
                    },
                    1L,
                    10L,
                    "record drain"));

            assertInstanceOf(TimeoutException.class, failure);
            assertTrue(failure.getMessage().contains("record drain"));
            assertTrue(checks.get() > 1, "condition should be polled until the deadline");
        }

        @Test
        @DisplayName("propagates a condition failure")
        void propagatesConditionFailure(Vertx vertx) throws Exception {
            IllegalStateException cause = new IllegalStateException("condition failed");

            assertSame(
                    cause,
                    awaitFailure(Shutdowns.awaitCondition(
                            vertx,
                            () -> {
                                throw cause;
                            },
                            1L,
                            100L,
                            "drain")));
        }
    }

    @Nested
    @DisplayName("withTimeout()")
    class WithTimeout {

        @Test
        @DisplayName("preserves a successful operation result")
        void preservesSuccess(Vertx vertx) throws Exception {
            Promise<String> operation = Promise.promise();
            Future<String> result = Shutdowns.withTimeout(vertx, operation.future(), 500L, "close");
            operation.complete("closed");

            assertEquals(
                    "closed", result.toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("preserves an operation failure")
        void preservesFailure(Vertx vertx) throws Exception {
            RuntimeException cause = new RuntimeException("close failed");

            assertSame(cause, awaitFailure(Shutdowns.withTimeout(vertx, Future.failedFuture(cause), 500L, "close")));
        }

        @Test
        @DisplayName("fails when an operation exceeds its deadline and ignores late completion")
        void timesOutAndIgnoresLateCompletion(Vertx vertx) throws Exception {
            Promise<Void> operation = Promise.promise();
            Future<Void> result = Shutdowns.withTimeout(vertx, operation.future(), 10L, "consumer close");

            Throwable failure = awaitFailure(result);
            assertInstanceOf(TimeoutException.class, failure);
            assertTrue(failure.getMessage().contains("consumer close"));

            operation.complete();
            assertInstanceOf(TimeoutException.class, awaitFailure(result));
        }

        @Test
        @DisplayName("rejects a non-positive timeout")
        void rejectsNonPositiveTimeout(Vertx vertx) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Shutdowns.withTimeout(vertx, Future.succeededFuture(), 0L, "close"));
        }
    }

    private static Throwable awaitFailure(Future<?> future) throws Exception {
        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> future.toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS));
        return failure.getCause();
    }
}
