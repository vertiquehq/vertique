// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.corpus;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * Registers {@link CorpusApi}, active, in the shape the annotation processor generates, and
 * contributes its resource as a manual {@code @JaxRsResources} instance.
 */
@Module
public final class CorpusRegistrationModule {

    private CorpusRegistrationModule() {}

    /**
     * Registers application {@code corpus} at {@code /api/corpus} listing {@link CorpusResource}.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration corpusRegistration() {
        return GeneratedRestApplicationRegistration.of(
                CorpusApi.class, CorpusApi.NAME, CorpusApi.PATH, List.of(CorpusResource.class), false, "", true);
    }

    /**
     * Contributes the Dagger-constructed {@link CorpusResource}.
     *
     * @param resource the injected resource instance
     * @return {@code resource}, contributed into the {@code @JaxRsResources Set<Object>}
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object corpusResource(CorpusResource resource) {
        return resource;
    }
}
