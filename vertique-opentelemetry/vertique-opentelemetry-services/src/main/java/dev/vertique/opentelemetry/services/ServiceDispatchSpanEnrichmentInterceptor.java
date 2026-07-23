// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.services;

import dev.vertique.core.eventbus.Result;
import dev.vertique.services.interceptor.ServiceDispatchContext;
import dev.vertique.services.interceptor.ServiceInterceptor;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.semconv.ErrorAttributes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link ServiceInterceptor} that enriches the active OpenTelemetry CONSUMER span with
 * service dispatch attributes and records terminal outcome status.
 *
 * <p>Contributed to the {@link ServiceInterceptor} multibinding by
 * {@link OpenTelemetryServicesModule}.
 *
 * <p>Span lifecycle: no sender-side seam exists in the event bus dispatch pipeline. Spans are
 * enriched only when a recording span is already current (propagation mode: PROPAGATE). When no
 * recording span is active, all operations are silent no-ops via the OpenTelemetry API's built-in
 * no-op implementation.
 *
 * <p>Enrichment on {@link #onDispatch}:
 * <ul>
 *   <li>{@code vertique.service.target} — the stable dot-delimited target id (e.g.
 *       {@code "integration.user-service.get-user"}); not set when
 *       {@link ServiceDispatchContext#stableTargetId()} is {@code null}, to avoid polluting spans
 *       with {@code UNKNOWN}</li>
 *   <li>{@code vertique.service.oneway} — {@code true} for fire-and-forget dispatches</li>
 * </ul>
 *
 * <p>Status recording on {@link #onTerminalComplete}:
 * <ul>
 *   <li>Failure result → span status {@link StatusCode#ERROR};
 *       {@code error.type} set to the failure cause's simple class name</li>
 *   <li>Success result → span status left {@link StatusCode#UNSET}</li>
 * </ul>
 *
 * <p>Terminal-outcome status is best-effort: the reply is sent before
 * {@link #onTerminalComplete} fires, so writes to an already-ended span are safe no-ops
 * per the OpenTelemetry API contract.
 *
 * <p>No {@link Span#recordException} is ever called — exception events are not emitted, only
 * the status and {@code error.type} attribute.
 *
 * <p>All operations are guarded against exceptions so that span enrichment failures never affect
 * the dispatch pipeline.
 *
 * @see OpenTelemetryServicesModule
 * @see ServiceAttributes
 */
@Slf4j
@Singleton
public final class ServiceDispatchSpanEnrichmentInterceptor implements ServiceInterceptor {

    /**
     * Constructs the interceptor. No dependencies are required; the OpenTelemetry API is accessed
     * via the static {@link Span#current()} method at dispatch time.
     */
    @Inject
    public ServiceDispatchSpanEnrichmentInterceptor() {}

    /**
     * Enriches the active CONSUMER span with service dispatch attributes.
     *
     * <p>Sets {@code vertique.service.target} when
     * {@link ServiceDispatchContext#stableTargetId()} is non-null, and always sets
     * {@code vertique.service.oneway}. When no recording span is current, this method is a no-op.
     *
     * <p>Exceptions are swallowed — this observer cannot affect the dispatch outcome.
     *
     * @param ctx the dispatch context; never {@code null}
     */
    @Override
    public void onDispatch(ServiceDispatchContext ctx) {
        try {
            Span span = Span.current();
            if (!span.getSpanContext().isValid() || !span.isRecording()) {
                return;
            }
            span.setAttribute(ServiceAttributes.SERVICE_ONEWAY, ctx.oneWay());
            String target = ctx.stableTargetId();
            if (target != null && !target.isBlank()) {
                span.setAttribute(ServiceAttributes.SERVICE_TARGET, target);
            }
        } catch (Exception e) {
            log.warn(
                    "[{}] onDispatch span enrichment failed (swallowed)",
                    this.getClass().getSimpleName(),
                    e);
        }
    }

    /**
     * Records the terminal dispatch outcome on the active CONSUMER span.
     *
     * <p>On failure: sets span status to {@link StatusCode#ERROR} and records
     * {@code error.type} from the failure cause's simple class name. On success: leaves span
     * status {@link StatusCode#UNSET}. When no recording span is current, this method is a no-op.
     *
     * <p>Writes to an already-ended span are safe no-ops per the OpenTelemetry API contract
     * (best-effort contract: the reply is sent before this hook fires).
     *
     * <p>Exceptions are swallowed — this observer cannot affect the dispatch outcome.
     *
     * @param ctx       the dispatch context; never {@code null}
     * @param result    the terminal dispatch result (post-recovery); never {@code null}
     * @param startTime the wall-clock instant at which dispatch was initiated; never {@code null}
     * @param endTime   the wall-clock instant at which the terminal outcome was reached; never {@code null}
     */
    @Override
    public void onTerminalComplete(ServiceDispatchContext ctx, Result<?> result, Instant startTime, Instant endTime) {
        try {
            Span span = Span.current();
            if (!span.getSpanContext().isValid() || !span.isRecording()) {
                return;
            }
            if (result.isFailure()) {
                span.setStatus(StatusCode.ERROR);
                Throwable cause = result.cause();
                if (cause != null) {
                    span.setAttribute(
                            ErrorAttributes.ERROR_TYPE, cause.getClass().getSimpleName());
                }
            }
            // Success: leave span status UNSET — no writes
        } catch (Exception e) {
            log.warn(
                    "[{}] onTerminalComplete span enrichment failed (swallowed)",
                    this.getClass().getSimpleName(),
                    e);
        }
    }
}
