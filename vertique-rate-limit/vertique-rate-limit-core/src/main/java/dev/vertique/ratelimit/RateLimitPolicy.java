// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

/**
 * One declared token-bucket policy.
 *
 * <p><strong>Deliberately minimal and non-final.</strong> This is not yet the exact public record
 * {@code contracts/rate-limit-runtime.md}'s "Policy model" describes: {@code failureMode},
 * {@code defaultCost}, and the sealed {@code RateLimitAlgorithm}/{@code RateLimitRefill} hierarchy
 * are a later task's artifacts. {@code revision} is frozen from this task onward — a later task
 * reads it to derive storage identity. {@code capacity}/{@code refillTokens}/{@code
 * refillPeriodMs} describe one greedy token-bucket bandwidth, sufficient for the local Bucket4j
 * translation this task owns; a later task replaces them with the sealed algorithm/refill types.
 */
public record RateLimitPolicy(
        String name,
        boolean enabled,
        RateLimitMode mode,
        String revision,
        long capacity,
        long refillTokens,
        long refillPeriodMs) {}
