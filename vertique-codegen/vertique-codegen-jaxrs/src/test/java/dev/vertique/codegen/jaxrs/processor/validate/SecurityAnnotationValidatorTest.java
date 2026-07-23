// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.processor.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.jaxrs.JaxRsPipelineProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SecurityAnnotationValidator}.
 *
 * <p>Each test compiles a small fixture class via {@link ProcessorTestHarness} with a
 * {@link ValidatorProbe} that invokes the validator and checks the resulting error diagnostics.
 * The validator is exercised on both class-level and method-level annotations, independently.
 */
class SecurityAnnotationValidatorTest {

    // --- Class-level conflict tests ---

    @Nested
    @DisplayName("class-level conflicts")
    class ClassLevelConflicts {

        @Test
        @DisplayName("@DenyAll + @PermitAll at class level → ERROR")
        void denyAll_and_permitAll_classLevel() {
            var result =
                    ProcessorTestHarness.run(new ValidatorProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.DenyAll;
                            import jakarta.annotation.security.PermitAll;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @DenyAll @PermitAll
                            @Path("/res")
                            public class Res {
                                @GET public String get() { return ""; }
                            }
                            """));
            result.assertFailed();
            assertEquals(1, errorCount(result), "Expected exactly 1 class-level conflict error");
        }

        @Test
        @DisplayName("@DenyAll + @RolesAllowed at class level → ERROR")
        void denyAll_and_rolesAllowed_classLevel() {
            var result =
                    ProcessorTestHarness.run(new ValidatorProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.DenyAll;
                            import jakarta.annotation.security.RolesAllowed;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @DenyAll @RolesAllowed("admin")
                            @Path("/res")
                            public class Res {
                                @GET public String get() { return ""; }
                            }
                            """));
            result.assertFailed();
            assertEquals(1, errorCount(result), "Expected exactly 1 class-level conflict error");
        }

        @Test
        @DisplayName("@PermitAll + @RolesAllowed at class level → ERROR")
        void permitAll_and_rolesAllowed_classLevel() {
            var result =
                    ProcessorTestHarness.run(new ValidatorProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.PermitAll;
                            import jakarta.annotation.security.RolesAllowed;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @PermitAll @RolesAllowed("admin")
                            @Path("/res")
                            public class Res {
                                @GET public String get() { return ""; }
                            }
                            """));
            result.assertFailed();
            assertEquals(1, errorCount(result), "Expected exactly 1 class-level conflict error");
        }

        @Test
        @DisplayName("class conflict + clean method → still errors on class")
        void classConflict_cleanMethod_stillErrors() {
            var result =
                    ProcessorTestHarness.run(new ValidatorProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.DenyAll;
                            import jakarta.annotation.security.PermitAll;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @DenyAll @PermitAll
                            @Path("/res")
                            public class Res {
                                @GET public String get() { return ""; }
                            }
                            """));
            result.assertFailed();
            // Class error fires regardless of clean method
            assertEquals(1, errorCount(result));
        }
    }

    // --- Method-level conflict tests ---

    @Nested
    @DisplayName("method-level conflicts")
    class MethodLevelConflicts {

        @Test
        @DisplayName("@DenyAll + @PermitAll at method level → ERROR")
        void denyAll_and_permitAll_methodLevel() {
            var result =
                    ProcessorTestHarness.run(new ValidatorProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.DenyAll;
                            import jakarta.annotation.security.PermitAll;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/res")
                            public class Res {
                                @GET @DenyAll @PermitAll
                                public String get() { return ""; }
                            }
                            """));
            result.assertFailed();
            assertEquals(1, errorCount(result), "Expected 1 method-level conflict error");
        }

        @Test
        @DisplayName("@PermitAll + @RolesAllowed at method level → ERROR")
        void permitAll_and_rolesAllowed_methodLevel() {
            var result =
                    ProcessorTestHarness.run(new ValidatorProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.PermitAll;
                            import jakarta.annotation.security.RolesAllowed;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/res")
                            public class Res {
                                @GET @PermitAll @RolesAllowed("admin")
                                public String get() { return ""; }
                            }
                            """));
            result.assertFailed();
            assertEquals(1, errorCount(result), "Expected 1 method-level conflict error");
        }
    }

    // --- Independent levels ---

    @Nested
    @DisplayName("independent levels — both can fire")
    class IndependentLevels {

        @Test
        @DisplayName("class conflict + method conflict → two errors")
        void bothLevelsConflict_twoErrors() {
            var result =
                    ProcessorTestHarness.run(new ValidatorProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.DenyAll;
                            import jakarta.annotation.security.PermitAll;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @DenyAll @PermitAll
                            @Path("/res")
                            public class Res {
                                @GET @DenyAll @PermitAll
                                public String get() { return ""; }
                            }
                            """));
            result.assertFailed();
            assertEquals(2, errorCount(result), "Expected one error per level");
        }
    }

    // --- Empty @RolesAllowed tests ---

    @Nested
    @DisplayName("empty @RolesAllowed — effective-policy resolution (mirrors runtime hasEmptyRolesAllowed)")
    class EmptyRolesAllowed {

        @Test
        @DisplayName(
                "empty @RolesAllowed at class level, no method-level override → ERROR on method (effective policy)")
        void emptyRolesAllowed_classLevel_noMethodOverride_errorOnMethod() {
            // Runtime: hasEmptyRolesAllowed returns true for this method — class-level @RolesAllowed({})
            // is the effective policy because the method has no security annotation.
            // APT must also reject — error is reported on the method, not the class.
            var result =
                    ProcessorTestHarness.run(new ValidatorProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.RolesAllowed;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @RolesAllowed({})
                            @Path("/res")
                            public class Res {
                                @GET public String get() { return ""; }
                            }
                            """));
            result.assertFailed();
            result.assertErrorMessage("empty value array");
        }

        @Test
        @DisplayName("empty @RolesAllowed at class level + @PermitAll on method → no error (class-level ignored)")
        void emptyRolesAllowed_classLevel_methodPermitAll_noError() {
            // Runtime: hasEmptyRolesAllowed returns false — method declares @PermitAll, so the
            // class-level @RolesAllowed({}) is ignored. APT must accept (no error).
            var result =
                    ProcessorTestHarness.run(new ValidatorProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.PermitAll;
                            import jakarta.annotation.security.RolesAllowed;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @RolesAllowed({})
                            @Path("/res")
                            public class Res {
                                @GET @PermitAll public String get() { return ""; }
                            }
                            """));
            result.assertSuccess();
            assertEquals(0, errorCount(result));
        }

        @Test
        @DisplayName("empty @RolesAllowed at class level + @DenyAll on method → no error (class-level ignored)")
        void emptyRolesAllowed_classLevel_methodDenyAll_noError() {
            // Runtime: hasEmptyRolesAllowed returns false — method declares @DenyAll, so the
            // class-level @RolesAllowed({}) is ignored. APT must accept (no error).
            var result =
                    ProcessorTestHarness.run(new ValidatorProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.DenyAll;
                            import jakarta.annotation.security.RolesAllowed;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @RolesAllowed({})
                            @Path("/res")
                            public class Res {
                                @GET @DenyAll public String get() { return ""; }
                            }
                            """));
            result.assertSuccess();
            assertEquals(0, errorCount(result));
        }

        @Test
        @DisplayName("empty @RolesAllowed at class level + @RolesAllowed(\"user\") on method → no error")
        void emptyRolesAllowed_classLevel_methodNonEmptyRoles_noError() {
            // Runtime: hasEmptyRolesAllowed returns false — method's own @RolesAllowed("user")
            // is non-empty. APT must accept (no error).
            var result =
                    ProcessorTestHarness.run(new ValidatorProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.RolesAllowed;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @RolesAllowed({})
                            @Path("/res")
                            public class Res {
                                @GET @RolesAllowed("user") public String get() { return ""; }
                            }
                            """));
            result.assertSuccess();
            assertEquals(0, errorCount(result));
        }

        @Test
        @DisplayName("empty @RolesAllowed at method level → ERROR (regardless of class-level)")
        void emptyRolesAllowed_methodLevel() {
            // Runtime: hasEmptyRolesAllowed returns true — method-level @RolesAllowed({}) is empty.
            var result =
                    ProcessorTestHarness.run(new ValidatorProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.RolesAllowed;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/res")
                            public class Res {
                                @GET @RolesAllowed({})
                                public String get() { return ""; }
                            }
                            """));
            result.assertFailed();
            result.assertErrorMessage("@RolesAllowed at method-level has empty value array");
        }

        @Test
        @DisplayName("non-empty @RolesAllowed at class level, no method override → no error")
        void nonEmptyRolesAllowed_classLevel_noError() {
            var result =
                    ProcessorTestHarness.run(new ValidatorProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.RolesAllowed;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @RolesAllowed("admin")
                            @Path("/res")
                            public class Res {
                                @GET public String get() { return ""; }
                            }
                            """));
            result.assertSuccess();
            assertEquals(0, errorCount(result));
        }
    }

    // --- Short-circuit after conflict (Fix C) ---

    @Nested
    @DisplayName("short-circuit after conflict — exactly one error emitted (Fix C)")
    class ShortCircuitAfterConflict {

        @Test
        @DisplayName("@PermitAll + @RolesAllowed({}) at method level → exactly ONE error (conflict), not two")
        void permitAllAndEmptyRolesAllowed_exactlyOneError() {
            // Fix C bug: before the fix, both a conflict error AND an empty-@RolesAllowed error
            // were emitted for this method. The runtime ResourceScanner continues after the first
            // violation, so only the conflict is reported.
            var result =
                    ProcessorTestHarness.run(new ValidatorProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.PermitAll;
                            import jakarta.annotation.security.RolesAllowed;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/res")
                            public class Res {
                                @GET @PermitAll @RolesAllowed({})
                                public String get() { return ""; }
                            }
                            """));
            result.assertFailed();
            assertEquals(1, errorCount(result), "Expected exactly 1 conflict error, not 2");
        }

        @Test
        @DisplayName(
                "class-level @DenyAll + @RolesAllowed({}) + method no annotation → class conflict only, NOT empty-roles fallback")
        void classConflict_methodNoAnnotation_onlyClassConflict() {
            // Fix C: a class-level conflict (already reported by validateClassLevel) should NOT
            // also trigger the method-level empty-@RolesAllowed fallback, because the class
            // conflict means the class-level @RolesAllowed is already invalid/inapplicable.
            // Only the class-level conflict error should fire; no method-level errors.
            var result =
                    ProcessorTestHarness.run(new ValidatorProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.DenyAll;
                            import jakarta.annotation.security.RolesAllowed;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @DenyAll @RolesAllowed({})
                            @Path("/res")
                            public class Res {
                                @GET public String get() { return ""; }
                            }
                            """));
            result.assertFailed();
            assertEquals(1, errorCount(result), "Expected exactly 1 class-level conflict error");
        }

        @Test
        @DisplayName("class-level @DenyAll + @RolesAllowed({}) + method-level @RolesAllowed({}) → class conflict only")
        void classConflict_methodEmptyRoles_onlyClassConflict() {
            // Round-3 Codex finding: when the class has a conflict AND the method declares its own
            // empty @RolesAllowed, runtime ResourceScanner continue's on the class-level conflict
            // and never invokes hasEmptyRolesAllowed for that method. The validator must do the same.
            var result =
                    ProcessorTestHarness.run(new ValidatorProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.DenyAll;
                            import jakarta.annotation.security.RolesAllowed;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @DenyAll @RolesAllowed({})
                            @Path("/res")
                            public class Res {
                                @GET @RolesAllowed({})
                                public String get() { return ""; }
                            }
                            """));
            result.assertFailed();
            assertEquals(1, errorCount(result), "Expected exactly 1 class-level conflict error, not 2");
        }
    }

    // --- Clean combination tests ---

    @Nested
    @DisplayName("valid combinations — no errors")
    class ValidCombinations {

        @Test
        @DisplayName("@DenyAll alone at class level → no error")
        void denyAllAlone_noError() {
            var result =
                    ProcessorTestHarness.run(new ValidatorProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.DenyAll;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @DenyAll
                            @Path("/res")
                            public class Res {
                                @GET public String get() { return ""; }
                            }
                            """));
            result.assertSuccess();
            assertEquals(0, errorCount(result));
        }

        @Test
        @DisplayName("no security annotations → no error")
        void noSecurityAnnotations_noError() {
            var result =
                    ProcessorTestHarness.run(new ValidatorProbe(), SourceFiles.inline("dev.vertique.test.Res", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/res")
                            public class Res {
                                @GET public String get() { return ""; }
                            }
                            """));
            result.assertSuccess();
            assertEquals(0, errorCount(result));
        }
    }

    // --- Class-level validation gated on verb-bearing methods ---

    @Nested
    @DisplayName("class-level validation gated on verb-bearing methods")
    class ClassLevelGatedOnVerbMethods {

        @Test
        @DisplayName("@DenyAll + @PermitAll at class level, no HTTP-verb methods → no error (runtime parity)")
        void classLevelConflict_noVerbMethods_noError() {
            // Runtime parity: ResourceScanner.scanResource only invokes
            // hasConflictingSecurityAnnotations inside the per-method loop, after
            // resolveHttpMethod returns non-null. A @Path class with no verb-annotated methods
            // (e.g., only helper methods) never enters that loop, so the class-level conflict is
            // silently ignored at runtime. The build must not flag it either.
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Foo", """
                                    package dev.vertique.test;

                                    import jakarta.annotation.security.DenyAll;
                                    import jakarta.annotation.security.PermitAll;
                                    import jakarta.ws.rs.Path;

                                    @DenyAll @PermitAll
                                    @Path("/foo")
                                    public class Foo {
                                        public String helper() { return ""; }
                                    }
                                    """));
            result.assertSuccess();
            assertEquals(
                    0,
                    errorCount(result),
                    "Expected no error — runtime never checks class-level security on a class with no verb methods");
        }

        @Test
        @DisplayName(
                "@DenyAll + @PermitAll at class level + at least one @GET method → class-level conflict error (regression)")
        void classLevelConflict_withVerbMethod_errors() {
            // Regression check: with at least one verb-bearing method the class-level conflict must
            // still be reported — existing behaviour preserved.
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.Foo", """
                                    package dev.vertique.test;

                                    import jakarta.annotation.security.DenyAll;
                                    import jakarta.annotation.security.PermitAll;
                                    import jakarta.ws.rs.GET;
                                    import jakarta.ws.rs.Path;

                                    @DenyAll @PermitAll
                                    @Path("/foo")
                                    public class Foo {
                                        @GET public String get() { return ""; }
                                    }
                                    """));
            result.assertFailed();
            assertEquals(1, errorCount(result), "Expected exactly 1 class-level conflict error");
        }
    }

    // --- Helper ---

    private int errorCount(ProcessorTestHarness.Result result) {
        return (int) result.compilation().diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .filter(d -> {
                    String msg = d.getMessage(null);
                    return msg != null
                            && (msg.contains("Conflicting security annotations")
                                    || msg.contains("@RolesAllowed at")
                                    || msg.contains("empty value array"));
                })
                .count();
    }

    // --- Probe processor ---

    /**
     * Probe processor that runs {@link SecurityAnnotationValidator} against every
     * {@code @Path}-annotated class and each of its methods.
     */
    static final class ValidatorProbe extends AbstractProcessor {

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("jakarta.ws.rs.Path");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
            CodegenContext ctx = new CodegenContext(processingEnv);
            SecurityAnnotationValidator validator = new SecurityAnnotationValidator(ctx);

            for (Element e : env.getElementsAnnotatedWith(
                    processingEnv.getElementUtils().getTypeElement("jakarta.ws.rs.Path"))) {
                if (!(e instanceof TypeElement resource)) continue;
                if (resource.getKind() == ElementKind.INTERFACE) continue;

                validator.validateClassLevel(resource);

                for (Element enclosed : resource.getEnclosedElements()) {
                    if (enclosed.getKind() != ElementKind.METHOD) continue;
                    validator.validateMethodLevel(resource, (ExecutableElement) enclosed);
                }
            }
            return false;
        }
    }
}
