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
 * <p>Thrown by {@code execute(...)} for a {@code BACKEND_FAILURE_CLOSED} decision.
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
