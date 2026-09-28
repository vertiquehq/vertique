// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.application.manual.MembershipAopProxyResourceModule;
import dev.vertique.rest.jaxrs.application.manual.MembershipClassLevelPermitAllResourceModule;
import dev.vertique.rest.jaxrs.application.manual.MembershipClassPathResourceModule;
import dev.vertique.rest.jaxrs.application.manual.MembershipGrandchildResourceModule;
import dev.vertique.rest.jaxrs.application.manual.MembershipNewInterfaceResourceModule;
import dev.vertique.rest.jaxrs.application.manual.MembershipOwnMethodResourceModule;
import dev.vertique.rest.jaxrs.application.manual.MembershipParamAnnotationOnlyResourceModule;
import dev.vertique.rest.jaxrs.application.manual.MembershipRolesAllowedResourceModule;
import dev.vertique.rest.jaxrs.application.manual.membership.DuplicateManualResourceModuleA;
import dev.vertique.rest.jaxrs.application.manual.membership.DuplicateManualResourceModuleB;
import dev.vertique.rest.jaxrs.application.unita.membership.AmbiguousResourceCatalogModule;
import dev.vertique.rest.jaxrs.application.unita.membership.Case21CatalogModule;
import dev.vertique.rest.jaxrs.application.unita.membership.Case21SubstitutionModule;
import dev.vertique.rest.jaxrs.application.unita.membership.Case22HandWrittenEntryModule;
import dev.vertique.rest.jaxrs.application.unita.membership.DuplicateCatalogEntryModuleA;
import dev.vertique.rest.jaxrs.application.unita.membership.DuplicateCatalogEntryModuleB;
import dev.vertique.rest.jaxrs.application.unita.membership.NullCatalogEntryModule;
import dev.vertique.rest.jaxrs.application.unitb.membership.Case21RegistrationModule;
import dev.vertique.rest.jaxrs.application.unitb.membership.Case22RegistrationModule;
import dev.vertique.rest.jaxrs.application.unitb.membership.DuplicateCatalogRegistrationModule;
import dev.vertique.rest.jaxrs.application.unitb.membership.MembershipViolationRegistrations;
import dev.vertique.rest.jaxrs.application.unitb.membership.NullCatalogEntryRegistrationModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger components for {@link JaxRsApplicationCompositionTest}'s TP-003 (membership violations)
 * cases and its accepted-row supports. Every registration is provided by
 * {@link MembershipViolationRegistrations}, each gated on its own property so exactly one row is
 * active per test invocation (or exactly two, for the two-applications row).
 *
 * <p>T023 L26 restoration: the seven surface-mismatch rows (each listing
 * {@code MembershipBaseResource} and gated on its own {@code membership.surface*.active} property)
 * and the AOP-shaped accepted row each need a manual candidate module no other row may see, so each
 * gets its own dedicated component ({@link #SurfaceOwnAnnotationComponent} and its seven siblings
 * below), never {@link StandardViolationComponent}. Wiring every surface candidate into one shared
 * component would let {@code MembershipAopProxyResourceModule}'s resource, which always satisfies
 * {@code sameSurface} against {@code MembershipBaseResource}, mask every mismatched-surface row's
 * intended failure.
 */
public final class MembershipComponents {

    private MembershipComponents() {}

    /** Provisions every membership-suite component exposes. */
    public interface Provisions {

        /**
         * Resolves the {@code Set<RouterMount>} multibinding, including {@code RestModule}'s
         * default JAX-RS mount provider.
         *
         * @return the resolved mount set
         */
        Set<RouterMount> routerMounts();
    }

    /**
     * TP-003's shared component: every row's registration (each property-gated, only the row under
     * test active), every unbound "kind check" and "no binding" type, the ambiguous catalog entry
     * and manual instance, the twice-manually-contributed duplicate-manual resource (case 13), and
     * {@code unita}'s catalog (for the disabled-catalog accepted row). {@code
     * DuplicateManualResourceModuleA} and {@code DuplicateManualResourceModuleB} contribute
     * manual-only instances, never a catalog entry, so their presence is harmless for every other
     * row: membership for a listed class is only evaluated when some active registration actually
     * lists it (AC-026.2).
     *
     * <p>Never wires any {@code MembershipBaseResource} manual candidate module: the seven
     * surface-mismatch rows and the AOP-shaped accepted row each need their own dedicated component
     * below, isolated from one another and from this component (T023 L26).
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                MembershipViolationRegistrations.class,
                dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule.class,
                AmbiguousResourceCatalogModule.class,
                dev.vertique.rest.jaxrs.application.manual.membership.AmbiguousResourceManualModule.class,
                DuplicateManualResourceModuleA.class,
                DuplicateManualResourceModuleB.class
            })
    public interface StandardViolationComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            StandardViolationComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-003 "surface: own annotation": {@link MembershipViolationRegistrations}'s registrations
     * (only {@code membership.surfaceOwnAnnotation.active} set), plus the sole manual candidate
     * {@code MembershipClassPathResource}, which adds a class-level annotation the listed
     * {@code MembershipBaseResource} does not declare, so {@code sameSurface} fails. Isolated from
     * every other surface component and from {@link StandardViolationComponent}: no other manual
     * candidate for {@code MembershipBaseResource} may be present, or it could satisfy {@code
     * sameSurface} instead and mask this row's intended failure.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                MembershipViolationRegistrations.class,
                MembershipClassPathResourceModule.class
            })
    public interface SurfaceOwnAnnotationComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            SurfaceOwnAnnotationComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-003 "surface: own method": {@link MembershipViolationRegistrations}'s registrations (only
     * {@code membership.surfaceOwnMethod.active} set), plus the sole manual candidate {@code
     * MembershipOwnMethodResource}, which declares a new resource method the listed {@code
     * MembershipBaseResource} does not declare, so {@code sameSurface} fails. Isolated as {@link
     * #SurfaceOwnAnnotationComponent} is.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                MembershipViolationRegistrations.class,
                MembershipOwnMethodResourceModule.class
            })
    public interface SurfaceOwnMethodComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            SurfaceOwnMethodComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-003 "surface: parameter annotation only": {@link MembershipViolationRegistrations}'s
     * registrations (only {@code membership.surfaceParamAnnotation.active} set), plus the sole
     * manual candidate {@code MembershipParamAnnotationOnlyResource}, which overrides a base method
     * and annotates only its parameter, so {@code sameSurface} fails. Isolated as {@link
     * #SurfaceOwnAnnotationComponent} is.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                MembershipViolationRegistrations.class,
                MembershipParamAnnotationOnlyResourceModule.class
            })
    public interface SurfaceParamAnnotationComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            SurfaceParamAnnotationComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-003 "surface: added interface": {@link MembershipViolationRegistrations}'s registrations
     * (only {@code membership.surfaceNewInterface.active} set), plus the sole manual candidate
     * {@code MembershipNewInterfaceResource}, which implements a new interface the listed {@code
     * MembershipBaseResource} does not implement, so {@code sameSurface} fails. Isolated as {@link
     * #SurfaceOwnAnnotationComponent} is.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                MembershipViolationRegistrations.class,
                MembershipNewInterfaceResourceModule.class
            })
    public interface SurfaceNewInterfaceComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            SurfaceNewInterfaceComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-003 "surface: grandchild": {@link MembershipViolationRegistrations}'s registrations (only
     * {@code membership.surfaceGrandchild.active} set), plus the sole manual candidate {@code
     * MembershipGrandchildResource}, a grandchild of the listed {@code MembershipBaseResource}
     * rather than a direct subclass, so {@code sameSurface} fails. Isolated as {@link
     * #SurfaceOwnAnnotationComponent} is.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                MembershipViolationRegistrations.class,
                MembershipGrandchildResourceModule.class
            })
    public interface SurfaceGrandchildComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            SurfaceGrandchildComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-003 "surface: class-level @PermitAll": {@link MembershipViolationRegistrations}'s
     * registrations (only {@code membership.surfaceClassLevelPermitAll.active} set), plus the sole
     * manual candidate {@code MembershipClassLevelPermitAllResource}, which carries a class-level
     * {@code @PermitAll} the listed {@code MembershipBaseResource} does not declare, so {@code
     * sameSurface} fails. Isolated as {@link #SurfaceOwnAnnotationComponent} is.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                MembershipViolationRegistrations.class,
                MembershipClassLevelPermitAllResourceModule.class
            })
    public interface SurfaceClassPermitAllComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            SurfaceClassPermitAllComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-003 "surface: @RolesAllowed": {@link MembershipViolationRegistrations}'s registrations
     * (only {@code membership.surfaceRolesAllowed.active} set), plus the sole manual candidate
     * {@code MembershipRolesAllowedResource}, whose override of the listed {@code
     * MembershipBaseResource}'s method carries {@code @RolesAllowed}, so {@code sameSurface} fails.
     * Isolated as {@link #SurfaceOwnAnnotationComponent} is.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                MembershipViolationRegistrations.class,
                MembershipRolesAllowedResourceModule.class
            })
    public interface SurfaceRolesAllowedComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            SurfaceRolesAllowedComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-003's accepted row: {@link MembershipViolationRegistrations}'s registrations (only {@code
     * membership.aopAccepted.active} set), plus the sole manual candidate {@code
     * MembershipAopProxyResource}, whose AOP-proxy shape {@code sameSurface} accepts. Isolated from
     * every surface-mismatch component: were it wired alongside any of them, its resource would
     * satisfy {@code sameSurface} for their listed {@code MembershipBaseResource} too, and mask
     * their intended failure.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                MembershipViolationRegistrations.class,
                MembershipAopProxyResourceModule.class
            })
    public interface AopAcceptedComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            AopAcceptedComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * T023 L22 restoration (TP-003 case 14): the dedicated
     * {@link DuplicateCatalogRegistrationModule} registration, plus two separate catalog-entry
     * modules for the same resource class, tripping the composer's step 1 duplicate-catalog-entry
     * check before any manual contribution or catalog entry resolves. Isolated from
     * {@link StandardViolationComponent}: a step 1 violation would fail every row sharing the
     * component.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                DuplicateCatalogRegistrationModule.class,
                DuplicateCatalogEntryModuleA.class,
                DuplicateCatalogEntryModuleB.class
            })
    public interface DuplicateCatalogEntryComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            DuplicateCatalogEntryComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * T023 L22 restoration (TP-003 case 21): the dedicated {@link Case21RegistrationModule}
     * registration, plus the substituted {@code Case21Resource} binding and its catalog entry.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                Case21RegistrationModule.class,
                Case21SubstitutionModule.class,
                Case21CatalogModule.class
            })
    public interface SubstitutedBindingComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            SubstitutedBindingComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * T023 L22 restoration (TP-003 case 22): the dedicated {@link Case22RegistrationModule}
     * registration, plus the hand-written {@code Case22Resource} entry whose provider returns an
     * unrelated instance.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                Case22RegistrationModule.class,
                Case22HandWrittenEntryModule.class
            })
    public interface HandWrittenEntryComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            HandWrittenEntryComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * T023 L22 restoration (G-07 (b)): the dedicated {@link NullCatalogEntryRegistrationModule}
     * registration, plus {@code NullCatalogEntryModule}, whose hand-written catalog entry's provider
     * always returns {@code null}.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                NullCatalogEntryRegistrationModule.class,
                NullCatalogEntryModule.class
            })
    public interface NullCatalogEntryComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            NullCatalogEntryComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }
}
