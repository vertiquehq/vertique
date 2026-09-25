// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.manual.membership;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.application.unita.membership.AmbiguousResource;

/**
 * TP-005 case 6's manual side: contributes a manual {@link AmbiguousResource} instance — the shape
 * sibling framework modules use — so a listed {@link AmbiguousResource} has both a catalog match
 * (via {@code unita.membership.AmbiguousResourceCatalogModule}) and this manual match, tripping
 * C-COMPOSE step 6.7's ambiguity check.
 */
@Module
public final class AmbiguousResourceManualModule {

    /**
     * Contributes the Dagger-constructed {@link AmbiguousResource} into the
     * {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object ambiguousResource(AmbiguousResource r) {
        return r;
    }
}
