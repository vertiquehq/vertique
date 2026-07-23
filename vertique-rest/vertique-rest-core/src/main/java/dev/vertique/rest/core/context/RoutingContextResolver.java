// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.context;

import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Built-in {@link RestContextResolver} that resolves the Vert.x {@link RoutingContext} itself as a
 * context value.
 *
 * <p>This resolver answers {@code @Context RoutingContext} injection points (and any subtype of
 * {@link RoutingContext}). When the requested type is assignable from {@link RoutingContext} and the
 * context is non-null, it returns the routing context cast to the requested type.
 *
 * <p>Priority is {@code 100} — higher than the application default of {@code 0}, so application
 * resolvers may shadow this one if needed (FR-REST-194).
 *
 * <p><b>Internal framework built-in — not an application SPI.</b> Applications must not extend or
 * contribute this resolver. To provide custom context values, implement {@link RestContextResolver}
 * directly.
 */
final class RoutingContextResolver implements RestContextResolver {

    /**
     * Constructs the resolver. Intended for framework-internal use via Dagger.
     */
    @Inject
    RoutingContextResolver() {}

    /**
     * Returns {@link RestContextResolver#PRIORITY_ROUTING_CONTEXT} ({@code 100}), placing this
     * built-in after all application resolvers (priority {@code 0}) but before
     * {@link JaxRsSecurityContextResolver} ({@code 110}) and {@link ContextHolderResolver}
     * ({@code 120}).
     *
     * @return {@link RestContextResolver#PRIORITY_ROUTING_CONTEXT}
     */
    @Override
    public int priority() {
        return RestContextResolver.PRIORITY_ROUTING_CONTEXT;
    }

    /**
     * Resolves the {@link RoutingContext} if the requested {@code type} is the same as or a
     * supertype of {@link RoutingContext}, and the given {@code ctx} is non-null.
     *
     * @param <T>  the requested context type
     * @param type the class of the requested context value; never {@code null}
     * @param ctx  the current Vert.x routing context; may be {@code null}
     * @return the routing context cast to {@code T}, or {@link Optional#empty()} if the type does
     *         not match or {@code ctx} is {@code null}
     */
    @Override
    public <T> Optional<T> resolve(Class<T> type, RoutingContext ctx) {
        if (ctx != null && type.isInstance(ctx)) {
            return Optional.of(type.cast(ctx));
        }
        return Optional.empty();
    }
}
