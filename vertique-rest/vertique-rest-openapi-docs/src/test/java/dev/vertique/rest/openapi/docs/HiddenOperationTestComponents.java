// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.openapi.docs.fixture.DocsTestSupportModule;
import dev.vertique.rest.openapi.docs.fixture.disclosure.sources.DisclosureSourceModules;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden.HiddenOperationModules;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger components of the hidden-operation integration test.
 *
 * <p>Every component binds the {@code web-validation} wiring with the canonical schema source itself
 * ({@link DisclosureSourceModules.Canonical}) and no recording sink, so the schemas the gate and the
 * documents see are the generator's, and lists exactly one module of {@link HiddenOperationModules}.
 * The <em>served</em> component lists {@link OpenApiDocsModule} and serves the public documents of
 * {@code hidden} and {@code hiddengen} over HTTP. A <em>rendering</em> component lists {@link
 * ProtectedRenderingModule} instead, registers a protected application, and exposes the {@link
 * ProtectedRenderingSink}, which keeps its protected rendering; it never deploys the documentation
 * mount. The configuration must select {@code web-validation}.
 */
final class HiddenOperationTestComponents {

    private HiddenOperationTestComponents() {}

    /** What every component exposes. */
    interface Served {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();
    }

    /** What every rendering component exposes. */
    interface Renders extends Served {

        /**
         * Resolves the component's rendering sink.
         *
         * @return the sink
         */
        ProtectedRenderingSink protectedRendering();
    }

    /** Creates a component from the application configuration. */
    interface Factory<C> {

        /**
         * Creates the component.
         *
         * @param config the application configuration
         * @return the component
         */
        C create(@BindsInstance @VertxConfig JsonObject config);
    }

    /** The applications {@code hidden} and {@code hiddengen}, serving their public documents. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                HiddenOperationModules.Served.class
            })
    interface ServedComponent extends Served {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ServedComponent> {}
    }

    /** The application {@code hidden}, rendering its protected document. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ProtectedRenderingModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                HiddenOperationModules.ProtectedHidden.class
            })
    interface ProtectedHiddenComponent extends Renders {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ProtectedHiddenComponent> {}
    }

    /** The control application {@code visiblewrite}, rendering its protected document. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ProtectedRenderingModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                DisclosureSourceModules.Canonical.class,
                HiddenOperationModules.ProtectedVisibleWrite.class
            })
    interface ProtectedVisibleWriteComponent extends Renders {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ProtectedVisibleWriteComponent> {}
    }
}
