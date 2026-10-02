// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import io.vertx.core.json.JsonObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentest4j.AssertionFailedError;

/**
 * APT compilation tests for FR-025's compile-time allow list on {@code @RestApplication}
 * declarations (C-APPCHECK): a declaration may carry only {@code @RestApplication},
 * {@code @ApiDocs}, {@code @OpenAPIDefinition} with only {@code info} set, {@code java.lang} and
 * {@code java.lang.annotation} types, and the source-retained {@code @ConditionalOnProperty} and
 * {@code @NoAutoWire}; any other runtime annotation on the declaration or on a superinterface,
 * transitively, fails compilation naming the declaration, the annotation, and the type carrying it;
 * and the declaration-only annotations fail on a superinterface. A {@code @NoAutoWire} declaration
 * is not checked, while {@code -Avertique.codegen.autoWire=false} still checks every declaration
 * (C-APP).
 *
 * <p>Each test compiles small fixtures through {@link JaxRsPipelineProcessor} via
 * {@link ProcessorTestHarness}, following {@link RestApplicationDeclarationTest}'s fixture style.
 * {@code PathResource} is a concrete class with {@code @Path("/p")}, an {@code @Inject}
 * constructor, and one {@code @GET} method; {@code Api} is
 * {@code @RestApplication(name = "api", path = "/api", resources = PathResource.class) interface Api {}},
 * with the row's annotations and superinterfaces added. {@code @ApiDocs} is the test-source
 * {@link dev.vertique.rest.openapi.docs.ApiDocs}. An error names a type or annotation when its
 * message contains the binary name. An accepted row's registration is checked at method level:
 * the generated module declares exactly one method returning
 * {@code GeneratedRestApplicationRegistration}, {@code apiRegistration}, whose registration names
 * {@code Api}. One rejected row, {@code @RolesAllowed} on {@code Api}, also runs a
 * {@link GeneratedModuleRecorder} beside the pipeline, as {@link RestApplicationDeclarationTest}
 * does, to read the failed compilation's generated module, which the harness cannot return once
 * compilation fails, and asserts that the module holds no registration for {@code Api}.
 */
class RestApplicationAllowListTest {

    private static final String MODULE_SIMPLE_NAME = "GeneratedJaxRsResourcesModule";

    private static final String ROLES_ALLOWED_FQN = "jakarta.annotation.security.RolesAllowed";
    private static final String PATH_FQN = "jakarta.ws.rs.Path";
    private static final String SINGLETON_FQN = "jakarta.inject.Singleton";
    private static final String NAMED_FQN = "jakarta.inject.Named";
    private static final String APPLICATION_PATH_FQN = "jakarta.ws.rs.ApplicationPath";
    private static final String API_DOCS_FQN = "dev.vertique.rest.openapi.docs.ApiDocs";
    private static final String OPEN_API_DEFINITION_FQN = "io.swagger.v3.oas.annotations.OpenAPIDefinition";
    private static final String REST_APPLICATION_FQN = RestApplication.class.getName();
    private static final String CONDITIONAL_ON_PROPERTY_FQN = "dev.vertique.codegen.ConditionalOnProperty";
    private static final String NO_AUTO_WIRE_FQN = "dev.vertique.codegen.NoAutoWire";
    private static final String CONDITIONAL_ON_PROPERTIES_FQN = "dev.vertique.codegen.ConditionalOnProperties";

    /** rest-024's statement that only {@code @OpenAPIDefinition}'s {@code info} element may be set. */
    private static final String INFO_ONLY_PHRASE = "only its info element may be set";

    private static final String API_DOCS_IMPORTS = """
            import static dev.vertique.rest.openapi.docs.ApiDocs.Access.PROTECTED;
            import static dev.vertique.rest.openapi.docs.ApiDocs.Access.PUBLIC;

            import dev.vertique.rest.openapi.docs.ApiDocs;
            """;

    // -----------------------------------------------------------------------------------------
    // Shared fixtures
    // -----------------------------------------------------------------------------------------

    /** One unit: the package holding {@code Api}, and the unit's sources. */
    private record Fixture(String packageName, List<JavaFileObject> sources) {

        String apiBinaryName() {
            return packageName + ".Api";
        }
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
     * {@code Api} in {@code packageName}, with {@code imports}, {@code annotations} above its
     * {@code @RestApplication}, and {@code extendsClause} (empty, or {@code " extends X"}).
     */
    private static JavaFileObject apiFixture(
            String packageName, String imports, String annotations, String extendsClause) {
        return SourceFiles.inline(
                packageName + ".Api", """
                package %s;

                import dev.vertique.rest.core.application.RestApplication;
                %s

                %s
                @RestApplication(name = "api", path = "/api", resources = PathResource.class)
                interface Api%s {}
                """.formatted(packageName, imports, annotations, extendsClause));
    }

    /**
     * {@code Api} in {@code packageName} whose members carry annotations the declaration itself may
     * not carry: {@code @Named("x")} on a constant and {@code @RolesAllowed("admin")} on a default
     * method.
     */
    private static JavaFileObject apiWithAnnotatedMembersFixture(String packageName) {
        return SourceFiles.inline(packageName + ".Api", """
                package %s;

                import dev.vertique.rest.core.application.RestApplication;
                import jakarta.annotation.security.RolesAllowed;
                import jakarta.inject.Named;

                @RestApplication(name = "api", path = "/api", resources = PathResource.class)
                interface Api {
                    @Named("x")
                    String NAME = "x";

                    @RolesAllowed("admin")
                    default String greeting() { return ""; }
                }
                """.formatted(packageName));
    }

    /** A plain interface {@code simpleName} in {@code packageName} with the given parts. */
    private static JavaFileObject interfaceFixture(
            String packageName, String simpleName, String imports, String annotations, String extendsClause) {
        return SourceFiles.inline(packageName + "." + simpleName, """
                package %s;

                %s

                %s
                interface %s%s {}
                """.formatted(
                        packageName, imports, annotations, simpleName, extendsClause));
    }

    /** A {@code TYPE}-targeted marker annotation {@code simpleName} with {@code retention}. */
    private static JavaFileObject markerAnnotationFixture(String packageName, String simpleName, String retention) {
        return SourceFiles.inline(packageName + "." + simpleName, """
                package %s;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.%s)
                @Target(ElementType.TYPE)
                @interface %s {}
                """.formatted(packageName, retention, simpleName));
    }

    private static Fixture unit(String packageName, JavaFileObject... sources) {
        List<JavaFileObject> all = new ArrayList<>();
        all.add(pathResourceFixture(packageName));
        all.addAll(List.of(sources));
        return new Fixture(packageName, List.copyOf(all));
    }

    // -----------------------------------------------------------------------------------------
    // TP-004 — a disallowed runtime annotation on a declaration or superinterface fails compilation
    // -----------------------------------------------------------------------------------------

    private static Arguments rejectedRow(String label, Fixture fixture, String carrierSimpleName, String annotation) {
        return Arguments.of(label, fixture, true, fixture.packageName() + "." + carrierSimpleName, annotation, false);
    }

    /**
     * A rejected row that also asserts, through a {@link GeneratedModuleRecorder}, that the failed
     * compilation's generated module holds no registration for {@code Api}.
     */
    private static Arguments rejectedUnregisteredRow(
            String label, Fixture fixture, String carrierSimpleName, String annotation) {
        return Arguments.of(label, fixture, true, fixture.packageName() + "." + carrierSimpleName, annotation, true);
    }

    private static Arguments acceptedRow(String label, Fixture fixture) {
        return Arguments.of(label, fixture, false, null, null, false);
    }

    private static Stream<Arguments> allowListCases() {
        String base = "dev.vertique.test.t028.tp004.";
        String rolesImport = "import jakarta.annotation.security.RolesAllowed;";
        String conditionalImport = "import dev.vertique.codegen.ConditionalOnProperty;";

        String r01 = base + "rolesonapi";
        String r02 = base + "rolesonsuper";
        String r03 = base + "rolestransitive";
        String r04 = base + "pathonapi";
        String r05 = base + "singletononapi";
        String r06 = base + "applicationpathonapi";
        String r07 = base + "ownmarkeronsuper";
        String r08 = base + "apialone";
        String r09 = base + "apidocspublic";
        String r10 = base + "openapiinfo";
        String r11 = base + "deprecated";
        String r12 = base + "oneconditional";
        String r13 = base + "twoconditionals";
        String r14 = base + "deprecatedsuper";
        String r15 = base + "classretainedsuper";
        String r16 = base + "namedonapi";
        String r17 = base + "openapitagsdefault";
        String r18 = base + "memberannotations";

        return Stream.of(
                rejectedUnregisteredRow(
                        "@RolesAllowed(\"admin\") on Api",
                        unit(r01, apiFixture(r01, rolesImport, "@RolesAllowed(\"admin\")", "")),
                        "Api",
                        ROLES_ALLOWED_FQN),
                rejectedRow(
                        "@RolesAllowed(\"admin\") on a direct superinterface Secured",
                        unit(
                                r02,
                                interfaceFixture(r02, "Secured", rolesImport, "@RolesAllowed(\"admin\")", ""),
                                apiFixture(r02, "", "", " extends Secured")),
                        "Secured",
                        ROLES_ALLOWED_FQN),
                rejectedRow(
                        "@RolesAllowed(\"admin\") on a superinterface of Secured (transitive)",
                        unit(
                                r03,
                                interfaceFixture(r03, "Root", rolesImport, "@RolesAllowed(\"admin\")", ""),
                                interfaceFixture(r03, "Secured", "", "", " extends Root"),
                                apiFixture(r03, "", "", " extends Secured")),
                        "Root",
                        ROLES_ALLOWED_FQN),
                rejectedRow(
                        "@Path(\"/x\") on Api",
                        unit(r04, apiFixture(r04, "import jakarta.ws.rs.Path;", "@Path(\"/x\")", "")),
                        "Api",
                        PATH_FQN),
                rejectedRow(
                        "@jakarta.inject.Singleton on Api (a scope annotation)",
                        unit(r05, apiFixture(r05, "", "@jakarta.inject.Singleton", "")),
                        "Api",
                        SINGLETON_FQN),
                rejectedRow(
                        "@jakarta.inject.Named(\"x\") on Api (a qualifier annotation)",
                        unit(r16, apiFixture(r16, "", "@jakarta.inject.Named(\"x\")", "")),
                        "Api",
                        NAMED_FQN),
                rejectedRow(
                        "@ApplicationPath(\"/api\") on Api",
                        unit(
                                r06,
                                apiFixture(
                                        r06,
                                        "import jakarta.ws.rs.ApplicationPath;",
                                        "@ApplicationPath(\"/api\")",
                                        "")),
                        "Api",
                        APPLICATION_PATH_FQN),
                rejectedRow(
                        "a runtime-retained fixture annotation on a superinterface",
                        unit(
                                r07,
                                markerAnnotationFixture(r07, "Marker", "RUNTIME"),
                                interfaceFixture(r07, "Secured", "", "@Marker", ""),
                                apiFixture(r07, "", "", " extends Secured")),
                        "Secured",
                        r07 + ".Marker"),
                acceptedRow("Api alone", unit(r08, apiFixture(r08, "", "", ""))),
                acceptedRow(
                        "Api with @ApiDocs(access = PUBLIC)",
                        unit(r09, apiFixture(r09, API_DOCS_IMPORTS, "@ApiDocs(access = PUBLIC)", ""))),
                acceptedRow(
                        "Api with @OpenAPIDefinition(info = @Info(title = \"t\", version = \"1\"))",
                        unit(
                                r10,
                                apiFixture(
                                        r10,
                                        "import io.swagger.v3.oas.annotations.OpenAPIDefinition;\n"
                                                + "import io.swagger.v3.oas.annotations.info.Info;",
                                        "@OpenAPIDefinition(info = @Info(title = \"t\", version = \"1\"))",
                                        ""))),
                acceptedRow(
                        "Api with @OpenAPIDefinition(info = @Info(title = \"t\", version = \"1\"), tags = {})"
                                + " (an element at its default counts as unset)",
                        unit(
                                r17,
                                apiFixture(
                                        r17,
                                        "import io.swagger.v3.oas.annotations.OpenAPIDefinition;\n"
                                                + "import io.swagger.v3.oas.annotations.info.Info;",
                                        "@OpenAPIDefinition(info = @Info(title = \"t\", version = \"1\"), tags = {})",
                                        ""))),
                acceptedRow("Api with @Deprecated", unit(r11, apiFixture(r11, "", "@Deprecated", ""))),
                acceptedRow(
                        "Api with one @ConditionalOnProperty",
                        unit(
                                r12,
                                apiFixture(
                                        r12, conditionalImport, "@ConditionalOnProperty(name = \"a.enabled\")", ""))),
                acceptedRow(
                        "Api with two @ConditionalOnProperty",
                        unit(
                                r13,
                                apiFixture(
                                        r13,
                                        conditionalImport,
                                        "@ConditionalOnProperty(name = \"a.enabled\")\n"
                                                + "@ConditionalOnProperty(name = \"b.enabled\")",
                                        ""))),
                acceptedRow(
                        "a superinterface carrying only @Deprecated",
                        unit(
                                r14,
                                interfaceFixture(r14, "Base", "", "@Deprecated", ""),
                                apiFixture(r14, "", "", " extends Base"))),
                acceptedRow(
                        "a superinterface carrying only a CLASS-retained annotation",
                        unit(
                                r15,
                                markerAnnotationFixture(r15, "ClassMarker", "CLASS"),
                                interfaceFixture(r15, "Base", "", "@ClassMarker", ""),
                                apiFixture(r15, "", "", " extends Base"))),
                acceptedRow(
                        "Api whose constant carries @Named and default method @RolesAllowed (members are not checked)",
                        unit(r18, apiWithAnnotatedMembersFixture(r18))));
    }

    @Test
    void scannerRecognizesTheRealRestApplicationTypeByName() {
        assertEquals(RestApplication.class.getName(), RestApplicationScanner.REST_APPLICATION_FQN);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allowListCases")
    @DisplayName("TP-004 — a disallowed runtime annotation on a declaration or superinterface fails compilation")
    void disallowedAnnotationsFailOnDeclarationOrSuperinterface(
            String label,
            Fixture fixture,
            boolean rejected,
            String carrier,
            String annotation,
            boolean assertUnregistered) {
        JavaFileObject[] sources = fixture.sources().toArray(JavaFileObject[]::new);
        GeneratedModuleRecorder recorder =
                new GeneratedModuleRecorder(fixture.packageName() + "." + MODULE_SIMPLE_NAME);
        var result = assertUnregistered
                ? ProcessorTestHarness.run(List.<Processor>of(new JaxRsPipelineProcessor(), recorder), sources)
                : ProcessorTestHarness.run(new JaxRsPipelineProcessor(), sources);
        logDiagnostics("TP-004 " + label, result);
        if (rejected) {
            assertErrorContainingAll(result, fixture.apiBinaryName(), carrier, annotation);
        } else {
            assertEmitsOnlyApiRegistration(result, fixture);
        }
        if (assertUnregistered) {
            assertNoApiRegistrationEmitted(recorder);
        }
    }

    // -----------------------------------------------------------------------------------------
    // TP-005 — declaration-only annotations fail on superinterfaces; @OpenAPIDefinition is info only
    // -----------------------------------------------------------------------------------------

    /** A unit where {@code Api} extends {@code Base}, which carries {@code annotation}. */
    private static Fixture baseCarrying(String packageName, String imports, String annotation) {
        return unit(
                packageName,
                interfaceFixture(packageName, "Base", imports, annotation, ""),
                apiFixture(packageName, "", "", " extends Base"));
    }

    private static Stream<Arguments> declarationOnlyCases() {
        String base = "dev.vertique.test.t028.tp005.";
        String r1 = base + "apidocs";
        String r2 = base + "openapi";
        String r3 = base + "restapplication";
        String r4 = base + "conditional";
        String r5 = base + "noautowire";
        String r6 = base + "servers";
        String r7 = base + "conditionalcontainer";
        return Stream.of(
                Arguments.of(
                        "@ApiDocs(access = PUBLIC) on Base",
                        baseCarrying(r1, API_DOCS_IMPORTS, "@ApiDocs(access = PUBLIC)"),
                        r1 + ".Base",
                        API_DOCS_FQN,
                        List.of()),
                Arguments.of(
                        "@OpenAPIDefinition(info = @Info(title = \"t\", version = \"1\")) on Base",
                        baseCarrying(
                                r2,
                                "import io.swagger.v3.oas.annotations.OpenAPIDefinition;\n"
                                        + "import io.swagger.v3.oas.annotations.info.Info;",
                                "@OpenAPIDefinition(info = @Info(title = \"t\", version = \"1\"))"),
                        r2 + ".Base",
                        OPEN_API_DEFINITION_FQN,
                        List.of()),
                Arguments.of(
                        "@RestApplication(name = \"base\", path = \"/base\", resources = PathResource.class) on Base",
                        baseCarrying(
                                r3,
                                "import dev.vertique.rest.core.application.RestApplication;",
                                "@RestApplication(name = \"base\", path = \"/base\", resources = PathResource.class)"),
                        r3 + ".Base",
                        REST_APPLICATION_FQN,
                        List.of()),
                Arguments.of(
                        "@ConditionalOnProperty(name = \"x\") on Base",
                        baseCarrying(
                                r4,
                                "import dev.vertique.codegen.ConditionalOnProperty;",
                                "@ConditionalOnProperty(name = \"x\")"),
                        r4 + ".Base",
                        CONDITIONAL_ON_PROPERTY_FQN,
                        List.of()),
                Arguments.of(
                        "two @ConditionalOnProperty on Base (the repeatable container @ConditionalOnProperties)",
                        baseCarrying(
                                r7,
                                "import dev.vertique.codegen.ConditionalOnProperty;",
                                "@ConditionalOnProperty(name = \"a\")\n@ConditionalOnProperty(name = \"b\")"),
                        r7 + ".Base",
                        CONDITIONAL_ON_PROPERTIES_FQN,
                        List.of()),
                Arguments.of(
                        "@NoAutoWire on Base",
                        baseCarrying(r5, "import dev.vertique.codegen.NoAutoWire;", "@NoAutoWire"),
                        r5 + ".Base",
                        NO_AUTO_WIRE_FQN,
                        List.of()),
                Arguments.of(
                        "servers set on Api's @OpenAPIDefinition",
                        unit(
                                r6,
                                apiFixture(
                                        r6,
                                        "import io.swagger.v3.oas.annotations.OpenAPIDefinition;\n"
                                                + "import io.swagger.v3.oas.annotations.info.Info;\n"
                                                + "import io.swagger.v3.oas.annotations.servers.Server;",
                                        "@OpenAPIDefinition(info = @Info(title = \"t\", version = \"1\"),"
                                                + " servers = @Server(url = \"/\"))",
                                        "")),
                        r6 + ".Api",
                        OPEN_API_DEFINITION_FQN,
                        List.of(INFO_ONLY_PHRASE)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("declarationOnlyCases")
    @DisplayName("TP-005 — declaration-only annotations fail on superinterfaces; @OpenAPIDefinition is info only")
    void declarationOnlyAnnotationsFailOnSuperinterfaces(
            String label, Fixture fixture, String carrier, String annotation, List<String> phrases) {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), fixture.sources().toArray(JavaFileObject[]::new));
        logDiagnostics("TP-005 " + label, result);
        String[] expected = Stream.concat(Stream.of(fixture.apiBinaryName(), carrier, annotation), phrases.stream())
                .toArray(String[]::new);
        assertErrorContainingAll(result, expected);
    }

    // -----------------------------------------------------------------------------------------
    // TP-007 — opted-out declarations are not checked; autoWire=false still checks them
    // -----------------------------------------------------------------------------------------

    private static JavaFileObject offFixture(String packageName, boolean noAutoWire) {
        return SourceFiles.inline(packageName + ".Off", """
                package %s;

                import static dev.vertique.rest.openapi.docs.ApiDocs.Access.PROTECTED;

                import dev.vertique.codegen.NoAutoWire;
                import dev.vertique.rest.core.application.RestApplication;
                import dev.vertique.rest.openapi.docs.ApiDocs;
                import jakarta.annotation.security.RolesAllowed;

                %s
                @RolesAllowed("admin")
                @ApiDocs(access = PROTECTED)
                @RestApplication(name = "off", path = "/off", resources = PathResource.class)
                interface Off {}
                """.formatted(packageName, noAutoWire ? "@NoAutoWire" : ""));
    }

    @TestFactory
    @DisplayName("TP-007 — opted-out declarations are not checked; autoWire=false still checks them")
    Stream<DynamicTest> optedOutDeclarationsSkipChecksUnlikeAutoWireFalse() {
        return Stream.of(
                DynamicTest.dynamicTest(
                        "@NoAutoWire declaration compiles with only the opt-out warning", this::tp007NoAutoWire),
                DynamicTest.dynamicTest(
                        "autoWire=false declaration fails the allow list and the @ApiDocs check",
                        this::tp007AutoWireFalse));
    }

    private void tp007NoAutoWire() {
        String pkg = "dev.vertique.test.t028.tp007.noautowire";
        String off = pkg + ".Off";
        var result =
                ProcessorTestHarness.run(new JaxRsPipelineProcessor(), pathResourceFixture(pkg), offFixture(pkg, true));
        logDiagnostics("TP-007 @NoAutoWire", result);
        result.assertSuccess();
        List<String> naming = result.compilation().diagnostics().stream()
                .filter(d -> {
                    String message = d.getMessage(null);
                    return message != null && message.contains(off);
                })
                .map(d -> "[" + d.getKind() + "] " + d.getMessage(null))
                .toList();
        assertEquals(
                1, naming.size(), () -> "Expected exactly one diagnostic naming " + off + diagnosticsSummary(result));
        String only = naming.get(0);
        assertTrue(
                (only.startsWith("[WARNING] ") || only.startsWith("[MANDATORY_WARNING] "))
                        && only.contains("is annotated @NoAutoWire")
                        && only.contains("not registered"),
                () -> "Expected the one diagnostic naming " + off + " to be T022's opt-out warning, got: " + only);
    }

    private void tp007AutoWireFalse() {
        String pkg = "dev.vertique.test.t028.tp007.autowirefalse";
        String off = pkg + ".Off";
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                Map.of("vertique.codegen.autoWire", "false"),
                pathResourceFixture(pkg),
                offFixture(pkg, false));
        logDiagnostics("TP-007 autoWire=false", result);
        assertErrorContainingAll(result, off, ROLES_ALLOWED_FQN);
        assertErrorContainingAll(result, off, "securityScheme");
    }

    // -----------------------------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------------------------

    /** Asserts that compilation failed with at least one ERROR whose message contains every part. */
    private static void assertErrorContainingAll(ProcessorTestHarness.Result result, String... parts) {
        result.assertFailed();
        boolean found = result.compilation().diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(null))
                .filter(Objects::nonNull)
                .anyMatch(message -> Arrays.stream(parts).allMatch(message::contains));
        assertTrue(
                found,
                () -> "Expected an ERROR containing all of " + Arrays.toString(parts) + diagnosticsSummary(result));
    }

    /**
     * Asserts that the unit compiled and that its generated module declares exactly one method
     * returning {@code GeneratedRestApplicationRegistration}, {@code apiRegistration}, whose
     * registration's declaring type is {@code Api}.
     */
    private static void assertEmitsOnlyApiRegistration(ProcessorTestHarness.Result result, Fixture fixture) {
        result.assertSuccess();
        Class<?> module = result.loadGeneratedClass(fixture.packageName() + "." + MODULE_SIMPLE_NAME);
        Class<?> apiType = result.loadGeneratedClass(fixture.apiBinaryName());
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

    /**
     * Asserts that the failed compilation's generated module holds no registration for the rejected
     * {@code Api}: no {@code apiRegistration} method and no method returning
     * {@code GeneratedRestApplicationRegistration}.
     *
     * <p>The members are read through {@link GeneratedModuleRecorder}, because the harness cannot
     * return a failed compilation's generated files and javac attributes no source once an error
     * exists, so a registration written into the module raises no diagnostic of its own. The
     * assertions first check that the recorder ran its final round, found the module, and saw its
     * {@code pathResourceBinding} method, which the pipeline writes for the DI-eligible
     * {@code PathResource} whether or not {@code Api} is rejected; that keeps the registration
     * checks from passing vacuously on an absent or memberless module.
     */
    private static void assertNoApiRegistrationEmitted(GeneratedModuleRecorder recorder) {
        assertTrue(recorder.finalRoundSeen(), () -> "Expected the recorder to run the final processing round");
        assertTrue(
                recorder.moduleFound(),
                () -> "Expected the recorder to find the generated module in the final processing round"
                        + recorder.describe());
        assertTrue(
                recorder.methodNames().contains("pathResourceBinding"),
                () -> "Expected the module's members to be visible to the recorder (its 'pathResourceBinding'"
                        + " method)" + recorder.describe());
        assertFalse(
                recorder.methodNames().contains("apiRegistration"),
                () -> "Expected no 'apiRegistration' method for the rejected Api" + recorder.describe());
        assertEquals(
                List.of(),
                recorder.registrationMethodNames(),
                () -> "Expected no GeneratedRestApplicationRegistration method for the rejected Api"
                        + recorder.describe());
    }

    /**
     * A processor run beside {@link JaxRsPipelineProcessor} that records, in the final
     * ({@code processingOver()}) round, the name and return type of each method the generated module
     * {@code moduleFqn} declares, following {@link RestApplicationDeclarationTest}'s recorder.
     *
     * <p>It supports {@code "*"} only so javac calls it in every round, as it does the pipeline, and
     * it always returns {@code false}, so it claims no annotation and changes nothing the pipeline
     * sees or writes. The pipeline writes the module in the first round, and javac still enters that
     * generated source's types for the later rounds after a processor has reported an error, so the
     * module's members are readable here in a failed compilation;
     * {@link #assertNoApiRegistrationEmitted} re-checks that visibility.
     */
    private static final class GeneratedModuleRecorder extends AbstractProcessor {

        private record RecordedMethod(String name, String returnType) {}

        private final String moduleFqn;
        private final List<RecordedMethod> methods = new ArrayList<>();
        private boolean finalRoundSeen;
        private boolean moduleFound;

        GeneratedModuleRecorder(String moduleFqn) {
            this.moduleFqn = moduleFqn;
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (!roundEnv.processingOver()) {
                return false;
            }
            finalRoundSeen = true;
            TypeElement module = processingEnv.getElementUtils().getTypeElement(moduleFqn);
            if (module != null) {
                moduleFound = true;
                for (ExecutableElement method : ElementFilter.methodsIn(module.getEnclosedElements())) {
                    methods.add(new RecordedMethod(
                            method.getSimpleName().toString(),
                            method.getReturnType().toString()));
                }
            }
            return false;
        }

        boolean finalRoundSeen() {
            return finalRoundSeen;
        }

        boolean moduleFound() {
            return moduleFound;
        }

        /** The names of the module's methods; empty when the module was not found. */
        List<String> methodNames() {
            return methods.stream().map(RecordedMethod::name).toList();
        }

        /** The names of the module's methods returning {@code GeneratedRestApplicationRegistration}. */
        List<String> registrationMethodNames() {
            return methods.stream()
                    .filter(m ->
                            GeneratedRestApplicationRegistration.class.getName().equals(m.returnType()))
                    .map(RecordedMethod::name)
                    .toList();
        }

        String describe() {
            StringBuilder sb = new StringBuilder("\nRecorder (final round seen: ")
                    .append(finalRoundSeen)
                    .append(", module ")
                    .append(moduleFqn)
                    .append(moduleFound ? " found" : " not found")
                    .append("):\n");
            methods.forEach(m -> sb.append("  ")
                    .append(m.name())
                    .append(" -> ")
                    .append(m.returnType())
                    .append('\n'));
            return sb.toString();
        }
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
