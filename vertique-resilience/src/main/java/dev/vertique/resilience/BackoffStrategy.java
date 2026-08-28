// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Strategy for computing the delay between retry attempts.
 *
 * <p>Implementations compute a delay in milliseconds given the 0-based retry count. The strategy
 * is decoupled from retry eligibility — whether to retry is determined by {@link RetryPolicy}.
 *
 * <p>Built-in factories are provided for exponential, fixed, and no-delay strategies. Custom
 * strategies are referenced from {@link dev.vertique.resilience.annotation.Retry#backoff()}.
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

    /**
     * Creates an exponential backoff strategy with random jitter.
     *
     * <p>The capped exponential delay is increased by a random value in
     * {@code [0, min(cappedDelay, 1000))} milliseconds.
     *
     * @param delayMs the base delay in milliseconds for the first retry
     * @param multiplier the exponential growth factor; {@code 1.0} produces fixed delay
     * @param maxDelayMs the upper bound on the delay before jitter is added
     * @return an exponential backoff strategy
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
     * Creates a fixed-delay backoff strategy.
     *
     * @param delayMs the constant delay in milliseconds
     * @return a fixed backoff strategy
     */
    static BackoffStrategy fixed(long delayMs) {
        return retryCount -> delayMs;
    }

    /**
     * Creates a no-delay backoff strategy.
     *
     * @return a zero-delay backoff strategy
     */
    static BackoffStrategy none() {
        return retryCount -> 0L;
    }

    /** Sentinel class used as the default value of {@link dev.vertique.resilience.annotation.Retry#backoff()}. */
    final class Default implements BackoffStrategy {

        private Default() {
            throw new UnsupportedOperationException(
                    "BackoffStrategy.Default is a sentinel class and must not be instantiated");
        }

        /**
         * Always throws because this sentinel is never instantiated.
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
