// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.ratelimit;

/**
 * One composable key dimension an edge {@link RateLimitEdgeRule} draws on when building its
 * {@code RateLimitKey} (contracts/rest-adapter.md, "Edge limiter").
 *
 * <p>{@link #GLOBAL} is the empty dimension and is valid only alone in a rule's {@code key} list.
 * {@link #IP} and {@link #HEADER} compose, in declared order, into one key.
 */
public enum RateLimitEdgeKeyDimension {
    /** The empty dimension: every request shares one bucket. Valid only alone. */
    GLOBAL,
    /** Keys on the caller's origin-resolved client IP (see {@code RequestOrigin.clientIp()}). */
    IP,
    /** Keys on a named request header's value. */
    HEADER
}
