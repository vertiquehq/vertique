// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Application;
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
 * before that composition's own mounts are ever built.
 *
 * <p>Both rows build their two {@link JaxRsRouterMount} application mounts directly through {@link
 * JaxRsRouterMount.Factory#createApplicationMount}, standing in for two separate compositions' mounts
 * having been merged into one list by the time {@code HttpVerticle.start} hands it to this
 * validator, and construct {@link JaxRsApplicationMountValidator} directly with an empty {@link
 * GeneratedJaxRsApplicationRegistration} set, standing in for a validator instance bound to a
 * composition that declares no application of its own.
 */
class JaxRsApplicationMountValidatorMergedCompositionTest {

    @Test
    @DisplayName("An application-mount pair conflict is reported even when the pair was never seen by one "
            + "composition's own composer resolution, with the pair named in deterministic mount-path order")
    void applicationMountPairConflictIsReportedAcrossMergedCompositions() {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount adminMount =
                factory.createApplicationMount("/api/mgmt/*", "openapi.json", Set.of(), AdminApplication.class);
        JaxRsRouterMount apiMount =
                factory.createApplicationMount("/api/*", "openapi.json", Set.of(), ApiApplication.class);

        JaxRsApplicationMountValidator validator = new JaxRsApplicationMountValidator(Set.of());
        // adminMount is listed FIRST, so a correct, deterministic pairing (sorted by mount path,
        // "/api/*" before "/api/mgmt/*") proves the report does not merely echo input order.
        List<String> violations = validator.validate(List.of(adminMount, apiMount));

        assertEquals(
                List.of("Application " + ApiApplication.class.getName() + " at '/api/*' conflicts with application "
                        + AdminApplication.class.getName() + " at '/api/mgmt/*': their mount paths overlap"),
                violations);
    }

    @Test
    @DisplayName("A cross-mount operationId collision between two application mounts is reported even when this "
            + "validator instance's own registration set is empty")
    void operationIdCollisionIsReportedWithAnEmptyRegistrationSet() {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount firstMount = factory.createApplicationMount(
                "/api/one/*", "openapi.json", Set.of(new MergedListResourceOne()), FirstApplication.class);
        JaxRsRouterMount secondMount = factory.createApplicationMount(
                "/api/two/*", "openapi.json", Set.of(new MergedListResourceTwo()), SecondApplication.class);

        JaxRsApplicationMountValidator validator = new JaxRsApplicationMountValidator(Set.of());
        List<String> violations = validator.validate(List.of(firstMount, secondMount));

        assertEquals(1, violations.size(), () -> "expected exactly one collision, got: " + violations);
        String violation = violations.get(0);
        assertTrue(violation.contains("'list'"), violation);
        assertTrue(violation.contains(MergedListResourceOne.class.getName()), violation);
        assertTrue(violation.contains(MergedListResourceTwo.class.getName()), violation);
        assertTrue(violation.contains("'/api/one/*'"), violation);
        assertTrue(violation.contains("'/api/two/*'"), violation);
    }

    // --- Fixtures ---

    /** Test-only application type; never constructed, used only for its class identity. */
    private static final class AdminApplication extends Application {}

    /** Test-only application type; never constructed, used only for its class identity. */
    private static final class ApiApplication extends Application {}

    /** Test-only application type; never constructed, used only for its class identity. */
    private static final class FirstApplication extends Application {}

    /** Test-only application type; never constructed, used only for its class identity. */
    private static final class SecondApplication extends Application {}

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
}
