// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.processor.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link HttpVerbValidator}.
 *
 * <p>Tier-A parity tests: methods with zero HTTP verb annotations must be silently accepted.
 * Tier-B guardrail tests: methods with more than one verb annotation must produce an ERROR.
 */
class HttpVerbValidatorTest {

    // --- presentVerbs tests ---

    @Nested
    @DisplayName("presentVerbs")
    class PresentVerbs {

        @Test
        @DisplayName("single @GET → presentVerbs returns one-element list")
        void singleGet_returnsOneVerb() {
            var result = ProcessorTestHarness.run(new VerbProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/res")
                            public class Res {
                                @GET public String get() { return ""; }
                            }
                            """));
            result.assertSuccess();
            assertEquals(0, errorCount(result));
        }

        @Test
        @DisplayName("helper method without verb → presentVerbs returns empty (silently accepted)")
        void helperMethod_noVerb_silentlyAccepted() {
            var result = ProcessorTestHarness.run(new VerbProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/res")
                            public class Res {
                                @GET public String get() { return ""; }
                                public String helper() { return ""; }
                            }
                            """));
            result.assertSuccess();
            assertEquals(0, errorCount(result));
        }
    }

    // --- Tier-A parity: zero-verb methods silently accepted ---

    @Nested
    @DisplayName("Tier-A parity — zero-verb methods silently accepted")
    class TierAParity {

        @Test
        @DisplayName("sub-resource locator (@Path only, no verb) is valid")
        void subResourceLocator_noVerb_valid() {
            var result = ProcessorTestHarness.run(new VerbProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/res")
                            public class Res {
                                @GET public String get() { return ""; }
                                @Path("/sub")
                                public Object subLocator() { return null; }
                            }
                            """));
            result.assertSuccess();
            assertEquals(0, errorCount(result));
        }

        @Test
        @DisplayName("method with @QueryParam but no verb is silently accepted")
        void queryParamWithoutVerb_silentlyAccepted() {
            var result = ProcessorTestHarness.run(new VerbProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.QueryParam;

                            @Path("/res")
                            public class Res {
                                @GET public String get() { return ""; }
                                public String search(@QueryParam("q") String q) { return q; }
                            }
                            """));
            result.assertSuccess();
            assertEquals(0, errorCount(result));
        }

        @Test
        @DisplayName("equals/hashCode without verb are silently accepted")
        void equalsHashCode_noVerb_silentlyAccepted() {
            var result = ProcessorTestHarness.run(new VerbProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/res")
                            public class Res {
                                @GET public String get() { return ""; }
                                @Override public boolean equals(Object o) { return super.equals(o); }
                                @Override public int hashCode() { return super.hashCode(); }
                            }
                            """));
            result.assertSuccess();
            assertEquals(0, errorCount(result));
        }
    }

    // --- Tier-B guardrail: multiple verbs error ---

    @Nested
    @DisplayName("Tier-B guardrail — multiple verbs on one method")
    class TierBGuardrail {

        @Test
        @DisplayName("@GET + @POST on same method → ERROR")
        void getAndPost_error() {
            var result = ProcessorTestHarness.run(new VerbProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;

                            @Path("/res")
                            public class Res {
                                @GET @POST public String handle() { return ""; }
                            }
                            """));
            result.assertFailed();
            result.assertErrorMessage("declares multiple HTTP verb annotations");
            result.assertErrorMessage("@GET");
            result.assertErrorMessage("@POST");
        }

        @Test
        @DisplayName("@PUT + @DELETE on same method → ERROR")
        void putAndDelete_error() {
            var result = ProcessorTestHarness.run(new VerbProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.DELETE;
                            import jakarta.ws.rs.PUT;
                            import jakarta.ws.rs.Path;

                            @Path("/res")
                            public class Res {
                                @PUT @DELETE public String handle() { return ""; }
                            }
                            """));
            result.assertFailed();
            result.assertErrorMessage("declares multiple HTTP verb annotations");
        }

        @Test
        @DisplayName("single @DELETE → no error")
        void singleDelete_noError() {
            var result = ProcessorTestHarness.run(new VerbProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.DELETE;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/res")
                            public class Res {
                                @DELETE @Path("/{id}") public void delete(@PathParam("id") String id) {}
                            }
                            """));
            result.assertSuccess();
            assertEquals(0, errorCount(result));
        }

        @Test
        @DisplayName("@HEAD + @OPTIONS on same method → ERROR")
        void headAndOptions_error() {
            var result = ProcessorTestHarness.run(new VerbProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.HEAD;
                            import jakarta.ws.rs.OPTIONS;
                            import jakarta.ws.rs.Path;

                            @Path("/res")
                            public class Res {
                                @HEAD @OPTIONS public String handle() { return ""; }
                            }
                            """));
            result.assertFailed();
            result.assertErrorMessage("declares multiple HTTP verb annotations");
            result.assertErrorMessage("@HEAD");
            result.assertErrorMessage("@OPTIONS");
        }

        @Test
        @DisplayName("@GET + @HEAD on same method → ERROR")
        void getAndHead_error() {
            var result = ProcessorTestHarness.run(new VerbProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.HEAD;
                            import jakarta.ws.rs.Path;

                            @Path("/res")
                            public class Res {
                                @GET @HEAD public String handle() { return ""; }
                            }
                            """));
            result.assertFailed();
            result.assertErrorMessage("declares multiple HTTP verb annotations");
        }
    }

    // --- Helper ---

    private int errorCount(ProcessorTestHarness.Result result) {
        return (int) result.compilation().diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .filter(d -> {
                    String msg = d.getMessage(null);
                    return msg != null && msg.contains("multiple HTTP verb annotations");
                })
                .count();
    }

    // --- Probe processor ---

    /**
     * Probe processor that runs {@link HttpVerbValidator} against every {@code @Path}-annotated
     * class and its methods.
     */
    static final class VerbProbe extends AbstractProcessor {

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("jakarta.ws.rs.Path");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
            CodegenContext ctx = new CodegenContext(processingEnv);
            HttpVerbValidator validator = new HttpVerbValidator(ctx);

            for (Element e : env.getElementsAnnotatedWith(
                    processingEnv.getElementUtils().getTypeElement("jakarta.ws.rs.Path"))) {
                if (!(e instanceof TypeElement resource)) continue;
                if (resource.getKind() == ElementKind.INTERFACE) continue;

                for (Element enclosed : resource.getEnclosedElements()) {
                    if (enclosed.getKind() != ElementKind.METHOD) continue;
                    ExecutableElement method = (ExecutableElement) enclosed;
                    validator.validate(method, validator.presentVerbs(method));
                }
            }
            return false;
        }
    }
}
