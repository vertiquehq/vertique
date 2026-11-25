// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;

/**
 * Manually contributes {@link OpidAlphaListResource},
 * {@link OpidBetaListResource}, {@link OpidSharedListResource}, {@link OpidInheritedFirstResource},
 * and {@link OpidInheritedSecondResource} into {@code @JaxRsResources Set<Object>} — the manual
 * shape {@link dev.vertique.rest.jaxrs.application.manual.ManualResourceModule} uses — so cases (a), (b), and (d) (built from {@link GeneratedJaxRsResourcesModule}'s registrations) each
 * have exactly one manual candidate matching every class its active application lists. Without this module,
 * these classes would be unbound by any catalog or manual module, so composition would fail naming the
 * unbound class before the cross-mount operationId collision was ever reached.
 */
@Module
public final class OpidResourcesModule {

    private OpidResourcesModule() {}

    /**
     * Contributes the Dagger-constructed {@link OpidAlphaListResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object opidAlphaListResource(OpidAlphaListResource r) {
        return r;
    }

    /**
     * Contributes the Dagger-constructed {@link OpidBetaListResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object opidBetaListResource(OpidBetaListResource r) {
        return r;
    }

    /**
     * Contributes the Dagger-constructed {@link OpidSharedListResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object opidSharedListResource(OpidSharedListResource r) {
        return r;
    }

    /**
     * Contributes the Dagger-constructed {@link OpidInheritedFirstResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object opidInheritedFirstResource(OpidInheritedFirstResource r) {
        return r;
    }

    /**
     * Contributes the Dagger-constructed {@link OpidInheritedSecondResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object opidInheritedSecondResource(OpidInheritedSecondResource r) {
        return r;
    }
}
