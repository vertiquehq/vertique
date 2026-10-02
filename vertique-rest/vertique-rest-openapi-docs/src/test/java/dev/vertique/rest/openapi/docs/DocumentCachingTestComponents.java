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
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.caching.CachingModules;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger components of the document caching integration test, one per fixture graph.
 *
 * <p>Both compose the real JWT authentication module, which brings the framework's authentication
 * and security modules and with them the real security policy validator, identity resolution, the
 * authorization contributor, and the authentication enforcement marker, so neither lists the
 * unsecured validator stand-in. Both take the Vert.x instance, which the JWT provider needs, and the
 * application configuration through their factories.
 */
final class DocumentCachingTestComponents {

    private DocumentCachingTestComponents() {}

    /** What every component exposes. */
    interface Served {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();
    }

    /** Creates a component from the Vert.x instance and the application configuration. */
    interface Factory<C> {

        /**
         * Creates the component.
         *
         * @param vertx  the Vert.x instance
         * @param config the application configuration
         * @return the component
         */
        C create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
    }

    /**
     * The public application {@code public} beside the application {@code management}, whose
     * document is protected by the JWT bearer scheme and a role.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                JwtAuthModule.class,
                CachingModules.PublicAndManagement.class
            })
    interface PublicAndManagementComponent extends Served {

        /** Factory taking the Vert.x instance and the configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<PublicAndManagementComponent> {}
    }

    /** One application per scheme kind, each with a protected document guarded by that kind. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                JwtAuthModule.class,
                CachingModules.SchemeKinds.class
            })
    interface SchemeKindsComponent extends Served {

        /** Factory taking the Vert.x instance and the configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<SchemeKindsComponent> {}
    }
}
