// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import dev.vertique.rest.test.RestTestContributions;
import dev.vertique.rest.test.RestTestFixtureModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Test graph over {@link RestTestFixtureModule} that additionally includes {@link RestValidationModule},
 * so a mount built from it carries the real injected {@link WebValidationStrategy} and
 * {@link AnnotationSchemaSource} rather than a hand-rolled stand-in.
 *
 * <p>Declared per {@code vertique-rest-test}'s consumer-component idiom (see the
 * {@link RestTestFixtureModule} javadoc): one package-private test {@code @Component} per consuming
 * Maven module, including any strategy module the module needs alongside the fixture module. Consumed
 * through {@link MountFixtures} rather than directly by individual integration tests.
 */
@Singleton
@Component(modules = {RestTestFixtureModule.class, RestValidationModule.class})
interface ValidationMountComponent {

    /**
     * Returns the real JAX-RS router mount factory assembled by the framework graph, wired with the
     * {@code web-validation} request-validation strategy.
     *
     * <p>Named {@code mountFactory} rather than {@code factory}: a component that declares a
     * {@link Component.Factory} gets a generated static {@code factory()} on its {@code Dagger…} class,
     * and Dagger rejects a component method that collides with it.
     *
     * @return the mount factory
     */
    JaxRsRouterMount.Factory mountFactory();

    /** Factory binding the three instances a consumer supplies to the graph. */
    @Component.Factory
    interface Factory {

        /**
         * Creates the validation mount graph.
         *
         * @param vertx         the Vert.x instance
         * @param config        the application configuration, exactly as {@code VertxModule} would
         *                      supply it in production
         * @param contributions the additive test contributions
         * @return the assembled component
         */
        ValidationMountComponent create(
                @BindsInstance Vertx vertx,
                @BindsInstance @VertxConfig JsonObject config,
                @BindsInstance RestTestContributions contributions);
    }
}
