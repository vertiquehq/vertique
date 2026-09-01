// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed native refill family a {@link TokenBucketRateLimit} uses
 * (contracts/rate-limit-runtime.md, "Policy model" and "Bucket4j translation contract"). Permits
 * exactly {@link GreedyRateLimitRefill} and {@link IntervalRateLimitRefill} — no
 * application-defined member is permitted.
 *
 * <p>Jackson polymorphic typing is configured via the {@code "type"} property discriminator so
 * {@code rateLimit.policies.<name>.algorithm.refill.type} (values {@code GREEDY}/{@code INTERVAL})
 * selects the concrete implementation during typed config binding.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = GreedyRateLimitRefill.class, name = "GREEDY"),
    @JsonSubTypes.Type(value = IntervalRateLimitRefill.class, name = "INTERVAL")
})
public sealed interface RateLimitRefill permits GreedyRateLimitRefill, IntervalRateLimitRefill {}
