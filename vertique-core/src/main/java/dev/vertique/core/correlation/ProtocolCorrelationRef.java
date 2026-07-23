// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.correlation;

import java.util.Map;
import java.util.Objects;

/**
 * An immutable reference to a correlation identifier extracted from an inbound protocol header.
 *
 * <p>Captures the header name, the extracted value, the source label, the response/propagation
 * policy, a durability flag, and an optional attributes map for protocol-specific metadata.
 *
 * <p>The canonical constructor validates header name and value via
 * {@link CorrelationHeaderValidator} and ensures the attributes map is an unmodifiable defensive
 * copy.
 *
 * @param headerName       the HTTP header name; must pass RFC 7230 token validation
 * @param value            the header value; must pass the correlation value allow-list
 * @param source           a label describing the extraction origin; must not be null or blank
 * @param responseMode     controls how the value is echoed in the response; must not be null
 * @param propagationMode  controls how the value is forwarded to outbound calls; must not be null
 * @param durableSafe      {@code true} if this correlation value is safe to persist in durable
 *                         storage (e.g. audit logs, outbox records)
 * @param attributes       optional protocol-specific metadata; {@code null} is treated as empty
 */
public record ProtocolCorrelationRef(
        String headerName,
        String value,
        String source,
        CorrelationResponseMode responseMode,
        CorrelationPropagationMode propagationMode,
        boolean durableSafe,
        Map<String, String> attributes) {

    /**
     * Constructs a {@link ProtocolCorrelationRef}, validating all required fields and making
     * a defensive immutable copy of {@code attributes}.
     *
     * @param headerName      the HTTP header name
     * @param value           the header value
     * @param source          the extraction origin label
     * @param responseMode    the response echo policy
     * @param propagationMode the outbound forwarding policy
     * @param durableSafe     whether safe for durable storage
     * @param attributes      optional metadata map; {@code null} is treated as empty
     * @throws IllegalArgumentException if {@code headerName} or {@code value} is invalid
     * @throws NullPointerException     if {@code source}, {@code responseMode}, or
     *                                  {@code propagationMode} is {@code null}
     */
    public ProtocolCorrelationRef {
        CorrelationHeaderValidator.requireValidHeaderName(headerName);
        CorrelationHeaderValidator.requireValidHeaderValue(value);
        Objects.requireNonNull(source, "source");
        if (source.isBlank()) {
            throw new IllegalArgumentException("ProtocolCorrelationRef source must not be blank");
        }
        Objects.requireNonNull(responseMode, "responseMode");
        Objects.requireNonNull(propagationMode, "propagationMode");
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
    }
}
