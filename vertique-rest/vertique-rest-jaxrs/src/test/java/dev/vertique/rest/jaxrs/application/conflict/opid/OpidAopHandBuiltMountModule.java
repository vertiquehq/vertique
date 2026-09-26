// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import java.util.Set;

/**
 * TP-005 (T004) case (f) fixture: hand-built module contributing a {@code JaxRsRouterMount} at
 * {@link #MOUNT_PATH}, holding an unproxied {@link OpidAopBaseResource} instance passed directly
 * to {@link JaxRsRouterMount.Factory#create} — never through {@code @JaxRsResources}, so
 * {@link OpidAopApplication}'s listing of {@link OpidAopBaseResource} stays unambiguous (matched
 * only by {@link OpidAopProxyResourceModule}'s manual contribution). Non-conflicting with
 * {@link OpidAopApplication}'s mount at {@code /opid/aop/*}.
 */
@Module
public final class OpidAopHandBuiltMountModule {

    private OpidAopHandBuiltMountModule() {}

    /** This mount's path, non-conflicting with {@link OpidAopApplication}'s {@code /opid/aop/*}. */
    public static final String MOUNT_PATH = "/opid-aop-other/*";

    /**
     * Builds the hand-built mount from an unproxied {@link OpidAopBaseResource} instance.
     *
     * @param factory the shared JAX-RS mount factory
     * @return the hand-built mount, contributed into {@code Set<RouterMount>}
     */
    @Provides
    @IntoSet
    static RouterMount opidAopHandBuiltMount(JaxRsRouterMount.Factory factory) {
        return factory.create(MOUNT_PATH, "openapi.json", Set.of(new OpidAopBaseResource()));
    }
}
