// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.routing.RouterSetup;
import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import io.vertx.ext.web.Router;
import java.util.Objects;

/**
 * Plain-{@link Router} implementation of the neutral {@link RouterSetup} SPI handed to
 * {@link dev.vertique.rest.core.lifecycle.RouterLifecycleHook}s during the
 * {@code beforeAuthSetup}/{@code afterAuthSetup} phases.
 *
 * <p>Unlike the transitional {@code RouterBuilderRouterSetup}, the real {@link Router} exists upfront
 * (routing is built directly on a plain {@link Router}, PRD-REST-017 FR-001), so {@link #router()}
 * returns it rather than throwing. {@link #security()} remains unsupported: the global setup surface
 * has no single security scheme to scope a {@link SecuritySchemeRegistry} to — scheme handlers are
 * configured per scheme via {@link dev.vertique.rest.core.security.SecuritySchemeHandler}, each given a
 * scheme-scoped registry directly by the mount.
 */
final class PlainRouterSetup implements RouterSetup {

    private final Router router;

    /**
     * Creates a setup over the given plain router.
     *
     * @param router the plain Vert.x web router being configured; must not be {@code null}
     */
    PlainRouterSetup(Router router) {
        this.router = Objects.requireNonNull(router, "router");
    }

    @Override
    public Router router() {
        return router;
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always — the global setup surface has no single scheme to
     *     scope a {@link SecuritySchemeRegistry} to; scheme handlers are configured per scheme via
     *     {@link dev.vertique.rest.core.security.SecuritySchemeHandler}, each handed a scheme-scoped
     *     registry directly by the mount.
     */
    @Override
    public SecuritySchemeRegistry security() {
        throw new UnsupportedOperationException("Global RouterSetup.security() is not scheme-scoped; scheme handlers "
                + "are configured per scheme via SecuritySchemeHandler");
    }
}
