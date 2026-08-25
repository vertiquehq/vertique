// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.cache;

import dev.vertique.cache.spi.CacheCleanupObservation;
import dev.vertique.cache.spi.CacheObservation;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.micrometer.MetricsConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;

/** Records redacted cache observations as bounded Micrometer timers and cleanup counters. */
@Singleton
public final class CacheMetricsObserver implements CacheObserver {
    private static final String CLEANUP_RUNS_COUNTER = "cache.cleanup.runs";
    private static final String CLEANUP_SCANNED_COUNTER = "cache.cleanup.scanned";
    private static final String CLEANUP_DELETED_COUNTER = "cache.cleanup.deleted";
    private static final String CLEANUP_BACKLOG_COUNTER = "cache.cleanup.backlog";

    private final MeterRegistry registry;
    private final boolean enabled;

    /**
     * Creates the observer with the application meter registry and optional metrics configuration.
     *
     * <p>When the optional configuration is absent, cache metrics are enabled by default.
     *
     * @param registry the application-wide meter registry; never {@code null}
     * @param metricsConfig optional metrics configuration; empty means enabled
     */
    @Inject
    public CacheMetricsObserver(MeterRegistry registry, Optional<MetricsConfig> metricsConfig) {
        this.registry = registry;
        this.enabled = metricsConfig.map(MetricsConfig::enabled).orElse(true);
    }

    /**
     * Creates the observer with an explicit metrics configuration.
     *
     * <p>This overload preserves direct construction for callers that already have a concrete
     * configuration; Dagger uses the optional-configuration constructor above.
     *
     * @param registry the application-wide meter registry; never {@code null}
     * @param config the metrics configuration; never {@code null}
     */
    public CacheMetricsObserver(MeterRegistry registry, MetricsConfig config) {
        this(registry, Optional.of(config));
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

    /**
     * Records one cleanup outcome and its bounded scan, delete, and backlog counts.
     *
     * <p>The cleanup SPI intentionally exposes counters rather than elapsed time, so this
     * callback records counters only. Registry failures are swallowed to keep maintenance
     * fail-open.
     *
     * @param observation the redacted cleanup outcome; never {@code null}
     */
    @Override
    public void onCleanup(CacheCleanupObservation observation) {
        if (!enabled) {
            return;
        }
        try {
            Tags tags = Tags.of(
                    "profile", observation.profile(),
                    "namespace", observation.namespace(),
                    "outcome", observation.outcome());
            counter(CLEANUP_RUNS_COUNTER, tags).increment();
            counter(CLEANUP_SCANNED_COUNTER, tags).increment(observation.scanned());
            counter(CLEANUP_DELETED_COUNTER, tags).increment(observation.deleted());
            counter(CLEANUP_BACKLOG_COUNTER, tags).increment(observation.backlog());
        } catch (Throwable ignored) {
            // Telemetry must never alter cache maintenance behavior.
        }
    }

    private Counter counter(String name, Tags tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    private static String bounded(String value) {
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
