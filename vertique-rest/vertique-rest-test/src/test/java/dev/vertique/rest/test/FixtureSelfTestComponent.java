// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.test;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * The fixture's own self-test graph: a consumer-shaped Dagger component over
 * {@link RestTestFixtureModule}.
 *
 * <p>This is deliberately written the way a consuming Maven module writes its own component (see the
 * {@code RestTestFixtureModule} javadoc), so the module's published shape is exercised exactly as an
 * application test harness would exercise it. It exposes the collaborators
 * {@code FixtureGraphTest} asserts on rather than reaching into
 * {@link JaxRsRouterMount.Factory} reflectively.
 *
 * <p>No validation-strategy module is included, so this graph carries only the {@code none} strategy
 * contributed by {@code RestModule} — which is what makes the {@code jaxrs.validationStrategy}
 * trap observable here.
 */
@Singleton
@Component(modules = RestTestFixtureModule.class)
interface FixtureSelfTestComponent {

    /**
     * Returns the real JAX-RS router mount factory assembled by the framework graph.
     *
     * <p>Named {@code mountFactory} rather than {@code factory}: a component that declares a
     * {@link Component.Factory} gets a generated static {@code factory()} on {@code Dagger…}, and
     * Dagger rejects a component method of the same name.
     *
     * @return the mount factory
     */
    JaxRsRouterMount.Factory mountFactory();

    /**
     * Returns the opaque mount handle {@link RestTestMounts} consumes — the factory above paired with
     * the graph's complete {@code Set<Middleware>}. This is the accessor a real consumer declares;
     * {@link #mountFactory()} is retained alongside it only because this module's own graph tests
     * assert at the factory level.
     *
     * @return the mount handle
     */
    RestTestMount testMount();

    /**
     * Returns the response body encoders in the order {@code RestModule.sortedResponseBodyEncoders}
     * produced — the same list instance the factory and the response serializer receive.
     *
     * @return the sorted encoder list
     */
    List<ResponseBodyEncoder> sortedResponseBodyEncoders();

    /**
     * Returns the request body decoders in the order {@code RestModule.sortedRequestBodyDecoders}
     * produced.
     *
     * @return the sorted decoder list
     */
    List<RequestBodyDecoder> sortedRequestBodyDecoders();

    /**
     * Returns the context-resolution coordinator built from the framework's package-private
     * resolvers in {@code vertique-rest-core}.
     *
     * @return the context resolution coordinator
     */
    RestContextResolution restContextResolution();

    /** Factory binding the three instances a consumer supplies to the fixture graph. */
    @Component.Factory
    interface Factory {

        /**
         * Creates the self-test graph.
         *
         * @param vertx         the Vert.x instance
         * @param config        the application configuration, exactly as {@code VertxModule} would
         *                      supply it in production
         * @param contributions the additive test contributions
         * @return the assembled component
         */
        FixtureSelfTestComponent create(
                @BindsInstance Vertx vertx,
                @BindsInstance @VertxConfig JsonObject config,
                @BindsInstance RestTestContributions contributions);
    }
}
