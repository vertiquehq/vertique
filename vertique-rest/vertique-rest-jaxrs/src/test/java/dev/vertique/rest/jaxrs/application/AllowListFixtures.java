// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.json.JsonProfile;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceEntry;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.MediaType;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TP-003 fixtures: every hand-written {@code jakarta.ws.rs.core.Application} hierarchy shape
 * {@link ApplicationAnnotationBackstopTest}'s 14 named rows drive through the composer's step 1a
 * backstop, plus the single {@link AllowListComponent} that composes each one against one shared,
 * always-enabled resource ({@link AllowListResource}).
 *
 * <p>Every application below overrides {@link Application#getClasses()} to select {@link
 * AllowListResource} and increments the shared {@link #APPLICATION_CONSTRUCTIONS} counter on
 * construction, so a row composes cleanly apart from its annotations (TP-003 "Given") until the
 * backstop rejects it. {@link #resetCounters()} must run before every row ({@code @BeforeEach} in
 * the test class).
 *
 * <p>{@link AllowListComponent} is built fresh per row by {@link #component(Class, boolean)}: its
 * {@link ResourceModule} is the fixed, one-entry generated-catalog shape ({@link
 * dev.vertique.rest.jaxrs.application.unita.GeneratedJaxRsResourcesModule unita}'s catalog-entry
 * pattern), and its {@link RegistrationModule} contributes the single, row-specific {@link
 * GeneratedJaxRsApplicationRegistration} the caller builds with {@link
 * GeneratedJaxRsApplicationRegistration#of}, exactly as a generated registration method would
 * ({@code unitb}'s {@code GeneratedJaxRsResourcesModule}) — bound in as a plain {@code
 * @BindsInstance} value and contributed {@code @IntoSet} because each row needs a different
 * application type, unlike a real generated module's fixed set of {@code @Provides} methods.
 */
public final class AllowListFixtures {

    private AllowListFixtures() {}

    /** Every fixture application's registration path: {@code "/api/allowlist"}. */
    static final String PATH = "/api/allowlist";

    /** Counts every fixture application's construction, across every row, reset by {@link #resetCounters()}. */
    private static final AtomicInteger APPLICATION_CONSTRUCTIONS = new AtomicInteger();

    /**
     * Increments the shared application construction counter. Called by every fixture
     * application's constructor.
     */
    private static void countApplicationConstruction() {
        APPLICATION_CONSTRUCTIONS.incrementAndGet();
    }

    /**
     * Returns how many fixture application instances have been constructed since the last {@link
     * #resetCounters()}.
     *
     * @return the application construction count
     */
    static int applicationConstructions() {
        return APPLICATION_CONSTRUCTIONS.get();
    }

    /**
     * Returns how many {@link AllowListResource} instances have been constructed since the last
     * {@link #resetCounters()}.
     *
     * @return the resource construction count
     */
    static int resourceConstructions() {
        return AllowListResource.CONSTRUCTIONS.get();
    }

    /** Resets both shared construction counters to {@code 0}. */
    static void resetCounters() {
        APPLICATION_CONSTRUCTIONS.set(0);
        AllowListResource.CONSTRUCTIONS.set(0);
    }

    /**
     * Builds a fresh {@link AllowListComponent} around one row: {@code applicationType} registered
     * at {@link #PATH}, active per {@code active}, constructed by reflection through its public
     * no-argument constructor (standing in for the generated {@code A::new}/{@code Provider}
     * factory shape, generalized across 14 distinct row types).
     *
     * @param applicationType the row's application type
     * @param active          whether the registration is active
     * @return the fresh component
     */
    static AllowListComponent component(Class<? extends Application> applicationType, boolean active) {
        JsonObject config = new JsonObject().put("jaxrs", new JsonObject().put("validationStrategy", "none"));
        GeneratedJaxRsApplicationRegistration registration =
                GeneratedJaxRsApplicationRegistration.of(applicationType, PATH, active, provider(applicationType));
        return DaggerAllowListFixtures_AllowListComponent.factory().create(config, registration);
    }

    /**
     * Returns a {@link Provider} that constructs {@code applicationType} through its public
     * no-argument constructor, standing in for the generated {@code A::new} factory reference for a
     * type that varies per row.
     *
     * @param applicationType the type to construct
     * @return the constructing provider
     */
    private static Provider<Application> provider(Class<? extends Application> applicationType) {
        return () -> {
            try {
                return applicationType.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(
                        "fixture " + applicationType.getName() + " must have a public no-argument constructor", e);
            }
        };
    }

    // -----------------------------------------------------------------------------------------
    // Shared resource, one entry, always enabled.
    // -----------------------------------------------------------------------------------------

    /**
     * The single resource every fixture application in this file selects via {@code getClasses()}.
     * Counts its own construction in {@link #CONSTRUCTIONS}, reset by {@link
     * AllowListFixtures#resetCounters()}.
     */
    @Path("/items")
    public static class AllowListResource {

        /** Construction count; reset before every row via {@link AllowListFixtures#resetCounters()}. */
        static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

        /** Counts this construction. */
        @Inject
        public AllowListResource() {
            CONSTRUCTIONS.incrementAndGet();
        }

        /**
         * Handles {@code GET /items}.
         *
         * @return the fixed body {@code "items"}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String items() {
            return "items";
        }
    }

    /**
     * The fixed, one-entry generated-catalog-shaped resource module: catalogs {@link
     * AllowListResource}, always enabled, mirroring {@code unita}'s {@code
     * GeneratedJaxRsResourcesModule}.
     */
    @Module
    static final class ResourceModule {

        private ResourceModule() {}

        /**
         * Catalogs {@link AllowListResource} for explicit-mode selection, always enabled.
         *
         * @param provider lazily constructs {@link AllowListResource}
         * @return the catalog entry
         */
        @Provides
        @IntoSet
        static GeneratedJaxRsResourceEntry allowListResourceEntry(Provider<AllowListResource> provider) {
            return GeneratedJaxRsResourceEntry.of(AllowListResource.class, true, provider);
        }
    }

    /**
     * The generated-catalog-shaped registration module: contributes the single, row-specific {@link
     * GeneratedJaxRsApplicationRegistration} {@link AllowListComponent.Factory#create} binds in,
     * mirroring {@code unitb}'s per-application {@code @Provides @IntoSet} registration methods,
     * generalized to one row-supplied instance instead of a fixed set of applications.
     */
    @Module
    abstract static class RegistrationModule {

        private RegistrationModule() {}

        /**
         * Contributes the bound registration into {@code Set<GeneratedJaxRsApplicationRegistration>}.
         *
         * @param registration the row's registration, bound by {@link
         *                     AllowListComponent.Factory#create}
         * @return {@code registration}, contributed {@code @IntoSet}
         */
        @Provides
        @IntoSet
        static GeneratedJaxRsApplicationRegistration contributeRegistration(
                GeneratedJaxRsApplicationRegistration registration) {
            return registration;
        }
    }

    /**
     * The {@code @Singleton} component every TP-003 row composes through: {@link RestModule}, the
     * shared {@link ApplicationTestSupportModule}, {@link ResourceModule}, and {@link
     * RegistrationModule}.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                ResourceModule.class,
                RegistrationModule.class
            })
    public interface AllowListComponent {

        /**
         * Resolves the {@code Set<RouterMount>} multibinding, fresh for this call.
         *
         * @return the resolved mount set
         */
        Set<RouterMount> routerMounts();

        /** Factory taking the application configuration and the row's registration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration and registration.
             *
             * @param config       the application configuration
             * @param registration the row's registration, contributed by {@link RegistrationModule}
             * @return the constructed component
             */
            AllowListComponent create(
                    @BindsInstance @VertxConfig JsonObject config,
                    @BindsInstance GeneratedJaxRsApplicationRegistration registration);
        }
    }

    // -----------------------------------------------------------------------------------------
    // TP-001/TP-002 row 8's runtime-retained, fixture-declared stand-in for a sibling framework
    // module's audit annotation; open core neither depends on nor names that module.
    // -----------------------------------------------------------------------------------------

    /**
     * Stands in for a sibling framework module's audit annotation: a {@code RUNTIME}-retained,
     * {@code TYPE}-target annotation the allow list must reject like any other annotation that is
     * not on the list.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface AuditStandIn {}

    // -----------------------------------------------------------------------------------------
    // Shared hierarchy types for the superclass/interface/superinterface rows.
    // -----------------------------------------------------------------------------------------

    /** Row "roles-on-abstract-superclass"'s abstract {@code Application} superclass. */
    @RolesAllowed("admin")
    abstract static class GuardedSuperclassApplication extends Application {

        /** Package-private no-argument constructor, invoked only via {@code super()}. */
        GuardedSuperclassApplication() {}
    }

    /** Row "roles-on-interface"'s directly-implemented, annotated interface. */
    @RolesAllowed("admin")
    interface GuardedInterface {}

    /** Row "roles-on-superinterface"'s directly-implemented interface, itself unannotated. */
    interface MiddleInterface extends AnnotatedRootInterface {}

    /** Row "roles-on-superinterface"'s annotated superinterface. */
    @RolesAllowed("admin")
    interface AnnotatedRootInterface {}

    /** Row "application-path-only-on-interface"'s sole {@code @ApplicationPath} carrier. */
    @ApplicationPath("/api/allowlist-path-contract")
    interface PathContract {}

    // -----------------------------------------------------------------------------------------
    // The 10 active failing rows.
    // -----------------------------------------------------------------------------------------

    /** Row "roles-on-application": {@code @RolesAllowed} directly on the concrete application. */
    @ApplicationPath("/api/allowlist")
    @RolesAllowed("admin")
    public static class RolesOnApplicationApplication extends Application {

        /** Counts this construction. */
        public RolesOnApplicationApplication() {
            countApplicationConstruction();
        }

        /**
         * Selects {@link AllowListResource}.
         *
         * @return a singleton set containing {@link AllowListResource}
         */
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(AllowListResource.class);
        }
    }

    /** Row "roles-on-abstract-superclass": {@code @RolesAllowed} on {@link GuardedSuperclassApplication}. */
    @ApplicationPath("/api/allowlist")
    public static class RolesOnAbstractSuperclassApplication extends GuardedSuperclassApplication {

        /** Counts this construction. */
        public RolesOnAbstractSuperclassApplication() {
            countApplicationConstruction();
        }

        /**
         * Selects {@link AllowListResource}.
         *
         * @return a singleton set containing {@link AllowListResource}
         */
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(AllowListResource.class);
        }
    }

    /** Row "roles-on-interface": {@code @RolesAllowed} on {@link GuardedInterface}. */
    @ApplicationPath("/api/allowlist")
    public static class RolesOnInterfaceApplication extends Application implements GuardedInterface {

        /** Counts this construction. */
        public RolesOnInterfaceApplication() {
            countApplicationConstruction();
        }

        /**
         * Selects {@link AllowListResource}.
         *
         * @return a singleton set containing {@link AllowListResource}
         */
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(AllowListResource.class);
        }
    }

    /** Row "roles-on-superinterface": {@code @RolesAllowed} on {@link AnnotatedRootInterface}. */
    @ApplicationPath("/api/allowlist")
    public static class RolesOnSuperinterfaceApplication extends Application implements MiddleInterface {

        /** Counts this construction. */
        public RolesOnSuperinterfaceApplication() {
            countApplicationConstruction();
        }

        /**
         * Selects {@link AllowListResource}.
         *
         * @return a singleton set containing {@link AllowListResource}
         */
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(AllowListResource.class);
        }
    }

    /** Row "permitall": {@code @PermitAll} directly on the concrete application. */
    @ApplicationPath("/api/allowlist")
    @PermitAll
    public static class PermitAllApplication extends Application {

        /** Counts this construction. */
        public PermitAllApplication() {
            countApplicationConstruction();
        }

        /**
         * Selects {@link AllowListResource}.
         *
         * @return a singleton set containing {@link AllowListResource}
         */
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(AllowListResource.class);
        }
    }

    /** Row "security-requirement": Swagger's {@code @SecurityRequirement} directly on the concrete application. */
    @ApplicationPath("/api/allowlist")
    @SecurityRequirement(name = "api-key")
    public static class SecurityRequirementApplication extends Application {

        /** Counts this construction. */
        public SecurityRequirementApplication() {
            countApplicationConstruction();
        }

        /**
         * Selects {@link AllowListResource}.
         *
         * @return a singleton set containing {@link AllowListResource}
         */
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(AllowListResource.class);
        }
    }

    /** Row "json-profile": {@code @JsonProfile} directly on the concrete application. */
    @ApplicationPath("/api/allowlist")
    @JsonProfile("default")
    public static class JsonProfileApplication extends Application {

        /** Counts this construction. */
        public JsonProfileApplication() {
            countApplicationConstruction();
        }

        /**
         * Selects {@link AllowListResource}.
         *
         * @return a singleton set containing {@link AllowListResource}
         */
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(AllowListResource.class);
        }
    }

    /** Row "audit-stand-in": {@link AuditStandIn} directly on the concrete application. */
    @ApplicationPath("/api/allowlist")
    @AuditStandIn
    public static class AuditStandInApplication extends Application {

        /** Counts this construction. */
        public AuditStandInApplication() {
            countApplicationConstruction();
        }

        /**
         * Selects {@link AllowListResource}.
         *
         * @return a singleton set containing {@link AllowListResource}
         */
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(AllowListResource.class);
        }
    }

    /**
     * Row "application-path-only-on-interface": {@code @ApplicationPath} only on {@link
     * PathContract}, none in this class's own superclass chain (the "only on an interface" shape).
     */
    public static class ApplicationPathOnlyOnInterfaceApplication extends Application implements PathContract {

        /** Counts this construction. */
        public ApplicationPathOnlyOnInterfaceApplication() {
            countApplicationConstruction();
        }

        /**
         * Selects {@link AllowListResource}.
         *
         * @return a singleton set containing {@link AllowListResource}
         */
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(AllowListResource.class);
        }
    }

    /**
     * Row "openapi-tags-non-default": {@code @OpenAPIDefinition} with a non-default {@code tags}
     * element directly on the concrete application.
     */
    @ApplicationPath("/api/allowlist")
    @OpenAPIDefinition(info = @Info(title = "t", version = "1"), tags = @Tag(name = "x"))
    public static class OpenApiTagsNonDefaultApplication extends Application {

        /** Counts this construction. */
        public OpenApiTagsNonDefaultApplication() {
            countApplicationConstruction();
        }

        /**
         * Selects {@link AllowListResource}.
         *
         * @return a singleton set containing {@link AllowListResource}
         */
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(AllowListResource.class);
        }
    }

    // Row "inactive-roles-on-application" reuses RolesOnApplicationApplication with active=false;
    // it needs no fixture class of its own.

    // -----------------------------------------------------------------------------------------
    // The 3 composing rows.
    // -----------------------------------------------------------------------------------------

    /**
     * Row "allow-listed": {@code @ApplicationPath}, {@code @Singleton}, {@code @Named}, {@code
     * @Deprecated}, and an info-only {@code @OpenAPIDefinition} — every element allow-listed.
     */
    @ApplicationPath("/api/allowlist")
    @Singleton
    @Named
    @Deprecated
    @OpenAPIDefinition(info = @Info(title = "t", version = "1"))
    public static class AllowListedApplication extends Application {

        /** Counts this construction. */
        public AllowListedApplication() {
            countApplicationConstruction();
        }

        /**
         * Selects {@link AllowListResource}.
         *
         * @return a singleton set containing {@link AllowListResource}
         */
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(AllowListResource.class);
        }
    }

    /**
     * Row "openapi-tags-empty": like {@link AllowListedApplication}, plus {@code tags = {}}, which
     * equals {@code @OpenAPIDefinition}'s declared default.
     */
    @ApplicationPath("/api/allowlist")
    @Singleton
    @Named
    @Deprecated
    @OpenAPIDefinition(
            info = @Info(title = "t", version = "1"),
            tags = {})
    public static class OpenApiTagsEmptyApplication extends Application {

        /** Counts this construction. */
        public OpenApiTagsEmptyApplication() {
            countApplicationConstruction();
        }

        /**
         * Selects {@link AllowListResource}.
         *
         * @return a singleton set containing {@link AllowListResource}
         */
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(AllowListResource.class);
        }
    }

    /**
     * Row "member-annotation-control": {@code @ApplicationPath} on the class, {@code @RolesAllowed}
     * on the overriding {@code getClasses()} method. Member annotations have no effect on an
     * application and are not checked.
     */
    @ApplicationPath("/api/allowlist")
    public static class MemberAnnotationControlApplication extends Application {

        /** Counts this construction. */
        public MemberAnnotationControlApplication() {
            countApplicationConstruction();
        }

        /**
         * Selects {@link AllowListResource}.
         *
         * @return a singleton set containing {@link AllowListResource}
         */
        @RolesAllowed("admin")
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(AllowListResource.class);
        }
    }
}
