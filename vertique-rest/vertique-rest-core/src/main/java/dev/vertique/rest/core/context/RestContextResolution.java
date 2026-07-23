// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.context;

import dev.vertique.core.extension.OrderedExtension;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Coordinator that drives the {@link RestContextResolver} chain for a single request.
 *
 * <p>At construction time the full set of registered resolvers is sorted into an immutable,
 * deterministic chain using {@link OrderedExtension#comparator()}:
 * <ol>
 *   <li>Primary sort: {@link RestContextResolver#phase()} ascending (FR-REST-194).
 *   <li>Secondary sort: {@link RestContextResolver#priority()} ascending — lower values run first.
 *       Application resolvers at the default priority of {@code 0} therefore run before framework
 *       built-ins at higher values.
 *   <li>Tertiary sort: {@link RestContextResolver#orderKey()} alphabetically (defaults to the
 *       fully-qualified class name) — breaks ties so the chain order is reproducible across JVM
 *       restarts.
 * </ol>
 *
 * <p>This class is a {@link Singleton} managed by Dagger and intended to be injected into the
 * REST dispatch layer. The sorted chain is computed once and reused for every request.
 *
 * @see RestContextResolver
 * @see RestContextUnavailableException
 * @see OrderedExtension
 */
@Singleton
public final class RestContextResolution {

    /** Comparator that establishes the stable, deterministic chain order. */
    private static final Comparator<OrderedExtension> CHAIN_ORDER = OrderedExtension.comparator();

    /** The sorted, immutable resolver chain. Built once in the constructor. */
    private final List<RestContextResolver> chain;

    /**
     * Constructs the coordinator and sorts the provided resolvers into the chain.
     *
     * <p>Intended for Dagger constructor injection. The {@code resolvers} set is provided via a
     * Dagger {@code @Multibinds Set<RestContextResolver>} declaration and populated by
     * {@code @IntoSet} contributions from application and framework modules.
     *
     * @param resolvers the full set of registered context resolvers; may be empty but must not be
     *                  {@code null}
     */
    @Inject
    public RestContextResolution(Set<RestContextResolver> resolvers) {
        this.chain = resolvers.stream().sorted(CHAIN_ORDER).toList();
    }

    /**
     * Resolves a context value of the given {@code type} for the current request by walking the
     * sorted resolver chain and returning the first non-empty result.
     *
     * <p>If no resolver in the chain returns a value, this method returns {@link Optional#empty()}.
     * To obtain a required value and throw a typed exception on absence, use
     * {@link #require(Class, RoutingContext, String, String)}.
     *
     * @param <T>  the context value type
     * @param type the class of the requested context value; must not be {@code null}
     * @param ctx  the current Vert.x routing context; must not be {@code null}
     * @return the first non-empty resolved value, or {@link Optional#empty()} if none matched
     */
    public <T> Optional<T> resolve(Class<T> type, RoutingContext ctx) {
        for (RestContextResolver resolver : chain) {
            Optional<T> value = resolver.resolve(type, ctx);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves a context value of the given {@code type} for the current request, throwing
     * {@link RestContextUnavailableException} if the value is absent (FR-REST-173/174).
     *
     * <p>This is the required form of resolution used for JAX-RS resource method parameters that
     * the dispatch layer has identified as injectable context types. When the resolver chain
     * returns no value, the resulting exception carries the exact FR-REST-174 message and the
     * {@code type}, {@code resourceClass}, and {@code methodName} that identify where the missing
     * binding was expected.
     *
     * @param <T>           the context value type
     * @param type          the class of the requested context value; must not be {@code null}
     * @param ctx           the current Vert.x routing context; must not be {@code null}
     * @param resourceClass the simple or qualified name of the JAX-RS resource class declaring
     *                      the parameter; used in the exception message; must not be {@code null}
     * @param methodName    the name of the resource method declaring the parameter; used in the
     *                      exception message; must not be {@code null}
     * @return the resolved context value; never {@code null}
     * @throws RestContextUnavailableException if no resolver in the chain can supply a value of
     *                                         the requested type for this request
     */
    public <T> T require(Class<T> type, RoutingContext ctx, String resourceClass, String methodName) {
        return resolve(type, ctx)
                .orElseThrow(() -> new RestContextUnavailableException(type, resourceClass, methodName));
    }
}
