// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.jaxrs.processor.validate.ApplicationAnnotationValidator;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.openapi.docs.ApiDocs;
import io.vertx.core.json.JsonObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentest4j.AssertionFailedError;

/**
 * APT compilation tests for the compile side of FR-033's {@code @ApiDocs} checks (C-APPCHECK,
 * AC-033.2, AC-011.2's compile-time rows): on a {@code @RestApplication} declaration,
 * {@code securityScheme} is required and non-blank exactly when {@code access} is
 * {@code PROTECTED}, and {@code rolesAllowed} is allowed only then, with non-blank entries; each
 * violation fails compilation naming the interface and the attribute. The processor recognizes
 * {@code @ApiDocs} by one pinned fully qualified name (AR3-009), so an unrelated annotation with
 * the same simple name is not checked.
 *
 * <p>Each row compiles {@code PathResource} and {@code Api} through {@link JaxRsPipelineProcessor}
 * via {@link ProcessorTestHarness}. {@code PathResource} is a concrete class with
 * {@code @Path("/p")}, an {@code @Inject} constructor, and one {@code @GET} method; {@code Api} is
 * {@code @RestApplication(name = "api", path = "/api", resources = PathResource.class) interface Api {}}
 * carrying the row's annotation, where {@code @ApiDocs} is the test-source {@link ApiDocs}. An
 * error names {@code Api} when its message contains {@code Api}'s binary name. An accepted row's
 * registration is checked at method level: the generated module declares exactly one method
 * returning {@code GeneratedRestApplicationRegistration}, {@code apiRegistration}, whose
 * registration names {@code Api}.
 */
class ApiDocsCompileCheckTest {

    private static final String PKG = "dev.vertique.test.t028.tp006";
    private static final String API_BINARY_NAME = PKG + ".Api";
    private static final String MODULE_FQN = PKG + ".GeneratedJaxRsResourcesModule";

    /** javac's diagnostic code for an annotation missing a value for an element without a default. */
    private static final String MISSING_ELEMENT_CODE = "compiler.err.annotation.missing.default.value";

    /** How a row's compilation is expected to end. */
    enum Outcome {
        /** Compiles and emits {@code Api}'s registration. */
        ACCEPTED,
        /** Fails with an error naming {@code Api} and the row's attribute. */
        REJECTED,
        /** Fails with javac's missing-element error for the row's attribute. */
        MISSING_ELEMENT
    }

    // -----------------------------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------------------------

    private static final JavaFileObject PATH_RESOURCE = SourceFiles.inline(PKG + ".PathResource", """
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
            """.formatted(PKG));

    /**
     * An unrelated {@code CLASS}-retained annotation sharing {@code ApiDocs}'s simple name and
     * shape, so FR-025's runtime allow list does not reject it and only a simple-name match would
     * check it.
     */
    private static final JavaFileObject OTHER_API_DOCS = SourceFiles.inline("other.ApiDocs", """
            package other;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Retention(RetentionPolicy.CLASS)
            @Target(ElementType.TYPE)
            public @interface ApiDocs {
                Access access();

                String securityScheme() default "";

                String[] rolesAllowed() default {};

                enum Access { PUBLIC, PROTECTED }
            }
            """);

    private static JavaFileObject apiFixture(String annotation) {
        return SourceFiles.inline(API_BINARY_NAME, """
                package %s;

                import static dev.vertique.rest.openapi.docs.ApiDocs.Access.PROTECTED;
                import static dev.vertique.rest.openapi.docs.ApiDocs.Access.PUBLIC;

                import dev.vertique.rest.core.application.RestApplication;
                import dev.vertique.rest.openapi.docs.ApiDocs;

                %s
                @RestApplication(name = "api", path = "/api", resources = PathResource.class)
                interface Api {}
                """.formatted(PKG, annotation));
    }

    // -----------------------------------------------------------------------------------------
    // TP-006 — @ApiDocs shapes are checked at compile time, naming the interface and attribute
    // -----------------------------------------------------------------------------------------

    private static Stream<Arguments> apiDocsShapes() {
        return Stream.of(
                Arguments.of("@ApiDocs(access = PROTECTED)", Outcome.REJECTED, "securityScheme", List.of()),
                Arguments.of(
                        "@ApiDocs(access = PROTECTED, securityScheme = \" \")",
                        Outcome.REJECTED,
                        "securityScheme",
                        List.of()),
                Arguments.of(
                        "@ApiDocs(access = PUBLIC, securityScheme = \"bearerAuth\")",
                        Outcome.REJECTED,
                        "securityScheme",
                        List.of()),
                Arguments.of(
                        "@ApiDocs(access = PUBLIC, rolesAllowed = \"admin\")",
                        Outcome.REJECTED,
                        "rolesAllowed",
                        List.of()),
                Arguments.of(
                        "@ApiDocs(access = PROTECTED, securityScheme = \"bearerAuth\", rolesAllowed = {\"admin\", \" \"})",
                        Outcome.REJECTED,
                        "rolesAllowed",
                        List.of()),
                Arguments.of(
                        "@ApiDocs(access = PROTECTED, securityScheme = \"bearerAuth\", rolesAllowed = \"\")",
                        Outcome.REJECTED,
                        "rolesAllowed",
                        List.of()),
                Arguments.of("@ApiDocs(access = PUBLIC)", Outcome.ACCEPTED, null, List.of()),
                Arguments.of(
                        "@ApiDocs(access = PROTECTED, securityScheme = \"bearerAuth\")",
                        Outcome.ACCEPTED,
                        null,
                        List.of()),
                Arguments.of(
                        "@ApiDocs(access = PROTECTED, securityScheme = \"bearerAuth\", rolesAllowed = {\"admin\","
                                + " \"ops\"})",
                        Outcome.ACCEPTED,
                        null,
                        List.of()),
                Arguments.of("@ApiDocs", Outcome.MISSING_ELEMENT, "access", List.of()),
                Arguments.of(
                        "@other.ApiDocs(access = other.ApiDocs.Access.PROTECTED)",
                        Outcome.ACCEPTED,
                        null,
                        List.of(OTHER_API_DOCS)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("apiDocsShapes")
    @DisplayName("TP-006 — @ApiDocs shapes are checked at compile time, naming the interface and attribute")
    void apiDocsShapesAreCheckedAtCompileTime(
            String annotation, Outcome expected, String attribute, List<JavaFileObject> extraSources) {
        List<JavaFileObject> sources = new ArrayList<>(List.of(PATH_RESOURCE, apiFixture(annotation)));
        sources.addAll(extraSources);
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), sources.toArray(JavaFileObject[]::new));
        logDiagnostics("TP-006 " + annotation, result);
        switch (expected) {
            case ACCEPTED -> assertEmitsOnlyApiRegistration(result);
            case REJECTED -> assertErrorNamingApiAnd(result, attribute);
            case MISSING_ELEMENT -> assertMissingElementError(result, attribute);
        }
    }

    // -----------------------------------------------------------------------------------------
    // TP-008 — the processor's @ApiDocs name is one pinned literal
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("TP-008 — the processor's @ApiDocs name is one pinned literal")
    void apiDocsAnnotationNameIsPinned() {
        assertEquals("dev.vertique.rest.openapi.docs.ApiDocs", ApplicationAnnotationValidator.API_DOCS_FQN);
        assertEquals(ApiDocs.class.getName(), ApplicationAnnotationValidator.API_DOCS_FQN);
    }

    // -----------------------------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------------------------

    private static void assertErrorNamingApiAnd(ProcessorTestHarness.Result result, String attribute) {
        result.assertFailed();
        boolean found = errorMessages(result).stream()
                .anyMatch(message -> message.contains(API_BINARY_NAME) && message.contains(attribute));
        assertTrue(
                found,
                () -> "Expected an ERROR naming " + API_BINARY_NAME + " and '" + attribute + "'"
                        + diagnosticsSummary(result));
    }

    private static void assertMissingElementError(ProcessorTestHarness.Result result, String element) {
        result.assertFailed();
        boolean found = result.compilation().diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .filter(d -> MISSING_ELEMENT_CODE.equals(d.getCode()))
                .map(d -> d.getMessage(null))
                .filter(Objects::nonNull)
                .anyMatch(message -> message.contains("'" + element + "'"));
        assertTrue(
                found,
                () -> "Expected javac's missing-element error (" + MISSING_ELEMENT_CODE + ") for '" + element + "'"
                        + diagnosticsSummary(result));
    }

    /**
     * Asserts that the unit compiled and that its generated module declares exactly one method
     * returning {@code GeneratedRestApplicationRegistration}, {@code apiRegistration}, whose
     * registration's declaring type is {@code Api}.
     */
    private static void assertEmitsOnlyApiRegistration(ProcessorTestHarness.Result result) {
        result.assertSuccess();
        Class<?> module = result.loadGeneratedClass(MODULE_FQN);
        Class<?> apiType = result.loadGeneratedClass(API_BINARY_NAME);
        List<Method> registrationMethods = Arrays.stream(module.getDeclaredMethods())
                .filter(m -> GeneratedRestApplicationRegistration.class.equals(m.getReturnType()))
                .toList();
        assertEquals(
                List.of("apiRegistration"),
                registrationMethods.stream().map(Method::getName).toList(),
                () -> "Expected exactly Api's registration method" + diagnosticsSummary(result));
        GeneratedRestApplicationRegistration registration = invokeRegistration(registrationMethods.get(0));
        assertEquals(apiType, registration.declaringType(), "declaringType()");
        assertEquals("api", registration.name(), "name()");
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

    private static List<String> errorMessages(ProcessorTestHarness.Result result) {
        return result.compilation().diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(null))
                .filter(Objects::nonNull)
                .toList();
    }

    private static void logDiagnostics(String label, ProcessorTestHarness.Result result) {
        System.out.println("=== " + label + " diagnostics ===");
        result.compilation()
                .diagnostics()
                .forEach(d -> System.out.println("[" + d.getKind() + "] " + d.getCode() + " " + d.getMessage(null)));
    }

    private static String diagnosticsSummary(ProcessorTestHarness.Result result) {
        StringBuilder sb = new StringBuilder("\nCompilation diagnostics:\n");
        result.compilation().diagnostics().forEach(d -> sb.append("  [")
                .append(d.getKind())
                .append("] ")
                .append(d.getCode())
                .append(' ')
                .append(d.getMessage(null))
                .append('\n'));
        return sb.toString();
    }
}
