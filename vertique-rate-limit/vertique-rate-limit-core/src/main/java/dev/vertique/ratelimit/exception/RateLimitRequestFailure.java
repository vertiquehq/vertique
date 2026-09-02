// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.exception;

/**
 * Bounded reason a {@link RateLimitRequestException} was thrown. {@code UNKNOWN_POLICY} is
 * deliberately absent: an unknown policy name fails synchronously at handle creation
 * ({@code RateLimiters.limiter(...)}), not as a request failure (contracts/rate-limit-runtime.md,
 * "Exceptions").
 */
public enum RateLimitRequestFailure {
    COST_EXCEEDS_CAPACITY,
    SUBJECT_UNRESOLVABLE
}
