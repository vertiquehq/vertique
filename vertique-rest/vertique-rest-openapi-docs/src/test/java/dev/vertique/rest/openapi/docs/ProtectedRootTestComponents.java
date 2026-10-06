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
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.root.DecisionRecorder;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.root.RootApplicationModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger components of the protected root-document integration test. Each holds the root
 * application {@code internal} at {@code /} as its sole declaration, with discovery membership, and
 * {@code GET /status} as its only resource. They differ only in the declaration's {@code @ApiDocs}:
 * {@link RoleRestrictedComponent} lists the {@code admin} role, {@link AuthenticatedOnlyComponent}
 * lists none.
 *
 * <p>Both compose the real JWT authentication module (scheme {@code bearerAuth}), which brings the
 * framework's authentication and security modules and with them the real security policy validator,
 * the authentication enforcement marker, and the security event observer set, so neither lists the
 * test-support module. Both take the Vert.x instance, which the JWT provider needs, and the
 * application configuration through their factories.
 */
final class ProtectedRootTestComponents {

    private ProtectedRootTestComponents() {}

    /** What every component exposes. */
    interface Served {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();

        /**
         * Returns the component's authorization decision recorder.
         *
         * @return the recorder
         */
        DecisionRecorder decisionRecorder();
    }

    /** The root application whose protected document is readable only with the {@code admin} role. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                JwtAuthModule.class,
                RootApplicationModule.class,
                RootApplicationModule.RoleRestricted.class
            })
    interface RoleRestrictedComponent extends Served {

        /** Factory taking the Vert.x instance and the configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component.
             *
             * @param vertx  the Vert.x instance
             * @param config the application configuration
             * @return the component
             */
            RoleRestrictedComponent create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }

    /** The root application whose protected document lists no role. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                JwtAuthModule.class,
                RootApplicationModule.class,
                RootApplicationModule.AuthenticatedOnly.class
            })
    interface AuthenticatedOnlyComponent extends Served {

        /** Factory taking the Vert.x instance and the configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component.
             *
             * @param vertx  the Vert.x instance
             * @param config the application configuration
             * @return the component
             */
            AuthenticatedOnlyComponent create(
                    @BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }
}
