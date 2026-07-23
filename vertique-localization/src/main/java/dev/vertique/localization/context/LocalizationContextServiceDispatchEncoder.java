// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.context;

import dev.vertique.core.context.ServiceDispatchContextEncoder;
import dev.vertique.core.context.ServiceDispatchEncodeContext;

/**
 * Service-dispatch encoder for {@link LocalizationContext}.
 *
 * <p>Passes the ambient holder-bound {@link LocalizationContext} value through unchanged into the
 * service-dispatch context map. Because {@link LocalizationContext} is an immutable record, no
 * snapshotting is required — the value can cross the in-process dispatch boundary safely by
 * reference.
 *
 * <p>On the receive side, {@link LocalizationContextServiceDispatchDecoder} reinstates the value
 * into the holder under the canonical {@code LocalizationContext.class.getName()} key.
 *
 * <p>Registered into the Dagger {@code Set<ServiceDispatchContextEncoder<?>>} multibinding via
 * {@link dev.vertique.localization.LocalizationModule}.
 */
public final class LocalizationContextServiceDispatchEncoder
        implements ServiceDispatchContextEncoder<LocalizationContext> {

    /** Constructs the encoder. */
    public LocalizationContextServiceDispatchEncoder() {}

    /** {@inheritDoc} */
    @Override
    public Class<LocalizationContext> type() {
        return LocalizationContext.class;
    }

    /**
     * Returns the {@link LocalizationContext} value unchanged (identity pass-through).
     *
     * <p>{@link LocalizationContext} is an immutable record, so sharing the instance across
     * the in-process dispatch boundary is safe.
     *
     * @param value   the currently bound localization context value; never {@code null}
     * @param context the encode context; not used
     * @return the value itself; never {@code null}
     */
    @Override
    public Object encode(LocalizationContext value, ServiceDispatchEncodeContext context) {
        return value;
    }
}
