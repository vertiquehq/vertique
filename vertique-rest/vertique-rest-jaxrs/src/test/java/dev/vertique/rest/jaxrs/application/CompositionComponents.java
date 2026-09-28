// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.application.manual.ManualResourceModule;
import dev.vertique.rest.jaxrs.application.manual.NestedCompositionResourceModule;
import dev.vertique.rest.jaxrs.application.manual.ReentrantResourceModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger components for {@link JaxRsApplicationCompositionTest} (TP-001, TP-002, TP-004, TP-005,
 * TP-015), built from the shared native fixture modules plus this lane's single-proof fixtures.
 * Every component's factory takes the application configuration as a
 * {@code @BindsInstance @VertxConfig JsonObject}.
 */
public final class CompositionComponents {

    private CompositionComponents() {}

    /** Provisions every composition-suite component exposes. */
    public interface Provisions {

        /**
         * Resolves the {@code @JaxRsResources Set<Object>} multibinding.
         *
         * @return the resolved resource set
         */
        @JaxRsResources
        Set<Object> jaxRsResources();

        /**
         * Resolves the {@code Set<RouterMount>} multibinding, including {@code RestModule}'s
         * default JAX-RS mount provider.
         *
         * @return the resolved mount set
         */
        Set<RouterMount> routerMounts();
    }

    /**
     * The standard fixture set: {@code unita} (Catalog, Extra, Disabled), {@code unita.scoped}
     * ({@code ScopedResource}), {@code unitb} ({@code PublicApi}, {@code ManagementApi}), and
     * {@code manual} (BlobLike). Used by TP-001 and TP-004.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unita.scoped.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unitb.GeneratedJaxRsResourcesModule.class,
                ManualResourceModule.class
            })
    public interface StandardComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            StandardComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * The zero-declaration fixture set: {@code unita} and {@code manual} only, with no application
     * registration module at all, so {@code Set<GeneratedRestApplicationRegistration>} resolves
     * empty.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule.class,
                ManualResourceModule.class
            })
    public interface ZeroDeclarationComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            ZeroDeclarationComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-002 row (a): {@code unita}, {@code manual}, and only the single-proof
     * {@link dev.vertique.rest.jaxrs.application.unitb.DiscoveryRegistrationModule
     * DiscoveryRegistrationModule} — no {@code unitb} module, so {@code DiscoveryApi} is the only
     * registration, proving the sole discovery rule's success path.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unitb.DiscoveryRegistrationModule.class,
                ManualResourceModule.class
            })
    public interface DiscoverySoloComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            DiscoverySoloComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-002 rows (b) to (d): the standard fixture set plus the single-proof
     * {@link dev.vertique.rest.jaxrs.application.unitb.DiscoveryRegistrationModule
     * DiscoveryRegistrationModule}, so {@code DiscoveryApi} is never the sole registration,
     * whatever the activation configuration.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unitb.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unitb.DiscoveryRegistrationModule.class,
                ManualResourceModule.class
            })
    public interface DiscoveryComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            DiscoveryComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-015 (G-06 (a)): zero declarations (no registration module at all) plus the single-proof
     * {@link ReentrantResourceModule}, so {@code RestModule.jaxRsRouterMount} runs its
     * zero-declaration body; the component-scoped re-entry guard around that body turns the
     * resource's dependency on this component's own {@code Set<RouterMount>} into a named failure.
     */
    @Singleton
    @Component(modules = {RestModule.class, ApplicationTestSupportModule.class, ReentrantResourceModule.class})
    public interface ReentrantResourceZeroDeclarationComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            ReentrantResourceZeroDeclarationComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-015 (G-06 (b)): {@code unitb} (for {@code PublicApi}, whose static resource list lists
     * {@link ReentrantResourceModule}'s resource in explicit-mode rows) plus the single-proof
     * {@link ReentrantResourceModule}, so the composer's manual-resource resolution (not the
     * zero-declaration body) is the one that re-enters.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.reentry.ReentrantExplicitApplicationModule.class,
                ReentrantResourceModule.class
            })
    public interface ReentrantResourceExplicitComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            ReentrantResourceExplicitComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-015 (W-1, round 2): zero declarations (no registration module at all) plus the single-proof
     * {@link NestedCompositionResourceModule}, whose resource builds and resolves a SECOND,
     * independent {@link ZeroDeclarationComponent} of its own inside its own construction.
     */
    @Singleton
    @Component(modules = {RestModule.class, ApplicationTestSupportModule.class, NestedCompositionResourceModule.class})
    public interface NestedCompositionZeroDeclarationComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            NestedCompositionZeroDeclarationComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }
}
