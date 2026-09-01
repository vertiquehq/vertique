// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

/**
 * A policy's explicit backend-failure behavior (contracts/rate-limit-runtime.md, "Policy model").
 *
 * <p>Has no default anywhere — every policy, enabled or disabled, must declare one explicitly
 * (FR-006, {@code spec.md} §5.5).
 */
public enum RateLimitFailureMode {
    /** Backend failure permits the request (best-effort; not a hard security boundary). */
    OPEN,
    /** Backend failure denies the request (protects admission; makes the backend an availability dependency). */
    CLOSED
}
