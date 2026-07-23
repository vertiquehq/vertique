// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import dev.vertique.core.context.DurablePropagationMetadata;
import dev.vertique.core.context.ServiceDispatchContextEncoder;
import dev.vertique.core.context.ServiceDispatchEncodeContext;

/**
 * Service-dispatch encoder for {@link DurablePropagationMetadata}.
 *
 * <p>Passes the {@link DurablePropagationMetadata} value through unchanged into the
 * service-dispatch context map. This allows service handlers invoked from a durable consumer
 * (Kafka, outbox) to inspect the raw durable metadata during the same in-process dispatch chain
 * (FR-CTX-143).
 *
 * <p>No durable encoder is provided for this type (FR-CTX-144): raw durable metadata from one
 * boundary must not be blindly republished onto another durable boundary.
 */
public final class DurablePropagationMetadataServiceDispatchEncoder
        implements ServiceDispatchContextEncoder<DurablePropagationMetadata> {

    /** Constructs the encoder. */
    public DurablePropagationMetadataServiceDispatchEncoder() {}

    /** {@inheritDoc} */
    @Override
    public Class<DurablePropagationMetadata> type() {
        return DurablePropagationMetadata.class;
    }

    /**
     * Returns the {@link DurablePropagationMetadata} value unchanged.
     *
     * @param value   the currently bound metadata value; never {@code null}
     * @param context the encode context; not used
     * @return the value itself; never {@code null}
     */
    @Override
    public Object encode(DurablePropagationMetadata value, ServiceDispatchEncodeContext context) {
        return value;
    }
}
