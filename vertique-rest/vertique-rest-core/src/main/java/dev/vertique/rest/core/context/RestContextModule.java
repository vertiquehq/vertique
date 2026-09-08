// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.context;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.rest.core.security.SecurityRuntime;
import java.util.Optional;
import java.util.Set;

/**
 * INTERNAL framework seam — HTTP-runtime collaborator consumed by sibling framework modules; not
 * an application contract and outside the maturity promise. An application uses the extension
 * points and configuration this module documents and never names this type.
 *
 * <p>Dagger module that declares the {@link RestContextResolver} multibinding and contributes the
 * three built-in framework resolvers.
 *
 * <p>This module is an internal wiring detail of the REST framework. Application code does not
 * reference it directly; it is included via
 * {@link dev.vertique.rest.core.dagger.RestCoreModule}.
 *
 * <p>Built-in resolvers contributed by this module (in {@link dev.vertique.core.extension.OrderedExtension}
 * order — phase → priority → orderKey):
 * <ol>
 *   <li>{@link RoutingContextResolver} — priority {@code 100}, resolves {@link io.vertx.ext.web.RoutingContext}.</li>
 *   <li>{@link JaxRsSecurityContextResolver} — priority {@code 110}, resolves
 *       {@link jakarta.ws.rs.core.SecurityContext} when the security module is present.</li>
 *   <li>{@link ContextHolderResolver} — priority {@code 120}, resolves any
 *       {@link dev.vertique.core.context.ContextValue} subtype from the current Vert.x request
 *       scope.</li>
 * </ol>
 *
 * <p>Application modules may contribute additional resolvers at the default priority ({@code 0})
 * via {@code @Provides @IntoSet RestContextResolver}. Application resolvers run before all
 * framework built-ins and may shadow them for the same type (FR-REST-194).
 *
 * <p><b>Internal — not a public application API.</b>
 */
@Module
public abstract class RestContextModule {

    /**
     * Declares the empty {@link RestContextResolver} multibinding so the set is always resolvable
     * by Dagger even when no application resolvers are contributed.
     *
     * @return the (empty) set of resolvers; Dagger populates this via {@code @IntoSet} contributions
     */
    @Multibinds
    abstract Set<RestContextResolver> restContextResolvers();

    /**
     * Contributes {@link RoutingContextResolver} to the resolver set.
     *
     * @return a new {@link RoutingContextResolver} instance
     */
    @Provides
    @IntoSet
    static RestContextResolver routingContextResolver() {
        return new RoutingContextResolver();
    }

    /**
     * Contributes {@link JaxRsSecurityContextResolver} to the resolver set.
     *
     * <p>The {@code Optional<SecurityRuntime>} is satisfied by a {@code @BindsOptionalOf}
     * declaration in {@link dev.vertique.rest.core.dagger.RestCoreModule}; it is empty when the
     * security module is absent.
     *
     * @param rt the optional security runtime; never {@code null}, may be empty
     * @return a new {@link JaxRsSecurityContextResolver} wrapping the optional runtime
     */
    @Provides
    @IntoSet
    static RestContextResolver jaxRsSecurityContextResolver(Optional<SecurityRuntime> rt) {
        return new JaxRsSecurityContextResolver(rt);
    }

    /**
     * Contributes {@link ContextHolderResolver} to the resolver set.
     *
     * @return a new {@link ContextHolderResolver} instance
     */
    @Provides
    @IntoSet
    static RestContextResolver contextHolderResolver() {
        return new ContextHolderResolver();
    }
}
