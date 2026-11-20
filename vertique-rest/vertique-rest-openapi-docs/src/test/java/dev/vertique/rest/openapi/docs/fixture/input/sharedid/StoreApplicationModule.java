// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.sharedid;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * Registers the application {@code store} ({@link StoreApi}) exactly as the annotation processor
 * would, since the processor does not run on framework test sources, and contributes its {@link
 * ShelfResource} as a manual {@code @JaxRsResources} instance.
 */
@Module
public final class StoreApplicationModule {

    private StoreApplicationModule() {}

    /**
     * Registers {@link StoreApi}.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration registration() {
        return GeneratedRestApplicationRegistration.of(
                StoreApi.class, StoreApi.NAME, StoreApi.PATH, List.<Class<?>>of(ShelfResource.class), false, "", true);
    }

    /**
     * Contributes {@link ShelfResource}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object resource() {
        return new ShelfResource();
    }
}
