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
import dev.vertique.rest.openapi.docs.fixture.DocsTestSupportModule;
import dev.vertique.rest.openapi.docs.fixture.security.orders.OrdersModule;
import dev.vertique.rest.openapi.docs.fixture.security.vault.VaultModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger components of the document security integration test, one per deployment.
 *
 * <p>{@link OrdersComponent} composes the real JWT authentication module, which brings the
 * framework's authentication and security modules and with them the real security policy validator
 * and the authentication enforcement marker, so it lists no {@link DocsTestSupportModule}. {@link
 * VaultComponent} lists {@link DocsTestSupportModule} and binds the marker in {@link VaultModule}.
 * Both use the {@code none} validation strategy and take the application configuration through their
 * factories.
 */
final class DocumentSecurityTestComponents {

    private DocumentSecurityTestComponents() {}

    /** What every component exposes. */
    interface Served {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();
    }

    /**
     * The application {@code orders} beside the JWT handler of {@code bearerAuth} and the fixture
     * handlers of {@code OrderSchemeHandlers}.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                JwtAuthModule.class,
                OrdersModule.class
            })
    interface OrdersComponent extends Served {

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
            OrdersComponent create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }

    /** The application {@code vault} beside its counting, undescribed scheme handler. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                VaultModule.class
            })
    interface VaultComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component.
             *
             * @param config the application configuration
             * @return the component
             */
            VaultComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }
}
