// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

/** Where a policy's admission state lives. */
public enum RateLimitMode {
    /** In-process Bucket4j state; per-instance, resets on restart. */
    LOCAL,
    /** Shared Redis-backed state, atomic per key across the fleet. */
    CLUSTERED
}
