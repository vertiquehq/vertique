// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import io.vertx.ext.web.handler.AuthenticationHandler;
import java.util.Objects;

/**
 * Plain-{@link io.vertx.ext.web.Router Router} {@link SecuritySchemeRegistry} that, scoped to a single
 * {@link SecuritySchemeHandler}, records the {@link AuthenticationHandler} that handler registers so the
 * mount can later apply it per the operations that require its scheme.
 *
 * <p>One instance is created per scheme handler and passed to its
 * {@link SecuritySchemeHandler#configure(SecuritySchemeRegistry)} call; the registered handler is stored
 * into a shared scheme-name → handler map keyed by the contributing handler's
 * {@link SecuritySchemeHandler#schemeName()}. The recorded type is an {@link AuthenticationHandler} so
 * the mount can compose alternative single-scheme requirements into a Vert.x
 * {@code ChainAuthHandler.any()} (an OR over schemes); {@code configure} is called once per handler with
 * no contract gating — resolving FR-014. The contributing handler is carried (rather than just its
 * scheme name) so the collector can name the conflicting handler classes when two handlers claim the
 * same scheme (finding W5).
 */
final class CollectingSecuritySchemeRegistry implements SecuritySchemeRegistry {

    private final SecuritySchemeHandler contributor;
    private final SecuritySchemeHandlerCollector collector;

    /**
     * Creates a registry scoped to the given scheme handler that records into the shared collector.
     *
     * @param contributor the scheme handler this registry is scoped to; must not be {@code null}
     * @param collector   the shared collector that stores {@code schemeName -> handler}; must not be
     *                    {@code null}
     */
    CollectingSecuritySchemeRegistry(SecuritySchemeHandler contributor, SecuritySchemeHandlerCollector collector) {
        this.contributor = Objects.requireNonNull(contributor, "contributor");
        this.collector = Objects.requireNonNull(collector, "collector");
    }

    @Override
    public void authenticationHandler(AuthenticationHandler handler) {
        Objects.requireNonNull(handler, "handler");
        collector.record(contributor, handler);
    }
}
