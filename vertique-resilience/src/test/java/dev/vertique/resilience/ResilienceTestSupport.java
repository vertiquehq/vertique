// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Shared deterministic waiting and context-control helpers for the resilience unit tests. */
final class ResilienceTestSupport {

    static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);

    private ResilienceTestSupport() {}

    static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    static Throwable awaitFailure(Future<?> future) throws Exception {
        try {
            await(future);
        } catch (ExecutionException failure) {
            return failure.getCause();
        }
        throw new AssertionError("expected failure");
    }

    /** Polls an observable condition up to a generous deadline. */
    static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within " + AWAIT_TIMEOUT);
            }
            Thread.sleep(2L);
        }
    }

    /** Returns once every task queued on the context before this call has run. */
    static void flush(Context context) throws Exception {
        Promise<Void> reached = Promise.promise();
        context.runOnContext(ignored -> reached.complete());
        await(reached.future());
    }

    /**
     * Occupies the context with a task that waits for {@link ContextBlock#release()}, so that work
     * queued afterwards from another thread runs only once the test lets it.
     */
    static ContextBlock block(Context context) throws InterruptedException {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        context.runOnContext(ignored -> {
            blocked.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        if (!blocked.await(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new AssertionError("the context was not occupied within " + AWAIT_TIMEOUT);
        }
        return new ContextBlock(release);
    }

    /** Waits until the referent has been garbage collected. */
    static boolean awaitCollected(WeakReference<?> reference) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (reference.get() != null) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            System.gc();
            Thread.sleep(10L);
        }
        return true;
    }

    /** A held context; closing it releases the context. */
    static final class ContextBlock implements AutoCloseable {
        private final CountDownLatch release;

        private ContextBlock(CountDownLatch release) {
            this.release = release;
        }

        void release() {
            release.countDown();
        }

        @Override
        public void close() {
            release();
        }
    }
}
