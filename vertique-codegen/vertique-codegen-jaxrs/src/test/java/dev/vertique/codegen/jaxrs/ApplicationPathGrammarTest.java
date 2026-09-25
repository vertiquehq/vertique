// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.beans.Introspector;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentest4j.AssertionFailedError;

/**
 * APT compilation tests for the {@code @ApplicationPath} rejection grammar: step 3 (the first
 * matching rule, in the frozen order {@code wildcard}, {@code router pattern}, {@code query},
 * {@code fragment}, {@code repeated separator}, {@code dot segment}, {@code encoded separator},
 * {@code unsupported character}) and step 4 ({@link JaxRsApplicationScanner} reporting one
 * compile error naming the application class, the value as written, and the rule).
 *
 * <p>Each test compiles a small {@code jakarta.ws.rs.core.Application} fixture through {@link
 * JaxRsPipelineProcessor} via {@link ProcessorTestHarness} and asserts the compiler diagnostics
 * and/or the generated {@code GeneratedJaxRsResourcesModule} source, mirroring {@link
 * JaxRsApplicationRegistrationEmitterTest}'s fixture and assertion style.
 */
class ApplicationPathGrammarTest {

    // -----------------------------------------------------------------------------------------
    // Shared fixture — a concrete, eligible, top-level Application with a public no-arg
    // constructor, so the only diagnostic a rejected value can produce is the grammar's own.
    // -----------------------------------------------------------------------------------------

    private static final String PATH_GRAMMAR_PACKAGE = "dev.vertique.test.pathgrammar";
    private static final String PATH_GRAMMAR_CLASS = "PathGrammarApplication";
    private static final String PATH_GRAMMAR_FQN = PATH_GRAMMAR_PACKAGE + "." + PATH_GRAMMAR_CLASS;
    private static final String PATH_GRAMMAR_MODULE = PATH_GRAMMAR_PACKAGE + ".GeneratedJaxRsResourcesModule";

    /** Builds the fixture source for {@code value}, unquoted and unescaped (no row needs escaping). */
    private static JavaFileObject pathGrammarFixture(String value) {
        return SourceFiles.inline(PATH_GRAMMAR_FQN, """
                package dev.vertique.test.pathgrammar;

                import jakarta.ws.rs.ApplicationPath;
                import jakarta.ws.rs.core.Application;

                @ApplicationPath("%s")
                public class PathGrammarApplication extends Application {
                    public PathGrammarApplication() {}
                }
                """.formatted(value));
    }

    /** Builds and compiles the fixture source for {@code value} through the pipeline processor. */
    private static ProcessorTestHarness.Result compilePathGrammarFixture(String value, Map<String, String> options) {
        JavaFileObject source = pathGrammarFixture(value);
        return options.isEmpty()
                ? ProcessorTestHarness.run(new JaxRsPipelineProcessor(), source)
                : ProcessorTestHarness.run(new JaxRsPipelineProcessor(), options, source);
    }

    // -----------------------------------------------------------------------------------------
    // TP-001 — an invalid application path fails compilation naming the application, value, and
    // rule
    // -----------------------------------------------------------------------------------------

    private record PathRejectionCase(String label, String value, String rule, Map<String, String> options) {}

    private static Stream<Arguments> pathRejectionCases() {
        List<PathRejectionCase> cases = List.of(
                new PathRejectionCase("/api/*/v1 -> wildcard", "/api/*/v1", "wildcard", Map.of()),
                new PathRejectionCase("/api/:id -> router pattern", "/api/:id", "router pattern", Map.of()),
                new PathRejectionCase("/api/{v} -> router pattern", "/api/{v}", "router pattern", Map.of()),
                new PathRejectionCase("/api?x=1 -> query", "/api?x=1", "query", Map.of()),
                new PathRejectionCase("/api#frag -> fragment", "/api#frag", "fragment", Map.of()),
                new PathRejectionCase("/api/../x -> dot segment", "/api/../x", "dot segment", Map.of()),
                new PathRejectionCase("/api/./x -> dot segment", "/api/./x", "dot segment", Map.of()),
                new PathRejectionCase("/api%2Fv1 -> encoded separator", "/api%2Fv1", "encoded separator", Map.of()),
                new PathRejectionCase("/api%2fv1 -> encoded separator", "/api%2fv1", "encoded separator", Map.of()),
                new PathRejectionCase("/api%5Cv1 -> encoded separator", "/api%5Cv1", "encoded separator", Map.of()),
                new PathRejectionCase("/api%5cv1 -> encoded separator", "/api%5cv1", "encoded separator", Map.of()),
                new PathRejectionCase("/api//v1 -> repeated separator", "/api//v1", "repeated separator", Map.of()),
                new PathRejectionCase(
                        "/api/a b -> unsupported character", "/api/a b", "unsupported character", Map.of()),
                new PathRejectionCase(
                        "/api%20x -> unsupported character", "/api%20x", "unsupported character", Map.of()),
                new PathRejectionCase(
                        "/api/:id, autoWire=false -> router pattern",
                        "/api/:id",
                        "router pattern",
                        Map.of("vertique.codegen.autoWire", "false")));
        return cases.stream().map(c -> Arguments.of(c.label(), c));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pathRejectionCases")
    @DisplayName("TP-001 — an invalid application path fails compilation naming the application, value, and rule")
    void rejectsInvalidPathsNamingRule(String label, PathRejectionCase testCase) {
        var result = compilePathGrammarFixture(testCase.value(), testCase.options());
        logDiagnostics("TP-001 " + label, result);

        result.assertFailed();
        List<Diagnostic<? extends JavaFileObject>> errors = result.compilation().errors();
        assertEquals(
                1,
                errors.size(),
                () -> "Expected exactly one ERROR diagnostic rejecting '" + testCase.value() + "'."
                        + diagnosticsSummary(result));
        String message = errors.get(0).getMessage(null);
        assertTrue(
                message != null && message.contains(PATH_GRAMMAR_CLASS),
                () -> "Expected the single ERROR to name '" + PATH_GRAMMAR_CLASS + "'." + diagnosticsSummary(result));
        assertTrue(
                message.contains(testCase.value()),
                () -> "Expected the single ERROR to contain the value as written '" + testCase.value() + "'."
                        + diagnosticsSummary(result));
        assertTrue(
                message.contains("(rule: " + testCase.rule() + ")"),
                () -> "Expected the single ERROR to contain '(rule: " + testCase.rule() + ")'."
                        + diagnosticsSummary(result));
    }

    // -----------------------------------------------------------------------------------------
    // TP-002 — unreserved paths compile unchanged, and a @NoAutoWire class is never checked
    // -----------------------------------------------------------------------------------------

    private static final String NO_AUTO_WIRE_PACKAGE = "dev.vertique.test.pathgrammar.noautowire";
    private static final String NO_AUTO_WIRE_CLASS = "NoAutoWirePathGrammarApplication";
    private static final String NO_AUTO_WIRE_MODULE = NO_AUTO_WIRE_PACKAGE + ".GeneratedJaxRsResourcesModule";

    /**
     * The exempt row's fixture (owner decision Q4): a concrete, eligible-looking {@code
     * Application} annotated {@code @NoAutoWire} and {@code @ApplicationPath("/api/:id")} — a
     * value TP-001 rejects on a non-exempt class — so a mutation that runs the grammar on {@code
     * @NoAutoWire} classes anyway (M-7) is observable.
     */
    private static final JavaFileObject NO_AUTO_WIRE_APPLICATION =
            SourceFiles.inline(NO_AUTO_WIRE_PACKAGE + "." + NO_AUTO_WIRE_CLASS, """
            package dev.vertique.test.pathgrammar.noautowire;

            import dev.vertique.codegen.NoAutoWire;
            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @NoAutoWire
            @ApplicationPath("/api/:id")
            public class NoAutoWirePathGrammarApplication extends Application {
                public NoAutoWirePathGrammarApplication() {}
            }
            """);

    private record UnreservedPathCase(
            String label,
            JavaFileObject source,
            String moduleFqn,
            String applicationSimpleName,
            String value,
            boolean noAutoWireExempt) {}

    private static Stream<Arguments> unreservedPathCases() {
        List<UnreservedPathCase> cases = List.of(
                new UnreservedPathCase(
                        "/api/v1.0",
                        pathGrammarFixture("/api/v1.0"),
                        PATH_GRAMMAR_MODULE,
                        PATH_GRAMMAR_CLASS,
                        "/api/v1.0",
                        false),
                new UnreservedPathCase(
                        "/api/~x",
                        pathGrammarFixture("/api/~x"),
                        PATH_GRAMMAR_MODULE,
                        PATH_GRAMMAR_CLASS,
                        "/api/~x",
                        false),
                new UnreservedPathCase(
                        "/a-b_c/d",
                        pathGrammarFixture("/a-b_c/d"),
                        PATH_GRAMMAR_MODULE,
                        PATH_GRAMMAR_CLASS,
                        "/a-b_c/d",
                        false),
                new UnreservedPathCase(
                        "@NoAutoWire exempt: /api/:id",
                        NO_AUTO_WIRE_APPLICATION,
                        NO_AUTO_WIRE_MODULE,
                        NO_AUTO_WIRE_CLASS,
                        "/api/:id",
                        true));
        return cases.stream().map(c -> Arguments.of(c.label(), c));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreservedPathCases")
    @DisplayName("TP-002 — unreserved paths compile unchanged, and a @NoAutoWire class is never checked")
    void acceptsUnreservedPaths(String label, UnreservedPathCase testCase) {
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), testCase.source());
        logDiagnostics("TP-002 " + label, result);

        result.assertSuccess();
        if (testCase.noAutoWireExempt()) {
            assertTrue(
                    result.compilation().errors().isEmpty(),
                    () -> "Expected no ERROR diagnostic for the @NoAutoWire exempt application."
                            + diagnosticsSummary(result));
            result.assertWarningMessage(testCase.applicationSimpleName()
                    + " is annotated @NoAutoWire, so it is not registered or" + " validated");
            assertTrue(
                    result.compilation()
                            .generatedSourceFile(testCase.moduleFqn())
                            .isEmpty(),
                    () -> "Expected no generated module registering the @NoAutoWire exempt application."
                            + diagnosticsSummary(result));
            return;
        }
        assertNormalizedPathLiteral(result, testCase.moduleFqn(), testCase.applicationSimpleName(), testCase.value());
    }

    // -----------------------------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Asserts that the generated module at {@code moduleFqn} declares a {@code
     * GeneratedJaxRsApplicationRegistration} provider method for {@code applicationSimpleName}
     * whose body's {@code of(...)} call carries {@code value} unchanged as the normalized path
     * literal, mirroring {@link JaxRsApplicationRegistrationEmitterTest}'s
     * normalized-path assertions.
     */
    private static void assertNormalizedPathLiteral(
            ProcessorTestHarness.Result result, String moduleFqn, String applicationSimpleName, String value) {
        String source = sourceOf(result, moduleFqn);
        String methodName = Introspector.decapitalize(applicationSimpleName) + "Registration";
        assertContainsNormalized(
                source,
                "static GeneratedJaxRsApplicationRegistration " + methodName + "(@VertxConfig JsonObject config) {",
                "the " + methodName + " signature in " + moduleFqn);
        assertContainsNormalized(
                source,
                "return GeneratedJaxRsApplicationRegistration.of(" + applicationSimpleName + ".class, \"" + value
                        + "\", true, " + applicationSimpleName + "::new);",
                "the " + methodName + " body in " + moduleFqn);
    }

    /**
     * Reads the character content of a generated source file, asserting its presence first so a
     * missing file fails as an {@link AssertionFailedError} rather than surfacing an unchecked
     * {@code Optional.get()} exception.
     */
    private static String sourceOf(ProcessorTestHarness.Result result, String generatedFqn) {
        var generated = result.compilation().generatedSourceFile(generatedFqn);
        assertTrue(
                generated.isPresent(),
                () -> "Expected generated source file for '" + generatedFqn + "' but none was found."
                        + diagnosticsSummary(result));
        try {
            return generated.get().getCharContent(true).toString();
        } catch (IOException e) {
            throw new AssertionFailedError(
                    "Failed to read generated source for '" + generatedFqn + "': " + e.getMessage());
        }
    }

    private static String normalizeWhitespace(String text) {
        return text.replaceAll("\\s+", " ").replaceAll(" ?([(),<>]) ?", "$1").trim();
    }

    /**
     * Asserts that {@code actual} contains {@code expectedSnippet} once both are collapsed to
     * single-space-separated tokens with no space beside a parenthesis, comma, or angle bracket, so
     * JavaPoet's column-100 wrapping cannot break an otherwise correct match.
     */
    private static void assertContainsNormalized(String actual, String expectedSnippet, String context) {
        String normalizedActual = normalizeWhitespace(actual);
        String normalizedExpected = normalizeWhitespace(expectedSnippet);
        assertTrue(
                normalizedActual.contains(normalizedExpected),
                () -> "Expected " + context + " to contain (after whitespace normalization):\n" + normalizedExpected
                        + "\nActual (normalized):\n" + normalizedActual);
    }

    private static void logDiagnostics(String label, ProcessorTestHarness.Result result) {
        System.out.println("=== " + label + " diagnostics ===");
        result.compilation()
                .diagnostics()
                .forEach(d -> System.out.println("[" + d.getKind() + "] " + d.getMessage(null)));
    }

    private static String diagnosticsSummary(ProcessorTestHarness.Result result) {
        StringBuilder sb = new StringBuilder("\nCompilation diagnostics:\n");
        result.compilation().diagnostics().forEach(d -> sb.append("  [")
                .append(d.getKind())
                .append("] ")
                .append(d.getMessage(null))
                .append('\n'));
        return sb.toString();
    }
}
