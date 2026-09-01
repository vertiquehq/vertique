// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.ratelimit;

import dev.vertique.codegen.RegisterIntoSet;
import dev.vertique.ratelimit.RateLimitOutcome;
import dev.vertique.ratelimit.spi.RateLimitObserver;
import dev.vertique.ratelimit.spi.event.RateLimitDecisionCompleted;
import dev.vertique.ratelimit.spi.event.RateLimitEvent;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.concurrent.TimeUnit;

/**
 * Records each completed rate-limit admission decision as a short-lived
 * {@code vertique.ratelimit.decision} span (contracts/observability.md, "OpenTelemetry adapter").
 */
@Singleton
@RegisterIntoSet(RateLimitObserver.class)
final class RateLimitSpanObserver implements RateLimitObserver {

    static final String SPAN_NAME = "vertique.ratelimit.decision";

    private final Tracer tracer;

    @Inject
    RateLimitSpanObserver(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public void onEvent(RateLimitEvent event) {
        switch (event) {
            case RateLimitDecisionCompleted completed -> recordSpan(completed);
        }
    }

    private void recordSpan(RateLimitDecisionCompleted completed) {
        Span span = null;
        try {
            span = tracer.spanBuilder(SPAN_NAME).startSpan();
            span.setAttribute("policy", completed.policyName());
            span.setAttribute("outcome", completed.outcome().name());
            span.setAttribute("mode", completed.mode().name());
            span.setAttribute("duration_ms", TimeUnit.NANOSECONDS.toMillis(completed.backendLatencyNanos()));
            if (isBackendFailure(completed.outcome())) {
                span.setStatus(StatusCode.ERROR);
            }
        } catch (Throwable ignored) {
            // Telemetry must never alter rate-limit admission decisions.
        } finally {
            if (span != null) {
                try {
                    span.end();
                } catch (Throwable ignored) {
                    // Telemetry must never alter rate-limit admission decisions.
                }
            }
        }
    }

    private static boolean isBackendFailure(RateLimitOutcome outcome) {
        return outcome == RateLimitOutcome.BACKEND_FAILURE_OPEN || outcome == RateLimitOutcome.BACKEND_FAILURE_CLOSED;
    }
}
