// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * INTERNAL framework seam — consumed by the inbox-outbox adapters and sibling framework modules; not
 * an application contract and outside the maturity promise. Applications use {@code OutboxService},
 * {@code InboxService}, and the extension points the module document lists.
 *
 * <p>Utility class for computing outbox relay backoff delays between retry attempts.
 *
 * <p>Uses exponential backoff with random jitter to spread retry load across time and avoid
 * thundering herd when many entries fail simultaneously.
 */
public final class OutboxBackoff {

    /** Prevent instantiation. */
    private OutboxBackoff() {}

    /**
     * Computes the next available-at instant using exponential backoff with jitter.
     *
     * <p>Formula: {@code min(baseDelayMs * 2^attempt + jitter, maxDelayMs)} where jitter is
     * a random value between 0 and {@code min(computedDelay, 1000)}.
     *
     * <p>Example delays for {@code baseDelayMs=1000}, {@code maxDelayMs=300000}:
     * <ul>
     *   <li>attempt 0: ~1 000 ms + jitter</li>
     *   <li>attempt 1: ~2 000 ms + jitter</li>
     *   <li>attempt 5: ~32 000 ms + jitter</li>
     *   <li>attempt 10+: capped at 300 000 ms</li>
     * </ul>
     *
     * @param attempt      zero-based attempt counter (0 = first retry after the first failure)
     * @param baseDelayMs  base delay in milliseconds before exponential scaling
     * @param maxDelayMs   maximum delay in milliseconds regardless of attempt count
     * @return the instant at which the entry should next become eligible for relay
     */
    public static Instant computeNextAvailableAt(int attempt, long baseDelayMs, long maxDelayMs) {
        long delay = (long) Math.min(baseDelayMs * Math.pow(2, attempt), maxDelayMs);
        long jitter = ThreadLocalRandom.current().nextLong(Math.min(delay, 1_000L) + 1);
        return Instant.now().plusMillis(Math.min(delay + jitter, maxDelayMs));
    }
}
