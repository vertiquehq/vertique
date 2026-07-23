// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security.dispatch;

import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextDecodeWarning;
import dev.vertique.core.context.ServiceDispatchContextDecoder;
import dev.vertique.core.context.ServiceDispatchDecodeContext;
import dev.vertique.security.SecurityContext;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * Service-dispatch decoder for {@link SecurityContext}.
 *
 * <p>Accepts an in-process dispatch-context map value as a {@link SecurityContext} if it is an
 * instance of that interface (or any subtype). This preserves the long-standing subtype-injection
 * behavior: concrete implementations such as {@code AuthenticatedSecurityContext} flow through
 * under the canonical {@code SecurityContext.class.getName()} key and are reinstated as-is.
 *
 * <p>If the raw value is present but is not a {@link SecurityContext} instance, a failure result
 * is returned with a {@link ContextDecodeWarning} describing the type mismatch. A {@code null}
 * value yields an empty result with no warnings (the type was simply not propagated).
 *
 * <p>This decoder is registered by {@link dev.vertique.rest.security.AuthModule}; it is present when the auth
 * module is wired (it is not a {@code ContextRuntimeModule} built-in).
 */
@Singleton
public final class SecurityContextServiceDispatchDecoder implements ServiceDispatchContextDecoder<SecurityContext> {

    /** Constructs the decoder. */
    public SecurityContextServiceDispatchDecoder() {}

    /** {@inheritDoc} */
    @Override
    public Class<SecurityContext> type() {
        return SecurityContext.class;
    }

    /**
     * Decodes the raw dispatch-context map value into a {@link SecurityContext}.
     *
     * <p>If {@code value} is a {@link SecurityContext} instance (including subtypes), it is
     * returned directly. If {@code value} is {@code null}, an empty result is returned (the
     * context was not propagated). Otherwise a failure result is returned with a warning
     * describing the unexpected type.
     *
     * @param value   the raw value from the dispatch-context map
     * @param context the decode context; not used
     * @return a successful result if {@code value} is a {@link SecurityContext}; an empty result
     *         if {@code value} is {@code null}; otherwise a failure result with a warning
     */
    @Override
    public ContextDecodeResult<SecurityContext> decode(Object value, ServiceDispatchDecodeContext context) {
        if (value == null) {
            return ContextDecodeResult.empty();
        }
        if (SecurityContext.class.isInstance(value)) {
            return ContextDecodeResult.of(SecurityContext.class.cast(value));
        }
        return ContextDecodeResult.failure(List.of(new ContextDecodeWarning(
                key(),
                value.toString(),
                "Expected SecurityContext but got " + value.getClass().getName())));
    }
}
