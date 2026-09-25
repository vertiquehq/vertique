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
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger components for {@link JaxRsApplicationCompositionTest} (TP-001 to TP-004, TP-006 to
 * TP-009, TP-014 to TP-016), built from L01's shared fixture modules plus this lane's single-proof
 * fixtures. Every component's factory takes the application configuration as a
 * {@code @BindsInstance @VertxConfig JsonObject}, matching {@code LegacyComponents} (T001) and
 * {@code SharedFixtureSmokeComponents} (L01).
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
     * The standard fixture set: {@code unita} (Catalog, Extra, Disabled), {@code unitb}
     * ({@link dev.vertique.rest.jaxrs.application.unitb.PublicApplication PublicApplication} and
     * {@link dev.vertique.rest.jaxrs.application.unitb.ManagementApplication ManagementApplication}),
     * and {@code manual} (BlobLike). Used by TP-001, TP-002, TP-004, TP-007, TP-008, and TP-014.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule.class,
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
     * registration module at all, so {@code Set<GeneratedJaxRsApplicationRegistration>} resolves
     * empty. Used by TP-009.
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
     * TP-003's fixture set: {@code unita}, the single-proof
     * {@link dev.vertique.rest.jaxrs.application.unita.scoped.ScopedResource ScopedResource} module,
     * and {@code unitb} (for {@code PublicApplication}, whose configurable {@code getClasses()}
     * drives the laziness and ordering assertions). No {@code manual} module: TP-003 does not
     * involve the manual contribution.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unita.scoped.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unitb.GeneratedJaxRsResourcesModule.class
            })
    public interface LazinessComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            LazinessComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-006 case (a): {@code unita}, {@code manual}, and only the single-proof
     * {@link dev.vertique.rest.jaxrs.application.unitb.DiscoveryRegistrationModule
     * DiscoveryRegistrationModule} — no {@code unitb} module, so
     * {@link dev.vertique.rest.jaxrs.application.unitb.DiscoveryApplication DiscoveryApplication} is
     * the only registration in {@code Set<GeneratedJaxRsApplicationRegistration>}, proving the sole
     * discovery rule's success path.
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
     * TP-006 cases (b) to (d): the standard fixture set plus the single-proof
     * {@link dev.vertique.rest.jaxrs.application.unitb.DiscoveryRegistrationModule
     * DiscoveryRegistrationModule}, so
     * {@link dev.vertique.rest.jaxrs.application.unitb.DiscoveryApplication DiscoveryApplication} is
     * never the sole registration, whatever the activation configuration.
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
     * TP-015's fixture set: {@code unita} (present only so the {@code Provider}-broken re-entrant
     * cycle compiles at staging, R4) plus the single-proof
     * {@link dev.vertique.rest.jaxrs.application.unitb.ReentrantRegistrationModule
     * ReentrantRegistrationModule}.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unitb.ReentrantRegistrationModule.class
            })
    public interface ReentrantComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            ReentrantComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-016's fixture set: the standard fixture set plus the single-proof
     * {@link dev.vertique.rest.jaxrs.application.unitb.ThirdOverridingRegistrationModule
     * ThirdOverridingRegistrationModule}, so the registration set has three entries.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unitb.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unitb.ThirdOverridingRegistrationModule.class,
                ManualResourceModule.class
            })
    public interface ThreeRegistrationsComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            ThreeRegistrationsComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }
}
