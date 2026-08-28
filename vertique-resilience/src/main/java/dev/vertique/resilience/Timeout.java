// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import io.vertx.core.Future;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/** Independently executable per-attempt timeout component. */
public final class Timeout {

    private final Resilience resilience;
    private final String operationKey;
    private final TimeoutConfig configuration;

    private Timeout(Resilience resilience, String operationKey, TimeoutConfig configuration) {
        this.resilience = resilience;
        this.operationKey = operationKey;
        this.configuration = configuration;
    }

    /**
     * Starts construction of a timeout component.
     *
     * @param resilience owning runtime
     * @param operationName raw construction-time operation identity
     * @return a timeout builder
     */
    public static Builder builder(Resilience resilience, String operationName) {
        Objects.requireNonNull(resilience, "resilience");
        return new Builder(resilience, ResilienceIdentity.applicationOperationKey(operationName));
    }

    static Builder builderForDerivedKey(Resilience resilience, String operationKey) {
        return new Builder(
                Objects.requireNonNull(resilience, "resilience"), Objects.requireNonNull(operationKey, "operationKey"));
    }

    /**
     * Executes one supplier attempt behind this timeout fence.
     *
     * @param operation asynchronous operation supplier
     * @param <T> operation result type
     * @return a future settled by the supplier or timeout fence
     */
    public <T> Future<T> execute(Supplier<Future<T>> operation) {
        return resilience.executeTimeout(operationKey, configuration, operation, null);
    }

    String operationKey() {
        return operationKey;
    }

    Resilience resilience() {
        return resilience;
    }

    TimeoutConfig configuration() {
        return configuration;
    }

    /** Builder for a single immutable timeout component. */
    public static final class Builder {

        private final Resilience resilience;
        private final String operationKey;
        private Duration duration;
        private boolean built;

        private Builder(Resilience resilience, String operationKey) {
            this.resilience = resilience;
            this.operationKey = operationKey;
        }

        /**
         * Sets the timeout duration once.
         *
         * @param timeout positive, millisecond-representable duration
         * @return this builder
         */
        public Builder duration(Duration timeout) {
            ensureMutable();
            if (duration != null) {
                throw new IllegalStateException("timeout duration already configured");
            }
            duration = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /**
         * Builds the timeout component.
         *
         * @return the immutable timeout component
         */
        public Timeout build() {
            ensureMutable();
            built = true;
            resilience.ensureOpenForConstruction();
            if (duration == null) {
                throw new IllegalStateException("timeout duration is required");
            }
            return new Timeout(resilience, operationKey, TimeoutConfig.of(duration));
        }

        private void ensureMutable() {
            if (built) {
                throw new IllegalStateException("timeout builder has already been used");
            }
        }
    }
}
