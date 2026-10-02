// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.support;

import io.vertx.core.Future;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

/**
 * Teardown steps that are all attempted and whose failures are all reported. Steps run when the
 * cleanup is closed, last registered first, as nested try-with-resources would close them. Every
 * step runs even when an earlier one failed or was interrupted. The first failure is thrown once every
 * step ran, with each later failure added to it as suppressed. An interrupt, pending or raised by a
 * step, is held back while the remaining steps run and restored on the thread before the cleanup
 * returns or throws.
 *
 * <p>Open it in try-with-resources around the test body, so a failure of the body keeps its place as
 * the test's failure and carries the cleanup failures as suppressed:
 *
 * <pre>{@code
 * Vertx owned = Vertx.vertx();
 * try (Cleanup cleanup = new Cleanup()) {
 *     cleanup.await("close the test-owned Vert.x instance", owned::close, CLOSE_BOUND);
 *     String id = Futures.await(owned.deployVerticle(verticle), DEPLOY_BOUND);
 *     cleanup.await("undeploy " + id, () -> owned.undeploy(id), CLOSE_BOUND);
 *     // the test body
 * }
 * }</pre>
 */
public final class Cleanup implements AutoCloseable {

    /** One teardown step. */
    @FunctionalInterface
    public interface Step {

        /**
         * Runs the step.
         *
         * @throws Exception when the step fails
         */
        void run() throws Exception;
    }

    private record Named(String description, Step step) {}

    private final Deque<Named> steps = new ArrayDeque<>();

    /**
     * Registers a step.
     *
     * @param description what the step does, named in its failure
     * @param step the step
     * @return this cleanup
     */
    public Cleanup step(String description, Step step) {
        steps.push(new Named(description, step));
        return this;
    }

    /**
     * Registers a step that starts an asynchronous action and waits for it within a bound. A failure
     * or a timeout of the action fails the step.
     *
     * @param description what the step does, named in its failure
     * @param action starts the action when the step runs
     * @param bound the longest the step waits for the action
     * @return this cleanup
     */
    public Cleanup await(String description, Supplier<? extends Future<?>> action, Duration bound) {
        return step(description, () -> Futures.await(action.get(), bound));
    }

    /**
     * Runs every registered step, last registered first, then reports their failures.
     *
     * @throws AssertionError when a step failed or timed out: the first failure, with every later one
     *     suppressed
     */
    @Override
    public void close() {
        AssertionError failure = null;
        boolean interrupted = false;
        while (!steps.isEmpty()) {
            Named named = steps.pop();
            // A pending interrupt would fail every later wait at once; it is restored after the last step.
            interrupted |= Thread.interrupted();
            try {
                named.step().run();
            } catch (Throwable stepFailure) {
                // Futures.await restores the flag it was interrupted with; clear it for the next step.
                boolean flagRestored = Thread.interrupted();
                interrupted |= flagRestored || stepFailure instanceof InterruptedException;
                AssertionError reported =
                        new AssertionError("teardown step failed: " + named.description(), stepFailure);
                if (failure == null) {
                    failure = reported;
                } else {
                    failure.addSuppressed(reported);
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failure != null) {
            throw failure;
        }
    }
}
