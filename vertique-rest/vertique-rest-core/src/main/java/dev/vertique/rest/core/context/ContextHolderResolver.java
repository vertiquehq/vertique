// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.context;

import dev.vertique.context.ContextValues;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Built-in {@link RestContextResolver} that reads typed context values from the current Vert.x
 * request scope via {@link ContextValues#current(Class)}.
 *
 * <p>This resolver delegates to {@link ContextValues}, which is backed by the framework
 * {@link dev.vertique.context.DefaultContextHolder}. It covers the full range of
 * {@link dev.vertique.core.context.ContextValue} types bound by the framework or by application
 * code before REST dispatch, including:
 * <ul>
 *   <li>{@code dev.vertique.security.SecurityContext} — the framework security context</li>
 *   <li>Correlation context</li>
 *   <li>Localisation context</li>
 *   <li>Application-specific {@link dev.vertique.core.context.ContextValue} subtypes</li>
 * </ul>
 *
 * <p>{@link ContextValues#current(Class)} is lenient: it returns {@link Optional#empty()} when
 * called outside a Vert.x context rather than throwing, so this resolver is safe to call in any
 * thread.
 *
 * <p>Priority is {@code 120} — the highest of the built-in resolvers, so application resolvers
 * (priority {@code 0}), {@link RoutingContextResolver} ({@code 100}), and
 * {@link JaxRsSecurityContextResolver} ({@code 110}) all run before this resolver.
 *
 * <p><b>Internal framework built-in — not an application SPI.</b> Applications must not extend or
 * contribute this resolver. To resolve custom {@link dev.vertique.core.context.ContextValue}
 * subtypes, simply bind them into the {@link dev.vertique.context.DefaultContextHolder} before
 * dispatch — this resolver will find them automatically.
 */
final class ContextHolderResolver implements RestContextResolver {

    /**
     * Constructs the resolver. Intended for framework-internal use via Dagger.
     */
    @Inject
    ContextHolderResolver() {}

    /**
     * Returns {@link RestContextResolver#PRIORITY_CONTEXT_HOLDER} ({@code 120}), placing this
     * built-in last in the chain — after all application resolvers ({@code 0}),
     * {@link RoutingContextResolver} ({@code 100}), and
     * {@link JaxRsSecurityContextResolver} ({@code 110}).
     *
     * @return {@link RestContextResolver#PRIORITY_CONTEXT_HOLDER}
     */
    @Override
    public int priority() {
        return RestContextResolver.PRIORITY_CONTEXT_HOLDER;
    }

    /**
     * Resolves a context value of the given {@code type} from the current Vert.x request scope
     * via {@link ContextValues#current(Class)}.
     *
     * <p>Returns {@link Optional#empty()} when no value of the requested type is bound in the
     * current scope, or when called outside a Vert.x context.
     *
     * @param <T>  the requested context type
     * @param type the class of the requested context value; never {@code null}
     * @param ctx  the current Vert.x routing context (not used — the context holder is keyed on
     *             the Vert.x thread-local context, not the routing context); never {@code null}
     * @return the currently bound value of {@code type}, or {@link Optional#empty()} if absent
     */
    @Override
    public <T> Optional<T> resolve(Class<T> type, RoutingContext ctx) {
        return ContextValues.current(type);
    }
}
