// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.cache;

import dev.vertique.codegen.RegisterIntoSet;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.event.CacheCleanupCompleted;
import dev.vertique.cache.spi.event.CacheEvent;
import dev.vertique.cache.spi.event.CacheLateCompletion;
import dev.vertique.cache.spi.event.CacheOperationCompleted;
import dev.vertique.micrometer.MetricsConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/** Records sealed cache events as bounded Micrometer timers and cleanup counters. */
@Singleton
@RegisterIntoSet(CacheObserver.class)
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
    public void onEvent(CacheEvent event) {
        if (!enabled) {
            return;
        }
        try {
            switch (event) {
                case CacheOperationCompleted completed ->
                    operation(
                            completed.operation().name(),
                            completed.provider(),
                            completed.cacheName(),
                            completed.outcome().name().toLowerCase(Locale.ROOT),
                            completed.elapsed());
                case CacheLateCompletion late ->
                    operation(
                            late.operation().name(),
                            late.provider(),
                            late.cacheName(),
                            "late_" + late.outcome().name().toLowerCase(Locale.ROOT),
                            late.elapsed());
                case CacheCleanupCompleted cleanup -> cleanup(cleanup);
            }
        } catch (Throwable ignored) {
            // Telemetry must never alter cache behavior.
        }
    }

    private void operation(String operation, String provider, String cacheName, String outcome, Duration elapsed) {
        Timer.builder("cache." + operation.toLowerCase(Locale.ROOT))
                .tag("provider", bounded(provider))
                .tag("cache", bounded(cacheName))
                .tag("outcome", bounded(outcome))
                .register(registry)
                .record(elapsed);
    }

    private void cleanup(CacheCleanupCompleted cleanup) {
        Tags tags = Tags.of(
                "profile", cleanup.profile(),
                "namespace", cleanup.namespace(),
                "outcome", cleanup.outcome());
        counter(CLEANUP_RUNS_COUNTER, tags).increment();
        counter(CLEANUP_SCANNED_COUNTER, tags).increment(cleanup.scanned());
        counter(CLEANUP_DELETED_COUNTER, tags).increment(cleanup.deleted());
        counter(CLEANUP_BACKLOG_COUNTER, tags).increment(cleanup.backlog());
    }

    private Counter counter(String name, Tags tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    private static String bounded(String value) {
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
