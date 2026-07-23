// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.correlation;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable snapshot of the full correlation state at a point in time.
 *
 * <p>Snapshots are safe to pass across thread boundaries, store in async pipelines, and
 * encode into durable records. All collection fields are unmodifiable defensive copies.
 *
 * <p>Use {@link #of(CorrelationIdentifier, CorrelationIdentifier)} to construct a minimal
 * snapshot when only the request and correlation IDs are known.
 *
 * @param requestId            the request-scoped identifier; must not be null
 * @param correlationId        the correlation identifier linking related requests; must not be null
 * @param causationId          the identifier of the request that caused this one; may be
 *                             {@code null}
 * @param trace                a distributed trace reference; may be {@code null}
 * @param protocolCorrelations protocol-level correlation refs extracted from inbound headers;
 *                             {@code null} is treated as an empty list
 * @param session              a session-level correlation reference; may be {@code null}
 * @param attributes           arbitrary key/value metadata; {@code null} is treated as empty
 */
public record CorrelationContextSnapshot(
        CorrelationIdentifier requestId,
        CorrelationIdentifier correlationId,
        @Nullable CorrelationIdentifier causationId,
        @Nullable TraceReference trace,
        List<ProtocolCorrelationRef> protocolCorrelations,
        @Nullable CorrelationSessionRef session,
        Map<String, String> attributes) {

    /**
     * Constructs a {@link CorrelationContextSnapshot}, validating required fields and making
     * unmodifiable defensive copies of the collection fields.
     *
     * @param requestId            the request-scoped identifier
     * @param correlationId        the correlation identifier
     * @param causationId          the causation identifier; may be {@code null}
     * @param trace                the distributed trace reference; may be {@code null}
     * @param protocolCorrelations the protocol-level correlation refs; {@code null} = empty
     * @param session              the session-level correlation reference; may be {@code null}
     * @param attributes           the metadata map; {@code null} = empty
     * @throws NullPointerException if {@code requestId} or {@code correlationId} is {@code null}
     */
    public CorrelationContextSnapshot {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(correlationId, "correlationId");
        protocolCorrelations = List.copyOf(protocolCorrelations == null ? List.of() : protocolCorrelations);
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
    }

    // --- static factories ---

    /**
     * Constructs a minimal snapshot containing only the required identifiers. All optional
     * fields are {@code null} and collection fields are empty.
     *
     * <p>Useful for tests and minimal-context scenarios where trace, session, and protocol
     * correlation data is not available.
     *
     * @param requestId     the request-scoped identifier; must not be null
     * @param correlationId the correlation identifier; must not be null
     * @return a new minimal {@link CorrelationContextSnapshot}
     */
    public static CorrelationContextSnapshot of(CorrelationIdentifier requestId, CorrelationIdentifier correlationId) {
        return new CorrelationContextSnapshot(requestId, correlationId, null, null, List.of(), null, Map.of());
    }
}
