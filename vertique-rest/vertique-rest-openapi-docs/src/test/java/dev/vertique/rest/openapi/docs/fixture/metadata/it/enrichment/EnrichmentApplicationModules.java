// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.enrichment;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * Dagger modules that each register one declared application of the metadata-enrichment integration
 * tests and contribute its resource. Every registration is built exactly as the annotation processor
 * would emit it, {@code GeneratedRestApplicationRegistration.of(declaringType, name, path, resources,
 * false, "", true)}, since the processor does not run on framework test sources; every resource
 * instance is contributed as a manual {@code @JaxRsResources} instance.
 *
 * <p>The two catalog twins share their operation ids, so {@link Gen} and {@link Refl} each belong to
 * a composition of their own.
 */
public final class EnrichmentApplicationModules {

    private EnrichmentApplicationModules() {}

    private static GeneratedRestApplicationRegistration register(
            Class<?> declaringType, String name, String path, Class<?> resource) {
        return GeneratedRestApplicationRegistration.of(declaringType, name, path, List.of(resource), false, "", true);
    }

    /** The application {@code gen} with the generated-path catalog twin. */
    @Module
    public static final class Gen {

        private Gen() {}

        /**
         * Registers {@link GenApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(GenApi.class, GenApi.NAME, GenApi.PATH, GeneratedCatalogResource.class);
        }

        /**
         * Contributes {@link GeneratedCatalogResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new GeneratedCatalogResource();
        }
    }

    /** The application {@code refl} with the reflective-path catalog twin. */
    @Module
    public static final class Refl {

        private Refl() {}

        /**
         * Registers {@link ReflApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(ReflApi.class, ReflApi.NAME, ReflApi.PATH, ReflectedCatalogResource.class);
        }

        /**
         * Contributes {@link ReflectedCatalogResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new ReflectedCatalogResource();
        }
    }

    /** The application {@code shop} with its products resource. */
    @Module
    public static final class Shop {

        private Shop() {}

        /**
         * Registers {@link ShopApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(ShopApi.class, ShopApi.NAME, ShopApi.PATH, ProductsResource.class);
        }

        /**
         * Contributes {@link ProductsResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new ProductsResource();
        }
    }
}
