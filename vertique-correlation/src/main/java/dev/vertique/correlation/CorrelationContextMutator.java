// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.core.correlation.CorrelationSessionRef;
import dev.vertique.core.correlation.ProtocolCorrelationRef;
import dev.vertique.core.correlation.TraceReference;
import dev.vertique.logging.MDCContexts;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;

/**
 * INTERNAL framework seam — consumed by sibling framework modules; not an application contract and
 * outside the maturity promise. Applications use the surface the module document lists and the
 * types in {@code dev.vertique.core}.
 *
 * <p>Framework-only write surface that enriches the holder-bound {@link CorrelationContext} after
 * the initial bind. Applications do NOT use this — read access is via the substrate
 * ({@code ContextHolder.current(CorrelationContext.class)} or
 * {@code ContextValues.current(CorrelationContext.class)}). The mutator is injected into REST
 * ingress, security/auth enrichers, and tracing contributors that need to extend the live
 * context as new ids become known mid-request.
 *
 * <p><b>Selective MDC mirroring (FR-COR-162/163/165).</b> Setters fall into two groups:
 * <ul>
 *   <li><b>Mirroring</b> — {@link #setCausationId} and {@link #setTrace} update both the live
 *       {@link CorrelationContext} and the matching {@link CorrelationMdcKeys} MDC entry inline.
 *       Passing {@code null} clears the field and removes the MDC key.</li>
 *   <li><b>Non-mirroring</b> — {@link #setSession}, {@link #addProtocolCorrelation}, and
 *       {@link #putAttribute} write the live context only and intentionally do NOT touch MDC.
 *       Session refs and protocol refs are not considered safe-by-default mirror targets;
 *       a future opt-in {@code CorrelationMdcConfig} can broaden the mirror set without
 *       expanding this V1 contract.</li>
 * </ul>
 *
 * <p>Every setter requires a {@link CorrelationContext} to already be bound on the current
 * Vert.x duplicated context — typically by {@code CorrelationIngressMiddleware} at REST ingress
 * or by {@code CorrelationContextSeeder} on non-REST inbound surfaces. Calling a setter when no
 * context is bound raises {@link IllegalStateException} so missing-binding bugs surface at the
 * call site instead of being silently swallowed.
 */
@Singleton
public final class CorrelationContextMutator {

    private final ContextHolder holder;

    @Inject
    public CorrelationContextMutator(ContextHolder holder) {
        this.holder = Objects.requireNonNull(holder, "holder");
    }

    // --- Mirroring setters (live context + MDC) ---

    /**
     * Sets (or clears) the causation id on the live context and mirrors the value into the
     * {@link CorrelationMdcKeys#CAUSATION_ID} MDC entry.
     *
     * @param causationId the causation id; {@code null} clears the field and removes the MDC key
     * @throws IllegalStateException if no {@link CorrelationContext} is bound on the current context
     */
    public void setCausationId(@Nullable CorrelationIdentifier causationId) {
        requireLive().setCausationId(causationId);
        if (causationId != null) {
            MDCContexts.put(CorrelationMdcKeys.CAUSATION_ID, causationId.value());
        } else {
            MDCContexts.remove(CorrelationMdcKeys.CAUSATION_ID);
        }
    }

    /**
     * Sets (or clears) the trace reference on the live context and mirrors {@code traceId} (and
     * {@code spanId}, when present) into the {@link CorrelationMdcKeys#TRACE_ID} /
     * {@link CorrelationMdcKeys#SPAN_ID} MDC entries. When {@code trace} is {@code null} both
     * MDC keys are removed; when {@code trace.spanId()} is {@code null} only the span key is
     * removed.
     *
     * @param trace the trace reference; {@code null} clears the field and both MDC keys
     * @throws IllegalStateException if no {@link CorrelationContext} is bound on the current context
     */
    public void setTrace(@Nullable TraceReference trace) {
        requireLive().setTrace(trace);
        if (trace != null) {
            MDCContexts.put(CorrelationMdcKeys.TRACE_ID, trace.traceId());
            if (trace.spanId() != null) {
                MDCContexts.put(CorrelationMdcKeys.SPAN_ID, trace.spanId());
            } else {
                MDCContexts.remove(CorrelationMdcKeys.SPAN_ID);
            }
        } else {
            MDCContexts.remove(CorrelationMdcKeys.TRACE_ID);
            MDCContexts.remove(CorrelationMdcKeys.SPAN_ID);
        }
    }

    // --- Non-mirroring setters (live context only; MDC intentionally untouched per FR-COR-163/165) ---

    /**
     * Sets (or clears) the session reference on the live context. Does not touch MDC — session
     * refs are not in the safe-by-default mirror set in V1.
     *
     * @param session the session ref; {@code null} clears the field
     * @throws IllegalStateException if no {@link CorrelationContext} is bound on the current context
     */
    public void setSession(@Nullable CorrelationSessionRef session) {
        requireLive().setSession(session);
    }

    /**
     * Appends a protocol-correlation ref to the live context. Does not touch MDC — protocol refs
     * are not in the safe-by-default mirror set in V1.
     *
     * @param ref the ref to append; must not be null
     * @throws NullPointerException if {@code ref} is null
     * @throws IllegalStateException if no {@link CorrelationContext} is bound on the current context
     */
    public void addProtocolCorrelation(ProtocolCorrelationRef ref) {
        requireLive().addProtocolCorrelation(Objects.requireNonNull(ref, "ref"));
    }

    /**
     * Sets or replaces an arbitrary attribute on the live context. Does not touch MDC.
     *
     * @param key   the attribute key; must not be null
     * @param value the attribute value; must not be null
     * @throws NullPointerException if {@code key} or {@code value} is null
     * @throws IllegalStateException if no {@link CorrelationContext} is bound on the current context
     */
    public void putAttribute(String key, String value) {
        requireLive().putAttribute(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
    }

    // --- Internal ---

    /**
     * Looks up the currently bound {@link CorrelationContext} as the mutable impl. Throws
     * {@link IllegalStateException} if absent — missing-binding bugs surface here rather than
     * silently no-op'ing.
     */
    private MutableCorrelationContext requireLive() {
        CorrelationContext live = holder.current(CorrelationContext.class)
                .orElseThrow(() -> new IllegalStateException("No CorrelationContext is bound — "
                        + "CorrelationIngressMiddleware (REST) or CorrelationContextSeeder (non-REST) "
                        + "must run before any enricher mutates correlation state"));
        if (!(live instanceof MutableCorrelationContext mutable)) {
            // Defensive: the only legitimate way to bind a CorrelationContext is through
            // CorrelationContextFactory, which always produces MutableCorrelationContext.
            throw new IllegalStateException("Bound CorrelationContext is not a framework-supplied mutable instance: "
                    + live.getClass().getName());
        }
        return mutable;
    }
}
