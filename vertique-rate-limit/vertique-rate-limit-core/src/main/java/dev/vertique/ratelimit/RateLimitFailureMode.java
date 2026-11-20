// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

/**
 * A policy's explicit backend-failure behavior.
 *
 * <p>Has no default anywhere — every policy, enabled or disabled, must declare one explicitly.
 */
public enum RateLimitFailureMode {
    /** Backend failure permits the request (best-effort; not a hard security boundary). */
    OPEN,
    /** Backend failure denies the request (protects admission; makes the backend an availability dependency). */
    CLOSED
}
