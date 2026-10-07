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
 * APT compilation tests for the compile side of the {@code @ApiDocs} checks on a
 * {@code @RestApplication} declaration. {@code policy} is required and names a valid access policy;
 * {@code securityScheme} must be empty exactly when that policy is public (one direct
 * {@code @PermitAll}; every other valid policy, {@code @DenyAll} included, is not public); and the
 * former {@code access} and {@code rolesAllowed} elements no longer exist. Each processor-level
 * violation fails compilation naming the interface and the attribute; the removed elements and a
 * missing {@code policy} fail in javac's own element resolution. The processor recognizes
 * {@code @ApiDocs} by one pinned fully qualified name, so an unrelated annotation with the same
 * simple name is not checked.
 *
 * <p>Each row compiles {@code PathResource}, {@code Policies} and {@code Api} through
 * {@link JaxRsPipelineProcessor} via {@link ProcessorTestHarness}. {@code PathResource} is a
 * concrete class with {@code @Path("/p")}, an {@code @Inject} constructor, and one {@code @GET}
 * method; {@code Policies} declares the public policy interfaces the rows name; {@code Api} is
 * {@code @RestApplication(name = "api", path = "/api", resources = PathResource.class) interface Api {}}
 * carrying the row's annotation, where {@code @ApiDocs} is the test-source {@link ApiDocs}. An
 * error names {@code Api} when its message contains {@code Api}'s binary name. An accepted row's
 * registration is checked at method level: the generated module declares exactly one method
 * returning {@code GeneratedRestApplicationRegistration}, {@code apiRegistration}, whose
 * registration names {@code Api}.
 */
class ApiDocsCompileCheckTest {

    private static final String PKG = "dev.vertique.test.apidocs.compilecheck";
    private static final String API_BINARY_NAME = PKG + ".Api";
    private static final String MODULE_FQN = PKG + ".GeneratedJaxRsResourcesModule";

    /** javac's diagnostic code for an annotation missing a value for an element without a default. */
    private static final String MISSING_ELEMENT_CODE = "compiler.err.annotation.missing.default.value";

    /** javac's diagnostic code for an annotation that sets an element the annotation type lacks. */
    private static final String UNKNOWN_ELEMENT_CODE = "compiler.err.cant.resolve.location.args";

    /** javac's diagnostic code for a policy class literal that is not an access policy type. */
    private static final String NOT_A_POLICY_TYPE_CODE = "compiler.err.prob.found.req";

    /** javac's diagnostic code for a policy class literal naming a type that does not exist. */
    private static final String UNRESOLVED_TYPE_CODE = "compiler.err.cant.resolve.location";

    /** How a row's compilation is expected to end. */
    enum Outcome {
        /** Compiles and emits {@code Api}'s registration. */
        ACCEPTED,
        /** Fails with a processor error naming {@code Api} and the row's attribute. */
        REJECTED,
        /** Fails with javac's missing-element error for the row's attribute. */
        MISSING_ELEMENT,
        /** Fails with javac's unknown-element error for the row's attribute. */
        UNKNOWN_ELEMENT,
        /** Fails with javac's error for a {@code policy} value that is not an access policy type. */
        NOT_A_POLICY_TYPE,
        /** Fails with javac's error for a {@code policy} value naming a type that does not exist. */
        UNRESOLVED_POLICY_TYPE
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
     * The policy interfaces the rows name. Every nested policy is explicitly public, as a member of
     * a class must be to count as public. The valid ones carry one direct runtime requirement; each
     * invalid one breaks exactly one rule of a valid policy.
     */
    private static final JavaFileObject POLICIES = SourceFiles.inline(PKG + ".Policies", """
            package %s;

            import dev.vertique.security.authz.AccessPolicy;
            import dev.vertique.security.authz.Authorized;
            import jakarta.annotation.security.DenyAll;
            import jakarta.annotation.security.PermitAll;
            import jakarta.annotation.security.RolesAllowed;

            public class Policies {
                @PermitAll
                public interface Open extends AccessPolicy {}

                @Authorized
                public interface AuthenticatedOnly extends AccessPolicy {}

                @RolesAllowed({"admin", "ops"})
                public interface Admins extends AccessPolicy {}

                @DenyAll
                public interface Denied extends AccessPolicy {}

                @PermitAll
                @RolesAllowed("admin")
                public interface OpenAndRoles extends AccessPolicy {}

                @RolesAllowed(" ")
                public interface BlankRole extends AccessPolicy {}

                @RolesAllowed({})
                public interface EmptyRoles extends AccessPolicy {}

                public interface NoRequirement extends AccessPolicy {}

                public interface Unrelated {}

                @PermitAll
                public interface TwoParents extends AccessPolicy, Unrelated {}

                @PermitAll
                public interface Derived extends Open {}

                @PermitAll
                public interface WithMethod extends AccessPolicy {
                    String name();
                }

                @PermitAll
                public static class ClassPolicy implements AccessPolicy {}

                /** An interface that does not extend the policy marker. */
                @PermitAll
                public interface NotAPolicy extends Unrelated {}
            }

            @jakarta.annotation.security.PermitAll
            interface HiddenOpen extends dev.vertique.security.authz.AccessPolicy {}
            """.formatted(PKG));

    /**
     * An unrelated {@code CLASS}-retained annotation sharing {@code ApiDocs}'s simple name and
     * shape, so the runtime allow list does not reject it and only a simple-name match would check
     * it.
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
                Class<?> policy();

                String securityScheme() default "";
            }
            """);

    /**
     * {@code Api} carrying {@code annotation} above its {@code @RestApplication}, with {@code body}
     * as its members. The removed {@code access} element's constants stay imported so a row can
     * still use them.
     */
    private static JavaFileObject apiFixture(String annotation, String body) {
        return SourceFiles.inline(API_BINARY_NAME, """
                package %s;

                import static dev.vertique.rest.openapi.docs.ApiDocs.Access.PROTECTED;
                import static dev.vertique.rest.openapi.docs.ApiDocs.Access.PUBLIC;

                import dev.vertique.rest.core.application.RestApplication;
                import dev.vertique.rest.openapi.docs.ApiDocs;
                import dev.vertique.security.authz.AccessPolicy;
                import jakarta.annotation.security.PermitAll;

                %s
                @RestApplication(name = "api", path = "/api", resources = PathResource.class)
                interface Api {
                    %s
                }
                """.formatted(PKG, annotation, body));
    }

    // -----------------------------------------------------------------------------------------
    // @ApiDocs shapes are checked at compile time, naming the interface and attribute
    // -----------------------------------------------------------------------------------------

    private static final String NESTED_POLICY = "@PermitAll interface NestedOpen extends AccessPolicy {}";

    private static Arguments row(String annotation, Outcome outcome, String attribute) {
        return Arguments.of(annotation, "", outcome, attribute, List.of());
    }

    private static Stream<Arguments> apiDocsShapes() {
        return Stream.of(
                // Valid declarations: a public policy with no scheme, and every other valid policy
                // (deny included) with a scheme.
                row("@ApiDocs(policy = Policies.Open.class)", Outcome.ACCEPTED, null),
                row(
                        "@ApiDocs(policy = Policies.AuthenticatedOnly.class, securityScheme = \"bearerAuth\")",
                        Outcome.ACCEPTED,
                        null),
                row(
                        "@ApiDocs(policy = Policies.Admins.class, securityScheme = \"bearerAuth\")",
                        Outcome.ACCEPTED,
                        null),
                row(
                        "@ApiDocs(policy = Policies.Denied.class, securityScheme = \"bearerAuth\")",
                        Outcome.ACCEPTED,
                        null),
                // A policy nested in the application interface is a member of an interface, so it is
                // implicitly public without the modifier.
                Arguments.of(
                        "@ApiDocs(policy = Api.NestedOpen.class)", NESTED_POLICY, Outcome.ACCEPTED, null, List.of()),
                // A public policy takes no scheme; any supplied value, blank included, is one.
                row(
                        "@ApiDocs(policy = Policies.Open.class, securityScheme = \"bearerAuth\")",
                        Outcome.REJECTED,
                        "securityScheme"),
                row(
                        "@ApiDocs(policy = Policies.Open.class, securityScheme = \" \")",
                        Outcome.REJECTED,
                        "securityScheme"),
                // Every other valid policy needs a scheme.
                row("@ApiDocs(policy = Policies.AuthenticatedOnly.class)", Outcome.REJECTED, "securityScheme"),
                row("@ApiDocs(policy = Policies.Admins.class)", Outcome.REJECTED, "securityScheme"),
                row("@ApiDocs(policy = Policies.Denied.class)", Outcome.REJECTED, "securityScheme"),
                // A blank scheme is not a scheme either.
                row(
                        "@ApiDocs(policy = Policies.AuthenticatedOnly.class, securityScheme = \" \")",
                        Outcome.REJECTED,
                        "securityScheme"),
                row(
                        "@ApiDocs(policy = Policies.Admins.class, securityScheme = \" \")",
                        Outcome.REJECTED,
                        "securityScheme"),
                // An invalid policy is rejected whatever the scheme is.
                row("@ApiDocs(policy = Policies.OpenAndRoles.class)", Outcome.REJECTED, "policy"),
                row(
                        "@ApiDocs(policy = Policies.BlankRole.class, securityScheme = \"bearerAuth\")",
                        Outcome.REJECTED,
                        "policy"),
                row(
                        "@ApiDocs(policy = Policies.EmptyRoles.class, securityScheme = \"bearerAuth\")",
                        Outcome.REJECTED,
                        "policy"),
                row("@ApiDocs(policy = Policies.NoRequirement.class)", Outcome.REJECTED, "policy"),
                row("@ApiDocs(policy = Policies.ClassPolicy.class)", Outcome.REJECTED, "policy"),
                row("@ApiDocs(policy = dev.vertique.security.authz.AccessPolicy.class)", Outcome.REJECTED, "policy"),
                row("@ApiDocs(policy = Policies.TwoParents.class)", Outcome.REJECTED, "policy"),
                row("@ApiDocs(policy = Policies.Derived.class)", Outcome.REJECTED, "policy"),
                row("@ApiDocs(policy = Policies.WithMethod.class)", Outcome.REJECTED, "policy"),
                row("@ApiDocs(policy = HiddenOpen.class)", Outcome.REJECTED, "policy"),
                // The type system rejects a class literal that is not an access policy type at all,
                // and one naming a type that does not exist.
                row("@ApiDocs(policy = Policies.NotAPolicy.class)", Outcome.NOT_A_POLICY_TYPE, "policy"),
                row("@ApiDocs(policy = Policies.Missing.class)", Outcome.UNRESOLVED_POLICY_TYPE, "Missing"),
                // policy is required.
                row("@ApiDocs", Outcome.MISSING_ELEMENT, "policy"),
                row("@ApiDocs(securityScheme = \"bearerAuth\")", Outcome.MISSING_ELEMENT, "policy"),
                // The removed authorization elements no longer exist, even beside a valid policy.
                row("@ApiDocs(policy = Policies.Open.class, access = PUBLIC)", Outcome.UNKNOWN_ELEMENT, "access"),
                row(
                        "@ApiDocs(policy = Policies.Admins.class, securityScheme = \"bearerAuth\","
                                + " rolesAllowed = \"admin\")",
                        Outcome.UNKNOWN_ELEMENT,
                        "rolesAllowed"),
                row("@ApiDocs(access = PROTECTED, securityScheme = \"bearerAuth\")", Outcome.MISSING_ELEMENT, "policy"),
                // An unrelated annotation with the same simple name is not checked, even where the
                // same shape on the real one would be rejected.
                Arguments.of(
                        "@other.ApiDocs(policy = Policies.Open.class, securityScheme = \"bearerAuth\")",
                        "",
                        Outcome.ACCEPTED,
                        null,
                        List.of(OTHER_API_DOCS)),
                Arguments.of(
                        "@other.ApiDocs(policy = Policies.Denied.class)",
                        "",
                        Outcome.ACCEPTED,
                        null,
                        List.of(OTHER_API_DOCS)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("apiDocsShapes")
    @DisplayName("policy is required, removed authorization elements reject, and policy and scheme must agree")
    void shouldRequirePolicyAndRejectRemovedAuthorizationElements(
            String annotation, String apiBody, Outcome expected, String attribute, List<JavaFileObject> extraSources) {
        List<JavaFileObject> sources =
                new ArrayList<>(List.of(PATH_RESOURCE, POLICIES, apiFixture(annotation, apiBody)));
        sources.addAll(extraSources);
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), sources.toArray(JavaFileObject[]::new));
        logDiagnostics("@ApiDocs shape " + annotation, result);
        switch (expected) {
            case ACCEPTED -> assertEmitsOnlyApiRegistration(result);
            case REJECTED -> assertErrorNamingApiAnd(result, attribute);
            case MISSING_ELEMENT -> assertJavacError(result, MISSING_ELEMENT_CODE, "'" + attribute + "'");
            case UNKNOWN_ELEMENT -> assertJavacError(result, UNKNOWN_ELEMENT_CODE, attribute);
            case NOT_A_POLICY_TYPE -> assertJavacError(result, NOT_A_POLICY_TYPE_CODE, "NotAPolicy");
            case UNRESOLVED_POLICY_TYPE -> assertJavacError(result, UNRESOLVED_TYPE_CODE, attribute);
        }
    }

    // -----------------------------------------------------------------------------------------
    // The processor's @ApiDocs name is one pinned literal
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("the processor's @ApiDocs name is one pinned literal")
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

    private static void assertJavacError(ProcessorTestHarness.Result result, String code, String messagePart) {
        result.assertFailed();
        boolean found = result.compilation().diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .filter(d -> code.equals(d.getCode()))
                .map(d -> d.getMessage(null))
                .filter(Objects::nonNull)
                .anyMatch(message -> message.contains(messagePart));
        assertTrue(
                found,
                () -> "Expected javac's error (" + code + ") mentioning " + messagePart + diagnosticsSummary(result));
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
