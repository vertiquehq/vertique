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
 * Tests for {@link BodyFormValidator}.
 *
 * <p>Verifies that parameter classification mirrors the runtime {@code ResourceScanner.resolveParams}
 * ordered dispatch, and that the body/form exclusivity rules match {@code RouteValidator.validateMethodParams}.
 * Error messages include the declaring class name derived from {@code method.getEnclosingElement()},
 * matching runtime {@code RouteValidator} wording for inherited resource methods.
 */
class BodyFormValidatorTest {

    // --- Single body parameter (happy path) ---

    @Test
    @DisplayName("single body parameter → no error")
    void singleBody_noError() {
        var result = ProcessorTestHarness.run(new BodyFormProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                        package dev.vertique.test;

                        import jakarta.ws.rs.POST;
                        import jakarta.ws.rs.Path;

                        @Path("/res")
                        public class Res {
                            @POST public void create(String body) {}
                        }
                        """));
        result.assertSuccess();
        assertEquals(0, errorCount(result));
    }

    // --- Multiple body parameters ---

    @Nested
    @DisplayName("multiple body parameters → ERROR")
    class MultipleBodies {

        @Test
        @DisplayName("two body parameters → ERROR")
        void twoBodies_error() {
            var result =
                    ProcessorTestHarness.run(new BodyFormProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;

                            @Path("/res")
                            public class Res {
                                @POST public void create(String a, String b) {}
                            }
                            """));
            result.assertFailed();
            result.assertErrorMessage("has 2 body parameters; at most one is allowed");
        }
    }

    // --- @FormParam + body mix ---

    @Nested
    @DisplayName("@FormParam / file upload mixed with body → ERROR")
    class FormAndBodyMix {

        @Test
        @DisplayName("@FormParam + body parameter → ERROR")
        void formAndBody_error() {
            var result =
                    ProcessorTestHarness.run(new BodyFormProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.FormParam;
                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;

                            @Path("/res")
                            public class Res {
                                @POST public void create(@FormParam("name") String name, String body) {}
                            }
                            """));
            result.assertFailed();
            result.assertErrorMessage("mixes @FormParam/file upload parameters with a body parameter");
        }

        @Test
        @DisplayName("List<FileUpload> + body parameter → ERROR")
        void fileUploadAndBody_error() {
            var result =
                    ProcessorTestHarness.run(new BodyFormProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import io.vertx.ext.web.FileUpload;
                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;
                            import java.util.List;

                            @Path("/res")
                            public class Res {
                                @POST public void upload(List<FileUpload> files, String body) {}
                            }
                            """));
            result.assertFailed();
            result.assertErrorMessage("mixes @FormParam/file upload parameters with a body parameter");
        }

        @Test
        @DisplayName("List<EntityPart> + body parameter → ERROR")
        void entityPartAndBody_error() {
            var result =
                    ProcessorTestHarness.run(new BodyFormProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.core.EntityPart;
                            import java.util.List;

                            @Path("/res")
                            public class Res {
                                @POST public void upload(List<EntityPart> parts, String body) {}
                            }
                            """));
            result.assertFailed();
            result.assertErrorMessage("mixes @FormParam/file upload parameters with a body parameter");
        }

        @Test
        @DisplayName("only @FormParam parameters → no error")
        void onlyFormParams_noError() {
            var result =
                    ProcessorTestHarness.run(new BodyFormProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.FormParam;
                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;

                            @Path("/res")
                            public class Res {
                                @POST public void create(@FormParam("name") String name,
                                                         @FormParam("email") String email) {}
                            }
                            """));
            result.assertSuccess();
            assertEquals(0, errorCount(result));
        }
    }

    // --- Context types do NOT count as body ---

    @Nested
    @DisplayName("context/path params + body → no error")
    class ContextPlusBody {

        @Test
        @DisplayName("RoutingContext + @PathParam + body → no error")
        void routingContextPathParamBody_noError() {
            var result =
                    ProcessorTestHarness.run(new BodyFormProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import io.vertx.ext.web.RoutingContext;
                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/res")
                            public class Res {
                                @POST @Path("/{id}")
                                public void create(RoutingContext ctx,
                                                   @PathParam("id") String id,
                                                   String body) {}
                            }
                            """));
            result.assertSuccess();
            assertEquals(0, errorCount(result));
        }

        @Test
        @DisplayName("jakarta.ws.rs.core.SecurityContext does NOT count as body")
        void jaxrsSecurityContext_notBody() {
            var result =
                    ProcessorTestHarness.run(new BodyFormProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.core.SecurityContext;

                            @Path("/res")
                            public class Res {
                                @POST public void create(SecurityContext sec, String body) {}
                            }
                            """));
            result.assertSuccess();
            assertEquals(0, errorCount(result));
        }

        @Test
        @DisplayName("framework dev.vertique.security.SecurityContext does NOT count as body (Fix A)")
        void frameworkSecurityContext_notBody() {
            // Fix A: SECURITY_CONTEXT_FQN was wrong (pointed to dev.vertique.rest.core.security),
            // so the isAssignable check resolved null and the framework SecurityContext was
            // misclassified as BODY. This test verifies the correct FQN resolves to a non-null
            // mirror and the parameter is correctly classified as SECURITY_CONTEXT.
            var result =
                    ProcessorTestHarness.run(new BodyFormProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import dev.vertique.security.SecurityContext;
                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;

                            @Path("/res")
                            public class Res {
                                @POST public void create(SecurityContext sec, String body) {}
                            }
                            """));
            result.assertSuccess();
            assertEquals(0, errorCount(result));
        }
    }

    // --- Context subtype test ---

    @Nested
    @DisplayName("context subtypes do NOT count as body — Fix 2 (isAssignable)")
    class ContextSubtypes {

        @Test
        @DisplayName("subtype of RoutingContext + body parameter → no error (subtype is a context)")
        void routingContextSubtype_notBody() {
            // Compile a stub that extends RoutingContext (via interface trick: use a concrete
            // RoutingContext implementation stub in the same compilation unit). Since we can't
            // extend the actual io.vertx.ext.web.RoutingContext interface directly in source text
            // without a complete implementation, we use io.vertx.ext.web.impl.RoutingContextImpl
            // which is not available. Instead, compile a class that extends RoutingContext
            // using an abstract override, then verify no false-positive body error is reported.
            //
            // Strategy: compile two source files together — a stub MyRoutingContext that implements
            // io.vertx.ext.web.RoutingContext (which is an interface), and a resource that uses it.
            var result = ProcessorTestHarness.run(
                    new BodyFormProbe(),
                    SourceFiles.inline("dev.vertique.test.MyRoutingContext", """
                            package dev.vertique.test;

                            import io.vertx.ext.web.RoutingContext;

                            /** Stub subtype of RoutingContext for testing isAssignable classification. */
                            public abstract class MyRoutingContext implements RoutingContext {}
                            """),
                    SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;

                            @Path("/res")
                            public class Res {
                                @POST public void create(MyRoutingContext ctx, String body) {}
                            }
                            """));
            result.assertSuccess();
            assertEquals(0, errorCount(result));
        }
    }

    // --- Diagnostic class name parity (Fix D) ---

    @Nested
    @DisplayName("diagnostic class name uses declaring class, not leaf @Path class (Fix D)")
    class DiagnosticClassNameParity {

        @Test
        @DisplayName("method declared on BaseResource, routed via ChildResource → error message says BaseResource")
        void inheritedMethod_errorMessageUsesDeclaringClass() {
            // Fix D: before this fix, validate(resource, method) used resource.getSimpleName()
            // (the leaf @Path class) for the diagnostic. For an inherited method, the runtime
            // RouteValidator uses method.getDeclaringClass().getSimpleName() — the declaring class.
            // The test verifies that the error message includes the declaring class name.
            var result = ProcessorTestHarness.run(
                    new BodyFormProbe(),
                    SourceFiles.inline("dev.vertique.test.BaseResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.POST;

                            public class BaseResource {
                                @POST public void create(String a, String b) {}
                            }
                            """),
                    SourceFiles.inline("dev.vertique.test.ChildResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.Path;

                            @Path("/child")
                            public class ChildResource extends BaseResource {}
                            """));
            result.assertFailed();
            // Error must mention BaseResource (declaring class), not ChildResource (leaf)
            result.assertErrorMessage("BaseResource");
        }
    }

    // --- Helper ---

    private int errorCount(ProcessorTestHarness.Result result) {
        return (int) result.compilation().diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .filter(d -> {
                    String msg = d.getMessage(null);
                    return msg != null && (msg.contains("body parameters") || msg.contains("file upload parameters"));
                })
                .count();
    }

    // --- Probe processor ---

    /**
     * Probe processor that runs {@link BodyFormValidator} against every {@code @Path}-annotated
     * class and its methods. Uses {@link BodyFormValidator#validate(ExecutableElement)} — the
     * validator derives the declaring class name from {@code method.getEnclosingElement()},
     * matching runtime {@code RouteValidator} wording for inherited resource methods.
     *
     * <p>The probe walks the superclass chain (like {@link
     * dev.vertique.codegen.jaxrs.processor.JaxRsResourceProcessor}) so that inherited methods
     * are also validated.
     */
    static final class BodyFormProbe extends AbstractProcessor {

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
            BodyFormValidator validator = new BodyFormValidator(ctx);

            for (Element e : env.getElementsAnnotatedWith(
                    processingEnv.getElementUtils().getTypeElement("jakarta.ws.rs.Path"))) {
                if (!(e instanceof TypeElement resource)) continue;
                if (resource.getKind() == ElementKind.INTERFACE) continue;

                // Walk superclass chain to collect methods (mirrors JaxRsResourceProcessor)
                TypeElement current = resource;
                while (current != null
                        && !"java.lang.Object".equals(current.getQualifiedName().toString())) {
                    for (Element enclosed : current.getEnclosedElements()) {
                        if (enclosed.getKind() != ElementKind.METHOD) continue;
                        validator.validate((ExecutableElement) enclosed);
                    }
                    var superMirror = current.getSuperclass();
                    if (superMirror == null) break;
                    var superElement = processingEnv.getTypeUtils().asElement(superMirror);
                    if (!(superElement instanceof TypeElement te)) break;
                    current = te;
                }
            }
            return false;
        }
    }
}
