// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
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
 * APT compilation tests for the application path grammar applied to {@code @RestApplication.path}:
 * step 3 of {@link ApplicationPathGrammar} (the first matching rule, in the frozen order
 * {@code wildcard}, {@code router pattern}, {@code query}, {@code fragment},
 * {@code repeated separator}, {@code dot segment}, {@code encoded separator},
 * {@code unsupported character}) and step 4 ({@link RestApplicationScanner} reporting one compile
 * error naming the declaration, the value as written, and the rule), with unreserved values
 * reaching {@code GeneratedRestApplicationRegistration.of(…)} unchanged.
 *
 * <p>Each test compiles a {@code PathResource} and an {@code Api} declaration,
 * {@code @RestApplication(name = "api", path = <value>, resources = PathResource.class)}, through
 * {@link JaxRsPipelineProcessor} via {@link ProcessorTestHarness}, following
 * {@link RestApplicationDeclarationTest}'s fixture style, whose TP-004 holds the normalization
 * rows. An error names the declaration when its message contains its binary name. An accepted
 * value is checked at method level: the generated module declares exactly one registration method,
 * {@code apiRegistration}, whose {@code of(…)} call carries the value as its path literal and whose
 * registration's {@code path()} returns it.
 */
class ApplicationPathGrammarTest {

    // -----------------------------------------------------------------------------------------
    // Shared fixture — a PathResource and an Api declaration listing it, so the only diagnostic a
    // rejected value can produce is the grammar's own.
    // -----------------------------------------------------------------------------------------

    private static final String PATH_GRAMMAR_PACKAGE = "dev.vertique.test.pathgrammar";
    private static final String NO_AUTO_WIRE_PACKAGE = "dev.vertique.test.pathgrammar.noautowire";
    private static final String DECLARATION_SIMPLE_NAME = "Api";
    private static final String MODULE_SIMPLE_NAME = "GeneratedJaxRsResourcesModule";
    private static final String REGISTRATION_METHOD = "apiRegistration";

    private static String declarationBinaryName(String packageName) {
        return packageName + "." + DECLARATION_SIMPLE_NAME;
    }

    private static String moduleFqn(String packageName) {
        return packageName + "." + MODULE_SIMPLE_NAME;
    }

    private static JavaFileObject pathResourceFixture(String packageName) {
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

    /**
     * Builds the {@code Api} declaration for {@code value}, unquoted and unescaped (no row needs
     * escaping), preceded by {@code annotations}.
     */
    private static JavaFileObject declarationFixture(String packageName, String annotations, String value) {
        return SourceFiles.inline(declarationBinaryName(packageName), """
                package %s;

                import dev.vertique.codegen.NoAutoWire;
                import dev.vertique.rest.core.application.RestApplication;

                %s
                @RestApplication(name = "api", path = "%s", resources = PathResource.class)
                interface Api {}
                """.formatted(packageName, annotations, value));
    }

    /** Compiles {@code PathResource} and the {@code Api} declaration through the pipeline processor. */
    private static ProcessorTestHarness.Result compile(
            String packageName, String annotations, String value, Map<String, String> options) {
        return ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                options,
                pathResourceFixture(packageName),
                declarationFixture(packageName, annotations, value));
    }

    // -----------------------------------------------------------------------------------------
    // TP-001 — an invalid @RestApplication path fails compilation naming the declaration, value,
    // and rule
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
    @DisplayName("TP-001 — an invalid @RestApplication path fails compilation naming the declaration, value, and rule")
    void rejectsInvalidPathsNamingRule(String label, PathRejectionCase testCase) {
        var result = compile(PATH_GRAMMAR_PACKAGE, "", testCase.value(), testCase.options());
        logDiagnostics("TP-001 " + label, result);

        result.assertFailed();
        List<Diagnostic<? extends JavaFileObject>> errors = result.compilation().errors();
        assertEquals(
                1,
                errors.size(),
                () -> "Expected exactly one ERROR diagnostic rejecting '" + testCase.value() + "'."
                        + diagnosticsSummary(result));
        String message = errors.get(0).getMessage(null);
        String declaration = declarationBinaryName(PATH_GRAMMAR_PACKAGE);
        assertTrue(
                message != null && message.contains(declaration),
                () -> "Expected the single ERROR to name '" + declaration + "'." + diagnosticsSummary(result));
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
    // TP-002 — unreserved paths compile unchanged, and a @NoAutoWire declaration is never checked
    // -----------------------------------------------------------------------------------------

    /**
     * One TP-002 row. The {@code @NoAutoWire} row carries {@code "/api/:id"}, a value TP-001
     * rejects on a checked declaration, so a mutation that runs the grammar on opted-out
     * declarations is observable; it expects T022's opt-out warning instead of a registration.
     */
    private record UnreservedPathCase(String label, String packageName, String value, boolean noAutoWire) {}

    private static Stream<Arguments> unreservedPathCases() {
        List<UnreservedPathCase> cases = List.of(
                new UnreservedPathCase("/api/v1.0", PATH_GRAMMAR_PACKAGE, "/api/v1.0", false),
                new UnreservedPathCase("/api/~x", PATH_GRAMMAR_PACKAGE, "/api/~x", false),
                new UnreservedPathCase("/a-b_c/d", PATH_GRAMMAR_PACKAGE, "/a-b_c/d", false),
                new UnreservedPathCase("@NoAutoWire exempt: /api/:id", NO_AUTO_WIRE_PACKAGE, "/api/:id", true));
        return cases.stream().map(c -> Arguments.of(c.label(), c));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreservedPathCases")
    @DisplayName("TP-002 — unreserved paths compile unchanged, and a @NoAutoWire declaration is never checked")
    void acceptsUnreservedPaths(String label, UnreservedPathCase testCase) {
        String annotations = testCase.noAutoWire() ? "@NoAutoWire" : "";
        var result = compile(testCase.packageName(), annotations, testCase.value(), Map.of());
        logDiagnostics("TP-002 " + label, result);

        result.assertSuccess();
        if (testCase.noAutoWire()) {
            assertOnlyTheOptOutWarning(result, testCase.packageName());
            return;
        }
        assertRegistrationCarriesPathLiteral(result, testCase.packageName(), testCase.value());
    }

    // -----------------------------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Asserts that the generated module in {@code packageName} declares exactly one registration
     * method, {@code apiRegistration}, whose signature and body are
     * {@code GeneratedRestApplicationRegistration.of(Api.class, "api", "<value>", …)} with
     * {@code value} unchanged as the path literal, and whose registration names {@code Api} and
     * returns {@code value} from {@code path()}.
     */
    private static void assertRegistrationCarriesPathLiteral(
            ProcessorTestHarness.Result result, String packageName, String value) {
        String moduleFqn = moduleFqn(packageName);
        String source = sourceOf(result, moduleFqn);
        assertContainsNormalized(
                source,
                "static GeneratedRestApplicationRegistration " + REGISTRATION_METHOD
                        + "(@VertxConfig JsonObject config) {"
                        + " return GeneratedRestApplicationRegistration.of(Api.class, \"api\", \"" + value + "\","
                        + " List.of(PathResource.class), false, \"\", true); }",
                "the " + REGISTRATION_METHOD + " method in " + moduleFqn);

        Class<?> module = result.loadGeneratedClass(moduleFqn);
        List<Method> registrationMethods = registrationMethods(module);
        assertEquals(
                List.of(REGISTRATION_METHOD),
                registrationMethods.stream().map(Method::getName).toList(),
                () -> "Expected exactly Api's registration method in " + moduleFqn + "\n" + source);
        GeneratedRestApplicationRegistration registration = invokeRegistration(registrationMethods.get(0));
        assertEquals(
                result.loadGeneratedClass(declarationBinaryName(packageName)),
                registration.declaringType(),
                "declaringType()");
        assertEquals(value, registration.path(), "path()");
    }

    /**
     * Asserts that the opted-out declaration in {@code packageName} gets no error and exactly one
     * diagnostic naming it, T022's opt-out warning, that no diagnostic reports a path rule, and that
     * the module written for {@code PathResource} declares no registration method.
     */
    private static void assertOnlyTheOptOutWarning(ProcessorTestHarness.Result result, String packageName) {
        assertEquals(
                List.of(),
                describeAll(result.compilation().errors()),
                () -> "Expected no ERROR diagnostic for the @NoAutoWire declaration." + diagnosticsSummary(result));
        String declaration = declarationBinaryName(packageName);
        List<Diagnostic<? extends JavaFileObject>> naming = result.compilation().diagnostics().stream()
                .filter(d -> {
                    String message = d.getMessage(null);
                    return message != null && message.contains(declaration);
                })
                .toList();
        assertEquals(
                1,
                naming.size(),
                () -> "Expected exactly one diagnostic naming " + declaration + diagnosticsSummary(result));
        Diagnostic<? extends JavaFileObject> only = naming.get(0);
        String message = only.getMessage(null);
        assertTrue(
                (only.getKind() == Diagnostic.Kind.WARNING || only.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
                        && message.contains("is annotated @NoAutoWire")
                        && message.contains("not registered")
                        && message.contains("not validated"),
                () -> "Expected the one diagnostic naming " + declaration + " to be T022's opt-out warning."
                        + diagnosticsSummary(result));
        assertTrue(
                result.compilation().diagnostics().stream()
                        .map(d -> d.getMessage(null))
                        .noneMatch(m -> m != null && m.contains("(rule: ")),
                () -> "Expected no diagnostic reporting a path rule." + diagnosticsSummary(result));

        Class<?> module = result.loadGeneratedClass(moduleFqn(packageName));
        assertEquals(
                List.of(),
                registrationMethods(module).stream().map(Method::getName).toList(),
                () -> "Expected no registration method for the @NoAutoWire declaration."
                        + sourceOf(result, moduleFqn(packageName)));
    }

    /** The declared methods of {@code module} returning {@code GeneratedRestApplicationRegistration}. */
    private static List<Method> registrationMethods(Class<?> module) {
        return Arrays.stream(module.getDeclaredMethods())
                .filter(m -> GeneratedRestApplicationRegistration.class.equals(m.getReturnType()))
                .toList();
    }

    private static GeneratedRestApplicationRegistration invokeRegistration(Method method) {
        assertEquals(
                List.of(JsonObject.class),
                List.of(method.getParameterTypes()),
                () -> "Expected '" + method.getName() + "' to take only a JsonObject (@VertxConfig)");
        method.setAccessible(true);
        try {
            return (GeneratedRestApplicationRegistration) method.invoke(null, new JsonObject());
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new AssertionFailedError(
                    "Failed to invoke registration method '" + method.getName() + "': " + e.getMessage(), e);
        }
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
        return text.replaceAll("\\s+", " ").replaceAll(" ?([(),<>{};]) ?", "$1").trim();
    }

    /**
     * Asserts that {@code actual} contains {@code expectedSnippet} once both are collapsed to
     * single-space-separated tokens with no space beside a parenthesis, comma, angle bracket, brace,
     * or semicolon, so JavaPoet's column-100 wrapping cannot break an otherwise correct match.
     */
    private static void assertContainsNormalized(String actual, String expectedSnippet, String context) {
        String normalizedActual = normalizeWhitespace(actual);
        String normalizedExpected = normalizeWhitespace(expectedSnippet);
        assertTrue(
                normalizedActual.contains(normalizedExpected),
                () -> "Expected " + context + " to contain (after whitespace normalization):\n" + normalizedExpected
                        + "\nActual (normalized):\n" + normalizedActual);
    }

    private static List<String> describeAll(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream()
                .map(d -> "[" + d.getKind() + "] " + d.getMessage(null))
                .toList();
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
