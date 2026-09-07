// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import dev.vertique.context.CompositeContextScope;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.InboundContextInitializationContext;
import dev.vertique.core.context.InboundContextInitializer;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.TraceReference;
import dev.vertique.logging.MDCContexts;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;

/**
 * INTERNAL framework seam — consumed by sibling framework modules; not an application contract and
 * outside the maturity promise. Applications use the surface the module document lists and the
 * types in {@code dev.vertique.core}.
 *
 * <p>First-ingress {@link InboundContextInitializer} that ensures a {@link CorrelationContext} is
 * bound for the current Vert.x duplicated context AND that the safe-by-default mirrored MDC
 * keys reflect that context's values (FR-COR-125 / FR-COR-162).
 *
 * <p>Runs after the substrate's inbound install step (the dispatch-context map or durable
 * metadata was decoded and bound on the holder). Two cases:
 * <ul>
 *   <li><b>Context already present</b> — an upstream decoder (service-dispatch / durable) bound
 *       it. The decoded value wins per FR-COR-125 (no reseed). The seeder still projects the
 *       mirrored MDC keys from that context so the dispatch sees the correlation fields in MDC
 *       output, since on non-REST boundaries (Kafka, delayed-job, workflow timer/branch) there
 *       is no separate REST middleware doing the MDC write.</li>
 *   <li><b>No context</b> — the seeder mints a fresh one via
 *       {@link CorrelationContextFactory#seed(String)}, recording the boundary string as the
 *       {@link dev.vertique.core.correlation.CorrelationIdentifier#source() source} (e.g.
 *       {@code "seeded:kafka"}, {@code "seeded:workflow-branch"}) and then projects MDC.</li>
 * </ul>
 *
 * <p>Returns a composed {@link ContextHolder.Scope} built via
 * {@code CompositeContextScope.of(mdcScope, bindScope)}; {@link CompositeContextScope} closes
 * its constituents in LIFO (reverse) order, so {@code bindScope} (when one was opened) closes
 * first — releasing the seeded {@link CorrelationContext} holder binding — and {@code mdcScope}
 * closes second, restoring the prior MDC values (including absences) snapshotted at install
 * time. {@code InboundExecutionContextScope} composes this with the inbound scope so the
 * dispatch lifetime is one unit.
 *
 * <p>Registered via {@code @Provides @IntoSet InboundContextInitializer} in
 * {@link CorrelationContextModule}.
 */
@Singleton
public final class CorrelationContextSeeder implements InboundContextInitializer {

    private final ContextHolder holder;
    private final CorrelationContextFactory factory;

    @Inject
    public CorrelationContextSeeder(ContextHolder holder, CorrelationContextFactory factory) {
        this.holder = Objects.requireNonNull(holder, "holder");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public ContextHolder.Scope initialize(InboundContextInitializationContext context) {
        Objects.requireNonNull(context, "context");

        ContextHolder.Scope bindScope = null;
        if (holder.current(CorrelationContext.class).isEmpty()) {
            CorrelationContext seeded = factory.seed(context.boundary());
            bindScope = holder.bind(CorrelationContext.class, seeded);
        }

        // Snapshot prior MDC values (including absences) for the mirrored set before any
        // projection so the scope close restores them even when an enricher later adds keys
        // that didn't exist at install time (e.g. tracing middleware adding traceId/spanId).
        ContextHolder.Scope mdcScope = MDCContexts.snapshotKeys(CorrelationMdcKeys.MIRRORED);
        projectToMdc();

        return CompositeContextScope.of(mdcScope, bindScope);
    }

    /**
     * Projects the safe-by-default mirrored fields of the currently-bound
     * {@link CorrelationContext} into MDC. Called immediately after install — both the seeded
     * and the decoded paths arrive here.
     *
     * <p>Mirrored keys whose field is <em>absent</em> on the current context are explicitly
     * removed, not left to fall through. The MDC snapshot scope restores them at close, but
     * during the dispatch's lifetime a stale {@code traceId} / {@code spanId} / {@code causationId}
     * from an outer scope would otherwise remain visible to log lines and bleed across what is
     * logically a fresh ingress (Kafka record, delayed job, workflow timer/branch).
     *
     * <p>The mutator handles MDC writes on later in-process enrichment via its mirroring
     * setters; this projection covers the inbound starting state when the upstream came across
     * a non-REST boundary that did not write MDC itself.
     */
    private void projectToMdc() {
        CorrelationContext live = holder.current(CorrelationContext.class).orElse(null);
        if (live == null) {
            return;
        }
        MDCContexts.put(CorrelationMdcKeys.REQUEST_ID, live.requestId().value());
        MDCContexts.put(CorrelationMdcKeys.CORRELATION_ID, live.correlationId().value());
        if (live.causationId() != null) {
            MDCContexts.put(CorrelationMdcKeys.CAUSATION_ID, live.causationId().value());
        } else {
            MDCContexts.remove(CorrelationMdcKeys.CAUSATION_ID);
        }
        TraceReference trace = live.trace();
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
}
