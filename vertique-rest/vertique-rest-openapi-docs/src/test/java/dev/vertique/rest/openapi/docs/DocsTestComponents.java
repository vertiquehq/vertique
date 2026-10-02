// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.application.RestApplications;
import dev.vertique.rest.jaxrs.publication.OperationPublicationSink;
import dev.vertique.rest.openapi.docs.fixture.CatalogResourceModule;
import dev.vertique.rest.openapi.docs.fixture.ContextRecordingRouterMount;
import dev.vertique.rest.openapi.docs.fixture.CountingRouterLifecycleHook;
import dev.vertique.rest.openapi.docs.fixture.CountingSchemaSource;
import dev.vertique.rest.openapi.docs.fixture.DocsTestSupportModule;
import dev.vertique.rest.openapi.docs.fixture.InactivePublicRegistrationModule;
import dev.vertique.rest.openapi.docs.fixture.ManualMountModule;
import dev.vertique.rest.openapi.docs.fixture.MarkerRouterMount;
import dev.vertique.rest.openapi.docs.fixture.ProtectedRegistrationModule;
import dev.vertique.rest.openapi.docs.fixture.RecordingMountCustomizer;
import dev.vertique.rest.openapi.docs.fixture.RecordingPublicationSink;
import dev.vertique.rest.openapi.docs.fixture.SchemaSourceModules;
import dev.vertique.rest.openapi.docs.fixture.SharedRegistrationModule;
import dev.vertique.rest.openapi.docs.fixture.SharedResourcesModule;
import dev.vertique.rest.openapi.docs.fixture.StubSchemeHandler;
import dev.vertique.rest.openapi.docs.fixture.UndocumentedRegistrationModule;
import dev.vertique.rest.openapi.docs.publication.DocumentStore;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * The Dagger test components of this module, shared by tests in every package. The module types
 * they expose, such as {@link DocumentStore}, are public types of the module's internal packages.
 *
 * <p>Every component is built from {@code RestModule}, the canonical {@link ConfigParsingModule},
 * {@link DocsTestSupportModule}, a deterministic counting schema source, and a
 * {@link RecordingMountCustomizer}; each takes the application configuration through its factory
 * (see {@code fixture.DocsConfigs}). Unless its name says otherwise a component also lists
 * {@link OpenApiDocsModule}, registers the shared declarations ({@code PublicApi} documented and
 * {@code MgmtApi} undocumented, both active) with their resources as manual {@code @JaxRsResources}
 * instances, and places a {@link MarkerRouterMount} after every other mount. No component exposes
 * {@code Set<RouterMount>}: resolving it builds the mounts, which is the deployment's job.
 */
public final class DocsTestComponents {

    private DocsTestComponents() {}

    /** What every component exposes. */
    public interface Provisions {

        /**
         * Creates a new {@link HttpVerticle} of this component.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();

        /**
         * Resolves the component's declared-application view.
         *
         * @return the view
         */
        RestApplications restApplications();

        /**
         * Resolves the component's publication sinks.
         *
         * @return the sink set, empty when nothing contributes a sink
         */
        Set<OperationPublicationSink> publicationSinks();

        /**
         * Resolves the component's JAX-RS routing configuration.
         *
         * @return the configuration
         */
        JaxRsConfig jaxRsConfig();

        /**
         * Resolves the component's canonical configuration parser.
         *
         * @return the parser
         */
        ConfigParser configParser();

        /**
         * Resolves the component's schema source.
         *
         * @return the counting source
         */
        CountingSchemaSource schemaSource();

        /**
         * Resolves the component's recording customizer.
         *
         * @return the customizer
         */
        RecordingMountCustomizer mountCustomizer();
    }

    /** What every component that lists {@link OpenApiDocsModule} exposes besides {@link Provisions}. */
    public interface DocsProvisions extends Provisions {

        /**
         * Resolves the component's document store.
         *
         * @return the store
         */
        DocumentStore documentStore();
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

    /** The shared fixture: the shared declarations and the documentation module. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                SharedRegistrationModule.class,
                SharedResourcesModule.class,
                SchemaSourceModules.Counting.class,
                RecordingMountCustomizer.Binding.class,
                MarkerRouterMount.Last.class
            })
    public interface SharedComponent extends DocsProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<SharedComponent> {}
    }

    /**
     * The shared fixture without a marker mount: nothing but the documentation mount is mounted under
     * {@code /apidocs/*}, so a request the documentation mount passes on reaches the router's own
     * final response.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                SharedRegistrationModule.class,
                SharedResourcesModule.class,
                SchemaSourceModules.Counting.class,
                RecordingMountCustomizer.Binding.class
            })
    public interface WithoutMarkerMountComponent extends DocsProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<WithoutMarkerMountComponent> {}
    }

    /** The shared fixture with {@code UndocumentedPublicApi} in place of {@code PublicApi}. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                UndocumentedRegistrationModule.class,
                SharedResourcesModule.class,
                SchemaSourceModules.Counting.class,
                RecordingMountCustomizer.Binding.class,
                MarkerRouterMount.Last.class
            })
    public interface UndocumentedComponent extends DocsProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<UndocumentedComponent> {}
    }

    /** The shared fixture with {@code PublicApi}'s registration inactive. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                InactivePublicRegistrationModule.class,
                SharedResourcesModule.class,
                SchemaSourceModules.Counting.class,
                RecordingMountCustomizer.Binding.class,
                MarkerRouterMount.Last.class
            })
    public interface InactivePublicComponent extends DocsProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<InactivePublicComponent> {}
    }

    /**
     * No registrations: the documentation module beside {@code CatalogResource} alone, served by the
     * legacy default mount at {@code jaxrs.basePath}.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                CatalogResourceModule.class,
                SchemaSourceModules.Counting.class,
                RecordingMountCustomizer.Binding.class,
                MarkerRouterMount.Last.class
            })
    public interface LegacyDefaultMountComponent extends DocsProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<LegacyDefaultMountComponent> {}
    }

    /**
     * The shared declarations and resources in a component without {@link OpenApiDocsModule}; the
     * module's artifact stays on the classpath, so {@code @ApiDocs} is still read at runtime.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                SharedRegistrationModule.class,
                SharedResourcesModule.class,
                SchemaSourceModules.Counting.class,
                RecordingMountCustomizer.Binding.class,
                MarkerRouterMount.Last.class
            })
    public interface WithoutDocsModuleComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<WithoutDocsModuleComponent> {}
    }

    /**
     * The control of {@link InactivePublicComponent}: the same composition, {@code PublicApi}'s
     * registration inactive, without {@link OpenApiDocsModule}.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                InactivePublicRegistrationModule.class,
                SharedResourcesModule.class,
                SchemaSourceModules.Counting.class,
                RecordingMountCustomizer.Binding.class,
                MarkerRouterMount.Last.class
            })
    public interface InactivePublicWithoutDocsModuleComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<InactivePublicWithoutDocsModuleComponent> {}
    }

    /**
     * The control of {@link LegacyDefaultMountComponent}: no registrations, {@code CatalogResource}
     * alone served by the legacy default mount at {@code jaxrs.basePath}, without
     * {@link OpenApiDocsModule}.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                CatalogResourceModule.class,
                SchemaSourceModules.Counting.class,
                RecordingMountCustomizer.Binding.class,
                MarkerRouterMount.Last.class
            })
    public interface LegacyDefaultMountWithoutDocsModuleComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<LegacyDefaultMountWithoutDocsModuleComponent> {}
    }

    /** The shared fixture plus a hand-built manual JAX-RS mount at {@code /api/manual/*}. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                SharedRegistrationModule.class,
                SharedResourcesModule.class,
                ManualMountModule.class,
                SchemaSourceModules.Counting.class,
                RecordingMountCustomizer.Binding.class,
                MarkerRouterMount.Last.class
            })
    public interface ManualMountComponent extends DocsProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ManualMountComponent> {}
    }

    /**
     * The shared fixture with an {@code APPLICATION}-phase marker mount at priority
     * {@link Integer#MIN_VALUE} in place of the last marker mount.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                SharedRegistrationModule.class,
                SharedResourcesModule.class,
                SchemaSourceModules.Counting.class,
                RecordingMountCustomizer.Binding.class,
                MarkerRouterMount.Early.class
            })
    public interface EarlyMarkerComponent extends DocsProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<EarlyMarkerComponent> {}
    }

    /**
     * The shared fixture plus a recording publication sink beside the documentation sink and a
     * {@code SYSTEM_LAST} context-recording mount at {@code /zz-last/*}.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                SharedRegistrationModule.class,
                SharedResourcesModule.class,
                SchemaSourceModules.Counting.class,
                RecordingMountCustomizer.Binding.class,
                MarkerRouterMount.Last.class,
                RecordingPublicationSink.Binding.class,
                ContextRecordingRouterMount.Binding.class
            })
    public interface RecordingSinkComponent extends DocsProvisions {

        /**
         * Resolves the component's recording sink.
         *
         * @return the recording sink
         */
        RecordingPublicationSink recordingSink();

        /**
         * Resolves the component's context-recording mount.
         *
         * @return the context-recording mount
         */
        ContextRecordingRouterMount contextRecordingMount();

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<RecordingSinkComponent> {}
    }

    /**
     * The shared fixture with {@code ProtectedMgmtApi} in place of {@code MgmtApi}, the stub
     * {@code bearerAuth} scheme handler and the auth enforcement marker bound, and a counting router
     * lifecycle hook.
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
                RecordingMountCustomizer.Binding.class,
                MarkerRouterMount.Last.class,
                StubSchemeHandler.BearerAuthWithEnforcement.class,
                CountingRouterLifecycleHook.Binding.class
            })
    public interface ProtectedComponent extends DocsProvisions {

        /**
         * Resolves the component's counting router lifecycle hook.
         *
         * @return the hook
         */
        CountingRouterLifecycleHook lifecycleHook();

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ProtectedComponent> {}
    }
}
