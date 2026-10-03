// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.patterns;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * Registers the application {@code patterns} ({@link PatternsApi}) exactly as the annotation
 * processor would, {@code GeneratedRestApplicationRegistration.of(declaringType, name, path,
 * resources, false, "", true)}, since the processor does not run on framework test sources, and
 * contributes its {@link PatternsResource} as a manual {@code @JaxRsResources} instance.
 */
@Module
public final class PatternsApplicationModule {

    private PatternsApplicationModule() {}

    /**
     * Registers {@link PatternsApi}.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration registration() {
        return GeneratedRestApplicationRegistration.of(
                PatternsApi.class,
                PatternsApi.NAME,
                PatternsApi.PATH,
                List.<Class<?>>of(PatternsResource.class),
                false,
                "",
                true);
    }

    /**
     * Contributes {@link PatternsResource}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object resource() {
        return new PatternsResource();
    }
}
