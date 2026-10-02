// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.support;

import io.vertx.core.Future;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded waits on Vert.x futures from a test thread. */
public final class Futures {

    private Futures() {}

    /**
     * Waits for a future within a bound. A failure or a timeout fails the test with its cause; an
     * interrupted wait restores the thread's interrupt flag and rethrows.
     *
     * @param future the future to wait for
     * @param bound the longest the wait may take
     * @param <T> the result type
     * @return the future's result
     * @throws InterruptedException when the waiting thread is interrupted
     */
    public static <T> T await(Future<T> future, Duration bound) throws InterruptedException {
        try {
            return future.toCompletionStage().toCompletableFuture().get(bound.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException failed) {
            throw new AssertionError("the future failed: " + failed.getCause(), failed.getCause());
        } catch (TimeoutException incomplete) {
            throw new AssertionError("the future did not complete within " + bound, incomplete);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }
}
