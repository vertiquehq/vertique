// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.resilience;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Strategy for computing the delay between retry attempts.
 *
 * <p>Implementations compute a delay in milliseconds given the 0-based retry count. The strategy
 * is decoupled from retry eligibility — whether to retry is determined by {@link RetryPolicy}.
 *
 * <p>Built-in factories:
 *
 * <ul>
 *   <li>{@link #exponential(long, double, long)} — exponential backoff with jitter (default)</li>
 *   <li>{@link #fixed(long)} — constant delay every retry</li>
 *   <li>{@link #none()} — no delay between retries</li>
 * </ul>
 *
 * <p>Custom strategies are provided as classes with a no-arg constructor and referenced from
 * {@link Retry#backoff()}.
 *
 * <pre>{@code
 * public class AggressiveBackoff implements BackoffStrategy {
 *     @Override
 *     public long delay(int retryCount) {
 *         return Math.min(100L * (long) Math.pow(3, retryCount), 5_000L);
 *     }
 * }
 *
 * @Retry(maxRetries = 5, backoff = AggressiveBackoff.class)
 * @GET Future<User> getUser(@PathParam("id") String id);
 * }</pre>
 */
@FunctionalInterface
public interface BackoffStrategy {

    /**
     * Computes the delay before the next retry attempt.
     *
     * @param retryCount the 0-based retry count (0 = evaluating delay before first retry)
     * @return delay in milliseconds; {@code 0} means retry immediately
     */
    long delay(int retryCount);

    // --- Built-in factories ---

    /**
     * Creates an exponential backoff strategy with random jitter.
     *
     * <p>Delay formula: {@code min(delayMs × multiplier^retryCount, maxDelayMs) + jitter}, where
     * jitter is a random value in {@code [0, min(delay, 1000))} milliseconds.
     *
     * @param delayMs the base delay in milliseconds for the first retry (retryCount = 0)
     * @param multiplier the exponential growth factor; {@code 1.0} produces fixed delay
     * @param maxDelayMs the upper bound on the delay before jitter is added
     * @return an exponential backoff {@link BackoffStrategy}
     */
    static BackoffStrategy exponential(long delayMs, double multiplier, long maxDelayMs) {
        return retryCount -> {
            double raw = delayMs * Math.pow(multiplier, retryCount);
            long capped = Math.min((long) raw, maxDelayMs);
            long jitter = (long) (ThreadLocalRandom.current().nextDouble() * Math.min(capped, 1000L));
            return capped + jitter;
        };
    }

    /**
     * Creates a fixed-delay backoff strategy that always returns the same delay.
     *
     * @param delayMs the constant delay in milliseconds between retries
     * @return a fixed backoff {@link BackoffStrategy}
     */
    static BackoffStrategy fixed(long delayMs) {
        return retryCount -> delayMs;
    }

    /**
     * Creates a no-delay backoff strategy that retries immediately.
     *
     * @return a zero-delay {@link BackoffStrategy}
     */
    static BackoffStrategy none() {
        return retryCount -> 0L;
    }

    // --- Sentinel ---

    /**
     * Sentinel class used as the default value of {@link Retry#backoff()}.
     *
     * <p>When the scanner sees this class as the backoff, it means "use the builder-level strategy"
     * — the sentinel is never instantiated. Attempting to call {@link #delay} throws
     * {@link UnsupportedOperationException}.
     */
    final class Default implements BackoffStrategy {

        private Default() {
            throw new UnsupportedOperationException(
                    "BackoffStrategy.Default is a sentinel class and must not be instantiated");
        }

        /**
         * Always throws — this sentinel class is never instantiated.
         *
         * @param retryCount unused
         * @return never returns
         * @throws UnsupportedOperationException always
         */
        @Override
        public long delay(int retryCount) {
            throw new UnsupportedOperationException("BackoffStrategy.Default is a sentinel class");
        }
    }
}
