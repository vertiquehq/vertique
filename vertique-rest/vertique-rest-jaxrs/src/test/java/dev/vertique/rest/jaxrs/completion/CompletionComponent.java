// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.completion;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.jaxrs.RestModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger test component of the synthetic-operation completion proof, built over
 * {@code RestModule} plus {@link CompletionFixtureModule}, in a package outside
 * {@code dev.vertique.rest.jaxrs} so it resolves the synthetic-operation installer the way a sibling
 * module's component does. {@code RestModule} includes the core REST module, so the verticle carries
 * the production ROOT middlewares, the completion emitter among them.
 */
@Singleton
@Component(modules = {RestModule.class, CompletionFixtureModule.class})
public interface CompletionComponent {

    /**
     * Creates the deployable {@link HttpVerticle} composing the fixture's mount.
     *
     * @return a new verticle instance
     */
    HttpVerticle httpVerticle();

    /**
     * Returns the shared record of registered descriptors and completion events.
     *
     * @return the completion records
     */
    CompletionRecords completionRecords();

    /** Factory taking the application configuration. */
    @Component.Factory
    interface Factory {

        /**
         * Creates the component bound to the given configuration.
         *
         * @param config the application configuration
         * @return the constructed component
         */
        CompletionComponent create(@BindsInstance @VertxConfig JsonObject config);
    }
}
