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
import dev.vertique.rest.openapi.docs.fixture.conformance.bound.BoundApplicationModule;
import dev.vertique.rest.openapi.docs.fixture.disclosure.sources.DisclosureSourceModules;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger component of the pattern-bound accounting integration test: the application {@code bound}
 * under the {@code web-validation} strategy over the canonical schema source, with default limits and
 * no documentation module, so no document is published. The configuration must select {@code
 * web-validation}.
 */
public final class PatternBoundTestComponents {

    private PatternBoundTestComponents() {}

    /** The component. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                BoundApplicationModule.class
            })
    public interface BoundComponent {

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
             * @param vertx the Vert.x instance
             * @param config the application configuration
             * @return the component
             */
            BoundComponent create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }
}
