// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.Future;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/**
 * External deep-review finding 2: the two-step sequence {@code RateLimiter#runFenced} used to
 * follow — {@code lifecycle.register(fence)} returning a non-null go-ahead, then <em>separately</em>
 * invoking the guarded action — carries no live guarantee once {@code register}'s own critical
 * section releases the lifecycle's monitor. A concurrent {@link RateLimiterLifecycle#close()} that
 * becomes fully observable strictly between those two steps could still let the action run, since
 * nothing re-validates {@code closed} between them.
 *
 * <p>{@link RateLimiterLifecycle#registerAndInvoke(Runnable, java.util.function.Supplier)} closes
 * this gap by holding the lifecycle's own monitor — the same one {@link
 * RateLimiterLifecycle#close()} synchronizes on — across <em>both</em> the closed-check/
 * registration step and the invocation of {@code action} itself. This is proven deterministically,
 * with no sleeps: while {@code action} is running (i.e. while this call still holds the monitor), a
 * concurrent thread's {@code close()} call is provably blocked from ever setting {@code
 * closed=true} — mutual exclusion on the shared monitor, not timing, is what makes this
 * deterministic. A {@link CountDownLatch} only confirms the closer thread has actually attempted
 * the call; the monitor itself is what prevents it from completing early.
 *
 * <p>Red evidence for this exact gap was captured separately, with the (temporary,
 * fully-reverted) two-step implementation of {@code registerAndInvoke} instrumented with a hook
 * firing deterministically between its {@code register()} and {@code action.get()} calls — proving
 * that a {@code close()} forced to run (and fully complete, via {@code Thread#join()}) inside that
 * exact gap still let the action run. That hook and its temporary call site never landed; this
 * test proves the landed, atomic implementation closes the gap structurally instead.
 */
class RateLimiterLifecycleRegistrationInvocationRaceTest {

    @Test
    void shouldKeepTheRuntimeOpenWhileAnInFlightRegisterAndInvokeActionStillHoldsTheSharedMonitor()
            throws InterruptedException {
        RateLimiterLifecycle lifecycle = new RateLimiterLifecycle();
        CountDownLatch closerAttempted = new CountDownLatch(1);
        boolean[] observedClosedDuringAction = {true};
        Thread[] closerThread = new Thread[1];

        Future<String> result = lifecycle.registerAndInvoke(() -> {}, () -> {
            closerThread[0] = new Thread(() -> {
                closerAttempted.countDown();
                lifecycle.close();
            });
            closerThread[0].start();
            try {
                closerAttempted.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }

            // Whether or not the closer thread has actually reached close()'s own synchronized
            // section yet, it cannot possibly have completed it: THIS thread is still holding the
            // exact same monitor, right now, inside this very action -- so close() cannot have set
            // closed=true. No sleep, no timeout, no probabilistic race: this is a hard guarantee
            // from mutual exclusion on the shared monitor, which is exactly what makes the fix
            // (holding that monitor across registration AND invocation) close the original gap.
            observedClosedDuringAction[0] = lifecycle.isClosed();
            return Future.succeededFuture("action-result");
        });

        assertThat(observedClosedDuringAction[0])
                .as("close() must be provably unable to complete while this atomic registerAndInvoke call still "
                        + "holds the lifecycle's shared monitor, running its action")
                .isFalse();
        assertThat(result.result())
                .as("the action still ran to completion and its result is delivered normally")
                .isEqualTo("action-result");

        closerThread[0].join();
        assertThat(lifecycle.isClosed())
                .as("once the atomic call releases the monitor, the waiting close() call proceeds and completes")
                .isTrue();
    }
}
