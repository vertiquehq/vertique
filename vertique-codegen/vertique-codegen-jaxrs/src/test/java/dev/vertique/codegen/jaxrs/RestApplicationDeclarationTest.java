// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
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
 * APT compilation tests for T022's {@code @RestApplication} declaration checks: declaration form
 * (an interface only), the name grammar and reserved names, the application path grammar, listed
 * resources, exactly-one-of membership and discovery-alone, opting out, and the accessibility rule
 * over the declaring interface and every listed resource class.
 *
 * <p>Each test compiles one or more small fixtures through {@link JaxRsPipelineProcessor} via
 * {@link ProcessorTestHarness} and asserts the compiler diagnostics and/or the generated
 * {@code GeneratedJaxRsResourcesModule} source, mirroring {@link JaxRsApplicationRegistrationEmitterTest}'s
 * and {@link ApplicationPathGrammarTest}'s fixture and assertion style. TP-001 and TP-010 also run
 * a {@link GeneratedModuleRecorder} beside the pipeline, to read a failed compilation's generated
 * module, which the harness cannot return once compilation fails. {@code PathResource} and
 * {@code Api} fixtures follow the T022 test-proof preamble: a concrete class with {@code @Path("/p")},
 * an {@code @Inject} constructor, and one {@code @GET} method; and
 * {@code @RestApplication(name = "api", path = "/api", resources = PathResource.class) interface Api {}},
 * unless a row states otherwise.
 */
class RestApplicationDeclarationTest {

    /** {@code [a-z0-9][a-z0-9_-]{0,63}} — the application name grammar (FR-022). */
    private static final String GRAMMAR_PHRASE = "[a-z0-9][a-z0-9_-]{0,63}";

    private static final String RESERVED_PHRASE = "reserved";
    private static final String BELONGS_ON_INTERFACE_PHRASE = "belongs on an interface";

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

    // -----------------------------------------------------------------------------------------
    // TP-001 — @RestApplication on anything but an interface fails compilation naming the type
    // -----------------------------------------------------------------------------------------

    private record NonInterfaceCase(
            String label, JavaFileObject fixture, String moduleFqn, String typeFqn, boolean isInterfaceRow) {}

    private static Stream<Arguments> nonInterfaceCases() {
        String classPkg = "dev.vertique.test.tp001.classkind";
        String enumPkg = "dev.vertique.test.tp001.enumkind";
        String recordPkg = "dev.vertique.test.tp001.recordkind";
        String annotationPkg = "dev.vertique.test.tp001.annotationkind";
        String interfacePkg = "dev.vertique.test.tp001.interfacekind";

        List<NonInterfaceCase> cases = List.of(
                new NonInterfaceCase(
                        "class",
                        SourceFiles.inline(classPkg + ".ClassApi", """
                                package %s;

                                import dev.vertique.rest.core.application.RestApplication;

                                @RestApplication(name = "api", path = "/api", resources = PathResource.class)
                                public class ClassApi {}
                                """.formatted(classPkg)),
                        classPkg + ".GeneratedJaxRsResourcesModule",
                        classPkg + ".ClassApi",
                        false),
                new NonInterfaceCase(
                        "enum",
                        SourceFiles.inline(enumPkg + ".EnumApi", """
                                package %s;

                                import dev.vertique.rest.core.application.RestApplication;

                                @RestApplication(name = "api", path = "/api", resources = PathResource.class)
                                public enum EnumApi { INSTANCE }
                                """.formatted(enumPkg)),
                        enumPkg + ".GeneratedJaxRsResourcesModule",
                        enumPkg + ".EnumApi",
                        false),
                new NonInterfaceCase(
                        "record",
                        SourceFiles.inline(recordPkg + ".RecordApi", """
                                package %s;

                                import dev.vertique.rest.core.application.RestApplication;

                                @RestApplication(name = "api", path = "/api", resources = PathResource.class)
                                public record RecordApi() {}
                                """.formatted(recordPkg)),
                        recordPkg + ".GeneratedJaxRsResourcesModule",
                        recordPkg + ".RecordApi",
                        false),
                new NonInterfaceCase(
                        "annotation type",
                        SourceFiles.inline(annotationPkg + ".AnnotationApi", """
                                package %s;

                                import dev.vertique.rest.core.application.RestApplication;

                                @RestApplication(name = "api", path = "/api", resources = PathResource.class)
                                public @interface AnnotationApi {}
                                """.formatted(annotationPkg)),
                        annotationPkg + ".GeneratedJaxRsResourcesModule",
                        annotationPkg + ".AnnotationApi",
                        false),
                new NonInterfaceCase(
                        "interface",
                        apiFixture(interfacePkg),
                        interfacePkg + ".GeneratedJaxRsResourcesModule",
                        interfacePkg + ".Api",
                        true));
        return cases.stream().map(c -> Arguments.of(c.label(), c));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonInterfaceCases")
    @DisplayName("TP-001 — @RestApplication on anything but an interface fails compilation naming the type")
    void restApplicationOnANonInterfaceTypeFailsCompilation(String label, NonInterfaceCase testCase) {
        String packageName = testCase.typeFqn().substring(0, testCase.typeFqn().lastIndexOf('.'));
        GeneratedModuleRecorder recorder = new GeneratedModuleRecorder();
        var result = ProcessorTestHarness.run(
                List.<Processor>of(new JaxRsPipelineProcessor(), recorder),
                pathResourceFixture(packageName),
                testCase.fixture());
        logDiagnostics("TP-001 " + label, result);
        if (testCase.isInterfaceRow()) {
            result.assertSuccess();
            result.assertGeneratedSourceContains(testCase.moduleFqn(), "GeneratedRestApplicationRegistration");
            assertEquals(
                    List.of(testCase.moduleFqn() + "#apiRegistration"),
                    recorder.restRegistrationMethods(),
                    () -> "Expected the recorder to see exactly the interface's registration method"
                            + recorder.describe());
            return;
        }
        result.assertFailed();
        assertErrorNamesTypeAndContainsAll(result, testCase.typeFqn(), BELONGS_ON_INTERFACE_PHRASE);
        assertEquals(
                1,
                result.compilation().errors().size(),
                () -> "Expected exactly one ERROR, the named declaration-form error for '" + testCase.typeFqn() + "'"
                        + diagnosticsSummary(result));
        assertNoRegistrationEmitted(recorder, testCase.moduleFqn(), testCase.typeFqn());
    }

    // -----------------------------------------------------------------------------------------
    // TP-002 — application names follow [a-z0-9][a-z0-9_-]{0,63}
    // -----------------------------------------------------------------------------------------

    private enum NameOutcome {
        GRAMMAR_REJECTED,
        RESERVED_REJECTED,
        ACCEPTED,
        MISSING
    }

    private record NameCase(String label, String nameLiteral, NameOutcome outcome) {}

    private static JavaFileObject nameFixture(String packageName, String nameLiteralOrNull) {
        String nameAttr = nameLiteralOrNull == null ? "" : "name = \"" + nameLiteralOrNull + "\", ";
        return SourceFiles.inline(packageName + ".Api", """
                package %s;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(%spath = "/api", resources = PathResource.class)
                interface Api {}
                """.formatted(packageName, nameAttr));
    }

    private static Stream<Arguments> nameCases() {
        List<NameCase> raw = new ArrayList<>();
        for (String rejected : List.of("Api", "-api", "api.v1", "", "a".repeat(65), "api v1")) {
            raw.add(new NameCase("grammar-rejected '" + rejected + "'", rejected, NameOutcome.GRAMMAR_REJECTED));
        }
        for (String reserved : List.of("none", "null")) {
            raw.add(new NameCase("reserved '" + reserved + "'", reserved, NameOutcome.RESERVED_REJECTED));
        }
        for (String accepted : List.of("a", "0api", "api-v1_2", "nonempty", "nullable", "a".repeat(64))) {
            raw.add(new NameCase("accepted '" + accepted + "'", accepted, NameOutcome.ACCEPTED));
        }
        raw.add(new NameCase("missing name", null, NameOutcome.MISSING));

        List<Arguments> arguments = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            NameCase testCase = raw.get(i);
            arguments.add(Arguments.of(testCase.label(), testCase, "dev.vertique.test.tp002.n" + i));
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nameCases")
    @DisplayName("TP-002 — application names follow [a-z0-9][a-z0-9_-]{0,63}")
    void applicationNamesFollowTheGrammar(String label, NameCase testCase, String packageName) {
        String moduleFqn = packageName + ".GeneratedJaxRsResourcesModule";
        String apiFqn = packageName + ".Api";
        String apiFileSuffix = packageName.replace('.', '/') + "/Api.java";
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                pathResourceFixture(packageName),
                nameFixture(packageName, testCase.nameLiteral()));
        logDiagnostics("TP-002 " + label, result);
        switch (testCase.outcome()) {
            case GRAMMAR_REJECTED -> {
                result.assertFailed();
                assertErrorNamesTypeAndContainsAll(result, apiFqn, GRAMMAR_PHRASE);
            }
            case RESERVED_REJECTED -> {
                result.assertFailed();
                assertErrorNamesTypeAndContainsAll(result, apiFqn, RESERVED_PHRASE);
            }
            case ACCEPTED -> {
                result.assertSuccess();
                result.assertGeneratedSourceContains(moduleFqn, "\"" + testCase.nameLiteral() + "\"");
            }
            case MISSING -> {
                result.assertFailed();
                boolean namesNameElementInApiFile = result.compilation().errors().stream()
                        .filter(d -> d.getMessage(null) != null
                                && d.getMessage(null).contains("'name'")
                                && d.getMessage(null).contains("missing"))
                        .anyMatch(d ->
                                d.getSource() != null && d.getSource().getName().endsWith(apiFileSuffix));
                assertTrue(
                        namesNameElementInApiFile,
                        () -> "Expected javac's missing-element error naming 'name', reported in '" + apiFileSuffix
                                + "'" + diagnosticsSummaryWithSources(result));
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // TP-003 — two declarations with one name in one unit fail naming both
    // -----------------------------------------------------------------------------------------

    private static final String TP003_A_PKG = "dev.vertique.test.tp003.a";
    private static final String TP003_B_PKG = "dev.vertique.test.tp003.b";
    private static final String TP003_CONTROL_PKG = "dev.vertique.test.tp003.control";

    private static JavaFileObject tp003PublicApiFixture() {
        return SourceFiles.inline(TP003_A_PKG + ".PublicApi", """
                package %s;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "api", path = "/api/a", resources = PathResource.class)
                interface PublicApi {}
                """.formatted(TP003_A_PKG));
    }

    private static JavaFileObject tp003OtherApiFixture() {
        return SourceFiles.inline(TP003_B_PKG + ".OtherApi", """
                package %s;

                import dev.vertique.codegen.ConditionalOnProperty;
                import dev.vertique.rest.core.application.RestApplication;

                @ConditionalOnProperty(name = "tp003.other.enabled")
                @RestApplication(name = "api", path = "/api/b", resources = PathResource.class)
                interface OtherApi {}
                """.formatted(TP003_B_PKG));
    }

    @Test
    @DisplayName("TP-003 — two declarations with one name in one unit fail naming both")
    void duplicateNamesInOneUnitFailNamingBoth() {
        assertAll(
                "TP-003 cases",
                () -> tp003SingleUnitDuplicateFails(),
                () -> tp003SeparateCompilationsSucceed(),
                () -> tp003ControlUnitWithDistinctNamesSucceeds());
    }

    private void tp003SingleUnitDuplicateFails() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                pathResourceFixture(TP003_A_PKG),
                tp003PublicApiFixture(),
                pathResourceFixture(TP003_B_PKG),
                tp003OtherApiFixture());
        logDiagnostics("TP-003 single-unit duplicate", result);
        result.assertFailed();
        String publicApiFqn = TP003_A_PKG + ".PublicApi";
        String otherApiFqn = TP003_B_PKG + ".OtherApi";
        boolean found = result.compilation().errors().stream()
                .map(d -> d.getMessage(null))
                .filter(Objects::nonNull)
                .anyMatch(msg -> msg.contains(publicApiFqn) && msg.contains(otherApiFqn));
        assertTrue(
                found,
                () -> "Expected an ERROR naming both '" + publicApiFqn + "' and '" + otherApiFqn + "'"
                        + diagnosticsSummary(result));
    }

    private void tp003SeparateCompilationsSucceed() {
        var resultA = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), pathResourceFixture(TP003_A_PKG), tp003PublicApiFixture());
        logDiagnostics("TP-003 separate (a)", resultA);
        resultA.assertSuccess();
        resultA.assertGeneratedSourceContains(TP003_A_PKG + ".GeneratedJaxRsResourcesModule", "PublicApi.class");

        var resultB = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), pathResourceFixture(TP003_B_PKG), tp003OtherApiFixture());
        logDiagnostics("TP-003 separate (b)", resultB);
        resultB.assertSuccess();
        resultB.assertGeneratedSourceContains(TP003_B_PKG + ".GeneratedJaxRsResourcesModule", "OtherApi.class");
    }

    private void tp003ControlUnitWithDistinctNamesSucceeds() {
        JavaFileObject controlApi =
                SourceFiles.inline(TP003_CONTROL_PKG + ".ControlApi", """
                package %s;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "api", path = "/api", resources = PathResource.class)
                interface ControlApi {}
                """.formatted(TP003_CONTROL_PKG));
        JavaFileObject controlApi2 =
                SourceFiles.inline(TP003_CONTROL_PKG + ".ControlApi2", """
                package %s;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "api2", path = "/api2", resources = PathResource.class)
                interface ControlApi2 {}
                """.formatted(TP003_CONTROL_PKG));
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), pathResourceFixture(TP003_CONTROL_PKG), controlApi, controlApi2);
        logDiagnostics("TP-003 control", result);
        result.assertSuccess();
        String moduleFqn = TP003_CONTROL_PKG + ".GeneratedJaxRsResourcesModule";
        result.assertGeneratedSourceContains(moduleFqn, "ControlApi.class");
        result.assertGeneratedSourceContains(moduleFqn, "ControlApi2.class");
    }

    // -----------------------------------------------------------------------------------------
    // TP-004 — application paths are normalized and rest-024's rejected paths fail naming the rule
    // -----------------------------------------------------------------------------------------

    private enum PathOutcome {
        REJECTED,
        NORMALIZED,
        MISSING
    }

    private record PathCase(String label, String pathLiteralOrNull, String ruleOrNormalized, PathOutcome outcome) {}

    private static JavaFileObject pathFixture(String packageName, String pathLiteralOrNull) {
        String pathAttr = pathLiteralOrNull == null ? "" : "path = \"" + pathLiteralOrNull + "\", ";
        return SourceFiles.inline(packageName + ".Api", """
                package %s;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "api", %sresources = PathResource.class)
                interface Api {}
                """.formatted(packageName, pathAttr));
    }

    private static Stream<Arguments> pathCases() {
        List<PathCase> raw = new ArrayList<>();
        raw.add(new PathCase("/api/*/v1 -> wildcard", "/api/*/v1", "wildcard", PathOutcome.REJECTED));
        raw.add(new PathCase("/api/:id -> router pattern", "/api/:id", "router pattern", PathOutcome.REJECTED));
        raw.add(new PathCase("/api/{v} -> router pattern", "/api/{v}", "router pattern", PathOutcome.REJECTED));
        raw.add(new PathCase("/api?x=1 -> query", "/api?x=1", "query", PathOutcome.REJECTED));
        raw.add(new PathCase("/api#frag -> fragment", "/api#frag", "fragment", PathOutcome.REJECTED));
        raw.add(new PathCase("/api/../x -> dot segment", "/api/../x", "dot segment", PathOutcome.REJECTED));
        raw.add(new PathCase("/api/./x -> dot segment", "/api/./x", "dot segment", PathOutcome.REJECTED));
        raw.add(new PathCase("/api%2Fv1 -> encoded separator", "/api%2Fv1", "encoded separator", PathOutcome.REJECTED));
        raw.add(new PathCase("/api//v1 -> repeated separator", "/api//v1", "repeated separator", PathOutcome.REJECTED));
        raw.add(new PathCase(
                "/api/ü -> unsupported character", "/api/ü", "unsupported character", PathOutcome.REJECTED));
        raw.add(new PathCase("\"\" -> /", "", "/", PathOutcome.NORMALIZED));
        raw.add(new PathCase("api/public/ -> /api/public", "api/public/", "/api/public", PathOutcome.NORMALIZED));
        raw.add(new PathCase("/api/public/* -> /api/public", "/api/public/*", "/api/public", PathOutcome.NORMALIZED));
        raw.add(new PathCase("/* -> /", "/*", "/", PathOutcome.NORMALIZED));
        raw.add(new PathCase("/v1.0/x_y~z- unchanged", "/v1.0/x_y~z-", "/v1.0/x_y~z-", PathOutcome.NORMALIZED));
        raw.add(new PathCase("missing path", null, null, PathOutcome.MISSING));

        List<Arguments> arguments = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            PathCase testCase = raw.get(i);
            arguments.add(Arguments.of(testCase.label(), testCase, "dev.vertique.test.tp004.p" + i));
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pathCases")
    @DisplayName("TP-004 — application paths are normalized and rest-024's rejected paths fail naming the rule")
    void applicationPathsFollowTheApplicationPathGrammar(String label, PathCase testCase, String packageName) {
        String moduleFqn = packageName + ".GeneratedJaxRsResourcesModule";
        String apiFqn = packageName + ".Api";
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                pathResourceFixture(packageName),
                pathFixture(packageName, testCase.pathLiteralOrNull()));
        logDiagnostics("TP-004 " + label, result);
        switch (testCase.outcome()) {
            case REJECTED -> {
                result.assertFailed();
                List<Diagnostic<? extends JavaFileObject>> errors =
                        result.compilation().errors();
                String message = errors.stream()
                        .map(d -> d.getMessage(null))
                        .filter(Objects::nonNull)
                        .filter(m -> m.contains(apiFqn))
                        .findFirst()
                        .orElse(null);
                assertTrue(
                        message != null,
                        () -> "Expected an ERROR naming '" + apiFqn + "'" + diagnosticsSummary(result));
                assertTrue(
                        message.contains(testCase.pathLiteralOrNull()),
                        () -> "Expected the ERROR to contain the value as written '" + testCase.pathLiteralOrNull()
                                + "': " + message);
                assertTrue(
                        message.contains("(rule: " + testCase.ruleOrNormalized() + ")"),
                        () -> "Expected the ERROR to contain '(rule: " + testCase.ruleOrNormalized() + ")': "
                                + message);
            }
            case NORMALIZED -> {
                result.assertSuccess();
                result.assertGeneratedSourceContains(moduleFqn, "\"" + testCase.ruleOrNormalized() + "\"");
            }
            case MISSING -> {
                result.assertFailed();
                boolean namesPathElement = result.compilation().errors().stream()
                        .map(d -> d.getMessage(null))
                        .filter(Objects::nonNull)
                        .anyMatch(msg -> msg.contains("'path'") && msg.contains("missing"));
                assertTrue(
                        namesPathElement,
                        () -> "Expected javac's missing-element error naming 'path'" + diagnosticsSummary(result));
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // TP-005 — listed resources must be concrete @Path resource classes, listed once
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("TP-005 — listed resources must be concrete @Path resource classes, listed once")
    void listedResourcesMustBeConcretePathResources() {
        assertAll(
                "TP-005 rows",
                () -> tp005InterfaceResourceRejected(),
                () -> tp005AbstractResourceRejected(),
                () -> tp005ProviderResourceRejected(),
                () -> tp005FeatureResourceRejected(),
                () -> tp005DynamicFeatureResourceRejected(),
                () -> tp005NoEffectivePathResourceRejected(),
                () -> tp005DuplicateResourceRejected(),
                () -> tp005PathResourceAccepted(),
                () -> tp005InterfaceInheritedPathResourceAccepted());
    }

    private void tp005InterfaceResourceRejected() {
        String pkg = "dev.vertique.test.tp005.iface";
        JavaFileObject ifaceResource = SourceFiles.inline(pkg + ".IfaceResource", """
                package %s;

                import jakarta.ws.rs.Path;

                @Path("/iface")
                public interface IfaceResource {}
                """.formatted(pkg));
        JavaFileObject api = SourceFiles.inline(pkg + ".Api", """
                package %s;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "api", path = "/api", resources = IfaceResource.class)
                interface Api {}
                """.formatted(pkg));
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), ifaceResource, api);
        logDiagnostics("TP-005 interface resource", result);
        assertErrorNamesTypeAndContainsAll(result, pkg + ".Api", "IfaceResource");
    }

    private void tp005AbstractResourceRejected() {
        String pkg = "dev.vertique.test.tp005.abstractclass";
        JavaFileObject abstractResource = SourceFiles.inline(pkg + ".AbstractResource", """
                package %s;

                import jakarta.ws.rs.Path;

                @Path("/abstract")
                public abstract class AbstractResource {}
                """.formatted(pkg));
        JavaFileObject api = SourceFiles.inline(pkg + ".Api", """
                package %s;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "api", path = "/api", resources = AbstractResource.class)
                interface Api {}
                """.formatted(pkg));
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), abstractResource, api);
        logDiagnostics("TP-005 abstract resource", result);
        assertErrorNamesTypeAndContainsAll(result, pkg + ".Api", "AbstractResource");
    }

    private void tp005ProviderResourceRejected() {
        String pkg = "dev.vertique.test.tp005.provider";
        JavaFileObject providerResource = SourceFiles.inline(pkg + ".ProviderResource", """
                package %s;

                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.ext.Provider;

                @Path("/provider")
                @Provider
                public class ProviderResource {}
                """.formatted(pkg));
        JavaFileObject api = SourceFiles.inline(pkg + ".Api", """
                package %s;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "api", path = "/api", resources = ProviderResource.class)
                interface Api {}
                """.formatted(pkg));
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), providerResource, api);
        logDiagnostics("TP-005 provider resource", result);
        assertErrorNamesTypeAndContainsAll(result, pkg + ".Api", "ProviderResource");
    }

    private void tp005FeatureResourceRejected() {
        String pkg = "dev.vertique.test.tp005.feature";
        JavaFileObject featureResource = SourceFiles.inline(pkg + ".FeatureResource", """
                package %s;

                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.core.Feature;
                import jakarta.ws.rs.core.FeatureContext;

                @Path("/feature")
                public class FeatureResource implements Feature {
                    @Override
                    public boolean configure(FeatureContext context) {
                        return true;
                    }
                }
                """.formatted(pkg));
        JavaFileObject api = SourceFiles.inline(pkg + ".Api", """
                package %s;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "api", path = "/api", resources = FeatureResource.class)
                interface Api {}
                """.formatted(pkg));
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), featureResource, api);
        logDiagnostics("TP-005 Feature resource", result);
        assertErrorNamesTypeAndContainsAll(result, pkg + ".Api", "FeatureResource");
    }

    private void tp005DynamicFeatureResourceRejected() {
        String pkg = "dev.vertique.test.tp005.dynamicfeature";
        JavaFileObject dynamicFeatureResource = SourceFiles.inline(pkg + ".DynamicFeatureResource", """
                package %s;

                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.container.DynamicFeature;
                import jakarta.ws.rs.container.ResourceInfo;
                import jakarta.ws.rs.core.FeatureContext;

                @Path("/dynamicfeature")
                public class DynamicFeatureResource implements DynamicFeature {
                    @Override
                    public void configure(ResourceInfo resourceInfo, FeatureContext context) {}
                }
                """.formatted(pkg));
        JavaFileObject api = SourceFiles.inline(pkg + ".Api", """
                package %s;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "api", path = "/api", resources = DynamicFeatureResource.class)
                interface Api {}
                """.formatted(pkg));
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), dynamicFeatureResource, api);
        logDiagnostics("TP-005 DynamicFeature resource", result);
        assertErrorNamesTypeAndContainsAll(result, pkg + ".Api", "DynamicFeatureResource");
    }

    private void tp005NoEffectivePathResourceRejected() {
        String pkg = "dev.vertique.test.tp005.nopath";
        JavaFileObject noPathResource = SourceFiles.inline(pkg + ".NoPathResource", """
                package %s;

                public class NoPathResource {}
                """.formatted(pkg));
        JavaFileObject api = SourceFiles.inline(pkg + ".Api", """
                package %s;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "api", path = "/api", resources = NoPathResource.class)
                interface Api {}
                """.formatted(pkg));
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), noPathResource, api);
        logDiagnostics("TP-005 no-effective-path resource", result);
        assertErrorNamesTypeAndContainsAll(result, pkg + ".Api", "NoPathResource");
    }

    private void tp005DuplicateResourceRejected() {
        String pkg = "dev.vertique.test.tp005.duplicate";
        JavaFileObject pathResource = pathResourceFixture(pkg);
        JavaFileObject api = SourceFiles.inline(pkg + ".Api", """
                package %s;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "api", path = "/api", resources = {PathResource.class, PathResource.class})
                interface Api {}
                """.formatted(pkg));
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), pathResource, api);
        logDiagnostics("TP-005 duplicate resource", result);
        assertErrorNamesTypeAndContainsAll(result, pkg + ".Api", "PathResource");
    }

    private void tp005PathResourceAccepted() {
        String pkg = "dev.vertique.test.tp005.accepted";
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), pathResourceFixture(pkg), apiFixture(pkg));
        logDiagnostics("TP-005 accepted PathResource", result);
        result.assertSuccess();
        result.assertGeneratedSourceContains(pkg + ".GeneratedJaxRsResourcesModule", "PathResource.class");
    }

    private void tp005InterfaceInheritedPathResourceAccepted() {
        String pkg = "dev.vertique.test.tp005.inherited";
        JavaFileObject contract = SourceFiles.inline(pkg + ".InheritedPathContract", """
                package %s;

                import jakarta.ws.rs.Path;

                @Path("/inherited")
                public interface InheritedPathContract {}
                """.formatted(pkg));
        JavaFileObject resource = SourceFiles.inline(pkg + ".InheritedPathResource", """
                package %s;

                public class InheritedPathResource implements InheritedPathContract {}
                """.formatted(pkg));
        JavaFileObject api = SourceFiles.inline(pkg + ".Api", """
                package %s;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "api", path = "/api", resources = InheritedPathResource.class)
                interface Api {}
                """.formatted(pkg));
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), contract, resource, api);
        logDiagnostics("TP-005 accepted interface-inherited @Path", result);
        result.assertSuccess();
        result.assertGeneratedSourceContains(pkg + ".GeneratedJaxRsResourcesModule", "InheritedPathResource.class");
    }

    // -----------------------------------------------------------------------------------------
    // TP-006 — membership is exactly one form, and discovery stands alone in its unit
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("TP-006 — membership is exactly one form, and discovery stands alone in its unit")
    void membershipIsExactlyOneFormAndDiscoveryIsAlone() {
        assertAll(
                "TP-006 cases",
                () -> tp006BothFormsFails(),
                () -> tp006NeitherFormFails(),
                () -> tp006DiscoveryBesideAnotherFails(),
                () -> tp006DiscoveryBesideConditionalAnotherFails(),
                () -> tp006SeparateCompilationsSucceed(),
                () -> tp006SoleDiscoveryUnitCompiles());
    }

    private void tp006BothFormsFails() {
        String pkg = "dev.vertique.test.tp006.bothforms";
        JavaFileObject api = SourceFiles.inline(pkg + ".Api", """
                package %s;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "api", path = "/api", resources = PathResource.class, discover = true)
                interface Api {}
                """.formatted(pkg));
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), pathResourceFixture(pkg), api);
        logDiagnostics("TP-006 both forms", result);
        assertErrorNamesTypeAndContainsAll(result, pkg + ".Api", "exactly one of");
    }

    private void tp006NeitherFormFails() {
        String pkg = "dev.vertique.test.tp006.neitherform";
        JavaFileObject api = SourceFiles.inline(pkg + ".Api", """
                package %s;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "api", path = "/api")
                interface Api {}
                """.formatted(pkg));
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), pathResourceFixture(pkg), api);
        logDiagnostics("TP-006 neither form", result);
        assertErrorNamesTypeAndContainsAll(result, pkg + ".Api", "exactly one of");
    }

    private void tp006DiscoveryBesideAnotherFails() {
        String pkg = "dev.vertique.test.tp006.beside";
        JavaFileObject discoverOnly = SourceFiles.inline(pkg + ".DiscoverOnly", """
                package %s;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "all", path = "/", discover = true)
                interface DiscoverOnly {}
                """.formatted(pkg));
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), pathResourceFixture(pkg), apiFixture(pkg), discoverOnly);
        logDiagnostics("TP-006 discovery beside another", result);
        result.assertFailed();
        boolean namesDiscoverOnly = result.compilation().errors().stream()
                .map(d -> d.getMessage(null))
                .filter(Objects::nonNull)
                .anyMatch(msg -> msg.contains(pkg + ".DiscoverOnly"));
        assertTrue(
                namesDiscoverOnly,
                () -> "Expected an ERROR naming '" + pkg + ".DiscoverOnly'" + diagnosticsSummary(result));
    }

    private void tp006DiscoveryBesideConditionalAnotherFails() {
        String pkg = "dev.vertique.test.tp006.besideconditional";
        JavaFileObject discoverOnly = SourceFiles.inline(pkg + ".DiscoverOnly", """
                package %s;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "all", path = "/", discover = true)
                interface DiscoverOnly {}
                """.formatted(pkg));
        JavaFileObject conditionalApi = SourceFiles.inline(pkg + ".Api", """
                package %s;

                import dev.vertique.codegen.ConditionalOnProperty;
                import dev.vertique.rest.core.application.RestApplication;

                @ConditionalOnProperty(name = "tp006.api.enabled")
                @RestApplication(name = "api", path = "/api", resources = PathResource.class)
                interface Api {}
                """.formatted(pkg));
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), pathResourceFixture(pkg), conditionalApi, discoverOnly);
        logDiagnostics("TP-006 discovery beside conditional another", result);
        result.assertFailed();
        boolean namesDiscoverOnly = result.compilation().errors().stream()
                .map(d -> d.getMessage(null))
                .filter(Objects::nonNull)
                .anyMatch(msg -> msg.contains(pkg + ".DiscoverOnly"));
        assertTrue(
                namesDiscoverOnly,
                () -> "Expected an ERROR naming '" + pkg + ".DiscoverOnly'" + diagnosticsSummary(result));
    }

    private void tp006SeparateCompilationsSucceed() {
        String discoverPkg = "dev.vertique.test.tp006.separate.discover";
        String apiPkg = "dev.vertique.test.tp006.separate.api";
        JavaFileObject discoverOnly = SourceFiles.inline(discoverPkg + ".DiscoverOnly", """
                package %s;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "all", path = "/", discover = true)
                interface DiscoverOnly {}
                """.formatted(discoverPkg));
        var discoverResult = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), discoverOnly);
        logDiagnostics("TP-006 separate (discover)", discoverResult);
        discoverResult.assertSuccess();

        var apiResult =
                ProcessorTestHarness.run(new JaxRsPipelineProcessor(), pathResourceFixture(apiPkg), apiFixture(apiPkg));
        logDiagnostics("TP-006 separate (api)", apiResult);
        apiResult.assertSuccess();
    }

    private void tp006SoleDiscoveryUnitCompiles() {
        String pkg = "dev.vertique.test.tp006.alone";
        JavaFileObject discoverOnly = SourceFiles.inline(pkg + ".DiscoverOnly", """
                package %s;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "all", path = "/", discover = true)
                interface DiscoverOnly {}
                """.formatted(pkg));
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), discoverOnly);
        logDiagnostics("TP-006 sole discovery unit", result);
        result.assertSuccess();
        String moduleFqn = pkg + ".GeneratedJaxRsResourcesModule";
        Class<?> declaringType = result.loadGeneratedClass(pkg + ".DiscoverOnly");
        GeneratedRestApplicationRegistration registration = soleRegistrationOf(result, moduleFqn);
        assertEquals(declaringType, registration.declaringType(), "declaringType()");
        assertEquals("all", registration.name(), "name()");
        assertTrue(registration.discover(), "discover() must be true for the sole discovery declaration");
        assertTrue(
                registration.resources().isEmpty(),
                () -> "resources() must be empty when discover() is true but was: " + registration.resources());
    }

    /**
     * Loads {@code moduleFqn} and reflectively invokes its sole
     * {@code GeneratedRestApplicationRegistration}-returning method with an empty
     * {@code @VertxConfig JsonObject}, mirroring {@code RestApplicationRegistrationEmitterTest}'s
     * reflective invocation style so an accessor-level assertion (not a source-text guess) proves
     * the emitted {@code resources}/{@code discover} shape.
     */
    private static GeneratedRestApplicationRegistration soleRegistrationOf(
            ProcessorTestHarness.Result result, String moduleFqn) {
        Class<?> module = result.loadGeneratedClass(moduleFqn);
        List<Method> registrationMethods = Arrays.stream(module.getDeclaredMethods())
                .filter(m -> GeneratedRestApplicationRegistration.class.equals(m.getReturnType()))
                .toList();
        assertEquals(
                1,
                registrationMethods.size(),
                () -> "Expected exactly one registration method in " + moduleFqn + diagnosticsSummary(result));
        Method method = registrationMethods.get(0);
        assertEquals(
                1,
                method.getParameterCount(),
                () -> "Expected '" + method.getName() + "' to take exactly one parameter (no Provider)");
        assertEquals(
                JsonObject.class,
                method.getParameterTypes()[0],
                () -> "Expected '" + method.getName() + "'s sole parameter to be a JsonObject (@VertxConfig)");
        method.setAccessible(true);
        try {
            return (GeneratedRestApplicationRegistration) method.invoke(null, new JsonObject());
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new AssertionFailedError(
                    "Failed to invoke registration method '" + method.getName() + "': " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------------------------
    // TP-008 — opted-out declarations are not registered and are named in one warning
    // -----------------------------------------------------------------------------------------

    @TestFactory
    @DisplayName("TP-008 — opted-out declarations are not registered and are named in one warning")
    Stream<DynamicTest> optedOutDeclarationsAreNotRegisteredAndWarn() {
        return Stream.of(
                DynamicTest.dynamicTest("(a) @NoAutoWire declaration is inert", this::tp008RowA),
                DynamicTest.dynamicTest("(b) autoWire=false suppresses registration and warns", this::tp008RowB),
                DynamicTest.dynamicTest("(c) autoWire=false still validates other declarations", this::tp008RowC));
    }

    private void tp008RowA() {
        String pkg = "dev.vertique.test.tp008.a";
        JavaFileObject off = SourceFiles.inline(pkg + ".Off", """
                package %s;

                import dev.vertique.codegen.NoAutoWire;
                import dev.vertique.rest.core.application.RestApplication;

                @NoAutoWire
                @RestApplication(name = "Bad Name", path = "/x*", discover = true)
                interface Off {}
                """.formatted(pkg));
        var result =
                ProcessorTestHarness.run(new JaxRsPipelineProcessor(), pathResourceFixture(pkg), apiFixture(pkg), off);
        logDiagnostics("TP-008 (a)", result);
        result.assertSuccess();
        assertEquals(0, result.compilation().errors().size(), "Expected no error for Off" + diagnosticsSummary(result));
        long offWarnings = messagesOf(result.compilation().warnings()).stream()
                .filter(m -> m.contains("Off") && m.contains("is not registered"))
                .count();
        assertEquals(
                1,
                offWarnings,
                () -> "Expected exactly one WARNING naming Off and stating it is not registered"
                        + diagnosticsSummary(result));
        String moduleFqn = pkg + ".GeneratedJaxRsResourcesModule";
        result.assertGeneratedSourceContains(moduleFqn, "Api.class");
        result.assertGeneratedSourceDoesNotContain(moduleFqn, "Off.class");
    }

    private void tp008RowB() {
        String pkg = "dev.vertique.test.tp008.b";
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                Map.of("vertique.codegen.autoWire", "false"),
                pathResourceFixture(pkg),
                apiFixture(pkg));
        logDiagnostics("TP-008 (b)", result);
        result.assertSuccess();
        long apiWarnings = messagesOf(result.compilation().warnings()).stream()
                .filter(m -> m.contains("Api") && m.contains("is not registered"))
                .count();
        assertEquals(
                1,
                apiWarnings,
                () -> "Expected exactly one WARNING naming Api and stating it is not registered"
                        + diagnosticsSummary(result));
        assertTrue(
                result.compilation()
                        .generatedSourceFile(pkg + ".GeneratedJaxRsResourcesModule")
                        .isEmpty(),
                () -> "Expected no module written under autoWire=false" + diagnosticsSummary(result));
    }

    private void tp008RowC() {
        String pkg = "dev.vertique.test.tp008.c";
        JavaFileObject badName = SourceFiles.inline(pkg + ".BadName", """
                package %s;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "Bad", path = "/b", resources = PathResource.class)
                interface BadName {}
                """.formatted(pkg));
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                Map.of("vertique.codegen.autoWire", "false"),
                pathResourceFixture(pkg),
                badName);
        logDiagnostics("TP-008 (c)", result);
        assertErrorNamesTypeAndContainsAll(result, pkg + ".BadName", GRAMMAR_PHRASE);
    }

    // -----------------------------------------------------------------------------------------
    // TP-010 — the declaring interface and listed resources must be accessible from the module
    // -----------------------------------------------------------------------------------------

    private static final String ACC_MODULE = "acc.GeneratedJaxRsResourcesModule";

    private record AccessibilityCase(
            String label,
            List<JavaFileObject> sources,
            Map<String, String> options,
            BiConsumer<ProcessorTestHarness.Result, GeneratedModuleRecorder> verify) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static Stream<AccessibilityCase> accessibilityCases() {
        return Stream.of(tp010RowA(), tp010RowB(), tp010RowC(), tp010RowD(), tp010RowE(), tp010RowF());
    }

    private static AccessibilityCase tp010RowA() {
        JavaFileObject pathResource = pathResourceFixture("acc");
        JavaFileObject hiddenApi = SourceFiles.inline("acc.apps.HiddenApi", """
                package acc.apps;

                import acc.PathResource;
                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "hidden", path = "/hidden", resources = PathResource.class)
                interface HiddenApi {}
                """);
        return new AccessibilityCase(
                "(a) package-private declaration outside the module's package",
                List.of(pathResource, hiddenApi),
                Map.of(),
                (result, recorder) -> assertAll(
                        "TP-010 (a)",
                        () -> assertErrorNamesTypeAndContainsAll(
                                result,
                                "acc.apps.HiddenApi",
                                "is not accessible from the generated module's package 'acc'"),
                        () -> assertNoErrorInGeneratedModuleSource(result, ACC_MODULE),
                        () -> assertNoRegistrationEmitted(recorder, ACC_MODULE, "acc.apps.HiddenApi")));
    }

    private static AccessibilityCase tp010RowB() {
        JavaFileObject pathResource = pathResourceFixture("acc");
        JavaFileObject samePackageApi = SourceFiles.inline("acc.SamePackageApi", """
                package acc;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "samepkg", path = "/samepkg", resources = PathResource.class)
                interface SamePackageApi {}
                """);
        return new AccessibilityCase(
                "(b) package-private declaration in the module's own package",
                List.of(pathResource, samePackageApi),
                Map.of(),
                (result, recorder) -> {
                    result.assertSuccess();
                    result.assertGeneratedSourceContains(ACC_MODULE, "SamePackageApi.class");
                });
    }

    private static AccessibilityCase tp010RowC() {
        JavaFileObject pathResource = pathResourceFixture("acc");
        JavaFileObject holder = SourceFiles.inline("acc.apps.Holder", """
                package acc.apps;

                import acc.PathResource;
                import dev.vertique.rest.core.application.RestApplication;

                class Holder {

                    @RestApplication(name = "inner", path = "/inner", resources = PathResource.class)
                    public interface Inner {}
                }
                """);
        return new AccessibilityCase(
                "(c) public declaration nested in a package-private enclosing type",
                List.of(pathResource, holder),
                Map.of(),
                (result, recorder) -> assertAll(
                        "TP-010 (c)",
                        () -> assertErrorNamesTypeAndContainsAll(
                                result, "acc.apps.Holder$Inner", "its enclosing type acc.apps.Holder is not public"),
                        () -> assertNoErrorInGeneratedModuleSource(result, ACC_MODULE),
                        () -> assertNoRegistrationEmitted(recorder, ACC_MODULE, "acc.apps.Holder$Inner")));
    }

    private static AccessibilityCase tp010RowD() {
        JavaFileObject pathResource = pathResourceFixture("acc");
        JavaFileObject privateResource = SourceFiles.inline("acc.other.PrivateResource", """
                package acc.other;

                import jakarta.ws.rs.Path;

                @Path("/private")
                class PrivateResource {}
                """);
        JavaFileObject hasPrivateResource = SourceFiles.inline("acc.other.HasPrivateResource", """
                package acc.other;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "d", path = "/d", resources = PrivateResource.class)
                public interface HasPrivateResource {}
                """);
        return new AccessibilityCase(
                "(d) listed resource not accessible from the module's package",
                List.of(pathResource, privateResource, hasPrivateResource),
                Map.of(),
                (result, recorder) -> assertAll(
                        "TP-010 (d)",
                        () -> assertErrorNamesTypeAndContainsAll(
                                result,
                                "acc.other.PrivateResource",
                                "is not accessible from the generated module's package 'acc'"),
                        () -> assertNoErrorInGeneratedModuleSource(result, ACC_MODULE),
                        () -> assertNoRegistrationEmitted(recorder, ACC_MODULE, "acc.other.HasPrivateResource")));
    }

    private static AccessibilityCase tp010RowE() {
        JavaFileObject pathResource = pathResourceFixture("acc");
        JavaFileObject publicOtherResource = SourceFiles.inline("acc.other.PublicOtherResource", """
                package acc.other;

                import jakarta.ws.rs.Path;

                @Path("/public")
                public class PublicOtherResource {}
                """);
        JavaFileObject hasPublicOtherResource = SourceFiles.inline("acc.apps.HasPublicOtherResource", """
                package acc.apps;

                import acc.other.PublicOtherResource;
                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "e", path = "/e", resources = PublicOtherResource.class)
                public interface HasPublicOtherResource {}
                """);
        return new AccessibilityCase(
                "(e) public declaration listing a public resource of another package",
                List.of(pathResource, publicOtherResource, hasPublicOtherResource),
                Map.of(),
                (result, recorder) -> {
                    result.assertSuccess();
                    result.assertGeneratedSourceContains(ACC_MODULE, "HasPublicOtherResource.class");
                });
    }

    private static AccessibilityCase tp010RowF() {
        JavaFileObject acmeResource = SourceFiles.inline("com.acme.api.AcmeResource", """
                package com.acme.api;

                import jakarta.inject.Inject;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;

                @Path("/acme")
                public class AcmeResource {
                    @Inject
                    public AcmeResource() {}

                    @GET
                    public String get() { return ""; }
                }
                """);
        JavaFileObject partnerResource = SourceFiles.inline("org.partner.api.PartnerResource", """
                package org.partner.api;

                import jakarta.inject.Inject;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;

                @Path("/partner")
                public class PartnerResource {
                    @Inject
                    public PartnerResource() {}

                    @GET
                    public String get() { return ""; }
                }
                """);
        JavaFileObject disjointApi = SourceFiles.inline("com.acme.app.DisjointApi", """
                package com.acme.app;

                import dev.vertique.rest.core.application.RestApplication;

                @RestApplication(name = "disjoint", path = "/disjoint", discover = true)
                interface DisjointApi {}
                """);
        return new AccessibilityCase(
                "(f) autoWire=false with disjoint origin packages and no resolvable module package",
                List.of(acmeResource, partnerResource, disjointApi),
                Map.of("vertique.codegen.autoWire", "false"),
                (result, recorder) -> assertAll(
                        "TP-010 (f)",
                        () -> assertErrorNamesTypeAndContainsAll(
                                result,
                                "com.acme.app.DisjointApi",
                                "no generated-module package could be resolved",
                                "must be public"),
                        () -> assertNoModuleWritten(recorder)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("accessibilityCases")
    @DisplayName("TP-010 — the declaring interface and listed resources must be accessible from the module")
    void declaringInterfaceAndListedResourcesMustBeAccessible(AccessibilityCase testCase) {
        GeneratedModuleRecorder recorder = new GeneratedModuleRecorder();
        var result = ProcessorTestHarness.run(
                List.<Processor>of(new JaxRsPipelineProcessor(), recorder),
                testCase.options(),
                testCase.sources().toArray(new JavaFileObject[0]));
        logDiagnostics("TP-010 " + testCase.label(), result);
        testCase.verify().accept(result, recorder);
    }

    // -----------------------------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------------------------

    private static void assertErrorNamesTypeAndContainsAll(
            ProcessorTestHarness.Result result, String binaryName, String... phrases) {
        result.assertFailed();
        boolean found = result.compilation().errors().stream()
                .map(d -> d.getMessage(null))
                .filter(Objects::nonNull)
                .anyMatch(msg ->
                        msg.contains(binaryName) && Arrays.stream(phrases).allMatch(msg::contains));
        assertTrue(
                found,
                () -> "Expected an ERROR naming '" + binaryName + "' and containing all of " + Arrays.toString(phrases)
                        + diagnosticsSummary(result));
    }

    /**
     * Asserts that the generated module {@code moduleFqn} exists and that no generated module
     * declares a method returning {@code GeneratedRestApplicationRegistration}, where
     * {@code rejectedTypeFqn} is the unit's only {@code @RestApplication}-annotated type and was
     * rejected, so the compilation failed.
     *
     * <p>Neither the compilation's generated files nor javac's diagnostics can answer this.
     * {@code compile-testing}'s {@code Compilation.generatedFiles()}, and every accessor built on
     * it, throws once the compilation has failed; and javac does not attribute any source,
     * generated or not, once an error exists, so a registration method written into the module
     * would produce no diagnostic of its own. The check therefore reads the modules' members
     * through {@link GeneratedModuleRecorder}, which ran beside the pipeline in the same
     * compilation and looked the modules up in the final processing round, after they were
     * written.
     *
     * <p>It asserts, in order: that the recorder ran its final round; that it found
     * {@code moduleFqn}; that the module's members were visible to it, by the presence of the
     * unit's {@code pathResourceBinding} method, which the pipeline writes for the DI-eligible
     * {@code PathResource} whether or not the declaration is rejected; and that no method of any
     * module the recorder found returns {@code GeneratedRestApplicationRegistration}. The second
     * and third assertions keep the fourth from passing vacuously: were the module absent or
     * memberless in that round, the fourth would hold whatever the pipeline wrote.
     */
    private static void assertNoRegistrationEmitted(
            GeneratedModuleRecorder recorder, String moduleFqn, String rejectedTypeFqn) {
        assertTrue(recorder.finalRoundSeen(), () -> "Expected the recorder to run the final processing round");
        assertTrue(
                recorder.modulesFound().contains(moduleFqn),
                () -> "Expected the recorder to find the generated module '" + moduleFqn
                        + "' in the final processing round" + recorder.describe());
        assertTrue(
                recorder.methodNames(moduleFqn).contains("pathResourceBinding"),
                () -> "Expected the members of '" + moduleFqn + "' to be visible to the recorder (its"
                        + " 'pathResourceBinding' method)" + recorder.describe());
        assertEquals(
                List.of(),
                recorder.restRegistrationMethods(),
                () -> "Expected no GeneratedRestApplicationRegistration method for the rejected '" + rejectedTypeFqn
                        + "'" + recorder.describe());
    }

    /**
     * Asserts that the recorder found no generated module at all, as expected when
     * {@code -Avertique.codegen.autoWire=false} suppresses the module write; no module implies no
     * {@code GeneratedRestApplicationRegistration} method. The recorder searches every package the
     * pipeline could resolve the module into without a {@code -Avertique.codegen.package}
     * override — each root element's package and its ancestors — and the same lookup finds the
     * module in {@link #assertNoRegistrationEmitted}'s rows, so an empty result is not an artifact
     * of the lookup.
     */
    private static void assertNoModuleWritten(GeneratedModuleRecorder recorder) {
        assertTrue(recorder.finalRoundSeen(), () -> "Expected the recorder to run the final processing round");
        assertEquals(
                Set.of(),
                recorder.modulesFound(),
                () -> "Expected no generated module (so no registration method)" + recorder.describe());
    }

    /**
     * Asserts that no ERROR diagnostic is reported inside {@code moduleFqn}'s generated source,
     * TP-010's "no error inside the generated module's source" clause.
     *
     * <p>After a failed compilation this proves nothing about what the module contains: javac does
     * not attribute any source once an error exists, so a registration the pipeline should not have
     * written raises no error here. {@link #assertNoRegistrationEmitted} is the check that the
     * module holds no registration.
     */
    private static void assertNoErrorInGeneratedModuleSource(ProcessorTestHarness.Result result, String moduleFqn) {
        String moduleRelativePath = moduleFqn.replace('.', '/');
        boolean errorInGenerated = result.compilation().errors().stream()
                .anyMatch(d -> d.getSource() != null
                        && d.getSource().toUri().toString().contains(moduleRelativePath));
        assertFalse(
                errorInGenerated,
                () -> "Expected no compiler error reported inside the generated module source '" + moduleFqn + "'"
                        + diagnosticsSummary(result));
    }

    /**
     * A processor run beside {@link JaxRsPipelineProcessor} that records, in the final
     * ({@code processingOver()}) round, every {@code GeneratedJaxRsResourcesModule} it can find and
     * the name and return type of each method that module declares.
     *
     * <p>It looks for the module in each package of the root elements it saw in earlier rounds and
     * in every ancestor of those packages, down to the unnamed package: every package the pipeline
     * can resolve the module into without a {@code -Avertique.codegen.package} override.
     *
     * <p>It supports {@code "*"} only so javac calls it in every round, as it does the pipeline,
     * and it always returns {@code false}, so it claims no annotation and changes nothing the
     * pipeline sees or writes. The pipeline writes the module in the first round, and javac still
     * enters that generated source's types for the later rounds after a processor has reported an
     * error (it skips only attribution), so the module's members, registration methods included,
     * are readable here in a failed compilation. {@link #assertNoRegistrationEmitted} re-checks
     * that visibility in every row rather than relying on this note.
     */
    private static final class GeneratedModuleRecorder extends AbstractProcessor {

        private static final String MODULE_SIMPLE_NAME = "GeneratedJaxRsResourcesModule";

        private record RecordedMethod(String name, String returnType) {}

        private final Set<String> candidatePackages = new TreeSet<>();
        private final Map<String, List<RecordedMethod>> modules = new TreeMap<>();
        private boolean finalRoundSeen;

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
            Elements elements = processingEnv.getElementUtils();
            if (!roundEnv.processingOver()) {
                for (Element root : roundEnv.getRootElements()) {
                    String packageName =
                            elements.getPackageOf(root).getQualifiedName().toString();
                    candidatePackages.add(packageName);
                    for (int dot = packageName.lastIndexOf('.'); dot > 0; dot = packageName.lastIndexOf('.')) {
                        packageName = packageName.substring(0, dot);
                        candidatePackages.add(packageName);
                    }
                    candidatePackages.add("");
                }
                return false;
            }
            finalRoundSeen = true;
            for (String packageName : candidatePackages) {
                String moduleFqn = packageName.isEmpty() ? MODULE_SIMPLE_NAME : packageName + "." + MODULE_SIMPLE_NAME;
                TypeElement module = elements.getTypeElement(moduleFqn);
                if (module == null) {
                    continue;
                }
                List<RecordedMethod> methods = new ArrayList<>();
                for (ExecutableElement method : ElementFilter.methodsIn(module.getEnclosedElements())) {
                    methods.add(new RecordedMethod(
                            method.getSimpleName().toString(),
                            method.getReturnType().toString()));
                }
                modules.put(moduleFqn, methods);
            }
            return false;
        }

        boolean finalRoundSeen() {
            return finalRoundSeen;
        }

        /** The fully qualified names of the modules found, in order. */
        Set<String> modulesFound() {
            return modules.keySet();
        }

        /** The names of {@code moduleFqn}'s methods; empty when that module was not found. */
        List<String> methodNames(String moduleFqn) {
            return modules.getOrDefault(moduleFqn, List.of()).stream()
                    .map(RecordedMethod::name)
                    .toList();
        }

        /**
         * Every found module's methods returning {@code GeneratedRestApplicationRegistration}, as
         * {@code moduleFqn#methodName}.
         */
        List<String> restRegistrationMethods() {
            List<String> registrationMethods = new ArrayList<>();
            modules.forEach((moduleFqn, methods) -> methods.stream()
                    .filter(m ->
                            GeneratedRestApplicationRegistration.class.getName().equals(m.returnType()))
                    .forEach(m -> registrationMethods.add(moduleFqn + "#" + m.name())));
            return registrationMethods;
        }

        String describe() {
            StringBuilder sb = new StringBuilder("\nRecorder (final round seen: ")
                    .append(finalRoundSeen)
                    .append(", packages searched: ")
                    .append(candidatePackages)
                    .append("):\n");
            if (modules.isEmpty()) {
                sb.append("  no module found\n");
            }
            modules.forEach((moduleFqn, methods) -> {
                sb.append("  ").append(moduleFqn).append('\n');
                methods.forEach(m -> sb.append("    ")
                        .append(m.name())
                        .append(" -> ")
                        .append(m.returnType())
                        .append('\n'));
            });
            return sb.toString();
        }
    }

    private static List<String> messagesOf(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream()
                .map(d -> d.getMessage(null))
                .filter(Objects::nonNull)
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

    /** Like {@link #diagnosticsSummary}, with each diagnostic's source file name. */
    private static String diagnosticsSummaryWithSources(ProcessorTestHarness.Result result) {
        StringBuilder sb = new StringBuilder("\nCompilation diagnostics:\n");
        result.compilation().diagnostics().forEach(d -> sb.append("  [")
                .append(d.getKind())
                .append("] ")
                .append(d.getSource() == null ? "(no source)" : d.getSource().getName())
                .append(": ")
                .append(d.getMessage(null))
                .append('\n'));
        return sb.toString();
    }
}
