// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import dev.vertique.cache.spi.CacheObservation;
import dev.vertique.cache.spi.CacheObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** Records redacted cache observations as bounded Micrometer timers. */
@Singleton
public final class CacheMetricsObserver implements CacheObserver {
    private final MeterRegistry registry;
    private final boolean enabled;

    @Inject
    public CacheMetricsObserver(MeterRegistry registry, MetricsConfig config) {
        this.registry = registry;
        this.enabled = config.enabled();
    }

    @Override
    public void onOperation(CacheObservation observation) {
        if (!enabled) {
            return;
        }
        try {
            Timer.builder("cache." + bounded(observation.operation()))
                    .tag("provider", bounded(observation.provider()))
                    .tag("cache", bounded(observation.cacheName()))
                    .tag("outcome", bounded(observation.outcome()))
                    .register(registry)
                    .record(observation.duration());
        } catch (Throwable ignored) {
            // Telemetry must never alter cache behavior.
        }
    }

    private static String bounded(String value) {
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
