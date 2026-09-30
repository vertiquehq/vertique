// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;

/** Contributes {@link CatalogResource} as a manual {@code @JaxRsResources} instance. */
@Module
public final class CatalogResourceModule {

    private CatalogResourceModule() {}

    /**
     * Contributes the Dagger-constructed {@link CatalogResource}.
     *
     * @param resource the injected resource instance
     * @return {@code resource}, contributed into the {@code @JaxRsResources Set<Object>}
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object catalogResource(CatalogResource resource) {
        return resource;
    }
}
