// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication;

import io.vertx.core.Future;
import jakarta.annotation.Nullable;

/**
 * INTERNAL extension point for a sibling framework module to receive a detached, per-mount
 * snapshot of every JAX-RS operation {@code JaxRsRouterMount} builds. Public only for cross-module
 * use by sibling framework modules; outside the maturity promise and not an application contract.
 *
 * <p>Every sink contributed to the {@code Set<OperationPublicationSink>} multibinding runs on the
 * event loop of the verticle composing the mount's router; several compositions may call a sink
 * concurrently. A sink's {@link #mountBuilt} may return an incomplete future and complete it
 * later, on another thread; {@code createRouter}'s continuation runs wherever that future
 * completes.
 *
 * <p>{@code createRouter}'s returned future succeeds with the router once every sink's future has
 * succeeded, and fails with a sink's future's failure. On success, the mount's {@code
 * MountCustomizer}s run and the server listens for that mount as usual; on failure, they never run
 * and the server never listens. A {@link RuntimeException} thrown by {@link
 * #wantsDetail} or {@link #mountBuilt} is likewise fatal to the enclosing mount's router creation
 * and so to startup. A sink must not retain the {@link MountPublication} it receives, or anything
 * reachable from it, past the call.
 */
public interface OperationPublicationSink {

    /**
     * Whether operations of this mount need schema copies and inventories; the argument is the
     * mount's application name, or null for a JAX-RS mount that serves no declared application.
     */
    boolean wantsDetail(@Nullable String applicationName);

    /**
     * Called once per JAX-RS mount at the end of createRouter, including empty mounts; createRouter
     * completes only when the returned future does. Implementations must not retain the publication
     * or anything reachable from it past the call. Exceptions thrown by this callback, and a failed
     * future, are fatal to the enclosing mount's router creation and so to startup; processing does
     * not continue.
     */
    Future<Void> mountBuilt(MountPublication publication);
}
