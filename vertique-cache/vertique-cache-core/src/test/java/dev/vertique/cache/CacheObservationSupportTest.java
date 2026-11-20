// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void fatalObserverFailuresAreNotCaught() {
        CacheObserver virtualMachineFailure = observation -> {
            throw new InternalError("virtual machine failure");
        };
        CacheObserver linkageFailure = observation -> {
            throw new NoClassDefFoundError("linkage failure");
        };

        assertThrows(InternalError.class, () -> emit(virtualMachineFailure));
        assertThrows(LinkageError.class, () -> emit(linkageFailure));
    }

    private static void emit(CacheObserver observer) {
        CacheObservationSupport.completed(
                Set.of(observer),
                "caffeine",
                CacheOperation.GET,
                "profiles",
                CacheOutcome.HIT,
                System.nanoTime() - Duration.ofMillis(1).toNanos());
    }
}
