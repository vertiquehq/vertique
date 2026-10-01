// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.listing;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * Registers {@link RosterApi}, active, in the shape the annotation processor generates, listing its
 * resources in an order that differs from their path order, and contributes each resource as a
 * manual {@code @JaxRsResources} instance.
 */
@Module
public final class RosterRegistrationModule {

    private RosterRegistrationModule() {}

    /**
     * Registers {@link RosterApi} at {@value RosterApi#PATH} listing {@link BetaResource}, {@link
     * AlphaResource}, and {@link OpenResource}, active.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration rosterApiRegistration() {
        return GeneratedRestApplicationRegistration.of(
                RosterApi.class,
                RosterApi.NAME,
                RosterApi.PATH,
                List.of(BetaResource.class, AlphaResource.class, OpenResource.class),
                false,
                "",
                true);
    }

    /**
     * Contributes the Dagger-constructed {@link BetaResource}.
     *
     * @param resource the injected resource instance
     * @return {@code resource}, contributed into the {@code @JaxRsResources Set<Object>}
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object betaResource(BetaResource resource) {
        return resource;
    }

    /**
     * Contributes the Dagger-constructed {@link AlphaResource}.
     *
     * @param resource the injected resource instance
     * @return {@code resource}, contributed into the {@code @JaxRsResources Set<Object>}
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object alphaResource(AlphaResource resource) {
        return resource;
    }

    /**
     * Contributes the Dagger-constructed {@link OpenResource}.
     *
     * @param resource the injected resource instance
     * @return {@code resource}, contributed into the {@code @JaxRsResources Set<Object>}
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object openResource(OpenResource resource) {
        return resource;
    }
}
