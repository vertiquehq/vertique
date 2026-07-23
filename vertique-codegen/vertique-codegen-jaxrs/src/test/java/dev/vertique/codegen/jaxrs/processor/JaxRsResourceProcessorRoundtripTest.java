// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.codegen.jaxrs.JaxRsPipelineProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Roundtrip tests for {@link JaxRsPipelineProcessor} (formerly {@code JaxRsResourceProcessor})
 * that compile real source fixtures through the full processor pipeline.
 *
 * <p>Two scenarios are covered:
 * <ol>
 *   <li><strong>Clean fixture:</strong> a legal JAX-RS resource with correct annotation
 *       combinations; asserts compilation succeeds with zero ERROR diagnostics.</li>
 *   <li><strong>Faulty fixture:</strong> a resource with multiple deliberate violations;
 *       asserts that the expected number of distinct ERROR diagnostics are emitted and that
 *       each error message contains a recognisable substring.</li>
 * </ol>
 */
class JaxRsResourceProcessorRoundtripTest {

    // --- Clean fixture ---

    @Nested
    @DisplayName("clean fixture — no diagnostics")
    class CleanFixture {

        @Test
        @DisplayName("well-formed resource compiles clean with zero ERROR diagnostics")
        void cleanResource_compilesWithNoErrors() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.UserResource", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.RolesAllowed;
                            import jakarta.ws.rs.DELETE;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;
                            import jakarta.ws.rs.QueryParam;

                            @RolesAllowed("admin")
                            @Path("/users")
                            public class UserResource {

                                @GET
                                public String listUsers(@QueryParam("page") int page) {
                                    return "";
                                }

                                @GET
                                @Path("/{id}")
                                public String getUser(@PathParam("id") String id) {
                                    return "";
                                }

                                @POST
                                public String createUser(String body) {
                                    return "";
                                }

                                @DELETE
                                @Path("/{id}")
                                public void deleteUser(@PathParam("id") String id) {}
                            }
                            """));

            result.assertSuccess();
            long errorCount = result.compilation().diagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .count();
            assertEquals(0, errorCount, "Clean fixture must produce zero ERROR diagnostics");
        }
    }

    // --- Faulty fixture ---

    @Nested
    @DisplayName("faulty fixture — expected errors emitted")
    class FaultyFixture {

        @Test
        @DisplayName("fixture with violations on independent methods emits one error per method")
        void faultyResource_emitsExpectedErrorCount() {
            // Two methods, each with its own violation:
            //   - getItem: @PermitAll + @RolesAllowed (security conflict). This method also has
            //     a path placeholder {id} with no matching @PathParam — but runtime stops
            //     processing the method on the security violation, so the validator does too
            //     (continue-after-violation parity).
            //   - createItem: two body parameters (no security issues).
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.FaultyResource", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.PermitAll;
                            import jakarta.annotation.security.RolesAllowed;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.QueryParam;

                            @Path("/items")
                            public class FaultyResource {

                                // Security conflict — runtime stops processing this method here,
                                // so the unmatched @Path("{id}") placeholder must NOT also error.
                                @GET
                                @Path("/{id}")
                                @PermitAll
                                @RolesAllowed("admin")
                                public String getItem(@QueryParam("q") String q) {
                                    return "";
                                }

                                // Two body params on one method (independent violation).
                                @POST
                                public void createItem(String bodyA, String bodyB) {}
                            }
                            """));

            result.assertFailed();
            result.assertErrorMessage("Conflicting security annotations");
            result.assertErrorMessage("body parameters");

            long errorCount = result.compilation().diagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .count();
            assertEquals(2, errorCount, "Expected exactly 2 ERROR diagnostics (one per method); got: " + errorCount);
        }

        @Test
        @DisplayName("method with both security and body/form violations emits ONLY the security error")
        void securityAndBodyForm_onlySecurityReported() {
            // Mirrors runtime ResourceScanner.scanResource: when a method's security check fires,
            // runtime continues past the per-method scan and never invokes
            // RouteValidator.validateMethodParams. The validator must do the same — otherwise we
            // emit two errors where runtime emits one.
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.PermitAll;
                            import jakarta.annotation.security.RolesAllowed;
                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;

                            @Path("/r")
                            public class Res {
                                @POST
                                @PermitAll @RolesAllowed("admin")
                                public void create(String a, String b) {}
                            }
                            """));

            result.assertFailed();
            result.assertErrorMessage("Conflicting security annotations");

            long errorCount = result.compilation().diagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .count();
            assertEquals(1, errorCount, "Security violation must short-circuit body/form check; got: " + errorCount);
        }
    }

    // --- Skipped fixtures (runtime never reaches them) ---

    @Nested
    @DisplayName("non-mountable resources are silently skipped")
    class NonMountableSkipped {

        @Test
        @DisplayName("abstract @Path class with multiple violations emits zero diagnostics")
        void abstractResource_silentlySkipped() {
            // Abstract @Path classes can never be mounted at runtime: JaxRsRouteRegistrar.scanResource
            // only scans instantiated resource objects, and CG-002 AutoWireProcessor drops abstract
            // types in AnnotationRootedCollector. The processor must skip them so build errors do
            // not surface where runtime would never act on the class.
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.AbstractResource", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.PermitAll;
                            import jakarta.annotation.security.RolesAllowed;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;

                            @PermitAll @RolesAllowed("admin")
                            @Path("/abstract")
                            public abstract class AbstractResource {
                                // Would normally be a security conflict + multiple body params,
                                // but the entire class is abstract and never instantiated.
                                @GET @Path("/{id}")
                                public abstract String get();

                                @POST
                                public void create(String a, String b) {}
                            }
                            """));

            result.assertSuccess();
            long errorCount = result.compilation().diagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .count();
            assertEquals(0, errorCount, "Abstract @Path classes must be silently skipped");
        }
    }
}
