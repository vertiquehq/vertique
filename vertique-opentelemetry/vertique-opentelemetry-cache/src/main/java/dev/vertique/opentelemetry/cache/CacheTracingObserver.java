// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.cache;

import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.event.CacheEvent;
import dev.vertique.cache.spi.event.CacheLateCompletion;
import dev.vertique.cache.spi.event.CacheOperationCompleted;
import dev.vertique.cache.spi.event.CacheOutcome;
import dev.vertique.opentelemetry.TracingConfig;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Locale;

/** Records sealed cache operation events as short-lived child spans. */
@Singleton
public final class CacheTracingObserver implements CacheObserver {
    private final Tracer tracer;
    private final boolean enabled;

    @Inject
    public CacheTracingObserver(Tracer tracer, TracingConfig config) {
        this.tracer = tracer;
        this.enabled = config.enabled();
    }

    @Override
    public void onEvent(CacheEvent event) {
        if (!enabled) {
            return;
        }
        switch (event) {
            case CacheOperationCompleted completed ->
                span(
                        completed.operation().name(),
                        completed.provider(),
                        completed.cacheName(),
                        completed.outcome().name().toLowerCase(Locale.ROOT),
                        completed.elapsed(),
                        completed.outcome() == CacheOutcome.ERROR || completed.outcome() == CacheOutcome.TIMEOUT);
            case CacheLateCompletion late ->
                span(
                        late.operation().name(),
                        late.provider(),
                        late.cacheName(),
                        "late_" + late.outcome().name().toLowerCase(Locale.ROOT),
                        late.elapsed(),
                        late.outcome() == CacheOutcome.ERROR);
            default -> {
                // Cleanup events remain metrics-only.
            }
        }
    }

    private void span(
            String operation, String provider, String cacheName, String outcome, Duration elapsed, boolean failed) {
        Span span = null;
        try {
            span = tracer.spanBuilder("cache." + operation.toLowerCase(Locale.ROOT))
                    .startSpan();
            span.setAttribute("provider", bounded(provider));
            span.setAttribute("cache", bounded(cacheName));
            span.setAttribute("outcome", bounded(outcome));
            span.setAttribute("duration_ms", elapsed.toNanos() / 1_000_000.0);
            if (failed) {
                span.setStatus(StatusCode.ERROR);
            }
        } catch (Throwable ignored) {
            // Telemetry must never alter cache behavior.
        } finally {
            if (span != null) {
                try {
                    span.end();
                } catch (Throwable ignored) {
                    // Telemetry must never alter cache behavior.
                }
            }
        }
    }

    private static String bounded(String value) {
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
