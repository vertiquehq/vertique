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
 * {@code Retry-After} threaded from {@code decision.retryAfter()} — via
 * {@code RestModule.defaultExceptionMapper()}'s core default mapping. Still a
 * {@code BusinessRuleException}/{@code ValidationException} subtype (behavior-compatible with
 * callers matching on either supertype), since {@link TooManyRequestsException} itself extends
 * {@code BusinessRuleException}.
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
