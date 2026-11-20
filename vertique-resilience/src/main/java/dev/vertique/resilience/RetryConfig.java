// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import java.util.Objects;
import java.util.Set;

/** Complete immutable retry configuration. */
public final class RetryConfig {

    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final RetryBackoff DEFAULT_BACKOFF = RetryBackoff.exponential(500L, 2.0d, 30_000L);

    private final int maxRetries;
    private final RetryBackoff backoff;
    private final Set<Class<? extends Throwable>> retryOn;
    private final Set<Class<? extends Throwable>> abortOn;
    private final RetryPolicy fallbackPolicy;
    private final long retryAfterMaxMs;

    private RetryConfig(
            int maxRetries,
            RetryBackoff backoff,
            Set<Class<? extends Throwable>> retryOn,
            Set<Class<? extends Throwable>> abortOn,
            RetryPolicy fallbackPolicy,
            long retryAfterMaxMs) {
        validateMaxRetries(maxRetries);
        validateRetryAfterMaxMs(retryAfterMaxMs);
        this.maxRetries = maxRetries;
        this.backoff = Objects.requireNonNull(backoff, "backoff");
        this.retryOn = immutableThrowableSet(retryOn, "retryOn");
        this.abortOn = immutableThrowableSet(abortOn, "abortOn");
        this.fallbackPolicy = Objects.requireNonNull(fallbackPolicy, "fallbackPolicy");
        this.retryAfterMaxMs = retryAfterMaxMs;
    }

    /** Starts a builder with the canonical annotation defaults. */
    public static Builder builder() {
        return new Builder();
    }

    public int maxRetries() {
        return maxRetries;
    }

    public RetryBackoff backoff() {
        return backoff;
    }

    public Set<Class<? extends Throwable>> retryOn() {
        return retryOn;
    }

    public Set<Class<? extends Throwable>> abortOn() {
        return abortOn;
    }

    public RetryPolicy fallbackPolicy() {
        return fallbackPolicy;
    }

    /**
     * Returns the longest delay hint the retry honors.
     *
     * @return the maximum in milliseconds, or 0 when a failure's {@link RetryDelayHint} is ignored
     */
    public long retryAfterMaxMs() {
        return retryAfterMaxMs;
    }

    static RetryConfig from(
            int maxRetries,
            RetryBackoff backoff,
            Set<Class<? extends Throwable>> retryOn,
            Set<Class<? extends Throwable>> abortOn,
            RetryPolicy fallbackPolicy,
            long retryAfterMaxMs) {
        return new RetryConfig(maxRetries, backoff, retryOn, abortOn, fallbackPolicy, retryAfterMaxMs);
    }

    private static Set<Class<? extends Throwable>> immutableThrowableSet(
            Set<Class<? extends Throwable>> values, String name) {
        Objects.requireNonNull(values, name);
        return Set.copyOf(values);
    }

    private static void validateMaxRetries(int value) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException("maxRetries must be between 0 and 100");
        }
    }

    private static void validateRetryAfterMaxMs(long value) {
        if (value < 0L) {
            throw new IllegalArgumentException("retryAfterMaxMs must be >= 0");
        }
    }

    /** Single-use builder for a complete retry configuration. */
    public static final class Builder {
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private RetryBackoff backoff = DEFAULT_BACKOFF;
        private Set<Class<? extends Throwable>> retryOn = Set.of();
        private Set<Class<? extends Throwable>> abortOn = Set.of();
        private RetryPolicy fallbackPolicy = (failure, retryCount) -> true;
        private long retryAfterMaxMs;
        private boolean built;

        private Builder() {}

        public Builder maxRetries(int value) {
            ensureMutable();
            validateMaxRetries(value);
            maxRetries = value;
            return this;
        }

        public Builder backoff(RetryBackoff value) {
            ensureMutable();
            backoff = Objects.requireNonNull(value, "backoff");
            return this;
        }

        public Builder retryOn(Set<Class<? extends Throwable>> value) {
            ensureMutable();
            retryOn = copySet(value, "retryOn");
            return this;
        }

        public Builder abortOn(Set<Class<? extends Throwable>> value) {
            ensureMutable();
            abortOn = copySet(value, "abortOn");
            return this;
        }

        public Builder fallbackPolicy(RetryPolicy value) {
            ensureMutable();
            fallbackPolicy = Objects.requireNonNull(value, "fallbackPolicy");
            return this;
        }

        /**
         * Sets the longest delay hint to honor.
         *
         * @param value the maximum in milliseconds, {@code >= 0}; 0 ignores every hint
         * @return this builder
         */
        public Builder retryAfterMaxMs(long value) {
            ensureMutable();
            validateRetryAfterMaxMs(value);
            retryAfterMaxMs = value;
            return this;
        }

        public RetryConfig build() {
            ensureMutable();
            built = true;
            return new RetryConfig(maxRetries, backoff, retryOn, abortOn, fallbackPolicy, retryAfterMaxMs);
        }

        private static Set<Class<? extends Throwable>> copySet(Set<Class<? extends Throwable>> values, String name) {
            Objects.requireNonNull(values, name);
            return Set.copyOf(values);
        }

        private static void validateMaxRetries(int value) {
            RetryConfig.validateMaxRetries(value);
        }

        private void ensureMutable() {
            if (built) {
                throw new IllegalStateException("retry config builder has already been used");
            }
        }
    }
}
