// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.spi.event;

import dev.vertique.ratelimit.RateLimitAlgorithmType;
import dev.vertique.ratelimit.RateLimitFailureCode;
import dev.vertique.ratelimit.RateLimitMode;
import dev.vertique.ratelimit.RateLimitOutcome;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The single terminal observation for one completed rate-limit admission decision
 * (contracts/rate-limit-runtime.md, "Observer SPI").
 *
 * @param policyName the policy this decision was resolved against
 * @param policyRevision the resolved policy's revision at decision time
 * @param mode where the policy's admission state lives
 * @param algorithm the policy's admission algorithm family
 * @param outcome the normalized decision outcome
 * @param cost tokens this request attempted to consume
 * @param capacity the policy's token-bucket capacity
 * @param remaining whole tokens available immediately after this decision, when known
 * @param retryAfter present and positive only for {@link RateLimitOutcome#QUOTA_EXCEEDED}
 * @param resetAfter hint duration until the bucket is full again absent further consumption
 * @param failureCode present only when the backend itself failed, not on an ordinary rejection
 * @param backendLatencyNanos elapsed time spent in the backend for this decision, in nanoseconds
 */
public record RateLimitDecisionCompleted(
        String policyName,
        String policyRevision,
        RateLimitMode mode,
        RateLimitAlgorithmType algorithm,
        RateLimitOutcome outcome,
        long cost,
        long capacity,
        OptionalLong remaining,
        Optional<Duration> retryAfter,
        Optional<Duration> resetAfter,
        Optional<RateLimitFailureCode> failureCode,
        long backendLatencyNanos)
        implements RateLimitEvent {

    public RateLimitDecisionCompleted {
        Objects.requireNonNull(policyName, "policyName");
        Objects.requireNonNull(policyRevision, "policyRevision");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(remaining, "remaining");
        Objects.requireNonNull(retryAfter, "retryAfter");
        Objects.requireNonNull(resetAfter, "resetAfter");
        Objects.requireNonNull(failureCode, "failureCode");
    }
}
