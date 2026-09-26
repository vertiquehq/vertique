// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;

/**
 * TP-005 case 15's manual module, contributing {@link MembershipOwnMethodResource}. Included only
 * in {@code MembershipComponents.SubclassOwnMethodComponent}, so it is the sole manual candidate
 * when {@link MembershipBaseResource} is listed.
 */
@Module
public final class MembershipOwnMethodResourceModule {

    /**
     * Contributes the Dagger-constructed {@link MembershipOwnMethodResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object membershipOwnMethodResource(MembershipOwnMethodResource r) {
        return r;
    }
}
