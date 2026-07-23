// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JaxRsCandidateScanner} using in-process APT compilation.
 *
 * <p>Each test compiles a source fixture through a probe processor that calls
 * {@link JaxRsCandidateScanner#scan} and {@link JaxRsCandidateScanner#filterDiCandidates},
 * then emits the simple names of the discovered types as {@code NOTE} diagnostics. Tests inspect
 * those diagnostics to assert which types were included or excluded.
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li>Concrete class with a direct {@code @Path} — found in semantic set.</li>
 *   <li>Concrete class implementing a {@code @Path} interface — found in semantic set.</li>
 *   <li>Abstract class with {@code @Path} — excluded from semantic set.</li>
 *   <li>Interface with {@code @Path} — excluded from semantic set.</li>
 *   <li>Concrete class with direct {@code @Path} but no {@code @Inject} constructor — in semantic
 *       set but NOT in DI set.</li>
 *   <li>{@code @NoAutoWire}-annotated concrete class with {@code @Path} + {@code @Inject} — in
 *       semantic set but NOT in DI set.</li>
 * </ul>
 */
class JaxRsCandidateScannerTest {

    // --- Semantic set tests ---

    @Nested
    @DisplayName("semantic candidates ��� direct @Path concrete class")
    class DirectPathConcrete {

        @Test
        @DisplayName("concrete class with @Path — included in semantic set")
        void directPath_includedInSemanticSet() {
            var result = ProcessorTestHarness.run(
                    new ScannerProbe(), SourceFiles.inline("dev.vertique.test.UserResource", """
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
            assertTrue(
                    semanticNames(result).contains("UserResource"),
                    "UserResource with @Path should be in the semantic set");
        }

        @Test
        @DisplayName("concrete class implementing @Path interface — included in semantic set")
        void interfaceBacked_includedInSemanticSet() {
            var result = ProcessorTestHarness.run(
                    new ScannerProbe(),
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
            assertTrue(
                    semanticNames(result).contains("ItemsResource"),
                    "ItemsResource (no direct @Path, implements @Path interface) should be in the semantic set");
        }
    }

    @Nested
    @DisplayName("semantic candidates — excluded types")
    class ExcludedTypes {

        @Test
        @DisplayName("abstract class with @Path — excluded from semantic set")
        void abstractClass_excludedFromSemanticSet() {
            var result = ProcessorTestHarness.run(
                    new ScannerProbe(), SourceFiles.inline("dev.vertique.test.AbstractResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/abstract")
                            public abstract class AbstractResource {
                                @GET
                                public abstract String get();
                            }
                            """));

            result.assertSuccess();
            assertFalse(
                    semanticNames(result).contains("AbstractResource"),
                    "Abstract @Path class must be excluded from the semantic set");
        }

        @Test
        @DisplayName("interface with @Path — excluded from semantic set")
        void interface_excludedFromSemanticSet() {
            var result = ProcessorTestHarness.run(
                    new ScannerProbe(), SourceFiles.inline("dev.vertique.test.OrdersApi", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/orders")
                            public interface OrdersApi {
                                @GET
                                String list();
                            }
                            """));

            result.assertSuccess();
            assertFalse(
                    semanticNames(result).contains("OrdersApi"),
                    "@Path interface must be excluded from the semantic set");
        }
    }

    // --- DI-set filter tests ---

    @Nested
    @DisplayName("DI candidates — @Inject and @NoAutoWire filtering")
    class DiCandidateFiltering {

        @Test
        @DisplayName("concrete @Path class without @Inject constructor — semantic but NOT DI")
        void noInjectConstructor_semanticButNotDi() {
            var result = ProcessorTestHarness.run(
                    new ScannerProbe(), SourceFiles.inline("dev.vertique.test.NoInjectResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/no-inject")
                            public class NoInjectResource {
                                // No @Inject constructor — manually bound

                                @GET
                                public String get() { return ""; }
                            }
                            """));

            result.assertSuccess();
            assertTrue(
                    semanticNames(result).contains("NoInjectResource"),
                    "NoInjectResource should be in the semantic set (has @Path)");
            assertFalse(
                    diNames(result).contains("NoInjectResource"),
                    "NoInjectResource should NOT be in the DI set (no @Inject constructor)");
        }

        @Test
        @DisplayName("@NoAutoWire-annotated concrete @Path+@Inject class — semantic but NOT DI")
        void noAutoWire_semanticButNotDi() {
            var result = ProcessorTestHarness.run(
                    new ScannerProbe(), SourceFiles.inline("dev.vertique.test.ManuallyBoundResource", """
                            package dev.vertique.test;

                            import dev.vertique.codegen.NoAutoWire;
                            import jakarta.inject.Inject;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @NoAutoWire
                            @Path("/manual")
                            public class ManuallyBoundResource {
                                @Inject
                                public ManuallyBoundResource() {}

                                @GET
                                public String get() { return ""; }
                            }
                            """));

            result.assertSuccess();
            assertTrue(
                    semanticNames(result).contains("ManuallyBoundResource"),
                    "ManuallyBoundResource should be in the semantic set (has @Path)");
            assertFalse(
                    diNames(result).contains("ManuallyBoundResource"),
                    "ManuallyBoundResource should NOT be in the DI set (@NoAutoWire prevents auto-wiring)");
        }

        @Test
        @DisplayName("concrete @Path+@Inject class with @NoAutoWire-less — both semantic AND DI")
        void injectAndNoNoAutoWire_inBothSets() {
            var result = ProcessorTestHarness.run(
                    new ScannerProbe(), SourceFiles.inline("dev.vertique.test.AutoResource", """
                            package dev.vertique.test;

                            import jakarta.inject.Inject;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/auto")
                            public class AutoResource {
                                @Inject
                                public AutoResource() {}

                                @GET
                                public String get() { return ""; }
                            }
                            """));

            result.assertSuccess();
            assertTrue(semanticNames(result).contains("AutoResource"), "AutoResource should be in the semantic set");
            assertTrue(
                    diNames(result).contains("AutoResource"),
                    "AutoResource should be in the DI set (@Inject present, no @NoAutoWire)");
        }

        @Test
        @DisplayName("@Path interface only — no concrete impl — neither semantic nor DI set populated")
        void interfaceOnly_noConcrete_setsEmpty() {
            var result =
                    ProcessorTestHarness.run(new ScannerProbe(), SourceFiles.inline("dev.vertique.test.ApiOnly", """
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
            assertFalse(semanticNames(result).contains("ApiOnly"), "@Path interface must not appear in semantic set");
            assertFalse(diNames(result).contains("ApiOnly"), "@Path interface must not appear in DI set");
        }
    }

    // --- Probe processor ---

    /**
     * Probe annotation processor that calls {@link JaxRsCandidateScanner#scan} and
     * {@link JaxRsCandidateScanner#filterDiCandidates} and emits the simple names of found
     * type elements as {@code NOTE} diagnostics.
     *
     * <p>Semantic candidates are prefixed {@code "SEMANTIC:"} and DI candidates are prefixed
     * {@code "DI:"} so the test can distinguish them from the same diagnostic list.
     */
    private static final class ScannerProbe extends AbstractProcessor {

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.RELEASE_21;
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (roundEnv.processingOver()) {
                return false;
            }
            CodegenContext ctx = new CodegenContext(processingEnv);
            EffectiveJaxRsContractResolver resolver = new EffectiveJaxRsContractResolver(ctx);

            Set<TypeElement> semantic = JaxRsCandidateScanner.scan(roundEnv, resolver);
            Set<TypeElement> di = JaxRsCandidateScanner.filterDiCandidates(semantic, processingEnv.getElementUtils());

            for (TypeElement t : semantic) {
                processingEnv
                        .getMessager()
                        .printNote("SEMANTIC:" + t.getSimpleName().toString());
            }
            for (TypeElement t : di) {
                processingEnv.getMessager().printNote("DI:" + t.getSimpleName().toString());
            }
            return false;
        }
    }

    // --- Helpers ---

    /**
     * Extracts simple type names from {@code NOTE} diagnostics prefixed with {@code "SEMANTIC:"}.
     *
     * @param result the compilation result
     * @return list of simple names in the semantic set
     */
    private List<String> semanticNames(ProcessorTestHarness.Result result) {
        return extractPrefixed(result, "SEMANTIC:");
    }

    /**
     * Extracts simple type names from {@code NOTE} diagnostics prefixed with {@code "DI:"}.
     *
     * @param result the compilation result
     * @return list of simple names in the DI set
     */
    private List<String> diNames(ProcessorTestHarness.Result result) {
        return extractPrefixed(result, "DI:");
    }

    /**
     * Returns the messages of all {@code NOTE} diagnostics that start with the given prefix,
     * with the prefix stripped.
     *
     * @param result the compilation result
     * @param prefix the prefix to filter and strip
     * @return list of stripped message values
     */
    private List<String> extractPrefixed(ProcessorTestHarness.Result result, String prefix) {
        List<String> names = new ArrayList<>();
        for (var diag : result.compilation().diagnostics()) {
            if (diag.getKind() != Diagnostic.Kind.NOTE) {
                continue;
            }
            String msg = diag.getMessage(null);
            if (msg != null && msg.startsWith(prefix)) {
                names.add(msg.substring(prefix.length()));
            }
        }
        return names;
    }
}
