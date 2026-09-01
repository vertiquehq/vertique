// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.spi.event;

/**
 * Redacted rate-limit observation event emitted by the shared runtime.
 *
 * <p>Events carry only bounded, policy-level data: the policy identity, mode, algorithm, decision
 * outcome, quota/cost figures, and timing. They never contain a raw or derived {@code
 * RateLimitKey}, a caller identity, an IP, a MAC, a Redis key/endpoint, or an exception.
 */
public sealed interface RateLimitEvent permits RateLimitDecisionCompleted {}
