// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.event.CacheEvent;
import dev.vertique.cache.spi.event.CacheLateCompletion;
import dev.vertique.cache.spi.event.CacheOperation;
import dev.vertique.cache.spi.event.CacheOperationCompleted;
import dev.vertique.cache.spi.event.CacheOutcome;
import java.time.Duration;
import java.util.Set;

/** Internal observer fan-out that isolates optional diagnostics from cache behavior. */
final class CacheObservationSupport {

    private CacheObservationSupport() {}

    static void completed(
            Set<CacheObserver> observers,
            String provider,
            CacheOperation operation,
            String cacheName,
            CacheOutcome outcome,
            long startedAt) {
        emit(observers, new CacheOperationCompleted(operation, provider, cacheName, outcome, elapsed(startedAt)));
    }

    static void late(
            Set<CacheObserver> observers,
            String provider,
            CacheOperation operation,
            String cacheName,
            CacheOutcome outcome,
            long startedAt) {
        emit(observers, new CacheLateCompletion(operation, provider, cacheName, outcome, elapsed(startedAt)));
    }

    static void emit(Set<CacheObserver> observers, CacheEvent event) {
        for (CacheObserver observer : observers) {
            try {
                observer.onEvent(event);
            } catch (Throwable failure) {
                if (isFatal(failure)) {
                    throw (Error) failure;
                }
                // Observer failures must never alter cache or business outcomes.
            }
        }
    }

    @SuppressWarnings("removal")
    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError
                || failure instanceof ThreadDeath
                || failure instanceof LinkageError;
    }

    private static Duration elapsed(long startedAt) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - startedAt));
    }
}
