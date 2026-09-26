// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;

/**
 * TP-005 case 19's manual module, contributing {@link MembershipGrandchildResource}. Included only
 * in {@code MembershipComponents.SubclassGrandchildComponent}, so it is the sole manual candidate
 * when {@link MembershipBaseResource} is listed.
 */
@Module
public final class MembershipGrandchildResourceModule {

    /**
     * Contributes the Dagger-constructed {@link MembershipGrandchildResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object membershipGrandchildResource(MembershipGrandchildResource r) {
        return r;
    }
}
