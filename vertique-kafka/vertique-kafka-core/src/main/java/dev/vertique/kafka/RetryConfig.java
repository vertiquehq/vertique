// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

/**
 * Configuration for the {@link ErrorStrategy#RETRY} error handling strategy.
 *
 * <p>Controls how many times a failing record is retried before falling back to
 * the exhausted strategy (typically {@link ErrorStrategy#DEAD_LETTER}).
 *
 * @param maxRetries maximum number of retry attempts per record
 * @param backoffMs initial backoff delay in milliseconds
 * @param backoffMultiplier multiplier applied to backoff on each subsequent retry
 * @param exhaustedStrategy fallback strategy when retries are exhausted (must not be {@link ErrorStrategy#RETRY})
 * @param maxBackoffMs upper cap on the computed backoff delay in milliseconds; prevents unbounded growth
 */
public record RetryConfig(
        int maxRetries, long backoffMs, double backoffMultiplier, ErrorStrategy exhaustedStrategy, long maxBackoffMs) {

    /** Default retry configuration: 3 retries, 1s initial backoff, 2x multiplier, 60s cap, DLQ on exhaustion. */
    public static final RetryConfig DEFAULT = new RetryConfig(3, 1000L, 2.0, ErrorStrategy.DEAD_LETTER, 60_000L);

    /**
     * Creates a retry config with validation.
     *
     * @param maxRetries maximum number of retry attempts per record
     * @param backoffMs initial backoff delay in milliseconds
     * @param backoffMultiplier multiplier applied to backoff on each subsequent retry
     * @param exhaustedStrategy fallback strategy when retries are exhausted
     * @param maxBackoffMs maximum backoff delay cap in milliseconds
     */
    public RetryConfig {
        if (maxRetries < 1) {
            throw new IllegalArgumentException("maxRetries must be >= 1, got " + maxRetries);
        }
        if (backoffMs < 0) {
            throw new IllegalArgumentException("backoffMs must be >= 0, got " + backoffMs);
        }
        if (backoffMultiplier < 1.0) {
            throw new IllegalArgumentException("backoffMultiplier must be >= 1.0, got " + backoffMultiplier);
        }
        if (exhaustedStrategy == ErrorStrategy.RETRY) {
            throw new IllegalArgumentException("exhaustedStrategy must not be RETRY (would cause infinite recursion)");
        }
        if (maxBackoffMs < backoffMs) {
            throw new IllegalArgumentException(
                    "maxBackoffMs must be >= backoffMs, got maxBackoffMs=" + maxBackoffMs + " backoffMs=" + backoffMs);
        }
    }

    /**
     * Computes the backoff delay for a given retry attempt, capped at {@link #maxBackoffMs()}.
     *
     * @param retryCount the current retry attempt (0-based)
     * @return the delay in milliseconds, never exceeding {@code maxBackoffMs}
     */
    public long delayMs(int retryCount) {
        long computed = (long) (backoffMs * Math.pow(backoffMultiplier, retryCount));
        return Math.min(computed, maxBackoffMs);
    }
}
