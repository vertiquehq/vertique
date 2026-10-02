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
import dev.vertique.rest.openapi.docs.fixture.conformance.complete.CompleteModules;
import dev.vertique.rest.openapi.docs.fixture.disclosure.sources.DisclosureSourceModules;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger components of the complete-document parity integration test, one per twin.
 *
 * <p>Each is built from {@code RestModule}, {@link OpenApiDocsModule}, the canonical {@link
 * ConfigParsingModule}, {@link DocsTestSupportModule}, {@link DisclosureSourceModules.Canonical} (the
 * {@code web-validation} strategy, the canonical schema source unwrapped, and an accepting {@code
 * BeanValidator}), and one module registering its declared application, which also binds the shared
 * scheme handlers, the auth enforcement marker, and the response producer bindings. Each takes the
 * Vert.x instance and the application configuration through its factory.
 *
 * <p>The two twins share their operation ids, which one composition refuses across its mounts, so
 * each has a component of its own.
 */
final class CompleteDocumentTestComponents {

    private CompleteDocumentTestComponents() {}

    /** What every component exposes. */
    interface Served {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();
    }

    /** The application {@code ref}: the twin the reflective scanner describes. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                CompleteModules.Ref.class
            })
    interface RefComponent extends Served {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component.
             *
             * @param vertx  the Vert.x instance
             * @param config the application configuration
             * @return the component
             */
            RefComponent create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }

    /** The application {@code gen}: the twin its generated-shape companion describes. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                CompleteModules.Gen.class
            })
    interface GenComponent extends Served {

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component.
             *
             * @param vertx  the Vert.x instance
             * @param config the application configuration
             * @return the component
             */
            GenComponent create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }
}
