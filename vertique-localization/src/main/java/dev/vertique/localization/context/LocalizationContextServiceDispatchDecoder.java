// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.context;

import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextDecodeWarning;
import dev.vertique.core.context.ServiceDispatchContextDecoder;
import dev.vertique.core.context.ServiceDispatchDecodeContext;
import java.util.List;

/**
 * Service-dispatch decoder for {@link LocalizationContext}.
 *
 * <p>Accepts an in-process dispatch-context map value as a {@link LocalizationContext} if it is
 * an instance of that record. Because {@link LocalizationContext} is a final record, no subtype
 * check is needed — an exact instance check via {@code isInstance} is sufficient.
 *
 * <p>Decode outcomes:
 * <ul>
 *   <li>A {@link LocalizationContext} instance → {@link ContextDecodeResult#of(Object) of(cast)}.</li>
 *   <li>{@code null} → {@link ContextDecodeResult#empty()} (the type was not propagated).</li>
 *   <li>Any other type → {@link ContextDecodeResult#failure(List) failure} with a
 *       {@link ContextDecodeWarning} describing the type mismatch.</li>
 * </ul>
 *
 * <p>Registered into the Dagger {@code Set<ServiceDispatchContextDecoder<?>>} multibinding via
 * {@link dev.vertique.localization.LocalizationModule}.
 */
public final class LocalizationContextServiceDispatchDecoder
        implements ServiceDispatchContextDecoder<LocalizationContext> {

    /** Constructs the decoder. */
    public LocalizationContextServiceDispatchDecoder() {}

    /** {@inheritDoc} */
    @Override
    public Class<LocalizationContext> type() {
        return LocalizationContext.class;
    }

    /**
     * Decodes the raw dispatch-context map value into a {@link LocalizationContext}.
     *
     * <p>If {@code value} is a {@link LocalizationContext} instance, it is returned directly.
     * If {@code value} is {@code null}, an empty result is returned (the context was not
     * propagated). Otherwise a failure result is returned with a warning describing the
     * unexpected type.
     *
     * @param value   the raw value from the dispatch-context map
     * @param context the decode context; not used
     * @return a successful result if {@code value} is a {@link LocalizationContext}; an empty
     *         result if {@code value} is {@code null}; otherwise a failure result with a warning
     */
    @Override
    public ContextDecodeResult<LocalizationContext> decode(Object value, ServiceDispatchDecodeContext context) {
        if (value == null) {
            return ContextDecodeResult.empty();
        }
        if (LocalizationContext.class.isInstance(value)) {
            return ContextDecodeResult.of(LocalizationContext.class.cast(value));
        }
        return ContextDecodeResult.failure(List.of(new ContextDecodeWarning(
                key(),
                value.toString(),
                "Expected LocalizationContext but got " + value.getClass().getName())));
    }
}
