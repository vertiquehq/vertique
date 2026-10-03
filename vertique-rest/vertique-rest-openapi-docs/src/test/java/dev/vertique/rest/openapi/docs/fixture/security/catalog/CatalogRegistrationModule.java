// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.catalog;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * Registers {@link CatalogApi}, active, exactly as the annotation processor would emit it for a
 * discovery application: no listed resources, discovery membership, and no OpenAPI path. The
 * processor does not run on framework test sources, so the registration is written by hand.
 */
@Module
public final class CatalogRegistrationModule {

    private CatalogRegistrationModule() {}

    /**
     * Registers {@link CatalogApi}.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration catalogApiRegistration() {
        return GeneratedRestApplicationRegistration.of(
                CatalogApi.class, CatalogApi.NAME, CatalogApi.PATH, List.of(), true, "", true);
    }
}
