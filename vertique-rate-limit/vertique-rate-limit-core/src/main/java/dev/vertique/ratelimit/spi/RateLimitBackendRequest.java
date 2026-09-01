// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.spi;

/**
 * One admission request against a {@link RateLimitBackend}.
 *
 * <p><strong>Deliberately minimal.</strong> {@code contracts/rate-limit-runtime.md}'s "Backend
 * seam" shows this record carrying a {@code TokenBucketRateLimit algorithm} field; that sealed
 * algorithm type is a later task's artifact. This task's required context marks only the
 * {@code RateLimitModeKey}/LOCAL-binding subset of that section as normative, so this task
 * carries the one greedy token-bucket bandwidth's fields directly instead.
 *
 * @param storageKey canonical per-policy, per-key storage identity
 * @param capacity token-bucket capacity
 * @param refillTokens tokens added per {@code refillPeriodMs}
 * @param refillPeriodMs refill period in milliseconds
 * @param cost tokens this request attempts to consume
 */
public record RateLimitBackendRequest(
        String storageKey, long capacity, long refillTokens, long refillPeriodMs, long cost) {}
