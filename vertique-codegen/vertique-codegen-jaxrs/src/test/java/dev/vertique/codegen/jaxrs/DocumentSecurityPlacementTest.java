// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.Objects;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code @OpenAPIDefinition} with a non-empty {@code security} fails compilation on any type that is
 * not a {@code @RestApplication} declaring interface, because the runtime enforces document-level
 * security only from the declaring interface.
 */
class DocumentSecurityPlacementTest {

    private static final String PHRASE = "carries @OpenAPIDefinition(security), which secures nothing at runtime";

    private static JavaFileObject resource(String packageName) {
        return SourceFiles.inline(packageName + ".PathResource", """
                package %s;

                import jakarta.inject.Inject;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;

                @Path("/p")
                public class PathResource {
                    @Inject
                    public PathResource() {}

                    @GET
                    public String get() { return ""; }
                }
                """.formatted(packageName));
    }

    private static JavaFileObject config(String packageName, String definition) {
        return SourceFiles.inline(packageName + ".OpenApiConfig", """
                package %s;

                import io.swagger.v3.oas.annotations.OpenAPIDefinition;
                import io.swagger.v3.oas.annotations.info.Info;
                import io.swagger.v3.oas.annotations.security.SecurityRequirement;

                %s
                public class OpenApiConfig {}
                """.formatted(packageName, definition));
    }

    @Test
    @DisplayName("A stand-alone configuration class with @OpenAPIDefinition(security) fails compilation")
    void standAloneConfigWithSecurityFails() {
        String pkg = "test.docsecurity.standalone";
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                resource(pkg),
                config(pkg, "@OpenAPIDefinition(security = @SecurityRequirement(name = \"bearerAuth\"))"));
        result.assertFailed();
        assertTrue(
                result.compilation().diagnostics().stream()
                        .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                        .map(d -> d.getMessage(null))
                        .filter(Objects::nonNull)
                        .anyMatch(m -> m.contains(pkg + ".OpenApiConfig") && m.contains(PHRASE)),
                () -> "expected the placement error naming the class: "
                        + result.compilation().diagnostics());
    }

    @Test
    @DisplayName("A resource class with @OpenAPIDefinition(security) fails compilation")
    void resourceWithSecurityFails() {
        String pkg = "test.docsecurity.resource";
        JavaFileObject source = SourceFiles.inline(pkg + ".SecuredResource", """
                package %s;

                import io.swagger.v3.oas.annotations.OpenAPIDefinition;
                import io.swagger.v3.oas.annotations.security.SecurityRequirement;
                import jakarta.inject.Inject;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;

                @Path("/s")
                @OpenAPIDefinition(security = @SecurityRequirement(name = "bearerAuth"))
                public class SecuredResource {
                    @Inject
                    public SecuredResource() {}

                    @GET
                    public String get() { return ""; }
                }
                """.formatted(pkg));
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), source);
        result.assertFailed();
    }

    @Test
    @DisplayName("An info-only stand-alone @OpenAPIDefinition still compiles")
    void infoOnlyConfigCompiles() {
        String pkg = "test.docsecurity.infoonly";
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                resource(pkg),
                config(pkg, "@OpenAPIDefinition(info = @Info(title = \"t\", version = \"1\"), security = {})"));
        result.assertSuccess();
    }
}
