// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.ratelimit;

import java.time.Duration;

/**
 * Shared HTTP-mapping constants and the {@code Retry-After} rounding rule
 * (contracts/rest-adapter.md, "HTTP mapping") used identically by both {@link
 * RateLimitExceptionMapper} (the {@code execute()}/exception-mapping path) and {@link
 * RateLimitEdgeMiddleware} (the edge decision-mapping path) — kept as one definition so the two
 * independent HTTP-mapping call sites cannot silently drift apart.
 */
final class RateLimitHttpMapping {

    static final String PROBLEM_JSON = "application/problem+json";
    static final String CACHE_CONTROL_HEADER = "Cache-Control";
    static final String CACHE_CONTROL_NO_STORE = "no-store";
    static final String RETRY_AFTER_HEADER = "Retry-After";

    private static final long MILLIS_PER_SECOND = 1000L;

    private RateLimitHttpMapping() {}

    /**
     * {@code max(1, ceil(retryAfter.toMillis() / 1000))}, computed with integer division so no
     * floating-point rounding can drift the boundary (e.g. 1500ms -&gt; 2s, 2001ms -&gt; 3s).
     *
     * @param retryAfter the duration to round; treated as zero when negative
     * @return the whole-second {@code Retry-After} value, always {@code >= 1}
     */
    static long retryAfterSeconds(Duration retryAfter) {
        long millis = Math.max(0L, retryAfter.toMillis());
        return Math.max(1L, Math.ceilDiv(millis, MILLIS_PER_SECOND));
    }
}
