// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.ApplicationMountTestAccess;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.application.view.ViewModuleA;
import dev.vertique.rest.jaxrs.application.view.ViewModuleB;
import dev.vertique.rest.jaxrs.application.view.ViewModuleC;
import dev.vertique.rest.jaxrs.application.view.ViewModuleD;
import dev.vertique.rest.jaxrs.publication.RestApplications;
import dev.vertique.rest.jaxrs.publication.RestApplications.ContractOrigin;
import dev.vertique.rest.jaxrs.publication.RestApplications.Entry;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves TP-006: {@link RestApplications} decides each declared application's identity, mount
 * path, and effective OpenAPI contract location once per component — from the highest-precedence
 * source available (configuration, then the declaration's own annotation, then the global
 * default) — and resolves to the same instance on every subsequent resolution.
 *
 * <p>Application {@code c}'s path {@code "/"} conflicts with the active {@code a} and {@code b}
 * under rest-024's kept path-conflict rule (L00 ruling E1), so only the view — never {@code
 * Set<RouterMount>} — is resolved on {@link ViewComponent1}, which declares all four applications.
 * The per-mount contract-location assertion instead runs on {@link ViewComponent2} ({@code a},
 * {@code b}, {@code d}) and {@link ViewComponent3} ({@code c}, {@code d}), neither of which
 * conflicts.
 */
class RestApplicationsTest {

    @Test
    @DisplayName("The view lists every declared application's identity, mount path, effective OpenAPI contract"
            + " location, and contract origin, and resolves to the same instance on every resolution")
    void viewCarriesEachApplicationsDecisions() {
        JsonObject configOne = configWith(applicationsObject(Map.of(
                "a", new JsonObject().put("openapiPath", "a-config.yaml"),
                "c", new JsonObject(),
                "d", new JsonObject().putNull("openapiPath"))));
        ViewComponent1 componentOne =
                DaggerRestApplicationsTest_ViewComponent1.factory().create(configOne);

        RestApplications firstResolution = componentOne.restApplications();
        RestApplications secondResolution = componentOne.restApplications();
        assertSame(firstResolution, secondResolution, "the view resolves to the same instance every time");

        assertEquals(
                List.of("a", "b", "c", "d"),
                firstResolution.all().stream().map(Entry::name).toList(),
                "all() lists every declared application, active or not, in declaration order");
        assertEquals(
                List.of(true, true, true, false),
                firstResolution.all().stream().map(Entry::active).toList(),
                "active flags: a, b, and c are active; d is not");
        assertEquals(
                List.of("/api/a/*", "/api/b/*", "/*", "/api/d/*"),
                firstResolution.all().stream().map(Entry::mountPath).toList(),
                "mountPath() is the registered path plus '/*', or '/*' alone for the root path");
        assertEquals(
                List.of("a-config.yaml", "b.yaml", "global.yaml", "d.yaml"),
                firstResolution.all().stream().map(Entry::effectiveOpenapiPath).toList(),
                "effectiveOpenapiPath() precedence: configuration, then the annotation, then the global default");
        assertEquals(
                List.of(
                        ContractOrigin.CONFIGURATION,
                        ContractOrigin.ANNOTATION,
                        ContractOrigin.GLOBAL,
                        ContractOrigin.ANNOTATION),
                firstResolution.all().stream().map(Entry::contractOrigin).toList(),
                "contractOrigin() names the source that decided each application's effective location");

        assertTrue(firstResolution.byName("b").isPresent(), "byName finds a declared application by name");
        assertEquals("b", firstResolution.byName("b").orElseThrow().name());
        assertTrue(firstResolution.byName("zz").isEmpty(), "byName is empty for an undeclared name");

        JsonObject configTwo = configWith(applicationsObject(Map.of(
                "a", new JsonObject().put("openapiPath", "a-config.yaml"),
                "d", new JsonObject().putNull("openapiPath"))));
        ViewComponent2 componentTwo =
                DaggerRestApplicationsTest_ViewComponent2.factory().create(configTwo);
        assertActiveMountsMatchTheView(componentTwo.restApplications(), componentTwo.routerMounts());

        JsonObject configThree = configWith(
                applicationsObject(Map.of("c", new JsonObject(), "d", new JsonObject().putNull("openapiPath"))));
        ViewComponent3 componentThree =
                DaggerRestApplicationsTest_ViewComponent3.factory().create(configThree);
        assertActiveMountsMatchTheView(componentThree.restApplications(), componentThree.routerMounts());
    }

    /**
     * Asserts every active application mount's {@code meta().openapiPath()} equals the matching
     * view entry's {@code effectiveOpenapiPath()}; {@code d} never appears since it is always
     * inactive and mounts nothing.
     *
     * @param view   the resolved view
     * @param mounts the resolved mount set
     */
    private static void assertActiveMountsMatchTheView(RestApplications view, Set<RouterMount> mounts) {
        assertFalse(mounts.isEmpty(), "at least one active application must mount");
        for (RouterMount mount : mounts) {
            JaxRsRouterMount jaxRsMount = assertInstanceOf(JaxRsRouterMount.class, mount);
            String name = ApplicationMountTestAccess.applicationName(jaxRsMount);
            Entry entry =
                    view.byName(name).orElseThrow(() -> new AssertionError("mount '" + name + "' is not in the view"));
            assertEquals(
                    entry.effectiveOpenapiPath(),
                    jaxRsMount.meta().openapiPath(),
                    () -> "mount '" + name + "'s openapiPath must equal the view's effectiveOpenapiPath()");
        }
    }

    private static JsonObject applicationsObject(Map<String, JsonObject> entries) {
        JsonObject applications = new JsonObject();
        entries.forEach(applications::put);
        return applications;
    }

    private static JsonObject configWith(JsonObject applications) {
        return new JsonObject()
                .put("jaxrs", new JsonObject().put("openapiPath", "global.yaml").put("applications", applications));
    }

    // --- Dagger components ---

    /**
     * Declares all four applications {@code a}, {@code b}, {@code c}, and {@code d}. {@code c}'s
     * root path conflicts with {@code a} and {@code b}, so only {@link #restApplications()} is
     * resolved on this component (L00 ruling E1).
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                ViewModuleA.class,
                ViewModuleB.class,
                ViewModuleC.class,
                ViewModuleD.class
            })
    interface ViewComponent1 {

        /**
         * Resolves the {@code RestApplications} view.
         *
         * @return the resolved view
         */
        RestApplications restApplications();

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            ViewComponent1 create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * Declares {@code a}, {@code b}, and {@code d} — no path conflict, so both the view and
     * {@code Set<RouterMount>} resolve.
     */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                ViewModuleA.class,
                ViewModuleB.class,
                ViewModuleD.class
            })
    interface ViewComponent2 {

        /**
         * Resolves the {@code RestApplications} view.
         *
         * @return the resolved view
         */
        RestApplications restApplications();

        /**
         * Resolves the {@code Set<RouterMount>} multibinding.
         *
         * @return the resolved mount set
         */
        Set<RouterMount> routerMounts();

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            ViewComponent2 create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /** Declares {@code c} and {@code d} — no path conflict, so both the view and mounts resolve. */
    @Singleton
    @Component(modules = {RestModule.class, ApplicationTestSupportModule.class, ViewModuleC.class, ViewModuleD.class})
    interface ViewComponent3 {

        /**
         * Resolves the {@code RestApplications} view.
         *
         * @return the resolved view
         */
        RestApplications restApplications();

        /**
         * Resolves the {@code Set<RouterMount>} multibinding.
         *
         * @return the resolved mount set
         */
        Set<RouterMount> routerMounts();

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            ViewComponent3 create(@BindsInstance @VertxConfig JsonObject config);
        }
    }
}
