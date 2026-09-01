// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import io.vertx.core.Future;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Handle bound to one policy plus a caller-supplied key-selector function, resolved from {@link
 * RateLimiters#limiter(String, Function)}.
 *
 * <p>Exposes the same {@code acquire}/{@code execute}/{@code policyName} shape as {@link
 * RateLimiter} and applies its selector function to derive the {@link RateLimitKey} before
 * dispatching through the same underlying decision path {@link RateLimiter#acquire(RateLimitKey,
 * long)} exercises — not a separate or unimplemented code path (contracts/rate-limit-runtime.md,
 * "Exact API shape").
 *
 * @param <K> the caller-supplied input type
 */
public final class KeyedRateLimiter<K> {

    private final RateLimiter delegate;
    private final Function<? super K, RateLimitKey> keySelector;

    KeyedRateLimiter(RateLimiter delegate, Function<? super K, RateLimitKey> keySelector) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.keySelector = Objects.requireNonNull(keySelector, "keySelector");
    }

    /**
     * @return the policy name this handle was resolved for
     */
    public String policyName() {
        return delegate.policyName();
    }

    /**
     * Derives a {@link RateLimitKey} from {@code input} via this handle's selector function, then
     * attempts to consume {@code policyName()}'s default cost against it through the same decision
     * path {@link RateLimiter#acquire(RateLimitKey)} uses.
     *
     * @param input the caller-supplied selector input
     * @return a future that completes with the decision, dispatched per {@link
     *     RateLimiter#acquire(RateLimitKey)}'s contract
     */
    public Future<RateLimitDecision> acquire(K input) {
        return delegate.acquire(keySelector.apply(input));
    }

    /**
     * Derives a {@link RateLimitKey} from {@code input} via this handle's selector function, then
     * attempts to consume {@code cost} tokens against it through the same decision path {@link
     * RateLimiter#acquire(RateLimitKey, long)} uses.
     *
     * @param input the caller-supplied selector input
     * @param cost tokens this request attempts to consume
     * @return a future that completes with the decision, dispatched per {@link
     *     RateLimiter#acquire(RateLimitKey, long)}'s contract
     */
    public Future<RateLimitDecision> acquire(K input, long cost) {
        return delegate.acquire(keySelector.apply(input), cost);
    }

    /**
     * Derives a {@link RateLimitKey} from {@code input}, then guards {@code action} through the
     * same decision path {@link RateLimiter#execute(RateLimitKey, Supplier)} uses.
     *
     * @param input the caller-supplied selector input
     * @param action the guarded action; invoked at most once, never retried, never refunded
     * @param <T> the action's result type
     * @return a future completing with the action's result, or the mapped denial/failure
     */
    public <T> Future<T> execute(K input, Supplier<Future<T>> action) {
        return delegate.execute(keySelector.apply(input), action);
    }

    /**
     * Derives a {@link RateLimitKey} from {@code input}, then guards {@code action} through the
     * same decision path {@link RateLimiter#execute(RateLimitKey, long, Supplier)} uses.
     *
     * @param input the caller-supplied selector input
     * @param cost tokens this request attempts to consume
     * @param action the guarded action; invoked at most once, never retried, never refunded
     * @param <T> the action's result type
     * @return a future completing with the action's result, or the mapped denial/failure
     */
    public <T> Future<T> execute(K input, long cost, Supplier<Future<T>> action) {
        return delegate.execute(keySelector.apply(input), cost, action);
    }
}
