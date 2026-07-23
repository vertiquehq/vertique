// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.correlation;

/**
 * Controls how a correlation identifier is propagated to outbound calls.
 *
 * <p>Applied per {@link ProtocolCorrelationRef} to allow fine-grained forwarding policy.
 */
public enum CorrelationPropagationMode {

    /** Do not propagate the correlation header to outbound calls. */
    NONE,

    /**
     * Forward the correlation value to outbound calls using the same header name that
     * was used to receive it.
     */
    PROPAGATE_SAME_HEADER
}
