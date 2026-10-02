// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.auth.jwt.JwtAuthModule;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.openapi.docs.fixture.DocsTestSupportModule;
import dev.vertique.rest.openapi.docs.fixture.RecordingPublicationSink;
import dev.vertique.rest.openapi.docs.fixture.SchemaSourceModules;
import dev.vertique.rest.openapi.docs.fixture.contract.AlphaApi;
import dev.vertique.rest.openapi.docs.fixture.contract.AlphaCatalogApi;
import dev.vertique.rest.openapi.docs.fixture.contract.AnnotatedInfoPartnerApi;
import dev.vertique.rest.openapi.docs.fixture.contract.BetaAdminApi;
import dev.vertique.rest.openapi.docs.fixture.contract.BetaApi;
import dev.vertique.rest.openapi.docs.fixture.contract.CatalogApi;
import dev.vertique.rest.openapi.docs.fixture.contract.ContractApplications;
import dev.vertique.rest.openapi.docs.fixture.contract.ContractJwtModule;
import dev.vertique.rest.openapi.docs.fixture.contract.ContractMounts;
import dev.vertique.rest.openapi.docs.fixture.contract.OrdersApi;
import dev.vertique.rest.openapi.docs.fixture.contract.PartnerApi;
import dev.vertique.rest.openapi.docs.fixture.contract.ProtectedPartnerApi;
import dev.vertique.rest.openapi.docs.fixture.contract.ProtectedRestrictedPartnerApi;
import dev.vertique.rest.openapi.docs.fixture.contract.ReservedIdCatalogApi;
import dev.vertique.rest.openapi.docs.fixture.contract.RestrictedPartnerApi;
import dev.vertique.rest.openapi.docs.fixture.contract.ThreeSegmentsCatalogApi;
import dev.vertique.rest.openapi.docs.fixture.disclosure.sources.DisclosureSourceModules;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.GuardedManagementApi;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.Observations;
import dev.vertique.rest.openapi.docs.fixture.startup.contract.TestValidationStrategies;
import dev.vertique.rest.openapi.docs.fixture.startup.startupit.HandBuiltMounts;
import dev.vertique.rest.openapi.docs.publication.DocumentStore;
import dev.vertique.rest.openapi.validation.OpenApiContractValidationModule;
import dev.vertique.rest.validation.RestValidationModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger test components of the served-contract integration test. They expose the component's
 * {@link DocumentStore}, a public type of the module's internal {@code publication} package.
 *
 * <p>Three module sets are used, each copied from an existing proof:
 *
 * <ul>
 *   <li><em>web-validation</em> components list {@code RestModule}, {@link OpenApiDocsModule}, the
 *       canonical {@link ConfigParsingModule}, {@link DocsTestSupportModule}, and {@link
 *       DisclosureSourceModules.Canonical} (the {@code web-validation} strategy over the canonical
 *       schema source), as the hidden-operation proof does;
 *   <li><em>contract</em> components add {@link OpenApiContractValidationModule}, which loads contracts
 *       through the bound {@link Vertx};
 *   <li><em>JWT</em> components list {@code RestModule}, {@link RestValidationModule}, {@link
 *       OpenApiDocsModule}, {@link ConfigParsingModule}, {@link JwtAuthModule} (scheme {@code
 *       bearerAuth}), and {@link ContractJwtModule}, as the protected-document proof does; they omit
 *       {@link DocsTestSupportModule}, since the security module binds the policy validator.
 * </ul>
 *
 * <p>Every factory takes the test's {@link Vertx} instance and the application configuration (see
 * {@code fixture.contract.ContractConfigs}); a component that does not need the instance ignores it.
 * Each component instance owns one document store, so deploying its {@link HttpVerticle} supplier twice
 * reuses that store.
 */
public final class ServedContractTestComponents {

    private ServedContractTestComponents() {}

    /** What every component exposes. */
    public interface DocsProvisions {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();

        /**
         * Resolves the component's document store.
         *
         * @return the store
         */
        DocumentStore documentStore();
    }

    /** What the gated component exposes besides {@link DocsProvisions}. */
    public interface GatedProvisions extends DocsProvisions {

        /**
         * Resolves the component's recording sink, which counts {@code mountBuilt} calls per mount path.
         *
         * @return the sink
         */
        RecordingPublicationSink recordingSink();
    }

    /** What every JWT component exposes besides {@link DocsProvisions}. */
    public interface JwtProvisions extends DocsProvisions {

        /**
         * Resolves the component's observation hub, which holds each request's probe trace.
         *
         * @return the hub
         */
        Observations observations();
    }

    /** Creates a component from the Vert.x instance and the application configuration. */
    public interface Factory<C> {

        /**
         * Creates the component.
         *
         * @param vertx  the Vert.x instance
         * @param config the application configuration
         * @return the component
         */
        C create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
    }

    /**
     * The shared fixture under {@code web-validation}: {@code partner} ({@link PartnerApi}, its own
     * contract by annotation), {@code orders} ({@link OrdersApi}, its own contract by
     * configuration), and {@code catalog} ({@link CatalogApi}, generated).
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                ContractApplications.Partner.class,
                ContractApplications.Orders.class,
                ContractApplications.Catalog.class
            })
    public interface SharedComponent extends DocsProvisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<SharedComponent> {}
    }

    /**
     * {@link SharedComponent} with {@code partner} declared by {@link AnnotatedInfoPartnerApi},
     * which also carries an {@code @OpenAPIDefinition} with an {@code info}.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                ContractApplications.AnnotatedInfoPartner.class,
                ContractApplications.Orders.class,
                ContractApplications.Catalog.class
            })
    public interface AnnotatedInfoComponent extends DocsProvisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<AnnotatedInfoComponent> {}
    }

    /**
     * The shared declarations with the {@code openapi-contract} strategy available ({@link
     * OpenApiContractValidationModule}); the configuration selects it.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                OpenApiContractValidationModule.class,
                ContractApplications.Partner.class,
                ContractApplications.Orders.class,
                ContractApplications.Catalog.class
            })
    public interface SharedOpenApiContractComponent extends DocsProvisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<SharedOpenApiContractComponent> {}
    }

    /**
     * {@link SharedOpenApiContractComponent} plus the custom test strategy {@code
     * custom-contract-test}, which reports resolving operations from the mount's contract and
     * installs no gate; the configuration selects it.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                OpenApiContractValidationModule.class,
                TestValidationStrategies.ContractTest.class,
                ContractApplications.Partner.class,
                ContractApplications.Orders.class,
                ContractApplications.Catalog.class
            })
    public interface SharedCustomContractTestComponent extends DocsProvisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<SharedCustomContractTestComponent> {}
    }

    /**
     * {@code partner} ({@link PartnerApi}) as the only documented application, under {@code
     * web-validation}, with a {@link RecordingPublicationSink} beside the documentation sink.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                ContractApplications.Partner.class,
                RecordingPublicationSink.Binding.class
            })
    public interface GatedPartnerComponent extends GatedProvisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<GatedPartnerComponent> {}
    }

    /**
     * {@link GatedPartnerComponent} with the module set of the documentation module's own gated
     * multi-instance proof: the deterministic {@link SchemaSourceModules.Counting} source instead of the
     * {@code web-validation} wiring. The configuration must select the {@code none} strategy ({@code
     * ContractConfigs.partnerOnlyWithoutValidation()}).
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                SchemaSourceModules.Counting.class,
                ContractApplications.Partner.class,
                RecordingPublicationSink.Binding.class
            })
    public interface GatedPartnerCountingComponent extends GatedProvisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<GatedPartnerCountingComponent> {}
    }

    /**
     * {@code alpha} ({@link AlphaApi}, {@code GET /a}) and {@code beta} ({@link BetaApi}, {@code
     * GET /b}) under {@code web-validation}; their contract locations come from configuration.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                ContractApplications.Alpha.class,
                ContractApplications.Beta.class
            })
    public interface AlphaBetaComponent extends DocsProvisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<AlphaBetaComponent> {}
    }

    /**
     * {@link AlphaBetaComponent} with the {@code openapi-contract} strategy available; the
     * configuration selects it.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                OpenApiContractValidationModule.class,
                ContractApplications.Alpha.class,
                ContractApplications.Beta.class
            })
    public interface AlphaBetaOpenApiContractComponent extends DocsProvisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<AlphaBetaOpenApiContractComponent> {}
    }

    /**
     * {@code alpha} ({@link AlphaCatalogApi}, {@code GET /catalog}) and {@code beta} ({@link
     * BetaAdminApi}, {@code GET /admin/users}) under {@code web-validation}; their contract
     * locations come from configuration.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                ContractApplications.AlphaCatalog.class,
                ContractApplications.BetaAdmin.class
            })
    public interface AlphaCatalogBetaAdminComponent extends DocsProvisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<AlphaCatalogBetaAdminComponent> {}
    }

    /**
     * {@link SharedComponent} plus the bound {@link HandBuiltMounts}, hand-built JAX-RS mounts that
     * belong to no application (see {@link ContractMounts}).
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                ContractApplications.Partner.class,
                ContractApplications.Orders.class,
                ContractApplications.Catalog.class,
                HandBuiltMounts.Contribution.class
            })
    public interface HandBuiltMountComponent extends DocsProvisions {

        /** Factory taking the Vert.x instance, the application configuration, and the hand-built mounts. */
        @Component.Factory
        interface ComponentFactory {

            /**
             * Creates the component.
             *
             * @param vertx  the Vert.x instance
             * @param config the application configuration
             * @param mounts the hand-built JAX-RS mounts, such as {@link ContractMounts#extraDocs()}
             * @return the component
             */
            HandBuiltMountComponent create(
                    @BindsInstance Vertx vertx,
                    @BindsInstance @VertxConfig JsonObject config,
                    @BindsInstance HandBuiltMounts mounts);
        }
    }

    /**
     * {@link SharedComponent} with {@code catalog} declared by {@link ReservedIdCatalogApi}, which
     * also lists an operation whose id is {@code apidocs:partner:json}.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                ContractApplications.Partner.class,
                ContractApplications.Orders.class,
                ContractApplications.ReservedIdCatalog.class
            })
    public interface ReservedIdCatalogComponent extends DocsProvisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ReservedIdCatalogComponent> {}
    }

    /**
     * {@link SharedComponent} with {@code catalog} declared by {@link ThreeSegmentsCatalogApi},
     * which also lists {@code GET /{a}/{b}/{c}}.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                ContractApplications.Partner.class,
                ContractApplications.Orders.class,
                ContractApplications.ThreeSegmentsCatalog.class
            })
    public interface ThreeSegmentsCatalogComponent extends DocsProvisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ThreeSegmentsCatalogComponent> {}
    }

    /**
     * The JWT graph ({@link JwtAuthModule}, scheme {@code bearerAuth}, {@link ContractJwtModule})
     * under {@code web-validation}: {@code partner} with a protected document ({@link
     * ProtectedPartnerApi}, its own contract by annotation), the generated protected {@code
     * management} ({@link GuardedManagementApi}), and {@code orders} with a public document ({@link
     * OrdersApi}, its own contract by configuration).
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                RestValidationModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                JwtAuthModule.class,
                ContractJwtModule.class,
                ContractApplications.ProtectedPartner.class,
                ContractApplications.Management.class,
                ContractApplications.Orders.class
            })
    public interface ProtectedAccessComponent extends JwtProvisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ProtectedAccessComponent> {}
    }

    /**
     * The JWT graph with {@code partner} alone, declared by {@link RestrictedPartnerApi}: a public
     * document from its own contract, and one operation that restricts its callers.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                RestValidationModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                JwtAuthModule.class,
                ContractJwtModule.class,
                ContractApplications.RestrictedPartner.class
            })
    public interface RestrictedPublicComponent extends JwtProvisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<RestrictedPublicComponent> {}
    }

    /**
     * {@link RestrictedPublicComponent} with {@code partner} declared by {@link
     * ProtectedRestrictedPartnerApi}: the same routes and contract, a protected document.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                RestValidationModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                JwtAuthModule.class,
                ContractJwtModule.class,
                ContractApplications.ProtectedRestrictedPartner.class
            })
    public interface RestrictedProtectedComponent extends JwtProvisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<RestrictedProtectedComponent> {}
    }
}
