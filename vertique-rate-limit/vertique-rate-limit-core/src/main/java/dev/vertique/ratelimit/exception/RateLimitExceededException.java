// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.exception;

import dev.vertique.core.exception.TooManyRequestsException;
import dev.vertique.ratelimit.RateLimitDecision;
import java.util.Objects;

/**
 * Guarded-execution quota denial. Exposes only the safe {@link RateLimitDecision}; internal
 * causes never cross this boundary.
 *
 * <p>Extends {@link TooManyRequestsException} (T020) so an application/framework graph with no
 * rate-limit-specific {@code ExceptionMapper} installed still renders this as {@code 429} — with
 * {@code Retry-After} threaded from {@code decision.retryAfter()} — via the framework's default
 * exception mapper, wired by {@code RestModule}. {@link
 * TooManyRequestsException} extends {@code VertiqueException} directly (T021 W1) — never {@code
 * BusinessRuleException}/{@code ValidationException} — so an application's own {@code
 * ExceptionMapper<ValidationException>} can never out-rank that 429 default for this exception.
 */
public final class RateLimitExceededException extends TooManyRequestsException {

    private static final String MESSAGE = "Rate limit quota exceeded";

    private final RateLimitDecision decision;

    public RateLimitExceededException(RateLimitDecision decision) {
        super(MESSAGE, Objects.requireNonNull(decision, "decision").retryAfter().orElse(null));
        this.decision = decision;
    }

    public RateLimitDecision decision() {
        return decision;
    }
}
