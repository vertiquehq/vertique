// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

import java.util.Objects;
import java.util.Optional;

/**
 * Context passed to a {@link DurableContextMetadataDecoder} at decode time.
 *
 * <p>Identifies the durable boundary where the metadata was read from (e.g., Kafka headers,
 * outbox message metadata). Additional metadata may be added in future versions without breaking
 * existing decoder implementations.
 *
 * @param boundary the durable boundary identifier (e.g., {@code "kafka"}, {@code "outbox"})
 * @param carrier the durable row-carrier binding for the F5 replay defense of PRD identity-002;
 *     empty when the boundary carries no carrier identity. Never {@code null}.
 */
public record DurableDecodeContext(String boundary, Optional<DurableCarrierDescriptor> carrier) {

    /**
     * Canonical constructor.
     *
     * @param boundary the durable boundary identifier
     * @param carrier the durable row-carrier binding, or empty when the boundary carries none
     * @throws NullPointerException if {@code boundary} or {@code carrier} is {@code null}
     */
    public DurableDecodeContext {
        Objects.requireNonNull(boundary, "boundary must not be null");
        Objects.requireNonNull(carrier, "carrier must not be null");
    }

    /**
     * Back-compat convenience constructor for boundaries that carry no carrier identity.
     *
     * @param boundary the durable boundary identifier
     */
    public DurableDecodeContext(String boundary) {
        this(boundary, Optional.empty());
    }
}
