// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.core.security.SecurityPolicyViolationException;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Locks down the runtime orchestration that the build-time CG-009 validators are expected to
 * mirror. Together with {@code RuntimeParityTest} in {@code vertique-codegen-jaxrs} (which
 * verifies the APT validators agree with {@code AnnotationSecurityPolicyResolver} directly),
 * this test closes the parity loop end-to-end:
 *
 * <ul>
 *   <li>APT decision == {@code AnnotationSecurityPolicyResolver} decision (codegen-jaxrs side).</li>
 *   <li>{@code AnnotationSecurityPolicyResolver} decision == {@code SecurityPolicyBuilder} decision
 *       (trivial delegation).</li>
 *   <li>{@code SecurityPolicyBuilder} decision == {@code ResourceScanner.scanResource} +
 *       {@code RouteValidator.validateMethodParams} decision (this test).</li>
 * </ul>
 *
 * <p>If a future change to {@code ResourceScanner} or {@code RouteValidator} alters how violations
 * are detected or aggregated — moves a check, drops a {@code continue}, swaps the
 * {@code SecurityPolicyBuilder} backend — the parity contract breaks here even when the lower-level
 * helper tests still pass.
 *
 * <p>The fixtures are the same {@code @Path}-annotated nested classes the codegen-jaxrs parity test
 * uses; both sides exercise the same source.
 */
class ResourceScannerOrchestrationParityTest {

    // --- Reflective fixtures ---

    /**
     * Clean resource — no violations expected at runtime.
     */
    @Path("/parity-accept")
    @RolesAllowed("user")
    static class AcceptedResource {

        /**
         * Clean GET endpoint.
         *
         * @param id the path parameter
         * @return the id
         */
        @GET
        @Path("/{id}")
        public String accepted(@PathParam("id") String id) {
            return id;
        }
    }

    /**
     * Resource with method-level security conflict.
     */
    @Path("/parity-conflict")
    static class MethodSecurityConflictResource {

        /**
         * Method-level {@code @PermitAll + @RolesAllowed} — runtime emits
         * {@code CONFLICTING_SECURITY_ANNOTATIONS}.
         *
         * @return constant
         */
        @GET
        @PermitAll
        @RolesAllowed("admin")
        public String conflict() {
            return "";
        }
    }

    /**
     * Resource with class-level security conflict — the per-method scan emits one violation per
     * method against the class-level conflict.
     */
    @DenyAll
    @PermitAll
    @Path("/parity-class-conflict")
    static class ClassSecurityConflictResource {

        /**
         * Method has no security annotations of its own. Runtime sees the class-level conflict.
         *
         * @return constant
         */
        @GET
        public String inherited() {
            return "";
        }
    }

    /**
     * Resource with class-level security conflict and TWO verb-bearing methods — the per-method
     * scan must emit one violation per method (not just one for the class).
     */
    @DenyAll
    @PermitAll
    @Path("/parity-class-conflict-multi")
    static class MultiMethodClassConflictResource {

        /**
         * @return constant
         */
        @GET
        public String first() {
            return "";
        }

        /**
         * @return constant
         */
        @POST
        public String second() {
            return "";
        }
    }

    /**
     * Resource with class-level security conflict AND a method that would otherwise produce a
     * route-shape violation (two body params). Runtime emits one security violation per method
     * and never invokes {@code RouteValidator.validateMethodParams} for the body conflict.
     */
    @DenyAll
    @PermitAll
    @Path("/parity-class-conflict-and-bodies")
    static class ClassConflictAndTwoBodyParamsResource {

        /**
         * @param a body
         * @param b body
         */
        @POST
        public void create(String a, String b) {}
    }

    /**
     * Resource with method-level empty {@code @RolesAllowed}.
     */
    @Path("/parity-empty-roles")
    static class EmptyRolesResource {

        /**
         * Method-level {@code @RolesAllowed({})}. Runtime emits {@code EMPTY_ROLES_ALLOWED}.
         *
         * @return constant
         */
        @GET
        @RolesAllowed({})
        public String empty() {
            return "";
        }
    }

    /**
     * Resource with two body parameters on a single method — runtime emits
     * {@code MULTIPLE_BODY_PARAMS} via {@link RouteValidator#validateMethodParams}.
     */
    @Path("/parity-multi-body")
    static class TwoBodyParamsResource {

        /**
         * Two unannotated parameters → both classified as BODY → violation.
         *
         * @param a body
         * @param b body
         */
        @POST
        public void create(String a, String b) {}
    }

    // --- Tests ---

    @Nested
    @DisplayName("ResourceScanner.scanResource — security violation channel")
    class SecurityChannel {

        @Test
        @DisplayName("clean resource produces zero security violations")
        void cleanResource_noViolations() {
            List<SecurityPolicyViolation> violations = new ArrayList<>();
            scanner().scanResource(new AcceptedResource(), violations);
            assertTrue(violations.isEmpty(), "Expected no violations on a clean resource");
        }

        @Test
        @DisplayName("method-level security conflict emits CONFLICTING_SECURITY_ANNOTATIONS")
        void methodConflict_emitsViolation() {
            List<SecurityPolicyViolation> violations = new ArrayList<>();
            scanner().scanResource(new MethodSecurityConflictResource(), violations);
            assertEquals(1, violations.size(), "Expected exactly 1 violation");
            assertEquals(
                    SecurityPolicyViolation.ViolationType.CONFLICTING_SECURITY_ANNOTATIONS,
                    violations.get(0).type());
        }

        @Test
        @DisplayName("class-level security conflict emits one violation per method")
        void classConflict_emitsViolation() {
            List<SecurityPolicyViolation> violations = new ArrayList<>();
            scanner().scanResource(new ClassSecurityConflictResource(), violations);
            assertEquals(1, violations.size(), "Expected exactly 1 violation on a single-method resource");
            assertEquals(
                    SecurityPolicyViolation.ViolationType.CONFLICTING_SECURITY_ANNOTATIONS,
                    violations.get(0).type());
        }

        @Test
        @DisplayName("class-level conflict with multiple methods emits ONE violation PER method")
        void classConflict_multipleMethods_oneViolationPerMethod() {
            List<SecurityPolicyViolation> violations = new ArrayList<>();
            scanner().scanResource(new MultiMethodClassConflictResource(), violations);
            assertEquals(2, violations.size(), "Expected exactly 2 violations — one per verb-bearing method");
            assertTrue(
                    violations.stream()
                            .allMatch(v ->
                                    v.type() == SecurityPolicyViolation.ViolationType.CONFLICTING_SECURITY_ANNOTATIONS),
                    "All violations should be CONFLICTING_SECURITY_ANNOTATIONS");
            // Each violation must point at a distinct operationId — pins "one violation per method"
            // against a regression that double-counts the same method.
            Set<String> operationIds = violations.stream()
                    .map(SecurityPolicyViolation::operationId)
                    .collect(Collectors.toSet());
            assertEquals(
                    Set.of("first", "second"),
                    operationIds,
                    "Violations must be attributed to distinct operationIds (first, second)");
        }

        @Test
        @DisplayName("empty @RolesAllowed emits EMPTY_ROLES_ALLOWED")
        void emptyRoles_emitsViolation() {
            List<SecurityPolicyViolation> violations = new ArrayList<>();
            scanner().scanResource(new EmptyRolesResource(), violations);
            assertEquals(1, violations.size(), "Expected exactly 1 violation");
            assertEquals(
                    SecurityPolicyViolation.ViolationType.EMPTY_ROLES_ALLOWED,
                    violations.get(0).type());
        }

        @Test
        @DisplayName("public scanResource throws SecurityPolicyViolationException when violations are present")
        void publicScanResource_throwsOnViolations() {
            assertThrows(SecurityPolicyViolationException.class, () -> scanner()
                    .scanResource(new MethodSecurityConflictResource()));
        }
    }

    @Nested
    @DisplayName("RouteValidator.validateMethodParams — route-shape violation channel")
    class RouteShapeChannel {

        @Test
        @DisplayName("two body params on one method emits MULTIPLE_BODY_PARAMS")
        void twoBodyParams_emitsViolation() {
            List<SecurityPolicyViolation> securityViolations = new ArrayList<>();
            List<ResourceMethodMeta> meta = scanner().scanResource(new TwoBodyParamsResource(), securityViolations);
            assertTrue(securityViolations.isEmpty(), "No security violation expected on this fixture");
            assertEquals(1, meta.size(), "Expected one method to be scanned");

            List<RouteRegistrationViolation> routeViolations = RouteValidator.validateMethodParams(meta.get(0));
            assertEquals(1, routeViolations.size(), "Expected exactly 1 route violation");
            assertEquals(
                    RouteRegistrationViolation.ViolationType.MULTIPLE_BODY_PARAMS,
                    routeViolations.get(0).type());
        }

        @Test
        @DisplayName("clean resource produces no route violations")
        void clean_noRouteViolations() {
            List<SecurityPolicyViolation> securityViolations = new ArrayList<>();
            List<ResourceMethodMeta> meta = scanner().scanResource(new AcceptedResource(), securityViolations);
            assertEquals(1, meta.size());
            assertTrue(RouteValidator.validateMethodParams(meta.get(0)).isEmpty());
        }
    }

    @Nested
    @DisplayName("continue-after-violation orchestration — security wins over route shape")
    class ContinueAfterViolation {

        /**
         * Both a security conflict AND two body params on one method. Runtime continues past the
         * security check and never invokes {@code RouteValidator.validateMethodParams} for this
         * method, so the meta list comes back empty. The codegen processor mirrors this exact
         * orchestration via the boolean return on {@code SecurityAnnotationValidator
         * .validateMethodLevel}.
         */
        @Path("/parity-both")
        static class SecurityAndRouteShapeResource {

            /**
             * @param a body
             * @param b body
             */
            @POST
            @PermitAll
            @RolesAllowed("admin")
            public void both(String a, String b) {}
        }

        @Test
        @DisplayName("method-level security conflict + two body params → runtime emits security only, not route shape")
        void methodSecurityWins_routeShapeNotChecked() {
            List<SecurityPolicyViolation> violations = new ArrayList<>();
            List<ResourceMethodMeta> meta = scanner().scanResource(new SecurityAndRouteShapeResource(), violations);
            assertEquals(1, violations.size(), "Expected one security violation");
            assertEquals(
                    SecurityPolicyViolation.ViolationType.CONFLICTING_SECURITY_ANNOTATIONS,
                    violations.get(0).type());
            assertTrue(
                    meta.isEmpty(), "Method must be skipped after the security violation — route shape never checked");
        }

        @Test
        @DisplayName("class-level security conflict + two body params → runtime emits class-level security only")
        void classSecurityWins_routeShapeNotChecked() {
            // Pins the class-level branch of continue-after-violation. The codegen processor
            // mirrors this via SecurityAnnotationValidator.validateMethodLevel returning false
            // when hasClassLevelConflict(resource) is true.
            List<SecurityPolicyViolation> violations = new ArrayList<>();
            List<ResourceMethodMeta> meta =
                    scanner().scanResource(new ClassConflictAndTwoBodyParamsResource(), violations);
            assertEquals(1, violations.size(), "Expected one class-level security violation");
            assertEquals(
                    SecurityPolicyViolation.ViolationType.CONFLICTING_SECURITY_ANNOTATIONS,
                    violations.get(0).type());
            assertTrue(meta.isEmpty(), "Method must be skipped after the class-level security violation");
        }
    }

    // --- Helper ---

    private ResourceScanner scanner() {
        return new ResourceScanner(new SecurityPolicyBuilder());
    }
}
