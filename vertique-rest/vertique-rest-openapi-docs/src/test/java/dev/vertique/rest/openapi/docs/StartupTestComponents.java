// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.core.router.MountCompositionValidator;
import dev.vertique.rest.core.router.MountCustomizer;
import dev.vertique.rest.core.router.RouterCustomizer;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.publication.OperationPublicationSink;
import dev.vertique.rest.openapi.docs.fixture.CountingRouterLifecycleHook;
import dev.vertique.rest.openapi.docs.fixture.DocsTestSupportModule;
import dev.vertique.rest.openapi.docs.fixture.MarkerRouterMount;
import dev.vertique.rest.openapi.docs.fixture.ProtectedRegistrationModule;
import dev.vertique.rest.openapi.docs.fixture.RecordingMountCustomizer;
import dev.vertique.rest.openapi.docs.fixture.SchemaSourceModules;
import dev.vertique.rest.openapi.docs.fixture.SharedRegistrationModule;
import dev.vertique.rest.openapi.docs.fixture.SharedResourcesModule;
import dev.vertique.rest.openapi.docs.fixture.startup.RouterSpy;
import dev.vertique.rest.openapi.docs.fixture.startup.SchemeBindings;
import dev.vertique.rest.openapi.docs.fixture.startup.StartupRegistrations;
import dev.vertique.rest.openapi.docs.fixture.startup.startupit.AnnotatedPublicRegistrationModule;
import dev.vertique.rest.openapi.docs.fixture.startup.startupit.ContributedResources;
import dev.vertique.rest.openapi.docs.fixture.startup.startupit.HandBuiltMounts;
import dev.vertique.rest.openapi.docs.fixture.startup.startupit.PublicReservedRegistrationModule;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * The Dagger test components of the startup-check integration tests. They live in the module's
 * package so they can expose its package-private types.
 *
 * <p>Every component lists {@link StartupBase}: {@code RestModule}, {@link OpenApiDocsModule}, the
 * canonical {@link ConfigParsingModule}, the test support bindings, a counting schema source, a
 * recording mount customizer, a marker mount after every other mount, and the two spies, a
 * {@link RouterSpy} mounted before every other mount and a {@link CountingRouterLifecycleHook}. A
 * component adds only its registrations, their resources as manual {@code @JaxRsResources}
 * instances, and at most one {@link SchemeBindings} module. Each takes the application configuration
 * through its factory. A component whose configuration is refused throws when
 * {@link StartupProvisions#httpVerticle()} provisions the mounts; its spies and store stay readable.
 */
public final class StartupTestComponents {

    private StartupTestComponents() {}

    /** The modules every startup component lists, without any registration or resource. */
    @Module(
            includes = {
                RestModule.class,
                OpenApiDocsModule.class,
                ConfigParsingModule.class,
                DocsTestSupportModule.class,
                SchemaSourceModules.Counting.class,
                RecordingMountCustomizer.Binding.class,
                MarkerRouterMount.Last.class,
                RouterSpy.Binding.class,
                CountingRouterLifecycleHook.Binding.class
            })
    public static final class StartupBase {

        private StartupBase() {}
    }

    /** What every startup component exposes. */
    public interface StartupProvisions {

        /**
         * Creates a new {@link HttpVerticle} of this component, provisioning its mounts.
         *
         * @return a new verticle
         */
        HttpVerticle httpVerticle();

        /**
         * Resolves the component's router spy, the first mount any composition builds.
         *
         * @return the spy
         */
        RouterSpy routerSpy();

        /**
         * Resolves the component's counting router lifecycle hook, called once per JAX-RS router built.
         *
         * @return the hook
         */
        CountingRouterLifecycleHook lifecycleHook();

        /**
         * Resolves the component's document store.
         *
         * @return the store
         */
        DocumentStore documentStore();
    }

    /** Creates a component from the application configuration. */
    public interface Factory<C extends StartupProvisions> {

        /**
         * Creates the component; nothing is provisioned yet.
         *
         * @param config the application configuration
         * @return the component
         */
        C create(@BindsInstance @VertxConfig JsonObject config);
    }

    /** The shared fixture: the documented {@code PublicApi} and the undocumented {@code MgmtApi}. */
    @Singleton
    @Component(modules = {StartupBase.class, SharedRegistrationModule.class, SharedResourcesModule.class})
    public interface SharedStartupComponent extends StartupProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<SharedStartupComponent> {}
    }

    /**
     * The shared fixture with {@code NoSchemeApi} in place of {@code MgmtApi}: a protected document
     * that names no security scheme, and no scheme handler or enforcement marker bound.
     */
    @Singleton
    @Component(
            modules = {
                StartupBase.class,
                StartupRegistrations.PublicOnly.class,
                StartupRegistrations.NoScheme.class,
                SharedResourcesModule.class
            })
    public interface NoSchemeStartupComponent extends StartupProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<NoSchemeStartupComponent> {}
    }

    /**
     * The shared fixture with {@code AnnotatedPublicApi} in place of {@code PublicApi}: the
     * {@code public} document's interface declares its {@code info}.
     */
    @Singleton
    @Component(modules = {StartupBase.class, AnnotatedPublicRegistrationModule.class, SharedResourcesModule.class})
    public interface AnnotatedPublicStartupComponent extends StartupProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<AnnotatedPublicStartupComponent> {}
    }

    /**
     * The shared fixture with {@code ProtectedMgmtApi} in place of {@code MgmtApi}, the enforcement
     * marker bound, and no scheme handler.
     */
    @Singleton
    @Component(
            modules = {
                StartupBase.class,
                ProtectedRegistrationModule.class,
                SharedResourcesModule.class,
                SchemeBindings.EnforcementOnly.class
            })
    public interface ProtectedEnforcementOnlyComponent extends StartupProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ProtectedEnforcementOnlyComponent> {}
    }

    /**
     * The shared fixture with {@code ProtectedMgmtApi} in place of {@code MgmtApi}, the enforcement
     * marker bound, and only a handler named {@code otherAuth}.
     */
    @Singleton
    @Component(
            modules = {
                StartupBase.class,
                ProtectedRegistrationModule.class,
                SharedResourcesModule.class,
                SchemeBindings.OtherAuthWithEnforcement.class
            })
    public interface ProtectedOtherAuthComponent extends StartupProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ProtectedOtherAuthComponent> {}
    }

    /**
     * The shared fixture with {@code ProtectedMgmtApi} in place of {@code MgmtApi}, the
     * {@code bearerAuth} handler bound, and no enforcement marker.
     */
    @Singleton
    @Component(
            modules = {
                StartupBase.class,
                ProtectedRegistrationModule.class,
                SharedResourcesModule.class,
                SchemeBindings.BearerAuthOnly.class
            })
    public interface ProtectedBearerAuthOnlyComponent extends StartupProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ProtectedBearerAuthOnlyComponent> {}
    }

    /**
     * The shared fixture with {@code AuthenticatedMgmtApi} in place of {@code MgmtApi}, the
     * {@code bearerAuth} handler bound, and no enforcement marker.
     */
    @Singleton
    @Component(
            modules = {
                StartupBase.class,
                StartupRegistrations.PublicOnly.class,
                StartupRegistrations.AuthenticatedMgmt.class,
                SharedResourcesModule.class,
                SchemeBindings.BearerAuthOnly.class
            })
    public interface AuthenticatedBearerAuthOnlyComponent extends StartupProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<AuthenticatedBearerAuthOnlyComponent> {}
    }

    /**
     * The shared fixture with {@code ProtectedMgmtApi} in place of {@code MgmtApi}, and both the
     * {@code bearerAuth} handler and the enforcement marker bound.
     */
    @Singleton
    @Component(
            modules = {
                StartupBase.class,
                ProtectedRegistrationModule.class,
                SharedResourcesModule.class,
                SchemeBindings.BearerAuthWithEnforcement.class
            })
    public interface ProtectedBearerAuthWithEnforcementComponent extends StartupProvisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<ProtectedBearerAuthWithEnforcementComponent> {}
    }

    /** What a component exposes so a test can see which composition checks and sinks it holds. */
    public interface CompositionExtensions {

        /**
         * Resolves the composition validators the Dagger-built {@link HttpVerticle} receives.
         *
         * @return the validators
         */
        Set<MountCompositionValidator> mountCompositionValidators();

        /**
         * Resolves the operation publication sinks of the component.
         *
         * @return the sinks
         */
        Set<OperationPublicationSink> publicationSinks();
    }

    /**
     * What the public five-argument {@link HttpVerticle} constructor receives, each call provisioning
     * anew: every {@link #routerMounts()} call creates new unscoped mounts. Also exposes the JAX-RS
     * mount factory, so a test can add a hand-built mount to a provision.
     */
    public interface VerticleInputs {

        /**
         * Provisions the server options.
         *
         * @return the options
         */
        HttpServerOptions httpServerOptions();

        /**
         * Provisions the router customizers.
         *
         * @return the customizers
         */
        Set<RouterCustomizer> routerCustomizers();

        /**
         * Provisions the middlewares.
         *
         * @return the middlewares
         */
        Set<Middleware> middlewares();

        /**
         * Provisions the router mounts, new unscoped mount instances on every call.
         *
         * @return the mounts
         */
        Set<RouterMount> routerMounts();

        /**
         * Provisions the mount customizers.
         *
         * @return the customizers
         */
        Set<MountCustomizer> mountCustomizers();

        /**
         * Resolves the JAX-RS mount factory of the component.
         *
         * @return the factory
         */
        JaxRsRouterMount.Factory jaxRsRouterMountFactory();
    }

    /**
     * The shared fixture plus the row's hand-built JAX-RS mounts: the documented {@code PublicApi},
     * the undocumented {@code MgmtApi}, and every mount of the bound {@link HandBuiltMounts}.
     */
    @Singleton
    @Component(
            modules = {
                StartupBase.class,
                SharedRegistrationModule.class,
                SharedResourcesModule.class,
                HandBuiltMounts.Contribution.class
            })
    public interface HandBuiltMountStartupComponent extends StartupProvisions {

        /** Factory taking the application configuration and the row's hand-built mounts. */
        @Component.Factory
        interface ComponentFactory {

            /**
             * Creates the component; nothing is provisioned yet.
             *
             * @param config the application configuration
             * @param mounts the row's hand-built JAX-RS mounts
             * @return the component
             */
            HandBuiltMountStartupComponent create(
                    @BindsInstance @VertxConfig JsonObject config, @BindsInstance HandBuiltMounts mounts);
        }
    }

    /**
     * {@code PublicReservedApi} in place of {@code PublicApi}, beside {@code MgmtApi}; the row's
     * {@link ContributedResources} supply the reserved-id resource the documented application lists.
     */
    @Singleton
    @Component(
            modules = {
                StartupBase.class,
                PublicReservedRegistrationModule.class,
                SharedResourcesModule.class,
                ContributedResources.Contribution.class
            })
    public interface PublicReservedStartupComponent extends StartupProvisions {

        /** Factory taking the application configuration and the row's resources. */
        @Component.Factory
        interface ComponentFactory {

            /**
             * Creates the component; nothing is provisioned yet.
             *
             * @param config    the application configuration
             * @param resources the row's {@code @JaxRsResources} instances
             * @return the component
             */
            PublicReservedStartupComponent create(
                    @BindsInstance @VertxConfig JsonObject config, @BindsInstance ContributedResources resources);
        }
    }

    /**
     * The documented root application {@code api} at {@code /} as the sole registration, holding the
     * row's {@link ContributedResources} by discovery.
     */
    @Singleton
    @Component(modules = {StartupBase.class, StartupRegistrations.Root.class, ContributedResources.Contribution.class})
    public interface RootApplicationStartupComponent extends StartupProvisions, CompositionExtensions {

        /** Factory taking the application configuration and the row's resources. */
        @Component.Factory
        interface ComponentFactory {

            /**
             * Creates the component; nothing is provisioned yet.
             *
             * @param config    the application configuration
             * @param resources the row's {@code @JaxRsResources} instances
             * @return the component
             */
            RootApplicationStartupComponent create(
                    @BindsInstance @VertxConfig JsonObject config, @BindsInstance ContributedResources resources);
        }
    }

    /**
     * No registration at all, the documentation module installed: the legacy default JAX-RS mount
     * holds the row's {@link ContributedResources}, beside the row's {@link HandBuiltMounts}.
     */
    @Singleton
    @Component(
            modules = {StartupBase.class, ContributedResources.Contribution.class, HandBuiltMounts.Contribution.class})
    public interface ZeroDeclarationStartupComponent extends StartupProvisions, CompositionExtensions {

        /** Factory taking the application configuration, the row's resources, and its hand-built mounts. */
        @Component.Factory
        interface ComponentFactory {

            /**
             * Creates the component; nothing is provisioned yet.
             *
             * @param config    the application configuration
             * @param resources the row's {@code @JaxRsResources} instances
             * @param mounts    the row's hand-built JAX-RS mounts
             * @return the component
             */
            ZeroDeclarationStartupComponent create(
                    @BindsInstance @VertxConfig JsonObject config,
                    @BindsInstance ContributedResources resources,
                    @BindsInstance HandBuiltMounts mounts);
        }
    }

    /**
     * The shared fixture exposing what the public five-argument {@link HttpVerticle} constructor
     * receives, beside its Dagger-built verticle.
     */
    @Singleton
    @Component(modules = {StartupBase.class, SharedRegistrationModule.class, SharedResourcesModule.class})
    public interface VerticleInputsStartupComponent extends StartupProvisions, VerticleInputs {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface ComponentFactory extends Factory<VerticleInputsStartupComponent> {}
    }
}
