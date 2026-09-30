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
import dev.vertique.rest.openapi.docs.fixture.ProtectedRegistrationModule;
import dev.vertique.rest.openapi.docs.fixture.SchemaSourceModules;
import dev.vertique.rest.openapi.docs.fixture.SharedResourcesModule;
import dev.vertique.rest.openapi.docs.fixture.startup.SchemeBindings;
import dev.vertique.rest.openapi.docs.fixture.startup.StartupRegistrations;
import dev.vertique.rest.openapi.docs.fixture.startup.warning.WarningControls;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger test components of the uncovered-control warning proof. Each lists the documentation
 * module, the REST module (which binds the framework's content-type middleware), and
 * {@link WarningControls}, the proof's mount customizers, middleware, hook, and interceptors.
 */
public final class WarningTestComponents {

    private WarningTestComponents() {}

    /** What every component of this proof exposes. */
    public interface Provisions {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();
    }

    /** Creates a component from the application configuration. */
    public interface Factory<C> {

        /**
         * Creates the component.
         *
         * @param config the application configuration
         * @return the component
         */
        C create(@BindsInstance @VertxConfig JsonObject config);
    }

    /**
     * The shared declarations with {@code DocumentedMgmtApi} in place of {@code MgmtApi}: two
     * public documents, {@code public} and {@code mgmt}, each with resources.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                StartupRegistrations.PublicOnly.class,
                StartupRegistrations.DocumentedMgmt.class,
                SharedResourcesModule.class,
                SchemaSourceModules.Counting.class,
                WarningControls.class
            })
    public interface DocumentedMgmtComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<DocumentedMgmtComponent> {}
    }

    /**
     * The shared declarations with {@code ProtectedMgmtApi} in place of {@code MgmtApi}, the stub
     * {@code bearerAuth} scheme handler and the auth enforcement marker bound.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                ProtectedRegistrationModule.class,
                SharedResourcesModule.class,
                SchemaSourceModules.Counting.class,
                SchemeBindings.BearerAuthWithEnforcement.class,
                WarningControls.class
            })
    public interface ProtectedMgmtComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ProtectedMgmtComponent> {}
    }

    /**
     * {@code EmptyApi} as the sole registration and no resource, so its application mount is empty.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                StartupRegistrations.Empty.class,
                SchemaSourceModules.Counting.class,
                WarningControls.class
            })
    public interface EmptyMountComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<EmptyMountComponent> {}
    }
}
