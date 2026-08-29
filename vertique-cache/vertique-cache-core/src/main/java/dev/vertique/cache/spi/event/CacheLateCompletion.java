// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi.event;

import java.time.Duration;
import java.util.Objects;

/**
 * A bounded supplement emitted when a provider future settles after its deadline
 * already produced a {@link CacheOutcome#TIMEOUT} terminal event. At most one late
 * event follows one timed-out operation.
 *
 * @param operation the runtime operation whose deadline expired
 * @param provider the resolved provider id
 * @param cacheName the logical cache name
 * @param outcome how the late settlement resolved: {@link CacheOutcome#HIT},
 *     {@link CacheOutcome#MISS}, {@link CacheOutcome#SUCCESS}, or
 *     {@link CacheOutcome#ERROR}
 * @param elapsed elapsed time from operation start to the late settlement
 */
public record CacheLateCompletion(
        CacheOperation operation, String provider, String cacheName, CacheOutcome outcome, Duration elapsed)
        implements CacheEvent {
    public CacheLateCompletion {
        Objects.requireNonNull(operation, "operation");
        EventValidation.requireLabel(provider, "provider");
        EventValidation.requireLabel(cacheName, "cacheName");
        Objects.requireNonNull(outcome, "outcome");
        if (outcome != CacheOutcome.HIT
                && outcome != CacheOutcome.MISS
                && outcome != CacheOutcome.SUCCESS
                && outcome != CacheOutcome.ERROR) {
            throw new IllegalArgumentException("late completion outcome must be a settlement outcome: " + outcome);
        }
        EventValidation.requireElapsed(elapsed);
    }
}
