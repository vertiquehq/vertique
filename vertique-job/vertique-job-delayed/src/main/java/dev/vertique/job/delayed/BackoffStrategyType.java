// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import dev.vertique.core.resilience.BackoffStrategy;
import java.util.Locale;

/**
 * Named backoff strategy types supported by the delayed job poller configuration.
 *
 * <p>Maps string config values to {@link BackoffStrategy} instances.
 */
public enum BackoffStrategyType {

    /** Constant delay between retries. */
    FIXED,

    /** Delay increases linearly: {@code baseDelay * (attempt + 1)}. */
    LINEAR,

    /** Delay doubles each attempt with jitter: {@code baseDelay * 2^attempt}. */
    EXPONENTIAL;

    /**
     * Parses a strategy name from configuration, case-insensitive.
     *
     * @param value the config string; must not be {@code null}
     * @return the matching strategy type
     * @throws IllegalArgumentException if {@code value} is {@code null} or does not match any type
     */
    public static BackoffStrategyType fromConfig(String value) {
        if (value == null) {
            throw new IllegalArgumentException("backoffStrategy must not be null");
        }
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    /**
     * Creates a {@link BackoffStrategy} instance with the given parameters.
     *
     * @param baseDelay the base delay in milliseconds
     * @param maxDelay  the maximum delay in milliseconds
     * @return a configured backoff strategy
     */
    public BackoffStrategy toStrategy(long baseDelay, long maxDelay) {
        return switch (this) {
            case FIXED -> BackoffStrategy.fixed(baseDelay);
            case EXPONENTIAL -> BackoffStrategy.exponential(baseDelay, 2.0, maxDelay);
            case LINEAR -> retryCount -> Math.min(baseDelay * (retryCount + 1L), maxDelay);
        };
    }
}
