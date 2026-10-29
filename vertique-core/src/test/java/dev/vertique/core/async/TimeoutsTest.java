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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(VertxExtension.class)
class TimeoutsTest {

    @Nested
    @DisplayName("awaitCondition()")
    class AwaitCondition {

        @Test
        @DisplayName("returns immediately when the condition is already true")
        void succeedsImmediately(Vertx vertx) {
            Future<Void> result = Timeouts.awaitCondition(vertx, () -> true, 1L, 20L, "drain");

            assertTrue(result.succeeded());
        }

        @Test
        @DisplayName("completes when the condition becomes true")
        void succeedsWhenConditionChanges(Vertx vertx) throws Exception {
            AtomicInteger checks = new AtomicInteger();

            Timeouts.awaitCondition(vertx, () -> checks.incrementAndGet() > 1, 10L, 5_000L, "drain")
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(6, TimeUnit.SECONDS);

            assertTrue(checks.get() > 1, "condition should be polled after its initial check");
        }

        @Test
        @DisplayName("times out while the condition remains false")
        void timesOut(Vertx vertx) throws Exception {
            Throwable failure = awaitFailure(Timeouts.awaitCondition(vertx, () -> false, 10L, 50L, "record drain"));

            assertInstanceOf(TimeoutException.class, failure);
            assertTrue(failure.getMessage().contains("record drain"));
        }

        @Test
        @DisplayName("propagates a condition failure")
        void propagatesConditionFailure(Vertx vertx) throws Exception {
            IllegalStateException cause = new IllegalStateException("condition failed");

            assertSame(
                    cause,
                    awaitFailure(Timeouts.awaitCondition(
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
        void preservesSuccess() throws Exception {
            Promise<String> operation = Promise.promise();
            Future<String> result = Timeouts.withTimeout(operation.future(), 500L, "close");
            operation.complete("closed");

            assertEquals(
                    "closed", result.toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("preserves an operation failure")
        void preservesFailure() throws Exception {
            RuntimeException cause = new RuntimeException("close failed");

            assertSame(cause, awaitFailure(Timeouts.withTimeout(Future.failedFuture(cause), 500L, "close")));
        }

        @Test
        @DisplayName("fails when an operation exceeds its deadline and ignores late completion")
        void timesOutAndIgnoresLateCompletion() throws Exception {
            Promise<Void> operation = Promise.promise();
            Future<Void> result = Timeouts.withTimeout(operation.future(), 10L, "consumer close");

            Throwable failure = awaitFailure(result);
            assertInstanceOf(TimeoutException.class, failure);
            assertTrue(failure.getMessage().contains("consumer close"));

            operation.complete();
            assertInstanceOf(TimeoutException.class, awaitFailure(result));
        }

        @Test
        @DisplayName("rejects a non-positive timeout")
        void rejectsNonPositiveTimeout() {
            assertThrows(
                    IllegalArgumentException.class, () -> Timeouts.withTimeout(Future.succeededFuture(), 0L, "close"));
        }
    }

    private static Throwable awaitFailure(Future<?> future) throws Exception {
        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        return failure.getCause();
    }
}
