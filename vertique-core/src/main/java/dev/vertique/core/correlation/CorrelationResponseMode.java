// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.correlation;

/**
 * Controls how a correlation identifier is echoed back in the HTTP response.
 *
 * <p>Applied per {@link ProtocolCorrelationRef} to allow fine-grained response header policy.
 */
public enum CorrelationResponseMode {

    /** Do not include any correlation header in the response. */
    NONE,

    /**
     * Echo the incoming header value back in the response using the same header name.
     * If no incoming value is present, no response header is emitted.
     */
    ECHO_SAME_HEADER,

    /**
     * Echo the incoming header value back in the response using the same header name.
     * If no incoming value is present, generate a new RFC 4122 UUID and emit it.
     */
    ECHO_OR_GENERATE_RFC4122
}
