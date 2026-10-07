// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.correlation;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable {@link CorrelationContext} carrying freshly minted, joinable request and correlation
 * identifiers.
 *
 * <p>Used by fail-closed emission sites that must still produce an audit-joinable event when no
 * ambient correlation is bound — for example authorization and snapshot-degradation paths that
 * mint {@code sourceEventId} from {@code requestId}. Unlike {@link UnboundCorrelationContext},
 * these identifiers are unique per call and are therefore request-joinable within the audit
 * record that carries them (they do not claim to match any inbound request that never established
 * correlation).
 *
 * <p>Obtain instances via {@link CorrelationContext#generated(String)}.
 */
final class GeneratedCorrelationContext implements CorrelationContext {

    private final CorrelationIdentifier requestId;
    private final CorrelationIdentifier correlationId;

    private GeneratedCorrelationContext(CorrelationIdentifier requestId, CorrelationIdentifier correlationId) {
        this.requestId = requestId;
        this.correlationId = correlationId;
    }

    /**
     * Mints a fresh context whose identifiers are RFC 4122 UUID v4 values tagged with
     * {@code source}.
     *
     * @param source origin label recorded on both identifiers; must not be null or blank
     * @return a new joinable context; never {@code null}
     */
    static GeneratedCorrelationContext mint(String source) {
        Objects.requireNonNull(source, "source");
        if (source.isBlank()) {
            throw new IllegalArgumentException("GeneratedCorrelationContext source must not be blank");
        }
        CorrelationIdentifier requestId =
                new CorrelationIdentifier(UUID.randomUUID().toString(), source);
        CorrelationIdentifier correlationId =
                new CorrelationIdentifier(UUID.randomUUID().toString(), source);
        return new GeneratedCorrelationContext(requestId, correlationId);
    }

    @Override
    public CorrelationIdentifier requestId() {
        return requestId;
    }

    @Override
    public CorrelationIdentifier correlationId() {
        return correlationId;
    }

    @Override
    @Nullable
    public CorrelationIdentifier causationId() {
        return null;
    }

    @Override
    @Nullable
    public TraceReference trace() {
        return null;
    }

    @Override
    public List<ProtocolCorrelationRef> protocolCorrelations() {
        return List.of();
    }

    @Override
    @Nullable
    public CorrelationSessionRef session() {
        return null;
    }

    @Override
    public Map<String, String> attributes() {
        return Map.of();
    }

    @Override
    public CorrelationContextSnapshot snapshot() {
        return CorrelationContextSnapshot.of(requestId, correlationId);
    }
}
