// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import dev.vertique.resilience.adapter.AdapterOperationIdentity;
import dev.vertique.resilience.adapter.CircuitFailureClassifier;
import dev.vertique.resilience.exception.CircuitOpenException;
import dev.vertique.resilience.exception.ResiliencePolicyException;
import dev.vertique.resilience.exception.ResiliencePolicyFailureReason;
import dev.vertique.resilience.spi.event.ResilienceConcern;
import io.vertx.core.Future;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Fixed-order executable composition of typed resilience concerns. */
public final class ResiliencePipeline {

    private final Resilience resilience;
    private final String operationKey;
    private final TimeoutConfig timeoutConfiguration;
    private final RetryConfig retryConfiguration;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final CircuitFailureClassifier circuitFailureClassifier;
    private final BooleanSupplier contextOpen;
    private final Consumer<Runnable> executionRegistrar;

    private ResiliencePipeline(
            Resilience resilience,
            String operationKey,
            TimeoutConfig timeoutConfiguration,
            RetryConfig retryConfiguration,
            CircuitBreaker circuitBreaker,
            Bulkhead bulkhead,
            CircuitFailureClassifier circuitFailureClassifier,
            BooleanSupplier contextOpen,
            Consumer<Runnable> executionRegistrar) {
        this.resilience = resilience;
        this.operationKey = operationKey;
        this.timeoutConfiguration = timeoutConfiguration;
        this.retryConfiguration = retryConfiguration;
        this.circuitBreaker = circuitBreaker;
        this.bulkhead = bulkhead;
        this.circuitFailureClassifier = circuitFailureClassifier;
        this.contextOpen = contextOpen;
        this.executionRegistrar = executionRegistrar;
    }

    /**
     * Creates the runtime-owned pipeline used by the structured adapter bridge.
     *
     * @param resilience owning runtime
     * @param identity structured adapter operation identity
     * @param policy complete resolved policy
     * @return executable timeout/retry pipeline
     * @throws IllegalStateException if the policy is empty
     * @throws ResiliencePolicyException if the policy contains a breaker or bulkhead concern
     */
    static ResiliencePipeline fromAdapterPolicy(
            Resilience resilience, AdapterOperationIdentity identity, ResolvedResiliencePolicy policy) {
        Objects.requireNonNull(resilience, "resilience");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(policy, "policy");

        String operationKey = deriveAdapterOperationKey(identity);
        if (policy.isEmpty()) {
            throw new IllegalStateException("a pipeline must configure at least one concern");
        }
        if (policy.circuitBreaker().isPresent() || policy.bulkhead().isPresent()) {
            throw new ResiliencePolicyException(ResiliencePolicyFailureReason.INVALID_CONFIGURATION);
        }
        resilience.ensureOpenForConstruction();
        return new ResiliencePipeline(
                resilience,
                operationKey,
                policy.timeout().orElse(null),
                policy.retry().orElse(null),
                null,
                null,
                null,
                () -> true,
                null);
    }

    static ResiliencePipeline fromAdapterPolicy(
            Resilience resilience,
            AdapterOperationIdentity identity,
            ResolvedResiliencePolicy policy,
            CircuitBreaker circuitBreaker,
            CircuitFailureClassifier classifier,
            BooleanSupplier contextOpen) {
        Objects.requireNonNull(resilience, "resilience");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(contextOpen, "contextOpen");
        if (policy.isEmpty()) {
            throw new IllegalStateException("a pipeline must configure at least one concern");
        }
        if (policy.circuitBreaker().isPresent() || policy.bulkhead().isPresent()) {
            throw new ResiliencePolicyException(ResiliencePolicyFailureReason.INVALID_CONFIGURATION);
        }
        resilience.ensureOpenForConstruction();
        return new ResiliencePipeline(
                resilience,
                deriveAdapterOperationKey(identity),
                policy.timeout().orElse(null),
                policy.retry().orElse(null),
                circuitBreaker,
                null,
                classifier,
                contextOpen,
                null);
    }

    static ResiliencePipeline fromAdapterPolicy(
            Resilience resilience,
            AdapterOperationIdentity identity,
            ResolvedResiliencePolicy policy,
            CircuitBreaker circuitBreaker,
            CircuitFailureClassifier classifier,
            BooleanSupplier contextOpen,
            Consumer<Runnable> executionRegistrar) {
        Objects.requireNonNull(executionRegistrar, "executionRegistrar");
        ResiliencePipeline base =
                fromAdapterPolicy(resilience, identity, policy, circuitBreaker, classifier, contextOpen);
        return new ResiliencePipeline(
                base.resilience,
                base.operationKey,
                base.timeoutConfiguration,
                base.retryConfiguration,
                base.circuitBreaker,
                base.bulkhead,
                base.circuitFailureClassifier,
                base.contextOpen,
                executionRegistrar);
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
        ResilienceExecutionObservation observation =
                new ResilienceExecutionObservation(resilience, operationKey, enabledConcerns());
        if (!contextOpen.getAsBoolean()) {
            return finish(observation, resilience.failedOnContext(resilience.executionContext(), operationKey));
        }
        if (circuitBreaker != null && circuitBreaker.isOpenForAdmission()) {
            observation.circuitRejected(circuitBreaker.stateKey(), circuitBreaker.currentState());
            return finish(
                    observation,
                    resilience.failedOnContext(
                            resilience.executionContext(),
                            new CircuitOpenException(operationKey, circuitBreaker.stateKey())));
        }
        Supplier<Future<T>> retryOrTimeout = retryConfiguration != null
                ? () -> resilience.executeRetry(
                        operationKey, retryConfiguration, timeoutConfiguration, operation, observation)
                : timeoutConfiguration != null
                        ? () -> resilience.executeTimeout(operationKey, timeoutConfiguration, operation, observation)
                        : () -> observation.executeAttempt(operation);
        Supplier<Future<T>> protectedOperation = circuitBreaker == null
                ? retryOrTimeout
                : () -> circuitBreaker.execute(
                        operationKey,
                        circuitFailureClassifier,
                        retryOrTimeout,
                        contextOpen,
                        executionRegistrar,
                        observation);
        if (bulkhead != null) {
            return finish(
                    observation,
                    bulkhead.execute(
                            operationKey,
                            protectedOperation,
                            contextOpen,
                            executionRegistrar == null ? ignored -> {} : executionRegistrar,
                            observation));
        }
        if (circuitBreaker != null) {
            return finish(observation, protectedOperation.get());
        }
        Future<T> outcome = retryOrTimeout.get();
        Future<T> fenced = executionRegistrar == null ? outcome : fenceContext(outcome);
        return finish(observation, fenced);
    }

    private Set<ResilienceConcern> enabledConcerns() {
        EnumSet<ResilienceConcern> enabled = EnumSet.noneOf(ResilienceConcern.class);
        if (timeoutConfiguration != null) enabled.add(ResilienceConcern.TIMEOUT);
        if (retryConfiguration != null) enabled.add(ResilienceConcern.RETRY);
        if (circuitBreaker != null) enabled.add(ResilienceConcern.CIRCUIT_BREAKER);
        if (bulkhead != null) enabled.add(ResilienceConcern.BULKHEAD);
        return Set.copyOf(enabled);
    }

    private <T> Future<T> finish(ResilienceExecutionObservation observation, Future<T> outcome) {
        outcome.onComplete(result -> observation.completed(result.succeeded() ? null : result.cause()));
        return outcome;
    }

    private <T> Future<T> fenceContext(Future<T> outcome) {
        io.vertx.core.Promise<T> result = io.vertx.core.Promise.promise();
        io.vertx.core.Context context = resilience.executionContext();
        executionRegistrar.accept(
                () -> result.tryFail(new dev.vertique.resilience.exception.ResilienceClosedException(operationKey)));
        outcome.onComplete(ignored -> context.runOnContext(done -> {
            if (contextOpen.getAsBoolean()) {
                if (outcome.succeeded()) {
                    result.tryComplete(outcome.result());
                } else {
                    result.tryFail(outcome.cause());
                }
            } else {
                result.tryFail(new dev.vertique.resilience.exception.ResilienceClosedException(operationKey));
            }
        }));
        return result.future();
    }

    /** Builder for one immutable fixed-order resilience pipeline. */
    public static final class Builder {

        private final Resilience resilience;
        private final String operationKey;
        private Timeout timeout;
        private Retry retry;
        private CircuitBreaker circuitBreaker;
        private Bulkhead bulkhead;
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
         * Adds a prebuilt retry concern owned by this pipeline's runtime.
         *
         * @param value retry component owned by this pipeline's runtime
         * @return this builder
         */
        public Builder retry(Retry value) {
            ensureRetryNotConfigured();
            retry = Objects.requireNonNull(value, "retry");
            return this;
        }

        /**
         * Builds an inline retry concern with this pipeline's operation identity.
         *
         * @param configuration consumer of the retry-owned builder
         * @return this builder
         */
        public Builder retry(Consumer<Retry.Builder> configuration) {
            ensureRetryNotConfigured();
            Objects.requireNonNull(configuration, "configuration");
            Retry.Builder retryBuilder = Retry.builderForDerivedKey(resilience, operationKey);
            configuration.accept(retryBuilder);
            retry = retryBuilder.build();
            return this;
        }

        /** Adds a prebuilt circuit breaker concern. */
        public Builder circuitBreaker(CircuitBreaker value) {
            ensureCircuitBreakerNotConfigured();
            circuitBreaker = Objects.requireNonNull(value, "circuitBreaker");
            return this;
        }

        /** Builds an inline circuit breaker concern with this pipeline's state identity. */
        public Builder circuitBreaker(Consumer<CircuitBreaker.Builder> configuration) {
            ensureCircuitBreakerNotConfigured();
            Objects.requireNonNull(configuration, "configuration");
            CircuitBreaker.Builder builder = CircuitBreaker.builderForDerivedKey(resilience, operationKey);
            configuration.accept(builder);
            circuitBreaker = builder.build();
            return this;
        }

        /** Adds a prebuilt bulkhead concern. */
        public Builder bulkhead(Bulkhead value) {
            ensureBulkheadNotConfigured();
            bulkhead = Objects.requireNonNull(value, "bulkhead");
            return this;
        }

        /** Builds an inline bulkhead concern with this pipeline's state identity. */
        public Builder bulkhead(Consumer<Bulkhead.Builder> configuration) {
            ensureBulkheadNotConfigured();
            Objects.requireNonNull(configuration, "configuration");
            Bulkhead.Builder builder = Bulkhead.builderForDerivedKey(resilience, operationKey);
            configuration.accept(builder);
            bulkhead = builder.build();
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
            if (timeout == null && retry == null && circuitBreaker == null && bulkhead == null) {
                throw new IllegalStateException("a pipeline must configure at least one concern");
            }
            if (timeout != null && timeout.resilience() != resilience) {
                throw new IllegalArgumentException("pipeline concerns must share the owning runtime");
            }
            if (retry != null && retry.resilience() != resilience) {
                throw new IllegalArgumentException("pipeline concerns must share the owning runtime");
            }
            if (circuitBreaker != null && circuitBreaker.resilience() != resilience) {
                throw new IllegalArgumentException("pipeline concerns must share the owning runtime");
            }
            if (bulkhead != null && bulkhead.resilience() != resilience) {
                throw new IllegalArgumentException("pipeline concerns must share the owning runtime");
            }
            return new ResiliencePipeline(
                    resilience,
                    operationKey,
                    timeout == null ? null : timeout.configuration(),
                    retry == null ? null : retry.configuration(),
                    circuitBreaker,
                    bulkhead,
                    null,
                    () -> true,
                    null);
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

        private void ensureRetryNotConfigured() {
            ensureMutable();
            if (retry != null) {
                throw new IllegalStateException("retry concern already configured");
            }
        }

        private void ensureCircuitBreakerNotConfigured() {
            ensureMutable();
            if (circuitBreaker != null) {
                throw new IllegalStateException("circuit-breaker concern already configured");
            }
        }

        private void ensureBulkheadNotConfigured() {
            ensureMutable();
            if (bulkhead != null) {
                throw new IllegalStateException("bulkhead concern already configured");
            }
        }
    }

    static String deriveAdapterOperationKey(AdapterOperationIdentity identity) {
        MessageDigest digest = newSha256();
        digest.update((byte) 0x01);

        byte[] kind = identity.kind().getBytes(StandardCharsets.UTF_8);
        updateLength(digest, kind.length);
        digest.update(kind);

        updateLength(digest, identity.components().size());
        for (String component : identity.components()) {
            byte[] encodedComponent = component.getBytes(StandardCharsets.UTF_8);
            updateLength(digest, encodedComponent.length);
            digest.update(encodedComponent);
        }

        return identity.kind().replace('.', ':') + ":" + HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static void updateLength(MessageDigest digest, int length) {
        digest.update((byte) (length >>> 24));
        digest.update((byte) (length >>> 16));
        digest.update((byte) (length >>> 8));
        digest.update((byte) length);
    }
}
