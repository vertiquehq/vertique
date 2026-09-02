// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

/** Normalized result of one admission decision. */
public enum RateLimitOutcome {
    /** The request was consumed within capacity. */
    PERMITTED,
    /** The request exceeded the policy's current quota. */
    QUOTA_EXCEEDED,
    /** The backend failed and the policy's {@code OPEN} failure mode admitted the request. */
    BACKEND_FAILURE_OPEN,
    /** The backend failed and the policy's {@code CLOSED} failure mode denied the request. */
    BACKEND_FAILURE_CLOSED,
    /** The policy is disabled; every request is admitted without engaging the engine. */
    DISABLED
}
