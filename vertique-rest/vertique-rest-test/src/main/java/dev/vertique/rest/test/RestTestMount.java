// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.test;

import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.MiddlewareScope;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.Set;

/**
 * Opaque handle to the collaborators {@link RestTestMounts} needs in order to assemble a mount the
 * way the framework's own HTTP verticle assembles one.
 *
 * <p>A faithful mount has <b>two</b> middleware tiers, installed by two different pieces of
 * production code. {@code JaxRsRouterMount} installs the {@link MiddlewareScope#API}-scoped
 * {@link Middleware}s on the API router it builds, but the {@link MiddlewareScope#ROOT}-scoped ones —
 * which is what {@link Middleware#scope()} <em>defaults</em> to — are installed by
 * {@code HttpVerticle} on the main router, above the mount. A helper handed only a
 * {@link JaxRsRouterMount.Factory} can therefore reconstruct just one of the two tiers, and a server
 * built that way silently drops the framework's root pipeline — per-request context lifecycle,
 * correlation ingress, default headers, contextual logging, request-completion emission — along with
 * every ROOT middleware a test contributes. This handle carries both halves so the helper can install
 * both.
 *
 * <h2>Why this type is opaque</h2>
 *
 * <p>Obtain one only by declaring it on a Dagger component over {@link RestTestFixtureModule}:
 *
 * <pre>{@code
 * RestTestMount testMount();
 * }</pre>
 *
 * <p>The type is {@code public} so a consumer can hold one in a field and pass it to
 * {@link RestTestMounts}; its constructor and accessors are deliberately <b>package-private</b> so
 * that a graph built by Dagger is the <em>only</em> way to produce one. Any public construction path
 * would let a caller pair a real factory with an arbitrary — or empty — middleware set and rebuild
 * exactly the unfaithful pipeline this type exists to make unrepresentable.
 *
 * <p>That is also why this is a {@code class} and not a {@code record}: a public record has a public
 * canonical constructor by definition (JLS 8.10.4), so recording the same two components would
 * reopen the hand-assembly path the package-private constructor closes.
 *
 * @see RestTestMounts
 * @see RestTestFixtureModule
 */
public final class RestTestMount {

    /** The framework-assembled mount factory, which builds the API router. */
    private final JaxRsRouterMount.Factory factory;

    /** The full framework middleware set, both tiers, exactly as {@code HttpVerticle} receives it. */
    private final Set<Middleware> middlewares;

    /**
     * Creates a handle over a Dagger-assembled mount factory and the graph's complete middleware set.
     * Package-private: see the class javadoc for why the only construction path is the DI graph.
     *
     * @param factory     the framework-assembled mount factory; must not be {@code null}
     * @param middlewares every {@link Middleware} in the graph, of either scope; defensively copied,
     *                    so the handle is immutable regardless of what the graph retains
     * @throws NullPointerException if either argument, or any middleware, is {@code null}
     */
    @Inject
    RestTestMount(JaxRsRouterMount.Factory factory, Set<Middleware> middlewares) {
        this.factory = Objects.requireNonNull(factory, "factory must not be null");
        this.middlewares = Set.copyOf(Objects.requireNonNull(middlewares, "middlewares must not be null"));
    }

    /**
     * Returns the mount factory that builds the API router.
     *
     * @return the mount factory
     */
    JaxRsRouterMount.Factory factory() {
        return factory;
    }

    /**
     * Returns every middleware in the graph, unfiltered and unordered — scope filtering and
     * {@link dev.vertique.core.extension.OrderedExtension} ordering are the installation site's job,
     * exactly as they are in {@code HttpVerticle} and {@code JaxRsRouterMount}.
     *
     * @return the immutable middleware set
     */
    Set<Middleware> middlewares() {
        return middlewares;
    }
}
