// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/** Immutable, validated retry-delay policy. */
public sealed interface RetryBackoff permits RetryBackoff.Fixed, RetryBackoff.Exponential, RetryBackoff.Custom {

    /**
     * Computes the delay before the next retry attempt.
     *
     * <p>The retry count is zero-based: {@code 0} computes the delay before the first retry.
     * Exponential policies add bounded random jitter after applying their cap.
     *
     * @param retryCount the non-negative, zero-based retry count
     * @return the delay in milliseconds
     * @throws IllegalArgumentException if {@code retryCount} is negative
     */
    default long delayMs(int retryCount) {
        return delayMs(retryCount, ThreadLocalRandom.current()::nextDouble);
    }

    /**
     * Computes the delay using a caller-supplied random source for exponential jitter.
     *
     * <p>The source is sampled only when the selected exponential policy has a non-zero jitter
     * bound. The runtime uses this overload to preserve its injectable randomness seam.
     *
     * @param retryCount the non-negative, zero-based retry count
     * @param randomSource source returning values in {@code [0, 1)}
     * @return the delay in milliseconds
     * @throws IllegalArgumentException if {@code retryCount} is negative
     * @throws NullPointerException if {@code randomSource} is {@code null}
     */
    default long delayMs(int retryCount, DoubleSupplier randomSource) {
        validateRetryCount(retryCount);
        Objects.requireNonNull(randomSource, "randomSource");
        return switch (this) {
            case Fixed fixed -> fixed.delayMs();
            case Exponential exponential -> exponentialDelay(exponential, retryCount, randomSource);
            case Custom custom -> custom.delegate().delay(retryCount);
        };
    }

    /** Creates a bounded fixed-delay policy. */
    static Fixed fixed(long delayMs) {
        return new Fixed(delayMs);
    }

    /** Creates an exponential policy with the canonical one-second jitter bound. */
    static Exponential exponential(long initialDelayMs, double multiplier, long maxDelayMs) {
        return new Exponential(initialDelayMs, multiplier, maxDelayMs, 1_000L);
    }

    /** Creates an exponential policy with an explicit jitter bound. */
    static Exponential exponential(long initialDelayMs, double multiplier, long maxDelayMs, long maxJitterMs) {
        return new Exponential(initialDelayMs, multiplier, maxDelayMs, maxJitterMs);
    }

    /** Creates an unbounded-budget custom policy. */
    static Custom custom(BackoffStrategy delegate) {
        return new Custom(delegate);
    }

    /** A constant delay. */
    record Fixed(long delayMs) implements RetryBackoff {
        public Fixed {
            if (delayMs < 0) {
                throw new IllegalArgumentException("delayMs must be non-negative");
            }
        }
    }

    /** A capped exponential delay with bounded jitter. */
    record Exponential(long initialDelayMs, double multiplier, long maxDelayMs, long maxJitterMs)
            implements RetryBackoff {
        public Exponential {
            if (initialDelayMs < 0) {
                throw new IllegalArgumentException("initialDelayMs must be non-negative");
            }
            if (!Double.isFinite(multiplier) || multiplier < 1.0d) {
                throw new IllegalArgumentException("multiplier must be finite and at least 1.0");
            }
            if (maxDelayMs < 0) {
                throw new IllegalArgumentException("maxDelayMs must be non-negative");
            }
            if (maxJitterMs < 0) {
                throw new IllegalArgumentException("maxJitterMs must be non-negative");
            }
        }
    }

    /** A caller-supplied delay strategy. */
    record Custom(BackoffStrategy delegate) implements RetryBackoff {
        public Custom {
            Objects.requireNonNull(delegate, "delegate");
        }
    }

    private static long exponentialDelay(Exponential backoff, int retryCount, DoubleSupplier randomSource) {
        long base = Retry.cappedExponentialDelay(backoff, retryCount);
        long jitterUpperBound = Math.min(base, backoff.maxJitterMs());
        if (jitterUpperBound == 0L) {
            return base;
        }
        double random = randomSource.getAsDouble();
        if (!Double.isFinite(random) || random < 0.0d || random >= 1.0d) {
            throw new IllegalArgumentException("random source must return a value in [0, 1)");
        }
        long jitter = (long) (random * jitterUpperBound);
        return ExecutionBudget.saturatingAdd(base, jitter);
    }

    private static void validateRetryCount(int retryCount) {
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount must be non-negative");
        }
    }
}
