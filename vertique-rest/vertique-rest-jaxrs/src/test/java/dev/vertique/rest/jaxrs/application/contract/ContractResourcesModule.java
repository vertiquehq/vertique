// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.contract;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;

/**
 * Manually contributes {@link ContractAResource}, {@link ContractBResource},
 * and {@link ContractCResource} into {@code @JaxRsResources Set<Object>} — the manual shape
 * {@link dev.vertique.rest.jaxrs.application.manual.ManualResourceModule} uses — so explicit applications {@code a}, {@code b}, and {@code c} each have exactly one manual candidate
 * matching their listed class. Without it, these classes would be unbound by any catalog or manual
 * module, so composition would correctly fail naming the unbound class instead of deploying.
 */
@Module
public final class ContractResourcesModule {

    private ContractResourcesModule() {}

    /**
     * Contributes the Dagger-constructed {@link ContractAResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object contractAResource(ContractAResource r) {
        return r;
    }

    /**
     * Contributes the Dagger-constructed {@link ContractBResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object contractBResource(ContractBResource r) {
        return r;
    }

    /**
     * Contributes the Dagger-constructed {@link ContractCResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object contractCResource(ContractCResource r) {
        return r;
    }
}
