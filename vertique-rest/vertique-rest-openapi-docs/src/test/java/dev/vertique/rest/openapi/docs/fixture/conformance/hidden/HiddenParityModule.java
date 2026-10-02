// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.hidden;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden.HiddenClassResource;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden.HiddenContractResource;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden.MixedOperationsResource;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden.PartlyHiddenContractResource;
import java.util.List;

/**
 * Registers {@link HiddenParityApi} exactly as the generated registration module would, {@code
 * GeneratedRestApplicationRegistration.of(declaringType, name, path, resources, false, "", true)},
 * since the annotation processor does not run on framework test sources, and contributes a new
 * instance of every resource it lists as a manual {@code @JaxRsResources} instance.
 */
@Module
public final class HiddenParityModule {

    private HiddenParityModule() {}

    /**
     * Registers {@link HiddenParityApi} with its four resource classes, in declaration order.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration registration() {
        return GeneratedRestApplicationRegistration.of(
                HiddenParityApi.class,
                HiddenParityApi.NAME,
                HiddenParityApi.PATH,
                List.of(
                        MixedOperationsResource.class,
                        HiddenClassResource.class,
                        HiddenContractResource.class,
                        PartlyHiddenContractResource.class),
                false,
                "",
                true);
    }

    /**
     * Contributes {@link MixedOperationsResource}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object mixedResource() {
        return new MixedOperationsResource();
    }

    /**
     * Contributes {@link HiddenClassResource}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object hiddenClassResource() {
        return new HiddenClassResource();
    }

    /**
     * Contributes {@link HiddenContractResource}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object hiddenContractResource() {
        return new HiddenContractResource();
    }

    /**
     * Contributes {@link PartlyHiddenContractResource}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object partlyHiddenContractResource() {
        return new PartlyHiddenContractResource();
    }
}
