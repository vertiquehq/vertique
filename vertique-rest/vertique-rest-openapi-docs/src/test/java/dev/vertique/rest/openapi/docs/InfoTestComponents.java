// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.openapi.docs.fixture.DocsTestSupportModule;
import dev.vertique.rest.openapi.docs.fixture.metadata.info.BasicInfoApi;
import dev.vertique.rest.openapi.docs.fixture.metadata.info.FullInfoApi;
import dev.vertique.rest.openapi.docs.fixture.metadata.info.InfoPingResource;
import dev.vertique.rest.openapi.docs.fixture.metadata.info.InfoRegistrations;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * The Dagger components of the document {@code info} integration tests.
 *
 * <p>Each component serves the one application {@code public} at {@code /api/public}, declared by
 * one fixture interface, which lists the ping resource; it is built from {@code RestModule}, {@link
 * OpenApiDocsModule}, the canonical {@link ConfigParsingModule}, {@link DocsTestSupportModule}, and
 * one registration module. Each takes the application configuration through its factory, so the
 * same component serves a document with and without configured {@code info}.
 *
 * <p>To add a component: declare a {@code @Singleton @Component} listing the four shared modules and
 * a registration module, extending {@link InfoProvisions}, with a nested {@code @Component.Factory}
 * extending {@link Factory}.
 */
public final class InfoTestComponents {

    private InfoTestComponents() {}

    /** What every component exposes. */
    public interface InfoProvisions {

        /**
         * Creates a new {@link HttpVerticle} of this component, which serves the documentation mount.
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

    /** Registers application {@code public} declared by {@link FullInfoApi}, with the ping resource. */
    @Module
    public static final class FullInfoModule {

        private FullInfoModule() {}

        /**
         * Registers the application.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return InfoRegistrations.publicApi(FullInfoApi.class);
        }

        /**
         * Contributes the ping resource.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new InfoPingResource();
        }
    }

    /** Registers application {@code public} declared by {@link BasicInfoApi}, with the ping resource. */
    @Module
    public static final class BasicInfoModule {

        private BasicInfoModule() {}

        /**
         * Registers the application.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return InfoRegistrations.publicApi(BasicInfoApi.class);
        }

        /**
         * Contributes the ping resource.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new InfoPingResource();
        }
    }

    /** The application whose declaring interface sets every {@code @Info} member. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                FullInfoModule.class
            })
    public interface FullInfoComponent extends InfoProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<FullInfoComponent> {}
    }

    /** The application whose declaring interface sets only title, version, and description. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                BasicInfoModule.class
            })
    public interface BasicInfoComponent extends InfoProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<BasicInfoComponent> {}
    }
}
