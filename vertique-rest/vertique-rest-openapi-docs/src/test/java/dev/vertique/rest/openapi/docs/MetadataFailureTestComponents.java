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
import dev.vertique.rest.openapi.docs.fixture.disclosure.sources.DisclosureSourceModules;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.failure.FailureApplicationModules;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger components of the metadata failure integration tests. Every component lists {@code
 * RestModule}, {@link OpenApiDocsModule}, the canonical {@link ConfigParsingModule}, {@link
 * DocsTestSupportModule}, the canonical schema source, and one application module, and takes the
 * application configuration through its factory.
 */
public final class MetadataFailureTestComponents {

    private MetadataFailureTestComponents() {}

    /** What every component exposes. */
    public interface Served {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();
    }

    /** Creates a component from the application configuration. */
    public interface Factory<C> {

        /**
         * Creates the component.
         *
         * @param config the application configuration
         * @return the component
         */
        C create(@BindsInstance @VertxConfig JsonObject config);
    }

    /** The application {@code search}, whose query parameter declares a location. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                FailureApplicationModules.Search.class
            })
    public interface SearchComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<SearchComponent> {}
    }

    /** The application {@code examples}, whose query parameter carries a referring example. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                FailureApplicationModules.Examples.class
            })
    public interface ExamplesComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ExamplesComponent> {}
    }
}
