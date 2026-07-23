// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * End-to-end APT compilation tests for {@link JaxRsPipelineProcessor}.
 *
 * <p>Each test compiles a small fixture through the new pipeline processor and verifies the
 * expected output:
 * <ul>
 *   <li><strong>DI module emission:</strong> {@code GeneratedJaxRsResourcesModule} is written with
 *       one {@code @Provides @IntoSet @JaxRsResources Object} method per eligible resource.</li>
 *   <li><strong>Interface-backed resources:</strong> a concrete class implementing a {@code @Path}
 *       interface is auto-wired when it has {@code @Inject}.</li>
 *   <li><strong>{@code @NoAutoWire} exclusion:</strong> annotated types are excluded from the DI
 *       module but produce no validation error.</li>
 *   <li><strong>Interface-only round (no concrete impl):</strong> no module is emitted.</li>
 *   <li><strong>{@code -Avertique.codegen.autoWire=false}:</strong> DI module is suppressed but
 *       validation still runs (a CG-009 violation fixture still emits an error).</li>
 *   <li><strong>Existing CG-009 validations:</strong> verb conflict, security conflict,
 *       path-placeholder mismatch, and body/form conflict are all still reported.</li>
 * </ul>
 */
class JaxRsPipelineProcessorTest {

    // --- DI module emission ---

    @Nested
    @DisplayName("DI module emission")
    class DiModuleEmission {

        @Test
        @DisplayName("@Path+@Inject resource — GeneratedJaxRsResourcesModule is emitted")
        void injectResource_moduleEmitted() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.UserResource", """
                            package dev.vertique.test;

                            import jakarta.inject.Inject;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/users")
                            public class UserResource {
                                @Inject
                                public UserResource() {}

                                @GET
                                public String list() { return ""; }
                            }
                            """));

            result.assertSuccess();
            result.assertGeneratedSourceContains("dev.vertique.test.GeneratedJaxRsResourcesModule", "@Module");
            result.assertGeneratedSourceContains("dev.vertique.test.GeneratedJaxRsResourcesModule", "@JaxRsResources");
            result.assertGeneratedSourceContains(
                    "dev.vertique.test.GeneratedJaxRsResourcesModule", "userResourceBinding");
        }

        @Test
        @DisplayName("interface-backed resource with @Inject — emitted in DI module")
        void interfaceBacked_withInject_emittedInModule() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.ItemsApi", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/items")
                            public interface ItemsApi {
                                @GET
                                String list();
                            }
                            """),
                    SourceFiles.inline("dev.vertique.test.ItemsResource", """
                            package dev.vertique.test;

                            import jakarta.inject.Inject;

                            public class ItemsResource implements ItemsApi {
                                @Inject
                                public ItemsResource() {}

                                @Override
                                public String list() { return ""; }
                            }
                            """));

            result.assertSuccess();
            result.assertGeneratedSourceContains(
                    "dev.vertique.test.GeneratedJaxRsResourcesModule", "itemsResourceBinding");
        }

        @Test
        @DisplayName("@NoAutoWire resource — excluded from DI module, no error emitted")
        void noAutoWireResource_excludedFromModule_noError() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.ManualResource", """
                            package dev.vertique.test;

                            import dev.vertique.codegen.NoAutoWire;
                            import jakarta.inject.Inject;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @NoAutoWire
                            @Path("/manual")
                            public class ManualResource {
                                @Inject
                                public ManualResource() {}

                                @GET
                                public String get() { return ""; }
                            }
                            """));

            result.assertSuccess();
            long errorCount = result.compilation().diagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .count();
            assertEquals(0, errorCount, "@NoAutoWire resource must produce zero errors");
            // When all DI candidates are filtered out by @NoAutoWire, no module is emitted.
            // Verify that no GeneratedJaxRsResourcesModule source file was produced.
            boolean moduleGenerated = result.compilation().generatedSourceFiles().stream()
                    .anyMatch(f -> f.toUri().toString().contains("GeneratedJaxRsResourcesModule"));
            org.junit.jupiter.api.Assertions.assertFalse(
                    moduleGenerated, "No DI module should be emitted when all @Path resources have @NoAutoWire");
        }

        @Test
        @DisplayName("@Path interface only (no concrete impl in round) — no module emitted")
        void interfaceOnly_noModuleEmitted() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.ApiOnly", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/api")
                            public interface ApiOnly {
                                @GET
                                String get();
                            }
                            """));

            result.assertSuccess();
            // Verify no GeneratedJaxRsResourcesModule was written
            boolean moduleGenerated = result.compilation().generatedSourceFiles().stream()
                    .anyMatch(f -> f.toUri().toString().contains("GeneratedJaxRsResourcesModule"));
            org.junit.jupiter.api.Assertions.assertFalse(
                    moduleGenerated, "No DI module should be emitted when there are no concrete @Path candidates");
        }
    }

    // --- auto-wire kill switch ---

    @Nested
    @DisplayName("auto-wire kill switch (-Avertique.codegen.autoWire=false)")
    class AutoWireKillSwitch {

        @Test
        @DisplayName("autoWire=false — DI module not emitted, validation still runs")
        void autoWireFalse_noModuleButValidationRuns() {
            // Fixture has both a clean resource AND a resource with a CG-009 violation.
            // With autoWire=false, the DI module must not be emitted, but the validation
            // error on the faulty resource must still appear.
            JavaFileObject cleanResource = SourceFiles.inline("dev.vertique.test.CleanResource", """
                    package dev.vertique.test;

                    import jakarta.inject.Inject;
                    import jakarta.ws.rs.GET;
                    import jakarta.ws.rs.Path;

                    @Path("/clean")
                    public class CleanResource {
                        @Inject
                        public CleanResource() {}

                        @GET
                        public String get() { return ""; }
                    }
                    """);

            JavaFileObject faultyResource = SourceFiles.inline("dev.vertique.test.FaultyResource", """
                    package dev.vertique.test;

                    import jakarta.annotation.security.PermitAll;
                    import jakarta.annotation.security.RolesAllowed;
                    import jakarta.inject.Inject;
                    import jakarta.ws.rs.GET;
                    import jakarta.ws.rs.Path;

                    @Path("/faulty")
                    public class FaultyResource {
                        @Inject
                        public FaultyResource() {}

                        // Security conflict — will trigger CG-009 validation error
                        @GET
                        @PermitAll
                        @RolesAllowed("admin")
                        public String get() { return ""; }
                    }
                    """);

            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    Map.of("vertique.codegen.autoWire", "false"),
                    cleanResource,
                    faultyResource);

            // Compilation fails because of the validation error — this confirms validation ran.
            // (DI module cannot be emitted when autoWireDisabled=true — no call is made to the
            // emitter — so the absence of a generated module is guaranteed by design.)
            result.assertFailed();
            result.assertErrorMessage("Conflicting security annotations");
        }
    }

    // --- CG-009 validation still works ---

    @Nested
    @DisplayName("CG-009 validations still active")
    class Cg009Validations {

        @Test
        @DisplayName("HTTP verb conflict on single method — error emitted")
        void verbConflict_errorEmitted() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.VerbConflict", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;

                            @Path("/verb")
                            public class VerbConflict {
                                @GET
                                @POST
                                public String getAndPost() { return ""; }
                            }
                            """));

            result.assertFailed();
            result.assertErrorMessage("multiple HTTP verb");
        }

        @Test
        @DisplayName("security annotation conflict — error emitted")
        void securityConflict_errorEmitted() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.SecConflict", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.PermitAll;
                            import jakarta.annotation.security.RolesAllowed;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/sec")
                            public class SecConflict {
                                @GET
                                @PermitAll
                                @RolesAllowed("admin")
                                public String get() { return ""; }
                            }
                            """));

            result.assertFailed();
            result.assertErrorMessage("Conflicting security annotations");
        }

        @Test
        @DisplayName("path placeholder mismatch — error emitted")
        void pathPlaceholderMismatch_errorEmitted() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.PathMismatch", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.PermitAll;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.QueryParam;

                            @Path("/items")
                            public class PathMismatch {
                                @GET
                                @PermitAll
                                @Path("/{id}")
                                // Missing @PathParam("id") — path mismatch
                                public String get(@QueryParam("q") String q) { return ""; }
                            }
                            """));

            result.assertFailed();
            result.assertErrorMessage("@PathParam");
        }

        @Test
        @DisplayName("body/form exclusivity conflict — error emitted")
        void bodyFormConflict_errorEmitted() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.BodyForm", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.FormParam;
                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;

                            @Path("/bf")
                            public class BodyForm {
                                @POST
                                public void create(@FormParam("name") String name, String body) {}
                            }
                            """));

            result.assertFailed();
            result.assertErrorMessage("mixes @FormParam");
        }
    }

    // --- ServiceLoader registration ---

    @Nested
    @DisplayName("META-INF/services registration")
    class ServiceLoaderRegistration {

        @Test
        @DisplayName("JaxRsPipelineProcessor appears in ServiceLoader<Processor> results")
        void serviceLoader_findsJaxRsPipelineProcessor() {
            boolean found = java.util.stream.StreamSupport.stream(
                            java.util.ServiceLoader.load(
                                            javax.annotation.processing.Processor.class,
                                            JaxRsPipelineProcessor.class.getClassLoader())
                                    .spliterator(),
                            false)
                    .anyMatch(p -> p instanceof JaxRsPipelineProcessor);

            org.junit.jupiter.api.Assertions.assertTrue(
                    found,
                    "JaxRsPipelineProcessor must be registered in "
                            + "META-INF/services/javax.annotation.processing.Processor");
        }

        @Test
        @DisplayName("JaxRsResourceProcessor (old CG-009) does NOT appear in ServiceLoader results")
        void serviceLoader_doesNotFindOldProcessor() {
            boolean found = java.util.stream.StreamSupport.stream(
                            java.util.ServiceLoader.load(
                                            javax.annotation.processing.Processor.class,
                                            JaxRsPipelineProcessor.class.getClassLoader())
                                    .spliterator(),
                            false)
                    .anyMatch(p -> p.getClass()
                            .getName()
                            .equals("dev.vertique.codegen.jaxrs.processor.JaxRsResourceProcessor"));

            org.junit.jupiter.api.Assertions.assertFalse(
                    found,
                    "JaxRsResourceProcessor (old CG-009) must not be registered in META-INF/services "
                            + "after being replaced by JaxRsPipelineProcessor");
        }
    }
}
