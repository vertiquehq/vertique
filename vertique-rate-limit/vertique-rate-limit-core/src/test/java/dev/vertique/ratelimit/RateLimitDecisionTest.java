// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** TP-002: {@link RateLimitDecision#permitted()} must distinguish admitting from denying outcomes. */
class RateLimitDecisionTest {

    @Test
    void shouldExposePermittedOnlyForNonDenyingOutcomes() {
        RateLimitDecision permitted = decisionFor(RateLimitOutcome.PERMITTED, Optional.empty(), Optional.empty());
        RateLimitDecision quotaExceeded =
                decisionFor(RateLimitOutcome.QUOTA_EXCEEDED, Optional.of(Duration.ofSeconds(1)), Optional.empty());
        RateLimitDecision backendFailureOpen =
                decisionFor(RateLimitOutcome.BACKEND_FAILURE_OPEN, Optional.empty(), Optional.empty());
        RateLimitDecision backendFailureClosed = decisionFor(
                RateLimitOutcome.BACKEND_FAILURE_CLOSED,
                Optional.empty(),
                Optional.of(RateLimitFailureCode.UNAVAILABLE));
        RateLimitDecision disabled = decisionFor(RateLimitOutcome.DISABLED, Optional.empty(), Optional.empty());

        assertThat(permitted.permitted()).as("PERMITTED").isTrue();
        assertThat(quotaExceeded.permitted()).as("QUOTA_EXCEEDED").isFalse();
        assertThat(backendFailureOpen.permitted()).as("BACKEND_FAILURE_OPEN").isTrue();
        assertThat(backendFailureClosed.permitted())
                .as("BACKEND_FAILURE_CLOSED")
                .isFalse();
        assertThat(disabled.permitted()).as("DISABLED").isTrue();
    }

    private static RateLimitDecision decisionFor(
            RateLimitOutcome outcome, Optional<Duration> retryAfter, Optional<RateLimitFailureCode> failureCode) {
        return new RateLimitDecision(
                "walking-skeleton",
                outcome,
                RateLimitMode.LOCAL,
                RateLimitAlgorithmType.TOKEN_BUCKET,
                2L,
                OptionalLong.of(0L),
                retryAfter,
                Optional.empty(),
                failureCode);
    }
}
