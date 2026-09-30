// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;

/**
 * Contributes both shared resources, {@link CatalogResource} and {@link ManagementResource}, as
 * manual {@code @JaxRsResources} instances.
 */
@Module(includes = CatalogResourceModule.class)
public final class SharedResourcesModule {

    private SharedResourcesModule() {}

    /**
     * Contributes the Dagger-constructed {@link ManagementResource}.
     *
     * @param resource the injected resource instance
     * @return {@code resource}, contributed into the {@code @JaxRsResources Set<Object>}
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object managementResource(ManagementResource resource) {
        return resource;
    }
}
