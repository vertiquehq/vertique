// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.service;

/**
 * Result returned by both the programmatic ({@link RateLimitProbeService}-independent) and the
 * {@code @RateLimited}-annotated probe paths, so a caller can compare the two side by side.
 *
 * @param outcome the programmatic path's {@code RateLimitDecision.outcome()} name, or {@code
 *     "ADMITTED"} for the annotated path (which only ever returns once the runtime has already
 *     admitted the call — a denial surfaces as a failed future instead, see {@link
 *     RateLimitProbeService})
 */
public record RateLimitProbeResult(String outcome) {}
