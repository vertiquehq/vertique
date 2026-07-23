// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.core.correlation.CorrelationSessionRef;
import dev.vertique.core.correlation.ProtocolCorrelationRef;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;

/**
 * Durable metadata encoder for {@link CorrelationContext} (Context contribution model level 4 —
 * bespoke).
 *
 * <p>Justified as a bespoke encoder rather than the generic {@code DurableJsonContextCodecs}
 * helper because it must:
 * <ul>
 *   <li>Filter the nested {@link CorrelationSessionRef} when
 *       {@link CorrelationSessionRef#durableSafe()} is {@code false} (FR-COR-144).</li>
 *   <li>Filter individual {@link ProtocolCorrelationRef} entries by
 *       {@link ProtocolCorrelationRef#durableSafe()} (FR-COR-148).</li>
 *   <li>Stamp the current {@link CorrelationEnvelope#CURRENT_SCHEMA_VERSION schemaVersion} so the
 *       matching decoder can enforce schema compatibility.</li>
 *   <li>Use {@code @JsonInclude(NON_NULL)} (via the envelope DTO) so absent optional fields are
 *       omitted from the wire shape (FR-COR-143).</li>
 * </ul>
 *
 * <p>The single key {@link CorrelationDurableKeys#CORRELATION} carries the whole envelope as a
 * JSON document. Serialisation uses {@code io.vertx.core.json.jackson.DatabindCodec.mapper()} —
 * the same mapper Vert.x uses for body codecs — so the encoder picks up project-wide Jackson
 * configuration without taking on its own {@code ObjectMapper} binding.
 *
 * <p>Encoding goes through the boundary type {@link CorrelationContextSnapshot}: the live
 * mutable context is snapshotted first so any concurrent mutation cannot mid-serialise the JSON.
 */
@Singleton
public final class CorrelationContextDurableEncoder implements DurableContextMetadataEncoder<CorrelationContext> {

    @Inject
    public CorrelationContextDurableEncoder() {}

    @Override
    public Class<CorrelationContext> type() {
        return CorrelationContext.class;
    }

    @Override
    public String namespace() {
        return CorrelationDurableKeys.CORRELATION;
    }

    @Override
    public DurableMetadata encode(CorrelationContext value, DurableEncodeContext context) {
        // Snapshot first so concurrent mutation on the live context cannot mid-serialise.
        CorrelationContextSnapshot snap = value.snapshot();
        CorrelationEnvelope envelope = toEnvelope(snap);
        // JsonObject.mapFrom uses the Vert.x shared mapper, matching the prior DatabindCodec path.
        return DurableMetadata.of(CorrelationDurableKeys.CORRELATION, JsonObject.mapFrom(envelope));
    }

    /**
     * Builds the wire-shape {@link CorrelationEnvelope} from a snapshot, applying the durability
     * filters. Package-private for testability.
     */
    static CorrelationEnvelope toEnvelope(CorrelationContextSnapshot snap) {
        CorrelationSessionRef session = snap.session();
        if (session != null && !session.durableSafe()) {
            // FR-COR-144: omit the entire session block when the source flagged it not safe.
            session = null;
        }
        List<CorrelationEnvelope.EnvelopeProtocolCorrelation> protocols = null;
        if (!snap.protocolCorrelations().isEmpty()) {
            List<CorrelationEnvelope.EnvelopeProtocolCorrelation> filtered = snap.protocolCorrelations().stream()
                    .filter(ProtocolCorrelationRef::durableSafe)
                    .map(CorrelationContextDurableEncoder::toEnvelopeRef)
                    .toList();
            // Omit the property entirely when the filtered list is empty so the JSON stays
            // minimal under FR-COR-143.
            if (!filtered.isEmpty()) {
                protocols = filtered;
            }
        }
        Map<String, String> attributes = snap.attributes().isEmpty() ? null : Map.copyOf(snap.attributes());
        return new CorrelationEnvelope(
                CorrelationEnvelope.CURRENT_SCHEMA_VERSION,
                snap.requestId(),
                snap.correlationId(),
                snap.causationId(),
                snap.trace(),
                session,
                protocols,
                attributes);
    }

    private static CorrelationEnvelope.EnvelopeProtocolCorrelation toEnvelopeRef(ProtocolCorrelationRef ref) {
        Map<String, String> attrs = ref.attributes().isEmpty() ? null : Map.copyOf(ref.attributes());
        return new CorrelationEnvelope.EnvelopeProtocolCorrelation(
                ref.headerName(),
                ref.value(),
                ref.source(),
                ref.responseMode(),
                ref.propagationMode(),
                ref.durableSafe(),
                attrs);
    }
}
