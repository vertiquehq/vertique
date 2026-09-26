// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.spy;

import dev.vertique.rest.core.router.RouterMount;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TP-004 (T004) spy: a plain, non-JAX-RS {@link RouterMount} whose {@link #createRouter(Vertx)}
 * counts its own invocations in {@link #CREATIONS}. Because it is not a {@code JaxRsRouterMount},
 * the rest-jaxrs validator's path-conflict rule ignores it entirely (C-CONFLICT: "It ignores
 * non-JAX-RS mounts"), so its mount path never itself conflicts. It exists only to prove that
 * {@code HttpVerticle} never reaches ANY mount's {@code createRouter} — JAX-RS or not — once a
 * composition validator rejects the composition: a conflicting composition's start promise must
 * fail before this counter, or {@link CountingRouterLifecycleHook#CREATIONS}, ever moves off
 * {@code 0}.
 */
public final class CountingRouterMount implements RouterMount {

    /** Mount path this spy is registered at; deliberately outside every TP-004 conflict pair. */
    public static final String MOUNT_PATH = "/spy/*";

    /** Number of {@link #createRouter(Vertx)} calls; reset before every case via {@link #reset()}. */
    public static final AtomicInteger CREATIONS = new AtomicInteger();

    /** {@inheritDoc} */
    @Override
    public String mountPath() {
        return MOUNT_PATH;
    }

    /**
     * Counts the call, then returns an empty router immediately.
     *
     * @param vertx the Vert.x instance
     * @return a succeeded future holding an empty router
     */
    @Override
    public Future<Router> createRouter(Vertx vertx) {
        CREATIONS.incrementAndGet();
        return Future.succeededFuture(Router.router(vertx));
    }

    /** Resets {@link #CREATIONS} to {@code 0}. */
    public static void reset() {
        CREATIONS.set(0);
    }
}
