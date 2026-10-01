// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.startupit;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.openapi.docs.fixture.CatalogResource;
import dev.vertique.rest.openapi.docs.fixture.ManagementResource;
import dev.vertique.rest.openapi.docs.fixture.MgmtApi;
import dev.vertique.rest.openapi.docs.fixture.PublicApi;
import java.util.List;

/**
 * Registers {@link PublicReservedApi} in place of {@link PublicApi}, beside the undocumented
 * {@link MgmtApi}, both active, calling {@link GeneratedRestApplicationRegistration#of} exactly as a
 * generated module does. The {@link ReservedJsonResource} instance comes from
 * {@link ContributedResources}.
 */
@Module
public final class PublicReservedRegistrationModule {

    private PublicReservedRegistrationModule() {}

    /**
     * Registers {@link PublicReservedApi}, active.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration publicReservedApiRegistration() {
        return GeneratedRestApplicationRegistration.of(
                PublicReservedApi.class,
                PublicApi.NAME,
                PublicApi.PATH,
                List.of(CatalogResource.class, ReservedJsonResource.class),
                false,
                "",
                true);
    }

    /**
     * Registers {@link MgmtApi}, active.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration mgmtApiRegistration() {
        return GeneratedRestApplicationRegistration.of(
                MgmtApi.class, MgmtApi.NAME, MgmtApi.PATH, List.of(ManagementResource.class), false, "", true);
    }
}
