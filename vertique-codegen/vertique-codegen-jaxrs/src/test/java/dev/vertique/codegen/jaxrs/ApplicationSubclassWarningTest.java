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
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentest4j.AssertionFailedError;

/**
 * APT compilation tests for T028's handling of concrete {@code jakarta.ws.rs.core.Application}
 * subclasses (FR-021, C-APPCHECK): a subclass is never registered; it compiles with exactly one
 * warning, its binary name followed by static text (DPR3-011), unless it carries
 * {@code @NoAutoWire}; and the generated {@code GeneratedJaxRsResourcesModule} holds no
 * {@code Application} registration, no construction glue, and resource bindings gated on
 * {@code Set<GeneratedRestApplicationRegistration>} (AC-021.4's compile-time half).
 *
 * <p>Each test compiles small fixtures through {@link JaxRsPipelineProcessor} via
 * {@link ProcessorTestHarness}, following {@link RestApplicationDeclarationTest}'s and
 * {@link RestApplicationRegistrationEmitterTest}'s fixture style. {@code PathResource} is a concrete
 * class with {@code @Path("/p")}, an {@code @Inject} constructor, and one {@code @GET} method;
 * {@code Api} is
 * {@code @RestApplication(name = "api", path = "/api", resources = PathResource.class) interface Api {}}.
 *
 * <p>A diagnostic mentions a subclass when its message contains the subclass's simple name. Every
 * fixture gives its subclass a simple name no other type or text of the unit contains, and the
 * binary name contains the simple name, so this is stricter than matching the binary name alone:
 * rest-024's diagnostics, which name a subclass by its simple name only, count too. The
 * single-warning checks count both {@link Diagnostic.Kind#WARNING} and
 * {@link Diagnostic.Kind#MANDATORY_WARNING}, and then require the one warning to be a
 * {@link Diagnostic.Kind#MANDATORY_WARNING}.
 */
class ApplicationSubclassWarningTest {

    /**
     * The warning's static text after the subclass's binary name (C-APPCHECK, DPR3-011); the same
     * for every subclass, whether or not the unit declares an application.
     */
    private static final String WARNING_STATIC_TEXT = ": @ApplicationPath and getClasses() have no effect;"
            + " with no @RestApplication declared in the component, its resources are served on the legacy"
            + " default mount at jaxrs.basePath; otherwise they are served only where a @RestApplication"
            + " lists them; declare an application with @RestApplication";

    private static final String MODULE_SIMPLE_NAME = "GeneratedJaxRsResourcesModule";

    private static final Map<String, String> AUTO_WIRE_DISABLED = Map.of("vertique.codegen.autoWire", "false");

    /** Every registration type name the generated module could spell, old or new. */
    private static final Pattern REGISTRATION_TOKEN = Pattern.compile("Generated\\w*Registration");

    /** The one warning expected for the subclass {@code binaryName}. */
    private static String expectedWarning(String binaryName) {
        return binaryName + WARNING_STATIC_TEXT;
    }

    // -----------------------------------------------------------------------------------------
    // Shared fixtures
    // -----------------------------------------------------------------------------------------

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

    private static JavaFileObject apiFixture(String packageName) {
        return SourceFiles.inline(packageName + ".Api", """
                package %s;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "api", path = "/api", resources = PathResource.class)
                interface Api {}
                """.formatted(packageName));
    }

    /**
     * A top-level {@code Application} subclass {@code simpleName} in {@code packageName}, with
     * {@code annotations} (one per line, imports included in {@code imports}) and
     * {@code constructor} as its only member.
     */
    private static JavaFileObject topLevelSubclass(
            String packageName, String simpleName, String imports, String annotations, String constructor) {
        return SourceFiles.inline(packageName + "." + simpleName, """
                package %s;

                import jakarta.ws.rs.core.Application;
                %s

                %s
                public class %s extends Application {
                    %s
                }
                """.formatted(
                        packageName, imports, annotations, simpleName, constructor));
    }

    // -----------------------------------------------------------------------------------------
    // TP-001 — an Application subclass compiles with exactly one warning naming it
    // -----------------------------------------------------------------------------------------

    /** One TP-001 unit: the subclass's binary name and the unit's sources. */
    private record SubclassFixture(String subclassBinaryName, List<JavaFileObject> sources) {}

    private static Stream<Arguments> applicationSubclassCases() {
        String base = "dev.vertique.test.t028.tp001.";
        String applicationPathImport = "import jakarta.ws.rs.ApplicationPath;";

        String topLevelPkg = base + "toplevel";
        String staticNestedPkg = base + "staticnested";
        String innerPkg = base + "inner";
        String injectPkg = base + "inject";
        String noCtorPkg = base + "noctor";
        String noPathPkg = base + "nopath";
        String invalidPathPkg = base + "invalidpath";
        String rolesPkg = base + "roles";
        String autoWireOffPkg = base + "autowireoff";
        String besideApiPkg = base + "besideapi";

        return Stream.of(
                Arguments.of(
                        "top-level with @ApplicationPath(\"/legacy\") and a no-argument constructor",
                        new SubclassFixture(
                                topLevelPkg + ".TopLevelApplication",
                                List.of(topLevelSubclass(
                                        topLevelPkg,
                                        "TopLevelApplication",
                                        applicationPathImport,
                                        "@ApplicationPath(\"/legacy\")",
                                        "public TopLevelApplication() {}"))),
                        Map.of()),
                Arguments.of(
                        "static nested",
                        new SubclassFixture(
                                staticNestedPkg + ".Holder$StaticNestedApplication",
                                List.of(SourceFiles.inline(
                                        staticNestedPkg + ".Holder", """
                                        package %s;

                                        import jakarta.ws.rs.ApplicationPath;
                                        import jakarta.ws.rs.core.Application;

                                        public class Holder {

                                            @ApplicationPath("/legacy")
                                            public static class StaticNestedApplication extends Application {
                                                public StaticNestedApplication() {}
                                            }
                                        }
                                        """.formatted(staticNestedPkg)))),
                        Map.of()),
                Arguments.of(
                        "non-static inner class",
                        new SubclassFixture(
                                innerPkg + ".Holder$InnerApplication",
                                List.of(SourceFiles.inline(innerPkg + ".Holder", """
                                        package %s;

                                        import jakarta.ws.rs.ApplicationPath;
                                        import jakarta.ws.rs.core.Application;

                                        public class Holder {

                                            @ApplicationPath("/legacy")
                                            public class InnerApplication extends Application {
                                                public InnerApplication() {}
                                            }
                                        }
                                        """.formatted(innerPkg)))),
                        Map.of()),
                Arguments.of(
                        "@Inject constructor",
                        new SubclassFixture(
                                injectPkg + ".InjectedApplication",
                                List.of(topLevelSubclass(
                                        injectPkg,
                                        "InjectedApplication",
                                        applicationPathImport + "\nimport jakarta.inject.Inject;",
                                        "@ApplicationPath(\"/legacy\")",
                                        "@Inject public InjectedApplication() {}"))),
                        Map.of()),
                Arguments.of(
                        "no usable constructor",
                        new SubclassFixture(
                                noCtorPkg + ".UnconstructibleApplication",
                                List.of(topLevelSubclass(
                                        noCtorPkg,
                                        "UnconstructibleApplication",
                                        applicationPathImport,
                                        "@ApplicationPath(\"/legacy\")",
                                        "public UnconstructibleApplication(String name) {}"))),
                        Map.of()),
                Arguments.of(
                        "no @ApplicationPath",
                        new SubclassFixture(
                                noPathPkg + ".UnpathedApplication",
                                List.of(topLevelSubclass(
                                        noPathPkg, "UnpathedApplication", "", "", "public UnpathedApplication() {}"))),
                        Map.of()),
                Arguments.of(
                        "@ApplicationPath(\"/api/*/v1\")",
                        new SubclassFixture(
                                invalidPathPkg + ".WildcardPathApplication",
                                List.of(topLevelSubclass(
                                        invalidPathPkg,
                                        "WildcardPathApplication",
                                        applicationPathImport,
                                        "@ApplicationPath(\"/api/*/v1\")",
                                        "public WildcardPathApplication() {}"))),
                        Map.of()),
                Arguments.of(
                        "carrying @RolesAllowed",
                        new SubclassFixture(
                                rolesPkg + ".GuardedApplication",
                                List.of(topLevelSubclass(
                                        rolesPkg,
                                        "GuardedApplication",
                                        applicationPathImport + "\nimport jakarta.annotation.security.RolesAllowed;",
                                        "@ApplicationPath(\"/legacy\")\n@RolesAllowed(\"admin\")",
                                        "public GuardedApplication() {}"))),
                        Map.of()),
                Arguments.of(
                        "compiled with -Avertique.codegen.autoWire=false",
                        new SubclassFixture(
                                autoWireOffPkg + ".UnwiredApplication",
                                List.of(topLevelSubclass(
                                        autoWireOffPkg,
                                        "UnwiredApplication",
                                        applicationPathImport,
                                        "@ApplicationPath(\"/legacy\")",
                                        "public UnwiredApplication() {}"))),
                        AUTO_WIRE_DISABLED),
                Arguments.of(
                        "beside Api and PathResource",
                        new SubclassFixture(
                                besideApiPkg + ".LegacyApplication",
                                List.of(
                                        pathResourceFixture(besideApiPkg),
                                        apiFixture(besideApiPkg),
                                        topLevelSubclass(
                                                besideApiPkg,
                                                "LegacyApplication",
                                                applicationPathImport,
                                                "@ApplicationPath(\"/legacy\")",
                                                "public LegacyApplication() {}"))),
                        Map.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("applicationSubclassCases")
    @DisplayName("TP-001 — an Application subclass compiles with exactly one warning naming it")
    void applicationSubclassCompilesWithOneWarning(String label, SubclassFixture fixture, Map<String, String> options) {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), options, fixture.sources().toArray(JavaFileObject[]::new));
        logDiagnostics("TP-001 " + label, result);
        assertOnlyTheSubclassWarning(result, fixture.subclassBinaryName());
    }

    // -----------------------------------------------------------------------------------------
    // TP-002 — @NoAutoWire on a subclass suppresses the warning
    // -----------------------------------------------------------------------------------------

    private static JavaFileObject legacyFixture(String packageName, boolean noAutoWire) {
        return SourceFiles.inline(packageName + ".Legacy", """
                package %s;

                import dev.vertique.codegen.NoAutoWire;
                import jakarta.ws.rs.ApplicationPath;
                import jakarta.ws.rs.core.Application;

                %s
                @ApplicationPath("/legacy")
                public class Legacy extends Application {
                    public Legacy() {}
                }
                """.formatted(packageName, noAutoWire ? "@NoAutoWire" : ""));
    }

    @TestFactory
    @DisplayName("TP-002 — @NoAutoWire on a subclass suppresses the warning")
    Stream<DynamicTest> noAutoWireSuppressesTheWarning() {
        return Stream.of(
                DynamicTest.dynamicTest(
                        "@NoAutoWire subclass compiles with no diagnostic naming Legacy", this::tp002NoAutoWire),
                DynamicTest.dynamicTest("control without @NoAutoWire gets the single warning", this::tp002Control));
    }

    private void tp002NoAutoWire() {
        String pkg = "dev.vertique.test.t028.tp002.noautowire";
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), legacyFixture(pkg, true));
        logDiagnostics("TP-002 @NoAutoWire", result);
        result.assertSuccess();
        assertEquals(
                List.of(),
                describeAll(diagnosticsMentioning(result, "Legacy")),
                () -> "Expected no diagnostic naming Legacy" + diagnosticsSummary(result));
    }

    private void tp002Control() {
        String pkg = "dev.vertique.test.t028.tp002.control";
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), legacyFixture(pkg, false));
        logDiagnostics("TP-002 control", result);
        assertOnlyTheSubclassWarning(result, pkg + ".Legacy");
    }

    // -----------------------------------------------------------------------------------------
    // TP-003 — no Application registration is emitted, and resource bindings take the native set
    // -----------------------------------------------------------------------------------------

    private static JavaFileObject injectedLegacyFixture(String packageName) {
        return SourceFiles.inline(packageName + ".Legacy", """
                package %s;

                import jakarta.inject.Inject;
                import jakarta.ws.rs.ApplicationPath;
                import jakarta.ws.rs.core.Application;

                @ApplicationPath("/legacy")
                public class Legacy extends Application {
                    @Inject
                    public Legacy() {}
                }
                """.formatted(packageName));
    }

    @TestFactory
    @DisplayName("TP-003 — no Application registration is emitted, and resource bindings take the native set")
    Stream<DynamicTest> noApplicationRegistrationIsEmitted() {
        return Stream.of(
                DynamicTest.dynamicTest(
                        "(a) PathResource and an @Inject-constructed Legacy: no registration, native-set binding",
                        this::tp003RowA),
                DynamicTest.dynamicTest("(b) only Legacy: no module is written", this::tp003RowB),
                DynamicTest.dynamicTest(
                        "(c) PathResource, Api, and Legacy: exactly Api's native registration", this::tp003RowC),
                DynamicTest.dynamicTest(
                        "(d) @ConditionalOnProperty resource: condition constant and guarded body kept, new set",
                        this::tp003RowD));
    }

    private void tp003RowA() {
        String pkg = "dev.vertique.test.t028.tp003.a";
        String moduleFqn = pkg + "." + MODULE_SIMPLE_NAME;
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), pathResourceFixture(pkg), injectedLegacyFixture(pkg));
        logDiagnostics("TP-003 (a)", result);
        result.assertSuccess();
        String source = sourceOf(result, moduleFqn);
        logGeneratedSource("TP-003 (a) module", source);

        // Structural absence (ruling E2): the only registration type the module spells is the native one.
        assertEquals(
                Set.of(GeneratedRestApplicationRegistration.class.getSimpleName()),
                registrationTokens(source),
                () -> "Expected every Generated*Registration token in the module to be"
                        + " GeneratedRestApplicationRegistration\n" + source);
        result.assertGeneratedSourceDoesNotContain(moduleFqn, "Provider<Legacy>");
        result.assertGeneratedSourceDoesNotContain(moduleFqn, "Legacy::new");

        Class<?> module = result.loadGeneratedClass(moduleFqn);
        assertEquals(
                List.of(),
                registrationMethodNames(module),
                () -> "Expected no registration method (the unit declares no @RestApplication)\n" + source);
        List<String> legacyMethods = Arrays.stream(module.getDeclaredMethods())
                .filter(m -> m.getName().startsWith("legacy") || mentionsInSignature(m, pkg + ".Legacy"))
                .map(Method::toGenericString)
                .toList();
        assertEquals(List.of(), legacyMethods, () -> "Expected no method for Legacy\n" + source);

        assertPresenceGatedBinding(module, "pathResourceBinding", pkg + ".PathResource");
        result.assertGeneratedSourceContains(
                moduleFqn, "Set<" + GeneratedRestApplicationRegistration.class.getSimpleName() + "> applications");
        result.assertGeneratedSourceContains(
                moduleFqn, "return applications.isEmpty() ? Set.of(provider.get()) : Set.of();");
    }

    private void tp003RowB() {
        String pkg = "dev.vertique.test.t028.tp003.b";
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), injectedLegacyFixture(pkg));
        logDiagnostics("TP-003 (b)", result);
        result.assertSuccess();
        List<String> modules = result.compilation().generatedSourceFiles().stream()
                .map(f -> f.toUri().getPath())
                .filter(path -> path.endsWith("/" + MODULE_SIMPLE_NAME + ".java"))
                .toList();
        assertEquals(List.of(), modules, () -> "Expected no generated module" + diagnosticsSummary(result));
    }

    private void tp003RowC() {
        String pkg = "dev.vertique.test.t028.tp003.c";
        String moduleFqn = pkg + "." + MODULE_SIMPLE_NAME;
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), pathResourceFixture(pkg), apiFixture(pkg), injectedLegacyFixture(pkg));
        logDiagnostics("TP-003 (c)", result);
        result.assertSuccess();
        String source = sourceOf(result, moduleFqn);
        logGeneratedSource("TP-003 (c) module", source);

        Class<?> module = result.loadGeneratedClass(moduleFqn);
        Class<?> apiType = result.loadGeneratedClass(pkg + ".Api");
        assertEquals(
                List.of("apiRegistration"),
                registrationMethodNames(module),
                () -> "Expected exactly one registration method, Api's\n" + source);
        Method apiRegistration = declaredMethod(module, "apiRegistration");
        assertEquals(
                GeneratedRestApplicationRegistration.class,
                apiRegistration.getReturnType(),
                "Expected apiRegistration to return the native registration type");
        GeneratedRestApplicationRegistration registration = invokeRegistration(apiRegistration);
        assertEquals(apiType, registration.declaringType(), "declaringType()");
        assertEquals("api", registration.name(), "name()");
    }

    private void tp003RowD() {
        String pkg = "dev.vertique.test.t028.tp003.d";
        String moduleFqn = pkg + "." + MODULE_SIMPLE_NAME;
        JavaFileObject adminResource = SourceFiles.inline(pkg + ".AdminResource", """
                package %s;

                import dev.vertique.codegen.ConditionalOnProperty;
                import jakarta.inject.Inject;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;

                @Path("/admin")
                @ConditionalOnProperty(name = "adminApi.enabled")
                public class AdminResource {
                    @Inject
                    public AdminResource() {}

                    @GET
                    public String get() { return ""; }
                }
                """.formatted(pkg));
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), adminResource);
        logDiagnostics("TP-003 (d)", result);
        result.assertSuccess();
        logGeneratedSource("TP-003 (d) module", sourceOf(result, moduleFqn));

        result.assertGeneratedSourceContains(moduleFqn, "PropertyCondition[] ADMIN_RESOURCE_BINDING_CONDITIONS");
        result.assertGeneratedSourceContains(moduleFqn, "new PropertyCondition(\"adminApi.enabled\", \"true\", false)");
        result.assertGeneratedSourceContains(
                moduleFqn,
                "return applications.isEmpty() && PropertyCondition.matchesAll(config,"
                        + " ADMIN_RESOURCE_BINDING_CONDITIONS) ? Set.of(provider.get()) : Set.of();");

        Class<?> module = result.loadGeneratedClass(moduleFqn);
        assertPresenceGatedBinding(module, "adminResourceBinding", pkg + ".AdminResource");
    }

    // -----------------------------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Asserts that the unit compiled, that exactly one diagnostic mentions the subclass, and that it
     * is a mandatory warning whose text equals {@link #expectedWarning(String)} for
     * {@code binaryName}: no error, registration note, or second warning of either kind names the
     * class.
     */
    private static void assertOnlyTheSubclassWarning(ProcessorTestHarness.Result result, String binaryName) {
        result.assertSuccess();
        List<Diagnostic<? extends JavaFileObject>> mentioning = diagnosticsMentioning(result, simpleNameOf(binaryName));
        List<Diagnostic<? extends JavaFileObject>> warningDiagnostics = mentioning.stream()
                .filter(ApplicationSubclassWarningTest::isWarning)
                .toList();
        List<String> warnings =
                warningDiagnostics.stream().map(d -> d.getMessage(null)).toList();
        assertEquals(
                List.of(expectedWarning(binaryName)),
                warnings,
                () -> "Expected exactly one warning for " + binaryName + " with the static text"
                        + diagnosticsSummary(result));
        assertEquals(
                Diagnostic.Kind.MANDATORY_WARNING,
                warningDiagnostics.get(0).getKind(),
                () -> "Expected the warning for " + binaryName + " to be a mandatory warning"
                        + diagnosticsSummary(result));
        List<String> others =
                describeAll(mentioning.stream().filter(d -> !isWarning(d)).toList());
        assertEquals(
                List.of(),
                others,
                () -> "Expected no other diagnostic naming " + binaryName + diagnosticsSummary(result));
    }

    private static boolean isWarning(Diagnostic<? extends JavaFileObject> diagnostic) {
        return diagnostic.getKind() == Diagnostic.Kind.WARNING
                || diagnostic.getKind() == Diagnostic.Kind.MANDATORY_WARNING;
    }

    private static List<Diagnostic<? extends JavaFileObject>> diagnosticsMentioning(
            ProcessorTestHarness.Result result, String simpleName) {
        return result.compilation().diagnostics().stream()
                .filter(d -> {
                    String message = d.getMessage(null);
                    return message != null && message.contains(simpleName);
                })
                .toList();
    }

    private static List<String> describeAll(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream()
                .map(d -> "[" + d.getKind() + "] " + d.getMessage(null))
                .toList();
    }

    /** The simple name of a binary name such as {@code p.Outer$Inner} or {@code p.Top}. */
    private static String simpleNameOf(String binaryName) {
        int cut = Math.max(binaryName.lastIndexOf('.'), binaryName.lastIndexOf('$'));
        return binaryName.substring(cut + 1);
    }

    /** The distinct {@code Generated…Registration} type names {@code source} spells. */
    private static Set<String> registrationTokens(String source) {
        Set<String> tokens = new TreeSet<>();
        Matcher matcher = REGISTRATION_TOKEN.matcher(source);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    /**
     * The sorted names of {@code module}'s declared methods returning any
     * {@code Generated…Registration} type, native or not.
     */
    private static List<String> registrationMethodNames(Class<?> module) {
        return Arrays.stream(module.getDeclaredMethods())
                .filter(m -> REGISTRATION_TOKEN
                        .matcher(m.getReturnType().getSimpleName())
                        .matches())
                .map(Method::getName)
                .sorted()
                .toList();
    }

    /** Whether {@code method}'s generic return or parameter types spell {@code typeName}. */
    private static boolean mentionsInSignature(Method method, String typeName) {
        return method.getGenericReturnType().getTypeName().contains(typeName)
                || Arrays.stream(method.getGenericParameterTypes())
                        .map(Type::getTypeName)
                        .anyMatch(name -> name.contains(typeName));
    }

    /**
     * Asserts that {@code methodName} keeps rest-024's presence-gated binding shape — a static
     * method returning {@code Set<Object>} and taking the {@code @VertxConfig JsonObject}, the
     * registration set, and the resource's {@code Provider} — with the registration set being
     * {@code Set<GeneratedRestApplicationRegistration>}.
     */
    private static void assertPresenceGatedBinding(Class<?> module, String methodName, String resourceFqn) {
        Method binding = declaredMethod(module, methodName);
        assertTrue(Modifier.isStatic(binding.getModifiers()), () -> methodName + " must be static");
        assertEquals(
                "java.util.Set<java.lang.Object>",
                binding.getGenericReturnType().getTypeName(),
                () -> methodName + "'s return type");
        assertEquals(
                List.of(
                        JsonObject.class.getName(),
                        "java.util.Set<" + GeneratedRestApplicationRegistration.class.getName() + ">",
                        "jakarta.inject.Provider<" + resourceFqn + ">"),
                Arrays.stream(binding.getGenericParameterTypes())
                        .map(Type::getTypeName)
                        .toList(),
                () -> methodName + "'s parameter types");
    }

    private static Method declaredMethod(Class<?> type, String methodName) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> methodName.equals(m.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionFailedError("Expected a method named '" + methodName + "' in "
                        + type.getName() + "; methods: " + Arrays.toString(type.getDeclaredMethods())));
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

    private static void logGeneratedSource(String label, String source) {
        System.out.println("=== " + label + " ===");
        System.out.println(source);
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
