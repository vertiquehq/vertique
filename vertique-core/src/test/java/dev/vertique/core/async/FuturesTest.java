// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.eventbus.Result;
import io.vertx.core.Future;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Futures}.
 */
class FuturesTest {

    @Nested
    @DisplayName("toFuture()")
    class ToFuture {

        @Test
        @DisplayName("successful Result becomes succeeded future")
        void successResultBecomesSucceededFuture() {
            Future<String> future = Futures.toFuture(Result.success("ok"));

            assertTrue(future.succeeded());
            assertEquals("ok", future.result());
        }

        @Test
        @DisplayName("failed Result becomes failed future with original cause")
        void failureResultBecomesFailedFuture() {
            RuntimeException cause = new RuntimeException("boom");

            Future<String> future = Futures.toFuture(Result.failure(cause));

            assertTrue(future.failed());
            assertSame(cause, future.cause());
        }
    }

    @Nested
    @DisplayName("reflect()")
    class Reflect {

        @Test
        @DisplayName("successful future becomes Result.success")
        void successFutureBecomesSuccessResult() {
            Future<Result<String>> future = Futures.reflect(Future.succeededFuture("ok"));

            assertTrue(future.succeeded());
            assertTrue(future.result().isSuccess());
            assertEquals("ok", future.result().get());
        }

        @Test
        @DisplayName("failed future becomes Result.failure")
        void failedFutureBecomesFailureResult() {
            IllegalStateException cause = new IllegalStateException("boom");

            Future<Result<String>> future = Futures.reflect(Future.failedFuture(cause));

            assertTrue(future.succeeded());
            assertTrue(future.result().isFailure());
            assertSame(cause, future.result().cause());
        }
    }

    @Nested
    @DisplayName("settle()")
    class Settle {

        @Test
        @DisplayName("returns all outcomes in input order")
        void returnsAllOutcomesInOrder() {
            RuntimeException cause = new RuntimeException("nope");

            Future<List<Result<String>>> future = Futures.settle(List.of(
                    Future.succeededFuture("first"), Future.failedFuture(cause), Future.succeededFuture("third")));

            assertTrue(future.succeeded());
            List<Result<String>> results = future.result();
            assertEquals(3, results.size());
            assertTrue(results.get(0).isSuccess());
            assertEquals("first", results.get(0).get());
            assertTrue(results.get(1).isFailure());
            assertSame(cause, results.get(1).cause());
            assertTrue(results.get(2).isSuccess());
            assertEquals("third", results.get(2).get());
        }
    }

    @Nested
    @DisplayName("mapFailure()")
    class MapFailure {

        @Test
        @DisplayName("maps failed futures to the new cause")
        void mapsFailedFutureCause() {
            IllegalStateException original = new IllegalStateException("original");
            UnsupportedOperationException mapped = new UnsupportedOperationException("mapped");

            Future<String> future = Futures.mapFailure(Future.failedFuture(original), err -> mapped);

            assertTrue(future.failed());
            assertSame(mapped, future.cause());
        }

        @Test
        @DisplayName("leaves successful futures untouched")
        void leavesSuccessfulFutureUntouched() {
            Future<String> future =
                    Futures.mapFailure(Future.succeededFuture("ok"), err -> new RuntimeException("mapped"));

            assertTrue(future.succeeded());
            assertEquals("ok", future.result());
        }

        @Test
        @DisplayName("propagates mapper exceptions as the new failure")
        void propagatesMapperExceptions() {
            RuntimeException original = new RuntimeException("original");

            Future<String> future = Futures.mapFailure(Future.failedFuture(original), err -> {
                throw new IllegalArgumentException("mapper failed");
            });

            assertTrue(future.failed());
            assertInstanceOf(IllegalArgumentException.class, future.cause());
            assertEquals("mapper failed", future.cause().getMessage());
        }
    }
}
