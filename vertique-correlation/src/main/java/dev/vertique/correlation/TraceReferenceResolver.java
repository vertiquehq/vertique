// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import dev.vertique.core.correlation.TraceReference;
import java.util.Optional;

/**
 * SPI for resolving the calling context's current distributed-trace identity at
 * correlation-binding time.
 *
 * <p>The REST ingress middleware ({@code CorrelationIngressMiddleware}) consults the resolver right
 * after the {@link dev.vertique.core.correlation.CorrelationContext} is bound and the initial MDC
 * snapshot is taken. When a trace is present, the middleware calls
 * {@link dev.vertique.correlation.CorrelationContextMutator#setTrace} so the trace ids are
 * reflected both on the live context and in the MDC for the duration of the request.
 *
 * <p><b>Implementation contract:</b>
 * <ul>
 *   <li>Must be <b>cheap and non-blocking</b> — the resolver is invoked on the Vert.x event loop
 *       during request ingress; expensive or I/O-bound implementations will degrade throughput.</li>
 *   <li>SHOULD <b>never throw</b> — the caller guards with a {@code try/catch} regardless, but a
 *       throwing implementation that the guard catches will still emit a {@code WARN} log entry.
 *       Prefer returning {@link Optional#empty()} to throwing for "no span" conditions.</li>
 *   <li>An <b>empty result</b> means "no trace to mirror" — no active span, or the span context is
 *       invalid. The framework takes no action when the resolver returns empty.</li>
 *   <li><b>Sampling stance</b> is left to the implementation. The framework's OpenTelemetry
 *       implementation is expected to return ids for any <em>valid</em> span regardless of whether
 *       the trace is sampled for export, so that log entries remain correlatable even when the
 *       trace is not visible in the trace store (the ids may not resolve there).</li>
 * </ul>
 *
 * <p><b>Singleton by design.</b> Tracing is OpenTelemetry-only in this framework (see ADR-0098 /
 * PRD D3). The binding is resolved via {@code Optional<TraceReferenceResolver>} (a
 * {@code @BindsOptionalOf} declaration in {@link CorrelationContextModule}) — exactly one
 * implementation may be on the graph. Applications that do not wire a tracer see an empty
 * {@code Optional} and no trace ids flow into the correlation context.
 *
 * @see CorrelationContextMutator#setTrace(TraceReference)
 * @see CorrelationContextModule
 */
public interface TraceReferenceResolver {

    /**
     * Returns the current distributed-trace identity for the calling context, if one exists.
     *
     * <p>A non-empty result implies that there is a valid active span; an empty result signals
     * that no span is active or the span context is invalid.
     *
     * @return an {@link Optional} containing the current {@link TraceReference}, or
     *         {@link Optional#empty()} when no trace is active or the span context is invalid
     */
    Optional<TraceReference> currentTrace();
}
