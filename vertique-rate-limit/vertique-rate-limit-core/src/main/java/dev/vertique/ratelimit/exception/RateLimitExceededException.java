// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.exception;

import dev.vertique.core.exception.BusinessRuleException;
import dev.vertique.ratelimit.RateLimitDecision;
import java.util.Objects;

/**
 * Guarded-execution quota denial. Exposes only the safe {@link RateLimitDecision}; internal
 * causes never cross this boundary.
 *
 * <p>This task mints this type only — {@code execute(...)} throwing it is a later task's
 * behavior (contracts/rate-limit-runtime.md, "Exceptions").
 */
public final class RateLimitExceededException extends BusinessRuleException {

    private static final String MESSAGE = "Rate limit quota exceeded";

    private final RateLimitDecision decision;

    public RateLimitExceededException(RateLimitDecision decision) {
        super(MESSAGE);
        this.decision = Objects.requireNonNull(decision, "decision");
    }

    public RateLimitDecision decision() {
        return decision;
    }
}
