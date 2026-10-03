// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.auth.jwt.JwtAuthModule;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.core.router.MountCompositionValidator;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.Observations;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.SharedDeploymentModule;
import dev.vertique.rest.validation.RestValidationModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * The Dagger component of the protected document integration test.
 *
 * <p>{@link SharedComponent} composes the framework's REST, request-validation, JWT authentication
 * (scheme {@code bearerAuth}), and documentation modules with the fixtures of {@link
 * SharedDeploymentModule}. Each instance owns its own document store and its own {@link
 * Observations}; the test builds one instance per deployment, and one more instance whose JAX-RS
 * mounts are never built, so its store never holds a document.
 */
final class ProtectedDocumentTestComponents {

    private ProtectedDocumentTestComponents() {}

    /** The shared deployment's graph. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                RestValidationModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                JwtAuthModule.class,
                SharedDeploymentModule.class
            })
    interface SharedComponent {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();

        /**
         * Resolves the component's router mounts. The documentation mount is unscoped, so each call
         * returns a fresh, unmarked documentation mount.
         *
         * @return the mounts
         */
        Set<RouterMount> routerMounts();

        /**
         * Returns the component's mount composition validators.
         *
         * @return the validators
         */
        Set<MountCompositionValidator> mountCompositionValidators();

        /**
         * Returns the component's middlewares, of every scope.
         *
         * @return the middlewares
         */
        Set<Middleware> middlewares();

        /**
         * Returns the component's observation hub.
         *
         * @return the hub
         */
        Observations observations();

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
            SharedComponent create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }
}
