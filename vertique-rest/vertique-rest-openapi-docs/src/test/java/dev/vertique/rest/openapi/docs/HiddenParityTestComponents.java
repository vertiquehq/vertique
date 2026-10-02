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
import dev.vertique.rest.openapi.docs.fixture.conformance.hidden.HiddenParityModule;
import dev.vertique.rest.openapi.docs.fixture.disclosure.sources.DisclosureSourceModules;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger component of the hidden-operation reader parity integration test. It lists the module
 * set of the hidden-operation proof's served component ({@code RestModule}, {@link
 * OpenApiDocsModule}, the canonical {@link ConfigParsingModule}, {@link DocsTestSupportModule}, and
 * {@link DisclosureSourceModules.Canonical}) with {@link HiddenParityModule} as its only application,
 * and serves that application's public document over HTTP. The configuration must select {@code
 * web-validation}.
 */
final class HiddenParityTestComponents {

    private HiddenParityTestComponents() {}

    /** The application {@code hiddenparity}, serving its public document. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                HiddenParityModule.class
            })
    interface ServedComponent {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface ComponentFactory {

            /**
             * Creates the component.
             *
             * @param vertx  the Vert.x instance
             * @param config the application configuration
             * @return the component
             */
            ServedComponent create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }
}
