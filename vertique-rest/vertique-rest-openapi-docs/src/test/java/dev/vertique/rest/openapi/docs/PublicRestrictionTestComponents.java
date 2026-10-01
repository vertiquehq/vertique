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
import dev.vertique.rest.openapi.docs.fixture.security.catalog.CatalogEntries;
import dev.vertique.rest.openapi.docs.fixture.security.catalog.CatalogRegistrationModule;
import dev.vertique.rest.openapi.docs.fixture.security.catalog.CatalogSecurityModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger components of the public-document restriction warning proof. Both serve the sole
 * discovery application {@code catalog} with the same security runtime ({@link
 * CatalogSecurityModule}); they differ only in the resource catalog its mount receives.
 */
final class PublicRestrictionTestComponents {

    private PublicRestrictionTestComponents() {}

    /** What every component exposes. */
    interface Served {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();
    }

    /** Creates a component from the application configuration. */
    interface Factory<C> {

        /**
         * Creates the component.
         *
         * @param config the application configuration
         * @return the component
         */
        C create(@BindsInstance @VertxConfig JsonObject config);
    }

    /** The mixed catalog: one open resource beside three that restrict callers. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                CatalogRegistrationModule.class,
                CatalogEntries.Mixed.class,
                CatalogSecurityModule.class
            })
    interface MixedCatalogComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<MixedCatalogComponent> {}
    }

    /** The open catalog: only the resource open to every caller. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                CatalogRegistrationModule.class,
                CatalogEntries.OpenOnly.class,
                CatalogSecurityModule.class
            })
    interface OpenCatalogComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<OpenCatalogComponent> {}
    }
}
