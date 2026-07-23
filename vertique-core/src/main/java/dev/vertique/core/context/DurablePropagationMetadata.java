// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

import java.util.Objects;

/**
 * Immutable snapshot of the structured durable metadata available at a durable boundary.
 *
 * <p>Bound into {@link ContextHolder} by {@link DurableContextPropagator#bindFrom} whenever durable
 * metadata is present, even if no typed durable decoder recognizes any namespace. This ensures that
 * framework or application code can always inspect the raw metadata through
 * {@code ContextHolder.current(DurablePropagationMetadata.class)}.
 *
 * <p>The framework does NOT provide a durable encoder for this type by default. Raw durable
 * metadata from one boundary must not be blindly republished onto another durable boundary
 * (FR-CTX-144).
 *
 * @param boundary the durable boundary identifier from which this metadata was read
 * @param metadata the full durable metadata document at the boundary; never {@code null}
 */
public record DurablePropagationMetadata(String boundary, DurableMetadata metadata) implements ContextValue {

    /**
     * Compact constructor that enforces non-null metadata.
     *
     * @param boundary the boundary identifier
     * @param metadata the durable metadata document; must not be {@code null}
     */
    public DurablePropagationMetadata {
        Objects.requireNonNull(metadata, "metadata");
    }
}
