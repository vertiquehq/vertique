// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.Future;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/**
 * External deep-review finding 2 (T021 W2 — third round): a two-step {@code
 * register(fence)}-then-invoke sequence carries no live guarantee once {@code register}'s own
 * critical section releases the lifecycle's monitor — a concurrent {@link
 * RateLimiterLifecycle#close()} that becomes fully observable strictly between those two steps
 * could still let the action run.
 *
 * <p>T018 originally closed this gap by holding the lifecycle's monitor across <em>both</em>
 * registration and invocation. A third external deep review found that fix serialized every LOCAL
 * backend admission across every policy this runtime resolves a handle for, since {@code
 * LocalBucket4jRateLimitBackend#consume} runs synchronously inside that critical section (one
 * shared {@link RateLimiterLifecycle} monitor per {@link RateLimiters} runtime, not per policy).
 *
 * <p>T021 W2 replaces lock-hold with a per-registration CAS ({@code REGISTERED -> INVOKING} raced
 * against {@code close()}'s {@code REGISTERED -> FENCED}): registration still happens under the
 * monitor, but the monitor is released <em>before</em> the action ever runs. This test proves the
 * gap the CAS must close is real and is actually closed — deterministically, with no sleeps —
 * using {@link RateLimiterLifecycle}'s package-private test-seam overload of {@code
 * registerAndInvoke}, whose extra hook runs exactly in the gap between registration and the CAS
 * attempt. A {@link CountDownLatch}-gated closer thread lands squarely in that gap and is joined
 * (so it has fully completed, via {@code close()}'s own synchronous return) before this test
 * inspects whether the action ran — no timing assumption, just happens-before from {@code
 * Thread#join()}.
 */
class RateLimiterLifecycleRegistrationInvocationRaceTest {

    @Test
    void shouldNeverInvokeTheActionWhenCloseLandsBetweenRegistrationAndTheInvocationCas() throws InterruptedException {
        RateLimiterLifecycle lifecycle = new RateLimiterLifecycle();
        boolean[] actionInvoked = {false};
        boolean[] guardedPromiseFenced = {false};

        Future<String> result = lifecycle.registerAndInvoke(
                () -> guardedPromiseFenced[0] = true,
                () -> {
                    actionInvoked[0] = true;
                    return Future.succeededFuture("should never run");
                },
                // Runs after registration releases the monitor, before this call's own CAS attempt
                // — landing close() here is exactly the gap the old two-step design left open.
                () -> {
                    Thread closer = new Thread(lifecycle::close);
                    closer.start();
                    try {
                        closer.join();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                });

        assertThat(result)
                .as("registerAndInvoke must report the same 'already closed' signal a pre-registration "
                        + "close observes, once close has won the race for this registration")
                .isNull();
        assertThat(actionInvoked[0])
                .as("close() won the CAS race for this registration (REGISTERED -> FENCED), so the "
                        + "registering call's own REGISTERED -> INVOKING CAS must have failed -- the action "
                        + "must never have been invoked at all")
                .isFalse();
        assertThat(guardedPromiseFenced[0])
                .as("close() must still have run this registration's fence, force-failing the caller's "
                        + "guarded promise, even though the action never started")
                .isTrue();
        assertThat(lifecycle.isClosed())
                .as("the closer thread was joined before this assertion -- close() has fully completed")
                .isTrue();
    }
}
