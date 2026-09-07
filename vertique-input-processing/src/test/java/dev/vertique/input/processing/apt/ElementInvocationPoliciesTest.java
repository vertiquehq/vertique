// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.input.processing.InvocationPolicyConflictException;
import dev.vertique.input.processing.PolicyAxis;
import dev.vertique.input.processing.apt.ElementInvocationPolicies.ElementPolicyChains;
import dev.vertique.input.processing.testkit.A;
import dev.vertique.input.processing.testkit.InvocationPolicyScenarios;
import dev.vertique.input.processing.testkit.InvocationPolicyScenarios.Outcome;
import dev.vertique.input.processing.testkit.InvocationPolicyScenarios.Row;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * TP-001 (T018, issue #379): {@code apt.ElementInvocationPolicies.resolveRoute}/{@code
 * resolveParameter} resolve the same IP-01..IP-19 matrix as {@code ReflectiveInvocationPolicies}
 * (T016 TP-002), but driven from real {@code javax.lang.model} elements obtained by compiling one
 * inline source per row through a probe annotation processor ({@link Probe}), instead of reflection.
 *
 * <p>Each row's carrier hierarchy is re-declared here as compilable source text (own package per
 * row, {@code dev.vertique.input.processing.apt.fixture.ipNN}), reusing the exact simple class and
 * method names of {@code InvocationPolicyScenarios}'s reflection carriers (e.g. {@code Ip14.bar},
 * {@code IFoo}/{@code FooImpl}) so the adapter's site/describe strings are byte-identical to the
 * reflective adapter's (contract, "Production design"). The row's expected outcome — {@link
 * Outcome.Chain} or {@link Outcome.Conflict} — is reused unchanged from {@link
 * InvocationPolicyScenarios#rows()}; only the source form differs from {@code
 * ReflectiveInvocationPoliciesTest}.
 *
 * <p>{@link Probe} calls {@code new ElementInvocationPolicies(elements, types).resolveRoute(...)}
 * then {@code resolveParameter(...)} and reports the outcome of each stage as a compiler {@link
 * Diagnostic.Kind#NOTE} (chain contents as canonical type names, or the caught {@link
 * InvocationPolicyConflictException}'s axis and message) — {@code NOTE} never fails the compilation,
 * so {@link ProcessorTestHarness.Result#assertSuccess()} holds for every row including the conflict
 * ones; TP-001 tests the adapter API directly, not the compile-error behavior (that is TP-002/TP-003
 * in {@code codegen-jaxrs}, where the caller turns the same exception into a real diagnostic).
 *
 * <p>Only IP-14 (same-site) and IP-17 (real {@code IFoo}/{@code FooImpl} names) have a contract-frozen
 * exact message; IP-15 (parameter-level) and IP-19 (type-level) assert the axis and that both real
 * declaration sites appear in the message, mirroring {@code ReflectiveInvocationPoliciesTest}'s own
 * ruling (T016 L01) that the contract does not pin an exact literal for those element kinds.
 *
 * <p>Sensitivity (contract-required): skipping the overridden-method walk (not using {@code
 * Elements.overrides}/{@code getAllMembers} inherited-method semantics) would make IP-09 and IP-12
 * resolve to empty chains instead of {@code [B]}/{@code [C]} — {@link
 * #resolvesRouteAndParameterFromCompiledElements} would fail for those rows.
 */
class ElementInvocationPoliciesTest {

    /** Field separator used by {@link Probe}'s NOTE-encoded results; not expected in any payload. */
    private static final String FIELD_SEP = "\u0001";

    /** List-item separator for chain FQNs within a single field. */
    private static final String LIST_SEP = "\u0002";

    @ParameterizedTest(name = "{0}")
    @DisplayName("resolveRoute/resolveParameter match the matrix over compiled elements")
    @MethodSource("dev.vertique.input.processing.testkit.InvocationPolicyScenarios#rows")
    void resolvesRouteAndParameterFromCompiledElements(Row row) {
        RowSource rowSource = rowSource(row.id());
        ProcessorTestHarness.Result result = ProcessorTestHarness.run(
                new Probe(),
                Map.of(Probe.OPTION_OWNER_FQN, rowSource.ownerFqn()),
                rowSource.sources().toArray(JavaFileObject[]::new));

        // A conflict is caught and reported as a NOTE by the probe — the compilation itself always
        // succeeds for TP-001 (see class javadoc).
        result.assertSuccess();

        String routeNote = firstNote(result, "ROUTE", row.id());
        String[] routeFields = routeNote.split(FIELD_SEP, 4);
        if (row.sanitize().route() instanceof Outcome.Conflict conflict) {
            assertConflictNote(row, conflict, routeFields);
            return;
        }
        assertEquals("OK", routeFields[1], row.id() + " route stage must resolve without conflict");
        assertEquals(
                expectedNames(((Outcome.Chain) row.canonicalize().route()).values()),
                routeFields[2],
                row.id() + " route canonicalizers");
        assertEquals(
                expectedNames(((Outcome.Chain) row.sanitize().route()).values()),
                routeFields[3],
                row.id() + " route sanitizers");

        String paramNote = firstNote(result, "PARAM", row.id());
        String[] paramFields = paramNote.split(FIELD_SEP, 4);
        if (row.sanitize().param() instanceof Outcome.Conflict conflict) {
            assertConflictNote(row, conflict, paramFields);
            return;
        }
        assertEquals("OK", paramFields[1], row.id() + " parameter stage must resolve without conflict");
        assertEquals(
                expectedNames(((Outcome.Chain) row.canonicalize().param()).values()),
                paramFields[2],
                row.id() + " parameter canonicalizers");
        assertEquals(
                expectedNames(((Outcome.Chain) row.sanitize().param()).values()),
                paramFields[3],
                row.id() + " parameter sanitizers");
    }

    /**
     * W-1 (T018 review): resolution-order parity with the reflective reference for the one shape the
     * IP matrix does not pin — a <em>direct</em> annotation on a farther site competing with a
     * <em>composed</em> one on a nearer site.
     *
     * <p>{@code AnnotationResolver.findMetaAnnotation(List, Class)} — the reference the reflective
     * adapter resolves its values through — scans the whole merged annotation list for a direct
     * instance first and only then walks meta-annotations, so an override annotated
     * {@code @ComposedSanitize} (meta {@code @Sanitize(B.class)}) over an interface method carrying a
     * direct {@code @Sanitize(A.class)} resolves to {@code [A.class]} at runtime. A site-major walk
     * (descending into each site's meta-annotations before testing the next site) would answer
     * {@code [B.class]} here; this case is what makes the two-pass order in
     * {@code ElementInvocationPolicies.findInSites} load-bearing.
     */
    @Test
    @DisplayName("a direct annotation on a farther site beats a composed one on a nearer site")
    void directAnnotationAnywhereBeatsComposedAnnotationOnANearerSite() {
        String pkg = "dev.vertique.input.processing.apt.fixture.directvscomposed";
        JavaFileObject iface = SourceFiles.inline(pkg + ".IDirect", """
                package %s;
                public interface IDirect {
                    @dev.vertique.core.sanitization.Sanitize(dev.vertique.input.processing.testkit.A.class)
                    void bar(String p);
                }
                """.formatted(pkg));
        JavaFileObject impl = SourceFiles.inline(pkg + ".DirectImpl", """
                package %s;
                public class DirectImpl implements IDirect {
                    @Override
                    @dev.vertique.input.processing.testkit.ComposedSanitize
                    public void bar(String p) {}
                }
                """.formatted(pkg));

        ProcessorTestHarness.Result result =
                ProcessorTestHarness.run(new Probe(), Map.of(Probe.OPTION_OWNER_FQN, pkg + ".DirectImpl"), iface, impl);
        result.assertSuccess();

        String label = "direct-beats-composed";
        String[] routeFields = firstNote(result, "ROUTE", label).split(FIELD_SEP, 4);
        assertEquals("OK", routeFields[1], label + " route stage must resolve without conflict");
        assertEquals("", routeFields[2], label + " route canonicalizers must be empty");
        assertEquals(
                expectedNames(List.of(A.class)),
                routeFields[3],
                label + " route sanitizers must come from the direct @Sanitize(A) on IDirect.bar,"
                        + " not from @ComposedSanitize's meta @Sanitize(B) on DirectImpl.bar");

        String[] paramFields = firstNote(result, "PARAM", label).split(FIELD_SEP, 4);
        assertEquals("OK", paramFields[1], label + " parameter stage must resolve without conflict");
        assertEquals(
                expectedNames(List.of(A.class)), paramFields[3], label + " parameter stage inherits the route chain");
    }

    /**
     * W-3 (T018 review): the composed-annotation walk's cycle bound is decisive, not decorative.
     *
     * <p>{@code CycA} is meta-annotated {@code @CycB} and {@code CycB} is meta-annotated
     * {@code @CycA}; a carrier method annotated {@code @CycA} alone declares no policy on either
     * axis, so resolving it forces the walk all the way through the cycle for all four annotation
     * FQNs. Without {@code findMetaRecursive}'s {@code visited} guard the processor recurses until it
     * throws {@link StackOverflowError}; with it, both stages resolve to empty chains, no conflict is
     * raised, and the compilation reports no error diagnostic.
     */
    @Test
    @DisplayName("a cyclic meta-annotation graph terminates with empty chains and no error")
    void cyclicMetaAnnotationGraphTerminatesWithoutRecursingForever() {
        String pkg = "dev.vertique.input.processing.apt.fixture.cycle";
        JavaFileObject cycA = SourceFiles.inline(pkg + ".CycA", """
                package %s;
                @CycB
                @java.lang.annotation.Target({
                        java.lang.annotation.ElementType.METHOD,
                        java.lang.annotation.ElementType.ANNOTATION_TYPE})
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface CycA {}
                """.formatted(pkg));
        JavaFileObject cycB = SourceFiles.inline(pkg + ".CycB", """
                package %s;
                @CycA
                @java.lang.annotation.Target({
                        java.lang.annotation.ElementType.METHOD,
                        java.lang.annotation.ElementType.ANNOTATION_TYPE})
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface CycB {}
                """.formatted(pkg));
        JavaFileObject carrier = SourceFiles.inline(pkg + ".CycCarrier", """
                package %s;
                public class CycCarrier {
                    @CycA
                    public void bar(String p) {}
                }
                """.formatted(pkg));

        ProcessorTestHarness.Result result = ProcessorTestHarness.run(
                new Probe(), Map.of(Probe.OPTION_OWNER_FQN, pkg + ".CycCarrier"), cycA, cycB, carrier);
        result.assertSuccess();

        String label = "cyclic-meta-annotations";
        assertTrue(
                result.compilation().diagnostics().stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR),
                label + " must produce no error diagnostic; diagnostics="
                        + result.compilation().diagnostics());

        String[] routeFields = firstNote(result, "ROUTE", label).split(FIELD_SEP, 4);
        assertEquals("OK", routeFields[1], label + " route stage must resolve without conflict");
        assertEquals("", routeFields[2], label + " route canonicalizers must be empty");
        assertEquals("", routeFields[3], label + " route sanitizers must be empty");

        String[] paramFields = firstNote(result, "PARAM", label).split(FIELD_SEP, 4);
        assertEquals("OK", paramFields[1], label + " parameter stage must resolve without conflict");
        assertEquals("", paramFields[2], label + " parameter canonicalizers must be empty");
        assertEquals("", paramFields[3], label + " parameter sanitizers must be empty");
    }

    /**
     * Asserts a {@code CONFLICT}-status note matches the row's expected {@link Outcome.Conflict},
     * mirroring {@code ReflectiveInvocationPoliciesTest#assertConflict} (T016): exact message for
     * IP-14/IP-17, axis plus both declaration sites for IP-15/IP-19.
     *
     * @param row      the scenario row
     * @param expected the row's expected conflict
     * @param fields   the note split on {@link #FIELD_SEP}: {@code [stage, "CONFLICT", axis, message]}
     */
    private void assertConflictNote(Row row, Outcome.Conflict expected, String[] fields) {
        assertEquals("CONFLICT", fields[1], row.id() + " must report a conflict");
        assertEquals(PolicyAxis.SANITIZE.name(), fields[2], row.id() + " conflict axis");
        String message = fields[3];
        switch (row.id()) {
            case "IP-14" ->
                assertEquals(
                        InvocationPolicyScenarios.expectedConflictMessage(
                                PolicyAxis.SANITIZE, "Ip14.bar", "Ip14.bar", "method Ip14.bar"),
                        message);
            case "IP-17" ->
                assertEquals(
                        InvocationPolicyScenarios.expectedConflictMessage(
                                PolicyAxis.SANITIZE, "IFoo.bar", "FooImpl.bar", "method FooImpl.bar"),
                        message);
            case "IP-15" -> assertTrue(message.contains("Ip15.bar"), "message should name Ip15.bar: " + message);
            case "IP-19" -> {
                assertTrue(message.contains("Ip19Base"), "message should name Ip19Base: " + message);
                assertTrue(message.contains("Ip19"), "message should name Ip19: " + message);
            }
            default -> throw new AssertionError("unexpected conflict row " + row.id());
        }
        // expected's own site strings are asserted structurally above via the row.id() switch,
        // matching InvocationPolicyScenarios's own IP-14/15/17/19 Outcome.Conflict data exactly.
        assertTrue(
                expected.elementDescription() != null
                        && !expected.elementDescription().isBlank(),
                row.id() + " scenario must carry a non-blank element description");
    }

    private static String firstNote(ProcessorTestHarness.Result result, String stage, String label) {
        return result.compilation().diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.NOTE)
                .map(d -> d.getMessage(null))
                .filter(msg -> msg != null && msg.startsWith(stage + FIELD_SEP))
                .findFirst()
                .orElseThrow(() -> new AssertionError(label + ": no " + stage
                        + " note diagnostic found; diagnostics="
                        + result.compilation().diagnostics()));
    }

    private static String expectedNames(List<Class<?>> values) {
        return values.stream().map(Class::getCanonicalName).collect(Collectors.joining(LIST_SEP));
    }

    // --- Row source fixtures ---

    /** One row's compiled source fixture(s) and the FQN of its most-derived (owner) type. */
    private record RowSource(List<JavaFileObject> sources, String ownerFqn) {}

    /**
     * Builds the inline source(s) and owner FQN for one IP-01..IP-19 row, re-declaring
     * {@link InvocationPolicyScenarios}'s reflection carriers as compilable source text in a
     * row-scoped package.
     *
     * @param rowId the scenario id (e.g. {@code "IP-14"})
     * @return the row's sources and owner FQN
     */
    private static RowSource rowSource(String rowId) {
        String pkg = "dev.vertique.input.processing.apt.fixture."
                + rowId.toLowerCase(Locale.ROOT).replace("-", "");
        return switch (rowId) {
            case "IP-01" -> single(pkg, "Ip01", """
                    package %s;
                    public class Ip01 {
                        public void bar(String p) {}
                    }
                    """);
            case "IP-02" -> single(pkg, "Ip02", """
                    package %s;
                    @dev.vertique.core.sanitization.Sanitize(dev.vertique.input.processing.testkit.A.class)
                    public class Ip02 {
                        public void bar(String p) {}
                    }
                    """);
            case "IP-03" -> single(pkg, "Ip03", """
                    package %s;
                    public class Ip03 {
                        @dev.vertique.core.sanitization.Sanitize(dev.vertique.input.processing.testkit.B.class)
                        public void bar(String p) {}
                    }
                    """);
            case "IP-04" -> single(pkg, "Ip04", """
                    package %s;
                    @dev.vertique.core.sanitization.Sanitize(dev.vertique.input.processing.testkit.A.class)
                    public class Ip04 {
                        @dev.vertique.core.sanitization.Sanitize(dev.vertique.input.processing.testkit.B.class)
                        public void bar(String p) {}
                    }
                    """);
            case "IP-05" -> single(pkg, "Ip05", """
                    package %s;
                    @dev.vertique.core.sanitization.Sanitize(dev.vertique.input.processing.testkit.A.class)
                    public class Ip05 {
                        @dev.vertique.core.sanitization.SkipSanitization
                        public void bar(String p) {}
                    }
                    """);
            case "IP-06" -> single(pkg, "Ip06", """
                    package %s;
                    public class Ip06 {
                        @dev.vertique.core.sanitization.SkipSanitization
                        public void bar(String p) {}
                    }
                    """);
            case "IP-07" -> single(pkg, "Ip07", """
                    package %s;
                    @dev.vertique.core.sanitization.Sanitize(dev.vertique.input.processing.testkit.A.class)
                    public class Ip07 {
                        public void bar(
                                @dev.vertique.core.sanitization.Sanitize(dev.vertique.input.processing.testkit.C.class) String p) {}
                    }
                    """);
            case "IP-08" -> single(pkg, "Ip08", """
                    package %s;
                    @dev.vertique.core.sanitization.Sanitize(dev.vertique.input.processing.testkit.A.class)
                    public class Ip08 {
                        public void bar(@dev.vertique.core.sanitization.SkipSanitization String p) {}
                    }
                    """);
            case "IP-09" ->
                new RowSource(
                        List.of(
                                SourceFiles.inline(pkg + ".Ip09Iface", """
                                    package %s;
                                    public interface Ip09Iface {
                                        @dev.vertique.core.sanitization.Sanitize(dev.vertique.input.processing.testkit.B.class)
                                        void bar(String p);
                                    }
                                    """.formatted(pkg)),
                                SourceFiles.inline(pkg + ".Ip09", """
                                    package %s;
                                    public class Ip09 implements Ip09Iface {
                                        @Override
                                        public void bar(String p) {}
                                    }
                                    """.formatted(pkg))),
                        pkg + ".Ip09");
            case "IP-10" ->
                new RowSource(
                        List.of(
                                SourceFiles.inline(pkg + ".Ip10Base", """
                                    package %s;
                                    @dev.vertique.core.sanitization.Sanitize(dev.vertique.input.processing.testkit.A.class)
                                    public class Ip10Base {
                                        public void bar(String p) {}
                                    }
                                    """.formatted(pkg)),
                                SourceFiles.inline(pkg + ".Ip10", """
                                    package %s;
                                    public class Ip10 extends Ip10Base {}
                                    """.formatted(pkg))),
                        pkg + ".Ip10");
            case "IP-11" -> single(pkg, "Ip11", """
                    package %s;
                    public class Ip11 {
                        @dev.vertique.input.processing.testkit.ComposedSanitize
                        public void bar(String p) {}
                    }
                    """);
            case "IP-12" ->
                new RowSource(
                        List.of(
                                SourceFiles.inline(pkg + ".Ip12Iface", """
                                    package %s;
                                    public interface Ip12Iface {
                                        void bar(
                                                @dev.vertique.core.sanitization.Sanitize(dev.vertique.input.processing.testkit.C.class) String p);
                                    }
                                    """.formatted(pkg)),
                                SourceFiles.inline(pkg + ".Ip12", """
                                    package %s;
                                    public class Ip12 implements Ip12Iface {
                                        @Override
                                        public void bar(String p) {}
                                    }
                                    """.formatted(pkg))),
                        pkg + ".Ip12");
            case "IP-13" -> single(pkg, "Ip13", """
                    package %s;
                    @dev.vertique.core.sanitization.Canonicalize(dev.vertique.input.processing.testkit.K.class)
                    public class Ip13 {
                        @dev.vertique.core.sanitization.Sanitize(dev.vertique.input.processing.testkit.B.class)
                        public void bar(String p) {}
                    }
                    """);
            case "IP-14" -> single(pkg, "Ip14", """
                    package %s;
                    public class Ip14 {
                        @dev.vertique.core.sanitization.Sanitize(dev.vertique.input.processing.testkit.B.class)
                        @dev.vertique.core.sanitization.SkipSanitization
                        public void bar(String p) {}
                    }
                    """);
            case "IP-15" -> single(pkg, "Ip15", """
                    package %s;
                    public class Ip15 {
                        public void bar(
                                @dev.vertique.core.sanitization.Sanitize(dev.vertique.input.processing.testkit.C.class)
                                @dev.vertique.core.sanitization.SkipSanitization String p) {}
                    }
                    """);
            case "IP-16" ->
                new RowSource(
                        List.of(
                                SourceFiles.inline(pkg + ".Ip16Iface", """
                                    package %s;
                                    public interface Ip16Iface {
                                        @dev.vertique.core.sanitization.SkipSanitization
                                        void bar(String p);
                                    }
                                    """.formatted(pkg)),
                                SourceFiles.inline(pkg + ".Ip16", """
                                    package %s;
                                    public class Ip16 implements Ip16Iface {
                                        @Override
                                        public void bar(String p) {}
                                    }
                                    """.formatted(pkg))),
                        pkg + ".Ip16");
            case "IP-17" ->
                new RowSource(
                        List.of(
                                SourceFiles.inline(pkg + ".IFoo", """
                                    package %s;
                                    public interface IFoo {
                                        @dev.vertique.core.sanitization.Sanitize(dev.vertique.input.processing.testkit.B.class)
                                        void bar(String p);
                                    }
                                    """.formatted(pkg)),
                                SourceFiles.inline(pkg + ".FooImpl", """
                                    package %s;
                                    public class FooImpl implements IFoo {
                                        @Override
                                        @dev.vertique.core.sanitization.SkipSanitization
                                        public void bar(String p) {}
                                    }
                                    """.formatted(pkg))),
                        pkg + ".FooImpl");
            case "IP-18" ->
                new RowSource(
                        List.of(
                                SourceFiles.inline(pkg + ".Ip18Base", """
                                    package %s;
                                    public class Ip18Base {
                                        @dev.vertique.core.sanitization.Sanitize(dev.vertique.input.processing.testkit.B.class)
                                        public void bar(String p) {}
                                    }
                                    """.formatted(pkg)),
                                SourceFiles.inline(pkg + ".Ip18", """
                                    package %s;
                                    public class Ip18 extends Ip18Base {
                                        @Override
                                        @dev.vertique.core.sanitization.Sanitize(dev.vertique.input.processing.testkit.A.class)
                                        public void bar(String p) {}
                                    }
                                    """.formatted(pkg))),
                        pkg + ".Ip18");
            case "IP-19" ->
                new RowSource(
                        List.of(
                                SourceFiles.inline(pkg + ".Ip19Base", """
                                    package %s;
                                    @dev.vertique.core.sanitization.SkipSanitization
                                    public class Ip19Base {
                                        public void bar(String p) {}
                                    }
                                    """.formatted(pkg)),
                                SourceFiles.inline(pkg + ".Ip19", """
                                    package %s;
                                    @dev.vertique.core.sanitization.Sanitize(dev.vertique.input.processing.testkit.A.class)
                                    public class Ip19 extends Ip19Base {}
                                    """.formatted(pkg))),
                        pkg + ".Ip19");
            default -> throw new IllegalArgumentException("no fixture registered for row " + rowId);
        };
    }

    private static RowSource single(String pkg, String simpleName, String body) {
        return new RowSource(
                List.of(SourceFiles.inline(pkg + "." + simpleName, body.formatted(pkg))), pkg + "." + simpleName);
    }

    // --- Probe processor ---

    /**
     * Locates the row's owner {@link TypeElement} (by the {@link #OPTION_OWNER_FQN} processor
     * option), resolves its {@code bar} member via {@link Elements#getAllMembers} (inherited-method
     * semantics — the merged view sees only the overriding declaration, exactly like IP-09/IP-17's
     * carriers), and drives {@link ElementInvocationPolicies#resolveRoute}/{@code resolveParameter}.
     * Each stage's outcome is reported as a {@link Diagnostic.Kind#NOTE} so it survives the
     * in-process compile-testing round trip without relying on same-classloader static-field capture.
     */
    @SupportedAnnotationTypes("*")
    @SupportedSourceVersion(SourceVersion.RELEASE_21)
    public static final class Probe extends AbstractProcessor {

        static final String OPTION_OWNER_FQN = "t018.ownerFqn";
        private static final String METHOD_NAME = "bar";

        @Override
        public Set<String> getSupportedOptions() {
            return Set.of(OPTION_OWNER_FQN);
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (roundEnv.processingOver()) {
                return false;
            }
            String ownerFqn = processingEnv.getOptions().get(OPTION_OWNER_FQN);
            if (ownerFqn == null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, OPTION_OWNER_FQN + " option not set");
                return false;
            }

            Elements elements = processingEnv.getElementUtils();
            Types types = processingEnv.getTypeUtils();
            TypeElement owner = elements.getTypeElement(ownerFqn);
            if (owner == null) {
                // Not yet resolvable this round (multi-round compiles); try again later.
                return false;
            }

            ExecutableElement method = findMethod(elements, owner);
            if (method == null) {
                processingEnv
                        .getMessager()
                        .printMessage(
                                Diagnostic.Kind.ERROR, "no member method named " + METHOD_NAME + " on " + ownerFqn);
                return false;
            }

            ElementInvocationPolicies policies = new ElementInvocationPolicies(elements, types);

            ElementPolicyChains route;
            try {
                route = policies.resolveRoute(method, owner);
            } catch (InvocationPolicyConflictException e) {
                note("ROUTE", "CONFLICT", e.axis().name(), e.getMessage());
                return false;
            }
            note("ROUTE", "OK", typeNames(route.canonicalizers()), typeNames(route.sanitizers()));

            VariableElement param = method.getParameters().get(0);
            ElementPolicyChains paramChains;
            try {
                paramChains = policies.resolveParameter(param, 0, method, owner, route);
            } catch (InvocationPolicyConflictException e) {
                note("PARAM", "CONFLICT", e.axis().name(), e.getMessage());
                return false;
            }
            note("PARAM", "OK", typeNames(paramChains.canonicalizers()), typeNames(paramChains.sanitizers()));
            return false;
        }

        private static ExecutableElement findMethod(Elements elements, TypeElement owner) {
            for (Element member : elements.getAllMembers(owner)) {
                if (member.getKind() == ElementKind.METHOD
                        && member.getSimpleName().contentEquals(METHOD_NAME)) {
                    return (ExecutableElement) member;
                }
            }
            return null;
        }

        private void note(String stage, String status, String a, String b) {
            processingEnv
                    .getMessager()
                    .printMessage(Diagnostic.Kind.NOTE, stage + FIELD_SEP + status + FIELD_SEP + a + FIELD_SEP + b);
        }

        private static String typeNames(List<TypeMirror> values) {
            return values.stream().map(TypeMirror::toString).collect(Collectors.joining(LIST_SEP));
        }
    }
}
