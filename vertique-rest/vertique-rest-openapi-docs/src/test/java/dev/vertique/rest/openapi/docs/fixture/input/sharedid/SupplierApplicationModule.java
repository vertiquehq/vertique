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
 * Registers the application {@code supplier} ({@link SupplierApi}) exactly as the annotation processor
 * would, since the processor does not run on framework test sources, and contributes its {@link
 * DeliveryResource} as a manual {@code @JaxRsResources} instance.
 */
@Module
public final class SupplierApplicationModule {

    private SupplierApplicationModule() {}

    /**
     * Registers {@link SupplierApi}.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration registration() {
        return GeneratedRestApplicationRegistration.of(
                SupplierApi.class,
                SupplierApi.NAME,
                SupplierApi.PATH,
                List.<Class<?>>of(DeliveryResource.class),
                false,
                "",
                true);
    }

    /**
     * Contributes {@link DeliveryResource}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object resource() {
        return new DeliveryResource();
    }
}
