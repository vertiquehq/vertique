// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import dev.vertique.core.exception.ConfigurationException;
import java.util.Objects;

/**
 * The only {@link RateLimitAlgorithm} v1 permits: a fixed-capacity token bucket refilled by
 * {@link #refill()} (contracts/rate-limit-runtime.md, "Policy model").
 *
 * @param capacity token-bucket capacity; bounded {@code 1..1_000_000_000_000}
 * @param refill the native refill shape (greedy or interval)
 */
public record TokenBucketRateLimit(long capacity, RateLimitRefill refill) implements RateLimitAlgorithm {

    private static final long MIN_CAPACITY = 1L;
    private static final long MAX_CAPACITY = 1_000_000_000_000L;

    /**
     * Compact constructor — validates {@code capacity} against §5.8's bounds and requires
     * {@code refill}.
     *
     * @throws ConfigurationException if {@code capacity} is out of bounds
     * @throws NullPointerException if {@code refill} is {@code null}
     */
    public TokenBucketRateLimit {
        Objects.requireNonNull(refill, "refill");
        if (capacity < MIN_CAPACITY || capacity > MAX_CAPACITY) {
            throw new ConfigurationException("rateLimit policy algorithm.capacity must be between 1 and 1000000000000");
        }
    }

    @Override
    public RateLimitAlgorithmType type() {
        return RateLimitAlgorithmType.TOKEN_BUCKET;
    }
}
