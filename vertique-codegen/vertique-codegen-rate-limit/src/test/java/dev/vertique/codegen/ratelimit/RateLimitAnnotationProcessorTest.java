// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.processing.Completion;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * TP-001/TP-002 — the frozen T008 contract matrix for {@link RateLimitAnnotationProcessor}
 * ({@code docs/specs/ratelimit-001-runtime-and-rest-adapter/tasks/T008-compile-time-annotation-validation.md}).
 *
 * <p>Every row compiles one inline fixture once with the real processor. The valid declaration must
 * compile and generate zero sources; every declared invalid declaration must fail with a diagnostic
 * naming the specific violated rule, attributed to the annotation site.
 */
class RateLimitAnnotationProcessorTest {

    private static final String PACKAGE = "com.example";

    // --- TP-001: policy/cost/selector/proxyability validation matrix ---

    /**
     * The five named rows of the T008 contract matrix.
     *
     * @return one row per boundary, named exactly as the proof contract lists it
     */
    static Stream<MatrixRow> t008ContractMatrixRows() {
        return Stream.of(
                new MatrixRow(
                        "shouldCompileAValidRateLimitedDeclaration",
                        RateLimitAnnotationProcessorTest::shouldCompileAValidRateLimitedDeclaration),
                new MatrixRow(
                        "shouldRejectAnInvalidPolicyNameSyntax",
                        RateLimitAnnotationProcessorTest::shouldRejectAnInvalidPolicyNameSyntax),
                new MatrixRow("shouldRejectCostBelowOne", RateLimitAnnotationProcessorTest::shouldRejectCostBelowOne),
                new MatrixRow(
                        "shouldRejectAnUnresolvableSelectorRoot",
                        RateLimitAnnotationProcessorTest::shouldRejectAnUnresolvableSelectorRoot),
                new MatrixRow(
                        "shouldRejectANonProxyableMethodShape",
                        RateLimitAnnotationProcessorTest::shouldRejectANonProxyableMethodShape));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("t008ContractMatrixRows")
    @DisplayName("enforces the T008 contract matrix")
    void shouldEnforceT008ContractMatrix(MatrixRow row) throws Throwable {
        row.proof().execute();
    }

    /** Policy {@code "search-quota"}, positional selector, default cost — the valid baseline. */
    private static void shouldCompileAValidRateLimitedDeclaration() {
        JavaFileObject source = SourceFiles.inline(PACKAGE + ".SearchQuotaBean", """
                package com.example;

                import dev.vertique.ratelimit.aop.RateLimited;
                import jakarta.inject.Inject;

                public class SearchQuotaBean {

                    @Inject
                    public SearchQuotaBean() {}

                    @RateLimited(policy = "search-quota", key = {"0"})
                    public String search(String query) {
                        return query;
                    }
                }
                """);
        ProcessorTestHarness.Result result = ProcessorTestHarness.run(new RateLimitAnnotationProcessor(), source);
        result.assertSuccess();
        assertEquals(
                0,
                result.compilation().generatedSourceFiles().size(),
                "a valid @RateLimited declaration must generate zero sources");
    }

    /** {@code policy = "Bad Name!"} violates {@code [A-Za-z0-9._~-]{1,128}}. */
    private static void shouldRejectAnInvalidPolicyNameSyntax() {
        JavaFileObject source = SourceFiles.inline(PACKAGE + ".InvalidPolicyNameBean", """
                package com.example;

                import dev.vertique.ratelimit.aop.RateLimited;
                import jakarta.inject.Inject;

                public class InvalidPolicyNameBean {

                    @Inject
                    public InvalidPolicyNameBean() {}

                    @RateLimited(policy = "Bad Name!", key = {"0"})
                    public String search(String query) {
                        return query;
                    }
                }
                """);
        ProcessorTestHarness.run(new RateLimitAnnotationProcessor(), source)
                .assertFailed()
                .assertErrorMessage("rate-limit policy name must match");
    }

    /** {@code cost = 0} violates {@code cost() >= 1}. */
    private static void shouldRejectCostBelowOne() {
        JavaFileObject source = SourceFiles.inline(PACKAGE + ".ZeroCostBean", """
                package com.example;

                import dev.vertique.ratelimit.aop.RateLimited;
                import jakarta.inject.Inject;

                public class ZeroCostBean {

                    @Inject
                    public ZeroCostBean() {}

                    @RateLimited(policy = "search-quota", cost = 0)
                    public String search(String query) {
                        return query;
                    }
                }
                """);
        ProcessorTestHarness.run(new RateLimitAnnotationProcessor(), source)
                .assertFailed()
                .assertErrorMessage("rate-limit cost must be at least 1");
    }

    /** {@code key = {"missingParameter"}} does not name a declared parameter of a single-arg method. */
    private static void shouldRejectAnUnresolvableSelectorRoot() {
        JavaFileObject source = SourceFiles.inline(PACKAGE + ".UnresolvableSelectorBean", """
                package com.example;

                import dev.vertique.ratelimit.aop.RateLimited;
                import jakarta.inject.Inject;

                public class UnresolvableSelectorBean {

                    @Inject
                    public UnresolvableSelectorBean() {}

                    @RateLimited(policy = "search-quota", key = {"missingParameter"})
                    public String search(String query) {
                        return query;
                    }
                }
                """);
        ProcessorTestHarness.run(new RateLimitAnnotationProcessor(), source)
                .assertFailed()
                .assertErrorMessage("does not resolve to a method parameter");
    }

    /** The annotated method is declared {@code private}, so it cannot be proxied. */
    private static void shouldRejectANonProxyableMethodShape() {
        JavaFileObject source = SourceFiles.inline(PACKAGE + ".NonProxyableBean", """
                package com.example;

                import dev.vertique.ratelimit.aop.RateLimited;
                import jakarta.inject.Inject;

                public class NonProxyableBean {

                    @Inject
                    public NonProxyableBean() {}

                    @RateLimited(policy = "search-quota", key = {"0"})
                    private String search(String query) {
                        return query;
                    }
                }
                """);
        ProcessorTestHarness.run(new RateLimitAnnotationProcessor(), source)
                .assertFailed()
                .assertErrorMessage("instance methods that can be overridden");
    }

    // --- Supplementary: root-text-equal non-root segment (not part of the frozen TP-001 matrix) ---

    /**
     * {@code key = {"0.0"}}: the root segment {@code "0"} resolves to parameter 0, and the second
     * segment is textually {@code "0"} too but is not a valid identifier for that position — it must
     * still be validated as a property-path segment rather than being waved through because it
     * happens to equal the root's text.
     */
    @Test
    @DisplayName(
            "rejects a non-root selector segment that is textually equal to the root but is not a valid identifier")
    void shouldRejectANonRootSegmentEqualToTheRootTextButNotAnIdentifier() {
        JavaFileObject source = SourceFiles.inline(PACKAGE + ".RootEchoSegmentBean", """
                package com.example;

                import dev.vertique.ratelimit.aop.RateLimited;
                import jakarta.inject.Inject;

                public class RootEchoSegmentBean {

                    @Inject
                    public RootEchoSegmentBean() {}

                    @RateLimited(policy = "search-quota", key = {"0.0"})
                    public String search(String query) {
                        return query;
                    }
                }
                """);
        ProcessorTestHarness.run(new RateLimitAnnotationProcessor(), source)
                .assertFailed()
                .assertErrorMessage("rate-limit key property path contains an invalid identifier");
    }

    // --- TP-002: process() contract ---

    @Test
    @DisplayName("process() returns false and generates zero sources")
    void shouldGenerateZeroSourcesAndReturnFalseFromProcess() {
        JavaFileObject source = SourceFiles.inline(PACKAGE + ".ZeroSourcesBean", """
                package com.example;

                import dev.vertique.ratelimit.aop.RateLimited;
                import jakarta.inject.Inject;

                public class ZeroSourcesBean {

                    @Inject
                    public ZeroSourcesBean() {}

                    @RateLimited(policy = "search-quota", key = {"0"})
                    public String search(String query) {
                        return query;
                    }
                }
                """);
        CapturingProcessor processor = new CapturingProcessor(new RateLimitAnnotationProcessor());

        ProcessorTestHarness.Result result = ProcessorTestHarness.run(processor, source);

        result.assertSuccess();
        assertEquals(Boolean.FALSE, processor.lastProcessResult(), "process() must return false");
        assertEquals(
                0,
                result.compilation().generatedSourceFiles().size(),
                "a compilation through RateLimitAnnotationProcessor must generate zero sources");
    }

    // --- Matrix row ---

    /**
     * One named row of the matrix. {@link #toString()} is the row name so the parameterized display
     * name is exactly the identifier the proof contract lists.
     *
     * @param rowName the frozen row identifier
     * @param proof   the row's inline fixture and its decisive assertions
     */
    private record MatrixRow(String rowName, Executable proof) {

        @Override
        public String toString() {
            return rowName;
        }
    }

    // --- process() return-value observer ---

    /**
     * Delegates every {@link Processor} call to a wrapped processor while recording the boolean
     * {@link #process} returned, so a test can observe that value directly rather than inferring it
     * from side effects. Used only by {@link #shouldGenerateZeroSourcesAndReturnFalseFromProcess()}.
     */
    private static final class CapturingProcessor implements Processor {

        private final Processor delegate;
        private volatile Boolean lastProcessResult;

        CapturingProcessor(Processor delegate) {
            this.delegate = delegate;
        }

        Boolean lastProcessResult() {
            return lastProcessResult;
        }

        @Override
        public Set<String> getSupportedOptions() {
            return delegate.getSupportedOptions();
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return delegate.getSupportedAnnotationTypes();
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return delegate.getSupportedSourceVersion();
        }

        @Override
        public void init(ProcessingEnvironment processingEnv) {
            delegate.init(processingEnv);
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            boolean result = delegate.process(annotations, roundEnv);
            lastProcessResult = result;
            return result;
        }

        @Override
        public Iterable<? extends Completion> getCompletions(
                Element element, AnnotationMirror annotation, ExecutableElement member, String userText) {
            return delegate.getCompletions(element, annotation, member, userText);
        }
    }
}
