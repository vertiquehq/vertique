// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import io.vertx.core.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Collects teardown failures so that every cleanup step is attempted and no failure is lost.
 *
 * <p>Each step runs regardless of earlier failures. The first failure becomes the primary one and
 * every later failure is attached to it as suppressed. An interrupted wait restores the calling
 * thread's interrupt status before it is recorded, and a bounded wait that times out is recorded
 * as a failure. {@link #rethrowIfAny()} then fails the teardown with the primary failure.
 */
public final class CleanupFailures {

    /** One cleanup step, which may fail. */
    @FunctionalInterface
    public interface Step {

        /**
         * Performs the step.
         *
         * @throws Exception if the step fails
         */
        void run() throws Exception;
    }

    private Throwable primary;

    /**
     * Runs {@code step}, recording its failure instead of propagating it.
     *
     * @param step the cleanup step
     */
    public void attempt(Step step) {
        try {
            step.run();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            collect(interrupted);
        } catch (Throwable failure) {
            collect(failure);
        }
    }

    /**
     * Starts an asynchronous cleanup action and waits for it, bounded by {@code timeout}, recording
     * a synchronous throw, a failed future, an interruption, or a timeout.
     *
     * @param action  starts the action and returns its completion
     * @param timeout the longest time to wait for completion
     * @param unit    the unit of {@code timeout}
     */
    public void await(Supplier<? extends Future<?>> action, long timeout, TimeUnit unit) {
        attempt(() -> action.get().toCompletionStage().toCompletableFuture().get(timeout, unit));
    }

    /**
     * Fails the teardown with the primary failure, carrying every later failure as suppressed, when
     * any step failed.
     *
     * @throws Exception the primary failure, when it is an exception
     */
    public void rethrowIfAny() throws Exception {
        if (primary == null) {
            return;
        }
        if (primary instanceof Exception exception) {
            throw exception;
        }
        if (primary instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("cleanup failed", primary);
    }

    private void collect(Throwable failure) {
        if (primary == null) {
            primary = failure;
        } else if (primary != failure) {
            primary.addSuppressed(failure);
        }
    }
}
