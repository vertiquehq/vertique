// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.application.manual.MembershipAopProxyResourceModule;
import dev.vertique.rest.jaxrs.application.manual.MembershipClassPathResourceModule;
import dev.vertique.rest.jaxrs.application.manual.MembershipGrandchildResourceModule;
import dev.vertique.rest.jaxrs.application.manual.MembershipNewInterfaceResourceModule;
import dev.vertique.rest.jaxrs.application.manual.MembershipOwnMethodResourceModule;
import dev.vertique.rest.jaxrs.application.manual.MembershipRolesAllowedResourceModule;
import dev.vertique.rest.jaxrs.application.manual.membership.AmbiguousResourceManualModule;
import dev.vertique.rest.jaxrs.application.manual.membership.DuplicateManualResourceModuleA;
import dev.vertique.rest.jaxrs.application.manual.membership.DuplicateManualResourceModuleB;
import dev.vertique.rest.jaxrs.application.unita.membership.AmbiguousResourceCatalogModule;
import dev.vertique.rest.jaxrs.application.unita.membership.Case21CatalogModule;
import dev.vertique.rest.jaxrs.application.unita.membership.Case21SubstitutionModule;
import dev.vertique.rest.jaxrs.application.unita.membership.Case22HandWrittenEntryModule;
import dev.vertique.rest.jaxrs.application.unita.membership.DuplicateCatalogEntryModuleA;
import dev.vertique.rest.jaxrs.application.unita.membership.DuplicateCatalogEntryModuleB;
import dev.vertique.rest.jaxrs.application.unitb.membership.MembershipCaseApplicationRegistrationModule;
import dev.vertique.rest.jaxrs.application.unitb.membership.MembershipDuplicateRegistrationModuleA;
import dev.vertique.rest.jaxrs.application.unitb.membership.MembershipDuplicateRegistrationModuleB;
import dev.vertique.rest.jaxrs.application.unitb.membership.MembershipMismatchedFactoryRegistrationModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger components for {@link JaxRsApplicationCompositionTest}'s TP-005 (membership violations)
 * and TP-018 (AOP-proxy-shaped manual match) cases. Every component's factory takes the application
 * configuration as a {@code @BindsInstance @VertxConfig JsonObject}, matching
 * {@link CompositionComponents}. These fixtures are self-contained: none of L01's or L02's shared
 * {@code unita}, {@code unitb}, or {@code manual} modules are included, so this suite never shares
 * component wiring with {@link JaxRsApplicationCompositionTest}'s other proofs.
 *
 * <p>Twelve of TP-005's 22 cases (1 to 7, 9 to 13) share {@link StandardViolationComponent}: they
 * differ only in {@link dev.vertique.rest.jaxrs.application.unitb.membership.MembershipCaseApplication}'s
 * runtime-configured {@code classesSupplier}/{@code singletonsSupplier}, never in module wiring.
 * Cases 8, 14, 16, 21, and 22, and the five non-matching manual-subclass cases (15, 17 to 20), each
 * need module wiring no other case may see (an extra duplicate registration or catalog entry, or the
 * single non-matching manual candidate required for C-COMPOSE step 6.6's "the only candidate"
 * naming), so each gets its own dedicated component. TP-018 gets its own component for the same
 * reason: it must be the only manual candidate present.
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
     * TP-005 cases 1 to 7 and 9 to 13: a single {@code MembershipCaseApplication} registration, plus
     * case 6's ambiguous catalog-and-manual resource and case 13's twice-manually-contributed
     * resource. Neither extra fixture affects the other cases: both remain unselected (hence never
     * constructed, D002) unless a case's {@code classesSupplier} lists them.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                MembershipCaseApplicationRegistrationModule.class,
                AmbiguousResourceCatalogModule.class,
                AmbiguousResourceManualModule.class,
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
     * TP-005 case 8: two separate registration modules for the same
     * {@code MembershipCaseApplication} class, and nothing else — never combined with
     * {@link MembershipCaseApplicationRegistrationModule}'s single registration.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                MembershipDuplicateRegistrationModuleA.class,
                MembershipDuplicateRegistrationModuleB.class
            })
    public interface DuplicateRegistrationComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            DuplicateRegistrationComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-005 case 14: the standard single {@code MembershipCaseApplication} registration, plus two
     * separate catalog-entry modules for the same resource class.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                MembershipCaseApplicationRegistrationModule.class,
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
     * TP-005 case 16: a registration declaring {@code MembershipDeclaredApplication} whose factory
     * constructs and returns an unrelated {@code MembershipWrongTypeApplication} instance.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                MembershipMismatchedFactoryRegistrationModule.class
            })
    public interface MismatchedFactoryComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            MismatchedFactoryComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-005 case 21: the standard single {@code MembershipCaseApplication} registration, plus the
     * substituted {@code Case21Resource} binding and its catalog entry.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                MembershipCaseApplicationRegistrationModule.class,
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
     * TP-005 case 22: the standard single {@code MembershipCaseApplication} registration, plus the
     * hand-written {@code Case22Resource} entry whose provider returns an unrelated instance.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                MembershipCaseApplicationRegistrationModule.class,
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
     * TP-005 case 15: the standard single {@code MembershipCaseApplication} registration, plus the
     * sole manual candidate {@code MembershipOwnMethodResource}.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                MembershipCaseApplicationRegistrationModule.class,
                MembershipOwnMethodResourceModule.class
            })
    public interface SubclassOwnMethodComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            SubclassOwnMethodComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-005 case 17: the standard single {@code MembershipCaseApplication} registration, plus the
     * sole manual candidate {@code MembershipClassPathResource}.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                MembershipCaseApplicationRegistrationModule.class,
                MembershipClassPathResourceModule.class
            })
    public interface SubclassClassPathComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            SubclassClassPathComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-005 case 18: the standard single {@code MembershipCaseApplication} registration, plus the
     * sole manual candidate {@code MembershipNewInterfaceResource}.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                MembershipCaseApplicationRegistrationModule.class,
                MembershipNewInterfaceResourceModule.class
            })
    public interface SubclassNewInterfaceComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            SubclassNewInterfaceComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-005 case 19: the standard single {@code MembershipCaseApplication} registration, plus the
     * sole manual candidate {@code MembershipGrandchildResource}.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                MembershipCaseApplicationRegistrationModule.class,
                MembershipGrandchildResourceModule.class
            })
    public interface SubclassGrandchildComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            SubclassGrandchildComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-005 case 20: the standard single {@code MembershipCaseApplication} registration, plus the
     * sole manual candidate {@code MembershipRolesAllowedResource}.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                MembershipCaseApplicationRegistrationModule.class,
                MembershipRolesAllowedResourceModule.class
            })
    public interface SubclassRolesAllowedComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            SubclassRolesAllowedComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * TP-018: the standard single {@code MembershipCaseApplication} registration, plus the sole
     * manual candidate {@code MembershipAopProxyResource}, which C-COMPOSE's {@code sameSurface}
     * accepts.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                MembershipCaseApplicationRegistrationModule.class,
                MembershipAopProxyResourceModule.class
            })
    public interface AopProxyMatchComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            AopProxyMatchComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }
}
