// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid.tp009;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;

/**
 * Contributes {@link PublicListResource} and {@link PartnerListResource} manually, satisfying
 * {@link Tp009Apis.PublicApi}'s and {@link Tp009Apis.PartnerApi}'s membership so each row's
 * composition mounts, rather than failing on an unbound listed class before the operationId
 * collision is ever reached.
 */
@Module
public final class Tp009ManualResourcesModule {

    private Tp009ManualResourcesModule() {}

    /**
     * Contributes the Dagger-constructed {@link PublicListResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object publicListResource(PublicListResource r) {
        return r;
    }

    /**
     * Contributes the Dagger-constructed {@link PartnerListResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object partnerListResource(PartnerListResource r) {
        return r;
    }
}
