// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;

/**
 * TP-005 (T004) case (f)'s manual module, contributing {@link OpidAopProxyResource} — the shape
 * sibling framework modules use. It is the sole manual candidate when {@link OpidAopBaseResource}
 * is listed, so {@link OpidAopApplication}'s membership resolution is unambiguous.
 */
@Module
public final class OpidAopProxyResourceModule {

    private OpidAopProxyResourceModule() {}

    /**
     * Contributes the Dagger-constructed {@link OpidAopProxyResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object opidAopProxyResource(OpidAopProxyResource r) {
        return r;
    }
}
