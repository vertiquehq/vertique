// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.routing;

import io.vertx.ext.web.Router;

/**
 * Global router-setup surface handed to router lifecycle hooks.
 *
 * <p>This is the neutral replacement for the global portion of the previous {@code RouterBuilder}
 * surface. It exposes the underlying {@link Router} together with the
 * {@link SecuritySchemeRegistry} for scheme registration.
 */
public interface RouterSetup {

    /**
     * Returns the underlying Vert.x web {@link Router} being constructed.
     *
     * <p><strong>Availability constraint:</strong> an implementation MAY throw
     * {@link UnsupportedOperationException} when the router is not yet available — notably during the
     * {@code beforeAuthSetup}/{@code afterAuthSetup} phases of the transitional RouterBuilder-based
     * setup, where the {@link Router} does not exist until routing is built. The router becomes
     * reliably available once routing is built directly on a plain {@link Router} (PRD-REST-017
     * FR-001). A lifecycle hook that needs the {@link Router} during the transitional era should use
     * {@link dev.vertique.rest.core.lifecycle.RouterLifecycleHook#afterRouterCreated(Router)} instead.
     *
     * @return the router under construction
     * @throws UnsupportedOperationException if the router is not yet available in the current
     *     setup phase (see the availability constraint above)
     */
    Router router();

    /**
     * Returns the security scheme registry for registering scheme authentication handlers.
     *
     * @return the non-null security scheme registry
     */
    SecuritySchemeRegistry security();
}
