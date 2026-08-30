// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.rest;

import dev.vertique.codegen.RegisterIntoSet;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.semconv.ErrorAttributes;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link RequestInterceptor} that records the span outcome (status and error type) on the
 * OpenTelemetry server span after each HTTP response, and captures the server span early so that
 * pre-dispatch requests (auth rejects, 404s) still get an exemplar.
 *
 * <p>Contributed to the {@link RequestInterceptor} multibinding by {@link OpenTelemetryRestModule}.
 *
 * <h2>Early span capture ({@link #onRequest})</h2>
 * <p>{@link #onRequest} fires at the API-router mount before auth and dispatch. It resolves
 * {@link Span#current()} and, if the span context is valid and {@link RestSpanKeys#SPAN_KEY} is
 * not already set, stores the span under {@link RestSpanKeys#SPAN_KEY}. This ensures the
 * {@link ServerSpanCompletionScope} can re-establish the span for exemplar attachment on requests
 * that short-circuit before the {@link ServerSpanEnrichmentContributor} operation handler runs
 * (e.g. 401 auth rejects, 404 route misses within the API router).
 *
 * <p><strong>Known limitation:</strong> root-level requests rejected before the API router
 * (framework-level short-circuits) are not covered by this early capture — those never reach
 * any {@link RequestInterceptor}.
 *
 * <h2>Span resolution for {@link #onError} and {@link #afterResponse}</h2>
 * <p>These methods first look for a span stored under {@link RestSpanKeys#SPAN_KEY} (placed there
 * either by {@link #onRequest} or by {@link ServerSpanEnrichmentContributor}). If no span is
 * found, they fall back to {@link Span#current()}. When neither yields a valid span, all
 * operations are no-ops.
 *
 * <h2>Outcome recording rules (OTel HTTP semconv)</h2>
 * <ul>
 *   <li>{@link #onError}: span status left {@link StatusCode#UNSET} — {@code onError} fires during
 *       the error pipeline, before the exception is mapped to an HTTP status code. Setting ERROR
 *       here is premature: the exception may map to 4xx (client fault). Instead, {@code error.type}
 *       is set to the throwable's simple class name for attribution. Status is decided in
 *       {@code afterResponse} from the final HTTP status.</li>
 *   <li>Status 5xx (from {@code afterResponse}): span status set to {@link StatusCode#ERROR}; if
 *       {@link RequestInterceptor#ORIGINAL_ERROR_KEY} holds a {@link Throwable}, {@code error.type}
 *       is set to its simple class name.</li>
 *   <li>Status 4xx (from {@code afterResponse}): span status left {@link StatusCode#UNSET} (client
 *       fault per HTTP semconv); if {@link RequestInterceptor#ORIGINAL_ERROR_KEY} holds a
 *       {@link Throwable}, {@code error.type} is set for attribution.</li>
 *   <li>Status 2xx/3xx: span status left {@link StatusCode#UNSET}; no {@code error.type}.</li>
 * </ul>
 *
 * <p>No {@link Span#recordException} is ever called — exception events are not emitted, only the
 * status and {@code error.type} attribute.
 *
 * <p>All operations are guarded against exceptions so that span recording failures never affect
 * the HTTP response pipeline.
 *
 * @see OpenTelemetryRestModule
 * @see ServerSpanEnrichmentContributor
 * @see ServerSpanCompletionScope
 * @see RestSpanKeys
 */
@Slf4j
@Singleton
@RegisterIntoSet(RequestInterceptor.class)
public final class ServerSpanOutcomeInterceptor implements RequestInterceptor {

    /**
     * Constructs the interceptor. No dependencies are required; the OpenTelemetry API is accessed
     * via the static {@link Span#current()} method at request time.
     */
    @Inject
    public ServerSpanOutcomeInterceptor() {}

    /**
     * Synchronous observer called when the request arrives at the framework layer.
     *
     * <p>Resolves {@link Span#current()} and, if the span context is valid and
     * {@link RestSpanKeys#SPAN_KEY} is not already present on the routing context, stores the span
     * there. This early capture ensures the {@link ServerSpanCompletionScope} can re-establish
     * the span for exemplar attachment on pre-dispatch requests (auth rejects, 404 route misses
     * within the API router) that never reach the {@link ServerSpanEnrichmentContributor}
     * operation handler.
     *
     * <p>The guard {@code rc.get(SPAN_KEY) == null} avoids clobbering a span set by a higher-priority
     * contributor (though in practice {@link #onRequest} fires before any operation handler).
     *
     * <p>All operations are guarded against exceptions — span recording failures never affect the
     * HTTP pipeline.
     *
     * @param rc the Vert.x {@link RoutingContext} for the incoming request
     */
    @Override
    public void onRequest(RoutingContext rc) {
        try {
            Span span = Span.current();
            if (span.getSpanContext().isValid() && rc.get(RestSpanKeys.SPAN_KEY) == null) {
                rc.put(RestSpanKeys.SPAN_KEY, span);
            }
        } catch (Exception e) {
            log.warn(
                    "ServerSpanOutcomeInterceptor.onRequest failed: {}",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * Synchronous observer called when an error enters the error pipeline.
     *
     * <p>Records the {@code error.type} attribute from the throwable's simple class name.
     * Span status is intentionally left {@link StatusCode#UNSET} — {@code onError} fires before
     * the exception is mapped to an HTTP status code, so the final status is not yet known. The
     * exception may map to 4xx (client fault), in which case setting ERROR here would be incorrect
     * per OTel HTTP semconv. Span status is decided in {@link #afterResponse} from the final HTTP
     * status code.
     *
     * @param rc    the Vert.x {@link RoutingContext} for the failed request
     * @param error the original failure, before any mapping
     */
    @Override
    public void onError(RoutingContext rc, Throwable error) {
        try {
            Span span = resolveSpan(rc);
            if (!span.getSpanContext().isValid()) {
                return;
            }
            // Set error.type only — status is decided from the final HTTP status in afterResponse
            span.setAttribute(ErrorAttributes.ERROR_TYPE, error.getClass().getSimpleName());
        } catch (Exception e) {
            log.warn(
                    "ServerSpanOutcomeInterceptor.onError failed: {}",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * Synchronous observer called after the response pipeline completes.
     *
     * <p>Resolves the active span and records the outcome based on the final HTTP status code:
     * <ul>
     *   <li>Status 5xx: span status set to {@link StatusCode#ERROR}; if
     *       {@link RequestInterceptor#ORIGINAL_ERROR_KEY} holds a {@link Throwable},
     *       {@code error.type} is set to its simple class name.</li>
     *   <li>Status 4xx: span status left {@link StatusCode#UNSET} (client fault per HTTP semconv);
     *       if {@link RequestInterceptor#ORIGINAL_ERROR_KEY} holds a {@link Throwable},
     *       {@code error.type} is set for attribution (may already be set by {@link #onError}).</li>
     *   <li>Status 2xx/3xx: span status left {@link StatusCode#UNSET}; no {@code error.type}.</li>
     * </ul>
     *
     * @param rc       the current routing context
     * @param response the final response after all transformations
     */
    @Override
    public void afterResponse(RoutingContext rc, Response response) {
        try {
            Span span = resolveSpan(rc);
            if (!span.getSpanContext().isValid()) {
                return;
            }
            int status = response.getStatus();
            if (status >= 500) {
                span.setStatus(StatusCode.ERROR);
                Object carried = rc.get(RequestInterceptor.ORIGINAL_ERROR_KEY);
                if (carried instanceof Throwable t) {
                    span.setAttribute(ErrorAttributes.ERROR_TYPE, t.getClass().getSimpleName());
                }
            } else if (status >= 400) {
                // 4xx: client fault — status stays UNSET; set error.type for attribution if present
                Object carried = rc.get(RequestInterceptor.ORIGINAL_ERROR_KEY);
                if (carried instanceof Throwable t) {
                    span.setAttribute(ErrorAttributes.ERROR_TYPE, t.getClass().getSimpleName());
                }
            }
        } catch (Exception e) {
            log.warn(
                    "ServerSpanOutcomeInterceptor.afterResponse failed: {}",
                    e.getClass().getSimpleName());
        }
    }

    // --- Helpers ---

    /**
     * Resolves the active span from the routing context data or falls back to {@link Span#current()}.
     *
     * <p>Prefers the span stored under {@link RestSpanKeys#SPAN_KEY} by
     * {@link ServerSpanEnrichmentContributor}; falls back to {@link Span#current()} when the key
     * is absent.
     *
     * @param rc the current routing context
     * @return the resolved span; never {@code null} (may be a no-op span with an invalid context)
     */
    private static Span resolveSpan(RoutingContext rc) {
        Object carried = rc.get(RestSpanKeys.SPAN_KEY);
        return carried instanceof Span s ? s : Span.current();
    }
}
