// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendRequest;
import dev.vertique.ratelimit.spi.RateLimitBackendResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Unkeyed handle bound to one policy, resolved from {@link RateLimiters#limiter(String)}.
 *
 * <p>This task's shape is a subset of the exact contract API: only {@link #policyName()} and
 * {@link #acquire(RateLimitKey)} exist here. The cost-bearing overloads and the guarded {@code
 * execute(...)} wrapper are a later task's artifacts (contracts/rate-limit-runtime.md, "Exact API
 * shape").
 */
public final class RateLimiter {

    private static final long DEFAULT_COST = 1L;

    private final RateLimitPolicy policy;
    private final RateLimitBackend backend;
    private final Vertx vertx;

    RateLimiter(RateLimitPolicy policy, RateLimitBackend backend, Vertx vertx) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.vertx = Objects.requireNonNull(vertx, "vertx");
    }

    /**
     * @return the policy name this handle was resolved for
     */
    public String policyName() {
        return policy.name();
    }

    /**
     * Attempts to consume one token against {@code key}. Never fails the returned future for
     * quota reasons — it always yields a {@link RateLimitDecision} (decision-first).
     *
     * @param key the caller-supplied key
     * @return a future that completes with the decision, dispatched on the calling Vert.x context
     *     when one is present
     */
    public Future<RateLimitDecision> acquire(RateLimitKey key) {
        Objects.requireNonNull(key, "key");
        TokenBucketRateLimit algorithm = (TokenBucketRateLimit) policy.algorithm();
        String storageKey = policy.name() + ':' + policy.revision() + ':' + key.canonicalEncoding();
        RateLimitBackendRequest request = new RateLimitBackendRequest(storageKey, algorithm, DEFAULT_COST);
        return dispatchOnCallingContext(backend.consume(request).map(result -> toDecision(result, algorithm)));
    }

    private RateLimitDecision toDecision(RateLimitBackendResult result, TokenBucketRateLimit algorithm) {
        RateLimitOutcome outcome = result.consumed() ? RateLimitOutcome.PERMITTED : RateLimitOutcome.QUOTA_EXCEEDED;
        return new RateLimitDecision(
                policy.name(),
                outcome,
                policy.mode(),
                RateLimitAlgorithmType.TOKEN_BUCKET,
                algorithm.capacity(),
                OptionalLong.of(result.remaining()),
                result.retryAfter(),
                result.resetAfter(),
                result.failureCode());
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
