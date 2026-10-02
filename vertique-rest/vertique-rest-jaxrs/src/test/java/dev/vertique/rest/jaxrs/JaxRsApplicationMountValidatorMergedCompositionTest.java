// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.router.MountCompositionValidator;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import dev.vertique.rest.jaxrs.application.RestApplications;
import dev.vertique.rest.jaxrs.application.dupname.DupnameResourcesModule;
import dev.vertique.rest.jaxrs.application.dupname.UnitOneRegistrationModule;
import dev.vertique.rest.jaxrs.application.dupname.UnitTwoRegistrationModule;
import dev.vertique.rest.jaxrs.application.dupname.UnitTwoRenamedRegistrationModule;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * G2-05 (P02 review finding sec F-2): {@link JaxRsApplicationMountValidator} must reject an
 * application-mount pair conflict, and must scan cross-mount operationIds, even when the mounts it
 * is handed were merged from more than one composition into a single {@code Set<RouterMount>} — a
 * shape {@link JaxRsApplicationComposer} step 1b never sees, because step 1b runs per composition,
 * before that composition's own mounts are ever built. TP-020 (CX-001) extends this to a repeated
 * application name across merged compositions.
 *
 * <p>The first two tests build their two {@link JaxRsRouterMount} application mounts directly
 * through {@link JaxRsRouterMount.Factory#createApplicationMount}, standing in for two separate
 * compositions' mounts having been merged into one list by the time {@code HttpVerticle.start}
 * hands it to this validator, and construct {@link JaxRsApplicationMountValidator} directly with an
 * empty {@link RestApplications} view, standing in for a validator instance bound to a composition
 * that declares no application of its own.
 */
class JaxRsApplicationMountValidatorMergedCompositionTest {

    @Test
    @DisplayName("An application-mount pair conflict is reported even when the pair was never seen by one "
            + "composition's own composer resolution, with the pair named in deterministic mount-path order")
    void applicationMountPairConflictIsReportedAcrossMergedCompositions() {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount adminMount =
                factory.createApplicationMount("/api/mgmt/*", "openapi.json", Set.of(), "mgmt", AdminApplication.class);
        JaxRsRouterMount apiMount =
                factory.createApplicationMount("/api/*", "openapi.json", Set.of(), "api", ApiApplication.class);

        MountCompositionValidator validator = emptyViewValidator();
        // adminMount is listed FIRST, so a correct, deterministic pairing (sorted by mount path,
        // "/api/*" before "/api/mgmt/*") proves the report does not merely echo input order.
        List<String> violations = validator.validate(List.of(adminMount, apiMount));

        // R-002: named '<name>' (<declaring interface FQN>), matching the composer's step-1b message
        // format (JaxRsApplicationComposer.appContext).
        assertEquals(
                List.of("Application 'api' (" + ApiApplication.class.getName() + ") at '/api/*' conflicts with"
                        + " application 'mgmt' (" + AdminApplication.class.getName()
                        + ") at '/api/mgmt/*': their mount paths overlap"),
                violations);
    }

    @Test
    @DisplayName("A cross-mount operationId collision between two application mounts is reported even when this "
            + "validator instance's own view is empty")
    void operationIdCollisionIsReportedWithAnEmptyRegistrationSet() {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount firstMount = factory.createApplicationMount(
                "/api/one/*", "openapi.json", Set.of(new MergedListResourceOne()), "first", FirstApplication.class);
        JaxRsRouterMount secondMount = factory.createApplicationMount(
                "/api/two/*", "openapi.json", Set.of(new MergedListResourceTwo()), "second", SecondApplication.class);

        MountCompositionValidator validator = emptyViewValidator();
        List<String> violations = validator.validate(List.of(firstMount, secondMount));

        assertEquals(1, violations.size(), () -> "expected exactly one collision, got: " + violations);
        String violation = violations.get(0);
        assertTrue(violation.contains("'list'"), violation);
        assertTrue(violation.contains(MergedListResourceOne.class.getName()), violation);
        assertTrue(violation.contains(MergedListResourceTwo.class.getName()), violation);
        assertTrue(violation.contains("'/api/one/*'"), violation);
        assertTrue(violation.contains("'/api/two/*'"), violation);
    }

    // --- TP-020 ---

    @Test
    @DisplayName("An application name repeated across mounts merged from two separate compositions fails, "
            + "naming the name and both mount paths, and marks neither mount validated; a differently-named "
            + "or hand-built pairing composes and is marked validated")
    void repeatedApplicationNameAcrossMergedCompositionsFails() {
        JsonObject unitOneActive = config("dupname.unitOne.active", true);
        JsonObject unitTwoActive = config("dupname.unitTwo.active", true);

        UnitOneComponent unitOne = DaggerJaxRsApplicationMountValidatorMergedCompositionTest_UnitOneComponent.factory()
                .create(unitOneActive);
        UnitTwoComponent unitTwo = DaggerJaxRsApplicationMountValidatorMergedCompositionTest_UnitTwoComponent.factory()
                .create(unitTwoActive);

        JaxRsRouterMount unitOneMount = onlyMount(unitOne.routerMounts());
        JaxRsRouterMount unitTwoMount = onlyMount(unitTwo.routerMounts());
        MountCompositionValidator validator = onlyValidator(unitOne.mountCompositionValidators());

        List<String> violations = validator.validate(List.of(unitOneMount, unitTwoMount));

        assertEquals(1, violations.size(), () -> "expected exactly one repeated-name violation: " + violations);
        String violation = violations.get(0);
        assertTrue(violation.contains("'api'"), violation);
        assertTrue(violation.contains("'/api/one/*'"), violation);
        assertTrue(violation.contains("'/api/two/*'"), violation);
        assertFalse(unitOneMount.isValidated(), "a violation must mark no mount validated");
        assertFalse(unitTwoMount.isValidated(), "a violation must mark no mount validated");

        // Control 1: the second unit renamed to "api-two" no longer collides.
        UnitTwoRenamedComponent unitTwoRenamed =
                DaggerJaxRsApplicationMountValidatorMergedCompositionTest_UnitTwoRenamedComponent.factory()
                        .create(unitTwoActive);
        JaxRsRouterMount unitTwoRenamedMount = onlyMount(unitTwoRenamed.routerMounts());

        List<String> controlViolations = validator.validate(List.of(unitOneMount, unitTwoRenamedMount));

        assertTrue(controlViolations.isEmpty(), () -> "renamed control must report nothing: " + controlViolations);
        assertTrue(unitOneMount.isValidated(), "the renamed control's application mounts must be marked validated");
        assertTrue(
                unitTwoRenamedMount.isValidated(), "the renamed control's application mounts must be marked validated");

        // Control 2: the first component's mount beside two hand-built (no application name) mounts.
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount handBuiltOne =
                factory.create("/other/one/*", "openapi.json", Set.of(new ControlResourceOne()));
        JaxRsRouterMount handBuiltTwo =
                factory.create("/other/two/*", "openapi.json", Set.of(new ControlResourceTwo()));

        List<String> handBuiltControlViolations = validator.validate(List.of(unitOneMount, handBuiltOne, handBuiltTwo));

        assertTrue(
                handBuiltControlViolations.isEmpty(),
                () -> "a hand-built pairing carries no application name: " + handBuiltControlViolations);
        assertTrue(unitOneMount.isValidated(), "the sole application mount must be marked validated");
    }

    private static JaxRsRouterMount onlyMount(Set<RouterMount> mounts) {
        assertEquals(1, mounts.size(), () -> "expected exactly one mount, got: " + mounts);
        return (JaxRsRouterMount) mounts.iterator().next();
    }

    private static MountCompositionValidator onlyValidator(Set<MountCompositionValidator> validators) {
        assertEquals(1, validators.size(), () -> "expected exactly one composition validator, got: " + validators);
        return validators.iterator().next();
    }

    private static JsonObject config(String key, boolean value) {
        String[] segments = key.split("\\.");
        JsonObject leaf = new JsonObject().put(segments[segments.length - 1], value);
        for (int i = segments.length - 2; i >= 0; i--) {
            leaf = new JsonObject().put(segments[i], leaf);
        }
        return leaf;
    }

    private static MountCompositionValidator emptyViewValidator() {
        return new JaxRsApplicationMountValidator(
                new RestApplications(List.of()),
                JaxRsConfig.builder().validationStrategy("none").build(),
                Set.of());
    }

    // --- Fixtures (first two tests) ---

    /** Test-only declaring type; never constructed, used only for its class identity. */
    private static final class AdminApplication {}

    /** Test-only declaring type; never constructed, used only for its class identity. */
    private static final class ApiApplication {}

    /** Test-only declaring type; never constructed, used only for its class identity. */
    private static final class FirstApplication {}

    /** Test-only declaring type; never constructed, used only for its class identity. */
    private static final class SecondApplication {}

    /** Declares a {@code list} operation with no common owner with {@link MergedListResourceTwo}. */
    @Path("/resource-one")
    public static class MergedListResourceOne {

        /**
         * Handles {@code GET .../resource-one}.
         *
         * @return the fixed body {@code "one"}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String list() {
            return "one";
        }
    }

    /** Declares a {@code list} operation with no common owner with {@link MergedListResourceOne}. */
    @Path("/resource-two")
    public static class MergedListResourceTwo {

        /**
         * Handles {@code GET .../resource-two}.
         *
         * @return the fixed body {@code "two"}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String list() {
            return "two";
        }
    }

    // --- Fixtures (TP-020's hand-built control) ---

    /** TP-020 control 2's first hand-built resource, an operationId distinct from every other fixture here. */
    @Path("/control-one")
    public static class ControlResourceOne {

        /**
         * Handles {@code GET .../control-one}.
         *
         * @return the fixed body {@code "controlOne"}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String controlOne() {
            return "controlOne";
        }
    }

    /** TP-020 control 2's second hand-built resource, an operationId distinct from every other fixture here. */
    @Path("/control-two")
    public static class ControlResourceTwo {

        /**
         * Handles {@code GET .../control-two}.
         *
         * @return the fixed body {@code "controlTwo"}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String controlTwo() {
            return "controlTwo";
        }
    }

    // --- TP-020's support module and Dagger components ---

    /**
     * The collaborators {@code JaxRsRouterMount.Factory} and the view's provider require but the
     * real security and config modules supply, mirrored from {@code application.ApplicationTestSupportModule}
     * (T002, E3): the real {@link DefaultConfigParser}, since the view's provider calls {@link
     * ConfigParser#parseKeyedObject}, and the unsecured {@link SecurityPolicyValidator} stand-in.
     */
    @Module
    static final class MergedCompositionSupportModule {

        private MergedCompositionSupportModule() {}

        @Provides
        @Nullable
        static SecurityPolicyValidator securityPolicyValidator() {
            return null;
        }

        @Provides
        static ConfigParser configParser() {
            return new DefaultConfigParser(DefaultConfigMapper.lenient());
        }
    }

    /** Provisions every TP-020 component exposes. */
    interface Provisions {

        /**
         * Resolves the {@code Set<RouterMount>} multibinding.
         *
         * @return the resolved mount set
         */
        Set<RouterMount> routerMounts();

        /**
         * Resolves the {@code Set<MountCompositionValidator>} multibinding.
         *
         * @return the resolved composition validator set
         */
        Set<MountCompositionValidator> mountCompositionValidators();
    }

    /** The first simulated compilation unit: registers {@code api} at {@code /api/one}. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                MergedCompositionSupportModule.class,
                UnitOneRegistrationModule.class,
                DupnameResourcesModule.class
            })
    interface UnitOneComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            UnitOneComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /** The second simulated compilation unit: registers {@code api} at {@code /api/two}. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                MergedCompositionSupportModule.class,
                UnitTwoRegistrationModule.class,
                DupnameResourcesModule.class
            })
    interface UnitTwoComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            UnitTwoComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /** TP-020's control: the second unit renamed to {@code api-two}, so it no longer collides. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                MergedCompositionSupportModule.class,
                UnitTwoRenamedRegistrationModule.class,
                DupnameResourcesModule.class
            })
    interface UnitTwoRenamedComponent extends Provisions {

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            UnitTwoRenamedComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }
}
