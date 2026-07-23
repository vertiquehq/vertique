// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.correlation;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Sentinel {@link CorrelationContext} used as a fail-closed fallback when no correlation context
 * is bound to the current execution scope.
 *
 * <p>This sentinel is consumed by the enforcement layer (e.g. {@code SecurityPolicyEnforcer}) when
 * {@code ContextHolder.current(CorrelationContext.class)} returns empty — typically during
 * fail-closed authorization paths that must still emit an {@code AuthorizationDecisionEvent} even
 * though no live request context is present.
 *
 * <p><b>Sentinel identifiers.</b> Both {@link #requestId()} and {@link #correlationId()} return a
 * {@link CorrelationIdentifier} whose {@code value} is the documented constant
 * {@link #SENTINEL_ID_VALUE} ({@code "unavailable"}) and whose {@code source} is
 * {@link #SENTINEL_ID_SOURCE} ({@code "unbound"}). Any downstream consumer (audit, metrics,
 * tracing) can detect events carrying this sentinel by checking
 * {@code correlation.requestId().value().equals(UnboundCorrelationContext.SENTINEL_ID_VALUE)}.
 *
 * <p><b>Audit visibility.</b> Events carrying this sentinel are audit-visible but
 * <em>not</em> request-joinable — they cannot be correlated back to a specific inbound request
 * because no request correlation was established at the time of emission.
 *
 * <p>Obtain the singleton via {@link #INSTANCE} or the interface factory
 * {@link CorrelationContext#unbound()}.
 */
public final class UnboundCorrelationContext implements CorrelationContext {

    /**
     * The sentinel value returned by {@link #requestId()} and {@link #correlationId()}.
     *
     * <p>Consumers detecting sentinel events should compare against this constant rather than
     * hard-coding the string literal.
     */
    public static final String SENTINEL_ID_VALUE = "unavailable";

    /**
     * The {@code source} label on the sentinel {@link CorrelationIdentifier}s, indicating that the
     * identifier was produced by the unbound sentinel rather than from an inbound protocol header
     * or generator.
     */
    public static final String SENTINEL_ID_SOURCE = "unbound";

    /** Singleton instance. Use {@link CorrelationContext#unbound()} to obtain it. */
    public static final UnboundCorrelationContext INSTANCE = new UnboundCorrelationContext();

    // --- sentinel identifiers (lazily initialised once at class-load) ---

    private static final CorrelationIdentifier SENTINEL_ID =
            new CorrelationIdentifier(SENTINEL_ID_VALUE, SENTINEL_ID_SOURCE);

    private UnboundCorrelationContext() {}

    // --- CorrelationContext implementation ---

    /**
     * {@inheritDoc}
     *
     * <p>Returns a sentinel {@link CorrelationIdentifier} with value {@code "unavailable"} and
     * source {@code "unbound"}, indicating that no live request correlation is available.
     *
     * @return the sentinel identifier; never {@code null}
     */
    @Override
    public CorrelationIdentifier requestId() {
        return SENTINEL_ID;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the same sentinel identifier as {@link #requestId()}.
     *
     * @return the sentinel identifier; never {@code null}
     */
    @Override
    public CorrelationIdentifier correlationId() {
        return SENTINEL_ID;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always returns {@code null} — causation tracking is unavailable in the unbound state.
     *
     * @return {@code null}
     */
    @Override
    @Nullable
    public CorrelationIdentifier causationId() {
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always returns {@code null} — no distributed trace context is present.
     *
     * @return {@code null}
     */
    @Override
    @Nullable
    public TraceReference trace() {
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always returns an empty list — no protocol correlation headers were captured.
     *
     * @return an empty, unmodifiable list; never {@code null}
     */
    @Override
    public List<ProtocolCorrelationRef> protocolCorrelations() {
        return List.of();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always returns {@code null} — no session context was established.
     *
     * @return {@code null}
     */
    @Override
    @Nullable
    public CorrelationSessionRef session() {
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always returns an empty map — no metadata attributes are present.
     *
     * @return an empty, unmodifiable map; never {@code null}
     */
    @Override
    public Map<String, String> attributes() {
        return Map.of();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns a minimal {@link CorrelationContextSnapshot} containing only the sentinel
     * identifiers. All optional fields are {@code null} and collections are empty. The snapshot
     * satisfies {@code AuthorizationDecisionEvent}'s non-null correlation validation so it can be
     * carried in audit events emitted during the fail-closed fallback path.
     *
     * @return a valid immutable snapshot; never {@code null}
     */
    @Override
    public CorrelationContextSnapshot snapshot() {
        return CorrelationContextSnapshot.of(SENTINEL_ID, SENTINEL_ID);
    }
}
