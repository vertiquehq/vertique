// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import io.vertx.core.Future;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Fixed-order executable composition of typed resilience concerns. */
public final class ResiliencePipeline {

    private final Resilience resilience;
    private final String operationKey;
    private final Timeout timeout;

    private ResiliencePipeline(Resilience resilience, String operationKey, Timeout timeout) {
        this.resilience = resilience;
        this.operationKey = operationKey;
        this.timeout = timeout;
    }

    /**
     * Returns the opaque operation identity used for execution failures.
     *
     * @return the derived operation key
     */
    public String operationKey() {
        return operationKey;
    }

    /**
     * Executes the configured pipeline.
     *
     * @param operation asynchronous operation supplier
     * @param <T> operation result type
     * @return a future settled by the configured concerns
     */
    public <T> Future<T> execute(Supplier<Future<T>> operation) {
        Objects.requireNonNull(operation, "operation");
        return resilience.executeTimeout(operationKey, timeout.configuration(), operation);
    }

    /** Builder for one immutable fixed-order resilience pipeline. */
    public static final class Builder {

        private final Resilience resilience;
        private final String operationKey;
        private Timeout timeout;
        private boolean built;

        Builder(Resilience resilience, String operationKey) {
            this.resilience = Objects.requireNonNull(resilience, "resilience");
            this.operationKey = Objects.requireNonNull(operationKey, "operationKey");
        }

        /**
         * Adds a prebuilt timeout concern.
         *
         * @param value timeout component owned by this pipeline's runtime
         * @return this builder
         */
        public Builder timeout(Timeout value) {
            ensureTimeoutNotConfigured();
            timeout = Objects.requireNonNull(value, "timeout");
            return this;
        }

        /**
         * Builds an inline timeout concern with this pipeline's operation identity.
         *
         * @param configuration consumer of the timeout-owned builder
         * @return this builder
         */
        public Builder timeout(Consumer<Timeout.Builder> configuration) {
            ensureTimeoutNotConfigured();
            Objects.requireNonNull(configuration, "configuration");
            Timeout.Builder timeoutBuilder = Timeout.builderForDerivedKey(resilience, operationKey);
            configuration.accept(timeoutBuilder);
            timeout = timeoutBuilder.build();
            return this;
        }

        /**
         * Builds the pipeline after validating concern ownership and completeness.
         *
         * @return the immutable pipeline
         */
        public ResiliencePipeline build() {
            ensureMutable();
            built = true;
            resilience.ensureOpenForConstruction();
            if (timeout == null) {
                throw new IllegalStateException("a pipeline must configure a timeout concern");
            }
            if (timeout.resilience() != resilience) {
                throw new IllegalArgumentException("pipeline concerns must share the owning runtime");
            }
            return new ResiliencePipeline(resilience, operationKey, timeout);
        }

        private void ensureMutable() {
            if (built) {
                throw new IllegalStateException("pipeline builder has already been used");
            }
        }

        private void ensureTimeoutNotConfigured() {
            ensureMutable();
            if (timeout != null) {
                throw new IllegalStateException("timeout concern already configured");
            }
        }
    }
}
