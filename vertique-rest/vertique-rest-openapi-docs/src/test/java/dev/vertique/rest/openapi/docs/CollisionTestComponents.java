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
import dev.vertique.rest.openapi.docs.fixture.SchemaSourceModules;
import dev.vertique.rest.openapi.docs.fixture.startup.StartupRegistrations;
import dev.vertique.rest.openapi.docs.fixture.startup.collision.CaseMount;
import dev.vertique.rest.openapi.docs.fixture.startup.collision.CaseResources;
import dev.vertique.rest.openapi.docs.fixture.startup.collision.CollisionRegistrations;
import dev.vertique.rest.openapi.docs.fixture.startup.collision.PublicationRetainingHook;
import dev.vertique.rest.openapi.docs.publication.DocumentStore;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger test components of the route-collision tests. They expose the component's {@link
 * DocumentStore}, a public type of the module's internal {@code publication} package.
 *
 * <p>Every component is built from {@code RestModule}, the canonical {@link ConfigParsingModule},
 * {@link DocsTestSupportModule}, and the deterministic counting schema source, and takes the
 * application configuration through its factory. The per-composition resources or mount come in as a
 * bound instance, so one component class serves every row of a table.
 */
public final class CollisionTestComponents {

    private CollisionTestComponents() {}

    /** What every documented component exposes. */
    public interface DocumentedProvisions {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();

        /**
         * Resolves the component's document store.
         *
         * @return the store
         */
        DocumentStore documentStore();
    }

    /**
     * The documented root application {@code api} at {@code /} (discovery membership, the sole
     * registration) and the documentation module; the bound {@link CaseResources} are the root
     * application's resources.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                StartupRegistrations.Root.class,
                CaseResources.Contribution.class,
                SchemaSourceModules.Counting.class
            })
    public interface RootApplicationComponent extends DocumentedProvisions {

        /** Factory taking the application configuration and the root application's resources. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component.
             *
             * @param config    the application configuration
             * @param resources the root application's resource instances
             * @return the component
             */
            RootApplicationComponent create(
                    @BindsInstance @VertxConfig JsonObject config, @BindsInstance CaseResources resources);
        }
    }

    /**
     * The documented application {@code public} at {@code /public} beside the undocumented
     * application {@code api} at {@code /api}, and the documentation module; the bound
     * {@link CaseResources} hold both applications' resources.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                CollisionRegistrations.PublicRoot.class,
                CollisionRegistrations.Api.class,
                CaseResources.Contribution.class,
                SchemaSourceModules.Counting.class
            })
    public interface UndocumentedNeighbourComponent extends DocumentedProvisions {

        /** Factory taking the application configuration and both applications' resources. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component.
             *
             * @param config    the application configuration
             * @param resources both applications' resource instances
             * @return the component
             */
            UndocumentedNeighbourComponent create(
                    @BindsInstance @VertxConfig JsonObject config, @BindsInstance CaseResources resources);
        }
    }

    /**
     * No documentation module and no registrations: the bound {@link CaseMount} is the only mount,
     * and a {@link PublicationRetainingHook} retains its publication.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                CaseMount.Contribution.class,
                PublicationRetainingHook.Binding.class,
                SchemaSourceModules.Counting.class
            })
    public interface CaseMountComponent {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();

        /**
         * Resolves the component's retaining hook.
         *
         * @return the hook
         */
        PublicationRetainingHook publicationHook();

        /** Factory taking the application configuration and the case mount. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component.
             *
             * @param config    the application configuration
             * @param caseMount the only mount
             * @return the component
             */
            CaseMountComponent create(
                    @BindsInstance @VertxConfig JsonObject config, @BindsInstance CaseMount caseMount);
        }
    }
}
