// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every class the JAX-RS pipeline writes carries the class-file marker
 * {@code dev.vertique.core.codegen.Generated}, so JaCoCo leaves it out of coverage. The
 * {@code javax} marker on the same types has {@code SOURCE} retention and is invisible to it.
 *
 * <p>The fixture compiles against the real marker in {@code vertique-core}, so a placement its
 * targets forbid would fail the compilation.
 */
class GeneratedClassFileMarkerTest {

    private static final String QUALIFIED_MARKER = "@dev.vertique.core.codegen.Generated\n";

    @Test
    @DisplayName("descriptor, execution plan, bean-param model and parameter metadata carry the marker")
    void everyGeneratedJaxRsClassCarriesTheClassFileMarker() {
        var result = ProcessorTestHarness.run(
                        new JaxRsPipelineProcessor(),
                        SourceFiles.inline("dev.vertique.test.MarkerParams", """
                                package dev.vertique.test;

                                import jakarta.ws.rs.QueryParam;

                                public record MarkerParams(@QueryParam("q") String query) {}
                                """),
                        SourceFiles.inline("dev.vertique.test.MarkerResource", """
                                package dev.vertique.test;

                                import jakarta.ws.rs.BeanParam;
                                import jakarta.ws.rs.GET;
                                import jakarta.ws.rs.Path;
                                import jakarta.ws.rs.PathParam;

                                @Path("/marker/{id}")
                                public class MarkerResource {
                                    public MarkerResource() {}

                                    @GET
                                    public String get(@PathParam("id") String id, @BeanParam MarkerParams params) {
                                        return id;
                                    }
                                }
                                """))
                .assertSuccess();

        // These types also carry the javax marker, so the class-file marker is written qualified.
        result.assertGeneratedSourceContains("dev.vertique.test.MarkerResource_JaxRsDescriptor", QUALIFIED_MARKER)
                .assertGeneratedSourceContains("dev.vertique.test.MarkerResource_get_0_ExecutionPlan", QUALIFIED_MARKER)
                .assertGeneratedSourceContains("dev.vertique.test.MarkerParams_BeanParamModel", QUALIFIED_MARKER)
                // Parameter metadata has no javax marker: the class-file marker is imported.
                .assertGeneratedSourceContains(
                        "dev.vertique.test.MarkerResource_JaxRsDescriptor_M0P0Meta",
                        "import dev.vertique.core.codegen.Generated;")
                .assertGeneratedSourceContains(
                        "dev.vertique.test.MarkerResource_JaxRsDescriptor_M0P0Meta", "@Generated\npublic");
    }
}
