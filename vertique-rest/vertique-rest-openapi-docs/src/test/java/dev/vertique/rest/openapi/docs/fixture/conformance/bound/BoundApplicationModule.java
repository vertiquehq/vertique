// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.bound;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * Registers the application {@link BoundApi} exactly as the annotation processor would emit it, and
 * contributes its one resource as a manual {@code @JaxRsResources} instance.
 */
@Module
public final class BoundApplicationModule {

    private BoundApplicationModule() {}

    /**
     * Registers {@link BoundApi}.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration registration() {
        return GeneratedRestApplicationRegistration.of(
                BoundApi.class, BoundApi.NAME, BoundApi.PATH, List.of(BoundResource.class), false, "", true);
    }

    /**
     * Contributes {@link BoundResource}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object resource() {
        return new BoundResource();
    }
}
