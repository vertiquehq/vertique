// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.beans.Introspector;
import java.io.IOException;
import java.util.List;
import java.util.Map;
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
 * APT compilation tests for T003's application-registration emission: {@link JaxRsPipelineProcessor}
 * scanning eligible {@code jakarta.ws.rs.core.Application} subtypes (C-SCAN), normalizing their
 * {@code @ApplicationPath} (C-PATH steps 0 to 2), and {@link GeneratedJaxRsResourcesModuleEmitter}
 * writing their {@code GeneratedJaxRsApplicationRegistration} bindings and the presence-gated /
 * lazy-catalog resource shape (C-GEN) alongside them.
 *
 * <p>Each test compiles one or more small fixtures through {@link JaxRsPipelineProcessor} via
 * {@link ProcessorTestHarness} and asserts the generated {@code GeneratedJaxRsResourcesModule}
 * source and/or the compiler diagnostics. Fixture sources are text blocks named after the
 * application (or resource) they declare.
 */
class JaxRsApplicationRegistrationEmitterTest {

    // -----------------------------------------------------------------------------------------
    // TP-001 — registrations are emitted for injectable and no-arg applications
    // -----------------------------------------------------------------------------------------

    private static final String TP001_MIXED_MODULE = "dev.vertique.test.tp001.mixed.GeneratedJaxRsResourcesModule";

    private static final JavaFileObject TP001_PLAIN_RESOURCE =
            SourceFiles.inline("dev.vertique.test.tp001.mixed.PlainResource", """
            package dev.vertique.test.tp001.mixed;

            import jakarta.inject.Inject;
            import jakarta.ws.rs.GET;
            import jakarta.ws.rs.Path;

            @Path("/plain")
            public class PlainResource {
                @Inject
                public PlainResource() {}

                @GET
                public String get() { return ""; }
            }
            """);

    private static final JavaFileObject TP001_INJECTABLE_APPLICATION =
            SourceFiles.inline("dev.vertique.test.tp001.mixed.InjectableApplication", """
            package dev.vertique.test.tp001.mixed;

            import jakarta.inject.Inject;
            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @ApplicationPath("/api/inject/")
            public class InjectableApplication extends Application {
                @Inject
                public InjectableApplication() {}
            }
            """);

    private static final JavaFileObject TP001_NO_ARG_APPLICATION =
            SourceFiles.inline("dev.vertique.test.tp001.mixed.NoArgApplication", """
            package dev.vertique.test.tp001.mixed;

            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @ApplicationPath("api/noarg")
            public class NoArgApplication extends Application {
                public NoArgApplication() {}
            }
            """);

    private static final JavaFileObject TP001_CONDITIONAL_APPLICATION =
            SourceFiles.inline("dev.vertique.test.tp001.mixed.ConditionalApplication", """
            package dev.vertique.test.tp001.mixed;

            import dev.vertique.codegen.ConditionalOnProperty;
            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @ConditionalOnProperty(name = "tp001.conditionalApplication.active")
            @ApplicationPath("/api/conditional/*")
            public class ConditionalApplication extends Application {
                public ConditionalApplication() {}
            }
            """);

    private static final JavaFileObject TP001_NESTED_APPLICATION_HOLDER =
            SourceFiles.inline("dev.vertique.test.tp001.mixed.Wrapper", """
            package dev.vertique.test.tp001.mixed;

            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            public class Wrapper {

                @ApplicationPath("/api/nested//")
                public static class NestedApplication extends Application {
                    public NestedApplication() {}
                }
            }
            """);

    private static final String TP001_ONLYAPP_MODULE = "dev.vertique.test.tp001.onlyapp.GeneratedJaxRsResourcesModule";

    private static final JavaFileObject TP001_ONLY_APPLICATION =
            SourceFiles.inline("dev.vertique.test.tp001.onlyapp.OnlyApplication", """
            package dev.vertique.test.tp001.onlyapp;

            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @ApplicationPath("/api/only")
            public class OnlyApplication extends Application {
                public OnlyApplication() {}
            }
            """);

    private static final String TP001_DUP_MODULE = "dev.vertique.test.tp001.dup.GeneratedJaxRsResourcesModule";

    private static final JavaFileObject TP001_DUP_OUTER_A =
            SourceFiles.inline("dev.vertique.test.tp001.dup.OuterA", """
            package dev.vertique.test.tp001.dup;

            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            public class OuterA {

                @ApplicationPath("/api/a")
                public static class DupApp extends Application {
                    public DupApp() {}
                }
            }
            """);

    private static final JavaFileObject TP001_DUP_OUTER_B =
            SourceFiles.inline("dev.vertique.test.tp001.dup.OuterB", """
            package dev.vertique.test.tp001.dup;

            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            public class OuterB {

                @ApplicationPath("/api/b")
                public static class DupApp extends Application {
                    public DupApp() {}
                }
            }
            """);

    @Test
    @DisplayName("TP-001 — registrations are emitted for injectable and no-arg applications")
    void emitsRegistrationForInjectableAndNoArgApplications() {
        assertAll(
                "TP-001 fixtures",
                () -> tp001MixedFixture(),
                () -> tp001ApplicationOnlyFixture(),
                () -> tp001DuplicateSimpleNameFixture());
    }

    private void tp001MixedFixture() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                TP001_PLAIN_RESOURCE,
                TP001_INJECTABLE_APPLICATION,
                TP001_NO_ARG_APPLICATION,
                TP001_CONDITIONAL_APPLICATION,
                TP001_NESTED_APPLICATION_HOLDER);
        result.assertSuccess();
        logGeneratedSource("TP-001 mixed fixture module", sourceOf(result, TP001_MIXED_MODULE));

        assertRegistrationMethod(
                result,
                TP001_MIXED_MODULE,
                "injectableApplicationRegistration",
                "InjectableApplication",
                true,
                "/api/inject",
                "true",
                "provider");
        assertRegistrationMethod(
                result,
                TP001_MIXED_MODULE,
                "noArgApplicationRegistration",
                "NoArgApplication",
                false,
                "/api/noarg",
                "true",
                "NoArgApplication::new");
        assertRegistrationMethod(
                result,
                TP001_MIXED_MODULE,
                "conditionalApplicationRegistration",
                "ConditionalApplication",
                false,
                "/api/conditional",
                "PropertyCondition.matchesAll(config, CONDITIONAL_APPLICATION_REGISTRATION_CONDITIONS)",
                "ConditionalApplication::new");
        assertRegistrationMethod(
                result,
                TP001_MIXED_MODULE,
                "nestedApplicationRegistration",
                "Wrapper.NestedApplication",
                false,
                "/api/nested",
                "true",
                "Wrapper.NestedApplication::new");
    }

    private void tp001ApplicationOnlyFixture() {
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), TP001_ONLY_APPLICATION);
        result.assertSuccess();
        assertTrue(
                moduleWritten(result, TP001_ONLYAPP_MODULE),
                () -> "Expected the application-only unit to still write its module at '" + TP001_ONLYAPP_MODULE + "'."
                        + diagnosticsSummary(result));
        assertRegistrationMethod(
                result,
                TP001_ONLYAPP_MODULE,
                "onlyApplicationRegistration",
                "OnlyApplication",
                false,
                "/api/only",
                "true",
                "OnlyApplication::new");
    }

    private void tp001DuplicateSimpleNameFixture() {
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), TP001_DUP_OUTER_A, TP001_DUP_OUTER_B);
        result.assertSuccess();
        assertRegistrationMethod(
                result,
                TP001_DUP_MODULE,
                "dupAppRegistration",
                "OuterA.DupApp",
                false,
                "/api/a",
                "true",
                "OuterA.DupApp::new");
        assertRegistrationMethod(
                result,
                TP001_DUP_MODULE,
                "dupAppRegistration_2",
                "OuterB.DupApp",
                false,
                "/api/b",
                "true",
                "OuterB.DupApp::new");
    }

    // -----------------------------------------------------------------------------------------
    // TP-002 — an application without a usable constructor fails compilation
    // -----------------------------------------------------------------------------------------

    private static final JavaFileObject TP002_PRIVATE_CONSTRUCTOR_APPLICATION =
            SourceFiles.inline("dev.vertique.test.tp002.privatector.PrivateConstructorApplication", """
            package dev.vertique.test.tp002.privatector;

            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @ApplicationPath("/api/private")
            public class PrivateConstructorApplication extends Application {
                private PrivateConstructorApplication() {}
            }
            """);

    private static final JavaFileObject TP002_PARAMETERIZED_CONSTRUCTOR_APPLICATION =
            SourceFiles.inline("dev.vertique.test.tp002.paramctor.ParameterizedConstructorApplication", """
            package dev.vertique.test.tp002.paramctor;

            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @ApplicationPath("/api/param")
            public class ParameterizedConstructorApplication extends Application {
                public ParameterizedConstructorApplication(String name) {}
            }
            """);

    private static final JavaFileObject TP002_INACCESSIBLE_RESOURCE =
            SourceFiles.inline("dev.vertique.test.tp002.inaccessible.resource.WidgetResource", """
            package dev.vertique.test.tp002.inaccessible.resource;

            import jakarta.inject.Inject;
            import jakarta.ws.rs.GET;
            import jakarta.ws.rs.Path;

            @Path("/widgets")
            public class WidgetResource {
                @Inject
                public WidgetResource() {}

                @GET
                public String list() { return ""; }
            }
            """);

    private static final JavaFileObject TP002_INACCESSIBLE_APPLICATION =
            SourceFiles.inline("dev.vertique.test.tp002.inaccessible.app.InaccessibleApplication", """
            package dev.vertique.test.tp002.inaccessible.app;

            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @ApplicationPath("/api/inaccessible")
            class InaccessibleApplication extends Application {
                public InaccessibleApplication() {}
            }
            """);

    private static final JavaFileObject TP002_NON_STATIC_INNER_APPLICATION_HOLDER =
            SourceFiles.inline("dev.vertique.test.tp002.innerapp.OuterHolder", """
            package dev.vertique.test.tp002.innerapp;

            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            public class OuterHolder {

                @ApplicationPath("/api/inner")
                public class InnerApplication extends Application {
                    public InnerApplication() {}
                }
            }
            """);

    /**
     * One TP-002 row: an application fixture (one or more source files) that must fail compilation
     * with the processor's OWN diagnostic — an ERROR whose message names
     * {@code applicationSimpleName} AND contains {@code expectedMessagePhrase} in that SAME
     * diagnostic — compiled with the given {@code -A} options.
     *
     * <p>G-10: asserting only that some error names the class (as {@link #assertFailsNamingClass}
     * does) is insufficient here, because a downstream javac error in the generated module (e.g. an
     * inaccessible-type error) can also happen to name the class without the processor ever
     * reporting its own targeted diagnostic. {@link #assertFailsWithProcessorDiagnostic} isolates
     * the processor's diagnostic instead.
     */
    private record ConstructorCase(
            String label,
            List<JavaFileObject> sources,
            Map<String, String> options,
            String applicationSimpleName,
            String expectedMessagePhrase) {}

    private static Stream<Arguments> constructorCases() {
        String noUsableConstructor = "has no usable constructor for application registration";
        String notAccessible = "is not accessible from the generated module's package";
        String notTopLevelOrStatic = "must be a top-level or static nested class";
        List<ConstructorCase> cases = List.of(
                new ConstructorCase(
                        "(1) private constructor",
                        List.of(TP002_PRIVATE_CONSTRUCTOR_APPLICATION),
                        Map.of(),
                        "PrivateConstructorApplication",
                        noUsableConstructor),
                new ConstructorCase(
                        "(1) private constructor, autoWire=false",
                        List.of(TP002_PRIVATE_CONSTRUCTOR_APPLICATION),
                        Map.of("vertique.codegen.autoWire", "false"),
                        "PrivateConstructorApplication",
                        noUsableConstructor),
                new ConstructorCase(
                        "(2) parameterized constructor without @Inject",
                        List.of(TP002_PARAMETERIZED_CONSTRUCTOR_APPLICATION),
                        Map.of(),
                        "ParameterizedConstructorApplication",
                        noUsableConstructor),
                new ConstructorCase(
                        "(2) parameterized constructor without @Inject, autoWire=false",
                        List.of(TP002_PARAMETERIZED_CONSTRUCTOR_APPLICATION),
                        Map.of("vertique.codegen.autoWire", "false"),
                        "ParameterizedConstructorApplication",
                        noUsableConstructor),
                new ConstructorCase(
                        "(3) non-public application in a package other than the generated module's",
                        List.of(TP002_INACCESSIBLE_RESOURCE, TP002_INACCESSIBLE_APPLICATION),
                        Map.of(),
                        "InaccessibleApplication",
                        notAccessible),
                new ConstructorCase(
                        "(3) non-public application in a package other than the generated module's,"
                                + " autoWire=false",
                        List.of(TP002_INACCESSIBLE_RESOURCE, TP002_INACCESSIBLE_APPLICATION),
                        Map.of("vertique.codegen.autoWire", "false"),
                        "InaccessibleApplication",
                        notAccessible),
                new ConstructorCase(
                        "(4) non-static inner application",
                        List.of(TP002_NON_STATIC_INNER_APPLICATION_HOLDER),
                        Map.of(),
                        "InnerApplication",
                        notTopLevelOrStatic),
                new ConstructorCase(
                        "(4) non-static inner application, autoWire=false",
                        List.of(TP002_NON_STATIC_INNER_APPLICATION_HOLDER),
                        Map.of("vertique.codegen.autoWire", "false"),
                        "InnerApplication",
                        notTopLevelOrStatic));
        return cases.stream().map(c -> Arguments.of(c.label(), c));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("constructorCases")
    @DisplayName("TP-002 — an application without a usable constructor fails compilation")
    void applicationWithoutUsableConstructorFailsCompilation(String label, ConstructorCase testCase) {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                testCase.options(),
                testCase.sources().toArray(new JavaFileObject[0]));
        logDiagnostics("TP-002 " + label, result);
        assertFailsWithProcessorDiagnostic(result, testCase.applicationSimpleName(), testCase.expectedMessagePhrase());
    }

    // -----------------------------------------------------------------------------------------
    // TP-003 — registration notes, the @NoAutoWire warning, and autoWire=false warnings
    // -----------------------------------------------------------------------------------------

    private static final String TP003_AB_MODULE = "dev.vertique.test.tp003.a.GeneratedJaxRsResourcesModule";

    private static final JavaFileObject TP003_STATUS_RESOURCE =
            SourceFiles.inline("dev.vertique.test.tp003.a.StatusResource", """
            package dev.vertique.test.tp003.a;

            import jakarta.inject.Inject;
            import jakarta.ws.rs.GET;
            import jakarta.ws.rs.Path;

            @Path("/status")
            public class StatusResource {
                @Inject
                public StatusResource() {}

                @GET
                public String status() { return ""; }
            }
            """);

    private static final JavaFileObject TP003_ONE_APPLICATION =
            SourceFiles.inline("dev.vertique.test.tp003.a.OneApplication", """
            package dev.vertique.test.tp003.a;

            import jakarta.inject.Inject;
            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @ApplicationPath("/api/one")
            public class OneApplication extends Application {
                @Inject
                public OneApplication() {}
            }
            """);

    private static final JavaFileObject TP003_TWO_APPLICATION =
            SourceFiles.inline("dev.vertique.test.tp003.a.TwoApplication", """
            package dev.vertique.test.tp003.a;

            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @ApplicationPath("/api/two")
            public class TwoApplication extends Application {
                public TwoApplication() {}
            }
            """);

    /**
     * The (a)/(b) {@code @NoAutoWire} application. Per L00 ruling R1, it carries a valid
     * {@code @ApplicationPath} and a usable no-arg constructor, so a scanner mutation that ignores
     * {@code @NoAutoWire} would make it show up as "registered" rather than fail compilation.
     */
    private static final JavaFileObject TP003_MANUAL_APPLICATION =
            SourceFiles.inline("dev.vertique.test.tp003.a.ManualApplication", """
            package dev.vertique.test.tp003.a;

            import dev.vertique.codegen.NoAutoWire;
            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @NoAutoWire
            @ApplicationPath("/api/manual")
            public class ManualApplication extends Application {
                public ManualApplication() {}
            }
            """);

    private static final List<JavaFileObject> TP003_AB_SOURCES =
            List.of(TP003_STATUS_RESOURCE, TP003_ONE_APPLICATION, TP003_TWO_APPLICATION, TP003_MANUAL_APPLICATION);

    private static final JavaFileObject TP003_PORTED_API =
            SourceFiles.inline("dev.vertique.test.tp003.c.PortedApi", """
            package dev.vertique.test.tp003.c;

            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @ApplicationPath("/api")
            public class PortedApi extends Application {}
            """);

    private static final JavaFileObject TP003_UNANNOTATED_APPLICATION =
            SourceFiles.inline("dev.vertique.test.tp003.d.UnannotatedApplication", """
            package dev.vertique.test.tp003.d;

            import jakarta.ws.rs.core.Application;

            public class UnannotatedApplication extends Application {}
            """);

    private static final String TP003_E_MODULE = "dev.vertique.test.tp003.e.GeneratedJaxRsResourcesModule";

    private static final JavaFileObject TP003_PING_RESOURCE =
            SourceFiles.inline("dev.vertique.test.tp003.e.PingResource", """
            package dev.vertique.test.tp003.e;

            import jakarta.inject.Inject;
            import jakarta.ws.rs.GET;
            import jakarta.ws.rs.Path;

            @Path("/ping")
            public class PingResource {
                @Inject
                public PingResource() {}

                @GET
                public String ping() { return ""; }
            }
            """);

    /** TP-003 (e)'s first {@code @NoAutoWire} application: an invalid path plus {@code @RolesAllowed}. */
    private static final JavaFileObject TP003_RESTRICTED_APPLICATION =
            SourceFiles.inline("dev.vertique.test.tp003.e.RestrictedApplication", """
            package dev.vertique.test.tp003.e;

            import dev.vertique.codegen.NoAutoWire;
            import jakarta.annotation.security.RolesAllowed;
            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @NoAutoWire
            @RolesAllowed("admin")
            @ApplicationPath("/api/*/v1")
            public class RestrictedApplication extends Application {}
            """);

    /** TP-003 (e)'s second {@code @NoAutoWire} application: the ported-Jakarta escape, no path at all. */
    private static final JavaFileObject TP003_LEGACY_APPLICATION =
            SourceFiles.inline("dev.vertique.test.tp003.e.LegacyApplication", """
            package dev.vertique.test.tp003.e;

            import dev.vertique.codegen.NoAutoWire;
            import jakarta.ws.rs.core.Application;

            @NoAutoWire
            public class LegacyApplication extends Application {}
            """);

    @Test
    @DisplayName("TP-003 — registration notes, the @NoAutoWire warning, and autoWire=false warnings")
    void autoWireDisabledWarnsPerApplication() {
        assertAll(
                "TP-003 (a)-(e)",
                () -> tp003RowA(),
                () -> tp003RowB(),
                () -> tp003RowC(),
                () -> tp003RowD(),
                () -> tp003RowE());
    }

    private void tp003RowA() {
        var result =
                ProcessorTestHarness.run(new JaxRsPipelineProcessor(), TP003_AB_SOURCES.toArray(new JavaFileObject[0]));
        logDiagnostics("TP-003 (a)", result);
        result.assertSuccess();
        assertRegistrationNote(result, "OneApplication", "/api/one");
        assertRegistrationNote(result, "TwoApplication", "/api/two");
        result.assertGeneratedSourceDoesNotContain(TP003_AB_MODULE, "ManualApplication.class");
        assertTrue(
                messagesOf(result.compilation().notes()).stream().noneMatch(m -> m.contains("ManualApplication")),
                () -> "Expected no registration NOTE for the @NoAutoWire application." + diagnosticsSummary(result));
        assertEquals(
                1,
                noAutoWireWarningsNaming(result, "ManualApplication"),
                () -> "Expected exactly one @NoAutoWire WARNING naming ManualApplication."
                        + diagnosticsSummary(result));
    }

    private void tp003RowB() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                Map.of("vertique.codegen.autoWire", "false"),
                TP003_AB_SOURCES.toArray(new JavaFileObject[0]));
        logDiagnostics("TP-003 (b)", result);
        result.assertSuccess();
        assertTrue(
                result.compilation().notes().isEmpty(),
                () -> "Expected no registration NOTE under autoWire=false." + diagnosticsSummary(result));
        assertFalse(
                moduleWritten(result, TP003_AB_MODULE),
                () -> "Expected no module written under autoWire=false." + diagnosticsSummary(result));
        List<String> autoWireWarnings = messagesOf(result.compilation().warnings()).stream()
                .filter(m -> m.contains("autoWire=false"))
                .toList();
        assertEquals(
                2,
                autoWireWarnings.size(),
                () -> "Expected exactly one autoWire=false WARNING per eligible application."
                        + diagnosticsSummary(result));
        assertTrue(
                autoWireWarnings.stream().anyMatch(m -> m.contains("OneApplication") && m.contains("default")),
                () -> "Expected an autoWire=false WARNING naming OneApplication and the default mount."
                        + diagnosticsSummary(result));
        assertTrue(
                autoWireWarnings.stream().anyMatch(m -> m.contains("TwoApplication") && m.contains("default")),
                () -> "Expected an autoWire=false WARNING naming TwoApplication and the default mount."
                        + diagnosticsSummary(result));
        assertTrue(
                autoWireWarnings.stream().noneMatch(m -> m.contains("ManualApplication")),
                () -> "No autoWire=false WARNING may name the @NoAutoWire application." + diagnosticsSummary(result));
        assertEquals(
                1,
                noAutoWireWarningsNaming(result, "ManualApplication"),
                () -> "Expected exactly one @NoAutoWire WARNING naming ManualApplication."
                        + diagnosticsSummary(result));
    }

    private void tp003RowC() {
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), TP003_PORTED_API);
        logDiagnostics("TP-003 (c)", result);
        result.assertSuccess();
        assertRegistrationNote(result, "PortedApi", "/api");
    }

    private void tp003RowD() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                Map.of("vertique.codegen.autoWire", "false"),
                TP003_UNANNOTATED_APPLICATION);
        logDiagnostics("TP-003 (d)", result);
        assertFailsNamingClass(result, "UnannotatedApplication");
    }

    private void tp003RowE() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                TP003_PING_RESOURCE,
                TP003_RESTRICTED_APPLICATION,
                TP003_LEGACY_APPLICATION);
        logDiagnostics("TP-003 (e)", result);
        result.assertSuccess();
        long errorCount = result.compilation().diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .count();
        assertEquals(0, errorCount, "TP-003 (e) must compile with no error" + diagnosticsSummary(result));
        result.assertGeneratedSourceDoesNotContain(TP003_E_MODULE, "RestrictedApplication.class");
        result.assertGeneratedSourceDoesNotContain(TP003_E_MODULE, "LegacyApplication.class");
        assertEquals(
                1,
                noAutoWireWarningsNaming(result, "RestrictedApplication"),
                () -> "Expected exactly one @NoAutoWire WARNING naming RestrictedApplication."
                        + diagnosticsSummary(result));
        assertEquals(
                1,
                noAutoWireWarningsNaming(result, "LegacyApplication"),
                () -> "Expected exactly one @NoAutoWire WARNING naming LegacyApplication."
                        + diagnosticsSummary(result));
    }

    // -----------------------------------------------------------------------------------------
    // TP-004 — application paths normalize, and the annotation is required
    // -----------------------------------------------------------------------------------------

    private static final JavaFileObject TP004_SLASH_SUFFIX_APPLICATION =
            SourceFiles.inline("dev.vertique.test.tp004.slashsuffix.SlashSuffixApplication", """
            package dev.vertique.test.tp004.slashsuffix;

            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @ApplicationPath("/api/public/")
            public class SlashSuffixApplication extends Application {
                public SlashSuffixApplication() {}
            }
            """);

    private static final JavaFileObject TP004_WILDCARD_APPLICATION =
            SourceFiles.inline("dev.vertique.test.tp004.wildcard.WildcardApplication", """
            package dev.vertique.test.tp004.wildcard;

            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @ApplicationPath("/api/*")
            public class WildcardApplication extends Application {
                public WildcardApplication() {}
            }
            """);

    private static final JavaFileObject TP004_NO_LEADING_SLASH_APPLICATION =
            SourceFiles.inline("dev.vertique.test.tp004.noleadingslash.NoLeadingSlashApplication", """
            package dev.vertique.test.tp004.noleadingslash;

            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @ApplicationPath("api")
            public class NoLeadingSlashApplication extends Application {
                public NoLeadingSlashApplication() {}
            }
            """);

    private static final JavaFileObject TP004_EMPTY_PATH_APPLICATION =
            SourceFiles.inline("dev.vertique.test.tp004.emptypath.EmptyPathApplication", """
            package dev.vertique.test.tp004.emptypath;

            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @ApplicationPath("")
            public class EmptyPathApplication extends Application {
                public EmptyPathApplication() {}
            }
            """);

    private static final JavaFileObject TP004_ROOT_PATH_APPLICATION =
            SourceFiles.inline("dev.vertique.test.tp004.rootpath.RootPathApplication", """
            package dev.vertique.test.tp004.rootpath;

            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @ApplicationPath("/")
            public class RootPathApplication extends Application {
                public RootPathApplication() {}
            }
            """);

    private static final JavaFileObject TP004_BASE_APPLICATION =
            SourceFiles.inline("dev.vertique.test.tp004.inherited.BaseApplication", """
            package dev.vertique.test.tp004.inherited;

            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @ApplicationPath("/api/inherited/")
            public abstract class BaseApplication extends Application {}
            """);

    private static final JavaFileObject TP004_INHERITED_PATH_APPLICATION =
            SourceFiles.inline("dev.vertique.test.tp004.inherited.InheritedPathApplication", """
            package dev.vertique.test.tp004.inherited;

            public class InheritedPathApplication extends BaseApplication {
                public InheritedPathApplication() {}
            }
            """);

    private static final JavaFileObject TP004_NO_PATH_APPLICATION =
            SourceFiles.inline("dev.vertique.test.tp004.missing.NoPathApplication", """
            package dev.vertique.test.tp004.missing;

            import jakarta.ws.rs.core.Application;

            public class NoPathApplication extends Application {
                public NoPathApplication() {}
            }
            """);

    /**
     * One TP-004 row. {@code expectedPath == null} marks the missing-annotation case, which expects
     * compilation to fail naming {@code applicationSimpleName} rather than a normalized literal.
     */
    private record PathCase(
            String label,
            List<JavaFileObject> sources,
            String moduleFqn,
            String applicationSimpleName,
            String expectedPath) {}

    private static Stream<Arguments> pathCases() {
        List<PathCase> cases = List.of(
                new PathCase(
                        "/api/public/ -> /api/public",
                        List.of(TP004_SLASH_SUFFIX_APPLICATION),
                        "dev.vertique.test.tp004.slashsuffix.GeneratedJaxRsResourcesModule",
                        "SlashSuffixApplication",
                        "/api/public"),
                new PathCase(
                        "/api/* -> /api",
                        List.of(TP004_WILDCARD_APPLICATION),
                        "dev.vertique.test.tp004.wildcard.GeneratedJaxRsResourcesModule",
                        "WildcardApplication",
                        "/api"),
                new PathCase(
                        "api -> /api",
                        List.of(TP004_NO_LEADING_SLASH_APPLICATION),
                        "dev.vertique.test.tp004.noleadingslash.GeneratedJaxRsResourcesModule",
                        "NoLeadingSlashApplication",
                        "/api"),
                new PathCase(
                        "\"\" -> /",
                        List.of(TP004_EMPTY_PATH_APPLICATION),
                        "dev.vertique.test.tp004.emptypath.GeneratedJaxRsResourcesModule",
                        "EmptyPathApplication",
                        "/"),
                new PathCase(
                        "/ -> /",
                        List.of(TP004_ROOT_PATH_APPLICATION),
                        "dev.vertique.test.tp004.rootpath.GeneratedJaxRsResourcesModule",
                        "RootPathApplication",
                        "/"),
                new PathCase(
                        "inherited from superclass -> /api/inherited",
                        List.of(TP004_BASE_APPLICATION, TP004_INHERITED_PATH_APPLICATION),
                        "dev.vertique.test.tp004.inherited.GeneratedJaxRsResourcesModule",
                        "InheritedPathApplication",
                        "/api/inherited"),
                new PathCase(
                        "missing annotation -> compile error naming the class",
                        List.of(TP004_NO_PATH_APPLICATION),
                        "dev.vertique.test.tp004.missing.GeneratedJaxRsResourcesModule",
                        "NoPathApplication",
                        null));
        return cases.stream().map(c -> Arguments.of(c.label(), c));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pathCases")
    @DisplayName("TP-004 — application paths normalize, and the annotation is required")
    void applicationPathsNormalize(String label, PathCase testCase) {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), testCase.sources().toArray(new JavaFileObject[0]));
        logDiagnostics("TP-004 " + label, result);
        if (testCase.expectedPath() == null) {
            assertFailsNamingClass(result, testCase.applicationSimpleName());
            result.assertErrorMessage("@ApplicationPath(\"/\")");
            return;
        }
        result.assertSuccess();
        String methodName = Introspector.decapitalize(testCase.applicationSimpleName()) + "Registration";
        assertRegistrationMethod(
                result,
                testCase.moduleFqn(),
                methodName,
                testCase.applicationSimpleName(),
                false,
                testCase.expectedPath(),
                "true",
                testCase.applicationSimpleName() + "::new");
    }

    // -----------------------------------------------------------------------------------------
    // TP-007 — the module's package stays with the resources when an application is added
    // -----------------------------------------------------------------------------------------

    private static final String TP007_RESOURCE_MODULE = "com.acme.resource.GeneratedJaxRsResourcesModule";
    private static final String TP007_APP_MODULE = "com.acme.app.GeneratedJaxRsResourcesModule";

    private static final JavaFileObject TP007_ITEM_RESOURCE = SourceFiles.inline("com.acme.resource.ItemResource", """
            package com.acme.resource;

            import jakarta.inject.Inject;
            import jakarta.ws.rs.GET;
            import jakarta.ws.rs.Path;

            @Path("/items")
            public class ItemResource {
                @Inject
                public ItemResource() {}

                @GET
                public String list() { return ""; }
            }
            """);

    private static final JavaFileObject TP007_ROOT_APPLICATION = SourceFiles.inline("com.acme.RootApplication", """
            package com.acme;

            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @ApplicationPath("/api")
            public class RootApplication extends Application {
                public RootApplication() {}
            }
            """);

    private static final JavaFileObject TP007_ONLY_APPLICATION =
            SourceFiles.inline("com.acme.app.OnlyApplication", """
            package com.acme.app;

            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @ApplicationPath("/mgmt")
            public class OnlyApplication extends Application {
                public OnlyApplication() {}
            }
            """);

    @Test
    @DisplayName("TP-007 — the module's package stays with the resources when an application is added")
    void packageStaysWithResourcesWhenApplicationAdded() {
        assertAll(
                "TP-007 (a) and (b)", () -> tp007ResourcesAndApplicationFixture(), () -> tp007ApplicationOnlyFixture());
    }

    private void tp007ResourcesAndApplicationFixture() {
        var result =
                ProcessorTestHarness.run(new JaxRsPipelineProcessor(), TP007_ITEM_RESOURCE, TP007_ROOT_APPLICATION);
        result.assertSuccess();
        assertTrue(
                moduleWritten(result, TP007_RESOURCE_MODULE),
                () -> "Expected the module at '" + TP007_RESOURCE_MODULE + "' (package resolved from resources)."
                        + diagnosticsSummary(result));
        assertRegistrationMethod(
                result,
                TP007_RESOURCE_MODULE,
                "rootApplicationRegistration",
                "RootApplication",
                false,
                "/api",
                "true",
                "RootApplication::new");
    }

    private void tp007ApplicationOnlyFixture() {
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), TP007_ONLY_APPLICATION);
        result.assertSuccess();
        assertTrue(
                moduleWritten(result, TP007_APP_MODULE),
                () -> "Expected the module at '" + TP007_APP_MODULE
                        + "' (package resolved from the applications-only unit)." + diagnosticsSummary(result));
    }

    // -----------------------------------------------------------------------------------------
    // TP-008 — catalog entries and presence-gated bindings are emitted
    // -----------------------------------------------------------------------------------------

    private static final String TP008_MODULE = "dev.vertique.test.tp008.GeneratedJaxRsResourcesModule";

    private static final JavaFileObject TP008_CATALOG_RESOURCE =
            SourceFiles.inline("dev.vertique.test.tp008.CatalogResource", """
            package dev.vertique.test.tp008;

            import jakarta.inject.Inject;
            import jakarta.ws.rs.GET;
            import jakarta.ws.rs.Path;

            @Path("/catalog")
            public class CatalogResource {
                @Inject
                public CatalogResource() {}

                @GET
                public String catalog() { return ""; }
            }
            """);

    private static final JavaFileObject TP008_BETA_RESOURCE =
            SourceFiles.inline("dev.vertique.test.tp008.BetaResource", """
            package dev.vertique.test.tp008;

            import dev.vertique.codegen.ConditionalOnProperty;
            import jakarta.inject.Inject;
            import jakarta.ws.rs.GET;
            import jakarta.ws.rs.Path;

            @Path("/beta")
            @ConditionalOnProperty(name = "tp008.betaResource.enabled")
            public class BetaResource {
                @Inject
                public BetaResource() {}

                @GET
                public String beta() { return ""; }
            }
            """);

    /** Expected C-GEN shape (mirroring T002's hand-written {@code unita} module) for the plain resource. */
    private static final String TP008_CATALOG_BINDING = """
            @Provides
            @ElementsIntoSet
            @JaxRsResources
            static Set<Object> catalogResourceBinding(
                    @VertxConfig JsonObject config,
                    Set<GeneratedJaxRsApplicationRegistration> applications,
                    Provider<CatalogResource> provider) {
                return applications.isEmpty() ? Set.of(provider.get()) : Set.of();
            }
            """;

    private static final String TP008_CATALOG_ENTRY = """
            @Provides
            @IntoSet
            static GeneratedJaxRsResourceEntry catalogResourceEntry(
                    @VertxConfig JsonObject config, Provider<CatalogResource> provider) {
                return GeneratedJaxRsResourceEntry.of(CatalogResource.class, true, provider);
            }
            """;

    /** Expected C-GEN shape for the {@code @ConditionalOnProperty} resource, mirroring {@code unita}'s DisabledResource. */
    private static final String TP008_BETA_BINDING = """
            @Provides
            @ElementsIntoSet
            @JaxRsResources
            static Set<Object> betaResourceBinding(
                    @VertxConfig JsonObject config,
                    Set<GeneratedJaxRsApplicationRegistration> applications,
                    Provider<BetaResource> provider) {
                return applications.isEmpty() && PropertyCondition.matchesAll(config, BETA_RESOURCE_BINDING_CONDITIONS)
                        ? Set.of(provider.get())
                        : Set.of();
            }
            """;

    private static final String TP008_BETA_ENTRY = """
            @Provides
            @IntoSet
            static GeneratedJaxRsResourceEntry betaResourceEntry(
                    @VertxConfig JsonObject config, Provider<BetaResource> provider) {
                return GeneratedJaxRsResourceEntry.of(
                        BetaResource.class,
                        PropertyCondition.matchesAll(config, BETA_RESOURCE_BINDING_CONDITIONS),
                        provider);
            }
            """;

    @Test
    @DisplayName("TP-008 — catalog entries and presence-gated bindings are emitted")
    void emitsCatalogEntryAndPresenceGatedBinding() {
        var result =
                ProcessorTestHarness.run(new JaxRsPipelineProcessor(), TP008_CATALOG_RESOURCE, TP008_BETA_RESOURCE);
        result.assertSuccess();
        String source = sourceOf(result, TP008_MODULE);
        logGeneratedSource("TP-008 module", source);

        assertAll(
                "TP-008 C-GEN shape",
                () -> assertContainsNormalized(
                        source, TP008_CATALOG_BINDING, "catalogResourceBinding in " + TP008_MODULE),
                () -> assertContainsNormalized(source, TP008_CATALOG_ENTRY, "catalogResourceEntry in " + TP008_MODULE),
                () -> assertContainsNormalized(source, TP008_BETA_BINDING, "betaResourceBinding in " + TP008_MODULE),
                () -> assertContainsNormalized(source, TP008_BETA_ENTRY, "betaResourceEntry in " + TP008_MODULE));
    }

    // -----------------------------------------------------------------------------------------
    // TP-009 — the accessibility check must include enclosing types (G-11)
    // -----------------------------------------------------------------------------------------

    /**
     * {@code Outer} is package-private, so {@code Api} — though itself {@code public} — is not
     * reachable from a different package: a caller outside {@code com.acme.web} cannot even name
     * {@code Outer.Api}. {@link JaxRsApplicationScanner#validate} must therefore inspect every
     * enclosing type's accessibility, not just the candidate's own modifiers (G-11).
     */
    private static final JavaFileObject TP009_OUTER = SourceFiles.inline("com.acme.web.Outer", """
            package com.acme.web;

            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            class Outer {

                @ApplicationPath("/api")
                public static class Api extends Application {
                    public Api() {}
                }
            }
            """);

    /** A public DI-eligible resource that resolves the generated module's package to {@code com.acme.web.resources}. */
    private static final JavaFileObject TP009_WEB_RESOURCE =
            SourceFiles.inline("com.acme.web.resources.WebResource", """
            package com.acme.web.resources;

            import jakarta.inject.Inject;
            import jakarta.ws.rs.GET;
            import jakarta.ws.rs.Path;

            @Path("/web")
            public class WebResource {
                @Inject
                public WebResource() {}

                @GET
                public String get() { return ""; }
            }
            """);

    @Test
    @DisplayName("TP-009 — the accessibility check must include enclosing types")
    void enclosingTypeMustBeAccessible() {
        assertAll(
                "TP-009 (a) and (b)",
                () -> tp009EnclosingTypeIsInaccessible(),
                () -> tp009EnclosingTypeIsInaccessibleAutoWireDisabled());
    }

    private void tp009EnclosingTypeIsInaccessible() {
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), TP009_OUTER, TP009_WEB_RESOURCE);
        logDiagnostics("TP-009 (a) enclosing type accessibility", result);
        assertFailsWithProcessorDiagnostic(result, "Api", "is not accessible from the generated module's package");
    }

    private void tp009EnclosingTypeIsInaccessibleAutoWireDisabled() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                Map.of("vertique.codegen.autoWire", "false"),
                TP009_OUTER,
                TP009_WEB_RESOURCE);
        logDiagnostics("TP-009 (b) enclosing type accessibility, autoWire=false", result);
        assertFailsWithProcessorDiagnostic(result, "Api", "is not accessible from the generated module's package");
    }

    // -----------------------------------------------------------------------------------------
    // TP-010 — autoWire=false must resolve disjoint packages without failing, and must still
    // validate (G-12)
    // -----------------------------------------------------------------------------------------

    private static final JavaFileObject TP010_ACME_RESOURCE = SourceFiles.inline("com.acme.api.AcmeResource", """
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

    private static final JavaFileObject TP010_PARTNER_RESOURCE =
            SourceFiles.inline("org.partner.api.PartnerResource", """
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

    private static final JavaFileObject TP010_VALID_APPLICATION = SourceFiles.inline("com.acme.app.Api", """
            package com.acme.app;

            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @ApplicationPath("/api")
            public class Api extends Application {
                public Api() {}
            }
            """);

    private static final JavaFileObject TP010_PRIVATE_CONSTRUCTOR_APPLICATION =
            SourceFiles.inline("com.acme.app.Api", """
            package com.acme.app;

            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @ApplicationPath("/api")
            public class Api extends Application {
                private Api() {}
            }
            """);

    @Test
    @DisplayName("TP-010 — autoWire=false must resolve disjoint packages without failing, and must still validate")
    void autoWireDisabledResolvesDisjointPackagesWithoutFailing() {
        assertAll(
                "TP-010 (a) and (b)",
                () -> tp010DisjointPackagesValidApplication(),
                () -> tp010DisjointPackagesInvalidConstructor());
    }

    private void tp010DisjointPackagesValidApplication() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                Map.of("vertique.codegen.autoWire", "false"),
                TP010_ACME_RESOURCE,
                TP010_PARTNER_RESOURCE,
                TP010_VALID_APPLICATION);
        logDiagnostics("TP-010 (a) disjoint packages, valid application", result);
        result.assertSuccess();
        assertTrue(
                result.compilation().errors().isEmpty(),
                () -> "Expected no error diagnostics under autoWire=false with disjoint packages."
                        + diagnosticsSummary(result));
        assertTrue(
                messagesOf(result.compilation().diagnostics()).stream().noneMatch(m -> m.contains("disjoint packages")),
                () -> "No diagnostic may report disjoint packages under autoWire=false." + diagnosticsSummary(result));
        List<String> autoWireWarnings = messagesOf(result.compilation().warnings()).stream()
                .filter(m -> m.contains("autoWire=false"))
                .toList();
        assertEquals(
                1,
                autoWireWarnings.size(),
                () -> "Expected exactly one autoWire=false WARNING naming the application."
                        + diagnosticsSummary(result));
        assertTrue(
                autoWireWarnings.stream().anyMatch(m -> m.contains("Api")),
                () -> "Expected the autoWire=false WARNING to name Api." + diagnosticsSummary(result));
    }

    private void tp010DisjointPackagesInvalidConstructor() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                Map.of("vertique.codegen.autoWire", "false"),
                TP010_ACME_RESOURCE,
                TP010_PARTNER_RESOURCE,
                TP010_PRIVATE_CONSTRUCTOR_APPLICATION);
        logDiagnostics("TP-010 (b) disjoint packages, invalid constructor", result);
        assertFailsWithProcessorDiagnostic(result, "Api", "has no usable constructor for application registration");
    }

    // -----------------------------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------------------------

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

    private static boolean moduleWritten(ProcessorTestHarness.Result result, String generatedFqn) {
        return result.compilation().generatedSourceFile(generatedFqn).isPresent();
    }

    private static String normalizeWhitespace(String text) {
        return text.replaceAll("\\s+", " ").replaceAll(" ?([(),<>]) ?", "$1").trim();
    }

    /**
     * Asserts that {@code actual} contains {@code expectedSnippet} once both are collapsed to
     * single-space-separated tokens with no space beside a parenthesis, comma, or angle bracket, so
     * JavaPoet's column-100 wrapping (which may break a line right after an opening parenthesis)
     * cannot break an otherwise correct match.
     */
    private static void assertContainsNormalized(String actual, String expectedSnippet, String context) {
        String normalizedActual = normalizeWhitespace(actual);
        String normalizedExpected = normalizeWhitespace(expectedSnippet);
        assertTrue(
                normalizedActual.contains(normalizedExpected),
                () -> "Expected " + context + " to contain (after whitespace normalization):\n" + normalizedExpected
                        + "\nActual (normalized):\n" + normalizedActual);
    }

    /**
     * Asserts that the generated module at {@code moduleFqn} declares a
     * {@code GeneratedJaxRsApplicationRegistration} provider method named {@code methodName} whose
     * parameter list and {@code of(...)} call match the C-GEN application-registration shape: an
     * {@code @VertxConfig JsonObject config} parameter, an optional {@code Provider<classRef>}
     * parameter (present only when the application is constructed through its {@code @Inject}
     * constructor), and a body calling {@code GeneratedJaxRsApplicationRegistration.of(classRef.class,
     * "expectedPath", conditionsArgument, factoryArgument)}.
     */
    private static void assertRegistrationMethod(
            ProcessorTestHarness.Result result,
            String moduleFqn,
            String methodName,
            String classRef,
            boolean hasProviderParam,
            String expectedPath,
            String conditionsArgument,
            String factoryArgument) {
        String source = sourceOf(result, moduleFqn);
        String params = hasProviderParam
                ? "@VertxConfig JsonObject config, Provider<" + classRef + "> provider"
                : "@VertxConfig JsonObject config";
        assertContainsNormalized(
                source,
                "static GeneratedJaxRsApplicationRegistration " + methodName + "(" + params + ") {",
                "the " + methodName + " signature in " + moduleFqn);
        assertContainsNormalized(
                source,
                "return GeneratedJaxRsApplicationRegistration.of(" + classRef + ".class, \"" + expectedPath + "\", "
                        + conditionsArgument + ", " + factoryArgument + ");",
                "the " + methodName + " body in " + moduleFqn);
    }

    /**
     * Asserts that {@code compilation().notes()} contains one NOTE diagnostic whose message names
     * both {@code applicationSimpleName} and {@code normalizedPath} — the C-SCAN registration note's
     * two required facts. {@link ProcessorTestHarness} has no note assertion of its own (T003
     * contract, Test conventions), so this is the one helper every TP-003 note check goes through.
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

    private static List<String> messagesOf(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream()
                .map(d -> d.getMessage(null))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Counts the {@code @NoAutoWire} WARNINGs naming {@code applicationSimpleName}: warnings that
     * name it and state that it is not registered or validated.
     */
    private static long noAutoWireWarningsNaming(ProcessorTestHarness.Result result, String applicationSimpleName) {
        return messagesOf(result.compilation().warnings()).stream()
                .filter(m -> m.contains(applicationSimpleName) && m.contains("not registered or validated"))
                .count();
    }

    private static void assertFailsNamingClass(ProcessorTestHarness.Result result, String applicationSimpleName) {
        result.assertFailed();
        result.assertErrorMessage(applicationSimpleName);
    }

    /**
     * Asserts that the compilation failed with the processor's OWN diagnostic: a single ERROR
     * diagnostic whose message contains both {@code applicationSimpleName} and
     * {@code expectedMessagePhrase} together (G-10).
     *
     * <p>This is stricter than {@link #assertFailsNamingClass}, which is satisfied by any error
     * mentioning the class — including a downstream {@code javac} error in code the emitter
     * generated, which would pass even if the processor's own validator never ran or never
     * reported. Requiring both facts in the SAME diagnostic message isolates the processor's own
     * validation from that false-positive path.
     */
    private static void assertFailsWithProcessorDiagnostic(
            ProcessorTestHarness.Result result, String applicationSimpleName, String expectedMessagePhrase) {
        result.assertFailed();
        boolean found = result.compilation().errors().stream()
                .map(d -> d.getMessage(null))
                .filter(Objects::nonNull)
                .anyMatch(msg -> msg.contains(applicationSimpleName) && msg.contains(expectedMessagePhrase));
        assertTrue(
                found,
                () -> "Expected an ERROR diagnostic naming '" + applicationSimpleName + "' and containing '"
                        + expectedMessagePhrase + "' in the SAME message, but none was found."
                        + diagnosticsSummary(result));
    }

    private static void logDiagnostics(String label, ProcessorTestHarness.Result result) {
        System.out.println("=== " + label + " diagnostics ===");
        result.compilation()
                .diagnostics()
                .forEach(d -> System.out.println("[" + d.getKind() + "] " + d.getMessage(null)));
    }

    private static void logGeneratedSource(String label, String source) {
        System.out.println("=== " + label + " ===");
        System.out.println(source);
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
