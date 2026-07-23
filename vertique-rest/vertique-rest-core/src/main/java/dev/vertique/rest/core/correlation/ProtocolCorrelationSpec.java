// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.correlation;

import dev.vertique.core.correlation.CorrelationPropagationMode;
import dev.vertique.core.correlation.CorrelationResponseMode;
import dev.vertique.core.correlation.ProtocolCorrelationRef;
import jakarta.annotation.Nullable;
import java.util.Map;
import java.util.Optional;

/**
 * Default extension point for protocol-defined correlation headers (FR-COR-094, Context
 * contribution model level 6).
 *
 * <p>Each spec declares one HTTP header that the correlation ingress middleware should consider
 * when building the live {@link dev.vertique.core.correlation.CorrelationContext}. Specs are
 * contributed into a {@code Set<ProtocolCorrelationSpec>} multibinding (declared in
 * {@code CorrelationIngressModule}); the ingress middleware iterates them and resolves each into
 * a {@link ProtocolCorrelationRef} before considering the lower-level
 * {@link ProtocolCorrelationContributor} escape hatch.
 *
 * <p><b>Validate / mint contract.</b> A capture-only spec cannot satisfy protocols like FAPI
 * which require an emitted UUID even when the inbound header is absent or malformed —
 * {@link ProtocolCorrelationRef} requires a valid value in its canonical constructor, so there
 * is no ref to emit when only inbound validation runs. Specs therefore resolve a final value:
 * <ul>
 *   <li>{@link #acceptInbound(String)} validates (and optionally normalises) the inbound header.
 *       Return the value to use if valid; {@code Optional.empty()} if absent or invalid.</li>
 *   <li>{@link #generate()} mints a fresh value when {@link #responseMode()} requires emission
 *       and {@code acceptInbound} returned empty. Only called for response modes that demand
 *       generation (e.g. {@link CorrelationResponseMode#ECHO_OR_GENERATE_RFC4122}).</li>
 * </ul>
 *
 * <p>The ingress middleware emits response headers uniformly from the resolved
 * {@link ProtocolCorrelationRef#responseMode()} — specs do not write response headers themselves.
 *
 * <p>For protocols whose extraction rules cannot be expressed by a single-header spec (e.g.
 * correlation values derived from JWT claims), use {@link ProtocolCorrelationContributor}
 * instead.
 */
public interface ProtocolCorrelationSpec {

    /**
     * The HTTP header name this spec handles. Casing is preserved verbatim for response emission.
     *
     * @return the header name; never {@code null}
     */
    String headerName();

    /**
     * Response-emission policy applied to the resulting {@link ProtocolCorrelationRef}.
     *
     * @return the response mode; never {@code null}
     */
    CorrelationResponseMode responseMode();

    /**
     * Downstream-propagation policy applied to the resulting {@link ProtocolCorrelationRef}.
     *
     * @return the propagation mode; never {@code null}
     */
    CorrelationPropagationMode propagationMode();

    /**
     * Whether the resolved value is safe to write into durable metadata. Durable encoders filter
     * out protocol refs with {@code durableSafe == false} (FR-COR-148).
     *
     * @return {@code true} when this value may appear in durable propagation
     */
    boolean durableSafe();

    /**
     * Validates (and optionally normalises) the inbound header value.
     *
     * @param inboundValue the raw header value as received; {@code null} when the request did
     *                     not carry the header
     * @return the value to bind into the {@link ProtocolCorrelationRef} when present and valid;
     *         {@link Optional#empty()} when the value is absent, malformed, or rejected by
     *         policy
     */
    Optional<String> acceptInbound(@Nullable String inboundValue);

    /**
     * Mints a fresh value when {@link #responseMode()} requires emission but
     * {@link #acceptInbound(String)} returned empty. The default implementation throws because
     * not all specs need to mint values — only override when the response mode requires it (e.g.
     * {@link CorrelationResponseMode#ECHO_OR_GENERATE_RFC4122}).
     *
     * @return the freshly minted value; never {@code null}
     * @throws UnsupportedOperationException if the spec does not support generation
     */
    default String generate() {
        throw new UnsupportedOperationException(
                "Spec " + headerName() + " uses a response mode requiring generate(), but did not implement it");
    }

    /**
     * Static attributes attached to the resulting {@link ProtocolCorrelationRef} (e.g.
     * {@code "standard": "fapi"}). May be used by downstream consumers to discriminate between
     * specs.
     *
     * @return immutable attributes map; defaults to empty
     */
    default Map<String, String> attributes() {
        return Map.of();
    }
}
