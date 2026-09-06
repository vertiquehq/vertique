// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextDecodeWarning;
import dev.vertique.core.context.DurablePropagationMetadata;
import dev.vertique.core.context.ServiceDispatchContextDecoder;
import dev.vertique.core.context.ServiceDispatchDecodeContext;
import java.util.List;

/**
 * INTERNAL framework seam — consumed by sibling framework modules; not an application contract and
 * outside the maturity promise. Applications program against the SPIs in
 * {@code dev.vertique.core.context} and receive this runtime through the framework's Dagger wiring.
 *
 * <p>Service-dispatch decoder for {@link DurablePropagationMetadata}.
 *
 * <p>Accepts an in-process dispatch-context map value as {@link DurablePropagationMetadata} if it
 * is already of that type (the matching encoder passes it through unchanged). Returns a failure
 * result with a warning if the raw value is of an unexpected type (FR-CTX-143).
 */
public final class DurablePropagationMetadataServiceDispatchDecoder
        implements ServiceDispatchContextDecoder<DurablePropagationMetadata> {

    /** Constructs the decoder. */
    public DurablePropagationMetadataServiceDispatchDecoder() {}

    /** {@inheritDoc} */
    @Override
    public Class<DurablePropagationMetadata> type() {
        return DurablePropagationMetadata.class;
    }

    /**
     * Decodes the raw dispatch-context map value into a {@link DurablePropagationMetadata}.
     *
     * <p>If {@code value} is already a {@link DurablePropagationMetadata} instance, it is returned
     * directly. Otherwise a failure result is returned with a warning.
     *
     * @param value   the raw value from the dispatch-context map
     * @param context the decode context; not used
     * @return a successful result if {@code value} is a {@link DurablePropagationMetadata};
     *         otherwise a failure result with a warning
     */
    @Override
    public ContextDecodeResult<DurablePropagationMetadata> decode(Object value, ServiceDispatchDecodeContext context) {
        if (value instanceof DurablePropagationMetadata metadata) {
            return ContextDecodeResult.of(metadata);
        }
        return ContextDecodeResult.failure(List.of(new ContextDecodeWarning(
                key(),
                value != null ? value.toString() : null,
                "Expected DurablePropagationMetadata but got "
                        + (value != null ? value.getClass().getName() : "null"))));
    }
}
