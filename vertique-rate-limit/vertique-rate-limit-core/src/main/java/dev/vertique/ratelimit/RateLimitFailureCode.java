// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

/**
 * Bounded normalization of an internal backend/engine failure. Causes and messages never cross
 * this boundary; only this fixed classification does.
 */
public enum RateLimitFailureCode {
    TIMEOUT,
    UNAVAILABLE,
    CAPACITY_EXHAUSTED,
    CONTENTION_EXHAUSTED,
    MALFORMED_STATE,
    INTERNAL
}
