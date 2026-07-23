// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.processor.validate;

import dev.vertique.codegen.jaxrs.JaxRsPipelineProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ContextParamValidator} wired through {@link JaxRsPipelineProcessor}.
 *
 * <p>Verifies that the compile-time context-param rules mirror the runtime
 * {@code RouteValidator.addContextParamViolations} logic (FR-REST-187/188/189), including the
 * split-inherited annotation case where {@code @Context} appears on the interface method parameter
 * while a value-binding annotation appears on the concrete parameter (or vice versa).
 *
 * <p>Short-circuit behaviour is also verified: when a {@code @Context} conflict is detected, the
 * path-alignment validator must not emit an additional diagnostic for the same parameter.
 */
class ContextParamValidatorTest {

    // --- Jakarta SecurityContext subtype → NON_INJECTABLE (exact match required) ---

    @Nested
    @DisplayName("jakarta SecurityContext subtype → NON_INJECTABLE error (exact match required)")
    class JakartaSecurityContextSubtype {

        @Test
        @DisplayName("@Context CustomJaxRsSc (extends jakarta SecurityContext) → NON_INJECTABLE")
        void jakartaSecurityContextSubtype_nonInjectable() {
            // A custom interface that extends jakarta.ws.rs.core.SecurityContext must be rejected:
            // the resolver (JaxRsSecurityContextResolver) only resolves the EXACT jakarta type.
            // Passing an assignability check at scanner/codegen time while failing at runtime
            // is the parity bug this test exercises.
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.CustomJaxRsSc", """
                            package dev.vertique.test;

                            public interface CustomJaxRsSc extends jakarta.ws.rs.core.SecurityContext {}
                            """),
                    SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.core.Context;

                            @Path("/res")
                            public class Res {
                                @GET
                                public String get(@Context CustomJaxRsSc sc) { return ""; }
                            }
                            """));
            result.assertFailed();
            result.assertErrorMessage("@Context injects RoutingContext, JAX-RS SecurityContext");
            result.assertErrorMessage("CustomJaxRsSc");
        }
    }

    // --- Non-injectable type → NON_INJECTABLE ---

    @Nested
    @DisplayName("non-injectable @Context type → NON_INJECTABLE error")
    class NonInjectable {

        @Test
        @DisplayName("@Context PaymentService (arbitrary class) → NON_INJECTABLE")
        void arbitraryClass_nonInjectable() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.PaymentService", """
                            package dev.vertique.test;
                            public class PaymentService {}
                            """),
                    SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.core.Context;

                            @Path("/res")
                            public class Res {
                                @GET
                                public String get(@Context PaymentService svc) { return ""; }
                            }
                            """));
            result.assertFailed();
            result.assertErrorMessage("Unsupported @Context parameter");
            result.assertErrorMessage("PaymentService");
        }

        @Test
        @DisplayName("@Context String → NON_INJECTABLE")
        void string_nonInjectable() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.core.Context;

                            @Path("/res")
                            public class Res {
                                @GET
                                public String get(@Context String value) { return ""; }
                            }
                            """));
            result.assertFailed();
            result.assertErrorMessage("Unsupported @Context parameter");
            result.assertErrorMessage("String");
        }
    }

    // --- Reserved JAX-RS type → UNSUPPORTED ---

    @Nested
    @DisplayName("reserved JAX-RS type → UNSUPPORTED error")
    class ReservedType {

        @Test
        @DisplayName("@Context jakarta.ws.rs.core.UriInfo → UNSUPPORTED")
        void uriInfo_unsupported() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.core.Context;
                            import jakarta.ws.rs.core.UriInfo;

                            @Path("/res")
                            public class Res {
                                @GET
                                public String get(@Context UriInfo ui) { return ""; }
                            }
                            """));
            result.assertFailed();
            result.assertErrorMessage("reserved JAX-RS context type");
            result.assertErrorMessage("UriInfo");
        }
    }

    // --- @Context + value-binding conflict → CONFLICT, no path-alignment error ---

    @Nested
    @DisplayName("@Context + value-binding annotation → CONFLICT error (short-circuit)")
    class Conflict {

        @Test
        @DisplayName("@Context @PathParam(\"id\") String id → CONFLICT, no path-alignment diagnostic")
        void contextAndPathParam_conflictOnly() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;
                            import jakarta.ws.rs.core.Context;

                            @Path("/res")
                            public class Res {
                                @GET @Path("/{id}")
                                public String get(@Context @PathParam("id") String id) { return ""; }
                            }
                            """));
            result.assertFailed();
            // Must emit the conflict diagnostic
            result.assertErrorMessage("also carries a value-binding annotation");
            // Must NOT emit a path-alignment diagnostic (short-circuit)
            boolean hasPathAlignmentError = result.compilation().diagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> d.getMessage(null))
                    .anyMatch(msg -> msg != null && msg.contains("matching @PathParam"));
            if (hasPathAlignmentError) {
                throw new org.opentest4j.AssertionFailedError(
                        "Path-alignment diagnostic should not be emitted when context-conflict is present");
            }
        }

        @Test
        @DisplayName("@PathParam on a ContextValue type (no explicit @Context) → CONFLICT (runtime parity)")
        void bindingAnnotationOnInjectableType_conflict() {
            // The param is classified CONTEXT *by type* (Tenant implements ContextValue) even without
            // an explicit @Context. The runtime RouteValidator rejects this as CONTEXT_PARAM_CONFLICT;
            // the compile-time validator must agree (FR-REST-187 parity), not silently pass.
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.Tenant", """
                            package dev.vertique.test;

                            import dev.vertique.core.context.ContextValue;

                            public record Tenant(String id) implements ContextValue {}
                            """),
                    SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/res")
                            public class Res {
                                @GET @Path("/{tenant}")
                                public String get(@PathParam("tenant") Tenant tenant) { return ""; }
                            }
                            """));
            result.assertFailed();
            result.assertErrorMessage("also carries a value-binding annotation");
        }
    }

    // --- Split inherited annotations → CONFLICT ---

    @Nested
    @DisplayName("split inherited annotations → CONFLICT error")
    class SplitInherited {

        @Test
        @DisplayName("interface @Context, impl @PathParam → CONFLICT")
        void interfaceContext_implPathParam_conflict() {
            // Interface declares @Context on the parameter; the concrete impl carries @PathParam.
            // The split means the classifier sees @PathParam first on the concrete param and
            // classifies the source as PATH, not CONTEXT — but the validator must still detect
            // the conflict via the effective-context check.
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.ResApi", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.core.Context;

                            @Path("/res")
                            public interface ResApi {
                                @GET @Path("/{id}")
                                String get(@Context String id);
                            }
                            """),
                    SourceFiles.inline("dev.vertique.test.ResImpl", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.PathParam;

                            public class ResImpl implements ResApi {
                                @Override
                                public String get(@PathParam("id") String id) { return ""; }
                            }
                            """));
            result.assertFailed();
            result.assertErrorMessage("also carries a value-binding annotation");
        }

        @Test
        @DisplayName("interface @PathParam, impl @Context → CONFLICT")
        void interfacePathParam_implContext_conflict() {
            // Interface declares @PathParam; the concrete impl carries @Context.
            // The classifier classifies the param as CONTEXT (due to @Context on concrete),
            // and the interface carries a binding annotation — conflict.
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.ResApi", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/res")
                            public interface ResApi {
                                @GET @Path("/{id}")
                                String get(@PathParam("id") String id);
                            }
                            """),
                    SourceFiles.inline("dev.vertique.test.ResImpl", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.core.Context;

                            public class ResImpl implements ResApi {
                                @Override
                                public String get(@Context String id) { return ""; }
                            }
                            """));
            result.assertFailed();
            result.assertErrorMessage("also carries a value-binding annotation");
        }
    }

    // --- Valid @Context parameters → no error ---

    @Nested
    @DisplayName("valid @Context parameters → no error")
    class Valid {

        @Test
        @DisplayName("@Context RoutingContext → success")
        void routingContext_valid() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import io.vertx.ext.web.RoutingContext;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.core.Context;

                            @Path("/res")
                            public class Res {
                                @GET
                                public String get(@Context RoutingContext rc) { return ""; }
                            }
                            """));
            result.assertSuccess();
        }

        @Test
        @DisplayName("@Context jakarta.ws.rs.core.SecurityContext → success")
        void jaxrsSecurityContext_valid() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.core.Context;
                            import jakarta.ws.rs.core.SecurityContext;

                            @Path("/res")
                            public class Res {
                                @GET
                                public String get(@Context SecurityContext sc) { return ""; }
                            }
                            """));
            result.assertSuccess();
        }

        @Test
        @DisplayName("@Context framework dev.vertique.security.SecurityContext → success")
        void frameworkSecurityContext_valid() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import dev.vertique.security.SecurityContext;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.core.Context;

                            @Path("/res")
                            public class Res {
                                @GET
                                public String get(@Context SecurityContext sc) { return ""; }
                            }
                            """));
            result.assertSuccess();
        }

        @Test
        @DisplayName("@Context ContextValue subtype (record Tenant) → success")
        void contextValueSubtype_valid() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.Tenant", """
                            package dev.vertique.test;

                            import dev.vertique.core.context.ContextValue;

                            /** Tenant context value for testing. */
                            public record Tenant(String id) implements ContextValue {}
                            """),
                    SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.core.Context;

                            @Path("/res")
                            public class Res {
                                @GET
                                public String get(@Context Tenant tenant) { return ""; }
                            }
                            """));
            result.assertSuccess();
        }
    }
}
