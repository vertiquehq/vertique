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
import dev.vertique.rest.openapi.docs.fixture.conformance.served.ConformanceCatalogApi;
import dev.vertique.rest.openapi.docs.fixture.conformance.served.ConformanceOrdersApi;
import dev.vertique.rest.openapi.docs.fixture.conformance.served.ConformancePartnerApi;
import dev.vertique.rest.openapi.docs.fixture.conformance.served.ConformanceServedApplications;
import dev.vertique.rest.openapi.docs.fixture.disclosure.sources.DisclosureSourceModules;
import dev.vertique.rest.openapi.validation.OpenApiContractValidationModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger test components of the served-contract conformance proof.
 *
 * <p>Both list {@code RestModule}, {@link OpenApiDocsModule}, the canonical {@link
 * ConfigParsingModule}, {@link DocsTestSupportModule}, and {@link DisclosureSourceModules.Canonical}
 * (the {@code web-validation} strategy over the canonical schema source); the contract component adds
 * {@link OpenApiContractValidationModule}, which loads contracts through the bound {@link Vertx}. The
 * configuration selects the strategy. Each component instance owns one document store, so deploying
 * its {@link HttpVerticle} supplier twice reuses that store.
 */
public final class ServedConformanceTestComponents {

    private ServedConformanceTestComponents() {}

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
         * @param vertx  the Vert.x instance
         * @param config the application configuration
         * @return the component
         */
        C create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
    }

    /**
     * {@code partner} ({@link ConformancePartnerApi}, its own contract by annotation) and {@code
     * catalog} ({@link ConformanceCatalogApi}, generated), for the {@code web-validation} strategy.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                ConformanceServedApplications.Partner.class,
                ConformanceServedApplications.Catalog.class
            })
    public interface WebValidationComponent extends Provisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<WebValidationComponent> {}
    }

    /**
     * {@code partner} ({@link ConformancePartnerApi}, its own contract by annotation) and {@code
     * orders} ({@link ConformanceOrdersApi}, its own contract by configuration), with the {@code
     * openapi-contract} strategy available.
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
                ConformanceServedApplications.Partner.class,
                ConformanceServedApplications.Orders.class
            })
    public interface OpenApiContractComponent extends Provisions {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<OpenApiContractComponent> {}
    }
}
