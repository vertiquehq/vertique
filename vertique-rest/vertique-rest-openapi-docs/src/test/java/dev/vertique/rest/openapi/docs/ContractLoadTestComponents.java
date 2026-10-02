// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.openapi.docs.fixture.CatalogResourceModule;
import dev.vertique.rest.openapi.docs.fixture.DocsTestSupportModule;
import dev.vertique.rest.openapi.docs.fixture.SchemaSourceModules;
import dev.vertique.rest.openapi.docs.fixture.startup.StartupRegistrations;
import dev.vertique.rest.openapi.docs.fixture.startup.contractload.ContractLoadModules;
import dev.vertique.rest.openapi.validation.OpenApiContractValidationModule;
import dev.vertique.rest.validation.RestValidationModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger test components of {@code OpenApiContractLoadStartupIT}: compositions with the
 * {@code openapi-contract} request-validation strategy available and without the documentation module,
 * so a startup failure can only come from the composition itself or from the strategy's contract load.
 * The strategy loads contracts through Vert.x, so every factory takes the test's {@link Vertx} instance
 * besides the application configuration.
 *
 * <p>Every component is built from {@code RestModule}, {@link OpenApiContractValidationModule}, the
 * canonical {@link ConfigParsingModule}, {@link DocsTestSupportModule}, and the deterministic counting
 * schema source unless its documentation says otherwise, plus exactly one mount-contributing fixture,
 * so exactly one JAX-RS mount binds a contract.
 */
public final class ContractLoadTestComponents {

    private ContractLoadTestComponents() {}

    /** What every component exposes. */
    public interface Provisions {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();
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

    /**
     * {@code public} at {@code /api/public}, listing the catalog resource, as the sole application; it
     * lists {@link RestValidationModule} in place of the counting schema source, so the {@code
     * web-validation} strategy can be selected.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                RestValidationModule.class,
                OpenApiContractValidationModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                StartupRegistrations.PublicOnly.class,
                CatalogResourceModule.class
            })
    public interface PublicContractLoadComponent extends Provisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<PublicContractLoadComponent> {}
    }

    /** {@code annotated}, whose declaration names its contract location, as the sole application. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiContractValidationModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                ContractLoadModules.Annotated.class,
                CatalogResourceModule.class,
                SchemaSourceModules.Counting.class
            })
    public interface AnnotatedContractLoadComponent extends Provisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<AnnotatedContractLoadComponent> {}
    }

    /** A hand-built JAX-RS mount with a contract location, and no application. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiContractValidationModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                ContractLoadModules.HandBuilt.class,
                SchemaSourceModules.Counting.class
            })
    public interface HandBuiltContractLoadComponent extends Provisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<HandBuiltContractLoadComponent> {}
    }

    /** {@code EmptyApi} as the sole registration, with no resource contributed: an empty mount. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiContractValidationModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                StartupRegistrations.Empty.class,
                SchemaSourceModules.Counting.class
            })
    public interface EmptyContractLoadComponent extends Provisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<EmptyContractLoadComponent> {}
    }
}
