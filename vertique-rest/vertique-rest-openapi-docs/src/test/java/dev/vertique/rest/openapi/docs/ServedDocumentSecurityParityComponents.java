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
import dev.vertique.rest.openapi.docs.fixture.security.parity.ParityModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger component of the served-document security parity test: the application {@code parity}
 * beside the real JWT authentication module, which brings the framework's authentication and
 * security modules and with them the real security policy validator and the authentication
 * enforcement marker.
 */
final class ServedDocumentSecurityParityComponents {

    private ServedDocumentSecurityParityComponents() {}

    /** The application {@code parity} beside the JWT handler of {@code bearerAuth}. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                JwtAuthModule.class,
                ParityModule.class
            })
    interface ParityComponent {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();

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
            ParityComponent create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }
}
