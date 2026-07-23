// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.correlation;

import dev.vertique.core.context.ContextValue;
import dev.vertique.core.eventbus.DispatchContextValue;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Read-only view of the correlation context for the current request or event.
 *
 * <p>Implementations of this interface are populated by the framework ingress pipeline:
 * {@code CorrelationIngressMiddleware} for REST requests, and (PR2) {@code CorrelationContextSeeder}
 * for non-REST inbound surfaces (service dispatch / Kafka / outbox-service relay / delayed jobs /
 * workflow branches). All collection-returning methods return unmodifiable views; to capture a
 * point-in-time copy that crosses thread boundaries safely, call {@link #snapshot()}.
 *
 * <p>Application code reads the live context through the generic substrate:
 * {@code ContextHolder.current(CorrelationContext.class)} (when DI is available) or
 * {@code ContextValues.current(CorrelationContext.class)} (static accessor).
 *
 * <p><b>Service-handler injection in V1.</b> {@code CorrelationContext} is annotated with
 * {@link DispatchContextValue}, which classifies it as a dispatch-context parameter so a service
 * handler may declare it as a method parameter and the dispatch framework will resolve it from the
 * currently bound holder value:
 *
 * <pre>{@code
 * public Future<Void> handle(MyEvent event, CorrelationContext correlation) {
 *     String requestId = correlation.requestId().value();
 *     ...
 * }
 * }</pre>
 *
 * <p><b>Caveat: cross-dispatch propagation lands in PR2.</b> PR1 ships the public model, the live
 * REST ingress, the holder binding, and the {@link ContextValueAdapter}-based snapshot/restore for
 * in-process Vert.x-context inheritance. It does NOT yet ship the
 * {@code ServiceDispatchContextEncoder} / {@code ServiceDispatchContextDecoder} pair that would
 * copy the correlation snapshot into outgoing {@code DispatchEnvelope.metadata().dispatchContext()}
 * and rebuild a fresh live context on the receiving side. Until PR2 lands, a service handler
 * dispatched across an event-bus boundary will not see {@code CorrelationContext} on its parameter
 * list — there is no generic developer-facing override API in PR1, and the service client factory's
 * current explicit-override path covers {@code SecurityContext} only. Service handlers running on
 * the same Vert.x duplicated context as the binder (e.g. local-bus dispatch within one request
 * scope, before the duplicated context is replaced) do see the bound context through the holder
 * and parameter injection via {@code @DispatchContextValue} works for them.
 */
@DispatchContextValue
public interface CorrelationContext extends ContextValue {

    /**
     * Returns the request-scoped identifier assigned to this specific inbound request or event.
     *
     * @return the request identifier; never {@code null}
     */
    CorrelationIdentifier requestId();

    /**
     * Returns the correlation identifier linking this request to related requests in a
     * business transaction.
     *
     * @return the correlation identifier; never {@code null}
     */
    CorrelationIdentifier correlationId();

    /**
     * Returns the identifier of the upstream request or event that directly caused this one,
     * or {@code null} if causation tracking is not available.
     *
     * @return the causation identifier; may be {@code null}
     */
    @Nullable
    CorrelationIdentifier causationId();

    /**
     * Returns the distributed trace reference for this request, or {@code null} if no trace
     * context was propagated.
     *
     * @return the trace reference; may be {@code null}
     */
    @Nullable
    TraceReference trace();

    /**
     * Returns an unmodifiable list of all protocol-level correlation references extracted from
     * inbound protocol headers (e.g. HTTP headers).
     *
     * @return an unmodifiable list of protocol correlation refs; never {@code null}, may be empty
     */
    List<ProtocolCorrelationRef> protocolCorrelations();

    /**
     * Returns the session-level correlation reference (e.g. from a JWT claim or session cookie),
     * or {@code null} if no session context was established.
     *
     * @return the session reference; may be {@code null}
     */
    @Nullable
    CorrelationSessionRef session();

    /**
     * Returns an unmodifiable view of the arbitrary key/value metadata associated with this
     * correlation context.
     *
     * @return an unmodifiable attributes map; never {@code null}, may be empty
     */
    Map<String, String> attributes();

    /**
     * Captures an immutable snapshot of the current correlation state. The snapshot is safe to
     * pass across thread boundaries, store in async pipelines, and encode into durable records.
     *
     * @return a new immutable snapshot of the current state
     */
    CorrelationContextSnapshot snapshot();

    // --- static factories ---

    /**
     * Returns the singleton {@link UnboundCorrelationContext} sentinel used as a fail-closed
     * fallback when no correlation context is bound to the current execution scope.
     *
     * <p>The returned instance's {@link #requestId()} and {@link #correlationId()} carry the
     * sentinel value {@code "unavailable"}, making events carrying it distinguishable from those
     * produced by a live request. Events emitted with the unbound sentinel are audit-visible but
     * <em>not</em> request-joinable.
     *
     * @return the {@link UnboundCorrelationContext} singleton; never {@code null}
     * @see UnboundCorrelationContext#INSTANCE
     */
    static CorrelationContext unbound() {
        return UnboundCorrelationContext.INSTANCE;
    }
}
