// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * APT compilation tests for the compile-time half of the {@code Application} annotation allow
 * list: every {@code RUNTIME}-retained type-declaration annotation anywhere in an
 * application's scope (the concrete application, every superclass strictly below {@code
 * jakarta.ws.rs.core.Application}, and every interface any of them implements, transitively
 * including superinterfaces) must be one of a small allow list, or compilation fails naming the
 * application, the declaring type, and the annotation.
 *
 * <p>Each test compiles one or more small {@code jakarta.ws.rs.core.Application} fixtures through
 * {@link JaxRsPipelineProcessor} via {@link ProcessorTestHarness} and asserts the compiler
 * diagnostics, mirroring {@link ApplicationPathGrammarTest}'s and {@link
 * JaxRsApplicationRegistrationEmitterTest}'s fixture and assertion style.
 */
class ApplicationAnnotationAllowListTest {

    private static final String PKG = "dev.vertique.test.allowlist";

    private static String pkg(String suffix) {
        return PKG + "." + suffix;
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    // -----------------------------------------------------------------------------------------
    // Shared hierarchy fixture builder — places one annotation at one level of a fixed scope
    // shape: Storefront (A) extends GuardedBase (the superclass) implements GuardedContract
    // (the interface), and GuardedContract extends RootContract (the superinterface).
    // -----------------------------------------------------------------------------------------

    private enum Level {
        CONCRETE,
        SUPERCLASS,
        INTERFACE,
        SUPERINTERFACE
    }

    /** One annotation to place at a hierarchy level: its FQN (import) and its usage text. */
    private record LevelAnnotation(String importLine, String annotationLine) {
        static final LevelAnnotation NONE = new LevelAnnotation("", "");
    }

    private static String importLine(LevelAnnotation a) {
        return a.importLine().isEmpty() ? "" : "import " + a.importLine() + ";\n";
    }

    private static String annotationLine(LevelAnnotation a) {
        return a.annotationLine().isEmpty() ? "" : a.annotationLine() + "\n";
    }

    /**
     * Builds the four-file scope fixture ({@code RootContract}, {@code GuardedContract},
     * {@code GuardedBase}, {@code Storefront}), placing {@code extra} at exactly one {@code level}
     * and leaving every other level plain.
     */
    private static List<JavaFileObject> hierarchyFixture(
            String pkg,
            Level level,
            LevelAnnotation extra,
            boolean applicationPathOnConcrete,
            boolean noAutoWireOnConcrete) {
        LevelAnnotation onSuperinterface = level == Level.SUPERINTERFACE ? extra : LevelAnnotation.NONE;
        LevelAnnotation onInterface = level == Level.INTERFACE ? extra : LevelAnnotation.NONE;
        LevelAnnotation onSuperclass = level == Level.SUPERCLASS ? extra : LevelAnnotation.NONE;
        LevelAnnotation onConcrete = level == Level.CONCRETE ? extra : LevelAnnotation.NONE;

        JavaFileObject rootContract = SourceFiles.inline(
                pkg + ".RootContract",
                "package " + pkg + ";\n\n"
                        + importLine(onSuperinterface)
                        + "\n"
                        + annotationLine(onSuperinterface)
                        + "public interface RootContract {}\n");

        JavaFileObject guardedContract = SourceFiles.inline(
                pkg + ".GuardedContract",
                "package " + pkg + ";\n\n"
                        + importLine(onInterface)
                        + "\n"
                        + annotationLine(onInterface)
                        + "public interface GuardedContract extends RootContract {}\n");

        JavaFileObject guardedBase = SourceFiles.inline(
                pkg + ".GuardedBase",
                "package " + pkg + ";\n\n"
                        + "import jakarta.ws.rs.core.Application;\n"
                        + importLine(onSuperclass)
                        + "\n"
                        + annotationLine(onSuperclass)
                        + "public abstract class GuardedBase extends Application {}\n");

        StringBuilder storefront = new StringBuilder();
        storefront.append("package ").append(pkg).append(";\n\n");
        if (applicationPathOnConcrete) {
            storefront.append("import jakarta.ws.rs.ApplicationPath;\n");
        }
        storefront.append(importLine(onConcrete));
        if (noAutoWireOnConcrete) {
            storefront.append("import dev.vertique.codegen.NoAutoWire;\n");
        }
        storefront.append("\n");
        if (applicationPathOnConcrete) {
            storefront.append("@ApplicationPath(\"/api/storefront\")\n");
        }
        storefront.append(annotationLine(onConcrete));
        if (noAutoWireOnConcrete) {
            storefront.append("@NoAutoWire\n");
        }
        storefront
                .append("public class Storefront extends GuardedBase implements GuardedContract {\n")
                .append("    public Storefront() {}\n")
                .append("}\n");

        return new ArrayList<>(List.of(
                rootContract,
                guardedContract,
                guardedBase,
                SourceFiles.inline(pkg + ".Storefront", storefront.toString())));
    }

    /**
     * TP-001 row 8's fixture-declared, {@code RUNTIME}-retained, {@code TYPE}-target stand-in for
     * a sibling framework module's audit annotation. It carries no resource semantics of its own;
     * the allow list rejects it anyway, because it is not on the list.
     */
    private static JavaFileObject auditStandInFixture(String pkg) {
        return SourceFiles.inline(
                pkg + ".AuditStandIn",
                "package " + pkg + ";\n\n"
                        + "import java.lang.annotation.ElementType;\n"
                        + "import java.lang.annotation.Retention;\n"
                        + "import java.lang.annotation.RetentionPolicy;\n"
                        + "import java.lang.annotation.Target;\n\n"
                        + "/**\n"
                        + " * Stands in for a sibling framework module's audit annotation (TP-001 row 8): a\n"
                        + " * RUNTIME-retained, TYPE-target annotation the allow list must reject like any other\n"
                        + " * annotation that is not on the list.\n"
                        + " */\n"
                        + "@Target(ElementType.TYPE)\n"
                        + "@Retention(RetentionPolicy.RUNTIME)\n"
                        + "public @interface AuditStandIn {}\n");
    }

    /**
     * TP-001 row 9's fixture: {@code PathContract} carries {@code @ApplicationPath}, and {@code
     * Storefront} implements it directly with none in its own superclass chain (the "only on an
     * interface" shape).
     */
    private static List<JavaFileObject> applicationPathOnlyOnInterfaceFixture(String pkg) {
        JavaFileObject pathContract = SourceFiles.inline(
                pkg + ".PathContract",
                "package " + pkg + ";\n\n"
                        + "import jakarta.ws.rs.ApplicationPath;\n\n"
                        + "@ApplicationPath(\"/api/path-contract\")\n"
                        + "public interface PathContract {}\n");
        JavaFileObject storefront = SourceFiles.inline(
                pkg + ".Storefront",
                "package " + pkg + ";\n\n"
                        + "import jakarta.ws.rs.core.Application;\n\n"
                        + "public class Storefront extends Application implements PathContract {\n"
                        + "    public Storefront() {}\n"
                        + "}\n");
        return List.of(pathContract, storefront);
    }

    /**
     * A row's fixture in which {@code extra} is placed on {@code GuardedContract}, and {@code
     * GuardedContract} is implemented only by {@code GuardedBase} (the abstract superclass),
     * never directly by {@code Storefront} itself. Unlike {@link #hierarchyFixture}, where {@code
     * Storefront} always implements the interface directly, this shape proves that the scope
     * discovers an interface through the superclass chain even when the concrete application's
     * own {@code implements} clause is empty.
     */
    private static List<JavaFileObject> interfaceOnlyOnAbstractSuperclassFixture(String pkg, LevelAnnotation extra) {
        JavaFileObject guardedContract = SourceFiles.inline(
                pkg + ".GuardedContract",
                "package " + pkg + ";\n\n"
                        + importLine(extra)
                        + "\n"
                        + annotationLine(extra)
                        + "public interface GuardedContract {}\n");
        JavaFileObject guardedBase = SourceFiles.inline(
                pkg + ".GuardedBase",
                "package " + pkg + ";\n\n"
                        + "import jakarta.ws.rs.core.Application;\n\n"
                        + "public abstract class GuardedBase extends Application implements GuardedContract {}\n");
        JavaFileObject storefront = SourceFiles.inline(
                pkg + ".Storefront",
                "package " + pkg + ";\n\n"
                        + "import jakarta.ws.rs.ApplicationPath;\n\n"
                        + "@ApplicationPath(\"/api/storefront\")\n"
                        + "public class Storefront extends GuardedBase {\n"
                        + "    public Storefront() {}\n"
                        + "}\n");
        return List.of(guardedContract, guardedBase, storefront);
    }

    // -----------------------------------------------------------------------------------------
    // TP-001 — resource-semantics annotations anywhere in the hierarchy fail unless exempt
    // -----------------------------------------------------------------------------------------

    /**
     * One TP-001 row. {@code sourceRetainedTemplate} selects the source-retained-annotation
     * message template (a source-retained annotation honored only on {@code A} itself) over the
     * ordinary allow-list-violation template. {@code expectSecondError} is set only for row 9,
     * where the required-{@code @ApplicationPath} error also fires. {@code pinExactMessage}
     * asserts the single ERROR equals the message template verbatim (rows 1 and 10); every other
     * row asserts the template as a substring. {@code exemptControl} short-circuits to the
     * {@code @NoAutoWire} success/warning assertion.
     */
    private record Tp001Case(
            String name,
            List<JavaFileObject> sources,
            String applicationFqn,
            String annotationFqn,
            String declaringTypeFqn,
            boolean sourceRetainedTemplate,
            boolean expectSecondError,
            boolean pinExactMessage,
            boolean exemptControl) {}

    private static Stream<Arguments> tp001Cases() {
        LevelAnnotation rolesAllowed =
                new LevelAnnotation("jakarta.annotation.security.RolesAllowed", "@RolesAllowed(\"admin\")");
        LevelAnnotation permitAll = new LevelAnnotation("jakarta.annotation.security.PermitAll", "@PermitAll");
        LevelAnnotation securityRequirement = new LevelAnnotation(
                "io.swagger.v3.oas.annotations.security.SecurityRequirement",
                "@SecurityRequirement(name = \"api-key\")");
        LevelAnnotation jsonProfile =
                new LevelAnnotation("dev.vertique.core.json.JsonProfile", "@JsonProfile(\"default\")");
        LevelAnnotation auditStandIn = new LevelAnnotation("", "@AuditStandIn");
        LevelAnnotation conditionalOnProperty = new LevelAnnotation(
                "dev.vertique.codegen.ConditionalOnProperty",
                "@ConditionalOnProperty(name = \"tp001.storefront.active\")");
        LevelAnnotation repeatedConditionalOnProperty = new LevelAnnotation(
                "dev.vertique.codegen.ConditionalOnProperty",
                "@ConditionalOnProperty(name = \"tp001.storefront.a\")\n"
                        + "@ConditionalOnProperty(name = \"tp001.storefront.b\")");
        LevelAnnotation noAutoWire = new LevelAnnotation("dev.vertique.codegen.NoAutoWire", "@NoAutoWire");

        String pkg1 = pkg("rolesonapplication");
        String pkg2 = pkg("rolesonabstractsuperclass");
        String pkg3 = pkg("rolesoninterface");
        String pkg4 = pkg("rolesonsuperinterface");
        String pkg5 = pkg("permitall");
        String pkg6 = pkg("securityrequirement");
        String pkg7 = pkg("jsonprofile");
        String pkg8 = pkg("auditstandin");
        String pkg9 = pkg("applicationpathonlyoninterface");
        String pkg10 = pkg("conditionalonabstractsuperclass");
        String pkg11 = pkg("noautowireonabstractsuperclass");
        String pkg12 = pkg("exemptcontrol");
        String pkg13 = pkg("rolesoninterfaceonlyonabstractsuperclass");
        String pkg14 = pkg("repeatedconditionalonabstractsuperclass");

        List<JavaFileObject> row8Sources = hierarchyFixture(pkg8, Level.CONCRETE, auditStandIn, true, false);
        row8Sources.add(auditStandInFixture(pkg8));

        List<Tp001Case> cases = List.of(
                new Tp001Case(
                        "roles-on-application",
                        hierarchyFixture(pkg1, Level.CONCRETE, rolesAllowed, true, false),
                        pkg1 + ".Storefront",
                        rolesAllowed.importLine(),
                        pkg1 + ".Storefront",
                        false,
                        false,
                        true,
                        false),
                new Tp001Case(
                        "roles-on-abstract-superclass",
                        hierarchyFixture(pkg2, Level.SUPERCLASS, rolesAllowed, true, false),
                        pkg2 + ".Storefront",
                        rolesAllowed.importLine(),
                        pkg2 + ".GuardedBase",
                        false,
                        false,
                        false,
                        false),
                new Tp001Case(
                        "roles-on-interface",
                        hierarchyFixture(pkg3, Level.INTERFACE, rolesAllowed, true, false),
                        pkg3 + ".Storefront",
                        rolesAllowed.importLine(),
                        pkg3 + ".GuardedContract",
                        false,
                        false,
                        false,
                        false),
                new Tp001Case(
                        "roles-on-superinterface",
                        hierarchyFixture(pkg4, Level.SUPERINTERFACE, rolesAllowed, true, false),
                        pkg4 + ".Storefront",
                        rolesAllowed.importLine(),
                        pkg4 + ".RootContract",
                        false,
                        false,
                        false,
                        false),
                new Tp001Case(
                        "permitall",
                        hierarchyFixture(pkg5, Level.CONCRETE, permitAll, true, false),
                        pkg5 + ".Storefront",
                        permitAll.importLine(),
                        pkg5 + ".Storefront",
                        false,
                        false,
                        false,
                        false),
                new Tp001Case(
                        "security-requirement",
                        hierarchyFixture(pkg6, Level.CONCRETE, securityRequirement, true, false),
                        pkg6 + ".Storefront",
                        securityRequirement.importLine(),
                        pkg6 + ".Storefront",
                        false,
                        false,
                        false,
                        false),
                new Tp001Case(
                        "json-profile",
                        hierarchyFixture(pkg7, Level.CONCRETE, jsonProfile, true, false),
                        pkg7 + ".Storefront",
                        jsonProfile.importLine(),
                        pkg7 + ".Storefront",
                        false,
                        false,
                        false,
                        false),
                new Tp001Case(
                        "audit-stand-in",
                        row8Sources,
                        pkg8 + ".Storefront",
                        pkg8 + ".AuditStandIn",
                        pkg8 + ".Storefront",
                        false,
                        false,
                        false,
                        false),
                new Tp001Case(
                        "application-path-only-on-interface",
                        applicationPathOnlyOnInterfaceFixture(pkg9),
                        pkg9 + ".Storefront",
                        "jakarta.ws.rs.ApplicationPath",
                        pkg9 + ".PathContract",
                        false,
                        true,
                        false,
                        false),
                new Tp001Case(
                        "conditional-on-abstract-superclass",
                        hierarchyFixture(pkg10, Level.SUPERCLASS, conditionalOnProperty, true, false),
                        pkg10 + ".Storefront",
                        conditionalOnProperty.importLine(),
                        pkg10 + ".GuardedBase",
                        true,
                        false,
                        true,
                        false),
                new Tp001Case(
                        "no-auto-wire-on-abstract-superclass",
                        hierarchyFixture(pkg11, Level.SUPERCLASS, noAutoWire, true, false),
                        pkg11 + ".Storefront",
                        noAutoWire.importLine(),
                        pkg11 + ".GuardedBase",
                        true,
                        false,
                        false,
                        false),
                new Tp001Case(
                        "exempt-control",
                        hierarchyFixture(pkg12, Level.CONCRETE, rolesAllowed, true, true),
                        pkg12 + ".Storefront",
                        null,
                        null,
                        false,
                        false,
                        false,
                        true),
                new Tp001Case(
                        "roles-on-interface-only-on-abstract-superclass",
                        interfaceOnlyOnAbstractSuperclassFixture(pkg13, rolesAllowed),
                        pkg13 + ".Storefront",
                        rolesAllowed.importLine(),
                        pkg13 + ".GuardedContract",
                        false,
                        false,
                        false,
                        false),
                new Tp001Case(
                        "repeated-conditional-on-abstract-superclass",
                        hierarchyFixture(pkg14, Level.SUPERCLASS, repeatedConditionalOnProperty, true, false),
                        pkg14 + ".Storefront",
                        "dev.vertique.codegen.ConditionalOnProperties",
                        pkg14 + ".GuardedBase",
                        true,
                        false,
                        false,
                        false));
        return cases.stream().map(c -> Arguments.of(c.name(), c));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tp001Cases")
    @DisplayName("TP-001 — resource-semantics annotations anywhere in the hierarchy fail unless exempt")
    void rejectsResourceSemanticsAnywhereInHierarchy(String name, Tp001Case testCase) {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), testCase.sources().toArray(new JavaFileObject[0]));
        logDiagnostics("TP-001 " + name, result);

        if (testCase.exemptControl()) {
            result.assertSuccess();
            assertTrue(
                    result.compilation().errors().isEmpty(),
                    () -> "Expected no ERROR diagnostic for the @NoAutoWire exempt control."
                            + diagnosticsSummary(result));
            result.assertWarningMessage(simpleName(testCase.applicationFqn())
                    + " is annotated @NoAutoWire, so it is not registered or" + " validated");
            return;
        }

        assertAllowListError(result, testCase);
    }

    /**
     * Asserts the shape for a failing TP-001 row: exactly one ERROR (two for row 9), with the
     * message template — naming {@code A}, the annotation, and the declaring type in the SAME
     * diagnostic. Rows 1 and 10 pin the full message; every other row asserts it as a substring.
     */
    private static void assertAllowListError(ProcessorTestHarness.Result result, Tp001Case testCase) {
        result.assertFailed();
        List<Diagnostic<? extends JavaFileObject>> errors = result.compilation().errors();
        int expectedCount = testCase.expectSecondError() ? 2 : 1;
        assertEquals(
                expectedCount,
                errors.size(),
                () -> "Expected exactly " + expectedCount + " ERROR diagnostic(s) for '" + testCase.name() + "'."
                        + diagnosticsSummary(result));

        String expectedMessage = testCase.sourceRetainedTemplate()
                ? "Application %s carries @%s on supertype %s, which is honored only on the application class itself"
                        .formatted(testCase.applicationFqn(), testCase.annotationFqn(), testCase.declaringTypeFqn())
                : "Application %s carries @%s on %s, which is not allowed: application classes carry no resource semantics"
                        .formatted(testCase.applicationFqn(), testCase.annotationFqn(), testCase.declaringTypeFqn());

        if (testCase.pinExactMessage()) {
            assertEquals(
                    expectedMessage,
                    errors.get(0).getMessage(null),
                    () -> "Expected the single ERROR to equal the pinned RL-2 template for '" + testCase.name() + "'."
                            + diagnosticsSummary(result));
            return;
        }

        boolean found = errors.stream()
                .map(d -> d.getMessage(null))
                .filter(Objects::nonNull)
                .anyMatch(msg -> msg.contains(expectedMessage));
        assertTrue(
                found,
                () -> "Expected an ERROR diagnostic containing '" + expectedMessage + "' for '" + testCase.name() + "'."
                        + diagnosticsSummary(result));
    }

    // -----------------------------------------------------------------------------------------
    // TP-002 — allow-listed annotations compile; an OpenAPIDefinition element off its default
    // fails
    // -----------------------------------------------------------------------------------------

    /**
     * Builds the TP-002 accepted-shape fixture: {@code @ApplicationPath}, the given {@code
     * @ConditionalOnProperty} row(s), {@code @Singleton}, {@code @Named}, {@code @Deprecated}, and
     * {@code @OpenAPIDefinition(info = @Info(title = "t", version = "1"), <openApiDefinitionArgs>)}
     * on the concrete application class itself.
     */
    private static JavaFileObject tp002Application(
            String pkg, List<String> conditionalAnnotations, String openApiDefinitionArgs) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("import jakarta.inject.Named;\n");
        sb.append("import jakarta.inject.Singleton;\n");
        sb.append("import jakarta.ws.rs.ApplicationPath;\n");
        sb.append("import jakarta.ws.rs.core.Application;\n");
        if (!conditionalAnnotations.isEmpty()) {
            sb.append("import dev.vertique.codegen.ConditionalOnProperty;\n");
        }
        sb.append("import io.swagger.v3.oas.annotations.OpenAPIDefinition;\n");
        sb.append("import io.swagger.v3.oas.annotations.info.Info;\n");
        if (openApiDefinitionArgs.contains("Tag")) {
            sb.append("import io.swagger.v3.oas.annotations.tags.Tag;\n");
        }
        sb.append("\n");
        sb.append("@ApplicationPath(\"/api/tp002\")\n");
        for (String conditional : conditionalAnnotations) {
            sb.append(conditional).append("\n");
        }
        sb.append("@Singleton\n");
        sb.append("@Named\n");
        sb.append("@Deprecated\n");
        sb.append("@OpenAPIDefinition(info = @Info(title = \"t\", version = \"1\")")
                .append(openApiDefinitionArgs.isEmpty() ? "" : ", " + openApiDefinitionArgs)
                .append(")\n");
        sb.append("public class Storefront extends Application {\n");
        sb.append("    public Storefront() {}\n");
        sb.append("}\n");
        return SourceFiles.inline(pkg + ".Storefront", sb.toString());
    }

    /**
     * TP-002 row 5's member-annotation control: {@code @ApplicationPath} on the class, {@code
     * @RolesAllowed} on the overriding {@code getClasses()} method. Member annotations have no
     * effect on an application and are not checked.
     */
    private static JavaFileObject tp002MemberAnnotationControl(String pkg) {
        return SourceFiles.inline(
                pkg + ".Storefront",
                "package " + pkg + ";\n\n"
                        + "import jakarta.annotation.security.RolesAllowed;\n"
                        + "import jakarta.ws.rs.ApplicationPath;\n"
                        + "import jakarta.ws.rs.core.Application;\n"
                        + "import java.util.Set;\n\n"
                        + "@ApplicationPath(\"/api/tp002\")\n"
                        + "public class Storefront extends Application {\n"
                        + "    public Storefront() {}\n\n"
                        + "    @RolesAllowed(\"admin\")\n"
                        + "    @Override\n"
                        + "    public Set<Class<?>> getClasses() {\n"
                        + "        return Set.of();\n"
                        + "    }\n"
                        + "}\n");
    }

    /** One TP-002 row: a compiling fixture and whether it is expected to compile. */
    private record Tp002Case(String name, JavaFileObject source, boolean expectCompile, String applicationFqn) {}

    private static Stream<Arguments> tp002Cases() {
        String pkg1 = pkg("tp002.allowlistedsinglecondition");
        String pkg2 = pkg("tp002.allowlistedrepeatedcondition");
        String pkg3 = pkg("tp002.tagsemptyequalsdefault");
        String pkg4 = pkg("tp002.tagsnondefaultfails");
        String pkg5 = pkg("tp002.memberannotationcontrol");

        List<Tp002Case> cases = List.of(
                new Tp002Case(
                        "allow-listed-single-condition",
                        tp002Application(
                                pkg1, List.of("@ConditionalOnProperty(name = \"tp002.storefront.active\")"), ""),
                        true,
                        pkg1 + ".Storefront"),
                new Tp002Case(
                        "allow-listed-repeated-condition",
                        tp002Application(
                                pkg2,
                                List.of(
                                        "@ConditionalOnProperty(name = \"tp002.storefront.a\")",
                                        "@ConditionalOnProperty(name = \"tp002.storefront.b\")"),
                                ""),
                        true,
                        pkg2 + ".Storefront"),
                new Tp002Case(
                        "tags-empty-equals-default",
                        tp002Application(
                                pkg3,
                                List.of("@ConditionalOnProperty(name = \"tp002.storefront.active\")"),
                                "tags = {}"),
                        true,
                        pkg3 + ".Storefront"),
                new Tp002Case(
                        "tags-non-default-fails",
                        tp002Application(
                                pkg4,
                                List.of("@ConditionalOnProperty(name = \"tp002.storefront.active\")"),
                                "tags = @Tag(name = \"x\")"),
                        false,
                        pkg4 + ".Storefront"),
                new Tp002Case(
                        "member-annotation-control", tp002MemberAnnotationControl(pkg5), true, pkg5 + ".Storefront"));
        return cases.stream().map(c -> Arguments.of(c.name(), c));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tp002Cases")
    @DisplayName("TP-002 — allow-listed annotations compile; an OpenAPIDefinition element off its default fails")
    void acceptsAllowListedAnnotations(String name, Tp002Case testCase) {
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), testCase.source());
        logDiagnostics("TP-002 " + name, result);

        if (testCase.expectCompile()) {
            result.assertSuccess();
            assertRegistrationNote(result, simpleName(testCase.applicationFqn()), "/api/tp002");
            return;
        }

        result.assertFailed();
        List<Diagnostic<? extends JavaFileObject>> errors = result.compilation().errors();
        assertEquals(
                1,
                errors.size(),
                () -> "Expected exactly one ERROR diagnostic for '" + name + "'." + diagnosticsSummary(result));
        String message = errors.get(0).getMessage(null);
        assertTrue(
                message != null
                        && message.contains(testCase.applicationFqn())
                        && message.contains("io.swagger.v3.oas.annotations.OpenAPIDefinition")
                        && message.endsWith("; only its info element may be set"),
                () -> "Expected the single ERROR to name '" + testCase.applicationFqn()
                        + "', contain 'io.swagger.v3.oas.annotations.OpenAPIDefinition', and end with"
                        + " '; only its info element may be set'." + diagnosticsSummary(result));
    }

    /**
     * Asserts that {@code compilation().notes()} contains one NOTE diagnostic naming both {@code
     * applicationSimpleName} and {@code normalizedPath}, mirroring {@link
     * JaxRsApplicationRegistrationEmitterTest}'s {@code assertRegistrationNote} helper.
     */
    private static void assertRegistrationNote(
            ProcessorTestHarness.Result result, String applicationSimpleName, String normalizedPath) {
        boolean found = result.compilation().notes().stream()
                .map(d -> d.getMessage(null))
                .filter(Objects::nonNull)
                .anyMatch(msg -> msg.contains(applicationSimpleName) && msg.contains(normalizedPath));
        assertTrue(
                found,
                () -> "Expected a NOTE diagnostic naming '" + applicationSimpleName + "' and path '" + normalizedPath
                        + "' but none was found." + diagnosticsSummary(result));
    }

    // -----------------------------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------------------------

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
