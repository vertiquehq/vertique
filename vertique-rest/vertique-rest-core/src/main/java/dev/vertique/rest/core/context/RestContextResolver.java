// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.context;

import dev.vertique.core.extension.OrderedExtension;
import io.vertx.ext.web.RoutingContext;
import java.util.Optional;

/**
 * SPI for resolving request-scoped context values during REST dispatch.
 *
 * <p>A {@code RestContextResolver} answers one question per call: "given this request context, can
 * I provide a value of the requested type?" If the resolver handles the type and the value is
 * present for this request it returns a non-empty {@link Optional}; otherwise it returns
 * {@link Optional#empty()} and the {@link RestContextResolution} coordinator tries the next
 * resolver in the chain.
 *
 * <p><b>Contract (FR-REST-172):</b> implementations MUST NOT create, mutate, enrich, replace, or
 * propagate context as a side effect of {@code resolve}. The method is a pure read — it observes
 * the request state and reports back.
 *
 * <p><b>Ordering (FR-REST-194):</b> resolvers participate in the {@link OrderedExtension} contract
 * and are sorted by phase, then {@link #priority()} ascending, then {@link #orderKey()} (defaults
 * to the fully-qualified class name) before the first call. Application resolvers use the default
 * phase ({@link dev.vertique.core.extension.ExtensionPhase#APPLICATION}) and priority ({@code 0}),
 * and therefore run <em>before</em> framework built-ins (which use higher positive values), so
 * application resolvers may shadow framework-provided values for the same type.
 *
 * <h2>Registering a resolver via Dagger</h2>
 *
 * <p>Contribute an implementation to the multibinding in a Dagger {@code @Module}:
 *
 * <pre>{@code
 * @Provides
 * @IntoSet
 * static RestContextResolver tenantResolver(TenantContextResolver resolver) {
 *     return resolver;
 * }
 * }</pre>
 *
 * <p>The concrete implementation only needs to be a Dagger-injectable class — no additional
 * framework registration is required beyond the {@code @IntoSet} contribution.
 *
 * @see RestContextResolution
 * @see RestContextUnavailableException
 * @see OrderedExtension
 */
public interface RestContextResolver extends OrderedExtension {

    // --- Built-in priority tier constants ---

    /**
     * Priority for the built-in {@link RoutingContextResolver}.
     * Application resolvers use the default priority of {@code 0} and run before this.
     */
    int PRIORITY_ROUTING_CONTEXT = 100;

    /**
     * Priority for the built-in {@link JaxRsSecurityContextResolver}.
     * Application resolvers use the default priority of {@code 0} and run before this.
     */
    int PRIORITY_JAXRS_SECURITY_CONTEXT = 110;

    /**
     * Priority for the built-in {@link ContextHolderResolver}.
     * Application resolvers use the default priority of {@code 0} and run before this.
     */
    int PRIORITY_CONTEXT_HOLDER = 120;

    /**
     * Resolves a context value of the given {@code type} for the current request, or
     * {@link Optional#empty()} if this resolver does not handle the type or the value is not bound
     * to the current request.
     *
     * <p>Implementations MUST NOT create, mutate, enrich, replace, or propagate context
     * (FR-REST-172). This method is invoked on the Vert.x event loop; it must not block.
     *
     * @param <T>  the context value type
     * @param type the class of the requested context value; never {@code null}
     * @param ctx  the current Vert.x routing context; never {@code null}
     * @return a non-empty {@link Optional} containing the resolved value, or
     *         {@link Optional#empty()} if this resolver cannot provide the value
     */
    <T> Optional<T> resolve(Class<T> type, RoutingContext ctx);
}
