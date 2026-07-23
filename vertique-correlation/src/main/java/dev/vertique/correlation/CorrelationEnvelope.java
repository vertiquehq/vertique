// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.core.correlation.CorrelationPropagationMode;
import dev.vertique.core.correlation.CorrelationResponseMode;
import dev.vertique.core.correlation.CorrelationSessionRef;
import dev.vertique.core.correlation.TraceReference;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Jackson-serialisable DTO mirroring the correlation context for durable persistence.
 *
 * <p>Lives apart from {@link dev.vertique.core.correlation.CorrelationContext} because durable
 * codecs need a stable, evolvable wire shape: {@code schemaVersion} for forward compatibility,
 * {@code @JsonInclude(NON_NULL)} so absent optional fields are omitted from the JSON, and
 * {@code @JsonIgnoreProperties(ignoreUnknown=true)} so future versions can add fields without
 * breaking older consumers (FR-COR-143 / FR-COR-152).
 *
 * <p>{@link CorrelationContextDurableEncoder} fills this DTO from a
 * {@link dev.vertique.core.correlation.CorrelationContextSnapshot}, applying durability filters
 * ({@link CorrelationSessionRef#durableSafe()} omits the whole session block when false;
 * {@link dev.vertique.core.correlation.ProtocolCorrelationRef#durableSafe()} filters individual
 * items out of the list — FR-COR-144 / FR-COR-148). {@link CorrelationContextDurableDecoder}
 * deserialises the JSON, enforces required fields and {@code schemaVersion}, re-validates header
 * names/values per {@link dev.vertique.core.correlation.CorrelationHeaderValidator}, and rebuilds
 * a fresh live context.
 *
 * @param schemaVersion        wire-schema version; current is {@value #CURRENT_SCHEMA_VERSION}
 * @param requestId            request id; required
 * @param correlationId        correlation id; required
 * @param causationId          causation id; nullable / omitted when absent
 * @param trace                trace reference; nullable / omitted when absent
 * @param session              session reference; nullable / omitted when absent or
 *                             {@code durableSafe = false}
 * @param protocolCorrelations protocol refs; filtered to {@code durableSafe = true} entries;
 *                             nullable / omitted when empty
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CorrelationEnvelope(
        int schemaVersion,
        CorrelationIdentifier requestId,
        CorrelationIdentifier correlationId,
        @Nullable CorrelationIdentifier causationId,
        @Nullable TraceReference trace,
        @Nullable CorrelationSessionRef session,
        @Nullable List<EnvelopeProtocolCorrelation> protocolCorrelations,
        @Nullable Map<String, String> attributes) {

    /** Current wire-schema version. Bump on incompatible payload changes. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * Inner DTO mirroring {@link dev.vertique.core.correlation.ProtocolCorrelationRef} for the
     * envelope's protocol-list slot. Filter-on-encode means the input list never contains items
     * with {@code durableSafe = false}; the {@code durableSafe} field is still carried so a future
     * consumer that downgrades policy can recognise the original safety flag.
     *
     * @param headerName      protocol header name (preserves response casing)
     * @param value           protocol value
     * @param source          provenance string
     * @param responseMode    response mode applied at the original ingress
     * @param propagationMode propagation mode applied at the original ingress
     * @param durableSafe     {@code true} (only true entries are persisted)
     * @param attributes      optional spec/contributor attributes
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EnvelopeProtocolCorrelation(
            String headerName,
            String value,
            String source,
            CorrelationResponseMode responseMode,
            CorrelationPropagationMode propagationMode,
            boolean durableSafe,
            @Nullable Map<String, String> attributes) {}
}
