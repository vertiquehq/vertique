// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.rest;

import dev.vertique.rest.core.events.RequestCompletionScope;
import io.opentelemetry.api.trace.Span;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * {@link RequestCompletionScope} implementation that re-establishes the HTTP server span as
 * the current OpenTelemetry span around the completion-listener dispatch loop.
 *
 * <p>Vert.x 5's OTel tracer ends the server span and detaches its scope synchronously inside
 * {@code conn.write()} — before any {@code ctx.addEndHandler} callback fires. As a result,
 * {@link Span#current()} returns the no-op span when {@code RestRequestCompletedListener} impls
 * run. This scope re-establishes the span's context so that Micrometer exemplar samplers (e.g.
 * {@link io.prometheus.metrics.tracer.otel.OpenTelemetrySpanContext}) can attach a {@code trace_id}
 * to timer samples recorded during completion dispatch.
 *
 * <p>The span reference is retrieved from the routing context under {@link RestSpanKeys#SPAN_KEY},
 * where it was stashed by {@link ServerSpanEnrichmentContributor} before the response was sent.
 * If no valid span is present (e.g. the operation was not traced or enrichment did not run), a
 * no-op {@link AutoCloseable} is returned and nothing changes.
 *
 * <p>This class is package-private — it is bound through {@link OpenTelemetryRestModule} as a
 * {@link RequestCompletionScope} singleton.
 *
 * @see OpenTelemetryRestModule
 * @see RequestCompletionScope
 * @see ServerSpanEnrichmentContributor
 */
@Singleton
final class ServerSpanCompletionScope implements RequestCompletionScope {

    /** No-op closeable returned when no valid span is present or on any exception. */
    private static final AutoCloseable NO_OP = () -> {};

    /**
     * Constructs the scope. No dependencies required; the OTel API is accessed statically
     * and the span is read from the routing context at dispatch time.
     */
    @Inject
    ServerSpanCompletionScope() {}

    /**
     * Retrieves the captured server span from the routing context and, if valid, makes it
     * current for the duration of the listener dispatch loop.
     *
     * <p>Returns a no-op {@link AutoCloseable} when:
     * <ul>
     *   <li>the routing context carries no value under {@link RestSpanKeys#SPAN_KEY}</li>
     *   <li>the value is not a {@link Span}</li>
     *   <li>the span's {@link io.opentelemetry.api.trace.SpanContext#isValid()} is {@code false}</li>
     *   <li>any exception occurs during the lookup or {@code makeCurrent()} call</li>
     * </ul>
     *
     * @param rc the routing context for the completed request; must not be {@code null}
     * @return an {@link io.opentelemetry.context.Scope} that re-establishes the span context,
     *         or a no-op closeable; never {@code null}
     */
    @Override
    public AutoCloseable open(RoutingContext rc) {
        try {
            Object carried = rc.get(RestSpanKeys.SPAN_KEY);
            if (carried instanceof Span span && span.getSpanContext().isValid()) {
                return span.makeCurrent();
            }
            return NO_OP;
        } catch (Exception e) {
            return NO_OP;
        }
    }
}
