// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security.dispatch;

import dev.vertique.core.context.ServiceDispatchContextEncoder;
import dev.vertique.core.context.ServiceDispatchEncodeContext;
import dev.vertique.security.SecurityContext;
import jakarta.inject.Singleton;

/**
 * Service-dispatch encoder for {@link SecurityContext}.
 *
 * <p>Passes the ambient holder-bound {@link SecurityContext} value through unchanged into the
 * service-dispatch context map. This enables automatic propagation of the caller's security context
 * across in-process service hops without requiring callers to explicitly pass the context.
 *
 * <p>On the receive side, {@link SecurityContextServiceDispatchDecoder} accepts the value back into
 * the holder under the canonical {@code SecurityContext.class.getName()} key. Subtypes of
 * {@link SecurityContext} (e.g., {@code AuthenticatedSecurityContext}) flow through under the same
 * key and are reinstated as-is on the receive side via {@code isInstance} filtering.
 *
 * <p>This encoder is registered by {@link dev.vertique.rest.security.AuthModule}; it is present when the auth
 * module is wired (it is not a {@code ContextRuntimeModule} built-in).
 */
@Singleton
public final class SecurityContextServiceDispatchEncoder implements ServiceDispatchContextEncoder<SecurityContext> {

    /** Constructs the encoder. */
    public SecurityContextServiceDispatchEncoder() {}

    /** {@inheritDoc} */
    @Override
    public Class<SecurityContext> type() {
        return SecurityContext.class;
    }

    /**
     * Returns the {@link SecurityContext} value unchanged (identity pass-through).
     *
     * @param value   the currently bound security context value; never {@code null}
     * @param context the encode context; not used
     * @return the value itself; never {@code null}
     */
    @Override
    public Object encode(SecurityContext value, ServiceDispatchEncodeContext context) {
        return value;
    }
}
