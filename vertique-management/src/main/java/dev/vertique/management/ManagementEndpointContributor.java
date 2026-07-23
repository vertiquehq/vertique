// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.management;

import dev.vertique.core.extension.OrderedExtension;
import io.vertx.ext.web.Router;

/**
 * SPI for modules that wish to mount additional routes on the management HTTP server.
 *
 * <p>Implementations are gathered via Dagger multibinding ({@code Set<ManagementEndpointContributor>})
 * and invoked once per management server start. The following invariants hold:
 *
 * <ul>
 *   <li>Contributors are invoked on the Vert.x event loop of the management verticle.
 *   <li>Health routes ({@code /health/*}) are mounted <em>before</em> contributors are invoked;
 *       any attempt to register a handler on those paths will not shadow the built-in health routes
 *       because the first registration wins in Vert.x router matching.
 *   <li>Contributors are invoked in {@link OrderedExtension#comparator()} order (ascending phase,
 *       then ascending priority, then ascending {@link OrderedExtension#orderKey()}).
 *   <li>When two contributors register handlers on the same path the first registration (the one
 *       with the lower comparator order) wins.
 *   <li>If a contributor's {@link #contribute} method throws, the management server startup fails
 *       immediately; the exception is propagated as the start-promise failure cause and no HTTP
 *       server port is bound.
 *   <li>Contributors are <em>not</em> invoked when management is disabled
 *       ({@code management.enabled=false}).
 * </ul>
 *
 * <p>Register an implementation via Dagger multibinding in your module:
 *
 * <pre>{@code
 * @Provides @IntoSet
 * static ManagementEndpointContributor metricsEndpoint(PrometheusHandler handler) {
 *     return router -> router.get("/metrics").handler(handler);
 * }
 * }</pre>
 *
 * @see ManagementModule
 * @see ManagementVerticle
 */
public interface ManagementEndpointContributor extends OrderedExtension {

    /**
     * Mounts one or more routes on the management {@link Router}.
     *
     * <p>Called once per management server start, after health routes are mounted and before
     * the HTTP server begins listening. Throwing from this method fails the management server
     * startup.
     *
     * @param router the management server router; health paths ({@code /health/*}) are already
     *               registered and must not be expected to take effect if re-registered here
     */
    void contribute(Router router);
}
