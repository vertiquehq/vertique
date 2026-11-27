// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.processing.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.sanitization.processing.InvocationPolicyConflictException;
import dev.vertique.sanitization.processing.PolicyAxis;
import dev.vertique.sanitization.processing.apt.ElementInvocationPolicies.ElementPolicyChains;
import dev.vertique.sanitization.processing.testkit.A;
import dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios;
import dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios.Outcome;
import dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios.Row;
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
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code apt.ElementInvocationPolicies.resolveRoute}/{@code resolveParameter} resolve the same
 * invocation-policy matrix as {@code ReflectiveInvocationPolicies}, but driven from real
 * {@code javax.lang.model} elements obtained by compiling one inline source per row through a probe
 * annotation processor ({@link Probe}), instead of reflection.
 *
 * <p>Each row's carrier hierarchy is re-declared here as compilable source text (own package per
 * row, {@code dev.vertique.sanitization.processing.apt.fixture.<scenario>}), reusing the exact simple class
 * and method names of {@code InvocationPolicyScenarios}'s reflection carriers (e.g.
 * {@code SameMethodConflict.bar}, {@code IFoo}/{@code FooImpl}) so the adapter's site/describe strings
 * are byte-identical to the reflective adapter's. The row's expected outcome — {@link
 * Outcome.Chain} or {@link Outcome.Conflict} — is reused unchanged from {@link
 * InvocationPolicyScenarios#rows()}; only the source form differs from {@code
 * ReflectiveInvocationPoliciesTest}.
 *
 * <p>{@link Probe} calls {@code new ElementInvocationPolicies(elements, types).resolveRoute(...)}
 * then {@code resolveParameter(...)} and reports the outcome of each stage as a compiler {@link
 * Diagnostic.Kind#NOTE} (chain contents as canonical type names, or the caught {@link
 * InvocationPolicyConflictException}'s axis and message) — {@code NOTE} never fails the compilation,
 * so {@link ProcessorTestHarness.Result#assertSuccess()} holds for every row including the conflict
 * ones; this test exercises the adapter API directly, not the compile-error behavior (that is covered
 * by the generator matrix tests, where the caller turns the same exception into a real diagnostic).
 *
 * <p>Only {@code same-method-conflict} (same-site) and {@code override-skip-conflict} (real
 * {@code IFoo}/{@code FooImpl} names) assert an exact message; {@code same-parameter-conflict}
 * (parameter-level) and {@code supertype-skip-conflict} (type-level) assert the axis and that both
 * real declaration sites appear in the message, mirroring {@code ReflectiveInvocationPoliciesTest}:
 * no exact literal is pinned for those element kinds.
 *
 * <p>Sensitivity: skipping the overridden-method walk (not using {@code Elements.overrides}/
 * {@code getAllMembers} inherited-method semantics) would make {@code interface-method-chain} and
 * {@code interface-parameter-chain} resolve to empty chains instead of {@code [B]}/{@code [C]} — {@link
 * #resolvesRouteAndParameterFromCompiledElements} would fail for those rows.
 */
class ElementInvocationPoliciesTest {

    /** Field separator used by {@link Probe}'s NOTE-encoded results; not expected in any payload. */
    private static final String FIELD_SEP = "\u0001";

    /** List-item separator for chain FQNs within a single field. */
    private static final String LIST_SEP = "\u0002";

    @ParameterizedTest(name = "{0}")
    @DisplayName("resolveRoute/resolveParameter match the matrix over compiled elements")
    @MethodSource("dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios#rows")
    void resolvesRouteAndParameterFromCompiledElements(Row row) {
        RowSource rowSource = rowSource(row.id());
        ProcessorTestHarness.Result result = ProcessorTestHarness.run(
                new Probe(),
                Map.of(Probe.OPTION_OWNER_FQN, rowSource.ownerFqn()),
                rowSource.sources().toArray(JavaFileObject[]::new));

        // A conflict is caught and reported as a NOTE by the probe — the compilation itself always
        // succeeds (see class javadoc).
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
     * Resolution-order parity with the reflective reference for the one shape the scenario matrix
     * does not pin — a <em>direct</em> annotation on a farther site competing with a
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
        String pkg = "dev.vertique.sanitization.processing.apt.fixture.directvscomposed";
        JavaFileObject iface = SourceFiles.inline(pkg + ".IDirect", """
                package %s;
                public interface IDirect {
                    @dev.vertique.sanitization.Sanitize(dev.vertique.sanitization.processing.testkit.A.class)
                    void bar(String p);
                }
                """.formatted(pkg));
        JavaFileObject impl = SourceFiles.inline(pkg + ".DirectImpl", """
                package %s;
                public class DirectImpl implements IDirect {
                    @Override
                    @dev.vertique.sanitization.processing.testkit.ComposedSanitize
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
     * The composed-annotation walk's cycle bound is decisive, not decorative.
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
        String pkg = "dev.vertique.sanitization.processing.apt.fixture.cycle";
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
     * {@code resolveParameter} rejects an out-of-range index with
     * {@link IllegalArgumentException} naming the method and the index, instead of letting an
     * {@link IndexOutOfBoundsException} escape from the parameter-site walk and abort the processor.
     *
     * <p>The carrier overrides an interface method on purpose: the site walk only indexes into a
     * <em>supertype</em> declaration's parameter list, so a standalone class never reaches the bad
     * index. Both out-of-range shapes are exercised — {@code -1} (the reported {@code indexOf}-miss
     * shape) and an index past the parameter count.
     */
    @ParameterizedTest(name = "index {0}")
    @ValueSource(ints = {-1, 7})
    @DisplayName("an out-of-range parameter index is rejected with IllegalArgumentException")
    void outOfRangeParameterIndexIsRejected(int index) {
        String pkg = "dev.vertique.sanitization.processing.apt.fixture.outofrange";
        JavaFileObject iface = SourceFiles.inline(pkg + ".IOob", """
                package %s;
                public interface IOob {
                    void bar(String p);
                }
                """.formatted(pkg));
        JavaFileObject impl = SourceFiles.inline(pkg + ".Oob", """
                package %s;
                public class Oob implements IOob {
                    @Override
                    public void bar(String p) {}
                }
                """.formatted(pkg));

        ProcessorTestHarness.Result result = ProcessorTestHarness.run(
                new Probe(),
                Map.of(Probe.OPTION_OWNER_FQN, pkg + ".Oob", Probe.OPTION_PARAM_INDEX, String.valueOf(index)),
                iface,
                impl);
        result.assertSuccess();

        String label = "out-of-range-" + index;
        String[] paramFields = firstNote(result, "PARAM", label).split(FIELD_SEP, 4);
        assertEquals("ILLEGAL_ARGUMENT", paramFields[1], label + " must be rejected by the precondition");
        String message = paramFields[3];
        assertTrue(message.contains("Oob.bar"), label + " message should name the method: " + message);
        assertTrue(message.contains(String.valueOf(index)), label + " message should name the index: " + message);
    }

    /**
     * Asserts a {@code CONFLICT}-status note matches the row's expected {@link Outcome.Conflict},
     * mirroring {@code ReflectiveInvocationPoliciesTest#assertConflict}: exact message for
     * {@code same-method-conflict}/{@code override-skip-conflict}, axis plus both declaration sites for
     * {@code same-parameter-conflict}/{@code supertype-skip-conflict}.
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
            case "same-method-conflict" ->
                assertEquals(
                        InvocationPolicyScenarios.expectedConflictMessage(
                                PolicyAxis.SANITIZE,
                                "SameMethodConflict.bar",
                                "SameMethodConflict.bar",
                                "method SameMethodConflict.bar"),
                        message);
            case "override-skip-conflict" ->
                assertEquals(
                        InvocationPolicyScenarios.expectedConflictMessage(
                                PolicyAxis.SANITIZE, "IFoo.bar", "FooImpl.bar", "method FooImpl.bar"),
                        message);
            case "same-parameter-conflict" ->
                assertTrue(
                        message.contains("SameParameterConflict.bar"),
                        "message should name SameParameterConflict.bar: " + message);
            case "supertype-skip-conflict" -> {
                assertTrue(
                        message.contains("SupertypeSkipConflictBase"),
                        "message should name SupertypeSkipConflictBase: " + message);
                assertTrue(
                        message.contains("SupertypeSkipConflict"),
                        "message should name SupertypeSkipConflict: " + message);
            }
            default -> throw new AssertionError("unexpected conflict row " + row.id());
        }
        // expected's own site strings are asserted structurally above via the row.id() switch,
        // matching InvocationPolicyScenarios's own conflict-row Outcome.Conflict data exactly.
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
     * Builds the inline source(s) and owner FQN for one scenario row, re-declaring
     * {@link InvocationPolicyScenarios}'s reflection carriers as compilable source text in a
     * row-scoped package.
     *
     * @param rowId the scenario id (e.g. {@code "same-method-conflict"})
     * @return the row's sources and owner FQN
     */
    private static RowSource rowSource(String rowId) {
        String pkg = "dev.vertique.sanitization.processing.apt.fixture."
                + rowId.toLowerCase(Locale.ROOT).replace("-", "");
        return switch (rowId) {
            case "no-policy" -> single(pkg, "NoPolicy", """
                    package %s;
                    public class NoPolicy {
                        public void bar(String p) {}
                    }
                    """);
            case "type-chain" -> single(pkg, "TypeChain", """
                    package %s;
                    @dev.vertique.sanitization.Sanitize(dev.vertique.sanitization.processing.testkit.A.class)
                    public class TypeChain {
                        public void bar(String p) {}
                    }
                    """);
            case "method-chain" -> single(pkg, "MethodChain", """
                    package %s;
                    public class MethodChain {
                        @dev.vertique.sanitization.Sanitize(dev.vertique.sanitization.processing.testkit.B.class)
                        public void bar(String p) {}
                    }
                    """);
            case "method-overrides-type" -> single(pkg, "MethodOverridesType", """
                    package %s;
                    @dev.vertique.sanitization.Sanitize(dev.vertique.sanitization.processing.testkit.A.class)
                    public class MethodOverridesType {
                        @dev.vertique.sanitization.Sanitize(dev.vertique.sanitization.processing.testkit.B.class)
                        public void bar(String p) {}
                    }
                    """);
            case "method-skip-over-type-chain" -> single(pkg, "MethodSkipOverTypeChain", """
                    package %s;
                    @dev.vertique.sanitization.Sanitize(dev.vertique.sanitization.processing.testkit.A.class)
                    public class MethodSkipOverTypeChain {
                        @dev.vertique.sanitization.SkipSanitization
                        public void bar(String p) {}
                    }
                    """);
            case "method-skip-only" -> single(pkg, "MethodSkipOnly", """
                    package %s;
                    public class MethodSkipOnly {
                        @dev.vertique.sanitization.SkipSanitization
                        public void bar(String p) {}
                    }
                    """);
            case "parameter-overrides-type" -> single(pkg, "ParameterOverridesType", """
                    package %s;
                    @dev.vertique.sanitization.Sanitize(dev.vertique.sanitization.processing.testkit.A.class)
                    public class ParameterOverridesType {
                        public void bar(
                                @dev.vertique.sanitization.Sanitize(dev.vertique.sanitization.processing.testkit.C.class) String p) {}
                    }
                    """);
            case "parameter-skip-over-type-chain" -> single(pkg, "ParameterSkipOverTypeChain", """
                    package %s;
                    @dev.vertique.sanitization.Sanitize(dev.vertique.sanitization.processing.testkit.A.class)
                    public class ParameterSkipOverTypeChain {
                        public void bar(@dev.vertique.sanitization.SkipSanitization String p) {}
                    }
                    """);
            case "interface-method-chain" ->
                new RowSource(
                        List.of(
                                SourceFiles.inline(pkg + ".InterfaceMethodChainIface", """
                                    package %s;
                                    public interface InterfaceMethodChainIface {
                                        @dev.vertique.sanitization.Sanitize(dev.vertique.sanitization.processing.testkit.B.class)
                                        void bar(String p);
                                    }
                                    """.formatted(pkg)),
                                SourceFiles.inline(pkg + ".InterfaceMethodChain", """
                                    package %s;
                                    public class InterfaceMethodChain implements InterfaceMethodChainIface {
                                        @Override
                                        public void bar(String p) {}
                                    }
                                    """.formatted(pkg))),
                        pkg + ".InterfaceMethodChain");
            case "superclass-type-chain" ->
                new RowSource(
                        List.of(
                                SourceFiles.inline(pkg + ".SuperclassTypeChainBase", """
                                    package %s;
                                    @dev.vertique.sanitization.Sanitize(dev.vertique.sanitization.processing.testkit.A.class)
                                    public class SuperclassTypeChainBase {
                                        public void bar(String p) {}
                                    }
                                    """.formatted(pkg)),
                                SourceFiles.inline(pkg + ".SuperclassTypeChain", """
                                    package %s;
                                    public class SuperclassTypeChain extends SuperclassTypeChainBase {}
                                    """.formatted(pkg))),
                        pkg + ".SuperclassTypeChain");
            case "composed-annotation" -> single(pkg, "ComposedAnnotation", """
                    package %s;
                    public class ComposedAnnotation {
                        @dev.vertique.sanitization.processing.testkit.ComposedSanitize
                        public void bar(String p) {}
                    }
                    """);
            case "interface-parameter-chain" ->
                new RowSource(
                        List.of(
                                SourceFiles.inline(pkg + ".InterfaceParameterChainIface", """
                                    package %s;
                                    public interface InterfaceParameterChainIface {
                                        void bar(
                                                @dev.vertique.sanitization.Sanitize(dev.vertique.sanitization.processing.testkit.C.class) String p);
                                    }
                                    """.formatted(pkg)),
                                SourceFiles.inline(pkg + ".InterfaceParameterChain", """
                                    package %s;
                                    public class InterfaceParameterChain implements InterfaceParameterChainIface {
                                        @Override
                                        public void bar(String p) {}
                                    }
                                    """.formatted(pkg))),
                        pkg + ".InterfaceParameterChain");
            case "independent-axes" -> single(pkg, "IndependentAxes", """
                    package %s;
                    @dev.vertique.sanitization.Canonicalize(dev.vertique.sanitization.processing.testkit.K.class)
                    public class IndependentAxes {
                        @dev.vertique.sanitization.Sanitize(dev.vertique.sanitization.processing.testkit.B.class)
                        public void bar(String p) {}
                    }
                    """);
            case "same-method-conflict" -> single(pkg, "SameMethodConflict", """
                    package %s;
                    public class SameMethodConflict {
                        @dev.vertique.sanitization.Sanitize(dev.vertique.sanitization.processing.testkit.B.class)
                        @dev.vertique.sanitization.SkipSanitization
                        public void bar(String p) {}
                    }
                    """);
            case "same-parameter-conflict" -> single(pkg, "SameParameterConflict", """
                    package %s;
                    public class SameParameterConflict {
                        public void bar(
                                @dev.vertique.sanitization.Sanitize(dev.vertique.sanitization.processing.testkit.C.class)
                                @dev.vertique.sanitization.SkipSanitization String p) {}
                    }
                    """);
            case "interface-method-skip" ->
                new RowSource(
                        List.of(
                                SourceFiles.inline(pkg + ".InterfaceMethodSkipIface", """
                                    package %s;
                                    public interface InterfaceMethodSkipIface {
                                        @dev.vertique.sanitization.SkipSanitization
                                        void bar(String p);
                                    }
                                    """.formatted(pkg)),
                                SourceFiles.inline(pkg + ".InterfaceMethodSkip", """
                                    package %s;
                                    public class InterfaceMethodSkip implements InterfaceMethodSkipIface {
                                        @Override
                                        public void bar(String p) {}
                                    }
                                    """.formatted(pkg))),
                        pkg + ".InterfaceMethodSkip");
            case "override-skip-conflict" ->
                new RowSource(
                        List.of(
                                SourceFiles.inline(pkg + ".IFoo", """
                                    package %s;
                                    public interface IFoo {
                                        @dev.vertique.sanitization.Sanitize(dev.vertique.sanitization.processing.testkit.B.class)
                                        void bar(String p);
                                    }
                                    """.formatted(pkg)),
                                SourceFiles.inline(pkg + ".FooImpl", """
                                    package %s;
                                    public class FooImpl implements IFoo {
                                        @Override
                                        @dev.vertique.sanitization.SkipSanitization
                                        public void bar(String p) {}
                                    }
                                    """.formatted(pkg))),
                        pkg + ".FooImpl");
            case "override-replaces-chain" ->
                new RowSource(
                        List.of(
                                SourceFiles.inline(pkg + ".OverrideReplacesChainBase", """
                                    package %s;
                                    public class OverrideReplacesChainBase {
                                        @dev.vertique.sanitization.Sanitize(dev.vertique.sanitization.processing.testkit.B.class)
                                        public void bar(String p) {}
                                    }
                                    """.formatted(pkg)),
                                SourceFiles.inline(pkg + ".OverrideReplacesChain", """
                                    package %s;
                                    public class OverrideReplacesChain extends OverrideReplacesChainBase {
                                        @Override
                                        @dev.vertique.sanitization.Sanitize(dev.vertique.sanitization.processing.testkit.A.class)
                                        public void bar(String p) {}
                                    }
                                    """.formatted(pkg))),
                        pkg + ".OverrideReplacesChain");
            case "supertype-skip-conflict" ->
                new RowSource(
                        List.of(
                                SourceFiles.inline(pkg + ".SupertypeSkipConflictBase", """
                                    package %s;
                                    @dev.vertique.sanitization.SkipSanitization
                                    public class SupertypeSkipConflictBase {
                                        public void bar(String p) {}
                                    }
                                    """.formatted(pkg)),
                                SourceFiles.inline(pkg + ".SupertypeSkipConflict", """
                                    package %s;
                                    @dev.vertique.sanitization.Sanitize(dev.vertique.sanitization.processing.testkit.A.class)
                                    public class SupertypeSkipConflict extends SupertypeSkipConflictBase {}
                                    """.formatted(pkg))),
                        pkg + ".SupertypeSkipConflict");
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
     * semantics — the merged view sees only the overriding declaration, exactly like the
     * {@code interface-method-chain} and {@code override-skip-conflict} carriers), and drives
     * {@link ElementInvocationPolicies#resolveRoute}/{@code resolveParameter}.
     * Each stage's outcome is reported as a {@link Diagnostic.Kind#NOTE} so it survives the
     * in-process compile-testing round trip without relying on same-classloader static-field capture.
     */
    @SupportedAnnotationTypes("*")
    @SupportedSourceVersion(SourceVersion.RELEASE_21)
    public static final class Probe extends AbstractProcessor {

        static final String OPTION_OWNER_FQN = "probe.ownerFqn";

        /**
         * Optional zero-based parameter index handed to {@code resolveParameter}; defaults to
         * {@code 0}. Set to an out-of-range value to drive the out-of-range precondition.
         */
        static final String OPTION_PARAM_INDEX = "probe.paramIndex";

        private static final String METHOD_NAME = "bar";

        @Override
        public Set<String> getSupportedOptions() {
            return Set.of(OPTION_OWNER_FQN, OPTION_PARAM_INDEX);
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
            int index = Integer.parseInt(processingEnv.getOptions().getOrDefault(OPTION_PARAM_INDEX, "0"));
            ElementPolicyChains paramChains;
            try {
                paramChains = policies.resolveParameter(param, index, method, owner, route);
            } catch (InvocationPolicyConflictException e) {
                note("PARAM", "CONFLICT", e.axis().name(), e.getMessage());
                return false;
            } catch (IllegalArgumentException e) {
                note("PARAM", "ILLEGAL_ARGUMENT", "", e.getMessage());
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
