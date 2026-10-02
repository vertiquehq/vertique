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
import dev.vertique.rest.openapi.docs.fixture.MarkerRouterMount;
import dev.vertique.rest.openapi.docs.fixture.conformance.shared.CountingCanonicalSchemaSource;
import dev.vertique.rest.openapi.docs.fixture.conformance.shared.OrderBodySwitchingSchemaSource;
import dev.vertique.rest.openapi.docs.fixture.conformance.shared.SharedApplicationsModule;
import dev.vertique.rest.openapi.docs.fixture.conformance.shared.SharedSchemaSourceModules;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger components of the multi-instance document integration test.
 *
 * <p>Each composes the framework's REST, JWT authentication (scheme {@code bearerAuth}), and
 * documentation modules with the public and management applications of {@link
 * SharedApplicationsModule} and one {@code web-validation} wiring of {@link
 * SharedSchemaSourceModules}, which decorates the canonical schema source. The counting and
 * first-variant graphs also place a {@link MarkerRouterMount} after every other mount, so a response
 * shows whether the documentation mount answered it. Each instance owns its own document store, so
 * the test builds one instance per scenario.
 */
final class OpenApiDocsMultiInstanceTestComponents {

    private OpenApiDocsMultiInstanceTestComponents() {}

    /** The graph whose schema source counts its calls and returns the canonical result unchanged. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                JwtAuthModule.class,
                SharedApplicationsModule.class,
                SharedSchemaSourceModules.Counting.class,
                MarkerRouterMount.Last.class
            })
    interface CountingSourceComponent {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();

        /**
         * Returns the component's counting source.
         *
         * @return the source
         */
        CountingCanonicalSchemaSource countingSource();

        /** Factory taking the Vert.x instance, which the JWT provider needs, and the configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component.
             *
             * @param vertx  the Vert.x instance
             * @param config the application configuration
             * @return the component
             */
            CountingSourceComponent create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * The graph whose schema source describes the order body's first variant on its first
     * resolution and the second variant on every later one.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                JwtAuthModule.class,
                SharedApplicationsModule.class,
                SharedSchemaSourceModules.SwitchingFromFirstVariant.class,
                MarkerRouterMount.Last.class
            })
    interface FirstVariantComponent {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();

        /**
         * Returns the component's switching source.
         *
         * @return the source
         */
        OrderBodySwitchingSchemaSource switchingSource();

        /**
         * Returns the component's document store.
         *
         * @return the store
         */
        DocumentStore documentStore();

        /** Factory taking the Vert.x instance, which the JWT provider needs, and the configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component.
             *
             * @param vertx  the Vert.x instance
             * @param config the application configuration
             * @return the component
             */
            FirstVariantComponent create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }

    /** The graph whose schema source describes the order body's second variant from the start. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                JwtAuthModule.class,
                SharedApplicationsModule.class,
                SharedSchemaSourceModules.SwitchingFromSecondVariant.class
            })
    interface SecondVariantComponent {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();

        /**
         * Returns the component's switching source.
         *
         * @return the source
         */
        OrderBodySwitchingSchemaSource switchingSource();

        /** Factory taking the Vert.x instance, which the JWT provider needs, and the configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component.
             *
             * @param vertx  the Vert.x instance
             * @param config the application configuration
             * @return the component
             */
            SecondVariantComponent create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }
}
