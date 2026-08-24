// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dev.vertique.cache.spi.CacheObservation;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.CacheRegion;
import java.time.Duration;
import java.util.Set;

/** Internal observer fan-out that isolates optional diagnostics from cache behavior. */
final class CacheObservationSupport {

    private CacheObservationSupport() {}

    static void observe(
            Set<CacheObserver> observers, String operation, CacheRegion region, String outcome, long startedAt) {
        CacheObservation observation = new CacheObservation(
                operation,
                "provider-neutral",
                region.name(),
                outcome,
                Duration.ofNanos(Math.max(0, System.nanoTime() - startedAt)));
        for (CacheObserver observer : observers) {
            try {
                observer.onOperation(observation);
            } catch (Throwable ignored) {
                // Observer failures must never alter cache or business outcomes.
            }
        }
    }
}
