// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.lifecycle;

import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.rest.core.routing.RouterSetup;
import io.vertx.ext.web.Router;

/**
 * Lifecycle hook for the router creation phase.
 *
 * <p>Implementations are sorted by the {@link OrderedExtension} ordering contract — phase first,
 * then {@link #priority()} ascending, then {@link #orderKey()} as a stable tie-break — and called
 * in that order.
 *
 * <p>Injected into {@code JaxRsRouterMount} via Dagger {@code Set<RouterLifecycleHook>} multibinding.
 *
 * @see OrderedExtension
 */
public interface RouterLifecycleHook extends OrderedExtension {

    /**
     * Called before authentication handlers are set up.
     *
     * @param setup the transport-neutral {@link RouterSetup} being configured
     */
    default void beforeAuthSetup(RouterSetup setup) {}

    /**
     * Called after authentication handlers are set up.
     *
     * @param setup the transport-neutral {@link RouterSetup} that has been configured with security
     *     handlers
     */
    default void afterAuthSetup(RouterSetup setup) {}

    /**
     * Called after the Router is created.
     *
     * @param router the fully created {@link Router} before it is mounted as a sub-router
     */
    default void afterRouterCreated(Router router) {}
}
