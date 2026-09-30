// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.openapi.docs.fixture.CatalogResourceModule;
import dev.vertique.rest.openapi.docs.fixture.DocsTestSupportModule;
import dev.vertique.rest.openapi.docs.fixture.SchemaSourceModules;
import dev.vertique.rest.openapi.docs.fixture.startup.StartupRegistrations;
import dev.vertique.rest.openapi.docs.fixture.startup.fallthrough.EveryMountHeaderCustomizer;
import dev.vertique.rest.openapi.docs.fixture.startup.fallthrough.FallThroughBindings;
import dev.vertique.rest.openapi.docs.fixture.startup.fallthrough.FallThroughCounters;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger test components of the fall-through proofs. Each registers the documented root
 * application {@code api} at {@code /} as its sole registration, holding {@code CatalogResource} as a
 * manual {@code @JaxRsResources} instance, lists {@link OpenApiDocsModule}, and contributes the
 * application-owned documentation UI mount at {@code /apidocs/ui/*}, a counting request interceptor,
 * and counting {@code API}- and {@code ROOT}-scoped middlewares, all counting into the
 * {@link FallThroughCounters} the factory receives.
 */
public final class FallThroughTestComponents {

    private FallThroughTestComponents() {}

    /** What every fall-through component exposes. */
    public interface Provisions {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();
    }

    /** Creates a component from the application configuration and the counters. */
    public interface Factory<C> {

        /**
         * Creates the component.
         *
         * @param config   the application configuration
         * @param counters the counters the interceptor and middlewares increment
         * @return the component
         */
        C create(@BindsInstance @VertxConfig JsonObject config, @BindsInstance FallThroughCounters counters);
    }

    /** The root application, the documentation UI mount, and the counting interceptor and middlewares. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                StartupRegistrations.Root.class,
                CatalogResourceModule.class,
                SchemaSourceModules.Counting.class,
                FallThroughBindings.class
            })
    public interface FallThroughComponent extends Provisions {

        /** Factory taking the application configuration and the counters. */
        @Component.Factory
        interface ComponentFactory extends Factory<FallThroughComponent> {}
    }

    /** {@link FallThroughComponent} plus an {@link EveryMountHeaderCustomizer}. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                StartupRegistrations.Root.class,
                CatalogResourceModule.class,
                SchemaSourceModules.Counting.class,
                FallThroughBindings.class,
                EveryMountHeaderCustomizer.Binding.class
            })
    public interface CustomizedFallThroughComponent extends Provisions {

        /** Factory taking the application configuration and the counters. */
        @Component.Factory
        interface ComponentFactory extends Factory<CustomizedFallThroughComponent> {}
    }
}
