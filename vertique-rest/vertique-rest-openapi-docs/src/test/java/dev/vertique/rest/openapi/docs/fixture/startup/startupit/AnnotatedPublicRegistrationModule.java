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
 * Registers the shared declarations with {@link AnnotatedPublicApi} in place of {@link PublicApi},
 * both active, calling {@link GeneratedRestApplicationRegistration#of} exactly as a generated module
 * does: the documented {@code public} application whose interface declares its {@code info}, beside
 * the undocumented {@link MgmtApi}.
 */
@Module
public final class AnnotatedPublicRegistrationModule {

    private AnnotatedPublicRegistrationModule() {}

    /**
     * Registers {@link AnnotatedPublicApi}, active.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration annotatedPublicApiRegistration() {
        return GeneratedRestApplicationRegistration.of(
                AnnotatedPublicApi.class,
                PublicApi.NAME,
                PublicApi.PATH,
                List.of(CatalogResource.class),
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
