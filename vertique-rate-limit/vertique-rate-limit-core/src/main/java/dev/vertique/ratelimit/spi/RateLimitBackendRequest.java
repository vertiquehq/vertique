// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.spi;

import dev.vertique.ratelimit.TokenBucketRateLimit;

/**
 * One admission request against a {@link RateLimitBackend} (contracts/rate-limit-runtime.md,
 * "Backend seam" — the exact contract-final shape).
 *
 * @param storageKey canonical per-policy, per-key storage identity
 * @param algorithm the requesting policy's token-bucket algorithm
 * @param cost tokens this request attempts to consume
 */
public record RateLimitBackendRequest(String storageKey, TokenBucketRateLimit algorithm, long cost) {}
