// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.cache.spi.event.CacheCleanupCompleted;
import dev.vertique.cache.spi.event.CacheOperation;
import dev.vertique.cache.spi.event.CacheOperationCompleted;
import dev.vertique.cache.spi.event.CacheOutcome;
import dev.vertique.micrometer.MetricsConfig;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CacheObserverTest {

    @Test
    void emitsBoundedOutcomesWithoutSensitiveAttributes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheMetricsObserver observer =
                new CacheMetricsObserver(registry, MetricsConfig.builder().build());

        observer.onEvent(new CacheOperationCompleted(
                CacheOperation.GET, "caffeine", "profiles", CacheOutcome.HIT, Duration.ofMillis(1)));

        Meter meter = registry.find("cache.get")
                .tag("provider", "caffeine")
                .tag("cache", "profiles")
                .tag("outcome", "hit")
                .meter();
        assertNotNull(meter);
        String tags = meter.getId().getTags().toString();
        assertFalse(tags.contains("raw-selector"));
        assertFalse(tags.contains("payload"));
        assertFalse(tags.contains("principal"));
    }

    @Test
    void doesNotRecordWhenMetricsAreDisabled() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheMetricsObserver observer = new CacheMetricsObserver(
                registry, MetricsConfig.builder().enabled(false).build());

        observer.onEvent(new CacheOperationCompleted(
                CacheOperation.GET, "redis", "profiles", CacheOutcome.MISS, Duration.ofMillis(1)));
        observer.onEvent(new CacheCleanupCompleted("primary", "profiles", "success", 4, 3, 1, false));

        assertFalse(registry.getMeters().stream()
                .anyMatch(meter -> meter.getId().getName().startsWith("cache.")));
    }

    @Test
    void registryFailureDoesNotEscapeTheObserver() {
        CacheMetricsObserver observer = new CacheMetricsObserver(
                org.mockito.Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class),
                MetricsConfig.builder().build());

        assertDoesNotThrow(() -> observer.onEvent(new CacheOperationCompleted(
                CacheOperation.GET, "redis", "profiles", CacheOutcome.ERROR, Duration.ofMillis(1))));
        assertDoesNotThrow(
                () -> observer.onEvent(new CacheCleanupCompleted("primary", "profiles", "failure", 0, 0, 1, true)));
    }

    @Test
    void recordsCleanupCountsWithOnlyBoundedDimensions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheMetricsObserver observer =
                new CacheMetricsObserver(registry, MetricsConfig.builder().build());

        observer.onEvent(new CacheCleanupCompleted("primary", "profiles", "success", 4, 3, 1, false));

        assertEquals(
                1.0,
                registry.get("cache.cleanup.runs")
                        .tag("profile", "primary")
                        .tag("namespace", "profiles")
                        .tag("outcome", "success")
                        .counter()
                        .count());
        assertEquals(
                4.0,
                registry.get("cache.cleanup.scanned")
                        .tag("profile", "primary")
                        .tag("namespace", "profiles")
                        .tag("outcome", "success")
                        .counter()
                        .count());
        assertFalse(registry
                .get("cache.cleanup.scanned")
                .tag("profile", "primary")
                .tag("namespace", "profiles")
                .tag("outcome", "success")
                .counter()
                .getId()
                .getTags()
                .stream()
                .anyMatch(tag -> tag.getKey().equals("failed")));
    }
}
