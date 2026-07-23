// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.jaxrs.JaxRsPipelineProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.rest.core.security.AnnotationSecurityPolicyResolver;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.lang.reflect.Method;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Parity guardrail test asserting that the APT-based {@link JaxRsPipelineProcessor} makes the
 * same accept/reject decisions as the runtime for the Tier-A check subset (security annotation
 * conflicts, empty {@code @RolesAllowed}, and body/form exclusivity).
 *
 * <p>Strategy: each fixture method is represented twice —
 * <ol>
 *   <li>As a compiled inner class on the reflective side: directly inspected using
 *       {@link AnnotationSecurityPolicyResolver#hasConflictingAnnotations},
 *       {@link AnnotationSecurityPolicyResolver#hasEmptyRolesAllowed}, and a hand-rolled
 *       body/form count that mirrors runtime {@code RouteValidator.validateMethodParams}.</li>
 *   <li>As source text in a dedicated single-method APT fixture compiled via
 *       {@link ProcessorTestHarness}: error count in that compilation is compared against the
 *       runtime decision.</li>
 * </ol>
 *
 * <p>Each parity test uses a single-method resource fixture so that error counts are unambiguous —
 * every error in the compilation belongs to the one method under test.
 *
 * <p>Note: {@link dev.vertique.rest.jaxrs.ResourceMethodMeta} construction is deferred until
 * runtime and requires significant wiring (route registration, scanner, resource instance). It is
 * not used here; instead, we call the security resolver and inspect parameter annotations directly,
 * which tests the same logical contract at a lower coupling cost. If the resource-meta API is
 * simplified in a future commit, this test can be upgraded to use it directly.
 *
 * <p>Scope: this test covers the Tier-A checks that runtime also performs:
 * <ul>
 *   <li>Security annotation conflicts
 *       ({@link AnnotationSecurityPolicyResolver#hasConflictingAnnotations})</li>
 *   <li>Empty {@code @RolesAllowed}
 *       ({@link AnnotationSecurityPolicyResolver#hasEmptyRolesAllowed})</li>
 *   <li>Body/form exclusivity (hand-rolled count matching runtime
 *       {@code RouteValidator.validateMethodParams})</li>
 * </ul>
 *
 * <p>The path-placeholder check is intentionally excluded from this parity test because runtime
 * has no equivalent check (it silently resolves to {@code null}); it is tested in
 * {@code PathParamAlignmentValidatorTest} against the intended behaviour instead.
 */
class RuntimeParityTest {

    // --- Reflective fixture classes (one per parity scenario) ---

    /**
     * Parity fixture — accepted method. Single GET with a path parameter; class-level
     * {@code @RolesAllowed("user")} (non-empty, no conflict). Runtime: accept. APT: no error.
     */
    @Path("/parity-accept")
    @RolesAllowed("user")
    static class AcceptedResource {

        /**
         * Clean method. Runtime and APT should both accept.
         */
        @GET
        @Path("/{id}")
        public String accepted(@PathParam("id") String id) {
            return id;
        }
    }

    /**
     * Parity fixture — security-conflict method. {@code @PermitAll + @RolesAllowed} at method
     * level. Runtime: {@code hasConflictingAnnotations} returns {@code true}. APT: ERROR.
     */
    @Path("/parity-conflict")
    @RolesAllowed("user")
    static class SecurityConflictResource {

        /**
         * Method with conflicting security annotations. Both runtime and APT should reject.
         */
        @GET
        @Path("/conflict")
        @PermitAll
        @RolesAllowed("admin")
        public String conflictingSecurityMethod() {
            return "";
        }
    }

    /**
     * Parity fixture — body/form conflict method. {@code @FormParam} mixed with an unannotated
     * body parameter. Runtime: {@code RouteValidator.validateMethodParams} returns a violation.
     * APT: ERROR.
     */
    @Path("/parity-body-form")
    static class BodyFormConflictResource {

        /**
         * Method with mixed form/body params. Both runtime and APT should reject.
         */
        @POST
        @Path("/upload")
        public String formAndBodyMethod(@FormParam("name") String name, String body) {
            return "";
        }
    }

    /**
     * Parity fixture — class-level empty {@code @RolesAllowed} with method-level {@code @PermitAll}.
     * Runtime: {@code hasEmptyRolesAllowed} returns {@code false} — method's {@code @PermitAll}
     * overrides the class-level {@code @RolesAllowed({})}. APT: no error (class-level ignored).
     * This is the Critical parity break that was fixed by Fix 1.
     */
    @Path("/parity-empty-roles-override")
    @RolesAllowed({})
    static class EmptyRolesOverrideResource {

        /**
         * Method with {@code @PermitAll} overriding class-level empty {@code @RolesAllowed}.
         * Runtime accepts; APT must also accept.
         */
        @GET
        @PermitAll
        public String permitted() {
            return "";
        }
    }

    /**
     * Parity fixture — class-level empty {@code @RolesAllowed} with no method-level override.
     * Runtime: {@code hasEmptyRolesAllowed} returns {@code true} — class-level {@code @RolesAllowed({})}
     * is the effective policy for the method. APT: ERROR on the method.
     */
    @Path("/parity-empty-roles-effective")
    @RolesAllowed({})
    static class EmptyRolesEffectiveResource {

        /**
         * Method with no security annotation — class-level empty {@code @RolesAllowed} applies.
         * Runtime rejects; APT must also reject.
         */
        @GET
        public String get() {
            return "";
        }
    }

    /**
     * Parity fixture — two body parameters. Runtime: {@code RouteValidator.validateMethodParams}
     * returns a violation. APT: ERROR.
     */
    @Path("/parity-two-bodies")
    static class TwoBodiesResource {

        /**
         * Method with two unannotated body params. Both runtime and APT should reject.
         */
        @POST
        public String twoBodiesMethod(String a, String b) {
            return "";
        }
    }

    /**
     * Parity fixture — framework {@code dev.vertique.security.SecurityContext} plus a body
     * parameter. Runtime: the {@code SecurityContext} is classified as CONTEXT (not BODY), so only
     * one body parameter is present. APT: no error. Fix A ensures the correct FQN is used so the
     * APT mirrors this classification.
     */
    @Path("/parity-framework-security-context")
    static class FrameworkSecurityContextResource {

        /**
         * Method with framework {@code SecurityContext} + body. Runtime accepts; APT must also
         * accept — one CONTEXT param, one BODY param.
         */
        @POST
        public String createWithSec(dev.vertique.security.SecurityContext sec, String body) {
            return "";
        }
    }

    /**
     * Parity fixture — body-conflict method declared on a base class, inherited by the leaf
     * {@code @Path} class. Runtime: {@code RouteValidator.validateMethodParams} includes the
     * declaring class name ({@code BaseBodyConflictResource}) in the error. APT Fix D ensures the
     * same wording by deriving the class name from {@code method.getEnclosingElement()}.
     */
    static class BaseBodyConflictResource {

        /**
         * Method with two body params. Inherited by the child class. Runtime rejects with the
         * base class name; APT must also reject using the base class name.
         */
        @POST
        public String inheritedConflict(String a, String b) {
            return "";
        }
    }

    /**
     * Leaf resource inheriting the body-conflict method from {@link BaseBodyConflictResource}.
     */
    @Path("/parity-inherited-conflict")
    static class ChildBodyConflictResource extends BaseBodyConflictResource {}

    // --- Runtime-side helpers ---

    /**
     * Determines whether the runtime would reject a method for a security or body/form violation.
     *
     * @param method        the reflective method to check
     * @param resourceClass the resource class the method belongs to
     * @return {@code true} if the runtime would raise a violation for this method
     */
    private boolean runtimeWouldReject(Method method, Class<?> resourceClass) {
        AnnotationSecurityPolicyResolver resolver = new AnnotationSecurityPolicyResolver();
        if (resolver.hasConflictingAnnotations(resourceClass, method)) {
            return true;
        }
        if (resolver.hasEmptyRolesAllowed(resourceClass, method)) {
            return true;
        }
        // Body/form conflict: count unannotated (BODY) params and @FormParam (FORM) params
        int bodyCount = 0;
        boolean hasForm = false;
        for (var param : method.getParameters()) {
            if (param.isAnnotationPresent(FormParam.class)) {
                hasForm = true;
            } else if (!isAnnotatedParam(param) && !isContextType(param.getType())) {
                bodyCount++;
            }
        }
        if (bodyCount > 1) {
            return true;
        }
        if (hasForm && bodyCount > 0) {
            return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if the parameter carries any JAX-RS binding annotation that the
     * runtime classifies as a non-BODY source ({@code @PathParam}, {@code @QueryParam},
     * {@code @HeaderParam}, {@code @CookieParam}, or {@code @FormParam}).
     *
     * @param param the reflective parameter to inspect
     * @return {@code true} if any binding annotation is present
     */
    private boolean isAnnotatedParam(java.lang.reflect.Parameter param) {
        return param.isAnnotationPresent(jakarta.ws.rs.PathParam.class)
                || param.isAnnotationPresent(jakarta.ws.rs.QueryParam.class)
                || param.isAnnotationPresent(jakarta.ws.rs.HeaderParam.class)
                || param.isAnnotationPresent(jakarta.ws.rs.CookieParam.class)
                || param.isAnnotationPresent(jakarta.ws.rs.FormParam.class);
    }

    /**
     * Returns {@code true} if the given type is one of the four framework context types that
     * runtime classifies as CONTEXT (not BODY). Mirrors the dispatch order in
     * {@code ResourceScanner.resolveParams}.
     *
     * @param type the parameter type to inspect
     * @return {@code true} if the type is a context type
     */
    private boolean isContextType(Class<?> type) {
        return io.vertx.ext.web.RoutingContext.class.isAssignableFrom(type)
                || dev.vertique.security.SecurityContext.class.isAssignableFrom(type)
                || jakarta.ws.rs.core.SecurityContext.class.isAssignableFrom(type)
                || dev.vertique.rest.core.request.RequestPreconditions.class.isAssignableFrom(type);
    }

    /**
     * Returns whether the given APT result has any ERROR diagnostics.
     *
     * @param result the APT compilation result
     * @return {@code true} if at least one ERROR diagnostic was emitted
     */
    private boolean aptRejects(ProcessorTestHarness.Result result) {
        return result.compilation().diagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
    }

    // --- Parity tests — accepted method ---

    @Nested
    @DisplayName("parity: accepted method — both sides agree: accept")
    class AcceptedMethod {

        @Test
        @DisplayName("accepted() — runtime accepts, APT emits no errors")
        void accepted_runtimeAcceptsAndAptEmitsNoErrors() throws Exception {
            // Runtime side
            Method method = AcceptedResource.class.getMethod("accepted", String.class);
            boolean runtimeRejects = runtimeWouldReject(method, AcceptedResource.class);

            // APT side — single-method resource; all errors belong to this method
            var aptResult = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.parity.AcceptedResource", """
                            package dev.vertique.test.parity;

                            import jakarta.annotation.security.RolesAllowed;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/parity-accept")
                            @RolesAllowed("user")
                            public class AcceptedResource {
                                @GET
                                @Path("/{id}")
                                public String accepted(@PathParam("id") String id) {
                                    return id;
                                }
                            }
                            """));

            boolean aptRejectsMethod = aptRejects(aptResult);

            // Parity assertion
            assertFalse(runtimeRejects, "accepted() should not be rejected by runtime");
            assertFalse(aptRejectsMethod, "accepted() should produce no APT errors");
            assertEquals(runtimeRejects, aptRejectsMethod, "Parity breach: runtime and APT disagree on accepted()");
        }
    }

    // --- Parity tests — security-conflict method ---

    @Nested
    @DisplayName("parity: security-conflict method — both sides agree: reject")
    class SecurityConflictMethod {

        @Test
        @DisplayName("conflictingSecurityMethod() — runtime rejects, APT emits ≥1 error")
        void conflict_runtimeRejectsAndAptEmitsError() throws Exception {
            // Runtime side
            Method method = SecurityConflictResource.class.getMethod("conflictingSecurityMethod");
            boolean runtimeRejects = runtimeWouldReject(method, SecurityConflictResource.class);

            // APT side — single-method resource; all errors belong to this method
            var aptResult = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.parity.SecurityConflictResource", """
                            package dev.vertique.test.parity;

                            import jakarta.annotation.security.PermitAll;
                            import jakarta.annotation.security.RolesAllowed;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/parity-conflict")
                            @RolesAllowed("user")
                            public class SecurityConflictResource {
                                @GET
                                @Path("/conflict")
                                @PermitAll
                                @RolesAllowed("admin")
                                public String conflictingSecurityMethod() {
                                    return "";
                                }
                            }
                            """));

            boolean aptRejectsMethod = aptRejects(aptResult);

            // Parity assertion
            assertTrue(runtimeRejects, "conflictingSecurityMethod() should be rejected by runtime");
            assertTrue(aptRejectsMethod, "conflictingSecurityMethod() should produce ≥1 APT error");
            assertEquals(
                    runtimeRejects,
                    aptRejectsMethod,
                    "Parity breach: runtime and APT disagree on conflictingSecurityMethod()");
        }
    }

    // --- Parity tests — body/form conflict method ---

    @Nested
    @DisplayName("parity: body/form conflict method — both sides agree: reject")
    class BodyFormConflictMethod {

        @Test
        @DisplayName("formAndBodyMethod() — runtime rejects, APT emits ≥1 error")
        void bodyFormConflict_runtimeRejectsAndAptEmitsError() throws Exception {
            // Runtime side
            Method method = BodyFormConflictResource.class.getMethod("formAndBodyMethod", String.class, String.class);
            boolean runtimeRejects = runtimeWouldReject(method, BodyFormConflictResource.class);

            // APT side — single-method resource; all errors belong to this method
            var aptResult = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.parity.BodyFormConflictResource", """
                            package dev.vertique.test.parity;

                            import jakarta.ws.rs.FormParam;
                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;

                            @Path("/parity-body-form")
                            public class BodyFormConflictResource {
                                @POST
                                @Path("/upload")
                                public String formAndBodyMethod(@FormParam("name") String name, String body) {
                                    return "";
                                }
                            }
                            """));

            boolean aptRejectsMethod = aptRejects(aptResult);

            // Parity assertion
            assertTrue(runtimeRejects, "formAndBodyMethod() should be rejected by runtime");
            assertTrue(aptRejectsMethod, "formAndBodyMethod() should produce ≥1 APT error");
            assertEquals(
                    runtimeRejects, aptRejectsMethod, "Parity breach: runtime and APT disagree on formAndBodyMethod()");
        }
    }

    // --- Parity tests — empty-@RolesAllowed override path (Fix 1 parity break) ---

    @Nested
    @DisplayName("parity: empty class-level @RolesAllowed + @PermitAll method — both accept")
    class EmptyRolesWithPermitAllOverride {

        @Test
        @DisplayName("permitted() — runtime accepts (class-level ignored), APT emits no errors")
        void emptyRolesClassLevel_methodPermitAll_accepted() throws Exception {
            // Runtime side: hasEmptyRolesAllowed returns false because method has @PermitAll.
            Method method = EmptyRolesOverrideResource.class.getMethod("permitted");
            boolean runtimeRejects = runtimeWouldReject(method, EmptyRolesOverrideResource.class);

            // APT side
            var aptResult = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.parity.EmptyRolesOverrideResource", """
                            package dev.vertique.test.parity;

                            import jakarta.annotation.security.PermitAll;
                            import jakarta.annotation.security.RolesAllowed;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/parity-empty-roles-override")
                            @RolesAllowed({})
                            public class EmptyRolesOverrideResource {
                                @GET @PermitAll public String permitted() { return ""; }
                            }
                            """));

            boolean aptRejectsMethod = aptRejects(aptResult);

            // Parity assertion — both must ACCEPT
            assertFalse(
                    runtimeRejects,
                    "permitted() should be accepted by runtime (method @PermitAll overrides class @RolesAllowed({}))");
            assertFalse(
                    aptRejectsMethod,
                    "permitted() should produce no APT errors (class-level @RolesAllowed({}) is ignored)");
            assertEquals(
                    runtimeRejects,
                    aptRejectsMethod,
                    "Parity breach: runtime and APT disagree on permitted() with empty class-level @RolesAllowed overridden by @PermitAll");
        }
    }

    @Nested
    @DisplayName("parity: empty class-level @RolesAllowed, no method override — both reject")
    class EmptyRolesEffectivePolicy {

        @Test
        @DisplayName("get() — runtime rejects (class-level is effective), APT emits ≥1 error")
        void emptyRolesClassLevel_noMethodOverride_rejected() throws Exception {
            // Runtime side: hasEmptyRolesAllowed returns true — class-level @RolesAllowed({}) is
            // the effective policy because the method has no security annotation.
            Method method = EmptyRolesEffectiveResource.class.getMethod("get");
            boolean runtimeRejects = runtimeWouldReject(method, EmptyRolesEffectiveResource.class);

            // APT side
            var aptResult = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.parity.EmptyRolesEffectiveResource", """
                            package dev.vertique.test.parity;

                            import jakarta.annotation.security.RolesAllowed;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/parity-empty-roles-effective")
                            @RolesAllowed({})
                            public class EmptyRolesEffectiveResource {
                                @GET public String get() { return ""; }
                            }
                            """));

            boolean aptRejectsMethod = aptRejects(aptResult);

            // Parity assertion — both must REJECT
            assertTrue(
                    runtimeRejects,
                    "get() should be rejected by runtime (class-level @RolesAllowed({}) is effective policy)");
            assertTrue(
                    aptRejectsMethod,
                    "get() should produce ≥1 APT error (class-level @RolesAllowed({}) is effective policy)");
            assertEquals(
                    runtimeRejects,
                    aptRejectsMethod,
                    "Parity breach: runtime and APT disagree on get() with effective empty class-level @RolesAllowed");
        }
    }

    @Nested
    @DisplayName("parity: two body parameters — both reject")
    class TwoBodiesConflict {

        @Test
        @DisplayName("twoBodiesMethod() — runtime rejects, APT emits ≥1 error")
        void twoBodies_runtimeRejectsAndAptEmitsError() throws Exception {
            // Runtime side
            Method method = TwoBodiesResource.class.getMethod("twoBodiesMethod", String.class, String.class);
            boolean runtimeRejects = runtimeWouldReject(method, TwoBodiesResource.class);

            // APT side
            var aptResult = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.parity.TwoBodiesResource", """
                            package dev.vertique.test.parity;

                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;

                            @Path("/parity-two-bodies")
                            public class TwoBodiesResource {
                                @POST public String twoBodiesMethod(String a, String b) { return ""; }
                            }
                            """));

            boolean aptRejectsMethod = aptRejects(aptResult);

            // Parity assertion — both must REJECT
            assertTrue(runtimeRejects, "twoBodiesMethod() should be rejected by runtime");
            assertTrue(aptRejectsMethod, "twoBodiesMethod() should produce ≥1 APT error");
            assertEquals(
                    runtimeRejects, aptRejectsMethod, "Parity breach: runtime and APT disagree on twoBodiesMethod()");
        }
    }

    // --- Parity tests — framework SecurityContext + body (Fix A) ---

    @Nested
    @DisplayName("parity: framework SecurityContext + body — both accept (Fix A)")
    class FrameworkSecurityContextParity {

        @Test
        @DisplayName("createWithSec(SecurityContext, body) — runtime accepts, APT emits no errors")
        void frameworkSecurityContext_runtimeAcceptsAndAptEmitsNoErrors() throws Exception {
            // Runtime side: SecurityContext is CONTEXT, body is one BODY — no violation.
            Method method = FrameworkSecurityContextResource.class.getMethod(
                    "createWithSec", dev.vertique.security.SecurityContext.class, String.class);
            boolean runtimeRejects = runtimeWouldReject(method, FrameworkSecurityContextResource.class);

            // APT side
            var aptResult = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.parity.FrameworkSecurityContextResource", """
                            package dev.vertique.test.parity;

                            import dev.vertique.security.SecurityContext;
                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;

                            @Path("/parity-framework-security-context")
                            public class FrameworkSecurityContextResource {
                                @POST
                                public String createWithSec(SecurityContext sec, String body) {
                                    return "";
                                }
                            }
                            """));

            boolean aptRejectsMethod = aptRejects(aptResult);

            // Parity assertion — both must ACCEPT
            assertFalse(
                    runtimeRejects,
                    "createWithSec(SecurityContext, body) should be accepted by runtime (SecurityContext is CONTEXT)");
            assertFalse(
                    aptRejectsMethod,
                    "createWithSec(SecurityContext, body) should produce no APT errors after Fix A FQN correction");
            assertEquals(
                    runtimeRejects,
                    aptRejectsMethod,
                    "Parity breach: runtime and APT disagree on createWithSec(SecurityContext, body)");
        }
    }

    // --- Parity tests — inherited body-conflict method (Fix D) ---

    @Nested
    @DisplayName("parity: inherited body-conflict method — APT error message uses declaring class (Fix D)")
    class InheritedBodyConflictParity {

        @Test
        @DisplayName(
                "inheritedConflict() via ChildBodyConflictResource — runtime rejects, APT rejects, message says BaseBodyConflictResource")
        void inheritedBodyConflict_aptErrorMessageUsesDeclaringClass() throws Exception {
            // Runtime side: two body params → rejected.
            Method method = BaseBodyConflictResource.class.getMethod("inheritedConflict", String.class, String.class);
            boolean runtimeRejects = runtimeWouldReject(method, ChildBodyConflictResource.class);

            // APT side — ChildBodyConflictResource is the @Path root; the method is inherited.
            // Fix D: the error message must include "BaseBodyConflictResource" (declaring class),
            // NOT "ChildBodyConflictResource" (the leaf @Path class).
            var aptResult = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.parity.BaseBodyConflictResource", """
                            package dev.vertique.test.parity;

                            import jakarta.ws.rs.POST;

                            public class BaseBodyConflictResource {
                                @POST public String inheritedConflict(String a, String b) { return ""; }
                            }
                            """),
                    SourceFiles.inline("dev.vertique.test.parity.ChildBodyConflictResource", """
                            package dev.vertique.test.parity;

                            import jakarta.ws.rs.Path;

                            @Path("/parity-inherited-conflict")
                            public class ChildBodyConflictResource extends BaseBodyConflictResource {}
                            """));

            boolean aptRejectsMethod = aptRejects(aptResult);

            // Parity assertion — both must REJECT
            assertTrue(runtimeRejects, "inheritedConflict() should be rejected by runtime");
            assertTrue(
                    aptRejectsMethod,
                    "inheritedConflict() should produce ≥1 APT error via ChildBodyConflictResource leaf");
            assertEquals(
                    runtimeRejects, aptRejectsMethod, "Parity breach: runtime and APT disagree on inheritedConflict()");

            // Additional Fix D assertion: error message names the declaring class, not the leaf
            boolean messageNamesDeclaringClass = aptResult.compilation().diagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .anyMatch(d -> {
                        String msg = d.getMessage(null);
                        return msg != null && msg.contains("BaseBodyConflictResource");
                    });
            assertTrue(
                    messageNamesDeclaringClass,
                    "APT error message should include 'BaseBodyConflictResource' (declaring class), not 'ChildBodyConflictResource'");
        }
    }
}
