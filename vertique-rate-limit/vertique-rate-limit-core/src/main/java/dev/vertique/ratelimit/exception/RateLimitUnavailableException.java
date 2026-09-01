// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.exception;

import dev.vertique.core.exception.UnavailableException;
import dev.vertique.ratelimit.RateLimitDecision;
import java.util.Objects;

/**
 * Guarded-execution fail-closed backend failure. Exposes only the safe {@link RateLimitDecision};
 * internal causes never cross this boundary.
 *
 * <p>This task mints this type only — {@code execute(...)} throwing it is a later task's
 * behavior (contracts/rate-limit-runtime.md, "Exceptions").
 */
public final class RateLimitUnavailableException extends UnavailableException {

    private static final String MESSAGE = "Rate limit backend unavailable";

    private final RateLimitDecision decision;

    public RateLimitUnavailableException(RateLimitDecision decision) {
        super(MESSAGE);
        this.decision = Objects.requireNonNull(decision, "decision");
    }

    public RateLimitDecision decision() {
        return decision;
    }
}
