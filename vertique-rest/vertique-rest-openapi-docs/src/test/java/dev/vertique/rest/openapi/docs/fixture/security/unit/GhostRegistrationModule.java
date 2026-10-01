// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.unit;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * Registers {@link GhostApi}, active, in the shape the annotation processor generates, and
 * contributes its {@link GhostResource} as a manual {@code @JaxRsResources} instance.
 */
@Module
public final class GhostRegistrationModule {

    private GhostRegistrationModule() {}

    /**
     * Registers {@link GhostApi} at {@value GhostApi#PATH} listing {@link GhostResource}, active.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration ghostApiRegistration() {
        return GeneratedRestApplicationRegistration.of(
                GhostApi.class, GhostApi.NAME, GhostApi.PATH, List.of(GhostResource.class), false, "", true);
    }

    /**
     * Contributes the Dagger-constructed {@link GhostResource}.
     *
     * @param resource the injected resource instance
     * @return {@code resource}, contributed into the {@code @JaxRsResources Set<Object>}
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object ghostResource(GhostResource resource) {
        return resource;
    }
}
