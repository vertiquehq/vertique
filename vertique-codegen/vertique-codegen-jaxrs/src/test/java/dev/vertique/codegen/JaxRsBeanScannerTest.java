// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link JaxRsBeanScanner} using a real APT compilation round.
 *
 * <p>Each test runs a probe processor that calls {@code JaxRsBeanScanner.pathParamNames()} against
 * a source fixture and emits the resulting names as {@code NOTE} diagnostics. The test then
 * inspects the compilation diagnostics to verify expected names.
 *
 * <p>Fixture types are annotated with {@code @Deprecated} so the probe processor can locate them
 * via {@code RoundEnvironment.getElementsAnnotatedWith(Deprecated.class)}.
 */
class JaxRsBeanScannerTest {

    // --- Record tests ---

    @Test
    @DisplayName("record with @PathParam on accessor — name is extracted")
    void record_pathParamOnAccessor_extracted() {
        var result =
                ProcessorTestHarness.run(new ScannerProbe(), SourceFiles.inline("dev.vertique.test.IdRecord", """
                                package dev.vertique.test;

                                import jakarta.ws.rs.PathParam;

                                @Deprecated
                                public record IdRecord(@PathParam("id") String id) {}
                                """));

        result.assertSuccess();
        List<String> names = extractNotes(result);
        assertEquals(List.of("id"), names);
    }

    // --- Class tests ---

    @Test
    @DisplayName("class with @PathParam on field — name is extracted")
    void class_pathParamOnField_extracted() {
        var result = ProcessorTestHarness.run(new ScannerProbe(), SourceFiles.inline("dev.vertique.test.IdBean", """
                                package dev.vertique.test;

                                import jakarta.ws.rs.PathParam;

                                @Deprecated
                                public class IdBean {
                                    @PathParam("id") public String id;
                                }
                                """));

        result.assertSuccess();
        List<String> names = extractNotes(result);
        assertEquals(List.of("id"), names);
    }

    @Test
    @DisplayName("class with @QueryParam only — returns empty set")
    void class_queryParamOnly_returnsEmpty() {
        var result =
                ProcessorTestHarness.run(new ScannerProbe(), SourceFiles.inline("dev.vertique.test.PageBean", """
                                package dev.vertique.test;

                                import jakarta.ws.rs.QueryParam;

                                @Deprecated
                                public class PageBean {
                                    @QueryParam("page") public int page;
                                }
                                """));

        result.assertSuccess();
        List<String> names = extractNotes(result);
        assertTrue(names.isEmpty(), "Expected empty but got: " + names);
    }

    @Test
    @DisplayName("empty type — returns empty set")
    void emptyType_returnsEmpty() {
        var result =
                ProcessorTestHarness.run(new ScannerProbe(), SourceFiles.inline("dev.vertique.test.EmptyBean", """
                                package dev.vertique.test;

                                @Deprecated
                                public class EmptyBean {}
                                """));

        result.assertSuccess();
        List<String> names = extractNotes(result);
        assertTrue(names.isEmpty(), "Expected empty but got: " + names);
    }

    @Test
    @DisplayName("@PathParam field inherited from superclass — name is extracted")
    void class_inheritedPathParam_extracted() {
        var result = ProcessorTestHarness.run(
                new ScannerProbe(),
                SourceFiles.inline("dev.vertique.test.BaseBean", """
                                package dev.vertique.test;

                                import jakarta.ws.rs.PathParam;

                                public class BaseBean {
                                    @PathParam("tenantId") public String tenantId;
                                }
                                """),
                SourceFiles.inline("dev.vertique.test.DerivedBean", """
                                package dev.vertique.test;

                                import jakarta.ws.rs.PathParam;

                                @Deprecated
                                public class DerivedBean extends BaseBean {
                                    @PathParam("resourceId") public String resourceId;
                                }
                                """));

        result.assertSuccess();
        List<String> names = extractNotes(result);
        // derived field appears first (most-derived wins), then inherited
        assertTrue(names.contains("resourceId"), "Expected resourceId in " + names);
        assertTrue(names.contains("tenantId"), "Expected tenantId in " + names);
        assertEquals(2, names.size());
    }

    @Test
    @DisplayName("record with @PathParam(\"\") on component accessor — literal blank returned, not field name")
    void record_blankPathParam_returnsBlank() {
        // Parity fix: resolvePathParamName no longer falls back to the Java identifier when
        // @PathParam("") has a blank value. Mirrors runtime ParameterExtractor (composite paths)
        // and PathParamAlignmentValidator (direct-param branch) which both preserve the literal
        // value. The "" entry surfaces as a "missing placeholder" diagnostic in the alignment
        // check — the correct signal for a malformed annotation.
        var result =
                ProcessorTestHarness.run(new ScannerProbe(), SourceFiles.inline("dev.vertique.test.BlankRecord", """
                                package dev.vertique.test;

                                import jakarta.ws.rs.PathParam;

                                @Deprecated
                                public record BlankRecord(@PathParam("") String id) {}
                                """));

        result.assertSuccess();
        List<String> names = extractNotes(result);
        // The literal "" is returned; the Java identifier "id" must NOT appear.
        assertEquals(List.of(""), names, "Expected literal blank string, not the field name 'id'");
    }

    @Test
    @DisplayName("class with @PathParam(\"\") on field — literal blank returned, not field name")
    void class_blankPathParam_returnsBlank() {
        // Same parity fix as record_blankPathParam_returnsBlank, but for class fields.
        var result =
                ProcessorTestHarness.run(new ScannerProbe(), SourceFiles.inline("dev.vertique.test.BlankBean", """
                                package dev.vertique.test;

                                import jakarta.ws.rs.PathParam;

                                @Deprecated
                                public class BlankBean {
                                    @PathParam("") public String id;
                                }
                                """));

        result.assertSuccess();
        List<String> names = extractNotes(result);
        // The literal "" is returned; the Java identifier "id" must NOT appear.
        assertEquals(List.of(""), names, "Expected literal blank string, not the field name 'id'");
    }

    @Test
    @DisplayName("multiple @PathParam fields preserve insertion order")
    void class_multiplePathParams_preserveOrder() {
        var result =
                ProcessorTestHarness.run(new ScannerProbe(), SourceFiles.inline("dev.vertique.test.OrderedBean", """
                                package dev.vertique.test;

                                import jakarta.ws.rs.PathParam;

                                @Deprecated
                                public class OrderedBean {
                                    @PathParam("alpha") public String alpha;
                                    @PathParam("beta") public String beta;
                                    @PathParam("gamma") public String gamma;
                                }
                                """));

        result.assertSuccess();
        List<String> names = extractNotes(result);
        assertEquals(List.of("alpha", "beta", "gamma"), names);
    }

    // --- Helper ---

    /**
     * Extracts the {@code @PathParam} names emitted by the scanner probe as NOTE diagnostics.
     *
     * <p>Notes are emitted as {@code SCAN:name} to distinguish them from any other notes.
     *
     * @param result the compilation result to inspect
     * @return the ordered list of names found in SCAN notes
     */
    private List<String> extractNotes(ProcessorTestHarness.Result result) {
        List<String> names = new ArrayList<>();
        result.compilation().diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.NOTE)
                .map(d -> d.getMessage(null))
                .filter(msg -> msg != null && msg.startsWith("SCAN:"))
                .map(msg -> msg.substring("SCAN:".length()))
                .forEach(names::add);
        return names;
    }

    // --- Probe processor ---

    /**
     * Probe processor that runs {@link JaxRsBeanScanner} against every {@code @Deprecated}-annotated
     * type and emits each discovered {@code @PathParam} name as a {@code NOTE} diagnostic in the
     * form {@code SCAN:<name>}.
     *
     * <p>Using {@code @Deprecated} as the trigger avoids any JAX-RS-specific triggering and
     * keeps the probe independent of the annotation under test.
     */
    static final class ScannerProbe extends AbstractProcessor {

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of(Deprecated.class.getName());
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
            CodegenContext ctx = new CodegenContext(processingEnv);
            JaxRsBeanScanner scanner = new JaxRsBeanScanner(ctx);

            for (Element e : env.getElementsAnnotatedWith(Deprecated.class)) {
                if (!(e instanceof TypeElement type)) {
                    continue;
                }
                Set<String> names = scanner.pathParamNames(type);
                for (String name : names) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "SCAN:" + name);
                }
            }
            return false;
        }
    }
}
