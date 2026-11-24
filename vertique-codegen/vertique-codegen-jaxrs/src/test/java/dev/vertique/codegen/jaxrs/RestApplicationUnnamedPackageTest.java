// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * With {@code -Avertique.codegen.autoWire=false}, a compilation unit entirely in the unnamed
 * package resolves to the unnamed package: it is one package, so a package-private resource and a
 * package-private declaration are accessible to each other. (With auto-wiring on, the unnamed
 * package is a compile error asking for {@code -Avertique.codegen.package}; not covered here.)
 */
class RestApplicationUnnamedPackageTest {

    private static final Map<String, String> AUTO_WIRE_OFF = Map.of("vertique.codegen.autoWire", "false");

    private static JavaFileObject resource(String modifier) {
        return SourceFiles.inline("PathResource", """
                import jakarta.inject.Inject;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;

                @Path("/p")
                %s class PathResource {
                    @Inject
                    public PathResource() {}

                    @GET
                    public String get() { return ""; }
                }
                """.formatted(modifier));
    }

    private static JavaFileObject declaration() {
        return SourceFiles.inline("Api", """
                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "api", path = "/api", resources = PathResource.class)
                interface Api {}
                """);
    }

    private static List<String> diagnostics(ProcessorTestHarness.Result result) {
        return result.compilation().diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR || d.getKind() == Diagnostic.Kind.WARNING)
                .map(d -> d.getKind() + " " + d.getMessage(null))
                .toList();
    }

    private static final String OPT_OUT_WARNING =
            "WARNING Api is not registered as a REST application because -Avertique.codegen.autoWire=false is set.";

    private static void assertUnnamedPackageAccepted(String modifier) {
        var off = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), AUTO_WIRE_OFF, resource(modifier), declaration());
        assertEquals(
                List.of(OPT_OUT_WARNING),
                diagnostics(off),
                "autoWire off, " + modifier + " resource: the unnamed package must not read as 'no package'");
    }

    @Test
    @DisplayName("autoWire off: a public resource in the unnamed package raises no accessibility error")
    void publicResource() {
        assertUnnamedPackageAccepted("public");
    }

    @Test
    @DisplayName("autoWire off: a package-private resource in the unnamed package raises no accessibility error")
    void packagePrivateResource() {
        assertUnnamedPackageAccepted("");
    }

    @Test
    @DisplayName("autoWire off: genuinely disjoint packages still resolve to no package")
    void disjointPackagesStillRejectPackagePrivate() {
        JavaFileObject first = SourceFiles.inline("a.First", """
                package a;

                import jakarta.inject.Inject;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;

                @Path("/first")
                public class First {
                    @Inject
                    public First() {}

                    @GET
                    public String get() { return ""; }
                }
                """);
        JavaFileObject second = SourceFiles.inline("b.Second", """
                package b;

                import jakarta.inject.Inject;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;

                @Path("/second")
                public class Second {
                    @Inject
                    public Second() {}

                    @GET
                    public String get() { return ""; }
                }
                """);
        JavaFileObject api = SourceFiles.inline("a.Api", """
                package a;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "api", path = "/api", resources = First.class)
                interface Api {}
                """);
        var off = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), AUTO_WIRE_OFF, first, second, api);
        assertEquals(
                List.of(
                        "ERROR a.Api is not accessible: no generated-module package could be resolved, so it and its enclosing types must be public.",
                        OPT_OUT_WARNING.replace("Api ", "a.Api ")),
                diagnostics(off));
    }
}
