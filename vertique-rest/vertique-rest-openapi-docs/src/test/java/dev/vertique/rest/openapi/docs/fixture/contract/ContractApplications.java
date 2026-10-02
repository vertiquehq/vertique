// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.GuardedManagementApi;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.TwinResource;
import java.util.List;

/**
 * Dagger modules, one per declared application variant, each registering its declaration exactly as
 * the generated registration module does ({@code GeneratedRestApplicationRegistration.of(declaringType,
 * name, path, resources, false, openapiPath, true)}, since the annotation processor does not run on
 * framework test sources) and contributing a new instance of every resource it lists as a manual
 * {@code @JaxRsResources} instance. A registration's {@code openapiPath} is the declaring
 * interface's: the empty string for a declaration that names no contract.
 *
 * <p>A component lists at most one module per application name: {@link Partner}, {@link
 * AnnotatedInfoPartner}, {@link ProtectedPartner}, {@link RestrictedPartner}, and {@link
 * ProtectedRestrictedPartner} all register {@code partner}; {@link Catalog}, {@link
 * ReservedIdCatalog}, and {@link ThreeSegmentsCatalog} all register {@code catalog}; {@link Alpha}
 * and {@link AlphaCatalog} register {@code alpha}; {@link Beta} and {@link BetaAdmin} register
 * {@code beta}.
 */
public final class ContractApplications {

    private ContractApplications() {}

    /**
     * Registers an active declaration exactly as the generated registration module does.
     *
     * @param declaringType the declaring interface
     * @param name          the application's name
     * @param path          the application's path
     * @param openapiPath   the declaring interface's contract location, {@code ""} for none
     * @param resources     the resource classes the declaration lists, in order
     * @return the registration
     */
    static GeneratedRestApplicationRegistration register(
            Class<?> declaringType, String name, String path, String openapiPath, Class<?>... resources) {
        return GeneratedRestApplicationRegistration.of(
                declaringType, name, path, List.of(resources), false, openapiPath, true);
    }

    /** Registers {@link PartnerApi}, active, with its own contract, and contributes {@link PartnerOrderResource}. */
    @Module
    public static final class Partner {

        private Partner() {}

        /**
         * Registers {@link PartnerApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(
                    PartnerApi.class,
                    PartnerApi.NAME,
                    PartnerApi.PATH,
                    PartnerApi.OPENAPI_PATH,
                    PartnerOrderResource.class);
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
     * Registers {@link AnnotatedInfoPartnerApi}, active, with its own contract, and contributes
     * {@link PartnerOrderResource}.
     */
    @Module
    public static final class AnnotatedInfoPartner {

        private AnnotatedInfoPartner() {}

        /**
         * Registers {@link AnnotatedInfoPartnerApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(
                    AnnotatedInfoPartnerApi.class,
                    PartnerApi.NAME,
                    PartnerApi.PATH,
                    PartnerApi.OPENAPI_PATH,
                    PartnerOrderResource.class);
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
     * Registers {@link ProtectedPartnerApi}, active, with its own contract, and contributes {@link
     * PartnerOrderResource}.
     */
    @Module
    public static final class ProtectedPartner {

        private ProtectedPartner() {}

        /**
         * Registers {@link ProtectedPartnerApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(
                    ProtectedPartnerApi.class,
                    PartnerApi.NAME,
                    PartnerApi.PATH,
                    PartnerApi.OPENAPI_PATH,
                    PartnerOrderResource.class);
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
     * Registers {@link RestrictedPartnerApi}, active, with its own contract, and contributes {@link
     * RestrictedPartnerOrderResource}.
     */
    @Module
    public static final class RestrictedPartner {

        private RestrictedPartner() {}

        /**
         * Registers {@link RestrictedPartnerApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(
                    RestrictedPartnerApi.class,
                    PartnerApi.NAME,
                    PartnerApi.PATH,
                    RestrictedPartnerApi.OPENAPI_PATH,
                    RestrictedPartnerOrderResource.class);
        }

        /**
         * Contributes {@link RestrictedPartnerOrderResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object restrictedPartnerOrderResource() {
            return new RestrictedPartnerOrderResource();
        }
    }

    /**
     * Registers {@link ProtectedRestrictedPartnerApi}, active, with its own contract, and
     * contributes {@link RestrictedPartnerOrderResource}.
     */
    @Module
    public static final class ProtectedRestrictedPartner {

        private ProtectedRestrictedPartner() {}

        /**
         * Registers {@link ProtectedRestrictedPartnerApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(
                    ProtectedRestrictedPartnerApi.class,
                    PartnerApi.NAME,
                    PartnerApi.PATH,
                    RestrictedPartnerApi.OPENAPI_PATH,
                    RestrictedPartnerOrderResource.class);
        }

        /**
         * Contributes {@link RestrictedPartnerOrderResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object restrictedPartnerOrderResource() {
            return new RestrictedPartnerOrderResource();
        }
    }

    /** Registers {@link OrdersApi}, active, without a contract of its own, and contributes {@link OrderResource}. */
    @Module
    public static final class Orders {

        private Orders() {}

        /**
         * Registers {@link OrdersApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(OrdersApi.class, OrdersApi.NAME, OrdersApi.PATH, "", OrderResource.class);
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
     * Registers {@link CatalogApi}, active, without a contract of its own, and contributes {@link
     * CatalogEntryResource}.
     */
    @Module
    public static final class Catalog {

        private Catalog() {}

        /**
         * Registers {@link CatalogApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(CatalogApi.class, CatalogApi.NAME, CatalogApi.PATH, "", CatalogEntryResource.class);
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

    /**
     * Registers {@link ReservedIdCatalogApi}, active, without a contract of its own, and
     * contributes {@link CatalogEntryResource} and {@link ReservedPartnerJsonResource}.
     */
    @Module
    public static final class ReservedIdCatalog {

        private ReservedIdCatalog() {}

        /**
         * Registers {@link ReservedIdCatalogApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(
                    ReservedIdCatalogApi.class,
                    CatalogApi.NAME,
                    CatalogApi.PATH,
                    "",
                    CatalogEntryResource.class,
                    ReservedPartnerJsonResource.class);
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

        /**
         * Contributes {@link ReservedPartnerJsonResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object reservedPartnerJsonResource() {
            return new ReservedPartnerJsonResource();
        }
    }

    /**
     * Registers {@link ThreeSegmentsCatalogApi}, active, without a contract of its own, and
     * contributes {@link CatalogEntryResource} and {@link ThreeSegmentsProbeResource}.
     */
    @Module
    public static final class ThreeSegmentsCatalog {

        private ThreeSegmentsCatalog() {}

        /**
         * Registers {@link ThreeSegmentsCatalogApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(
                    ThreeSegmentsCatalogApi.class,
                    CatalogApi.NAME,
                    CatalogApi.PATH,
                    "",
                    CatalogEntryResource.class,
                    ThreeSegmentsProbeResource.class);
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

        /**
         * Contributes {@link ThreeSegmentsProbeResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object threeSegmentsProbeResource() {
            return new ThreeSegmentsProbeResource();
        }
    }

    /**
     * Registers {@link GuardedManagementApi}, active, without a contract of its own, and
     * contributes {@link TwinResource}.
     */
    @Module
    public static final class Management {

        private Management() {}

        /**
         * Registers {@link GuardedManagementApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(
                    GuardedManagementApi.class,
                    GuardedManagementApi.NAME,
                    GuardedManagementApi.PATH,
                    "",
                    TwinResource.class);
        }

        /**
         * Contributes {@link TwinResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object twinResource() {
            return new TwinResource();
        }
    }

    /** Registers {@link AlphaApi}, active, without a contract of its own, and contributes {@link AlphaResource}. */
    @Module
    public static final class Alpha {

        private Alpha() {}

        /**
         * Registers {@link AlphaApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(AlphaApi.class, AlphaApi.NAME, AlphaApi.PATH, "", AlphaResource.class);
        }

        /**
         * Contributes {@link AlphaResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object alphaResource() {
            return new AlphaResource();
        }
    }

    /** Registers {@link BetaApi}, active, without a contract of its own, and contributes {@link BetaResource}. */
    @Module
    public static final class Beta {

        private Beta() {}

        /**
         * Registers {@link BetaApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(BetaApi.class, BetaApi.NAME, BetaApi.PATH, "", BetaResource.class);
        }

        /**
         * Contributes {@link BetaResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object betaResource() {
            return new BetaResource();
        }
    }

    /**
     * Registers {@link AlphaCatalogApi}, active, without a contract of its own, and contributes
     * {@link AlphaCatalogResource}.
     */
    @Module
    public static final class AlphaCatalog {

        private AlphaCatalog() {}

        /**
         * Registers {@link AlphaCatalogApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(AlphaCatalogApi.class, AlphaApi.NAME, AlphaApi.PATH, "", AlphaCatalogResource.class);
        }

        /**
         * Contributes {@link AlphaCatalogResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object alphaCatalogResource() {
            return new AlphaCatalogResource();
        }
    }

    /**
     * Registers {@link BetaAdminApi}, active, without a contract of its own, and contributes {@link
     * BetaAdminUsersResource}.
     */
    @Module
    public static final class BetaAdmin {

        private BetaAdmin() {}

        /**
         * Registers {@link BetaAdminApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(BetaAdminApi.class, BetaApi.NAME, BetaApi.PATH, "", BetaAdminUsersResource.class);
        }

        /**
         * Contributes {@link BetaAdminUsersResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object betaAdminUsersResource() {
            return new BetaAdminUsersResource();
        }
    }
}
