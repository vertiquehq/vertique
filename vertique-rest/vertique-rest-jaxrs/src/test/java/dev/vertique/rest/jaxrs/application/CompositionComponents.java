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

    /**
     * G-02 (a) and (b): only the single-proof
     * {@link dev.vertique.rest.jaxrs.application.unitb.ThrowingConstructorRegistrationModule
     * ThrowingConstructorRegistrationModule}, so
     * {@link dev.vertique.rest.jaxrs.application.unitb.ThrowingConstructorApplication
     * ThrowingConstructorApplication} is the sole registration.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unitb.ThrowingConstructorRegistrationModule.class
            })
    public interface ThrowingConstructorComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            ThrowingConstructorComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * G-02 (c) and (d): only the single-proof
     * {@link dev.vertique.rest.jaxrs.application.unitb.ThrowingGetSingletonsRegistrationModule
     * ThrowingGetSingletonsRegistrationModule}, so
     * {@link dev.vertique.rest.jaxrs.application.unitb.ThrowingGetSingletonsApplication
     * ThrowingGetSingletonsApplication} is the sole registration.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unitb.ThrowingGetSingletonsRegistrationModule.class
            })
    public interface ThrowingGetSingletonsComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            ThrowingGetSingletonsComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * G-04 (a): {@code unita} (for {@link dev.vertique.rest.jaxrs.application.unita.CatalogResource
     * CatalogResource} and {@link dev.vertique.rest.jaxrs.application.unita.ExtraResource
     * ExtraResource}'s catalog entries) plus the single-proof
     * {@link dev.vertique.rest.jaxrs.application.unitb.InheritingApplicationRegistrationModule
     * InheritingApplicationRegistrationModule}, so
     * {@link dev.vertique.rest.jaxrs.application.unitb.InheritingApplication InheritingApplication}
     * is the sole registration: if it were misclassified as discovery, both catalog entries would be
     * selected instead of only the one its inherited {@code getClasses()} lists.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule.class,
                dev.vertique.rest.jaxrs.application.unitb.InheritingApplicationRegistrationModule.class
            })
    public interface InheritingApplicationComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            InheritingApplicationComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * G-04 (b): only the single-proof
     * {@link dev.vertique.rest.jaxrs.application.unitb.SingletonsOnlyOverridingRegistrationModule
     * SingletonsOnlyOverridingRegistrationModule}, so
     * {@link dev.vertique.rest.jaxrs.application.unitb.SingletonsOnlyOverridingApplication
     * SingletonsOnlyOverridingApplication} is the sole registration.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unitb.SingletonsOnlyOverridingRegistrationModule.class
            })
    public interface SingletonsOnlyComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            SingletonsOnlyComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * G-06 (a): zero declarations (no registration module at all) plus the single-proof
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
     * G-06 (b): {@code unitb} (for
     * {@link dev.vertique.rest.jaxrs.application.unitb.PublicApplication PublicApplication}, whose
     * configurable {@code getClasses()} lists {@link ReentrantResourceModule}'s resource) plus the
     * single-proof {@link ReentrantResourceModule}, so the composer's step 4 manual-resource
     * resolution (not the zero-declaration body) is the one that re-enters.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                dev.vertique.rest.jaxrs.application.unitb.GeneratedJaxRsResourcesModule.class,
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
     * W-1 (round 2): zero declarations (no registration module at all) plus the single-proof
     * {@link NestedCompositionResourceModule}, whose {@link
     * dev.vertique.rest.jaxrs.application.manual.NestedCompositionResource
     * NestedCompositionResource} builds and resolves a SECOND, independent {@link
     * ZeroDeclarationComponent} of its own inside its own construction. Resolving THIS
     * component's {@code Set<RouterMount>} must succeed: a nested composition of a different
     * component is legitimate, unlike {@link ReentrantResourceZeroDeclarationComponent}'s
     * same-component re-entry (G-06).
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
