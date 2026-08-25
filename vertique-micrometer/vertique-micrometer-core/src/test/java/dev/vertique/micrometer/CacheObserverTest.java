// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.cache.spi.CacheObservation;
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

        observer.onOperation(new CacheObservation("get", "caffeine", "profiles", "hit", Duration.ofMillis(1)));

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
}
