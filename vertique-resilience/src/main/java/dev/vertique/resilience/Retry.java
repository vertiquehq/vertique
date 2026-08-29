// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import io.vertx.core.Future;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Runtime-owned retry component backed by Vert.x CircuitBreaker. */
public final class Retry {

    private final Resilience resilience;
    private final String operationKey;
    private final RetryConfig configuration;

    private Retry(Resilience resilience, String operationKey, RetryConfig configuration) {
        this.resilience = resilience;
        this.operationKey = operationKey;
        this.configuration = configuration;
    }

    /** Starts construction of a retry component. */
    public static Builder builder(Resilience resilience, String operationName) {
        Objects.requireNonNull(resilience, "resilience");
        return new Builder(resilience, ResilienceIdentity.applicationOperationKey(operationName));
    }

    static Builder builderForDerivedKey(Resilience resilience, String operationKey) {
        return new Builder(
                Objects.requireNonNull(resilience, "resilience"), Objects.requireNonNull(operationKey, "operationKey"));
    }

    Resilience resilience() {
        return resilience;
    }

    String operationKey() {
        return operationKey;
    }

    RetryConfig configuration() {
        return configuration;
    }

    static long cappedExponentialDelay(RetryBackoff.Exponential backoff, int retryCount) {
        if (backoff.initialDelayMs() == 0L || backoff.maxDelayMs() == 0L) {
            return 0L;
        }
        if (backoff.multiplier() == 1.0d) {
            return Math.min(backoff.initialDelayMs(), backoff.maxDelayMs());
        }
        double value = backoff.initialDelayMs();
        for (int index = 0; index < retryCount && value < backoff.maxDelayMs(); index++) {
            value *= backoff.multiplier();
            if (!Double.isFinite(value)) {
                return backoff.maxDelayMs();
            }
        }
        if (value >= backoff.maxDelayMs()) {
            return backoff.maxDelayMs();
        }
        return Math.min(backoff.maxDelayMs(), (long) value);
    }

    /** Executes the supplier under this retry policy. */
    public <T> Future<T> execute(Supplier<Future<T>> operation) {
        return resilience.executeRetry(operationKey, configuration, null, operation);
    }

    /** Builder for one immutable retry component. */
    public static final class Builder {

        private final Resilience resilience;
        private final String operationKey;
        private final RetryConfig.Builder configuration = RetryConfig.builder();
        private boolean built;

        private Builder(Resilience resilience, String operationKey) {
            this.resilience = resilience;
            this.operationKey = operationKey;
        }

        /** Sets the maximum number of additional attempts. */
        public Builder maxRetries(int value) {
            ensureMutable();
            configuration.maxRetries(value);
            return this;
        }

        /** Sets the retry backoff. */
        public Builder backoff(RetryBackoff value) {
            ensureMutable();
            configuration.backoff(value);
            return this;
        }

        /** Sets the selective retry exception set. */
        public Builder retryOn(Set<Class<? extends Throwable>> value) {
            ensureMutable();
            configuration.retryOn(value);
            return this;
        }

        /** Sets the abort exception set. */
        public Builder abortOn(Set<Class<? extends Throwable>> value) {
            ensureMutable();
            configuration.abortOn(value);
            return this;
        }

        /** Sets the fallback retry predicate. */
        public Builder fallbackPolicy(RetryPolicy value) {
            ensureMutable();
            configuration.fallbackPolicy(value);
            return this;
        }

        /** Builds the immutable retry component. */
        public Retry build() {
            ensureMutable();
            built = true;
            resilience.ensureOpenForConstruction();
            return new Retry(resilience, operationKey, configuration.build());
        }

        private void ensureMutable() {
            if (built) {
                throw new IllegalStateException("retry builder has already been used");
            }
        }
    }
}
