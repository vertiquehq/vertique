// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;

/**
 * TP-005 case 23's (G-03) manual module, contributing {@link MembershipClassLevelPermitAllResource}.
 * Included only in {@code MembershipComponents.SubclassClassLevelPermitAllComponent}, so it is the
 * sole manual candidate when {@link MembershipBaseResource} is listed.
 */
@Module
public final class MembershipClassLevelPermitAllResourceModule {

    /**
     * Contributes the Dagger-constructed {@link MembershipClassLevelPermitAllResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object membershipClassLevelPermitAllResource(MembershipClassLevelPermitAllResource r) {
        return r;
    }
}
