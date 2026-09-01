// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import java.time.Duration;
import java.util.Objects;

/**
 * All-at-once token refill: the full {@link #tokens()} amount arrives exactly at each
 * {@link #period()} boundary, never fractionally in between (contracts/rate-limit-runtime.md,
 * "Bucket4j translation contract" — maps to Bucket4j's {@code refillIntervally}).
 *
 * @param tokens tokens added per {@link #period()}; bounded {@code 1..1_000_000_000_000}
 * @param period the refill period; bounded {@code 1ms..31_536_000_000ms}
 */
public record IntervalRateLimitRefill(long tokens, Duration period) implements RateLimitRefill {

    private static final long MIN_TOKENS = 1L;
    private static final long MAX_TOKENS = 1_000_000_000_000L;
    private static final long MIN_PERIOD_MS = 1L;
    private static final long MAX_PERIOD_MS = 31_536_000_000L;

    /**
     * Compact constructor — validates {@code tokens} and {@code period} against §5.8's bounds.
     *
     * @throws ConfigurationException if {@code period} is {@code null} or either bound is violated
     */
    public IntervalRateLimitRefill {
        Objects.requireNonNull(period, "period");
        if (tokens < MIN_TOKENS || tokens > MAX_TOKENS) {
            throw new ConfigurationException(
                    "rateLimit policy algorithm.refill.tokens must be between 1 and 1000000000000");
        }
        long periodMs = period.toMillis();
        if (periodMs < MIN_PERIOD_MS || periodMs > MAX_PERIOD_MS) {
            throw new ConfigurationException(
                    "rateLimit policy algorithm.refill.periodMs must be between 1 and 31536000000");
        }
    }

    /**
     * Jackson-friendly factory. {@code periodMs} carries the framework's explicit-unit config
     * naming convention (duration fields end in {@code Ms}); it is converted to the typed
     * {@link #period()} here so the rest of the framework never handles a bare millisecond long.
     *
     * @param tokens tokens added per period
     * @param periodMs the refill period in milliseconds
     * @return the deserialized, validated refill
     */
    @JsonCreator
    static IntervalRateLimitRefill fromJson(
            @JsonProperty("tokens") long tokens, @JsonProperty("periodMs") long periodMs) {
        return new IntervalRateLimitRefill(tokens, Duration.ofMillis(periodMs));
    }
}
