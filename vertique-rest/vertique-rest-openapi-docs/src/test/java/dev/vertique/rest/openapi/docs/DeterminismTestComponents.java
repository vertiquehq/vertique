// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.rest.core.response.ResponseProducerBinding;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.openapi.docs.fixture.DocsTestSupportModule;
import dev.vertique.rest.openapi.docs.fixture.conformance.complete.CompleteModules;
import dev.vertique.rest.openapi.docs.fixture.conformance.determinism.DetailCapturingSink;
import dev.vertique.rest.openapi.docs.fixture.disclosure.sources.DisclosureSourceModules;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.Set;

/** The Dagger component of the document determinism test. */
final class DeterminismTestComponents {

    private DeterminismTestComponents() {}

    /**
     * The complete-feature application {@code ref} with a {@link DetailCapturingSink}, without the
     * documentation module, so no composition binds two documentation sinks: deploying its {@link
     * HttpVerticle} builds the real mount and leaves the mount's attached publication in the sink. No
     * document is assembled or served by this component. It exposes the inputs the documentation
     * module would hand the assembler: the bound schema source, the profile registry, the response
     * producer bindings, and the security scheme handlers.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                CompleteModules.Ref.class,
                DetailCapturingSink.Binding.class
            })
    interface CaptureComponent {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();

        /**
         * Resolves the component's capturing sink.
         *
         * @return the sink
         */
        DetailCapturingSink capture();

        /**
         * Resolves the bound operation schema source.
         *
         * @return the source, present in this composition
         */
        Optional<OperationSchemaSource> schemaSource();

        /**
         * Resolves the JSON mapper profile registry.
         *
         * @return the registry
         */
        JsonMapperProfileRegistry profiles();

        /**
         * Resolves the registered response producer bindings.
         *
         * @return the bindings
         */
        Set<ResponseProducerBinding<?>> producerBindings();

        /**
         * Resolves the registered security scheme handlers.
         *
         * @return the handlers
         */
        Set<SecuritySchemeHandler> securitySchemeHandlers();

        /** Factory taking the Vert.x instance and the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component.
             *
             * @param vertx the Vert.x instance
             * @param config the application configuration
             * @return the component
             */
            CaptureComponent create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }
}
