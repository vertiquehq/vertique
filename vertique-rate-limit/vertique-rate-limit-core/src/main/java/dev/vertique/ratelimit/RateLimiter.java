// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import dev.vertique.ratelimit.exception.RateLimitExceededException;
import dev.vertique.ratelimit.exception.RateLimitRequestException;
import dev.vertique.ratelimit.exception.RateLimitRequestFailure;
import dev.vertique.ratelimit.exception.RateLimitUnavailableException;
import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendRequest;
import dev.vertique.ratelimit.spi.RateLimitBackendResult;
import dev.vertique.ratelimit.spi.RateLimitObserver;
import dev.vertique.ratelimit.spi.event.RateLimitDecisionCompleted;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Unkeyed handle bound to one policy, resolved from {@link RateLimiters#limiter(String)}.
 *
 * <p>{@code acquire(...)} never fails its returned {@link Future} for quota reasons — it always
 * yields a {@link RateLimitDecision} (decision-first). {@code execute(...)} is the guarded wrapper
 * (contracts/rate-limit-runtime.md, "Handle semantics"): {@code PERMITTED}/{@code
 * BACKEND_FAILURE_OPEN}/{@code DISABLED} run the action exactly once, with no retry and no refund;
 * {@code QUOTA_EXCEEDED} fails with {@link RateLimitExceededException}; {@code
 * BACKEND_FAILURE_CLOSED} fails with {@link RateLimitUnavailableException}. A cost above policy
 * capacity fails both {@code acquire} and {@code execute} with {@link RateLimitRequestException}
 * ({@link RateLimitRequestFailure#COST_EXCEEDS_CAPACITY}) before either reaches the engine, even
 * under {@code failureMode=OPEN}.
 */
public final class RateLimiter {

    private final RateLimitPolicy policy;
    private final RateLimitBackend backend;
    private final Vertx vertx;
    private final Set<RateLimitObserver> observers;
    private final boolean rateLimitEnabled;
    private final RateLimiterLifecycle lifecycle;

    RateLimiter(
            RateLimitPolicy policy,
            RateLimitBackend backend,
            Vertx vertx,
            Set<RateLimitObserver> observers,
            boolean rateLimitEnabled,
            RateLimiterLifecycle lifecycle) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.observers = Objects.requireNonNull(observers, "observers");
        this.rateLimitEnabled = rateLimitEnabled;
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /**
     * @return the policy name this handle was resolved for
     */
    public String policyName() {
        return policy.name();
    }

    /**
     * Exposed for framework adapters that must classify a decision at their own boundary — e.g. an
     * edge middleware's absent-origin classification (contracts/rest-adapter.md, "Rule composition
     * semantics" — IP mechanism) — without independently re-deriving this handle's bound policy's
     * {@code failureMode} from its own configuration source.
     *
     * @return this handle's bound policy's explicit backend-failure behavior
     */
    public RateLimitFailureMode failureMode() {
        return policy.failureMode();
    }

    /**
     * Exposed for framework adapters that must validate a caller-declared cost against this
     * handle's bound policy's capacity at their own construction time — e.g. the REST edge
     * middleware's rule-composition validation (contracts/rest-adapter.md, "Configuration") —
     * without independently re-deriving it from this handle's policy/algorithm internals. A cost
     * above this value fails {@link #acquire(RateLimitKey, long)}/{@link #execute(RateLimitKey,
     * long, Supplier)} with {@link RateLimitRequestException} ({@link
     * RateLimitRequestFailure#COST_EXCEEDS_CAPACITY}) before either reaches the engine.
     *
     * @return this handle's bound policy's token-bucket capacity
     */
    public long capacity() {
        return ((TokenBucketRateLimit) policy.algorithm()).capacity();
    }

    /**
     * Attempts to consume {@code policy.defaultCost()} tokens against {@code key}. Equivalent to
     * {@code acquire(key, policy.defaultCost())}.
     *
     * @param key the caller-supplied key
     * @return a future that completes with the decision, dispatched on the calling Vert.x context
     *     when one is present
     */
    public Future<RateLimitDecision> acquire(RateLimitKey key) {
        return acquire(key, policy.defaultCost());
    }

    /**
     * Attempts to consume {@code cost} tokens against {@code key}. Never fails the returned future
     * for quota reasons — it always yields a {@link RateLimitDecision} (decision-first); a request
     * whose {@code cost} exceeds policy capacity fails the future instead, before the engine is
     * ever consulted (contracts/rate-limit-runtime.md, "Handle semantics").
     *
     * @param key the caller-supplied key
     * @param cost tokens this request attempts to consume
     * @return a future that completes with the decision, dispatched on the calling Vert.x context
     *     when one is present
     * @throws IllegalArgumentException synchronously (never as a failed future) when {@code cost}
     *     is below 1 — checked, and rejected, before any engine call, so a Bucket4j {@code
     *     IllegalArgumentException} for the same reason can never cross this boundary
     * @throws RateLimitRequestException (as a failed future, never thrown synchronously) with
     *     reason {@link RateLimitRequestFailure#COST_EXCEEDS_CAPACITY} when {@code cost} exceeds
     *     this policy's capacity
     */
    public Future<RateLimitDecision> acquire(RateLimitKey key, long cost) {
        Objects.requireNonNull(key, "key");
        if (cost < 1) {
            throw new IllegalArgumentException("rate-limit cost must be at least 1, got " + cost);
        }
        TokenBucketRateLimit algorithm = (TokenBucketRateLimit) policy.algorithm();
        if (cost > algorithm.capacity()) {
            return dispatchOnCallingContext(
                    Future.failedFuture(new RateLimitRequestException(RateLimitRequestFailure.COST_EXCEEDS_CAPACITY)));
        }
        if (lifecycle.isClosed()) {
            return dispatchOnCallingContext(Future.failedFuture(closedException()));
        }
        if (!rateLimitEnabled) {
            return dispatchOnCallingContext(Future.succeededFuture(disabledDecision(algorithm, cost)));
        }
        String storageKey = policy.name() + ':' + policy.revision() + ':' + key.canonicalEncoding();
        RateLimitBackendRequest request = new RateLimitBackendRequest(storageKey, algorithm, cost);
        long startedAt = System.nanoTime();
        Future<RateLimitBackendResult> consumeFuture;
        try {
            consumeFuture = backend.consume(request);
        } catch (Throwable synchronousFailure) {
            consumeFuture = Future.failedFuture(synchronousFailure);
        }
        return dispatchOnCallingContext(consumeFuture
                .map(result -> completeDecision(result, algorithm, cost, elapsedNanosSince(startedAt)))
                .recover(failure -> Future.succeededFuture(backendFailureDecision(algorithm, cost))));
    }

    /**
     * Guarded wrapper around {@link #acquire(RateLimitKey)}: runs {@code action} exactly once when
     * permitted, otherwise fails with the mapped exception. Equivalent to {@code execute(key,
     * policy.defaultCost(), action)}.
     *
     * @param key the caller-supplied key
     * @param action the guarded action; invoked at most once, never retried, never refunded
     * @param <T> the action's result type
     * @return a future completing with the action's result, or the mapped denial/failure
     */
    public <T> Future<T> execute(RateLimitKey key, Supplier<Future<T>> action) {
        return execute(key, policy.defaultCost(), action);
    }

    /**
     * Guarded wrapper around {@link #acquire(RateLimitKey, long)} (contracts/rate-limit-runtime.md,
     * "Handle semantics"): {@code PERMITTED}/{@code BACKEND_FAILURE_OPEN}/{@code DISABLED} run
     * {@code action} exactly once; {@code QUOTA_EXCEEDED} fails with {@link
     * RateLimitExceededException}; {@code BACKEND_FAILURE_CLOSED} fails with {@link
     * RateLimitUnavailableException}. A synchronous {@code action} throw becomes a failed future; a
     * {@code null} action result is a contract failure; an application failure passes through
     * unchanged.
     *
     * @param key the caller-supplied key
     * @param cost tokens this request attempts to consume
     * @param action the guarded action; invoked at most once, never retried, never refunded
     * @param <T> the action's result type
     * @return a future completing with the action's result, or the mapped denial/failure
     */
    public <T> Future<T> execute(RateLimitKey key, long cost, Supplier<Future<T>> action) {
        Objects.requireNonNull(action, "action");
        return acquire(key, cost).compose(decision -> runGuarded(decision, action));
    }

    private <T> Future<T> runGuarded(RateLimitDecision decision, Supplier<Future<T>> action) {
        return switch (decision.outcome()) {
            case PERMITTED, BACKEND_FAILURE_OPEN, DISABLED -> runFenced(action);
            case QUOTA_EXCEEDED -> Future.failedFuture(new RateLimitExceededException(decision));
            case BACKEND_FAILURE_CLOSED -> Future.failedFuture(new RateLimitUnavailableException(decision));
        };
    }

    /**
     * Registers this call's lifecycle fence <strong>before</strong> invoking {@code action}, so a
     * concurrent {@link RateLimiters#close()} that becomes observable at any point up to and
     * including registration is guaranteed to pre-empt the action — {@code action} is invoked at
     * most once, and never at all once close is observable. Once registered, the action's returned
     * future is tracked by this runtime's shared {@link RateLimiterLifecycle} so that same {@code
     * close()} can force-fail this call's guarded future immediately, without waiting for (or ever
     * surfacing) the action's real, possibly-late completion.
     */
    private <T> Future<T> runFenced(Supplier<Future<T>> action) {
        Promise<T> guarded = Promise.promise();
        Runnable fence = () -> guarded.tryFail(closedException());
        Runnable registered = lifecycle.register(fence);
        if (registered == null) {
            return dispatchOnCallingContext(Future.failedFuture(closedException()));
        }
        Future<T> actionFuture;
        try {
            actionFuture = action.get();
        } catch (Throwable failure) {
            lifecycle.unregister(fence);
            return Future.failedFuture(failure);
        }
        if (actionFuture == null) {
            lifecycle.unregister(fence);
            return Future.failedFuture(new NullPointerException("execute action must not return a null Future"));
        }
        actionFuture.onComplete(result -> {
            lifecycle.unregister(fence);
            if (result.succeeded()) {
                guarded.tryComplete(result.result());
            } else {
                guarded.tryFail(result.cause());
            }
        });
        return dispatchOnCallingContext(guarded.future());
    }

    /**
     * Normalizes one backend result into a {@link RateLimitDecision} and, at this single
     * decision-completion point, dispatches the corresponding redacted {@link
     * RateLimitDecisionCompleted} event to every bound observer before returning the decision
     * (contracts/rate-limit-runtime.md, "Observer SPI"). Observer dispatch never alters the
     * decision already computed here.
     */
    private RateLimitDecision completeDecision(
            RateLimitBackendResult result, TokenBucketRateLimit algorithm, long cost, long backendLatencyNanos) {
        RateLimitOutcome outcome = classifyOutcome(result);
        OptionalLong remaining = OptionalLong.of(result.remaining());
        RateLimitDecision decision = buildDecision(
                outcome, algorithm, remaining, result.retryAfter(), result.resetAfter(), result.failureCode());
        emitIfObserved(
                outcome,
                algorithm,
                cost,
                remaining,
                result.retryAfter(),
                result.resetAfter(),
                result.failureCode(),
                backendLatencyNanos);
        return decision;
    }

    /**
     * Classifies a backend result into an outcome: a present {@code failureCode} (e.g. {@code
     * CAPACITY_EXHAUSTED}) is a backend failure, classified through this policy's {@code
     * failureMode} into {@code BACKEND_FAILURE_OPEN}/{@code BACKEND_FAILURE_CLOSED}
     * (contracts/rate-limit-runtime.md, "Failure classification"); otherwise the ordinary
     * consumed/rejected mapping applies.
     */
    private RateLimitOutcome classifyOutcome(RateLimitBackendResult result) {
        if (result.failureCode().isPresent()) {
            return classifyBackendFailureOutcome();
        }
        return result.consumed() ? RateLimitOutcome.PERMITTED : RateLimitOutcome.QUOTA_EXCEEDED;
    }

    /** This policy's {@code failureMode}, applied to any backend failure regardless of its source. */
    private RateLimitOutcome classifyBackendFailureOutcome() {
        return policy.failureMode() == RateLimitFailureMode.OPEN
                ? RateLimitOutcome.BACKEND_FAILURE_OPEN
                : RateLimitOutcome.BACKEND_FAILURE_CLOSED;
    }

    /**
     * Normalizes a backend future that failed outright — rejected, or threw synchronously before
     * ever returning one — into the same {@code BACKEND_FAILURE_OPEN}/{@code
     * BACKEND_FAILURE_CLOSED} decision shape {@link #classifyOutcome} produces for an in-band
     * backend failure, so {@code acquire(...)} still always yields a decision (decision-first) and
     * still emits {@link RateLimitDecisionCompleted} exactly once. The underlying cause never
     * crosses this boundary — only the fixed {@link RateLimitFailureCode#INTERNAL} classification
     * does, mirroring {@link RateLimitFailureCode}'s own "causes and messages never cross this
     * boundary" contract.
     */
    private RateLimitDecision backendFailureDecision(TokenBucketRateLimit algorithm, long cost) {
        RateLimitOutcome outcome = classifyBackendFailureOutcome();
        RateLimitDecision decision = buildDecision(
                outcome,
                algorithm,
                OptionalLong.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(RateLimitFailureCode.INTERNAL));
        emitIfObserved(
                outcome,
                algorithm,
                cost,
                OptionalLong.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(RateLimitFailureCode.INTERNAL),
                0L);
        return decision;
    }

    /**
     * The {@code rateLimit.enabled} root kill switch's decision: every request is admitted without
     * engaging the engine, and an event is still emitted so observability stays continuous across
     * the switch.
     */
    private RateLimitDecision disabledDecision(TokenBucketRateLimit algorithm, long cost) {
        OptionalLong remaining = OptionalLong.of(algorithm.capacity());
        RateLimitDecision decision = buildDecision(
                RateLimitOutcome.DISABLED, algorithm, remaining, Optional.empty(), Optional.empty(), Optional.empty());
        emitIfObserved(
                RateLimitOutcome.DISABLED,
                algorithm,
                cost,
                remaining,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0L);
        return decision;
    }

    /** Synthetic fail-closed decision/exception used once this runtime has been closed. */
    private RateLimitUnavailableException closedException() {
        TokenBucketRateLimit algorithm = (TokenBucketRateLimit) policy.algorithm();
        RateLimitDecision decision = buildDecision(
                RateLimitOutcome.BACKEND_FAILURE_CLOSED,
                algorithm,
                OptionalLong.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(RateLimitFailureCode.UNAVAILABLE));
        return new RateLimitUnavailableException(decision);
    }

    /** Builds the {@link RateLimitDecision} shape shared by every completion path on this handle. */
    private RateLimitDecision buildDecision(
            RateLimitOutcome outcome,
            TokenBucketRateLimit algorithm,
            OptionalLong remaining,
            Optional<Duration> retryAfter,
            Optional<Duration> resetAfter,
            Optional<RateLimitFailureCode> failureCode) {
        return new RateLimitDecision(
                policy.name(),
                outcome,
                policy.mode(),
                RateLimitAlgorithmType.TOKEN_BUCKET,
                algorithm.capacity(),
                remaining,
                retryAfter,
                resetAfter,
                failureCode);
    }

    /** Dispatches the {@link RateLimitDecisionCompleted} event shared by every completion path, when observed. */
    private void emitIfObserved(
            RateLimitOutcome outcome,
            TokenBucketRateLimit algorithm,
            long cost,
            OptionalLong remaining,
            Optional<Duration> retryAfter,
            Optional<Duration> resetAfter,
            Optional<RateLimitFailureCode> failureCode,
            long backendLatencyNanos) {
        if (observers.isEmpty()) {
            return;
        }
        RateLimitObservationSupport.emit(
                observers,
                new RateLimitDecisionCompleted(
                        policy.name(),
                        policy.revision(),
                        policy.mode(),
                        RateLimitAlgorithmType.TOKEN_BUCKET,
                        outcome,
                        cost,
                        algorithm.capacity(),
                        remaining,
                        retryAfter,
                        resetAfter,
                        failureCode,
                        backendLatencyNanos));
    }

    private static long elapsedNanosSince(long startedAt) {
        return Math.max(0L, System.nanoTime() - startedAt);
    }

    /**
     * Dispatches completion on the calling Vert.x context, when one is present, per
     * contracts/rate-limit-runtime.md's async contract; without one, no thread affinity is
     * promised.
     */
    private static <T> Future<T> dispatchOnCallingContext(Future<T> future) {
        Context context = Vertx.currentContext();
        if (context == null) {
            return future;
        }
        Promise<T> dispatched = Promise.promise();
        future.onComplete(result -> context.runOnContext(ignored -> {
            if (result.succeeded()) {
                dispatched.tryComplete(result.result());
            } else {
                dispatched.tryFail(result.cause());
            }
        }));
        return dispatched.future();
    }
}
