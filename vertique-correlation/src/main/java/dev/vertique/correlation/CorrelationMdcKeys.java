// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import java.util.Set;

/**
 * Public string constants for the framework's mirrored MDC keys.
 *
 * <p>These keys are written into the MDC by {@code CorrelationIngressMiddleware} at request
 * ingress and (for the safe-by-default mirrored subset) by {@code CorrelationContextMutator}
 * during enrichment. Session refs and protocol-correlation refs are intentionally NOT mirrored
 * in V1 (FR-COR-163/165); a future opt-in {@code CorrelationMdcConfig} may broaden the set.
 *
 * <p>Lives in {@code vertique-correlation} (not {@code vertique-core}) because "MDC" is a
 * logging concept that should not leak into the framework's API module. The only consumers
 * are the mutator (same module) and the REST ingress middleware in {@code vertique-rest-core},
 * both of which already depend on this module.
 */
public final class CorrelationMdcKeys {

    /** Matches the existing {@code rest-core MdcKeys.REQUEST_ID} literal — single source of truth. */
    public static final String REQUEST_ID = "requestId";

    /** Correlation id propagated across causally related operations. */
    public static final String CORRELATION_ID = "correlationId";

    /** Id of the upstream operation that caused this one (FR-COR-085). */
    public static final String CAUSATION_ID = "causationId";

    /** W3C Trace Context trace id, when an external tracer is wired. */
    public static final String TRACE_ID = "traceId";

    /** W3C Trace Context span id, when an external tracer is wired. */
    public static final String SPAN_ID = "spanId";

    /**
     * The full mirrored set — {@code CorrelationIngressMiddleware} calls
     * {@code MDCContexts.snapshotKeys(MIRRORED)} once at request start so these keys are
     * restored at request end regardless of which enricher mutated them mid-request.
     */
    public static final Set<String> MIRRORED = Set.of(REQUEST_ID, CORRELATION_ID, CAUSATION_ID, TRACE_ID, SPAN_ID);

    private CorrelationMdcKeys() {}
}
