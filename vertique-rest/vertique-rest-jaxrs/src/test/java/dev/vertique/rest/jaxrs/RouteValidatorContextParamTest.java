// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextValue;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link RouteValidator}'s context-param validation — the three new violation types:
 * {@link RouteRegistrationViolation.ViolationType#CONTEXT_PARAM_CONFLICT},
 * {@link RouteRegistrationViolation.ViolationType#UNSUPPORTED_JAXRS_CONTEXT_TYPE}, and
 * {@link RouteRegistrationViolation.ViolationType#NON_INJECTABLE_CONTEXT_TYPE}.
 *
 * <p>Cases covered:
 * <ul>
 *   <li>{@code @Context @PathParam("id") String} → exactly one CONTEXT_PARAM_CONFLICT</li>
 *   <li>{@code @Context jakarta.ws.rs.core.UriInfo} → UNSUPPORTED_JAXRS_CONTEXT_TYPE</li>
 *   <li>{@code @Context PaymentService} (non-context class) → NON_INJECTABLE_CONTEXT_TYPE</li>
 *   <li>{@code @Context String} → NON_INJECTABLE_CONTEXT_TYPE (not classified as BODY)</li>
 *   <li>Valid {@code @Context RoutingContext} → no context violations</li>
 *   <li>Valid {@code @Context Tenant} ({@link ContextValue}) → no context violations</li>
 * </ul>
 */
class RouteValidatorContextParamTest {

    // --- Test-fixture injectable type ---

    /**
     * App-defined context value record for the "valid injectable" test case.
     *
     * @param id tenant identifier
     */
    record Tenant(String id) implements ContextValue {}

    /** Non-injectable class — simulates an arbitrary app service, not a context type. */
    static class PaymentService {}

    /**
     * A custom subinterface of the JAX-RS {@link jakarta.ws.rs.core.SecurityContext}.
     *
     * <p>Subtypes are NOT injectable — the resolver only handles the exact JAX-RS type.
     * A custom subtype must produce {@code NON_INJECTABLE_CONTEXT_TYPE}.
     */
    interface CustomJaxRsSecurityContext extends jakarta.ws.rs.core.SecurityContext {}

    // --- Resource fixtures ---

    /** Method with {@code @Context} AND {@code @PathParam} on the same parameter. */
    @Path("/conflict")
    @PermitAll
    static class ContextPathParamConflictResource {

        /**
         * @param id conflicting param
         * @return result
         */
        @GET
        @Path("/{id}")
        public String get(@Context @PathParam("id") String id) {
            return id;
        }
    }

    /** Method with {@code @Context jakarta.ws.rs.core.UriInfo}. */
    @Path("/uriinfo")
    @PermitAll
    static class UriInfoResource {

        /**
         * @param uriInfo unsupported context type
         * @return result
         */
        @GET
        public String get(@Context jakarta.ws.rs.core.UriInfo uriInfo) {
            return "ok";
        }
    }

    /** Method with {@code @Context PaymentService} — non-injectable type. */
    @Path("/payment")
    @PermitAll
    static class PaymentServiceResource {

        /**
         * @param svc non-injectable type
         * @return result
         */
        @GET
        public String get(@Context PaymentService svc) {
            return "ok";
        }
    }

    /** Method with {@code @Context String} — non-injectable type (String is not a ContextValue). */
    @Path("/string")
    @PermitAll
    static class ContextStringResource {

        /**
         * @param s non-injectable context parameter
         * @return result
         */
        @GET
        public String get(@Context String s) {
            return s;
        }
    }

    /**
     * Method with {@code @Context CustomJaxRsSecurityContext} — a subinterface of the JAX-RS
     * {@code SecurityContext}. Subtypes are not resolvable by the framework and must be rejected.
     */
    @Path("/custom-sc")
    @PermitAll
    static class CustomJaxRsSecurityContextResource {

        /**
         * @param sc custom JAX-RS security context subtype (non-injectable)
         * @return result
         */
        @GET
        public String get(@Context CustomJaxRsSecurityContext sc) {
            return "ok";
        }
    }

    /** Method with valid {@code @Context RoutingContext} — no violations expected. */
    @Path("/valid-routing")
    @PermitAll
    static class ValidRoutingContextResource {

        /**
         * @param ctx routing context
         * @return result
         */
        @GET
        public String get(@Context RoutingContext ctx) {
            return "ok";
        }
    }

    /** Method with valid {@code @Context Tenant} ({@link ContextValue}) — no violations expected. */
    @Path("/valid-tenant")
    @PermitAll
    static class ValidTenantResource {

        /**
         * @param t tenant context value
         * @return result
         */
        @GET
        public String get(@Context Tenant t) {
            return "ok";
        }
    }

    // --- Helper ---

    private ResourceScanner scanner() {
        return new ResourceScanner(new SecurityPolicyBuilder());
    }

    private ResourceMethodMeta scanFirst(Object resource) {
        List<ResourceMethodMeta> metas = scanner().scanResource(resource);
        assertEquals(1, metas.size(), "Expected exactly one method on fixture");
        return metas.get(0);
    }

    // --- Tests ---

    @Nested
    @DisplayName("CONTEXT_PARAM_CONFLICT — @Context + binding annotation on same parameter")
    class ContextParamConflict {

        @Test
        @DisplayName("@Context @PathParam('id') String → exactly one CONTEXT_PARAM_CONFLICT violation")
        void contextAndPathParam_conflict() {
            ResourceMethodMeta meta = scanFirst(new ContextPathParamConflictResource());
            List<RouteRegistrationViolation> violations = RouteValidator.validateMethodParams(meta);
            assertEquals(1, violations.size(), "Expected exactly one violation");
            assertEquals(
                    RouteRegistrationViolation.ViolationType.CONTEXT_PARAM_CONFLICT,
                    violations.get(0).type());
        }
    }

    @Nested
    @DisplayName("UNSUPPORTED_JAXRS_CONTEXT_TYPE — reserved but unsupported JAX-RS context types")
    class UnsupportedJaxRsContextType {

        @Test
        @DisplayName("@Context UriInfo → UNSUPPORTED_JAXRS_CONTEXT_TYPE violation")
        void contextUriInfo_unsupported() {
            ResourceMethodMeta meta = scanFirst(new UriInfoResource());
            List<RouteRegistrationViolation> violations = RouteValidator.validateMethodParams(meta);
            assertEquals(1, violations.size(), "Expected exactly one violation");
            assertEquals(
                    RouteRegistrationViolation.ViolationType.UNSUPPORTED_JAXRS_CONTEXT_TYPE,
                    violations.get(0).type());
        }
    }

    @Nested
    @DisplayName("NON_INJECTABLE_CONTEXT_TYPE — @Context with unknown / unsupported type")
    class NonInjectableContextType {

        @Test
        @DisplayName("@Context PaymentService → NON_INJECTABLE_CONTEXT_TYPE violation")
        void contextPaymentService_nonInjectable() {
            ResourceMethodMeta meta = scanFirst(new PaymentServiceResource());
            List<RouteRegistrationViolation> violations = RouteValidator.validateMethodParams(meta);
            assertEquals(1, violations.size(), "Expected exactly one violation");
            assertEquals(
                    RouteRegistrationViolation.ViolationType.NON_INJECTABLE_CONTEXT_TYPE,
                    violations.get(0).type());
        }

        @Test
        @DisplayName("@Context String → NON_INJECTABLE_CONTEXT_TYPE (String is not a ContextValue)")
        void contextString_nonInjectable() {
            ResourceMethodMeta meta = scanFirst(new ContextStringResource());
            List<RouteRegistrationViolation> violations = RouteValidator.validateMethodParams(meta);
            assertEquals(1, violations.size(), "Expected exactly one violation");
            assertEquals(
                    RouteRegistrationViolation.ViolationType.NON_INJECTABLE_CONTEXT_TYPE,
                    violations.get(0).type());
        }

        @Test
        @DisplayName("@Context CustomJaxRsSecurityContext (jakarta SC subtype) → NON_INJECTABLE_CONTEXT_TYPE")
        void jakartaSecurityContextSubtype_nonInjectable() {
            // A jakarta SecurityContext subtype passes no resolver at runtime — reject it here.
            ResourceMethodMeta meta = scanFirst(new CustomJaxRsSecurityContextResource());
            List<RouteRegistrationViolation> violations = RouteValidator.validateMethodParams(meta);
            assertEquals(1, violations.size(), "Expected exactly one violation");
            assertEquals(
                    RouteRegistrationViolation.ViolationType.NON_INJECTABLE_CONTEXT_TYPE,
                    violations.get(0).type());
        }
    }

    @Nested
    @DisplayName("No context violations for valid injectable types")
    class NoContextViolations {

        @Test
        @DisplayName("@Context RoutingContext → no context violations")
        void validRoutingContext_noViolations() {
            ResourceMethodMeta meta = scanFirst(new ValidRoutingContextResource());
            List<RouteRegistrationViolation> violations = RouteValidator.validateMethodParams(meta);
            // Filter to only context-type violations
            long contextViolations = violations.stream()
                    .filter(v -> v.type() == RouteRegistrationViolation.ViolationType.CONTEXT_PARAM_CONFLICT
                            || v.type() == RouteRegistrationViolation.ViolationType.UNSUPPORTED_JAXRS_CONTEXT_TYPE
                            || v.type() == RouteRegistrationViolation.ViolationType.NON_INJECTABLE_CONTEXT_TYPE)
                    .count();
            assertEquals(0, contextViolations, "Valid RoutingContext must produce no context violations");
        }

        @Test
        @DisplayName("@Context Tenant (ContextValue) → no context violations")
        void validTenant_noViolations() {
            ResourceMethodMeta meta = scanFirst(new ValidTenantResource());
            List<RouteRegistrationViolation> violations = RouteValidator.validateMethodParams(meta);
            long contextViolations = violations.stream()
                    .filter(v -> v.type() == RouteRegistrationViolation.ViolationType.CONTEXT_PARAM_CONFLICT
                            || v.type() == RouteRegistrationViolation.ViolationType.UNSUPPORTED_JAXRS_CONTEXT_TYPE
                            || v.type() == RouteRegistrationViolation.ViolationType.NON_INJECTABLE_CONTEXT_TYPE)
                    .count();
            assertEquals(0, contextViolations, "Valid Tenant ContextValue must produce no context violations");
        }
    }

    @Nested
    @DisplayName("Violation messages contain useful diagnostic information")
    class ViolationMessages {

        @Test
        @DisplayName("CONTEXT_PARAM_CONFLICT message names the type and method")
        void conflictMessage_containsTypeAndMethod() {
            ResourceMethodMeta meta = scanFirst(new ContextPathParamConflictResource());
            List<RouteRegistrationViolation> violations = RouteValidator.validateMethodParams(meta);
            assertEquals(1, violations.size());
            String msg = violations.get(0).message();
            assertTrue(msg.contains("String"), "Message should mention the parameter type");
            assertTrue(msg.contains("get"), "Message should mention the method name");
        }

        @Test
        @DisplayName("UNSUPPORTED_JAXRS_CONTEXT_TYPE message names UriInfo and method")
        void unsupportedMessage_containsTypeAndMethod() {
            ResourceMethodMeta meta = scanFirst(new UriInfoResource());
            List<RouteRegistrationViolation> violations = RouteValidator.validateMethodParams(meta);
            assertEquals(1, violations.size());
            String msg = violations.get(0).message();
            assertTrue(msg.contains("UriInfo"), "Message should mention the parameter type");
        }

        @Test
        @DisplayName("NON_INJECTABLE_CONTEXT_TYPE message names PaymentService and method")
        void nonInjectableMessage_containsTypeAndMethod() {
            ResourceMethodMeta meta = scanFirst(new PaymentServiceResource());
            List<RouteRegistrationViolation> violations = RouteValidator.validateMethodParams(meta);
            assertEquals(1, violations.size());
            String msg = violations.get(0).message();
            assertTrue(msg.contains("PaymentService"), "Message should mention the parameter type");
            assertTrue(msg.contains("get"), "Message should mention the method name");
        }
    }
}
