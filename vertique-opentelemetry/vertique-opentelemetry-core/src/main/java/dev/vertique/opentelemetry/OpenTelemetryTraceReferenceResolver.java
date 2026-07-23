// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry;

import dev.vertique.core.correlation.TraceReference;
import dev.vertique.correlation.TraceReferenceResolver;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link TraceReferenceResolver} implementation that reads the current OpenTelemetry active span.
 *
 * <p>Invoked by the REST ingress middleware on the Vert.x event loop; must be cheap and
 * non-blocking.
 *
 * <h2>Validity vs sampling (SP-10)</h2>
 * <p>This resolver returns a {@link TraceReference} for any <em>valid</em> span context,
 * regardless of whether the trace is sampled for export. An unsampled trace has valid trace and
 * span ids — those ids can be written into log MDC so that log entries remain correlatable even
 * when the trace is not visible in the backend trace store. The ids may not resolve to a trace
 * record in Jaeger/Zippo/etc. when sampling dropped the trace; that is expected and documented
 * behaviour. Applications that want to suppress unsampled ids may override this resolver.
 *
 * <h2>Never throws</h2>
 * <p>The method body is wrapped in a broad {@code try/catch}. Any exception from the OpenTelemetry
 * API (e.g. a buggy bridge implementation) is logged at WARN with the exception class name only
 * (no throwable attached — cause-free logging posture), and {@link Optional#empty()} is returned
 * so the ingress middleware degrades silently.
 *
 * @see TraceReferenceResolver
 * @see OpenTelemetryModule
 */
@Singleton
final class OpenTelemetryTraceReferenceResolver implements TraceReferenceResolver {

    private static final Logger LOG = LoggerFactory.getLogger(OpenTelemetryTraceReferenceResolver.class);

    /** Source label written into every resolved {@link TraceReference}. */
    private static final String SOURCE = "opentelemetry";

    // --- Constructor ---

    /**
     * No-dependency constructor used by Dagger.
     */
    @Inject
    OpenTelemetryTraceReferenceResolver() {}

    // --- TraceReferenceResolver ---

    /**
     * Returns the current OpenTelemetry span's trace and span ids, if a valid span is active.
     *
     * <p>A {@link TraceReference} is returned only when {@link SpanContext#isValid()} is
     * {@code true}. Sampling is deliberately NOT checked — see class javadoc (SP-10).
     *
     * <p>Any exception from the OpenTelemetry API is caught, logged at WARN with the exception
     * class name only (no throwable attached — cause-free logging posture), and
     * {@link Optional#empty()} is returned so the ingress middleware degrades silently.
     *
     * @return an {@link Optional} containing the current {@link TraceReference} when a valid span
     *         is active; {@link Optional#empty()} otherwise
     */
    @Override
    public Optional<TraceReference> currentTrace() {
        try {
            Span span = Span.current();
            SpanContext sc = span.getSpanContext();
            if (!sc.isValid()) {
                return Optional.empty();
            }
            return Optional.of(new TraceReference(sc.getTraceId(), sc.getSpanId(), SOURCE));
        } catch (Exception ex) {
            LOG.warn(
                    "OpenTelemetryTraceReferenceResolver: failed to resolve current trace: {}",
                    ex.getClass().getName());
            return Optional.empty();
        }
    }
}
