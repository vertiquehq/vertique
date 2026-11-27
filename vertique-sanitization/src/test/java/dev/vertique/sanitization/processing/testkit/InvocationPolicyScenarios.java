// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.processing.testkit;

import dev.vertique.sanitization.Canonicalize;
import dev.vertique.sanitization.Sanitize;
import dev.vertique.sanitization.SkipSanitization;
import dev.vertique.sanitization.processing.InvocationPolicySource;
import dev.vertique.sanitization.processing.PolicyAxis;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The invocation-policy scenario matrix: nineteen named scenarios covering declaration-site precedence,
 * inheritance, composed annotations, independent axes, and conflicts.
 *
 * <p>Every row is expressed twice, from the same declarative description, for the two proofs that
 * consume this class:
 *
 * <ul>
 *   <li>{@link Row#canonicalize()} / {@link Row#sanitize()} carry hand-built
 *       {@link InvocationPolicySource} stubs (built with {@link #sources}/{@link #none}) mirroring
 *       each scenario, for {@code InvocationPolicyResolverTest} to drive
 *       {@code InvocationPolicyResolver.resolveRouteChain}/{@code resolveParameterChain} directly.</li>
 *   <li>{@link Row#owner()}, {@link Row#method()}, {@link Row#parameterIndex()} carry a real annotated
 *       class/interface hierarchy, for {@code ReflectiveInvocationPoliciesTest} to drive
 *       {@code ReflectiveInvocationPolicies.resolveRoute}/{@code resolveParameter} over actual
 *       reflection.</li>
 * </ul>
 *
 * <p>Both proofs assert the same expected chains ({@link Outcome.Chain}) or the same conflicting-axis
 * shape ({@link Outcome.Conflict}) recorded on each {@link AxisExpectation}, so the matrix itself —
 * not either test — is the single source of truth for what each scenario means.
 *
 * <p><b>Per-axis vs. combined conflicts.</b> {@code InvocationPolicyResolver.resolveRouteChain}/
 * {@code resolveParameterChain} take an explicit {@link PolicyAxis}, so a scenario whose annotations
 * are all {@code @Sanitize}/{@code @SkipSanitization} only ever conflicts on the {@code SANITIZE} axis
 * — the {@code CANONICALIZE} axis for that same scenario has no {@code @Canonicalize}/
 * {@code @SkipCanonicalization} annotations at all and resolves cleanly to {@code []}, exactly like
 * every other axis-less row. Every conflict row in this matrix ({@code same-method-conflict},
 * {@code same-parameter-conflict}, {@code override-skip-conflict}, {@code supertype-skip-conflict}) is
 * SANITIZE-only for that reason; {@link Row#canonicalize()} is never a conflict.
 */
public final class InvocationPolicyScenarios {

    private InvocationPolicyScenarios() {}

    /**
     * The scenario rows, for {@code @MethodSource(
     * "dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios#rows")}.
     *
     * @return the matrix rows in declaration order
     */
    public static Stream<Row> rows() {
        return ROWS.stream();
    }

    private static final List<Row> ROWS = List.of(
            noPolicy(),
            typeChain(),
            methodChain(),
            methodOverridesType(),
            methodSkipOverTypeChain(),
            methodSkipOnly(),
            parameterOverridesType(),
            parameterSkipOverTypeChain(),
            interfaceMethodChain(),
            superclassTypeChain(),
            composedAnnotation(),
            interfaceParameterChain(),
            independentAxes(),
            sameMethodConflict(),
            sameParameterConflict(),
            interfaceMethodSkip(),
            overrideSkipConflict(),
            overrideReplacesChain(),
            supertypeSkipConflict());

    // --- Row definitions ---

    private static Row noPolicy() {
        return new Row(
                "no-policy", NoPolicy.class, method(NoPolicy.class, "bar", String.class), 0, emptyAxis(), emptyAxis());
    }

    private static Row typeChain() {
        InvocationPolicySource<Class<?>> type = sources(List.of(A.class), "TypeChain", false, null, "type TypeChain");
        return new Row(
                "type-chain",
                TypeChain.class,
                method(TypeChain.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(none(), type, none(), Outcome.chain(A.class), Outcome.chain(A.class)));
    }

    private static Row methodChain() {
        InvocationPolicySource<Class<?>> methodSrc =
                sources(List.of(B.class), "MethodChain.bar", false, null, "method MethodChain.bar");
        return new Row(
                "method-chain",
                MethodChain.class,
                method(MethodChain.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(methodSrc, none(), none(), Outcome.chain(B.class), Outcome.chain(B.class)));
    }

    private static Row methodOverridesType() {
        InvocationPolicySource<Class<?>> type =
                sources(List.of(A.class), "MethodOverridesType", false, null, "type MethodOverridesType");
        InvocationPolicySource<Class<?>> methodSrc =
                sources(List.of(B.class), "MethodOverridesType.bar", false, null, "method MethodOverridesType.bar");
        return new Row(
                "method-overrides-type",
                MethodOverridesType.class,
                method(MethodOverridesType.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(methodSrc, type, none(), Outcome.chain(B.class), Outcome.chain(B.class)));
    }

    private static Row methodSkipOverTypeChain() {
        InvocationPolicySource<Class<?>> type =
                sources(List.of(A.class), "MethodSkipOverTypeChain", false, null, "type MethodSkipOverTypeChain");
        InvocationPolicySource<Class<?>> methodSrc =
                sources(null, null, true, "MethodSkipOverTypeChain.bar", "method MethodSkipOverTypeChain.bar");
        return new Row(
                "method-skip-over-type-chain",
                MethodSkipOverTypeChain.class,
                method(MethodSkipOverTypeChain.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(methodSrc, type, none(), Outcome.chain(), Outcome.chain()));
    }

    private static Row methodSkipOnly() {
        InvocationPolicySource<Class<?>> methodSrc =
                sources(null, null, true, "MethodSkipOnly.bar", "method MethodSkipOnly.bar");
        return new Row(
                "method-skip-only",
                MethodSkipOnly.class,
                method(MethodSkipOnly.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(methodSrc, none(), none(), Outcome.chain(), Outcome.chain()));
    }

    private static Row parameterOverridesType() {
        InvocationPolicySource<Class<?>> type =
                sources(List.of(A.class), "ParameterOverridesType", false, null, "type ParameterOverridesType");
        InvocationPolicySource<Class<?>> param = sources(
                List.of(C.class),
                "ParameterOverridesType.bar",
                false,
                null,
                "parameter 0 of method ParameterOverridesType.bar");
        return new Row(
                "parameter-overrides-type",
                ParameterOverridesType.class,
                method(ParameterOverridesType.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(none(), type, param, Outcome.chain(A.class), Outcome.chain(C.class)));
    }

    private static Row parameterSkipOverTypeChain() {
        InvocationPolicySource<Class<?>> type =
                sources(List.of(A.class), "ParameterSkipOverTypeChain", false, null, "type ParameterSkipOverTypeChain");
        InvocationPolicySource<Class<?>> param = sources(
                null,
                null,
                true,
                "ParameterSkipOverTypeChain.bar",
                "parameter 0 of method ParameterSkipOverTypeChain.bar");
        return new Row(
                "parameter-skip-over-type-chain",
                ParameterSkipOverTypeChain.class,
                method(ParameterSkipOverTypeChain.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(none(), type, param, Outcome.chain(A.class), Outcome.chain()));
    }

    private static Row interfaceMethodChain() {
        InvocationPolicySource<Class<?>> methodSrc = sources(
                List.of(B.class), "InterfaceMethodChainIface.bar", false, null, "method InterfaceMethodChain.bar");
        return new Row(
                "interface-method-chain",
                InterfaceMethodChain.class,
                method(InterfaceMethodChain.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(methodSrc, none(), none(), Outcome.chain(B.class), Outcome.chain(B.class)));
    }

    private static Row superclassTypeChain() {
        InvocationPolicySource<Class<?>> type =
                sources(List.of(A.class), "SuperclassTypeChainBase", false, null, "type SuperclassTypeChain");
        return new Row(
                "superclass-type-chain",
                SuperclassTypeChain.class,
                method(SuperclassTypeChain.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(none(), type, none(), Outcome.chain(A.class), Outcome.chain(A.class)));
    }

    private static Row composedAnnotation() {
        InvocationPolicySource<Class<?>> methodSrc =
                sources(List.of(B.class), "ComposedAnnotation.bar", false, null, "method ComposedAnnotation.bar");
        return new Row(
                "composed-annotation",
                ComposedAnnotation.class,
                method(ComposedAnnotation.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(methodSrc, none(), none(), Outcome.chain(B.class), Outcome.chain(B.class)));
    }

    private static Row interfaceParameterChain() {
        InvocationPolicySource<Class<?>> param = sources(
                List.of(C.class),
                "InterfaceParameterChainIface.bar",
                false,
                null,
                "parameter 0 of method InterfaceParameterChain.bar");
        return new Row(
                "interface-parameter-chain",
                InterfaceParameterChain.class,
                method(InterfaceParameterChain.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(none(), none(), param, Outcome.chain(), Outcome.chain(C.class)));
    }

    private static Row independentAxes() {
        InvocationPolicySource<Class<?>> canonType =
                sources(List.of(K.class), "IndependentAxes", false, null, "type IndependentAxes");
        InvocationPolicySource<Class<?>> saniMethod =
                sources(List.of(B.class), "IndependentAxes.bar", false, null, "method IndependentAxes.bar");
        return new Row(
                "independent-axes",
                IndependentAxes.class,
                method(IndependentAxes.class, "bar", String.class),
                0,
                axis(none(), canonType, none(), Outcome.chain(K.class), Outcome.chain(K.class)),
                axis(saniMethod, none(), none(), Outcome.chain(B.class), Outcome.chain(B.class)));
    }

    private static Row sameMethodConflict() {
        InvocationPolicySource<Class<?>> methodSrc = sources(
                List.of(B.class),
                "SameMethodConflict.bar",
                true,
                "SameMethodConflict.bar",
                "method SameMethodConflict.bar");
        return new Row(
                "same-method-conflict",
                SameMethodConflict.class,
                method(SameMethodConflict.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(
                        methodSrc,
                        none(),
                        none(),
                        Outcome.conflict(
                                "method SameMethodConflict.bar", "SameMethodConflict.bar", "SameMethodConflict.bar"),
                        Outcome.chain()));
    }

    private static Row sameParameterConflict() {
        InvocationPolicySource<Class<?>> param = sources(
                List.of(C.class),
                "SameParameterConflict.bar",
                true,
                "SameParameterConflict.bar",
                "parameter 0 of method SameParameterConflict.bar");
        return new Row(
                "same-parameter-conflict",
                SameParameterConflict.class,
                method(SameParameterConflict.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(
                        none(),
                        none(),
                        param,
                        Outcome.chain(),
                        Outcome.conflict(
                                "parameter 0 of method SameParameterConflict.bar",
                                "SameParameterConflict.bar",
                                "SameParameterConflict.bar")));
    }

    private static Row interfaceMethodSkip() {
        InvocationPolicySource<Class<?>> methodSrc =
                sources(null, null, true, "InterfaceMethodSkipIface.bar", "method InterfaceMethodSkip.bar");
        return new Row(
                "interface-method-skip",
                InterfaceMethodSkip.class,
                method(InterfaceMethodSkip.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(methodSrc, none(), none(), Outcome.chain(), Outcome.chain()));
    }

    private static Row overrideSkipConflict() {
        InvocationPolicySource<Class<?>> methodSrc =
                sources(List.of(B.class), "IFoo.bar", true, "FooImpl.bar", "method FooImpl.bar");
        return new Row(
                "override-skip-conflict",
                FooImpl.class,
                method(FooImpl.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(
                        methodSrc,
                        none(),
                        none(),
                        Outcome.conflict("method FooImpl.bar", "IFoo.bar", "FooImpl.bar"),
                        Outcome.chain()));
    }

    private static Row overrideReplacesChain() {
        InvocationPolicySource<Class<?>> methodSrc =
                sources(List.of(A.class), "OverrideReplacesChain.bar", false, null, "method OverrideReplacesChain.bar");
        return new Row(
                "override-replaces-chain",
                OverrideReplacesChain.class,
                method(OverrideReplacesChain.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(methodSrc, none(), none(), Outcome.chain(A.class), Outcome.chain(A.class)));
    }

    private static Row supertypeSkipConflict() {
        InvocationPolicySource<Class<?>> type = sources(
                List.of(A.class),
                "SupertypeSkipConflict",
                true,
                "SupertypeSkipConflictBase",
                "type SupertypeSkipConflict");
        return new Row(
                "supertype-skip-conflict",
                SupertypeSkipConflict.class,
                method(SupertypeSkipConflict.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(
                        none(),
                        type,
                        none(),
                        Outcome.conflict(
                                "type SupertypeSkipConflict", "SupertypeSkipConflict", "SupertypeSkipConflictBase"),
                        Outcome.chain()));
    }

    private static AxisExpectation axis(
            InvocationPolicySource<Class<?>> methodSource,
            InvocationPolicySource<Class<?>> typeSource,
            InvocationPolicySource<Class<?>> paramSource,
            Outcome route,
            Outcome param) {
        return new AxisExpectation(methodSource, typeSource, paramSource, route, param);
    }

    /** The fully-empty axis expectation: no annotations anywhere, chains resolve to {@code []}. */
    private static AxisExpectation emptyAxis() {
        return axis(none(), none(), none(), Outcome.chain(), Outcome.chain());
    }

    // --- Stub InvocationPolicySource construction ---

    /**
     * Builds a hand-built {@link InvocationPolicySource} stub for one scenario declaration.
     *
     * @param additive   the additive chain, or {@code null} when absent
     * @param additiveAt the additive annotation's declaration site, required iff {@code additive} is
     *                   non-{@code null}
     * @param skip       whether the skip annotation is present
     * @param skipAt     the skip annotation's declaration site, required iff {@code skip} is
     *                   {@code true}
     * @param describe   the element description for diagnostics
     * @param <V>        the policy value type
     * @return the stub source
     */
    public static <V> InvocationPolicySource<V> sources(
            List<V> additive, String additiveAt, boolean skip, String skipAt, String describe) {
        return new StubSource<>(
                additive == null ? Optional.empty() : Optional.of(additive),
                additive == null ? Optional.empty() : Optional.of(additiveAt),
                skip,
                skip ? Optional.of(skipAt) : Optional.empty(),
                describe);
    }

    /**
     * The empty stub source: no additive chain, no skip.
     *
     * @param <V> the policy value type
     * @return {@link InvocationPolicySource#none()}
     */
    public static <V> InvocationPolicySource<V> none() {
        return InvocationPolicySource.none();
    }

    /**
     * Builds the exact conflict message the resolver's exception constructor is expected to produce
     * (see {@code InvocationPolicyConflictException}): both proofs use this single formatter so the fixed
     * tail is never restated as a second literal that could silently drift.
     *
     * @param axis               the conflicting axis
     * @param additiveDeclaredAt the additive annotation's declaration site
     * @param skipDeclaredAt     the skip annotation's declaration site
     * @param elementDescription the conflicting element's description
     * @return the expected {@code InvocationPolicyConflictException} message
     */
    public static String expectedConflictMessage(
            PolicyAxis axis, String additiveDeclaredAt, String skipDeclaredAt, String elementDescription) {
        return ("Conflicting %s (declared on %s) and %s (declared on %s) for %s — an override cannot remove "
                        + "an inherited policy; remove one of the annotations.")
                .formatted(
                        axis.additiveAnnotation(),
                        additiveDeclaredAt,
                        axis.skipAnnotation(),
                        skipDeclaredAt,
                        elementDescription);
    }

    /**
     * Builds the exact WARN {@code InvocationPolicyResolver} is expected to log when a skip declared
     * on a <em>supertype</em> site removes a non-empty additive chain declared one level below: the
     * precedence is unchanged — the skip still wins — but the removal is announced instead of being
     * silent.
     *
     * <p>Both proofs (the stub-driven {@code InvocationPolicyResolverTest} and the real-carrier
     * {@code ReflectiveInvocationPoliciesTest}) format the expectation here, so the message shape is
     * never restated as a second literal that could silently drift.
     *
     * @param axis               the axis whose chain is removed
     * @param skipDeclaredAt     the inherited skip annotation's declaration site (e.g.
     *                           {@code "IFoo.bar"})
     * @param additiveDeclaredAt the removed chain's declaration site (e.g. {@code "FooImpl"}), or
     *                           {@code "the route"} when the removed chain is the already-resolved
     *                           route chain a parameter falls back to
     * @param elementDescription the description of the element being resolved
     * @return the expected WARN message
     */
    public static String expectedInheritedSkipWarning(
            PolicyAxis axis, String skipDeclaredAt, String additiveDeclaredAt, String elementDescription) {
        return ("%s declared on %s removes the %s chain declared on %s for %s — the override inherits the skip; "
                        + "declare the chain on the element or remove the inherited skip")
                .formatted(
                        axis.skipAnnotation(),
                        skipDeclaredAt,
                        axis.additiveAnnotation(),
                        additiveDeclaredAt,
                        elementDescription);
    }

    private record StubSource<V>(
            Optional<List<V>> additive,
            Optional<String> additiveDeclaredAt,
            boolean skip,
            Optional<String> skipDeclaredAt,
            String describe)
            implements InvocationPolicySource<V> {}

    // --- Row / expectation shapes ---

    /**
     * One scenario row: stub sources for the resolver proof (via {@link #canonicalize()} /
     * {@link #sanitize()}) and a real carrier for the reflection proof (via {@link #owner()} /
     * {@link #method()} / {@link #parameterIndex()}).
     */
    public record Row(
            String id,
            Class<?> owner,
            Method method,
            int parameterIndex,
            AxisExpectation canonicalize,
            AxisExpectation sanitize) {

        @Override
        public String toString() {
            return id;
        }
    }

    /**
     * One axis's expectation for a row: the stub sources the resolver proof feeds to
     * {@code InvocationPolicyResolver}, and the expected route/parameter {@link Outcome}s both proofs
     * assert against.
     */
    public record AxisExpectation(
            InvocationPolicySource<Class<?>> methodSource,
            InvocationPolicySource<Class<?>> typeSource,
            InvocationPolicySource<Class<?>> paramSource,
            Outcome route,
            Outcome param) {}

    /** Either a resolved chain, or a conflict expectation, for one resolution stage of one axis. */
    public sealed interface Outcome {

        record Chain(List<Class<?>> values) implements Outcome {}

        record Conflict(String elementDescription, String additiveDeclaredAt, String skipDeclaredAt)
                implements Outcome {}

        static Outcome chain(Class<?>... values) {
            return new Chain(List.of(values));
        }

        static Outcome conflict(String elementDescription, String additiveDeclaredAt, String skipDeclaredAt) {
            return new Conflict(elementDescription, additiveDeclaredAt, skipDeclaredAt);
        }
    }

    // --- Reflection helper ---

    private static Method method(Class<?> type, String name, Class<?>... paramTypes) {
        try {
            return type.getMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // --- Carriers ---
    // Every carrier method is bar(String) so every row exercises both resolveRoute/resolveRouteChain
    // and resolveParameter/resolveParameterChain uniformly at parameterIndex 0; the resolver's message
    // shape omits the parameter list, so this is safe even for the exact-message
    // rows (same-method-conflict, override-skip-conflict).

    static class NoPolicy {
        public void bar(String p) {}
    }

    @Sanitize(A.class)
    static class TypeChain {
        public void bar(String p) {}
    }

    static class MethodChain {
        @Sanitize(B.class)
        public void bar(String p) {}
    }

    @Sanitize(A.class)
    static class MethodOverridesType {
        @Sanitize(B.class)
        public void bar(String p) {}
    }

    @Sanitize(A.class)
    static class MethodSkipOverTypeChain {
        @SkipSanitization
        public void bar(String p) {}
    }

    static class MethodSkipOnly {
        @SkipSanitization
        public void bar(String p) {}
    }

    @Sanitize(A.class)
    static class ParameterOverridesType {
        public void bar(@Sanitize(C.class) String p) {}
    }

    @Sanitize(A.class)
    static class ParameterSkipOverTypeChain {
        public void bar(@SkipSanitization String p) {}
    }

    interface InterfaceMethodChainIface {
        @Sanitize(B.class)
        void bar(String p);
    }

    static class InterfaceMethodChain implements InterfaceMethodChainIface {
        @Override
        public void bar(String p) {}
    }

    @Sanitize(A.class)
    static class SuperclassTypeChainBase {
        public void bar(String p) {}
    }

    static class SuperclassTypeChain extends SuperclassTypeChainBase {}

    static class ComposedAnnotation {
        @ComposedSanitize
        public void bar(String p) {}
    }

    interface InterfaceParameterChainIface {
        void bar(@Sanitize(C.class) String p);
    }

    static class InterfaceParameterChain implements InterfaceParameterChainIface {
        @Override
        public void bar(String p) {}
    }

    @Canonicalize(K.class)
    static class IndependentAxes {
        @Sanitize(B.class)
        public void bar(String p) {}
    }

    static class SameMethodConflict {
        @Sanitize(B.class)
        @SkipSanitization
        public void bar(String p) {}
    }

    static class SameParameterConflict {
        public void bar(@Sanitize(C.class) @SkipSanitization String p) {}
    }

    interface InterfaceMethodSkipIface {
        @SkipSanitization
        void bar(String p);
    }

    static class InterfaceMethodSkip implements InterfaceMethodSkipIface {
        @Override
        public void bar(String p) {}
    }

    interface IFoo {
        @Sanitize(B.class)
        void bar(String p);
    }

    static class FooImpl implements IFoo {
        @Override
        @SkipSanitization
        public void bar(String p) {}
    }

    static class OverrideReplacesChainBase {
        @Sanitize(B.class)
        public void bar(String p) {}
    }

    static class OverrideReplacesChain extends OverrideReplacesChainBase {
        @Override
        @Sanitize(A.class)
        public void bar(String p) {}
    }

    @SkipSanitization
    static class SupertypeSkipConflictBase {
        public void bar(String p) {}
    }

    @Sanitize(A.class)
    static class SupertypeSkipConflict extends SupertypeSkipConflictBase {}
}
