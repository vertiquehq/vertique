// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.served;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.openapi.docs.fixture.contract.CatalogEntryResource;
import dev.vertique.rest.openapi.docs.fixture.contract.OrderResource;
import dev.vertique.rest.openapi.docs.fixture.contract.PartnerOrderResource;
import java.util.List;

/**
 * Dagger modules, one per declared application of the served-contract conformance proof, each
 * registering its declaration exactly as the generated registration module does ({@code
 * GeneratedRestApplicationRegistration.of(declaringType, name, path, resources, false, openapiPath,
 * true)}, since the annotation processor does not run on framework test sources) and contributing a
 * new instance of every resource it lists as a manual {@code @JaxRsResources} instance. A
 * registration's {@code openapiPath} is the declaring interface's: the empty string for a
 * declaration that names no contract.
 */
public final class ConformanceServedApplications {

    private ConformanceServedApplications() {}

    /**
     * Registers {@link ConformancePartnerApi}, active, with its own contract by annotation, and
     * contributes {@link PartnerOrderResource}.
     */
    @Module
    public static final class Partner {

        private Partner() {}

        /**
         * Registers {@link ConformancePartnerApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return GeneratedRestApplicationRegistration.of(
                    ConformancePartnerApi.class,
                    ConformancePartnerApi.NAME,
                    ConformancePartnerApi.PATH,
                    List.of(PartnerOrderResource.class),
                    false,
                    ConformancePartnerApi.OPENAPI_PATH,
                    true);
        }

        /**
         * Contributes {@link PartnerOrderResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object partnerOrderResource() {
            return new PartnerOrderResource();
        }
    }

    /**
     * Registers {@link ConformanceOrdersApi}, active, whose declaring interface names no contract (the
     * configuration names it), and contributes {@link OrderResource}.
     */
    @Module
    public static final class Orders {

        private Orders() {}

        /**
         * Registers {@link ConformanceOrdersApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return GeneratedRestApplicationRegistration.of(
                    ConformanceOrdersApi.class,
                    ConformanceOrdersApi.NAME,
                    ConformanceOrdersApi.PATH,
                    List.of(OrderResource.class),
                    false,
                    "",
                    true);
        }

        /**
         * Contributes {@link OrderResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object orderResource() {
            return new OrderResource();
        }
    }

    /**
     * Registers {@link ConformanceCatalogApi}, active, without a contract of its own, and
     * contributes {@link CatalogEntryResource}.
     */
    @Module
    public static final class Catalog {

        private Catalog() {}

        /**
         * Registers {@link ConformanceCatalogApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return GeneratedRestApplicationRegistration.of(
                    ConformanceCatalogApi.class,
                    ConformanceCatalogApi.NAME,
                    ConformanceCatalogApi.PATH,
                    List.of(CatalogEntryResource.class),
                    false,
                    "",
                    true);
        }

        /**
         * Contributes {@link CatalogEntryResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object catalogEntryResource() {
            return new CatalogEntryResource();
        }
    }
}
