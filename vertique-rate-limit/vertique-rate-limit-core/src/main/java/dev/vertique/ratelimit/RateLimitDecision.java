// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Normalized, safe outcome of one admission decision plus optional quota timing.
 *
 * <p>{@code remaining} is the number of whole tokens available immediately after the decision.
 * {@code retryAfter} is present and positive only for {@link RateLimitOutcome#QUOTA_EXCEEDED}.
 * {@code resetAfter} is a hint (not a reservation) for the duration until the bucket is full again
 * absent further consumption. Both are relative durations.
 *
 * <p>This record's shape is frozen from the package's baseline task onward; no later task changes
 * it (contracts/rate-limit-runtime.md, "Decision model").
 */
public record RateLimitDecision(
        String policyName,
        RateLimitOutcome outcome,
        RateLimitMode mode,
        RateLimitAlgorithmType algorithm,
        long capacity,
        OptionalLong remaining,
        Optional<Duration> retryAfter,
        Optional<Duration> resetAfter,
        Optional<RateLimitFailureCode> failureCode) {

    /**
     * @return {@code true} for {@link RateLimitOutcome#PERMITTED}, {@link
     *     RateLimitOutcome#BACKEND_FAILURE_OPEN}, and {@link RateLimitOutcome#DISABLED}; {@code
     *     false} for {@link RateLimitOutcome#QUOTA_EXCEEDED} and {@link
     *     RateLimitOutcome#BACKEND_FAILURE_CLOSED}
     */
    public boolean permitted() {
        return outcome == RateLimitOutcome.PERMITTED
                || outcome == RateLimitOutcome.BACKEND_FAILURE_OPEN
                || outcome == RateLimitOutcome.DISABLED;
    }
}
