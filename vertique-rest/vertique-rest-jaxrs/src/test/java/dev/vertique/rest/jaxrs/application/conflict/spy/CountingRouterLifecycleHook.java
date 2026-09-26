// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.spy;

import dev.vertique.rest.core.lifecycle.RouterLifecycleHook;
import dev.vertique.rest.core.routing.RouterSetup;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TP-004 (T004) spy: a {@link RouterLifecycleHook} that counts every JAX-RS router creation in a
 * composition, using an existing extension seam so no production change is needed.
 * {@code JaxRsRouterMount.createRouter} calls {@link #beforeAuthSetup(RouterSetup)} once per
 * non-empty mount it builds, on every hook shared by the composition's
 * {@code JaxRsRouterMount.Factory} — the OQ-001 prototype's precedent for counting JAX-RS router
 * creation. Because a composition validator rejection fails {@code HttpVerticle.start} before any
 * mount's {@code createRouter} runs, {@link #CREATIONS} staying {@code 0} proves no JAX-RS router
 * — hand-built or application — was ever built.
 */
public final class CountingRouterLifecycleHook implements RouterLifecycleHook {

    /** Number of {@link #beforeAuthSetup(RouterSetup)} calls; reset before every case via {@link #reset()}. */
    public static final AtomicInteger CREATIONS = new AtomicInteger();

    /**
     * Counts the call.
     *
     * @param setup the router setup under construction (unused)
     */
    @Override
    public void beforeAuthSetup(RouterSetup setup) {
        CREATIONS.incrementAndGet();
    }

    /** Resets {@link #CREATIONS} to {@code 0}. */
    public static void reset() {
        CREATIONS.set(0);
    }
}
