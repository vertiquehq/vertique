// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.CacheRegion;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CacheObservationSupportTest {

    @Test
    void observerFailureDoesNotEscapeTheCacheObservationBoundary() {
        CacheObserver failingObserver = observation -> {
            throw new AssertionError("telemetry failure");
        };

        assertDoesNotThrow(() -> CacheObservationSupport.observe(
                Set.of(failingObserver),
                "caffeine",
                "get",
                new CacheRegion("cache", "profiles", 1),
                "hit",
                System.nanoTime() - Duration.ofMillis(1).toNanos()));
    }
}
