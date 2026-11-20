// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.exception;

import dev.vertique.core.exception.BusinessRuleException;
import java.util.Objects;

/**
 * Safe programmatic request failure with a bounded reason. Messages are fixed from {@link
 * RateLimitRequestFailure} values only; no cause or request key is exposed.
 *
 * <p>Thrown for {@code COST_EXCEEDS_CAPACITY} cost validation.
 */
public final class RateLimitRequestException extends BusinessRuleException {

    private static final String MESSAGE = "Rate limit request failed";

    private final RateLimitRequestFailure reason;

    public RateLimitRequestException(RateLimitRequestFailure reason) {
        super(MESSAGE);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public RateLimitRequestFailure reason() {
        return reason;
    }
}
