// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi.event;

import java.time.Duration;
import java.util.Objects;

/**
 * The single terminal observation for one attempted cache runtime operation.
 *
 * @param operation the runtime operation
 * @param provider the resolved provider id, or {@code none} before provider selection
 * @param cacheName the logical cache name
 * @param outcome the terminal outcome
 * @param elapsed elapsed time from operation start to this outcome
 */
public record CacheOperationCompleted(
        CacheOperation operation, String provider, String cacheName, CacheOutcome outcome, Duration elapsed)
        implements CacheEvent {
    public CacheOperationCompleted {
        Objects.requireNonNull(operation, "operation");
        EventValidation.requireLabel(provider, "provider");
        EventValidation.requireLabel(cacheName, "cacheName");
        Objects.requireNonNull(outcome, "outcome");
        EventValidation.requireElapsed(elapsed);
    }
}
