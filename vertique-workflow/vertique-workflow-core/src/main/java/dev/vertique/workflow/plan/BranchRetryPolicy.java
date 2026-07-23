// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import java.time.Duration;
import java.util.Objects;

/**
 * Branch-level retry policy attached to a {@link ForkNode}.
 *
 * <p>The policy applies to all branches in the fork group; per-branch retry overrides are not
 * supported in the first PRD-WF-002 release. The policy controls retry of branch <em>orchestration
 * failures</em> only — outbox delivery retry and service-client transient retry remain owned by
 * the outbox relay and the service-client resilience layer respectively (PRD-WF-002 §A.4.3).
 *
 * @param maxAttempts maximum number of attempts (≥ 1); {@code 1} disables retry. The branch is
 *     marked {@code FAILED} after exhausting the budget.
 * @param initialDelay base retry delay; combined with {@code backoff} to produce the next-retry
 *     timestamp. Must be non-negative.
 * @param backoff backoff strategy applied to {@code initialDelay} when computing successive
 *     retries
 */
public record BranchRetryPolicy(int maxAttempts, Duration initialDelay, BackoffStrategy backoff) {

    /**
     * Compact constructor enforcing the invariants documented above.
     */
    public BranchRetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        Objects.requireNonNull(initialDelay, "initialDelay");
        if (initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must be non-negative, got " + initialDelay);
        }
        Objects.requireNonNull(backoff, "backoff");
    }

    /**
     * Backoff strategy applied to {@link #initialDelay} when computing successive retry delays.
     */
    public enum BackoffStrategy {
        /** Each retry waits exactly {@code initialDelay}. */
        FIXED,

        /** Retry {@code n} (1-based) waits {@code initialDelay × 2^(n-1)}. */
        EXPONENTIAL
    }

    // --- Convenience factories ---

    /**
     * Starts a fluent builder for a policy with the given maximum attempts.
     *
     * @param maxAttempts maximum number of attempts (≥ 1)
     * @return a fluent builder
     */
    public static Builder maxAttempts(int maxAttempts) {
        return new Builder(maxAttempts);
    }

    /**
     * Returns a no-retry policy (single attempt; failures terminate the branch immediately).
     *
     * @return a policy with {@code maxAttempts=1}
     */
    public static BranchRetryPolicy none() {
        return new BranchRetryPolicy(1, Duration.ZERO, BackoffStrategy.FIXED);
    }

    /**
     * Fluent builder for {@link BranchRetryPolicy}.
     */
    public static final class Builder {
        private final int maxAttempts;
        private Duration initialDelay = Duration.ofSeconds(1);
        private BackoffStrategy backoff = BackoffStrategy.EXPONENTIAL;

        private Builder(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        /**
         * Sets the base retry delay.
         *
         * @param initialDelay the base delay (non-negative)
         * @return this builder
         */
        public Builder initialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
            return this;
        }

        /**
         * Sets the backoff strategy.
         *
         * @param backoff the backoff strategy
         * @return this builder
         */
        public Builder backoff(BackoffStrategy backoff) {
            this.backoff = backoff;
            return this;
        }

        /**
         * Builds the immutable policy.
         *
         * @return the policy
         */
        public BranchRetryPolicy build() {
            return new BranchRetryPolicy(maxAttempts, initialDelay, backoff);
        }
    }
}
