// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.event.CacheOperation;
import dev.vertique.cache.spi.event.CacheOutcome;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CacheObservationSupportTest {

    @Test
    void observerFailureDoesNotEscapeTheCacheObservationBoundary() {
        CacheObserver failingObserver = observation -> {
            throw new AssertionError("telemetry failure");
        };

        assertDoesNotThrow(() -> CacheObservationSupport.completed(
                Set.of(failingObserver),
                "caffeine",
                CacheOperation.GET,
                "profiles",
                CacheOutcome.HIT,
                System.nanoTime() - Duration.ofMillis(1).toNanos()));
    }
}
