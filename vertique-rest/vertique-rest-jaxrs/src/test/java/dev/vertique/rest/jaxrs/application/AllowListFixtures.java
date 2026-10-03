// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceEntry;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.openapi.docs.ApiDocs;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TP-013 and TP-014 fixtures: one declaring interface per
 * {@link ApplicationAnnotationBackstopTest} row, each registered by
 * {@link #component(GeneratedRestApplicationRegistration)} against one shared, always-enabled
 * resource ({@link AllowListResource}). Every row's registration is built inline by the test with
 * {@link GeneratedRestApplicationRegistration#of}, mirroring a hand-written registration a
 * processor never produced (the runtime allow-list re-check's whole purpose).
 */
public final class AllowListFixtures {

    private AllowListFixtures() {}

    /** Every fixture application's registration path. */
    static final String PATH = "/api/allowlist";

    /**
     * Returns how many {@link AllowListResource} instances have been constructed since the last
     * {@link #resetCounters()}.
     *
     * @return the resource construction count
     */
    static int resourceConstructions() {
        return AllowListResource.CONSTRUCTIONS.get();
    }

    /** Resets the shared construction counter to {@code 0}. */
    static void resetCounters() {
        AllowListResource.CONSTRUCTIONS.set(0);
    }

    /**
     * Builds a fresh {@link AllowListComponent} around one row's registration.
     *
     * @param registration the row's registration
     * @return the fresh component
     */
    static AllowListComponent component(GeneratedRestApplicationRegistration registration) {
        JsonObject config = new JsonObject().put("jaxrs", new JsonObject().put("validationStrategy", "none"));
        return DaggerAllowListFixtures_AllowListComponent.factory().create(config, registration);
    }

    // -----------------------------------------------------------------------------------------
    // Shared resource, one entry, always enabled.
    // -----------------------------------------------------------------------------------------

    /** The single resource every fixture application in this file lists. */
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

    /** The fixed, one-entry catalog module: catalogs {@link AllowListResource}, always enabled. */
    @Module
    static final class ResourceModule {

        private ResourceModule() {}

        @Provides
        @IntoSet
        static GeneratedJaxRsResourceEntry allowListResourceEntry(Provider<AllowListResource> provider) {
            return GeneratedJaxRsResourceEntry.of(AllowListResource.class, true, provider);
        }
    }

    /** Contributes the row-supplied registration into {@code Set<GeneratedRestApplicationRegistration>}. */
    @Module
    abstract static class RegistrationModule {

        private RegistrationModule() {}

        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration contributeRegistration(
                GeneratedRestApplicationRegistration registration) {
            return registration;
        }
    }

    /** The {@code @Singleton} component every TP-013/TP-014 row composes through. */
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
                    @BindsInstance GeneratedRestApplicationRegistration registration);
        }
    }

    // -----------------------------------------------------------------------------------------
    // TP-013 failing rows: one disallowed annotation on the declaring interface.
    // -----------------------------------------------------------------------------------------

    /** Row: {@code @RolesAllowed} on the declaring interface. */
    @RestApplication(name = "backstop-roles", path = PATH, resources = AllowListResource.class)
    @RolesAllowed("admin")
    public interface RolesOnDeclaringApi {}

    /** Row: {@code @Path} on the declaring interface. */
    @RestApplication(name = "backstop-path", path = PATH, resources = AllowListResource.class)
    @Path("/unexpected")
    public interface PathOnDeclaringApi {}

    /** Row: {@code @jakarta.inject.Singleton} on the declaring interface (scope, no longer exempt). */
    @RestApplication(name = "backstop-singleton", path = PATH, resources = AllowListResource.class)
    @Singleton
    public interface SingletonOnDeclaringApi {}

    /** Row: {@code @OpenAPIDefinition} with a non-{@code info} element set on the declaring interface. */
    @RestApplication(name = "backstop-openapi", path = PATH, resources = AllowListResource.class)
    @OpenAPIDefinition(servers = @Server(url = "https://zq7.example"))
    public interface OpenApiServersOnDeclaringApi {}

    // -----------------------------------------------------------------------------------------
    // TP-013 failing rows: a superinterface carrying @RolesAllowed, one and two levels up.
    // -----------------------------------------------------------------------------------------

    /** A direct superinterface carrying {@code @RolesAllowed}. */
    @RolesAllowed("admin")
    public interface DirectRolesSuperinterface {}

    /** Row: {@code @RolesAllowed} on a direct superinterface. */
    @RestApplication(name = "backstop-roles-super", path = PATH, resources = AllowListResource.class)
    public interface RolesOnDirectSuperinterfaceApi extends DirectRolesSuperinterface {}

    /** A superinterface two levels up, carrying {@code @RolesAllowed}. */
    @RolesAllowed("admin")
    public interface TwoLevelsUpRolesSuperinterface {}

    /** The intermediate, unannotated superinterface. */
    public interface MiddleSuperinterface extends TwoLevelsUpRolesSuperinterface {}

    /** Row: {@code @RolesAllowed} on a superinterface two levels up. */
    @RestApplication(name = "backstop-roles-super2", path = PATH, resources = AllowListResource.class)
    public interface RolesOnSuperinterfaceTwoLevelsUpApi extends MiddleSuperinterface {}

    // -----------------------------------------------------------------------------------------
    // TP-013 composing controls.
    // -----------------------------------------------------------------------------------------

    /** Control: {@code @RestApplication} alone. */
    @RestApplication(name = "backstop-control-plain", path = PATH, resources = AllowListResource.class)
    public interface PlainControlApi {}

    /** Control: an info-only {@code @OpenAPIDefinition}. */
    @RestApplication(name = "backstop-control-openapi", path = PATH, resources = AllowListResource.class)
    @OpenAPIDefinition(info = @Info(title = "t", version = "1"))
    public interface OpenApiInfoOnlyControlApi {}

    /** Control: the test-source {@code @ApiDocs}. */
    @RestApplication(name = "backstop-control-apidocs", path = PATH, resources = AllowListResource.class)
    @ApiDocs
    public interface ApiDocsControlApi {}

    /** Control: {@code @Deprecated} (a {@code java.lang} type). */
    @RestApplication(name = "backstop-control-deprecated", path = PATH, resources = AllowListResource.class)
    @Deprecated
    public interface DeprecatedControlApi {}

    // -----------------------------------------------------------------------------------------
    // TP-014 rows: a superinterface carrying an annotation honored only on the declaring interface.
    // -----------------------------------------------------------------------------------------

    /** A superinterface declaring a second application (honored only on the declaring interface). */
    @RestApplication(name = "other", path = "/other", discover = true)
    public interface OtherApplicationSuperinterface {}

    /** Row: {@code @RestApplication} on a superinterface. */
    @RestApplication(name = "backstop-other-super", path = PATH, resources = AllowListResource.class)
    public interface RestApplicationOnSuperinterfaceApi extends OtherApplicationSuperinterface {}

    /** A superinterface carrying the test-source {@code @ApiDocs}. */
    @ApiDocs
    public interface ApiDocsSuperinterface {}

    /** Row: the test-source {@code @ApiDocs} on a superinterface. */
    @RestApplication(name = "backstop-apidocs-super", path = PATH, resources = AllowListResource.class)
    public interface ApiDocsOnSuperinterfaceApi extends ApiDocsSuperinterface {}

    /** A superinterface carrying an info-only {@code @OpenAPIDefinition}. */
    @OpenAPIDefinition(info = @Info(title = "t", version = "1"))
    public interface OpenApiSuperinterface {}

    /** Row: {@code @OpenAPIDefinition} on a superinterface. */
    @RestApplication(name = "backstop-openapi-super", path = PATH, resources = AllowListResource.class)
    public interface OpenApiOnSuperinterfaceApi extends OpenApiSuperinterface {}

    // -----------------------------------------------------------------------------------------
    // TP-014 composing control.
    // -----------------------------------------------------------------------------------------

    /** A superinterface carrying only {@code @Deprecated}. */
    @Deprecated
    public interface DeprecatedSuperinterface {}

    /** Control: {@code @Deprecated} on a superinterface (a {@code java.lang} type, unrestricted). */
    @RestApplication(name = "backstop-deprecated-super", path = PATH, resources = AllowListResource.class)
    public interface DeprecatedOnSuperinterfaceApi extends DeprecatedSuperinterface {}

    /**
     * PIT G4: a runtime annotation type used as a superinterface, carrying only
     * {@code java.lang.annotation} types ({@code @Retention}, {@code @Target}, {@code @Documented}).
     * An annotation interface is the only reachable way to put a {@code java.lang.annotation} type in
     * scope, since every such annotation targets annotation types.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Documented
    public @interface MetaAnnotatedSuperinterface {}

    /** Control: {@code java.lang.annotation} types on a superinterface (unrestricted). */
    @RestApplication(name = "backstop-meta-super", path = PATH, resources = AllowListResource.class)
    public interface MetaAnnotatedSuperinterfaceApi extends MetaAnnotatedSuperinterface {}
}
