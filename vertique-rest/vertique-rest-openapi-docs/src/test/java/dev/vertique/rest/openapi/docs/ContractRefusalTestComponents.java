// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.openapi.docs.fixture.DocsTestSupportModule;
import dev.vertique.rest.openapi.docs.fixture.SchemaSourceModules;
import dev.vertique.rest.openapi.docs.fixture.SharedRegistrationModule;
import dev.vertique.rest.openapi.docs.fixture.SharedResourcesModule;
import dev.vertique.rest.openapi.docs.fixture.startup.StartupRegistrations;
import dev.vertique.rest.openapi.docs.fixture.startup.contract.ContractRegistrations;
import dev.vertique.rest.openapi.docs.fixture.startup.contract.TestValidationStrategies;
import dev.vertique.rest.openapi.validation.OpenApiContractValidationModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger test components of {@code OpenApiContractRefusalIT}: compositions with the
 * {@code openapi-contract} request-validation strategy available. The strategy loads contracts
 * through Vert.x, so every factory takes the test's {@link Vertx} instance besides the application
 * configuration.
 *
 * <p>Every component is built from {@code RestModule}, {@link OpenApiContractValidationModule}, the
 * canonical {@link ConfigParsingModule}, {@link DocsTestSupportModule}, and the deterministic counting
 * schema source, and, unless its name says otherwise, lists {@link OpenApiDocsModule}. No component
 * places a marker mount after the others, so a request no mount answers gets the router's own
 * {@code 404}.
 */
public final class ContractRefusalTestComponents {

    private ContractRefusalTestComponents() {}

    /** What every component exposes. */
    public interface Provisions {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();
    }

    /** What every component that lists {@link OpenApiDocsModule} exposes besides {@link Provisions}. */
    public interface DocsProvisions extends Provisions {

        /**
         * Resolves the component's document store.
         *
         * @return the store
         */
        DocumentStore documentStore();
    }

    /** Creates a component from the Vert.x instance and the application configuration. */
    public interface Factory<C> {

        /**
         * Creates the component.
         *
         * @param vertx  the Vert.x instance the contract strategy loads contracts with
         * @param config the application configuration
         * @return the component
         */
        C create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
    }

    /** The shared declarations ({@code PublicApi} documented, {@code MgmtApi} undocumented) and their resources. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiContractValidationModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                SharedRegistrationModule.class,
                SharedResourcesModule.class,
                SchemaSourceModules.Counting.class
            })
    public interface SharedContractComponent extends DocsProvisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<SharedContractComponent> {}
    }

    /** {@code EmptyApi} as the sole registration, with no resource contributed: an empty mount. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiContractValidationModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                StartupRegistrations.Empty.class,
                SchemaSourceModules.Counting.class
            })
    public interface EmptyContractComponent extends DocsProvisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<EmptyContractComponent> {}
    }

    /** {@link SharedContractComponent} plus a custom strategy that does not resolve operations from a contract. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiContractValidationModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                SharedRegistrationModule.class,
                SharedResourcesModule.class,
                SchemaSourceModules.Counting.class,
                TestValidationStrategies.DocsTest.class
            })
    public interface DocsTestStrategyComponent extends DocsProvisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<DocsTestStrategyComponent> {}
    }

    /** {@link SharedContractComponent} plus a custom strategy that resolves operations from the mount's contract. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiContractValidationModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                SharedRegistrationModule.class,
                SharedResourcesModule.class,
                SchemaSourceModules.Counting.class,
                TestValidationStrategies.ContractTest.class
            })
    public interface ContractTestStrategyComponent extends DocsProvisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ContractTestStrategyComponent> {}
    }

    /** The shared fixture with {@code OwnContractPublicApi}, which declares its own contract, in place of {@code PublicApi}. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiContractValidationModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                ContractRegistrations.class,
                SharedResourcesModule.class,
                SchemaSourceModules.Counting.class
            })
    public interface OwnContractComponent extends DocsProvisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<OwnContractComponent> {}
    }

    /**
     * The same composition as {@link SharedContractComponent}, named for the build whose
     * configuration switches the {@code public} document off.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiContractValidationModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                SharedRegistrationModule.class,
                SharedResourcesModule.class,
                SchemaSourceModules.Counting.class
            })
    public interface DocumentOffContractComponent extends DocsProvisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<DocumentOffContractComponent> {}
    }

    /**
     * The same composition as {@link SharedContractComponent}, named for the build whose
     * configuration switches {@code apidocs} off.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiContractValidationModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                SharedRegistrationModule.class,
                SharedResourcesModule.class,
                SchemaSourceModules.Counting.class
            })
    public interface ApidocsOffContractComponent extends DocsProvisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ApidocsOffContractComponent> {}
    }

    /**
     * {@link SharedContractComponent} without {@link OpenApiDocsModule}; the module's artifact stays
     * on the classpath, so {@code @ApiDocs} is still read at runtime.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiContractValidationModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                SharedRegistrationModule.class,
                SharedResourcesModule.class,
                SchemaSourceModules.Counting.class
            })
    public interface WithoutDocsModuleContractComponent extends Provisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<WithoutDocsModuleContractComponent> {}
    }
}
