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
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.Observations;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.typed.TypedDocumentModule;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.typed.TypedDocumentObservations;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.typed.TypedSingleDocumentModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger components of the typed document policy tests. Both compose the real JWT authentication
 * module (scheme {@code bearerAuth}), which brings the framework's authentication and security
 * modules and with them the real security policy validator, identity resolution, and the
 * authorization contributors; neither binds the authentication enforcement marker by hand.
 */
final class TypedDocumentTestComponents {

    private TypedDocumentTestComponents() {}

    /**
     * Every application of the typed document fixture, with the application's action registry and
     * counting authorizer, a late contributor, and a later plain mount at {@code /apidocs/*}.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                JwtAuthModule.class,
                TypedDocumentModule.class
            })
    interface AllPolicies {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();

        /**
         * Returns what the fixture's authorizer and late contributor observed.
         *
         * @return the observations
         */
        TypedDocumentObservations observations();

        /**
         * Returns the counters of the later plain mount.
         *
         * @return the observation hub
         */
        Observations mountObservations();

        /** Factory taking the Vert.x instance, which the JWT provider needs, and the configuration. */
        @Component.Factory
        interface ComponentFactory {

            /**
             * Creates the component.
             *
             * @param vertx  the Vert.x instance
             * @param config the application configuration
             * @return the component
             */
            AllPolicies create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * One application {@code management} at {@code /api/mgmt}, declared by the interface the factory
     * receives, beside the real authentication modules and no action registry or authorizer.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                JwtAuthModule.class,
                TypedSingleDocumentModule.class
            })
    interface SingleDocument {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();

        /** Factory taking the Vert.x instance, the configuration, and the declaration. */
        @Component.Factory
        interface ComponentFactory {

            /**
             * Creates the component.
             *
             * @param vertx       the Vert.x instance
             * @param config      the application configuration
             * @param declaration the declaring interface of the one application
             * @return the component
             */
            SingleDocument create(
                    @BindsInstance Vertx vertx,
                    @BindsInstance @VertxConfig JsonObject config,
                    @BindsInstance TypedSingleDocumentModule.Declaration declaration);
        }
    }
}
