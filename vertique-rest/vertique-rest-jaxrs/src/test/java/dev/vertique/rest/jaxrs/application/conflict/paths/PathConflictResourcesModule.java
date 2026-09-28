// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.paths;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;

/**
 * T023 L28 restoration: manually contributes every {@code conflict.paths} fixture resource —
 * {@link AlphaResource}, {@link BetaResource}, {@link GammaResource}, {@link DeltaResource},
 * {@link RootResource}, {@link PublicProbeResource}, and {@link PublicityProbeResource} — into
 * {@code @JaxRsResources Set<Object>} — the manual shape
 * {@link dev.vertique.rest.jaxrs.application.manual.ManualResourceModule} uses — so every
 * {@link PathConflictApis} application has exactly one manual candidate matching its listed class.
 * Included by {@link GeneratedJaxRsResourcesModule} ({@code @Module(includes = ...)}), so every
 * component that already wires that registration module gets these bindings too, without this
 * package's Dagger components needing their own module list changed. The port left these classes
 * unbound by any catalog or manual module, so composition failed naming the unbound class before
 * TP-016's path-conflict rule (case 4's control) or the deliberate conflict (case (f)'s active
 * {@code RootApi}) was ever reached (AC-024.2).
 */
@Module
public final class PathConflictResourcesModule {

    private PathConflictResourcesModule() {}

    /**
     * Contributes the Dagger-constructed {@link AlphaResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object alphaResource(AlphaResource r) {
        return r;
    }

    /**
     * Contributes the Dagger-constructed {@link BetaResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object betaResource(BetaResource r) {
        return r;
    }

    /**
     * Contributes the Dagger-constructed {@link GammaResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object gammaResource(GammaResource r) {
        return r;
    }

    /**
     * Contributes the Dagger-constructed {@link DeltaResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object deltaResource(DeltaResource r) {
        return r;
    }

    /**
     * Contributes the Dagger-constructed {@link RootResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object rootResource(RootResource r) {
        return r;
    }

    /**
     * Contributes the Dagger-constructed {@link PublicProbeResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object publicProbeResource(PublicProbeResource r) {
        return r;
    }

    /**
     * Contributes the Dagger-constructed {@link PublicityProbeResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object publicityProbeResource(PublicityProbeResource r) {
        return r;
    }
}
