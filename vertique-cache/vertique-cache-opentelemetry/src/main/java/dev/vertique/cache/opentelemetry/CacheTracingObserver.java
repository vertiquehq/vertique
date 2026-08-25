// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.opentelemetry;

import dev.vertique.cache.spi.CacheObservation;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.opentelemetry.TracingConfig;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** Records redacted cache observations as short-lived child spans. */
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
    public void onOperation(CacheObservation observation) {
        if (!enabled) {
            return;
        }
        Span span = null;
        try {
            span = tracer.spanBuilder("cache." + bounded(observation.operation()))
                    .startSpan();
            span.setAttribute("provider", bounded(observation.provider()));
            span.setAttribute("cache", bounded(observation.cacheName()));
            span.setAttribute("outcome", bounded(observation.outcome()));
            span.setAttribute("duration_ms", observation.duration().toNanos() / 1_000_000.0);
            if ("failure".equals(observation.outcome())) {
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
