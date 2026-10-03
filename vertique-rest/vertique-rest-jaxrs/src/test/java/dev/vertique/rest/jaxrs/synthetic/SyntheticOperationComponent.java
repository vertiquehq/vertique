// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.synthetic;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.jaxrs.RestModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The {@link SyntheticOperationIT} Dagger test component, built over {@code RestModule} plus
 * {@link SyntheticFixtureModule}, deliberately in a package outside {@code dev.vertique.rest.jaxrs}
 * so resolving the installer's {@code @Binds} exercises the same cross-package path the OpenAPI
 * documentation module's own component will use.
 */
@Singleton
@Component(modules = {RestModule.class, SyntheticFixtureModule.class})
public interface SyntheticOperationComponent {

    /**
     * Creates the deployable {@link HttpVerticle} composing every mount this fixture contributes.
     *
     * @return a new verticle instance
     */
    HttpVerticle httpVerticle();

    /**
     * Returns the shared per-request trace recorder.
     *
     * @return the trace recorder
     */
    TraceRecorder traceRecorder();

    /**
     * Returns the shared counting error interceptor.
     *
     * @return the error interceptor
     */
    CountingErrorInterceptor countingErrorInterceptor();

    /**
     * Returns the shared later-{@code /apidocs/*} resource, exposing its invocation counters.
     *
     * @return the later mount's resource
     */
    LaterApidocsResource laterApidocsResource();

    /**
     * Returns the shared catch-all mount, exposing its failure counter.
     *
     * @return the catch-all mount
     */
    CatchAllFailingMount catchAllFailingMount();

    /**
     * Returns the shared authorization contributor, exposing the policy it received per operation.
     *
     * @return the authz100 contributor
     */
    Authz100Contributor authz100Contributor();

    /** Factory taking the application configuration. */
    @Component.Factory
    interface Factory {

        /**
         * Creates the component bound to the given configuration.
         *
         * @param config the application configuration
         * @return the constructed component
         */
        SyntheticOperationComponent create(@BindsInstance @VertxConfig JsonObject config);
    }
}
