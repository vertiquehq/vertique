// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;

/**
 * TP-005 case 17's manual module, contributing {@link MembershipClassPathResource}. Included only
 * in {@code MembershipComponents.SubclassClassPathComponent}, so it is the sole manual candidate
 * when {@link MembershipBaseResource} is listed.
 */
@Module
public final class MembershipClassPathResourceModule {

    /**
     * Contributes the Dagger-constructed {@link MembershipClassPathResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object membershipClassPathResource(MembershipClassPathResource r) {
        return r;
    }
}
