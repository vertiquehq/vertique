// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it.apps.app;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.jaxrs.RestModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * T003 TP-005 component: lists only {@link RestModule}, the {@code resources} unit's real, processor-
 * generated {@code dev.vertique.it.apps.resources.GeneratedJaxRsResourcesModule}, this unit's own
 * real, processor-generated {@code dev.vertique.it.apps.app.GeneratedJaxRsResourcesModule} (which
 * carries {@link PublicApplication}'s and {@link ManagementApplication}'s registrations), and
 * {@link AppSupportModule} — no handwritten registration or mount module.
 */
@Singleton
@Component(
        modules = {
            RestModule.class,
            AppSupportModule.class,
            dev.vertique.it.apps.resources.GeneratedJaxRsResourcesModule.class,
            GeneratedJaxRsResourcesModule.class
        })
public interface AppComponent {

    /**
     * Creates a new {@link HttpVerticle}. Unscoped: every call composes {@code Set<RouterMount>}
     * again, matching one composition per verticle instance.
     *
     * @return a new verticle instance
     */
    HttpVerticle httpVerticle();

    /** Factory taking the application configuration. */
    @Component.Factory
    interface Factory {

        /**
         * Creates the component bound to the given configuration.
         *
         * @param config the application configuration
         * @return the constructed component
         */
        AppComponent create(@BindsInstance @VertxConfig JsonObject config);
    }
}
