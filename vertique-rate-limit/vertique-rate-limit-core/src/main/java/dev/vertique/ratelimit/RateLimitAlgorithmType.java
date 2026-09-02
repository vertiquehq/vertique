// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

/** Admission algorithm family a policy uses. v1 permits only token bucket. */
public enum RateLimitAlgorithmType {
    TOKEN_BUCKET
}
