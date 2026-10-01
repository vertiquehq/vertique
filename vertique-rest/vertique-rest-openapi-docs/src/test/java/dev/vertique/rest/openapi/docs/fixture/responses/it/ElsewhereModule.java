// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * Registers the application {@code elsewhere}, declared by {@link ElsewhereApi}, exactly as {@link
 * ResponseApplicationModules} registers its applications, and contributes its resource. Unlike those
 * modules it is meant to be listed beside one of them in a single composition.
 */
@Module
public final class ElsewhereModule {

    private ElsewhereModule() {}

    /**
     * Registers {@link ElsewhereApi}.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration registration() {
        return ResponseApplicationModules.register(
                ElsewhereApi.class, ElsewhereApi.NAME, ElsewhereApi.PATH, List.of(FixedReceiptResource.class));
    }

    /**
     * Contributes {@link FixedReceiptResource}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object fixedReceiptResource() {
        return new FixedReceiptResource();
    }
}
