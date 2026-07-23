// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Context passed to a {@link DurableContextMetadataEncoder} at encode time.
 *
 * <p>Identifies the durable boundary where the metadata is being written (e.g., Kafka headers,
 * outbox message metadata). Additional metadata may be added in future versions without breaking
 * existing encoder implementations.
 *
 * @param boundary the durable boundary identifier (e.g., {@code "kafka"}, {@code "outbox"})
 * @param carrier the durable row-carrier binding for the F5 replay defense of PRD identity-002;
 *     empty when the boundary carries no carrier identity. Never {@code null}.
 * @param fireTime the intended dispatch/fire time of the durable row being encoded (e.g. a delayed
 *     job's scheduled {@code runAt}), so an encoder can detect that content it is about to sign would
 *     already be stale by the time the row fires (F5 doomed-expiry detection, PRD identity-002 §14.6
 *     A9). Empty for boundaries with no fixed fire time (cron, outbox, Kafka) — only the delayed-job
 *     schedule path populates it today. Never {@code null}.
 */
public record DurableEncodeContext(
        String boundary, Optional<DurableCarrierDescriptor> carrier, Optional<Instant> fireTime) {

    /**
     * Canonical constructor.
     *
     * @param boundary the durable boundary identifier
     * @param carrier the durable row-carrier binding, or empty when the boundary carries none
     * @param fireTime the intended dispatch/fire time of the row being encoded, or empty when the
     *     boundary carries no fixed fire time
     * @throws NullPointerException if {@code boundary}, {@code carrier}, or {@code fireTime} is
     *     {@code null}
     */
    public DurableEncodeContext {
        Objects.requireNonNull(boundary, "boundary must not be null");
        Objects.requireNonNull(carrier, "carrier must not be null");
        Objects.requireNonNull(fireTime, "fireTime must not be null");
    }

    /**
     * Back-compat convenience constructor for boundaries that carry no fire-time evidence, only a
     * carrier.
     *
     * @param boundary the durable boundary identifier
     * @param carrier the durable row-carrier binding, or empty when the boundary carries none
     */
    public DurableEncodeContext(String boundary, Optional<DurableCarrierDescriptor> carrier) {
        this(boundary, carrier, Optional.empty());
    }

    /**
     * Back-compat convenience constructor for boundaries that carry no carrier identity or fire-time
     * evidence.
     *
     * @param boundary the durable boundary identifier
     */
    public DurableEncodeContext(String boundary) {
        this(boundary, Optional.empty(), Optional.empty());
    }
}
