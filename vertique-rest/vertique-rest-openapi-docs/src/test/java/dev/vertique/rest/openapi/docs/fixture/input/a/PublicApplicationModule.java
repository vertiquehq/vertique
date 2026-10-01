// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.a;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * Registers the application {@code public} ({@link PublicApi}) exactly as the annotation processor
 * would, {@code GeneratedRestApplicationRegistration.of(declaringType, name, path, resources, false,
 * "", true)}, since the processor does not run on framework test sources, and contributes its {@link
 * CatalogResource} as a manual {@code @JaxRsResources} instance.
 */
@Module
public final class PublicApplicationModule {

    private PublicApplicationModule() {}

    /**
     * Registers {@link PublicApi}.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration registration() {
        return GeneratedRestApplicationRegistration.of(
                PublicApi.class,
                PublicApi.NAME,
                PublicApi.PATH,
                List.<Class<?>>of(CatalogResource.class),
                false,
                "",
                true);
    }

    /**
     * Contributes {@link CatalogResource}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object resource() {
        return new CatalogResource();
    }
}
