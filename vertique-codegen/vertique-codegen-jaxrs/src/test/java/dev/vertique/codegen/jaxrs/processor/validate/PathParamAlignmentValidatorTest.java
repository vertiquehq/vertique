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
 * Tests for {@link PathParamAlignmentValidator}.
 *
 * <p>Validates bidirectional alignment between {@code @Path} placeholders and {@code @PathParam}
 * declarations, including composite parameters ({@code @BeanParam} and {@code @RequestParams}
 * types) and regex-constrained placeholders.
 */
class PathParamAlignmentValidatorTest {

    // --- Missing @PathParam for placeholder ---

    @Nested
    @DisplayName("placeholder without @PathParam")
    class PlaceholderWithoutParam {

        @Test
        @DisplayName("placeholder {id} with no @PathParam → ERROR")
        void placeholder_noPathParam_error() {
            var result =
                    ProcessorTestHarness.run(new AlignmentProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/res")
                            public class Res {
                                @GET @Path("/{id}")
                                public String get() { return ""; }
                            }
                            """));
            result.assertFailed();
            result.assertErrorMessage("@Path placeholder '{id}'");
            result.assertErrorMessage("no matching @PathParam");
        }

        @Test
        @DisplayName("class + method path both have placeholders, one missing → ERROR")
        void classPlusMethodPath_oneMissing_error() {
            var result =
                    ProcessorTestHarness.run(new AlignmentProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/tenant/{tenantId}")
                            public class Res {
                                @GET @Path("/{id}")
                                public String get(@PathParam("tenantId") String tenantId) { return ""; }
                            }
                            """));
            result.assertFailed();
            result.assertErrorMessage("'{id}'");
        }
    }

    // --- @PathParam without placeholder ---

    @Nested
    @DisplayName("@PathParam without placeholder")
    class ParamWithoutPlaceholder {

        @Test
        @DisplayName("@PathParam with no matching placeholder → ERROR")
        void pathParam_noPlaceholder_error() {
            var result =
                    ProcessorTestHarness.run(new AlignmentProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/res")
                            public class Res {
                                @GET @Path("/items")
                                public String get(@PathParam("id") String id) { return ""; }
                            }
                            """));
            result.assertFailed();
            result.assertErrorMessage("@PathParam(\"id\")");
            result.assertErrorMessage("no matching placeholder");
        }
    }

    // --- Regex placeholder ---

    @Nested
    @DisplayName("regex-constrained placeholder")
    class RegexPlaceholder {

        @Test
        @DisplayName("{id:[0-9]+} matches @PathParam(\"id\") → no error")
        void regexPlaceholder_matchesPathParam_noError() {
            var result =
                    ProcessorTestHarness.run(new AlignmentProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/res")
                            public class Res {
                                @GET @Path("/{id:[0-9]+}")
                                public String get(@PathParam("id") String id) { return ""; }
                            }
                            """));
            result.assertSuccess();
            assertEquals(0, errorCount(result));
        }
    }

    // --- @BeanParam composite ---

    @Nested
    @DisplayName("@BeanParam composite contributes @PathParam names")
    class BeanParamComposite {

        @Test
        @DisplayName("@BeanParam class with @PathParam field → aligned")
        void beanParam_pathParamField_aligned() {
            var result = ProcessorTestHarness.run(
                    new AlignmentProbe(),
                    SourceFiles.inline("dev.vertique.test.IdBean", """
                            package dev.vertique.test;
                            import jakarta.ws.rs.PathParam;
                            public class IdBean {
                                @PathParam("id") public String id;
                            }
                            """),
                    SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.BeanParam;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/res")
                            public class Res {
                                @GET @Path("/{id}")
                                public String get(@BeanParam IdBean bean) { return ""; }
                            }
                            """));
            result.assertSuccess();
            assertEquals(0, errorCount(result));
        }

        @Test
        @DisplayName("@BeanParam missing @PathParam for placeholder → ERROR")
        void beanParam_missingPathParam_error() {
            var result = ProcessorTestHarness.run(
                    new AlignmentProbe(),
                    SourceFiles.inline("dev.vertique.test.EmptyBean", """
                            package dev.vertique.test;
                            public class EmptyBean {}
                            """),
                    SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.BeanParam;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/res")
                            public class Res {
                                @GET @Path("/{id}")
                                public String get(@BeanParam EmptyBean bean) { return ""; }
                            }
                            """));
            result.assertFailed();
            result.assertErrorMessage("'{id}'");
        }
    }

    // --- @RequestParams composite ---

    @Nested
    @DisplayName("@RequestParams type contributes @PathParam names")
    class RequestParamsComposite {

        @Test
        @DisplayName("@RequestParams record with @PathParam → aligned")
        void requestParams_record_aligned() {
            var result = ProcessorTestHarness.run(
                    new AlignmentProbe(),
                    SourceFiles.inline("dev.vertique.test.IdParams", """
                            package dev.vertique.test;
                            import jakarta.ws.rs.PathParam;
                            import dev.vertique.rest.core.request.RequestParams;

                            @RequestParams
                            public record IdParams(@PathParam("id") String id) {}
                            """),
                    SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/res")
                            public class Res {
                                @GET @Path("/{id}")
                                public String get(IdParams params) { return ""; }
                            }
                            """));
            result.assertSuccess();
            assertEquals(0, errorCount(result));
        }
    }

    // --- Record component @PathParam ---

    @Nested
    @DisplayName("record-component @PathParam discovered via JaxRsBeanScanner")
    class RecordComponentPathParam {

        @Test
        @DisplayName("record with @PathParam on accessor → discoverable via @BeanParam")
        void record_pathParamAccessor_discovered() {
            var result = ProcessorTestHarness.run(
                    new AlignmentProbe(),
                    SourceFiles.inline("dev.vertique.test.IdRecord", """
                            package dev.vertique.test;
                            import jakarta.ws.rs.PathParam;
                            public record IdRecord(@PathParam("id") String id) {}
                            """),
                    SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.BeanParam;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/res")
                            public class Res {
                                @GET @Path("/{id}")
                                public String get(@BeanParam IdRecord rec) { return ""; }
                            }
                            """));
            result.assertSuccess();
            assertEquals(0, errorCount(result));
        }
    }

    // --- Happy path ---

    @Test
    @DisplayName("aligned placeholder and @PathParam → no error")
    void aligned_noError() {
        var result = ProcessorTestHarness.run(new AlignmentProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                        package dev.vertique.test;

                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.PathParam;

                        @Path("/res")
                        public class Res {
                            @GET @Path("/{id}")
                            public String get(@PathParam("id") String id) { return ""; }
                        }
                        """));
        result.assertSuccess();
        assertEquals(0, errorCount(result));
    }

    @Test
    @DisplayName("@PathParam(\"\") on direct method param errors symmetrically with bean-scan branch")
    void blankPathParamOnDirectParam_errors() {
        // Round-5 parity fix: the bean-scan branch (JaxRsBeanScanner) preserves blank @PathParam
        // values, so the direct-param branch must do the same — both runtime ParameterExtractor
        // and rest-client ClientInterfaceScanner preserve the literal value. Without the symmetry
        // a developer hitting @PathParam("") on a direct param gets no signal until runtime, while
        // the equivalent @BeanParam form would already be flagged at compile time.
        var result = ProcessorTestHarness.run(new AlignmentProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                        package dev.vertique.test;

                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.PathParam;

                        @Path("/res")
                        public class Res {
                            @GET @Path("/{id}")
                            public String get(@PathParam("") String id) { return ""; }
                        }
                        """));
        result.assertFailed();
        // Two complementary errors: placeholder {id} has no matching @PathParam("id"),
        // and @PathParam("") has no matching placeholder.
        assertEquals(2, errorCount(result), "Expected 2 alignment errors for blank @PathParam");
    }

    // --- Helper ---

    private int errorCount(ProcessorTestHarness.Result result) {
        return (int) result.compilation().diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .count();
    }

    // --- Probe processor ---

    /**
     * Probe processor that runs {@link PathParamAlignmentValidator} against every
     * {@code @Path}-annotated class and all its methods.
     */
    static final class AlignmentProbe extends AbstractProcessor {

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
            PathParamAlignmentValidator validator = new PathParamAlignmentValidator(ctx);

            for (Element e : env.getElementsAnnotatedWith(
                    processingEnv.getElementUtils().getTypeElement("jakarta.ws.rs.Path"))) {
                if (!(e instanceof TypeElement resource)) continue;
                if (resource.getKind() == ElementKind.INTERFACE) continue;

                for (Element enclosed : resource.getEnclosedElements()) {
                    if (enclosed.getKind() != ElementKind.METHOD) continue;
                    validator.validate(resource, (ExecutableElement) enclosed);
                }
            }
            return false;
        }
    }
}
