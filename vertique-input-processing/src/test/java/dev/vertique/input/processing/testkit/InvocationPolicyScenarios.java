// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing.testkit;

import dev.vertique.core.sanitization.Canonicalize;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.SkipSanitization;
import dev.vertique.input.processing.InvocationPolicySource;
import dev.vertique.input.processing.PolicyAxis;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The IP-01..IP-19 invocation-policy scenario matrix (contract
 * {@code contracts/invocation-policy-resolver.md} L135-162; T016, issue #379).
 *
 * <p>Every row is expressed twice, from the same declarative description, for the two proofs that
 * consume this class:
 *
 * <ul>
 *   <li>{@link Row#canonicalize()} / {@link Row#sanitize()} carry hand-built
 *       {@link InvocationPolicySource} stubs (built with {@link #sources}/{@link #none}) mirroring
 *       each scenario, for {@code InvocationPolicyResolverTest} (TP-001) to drive
 *       {@code InvocationPolicyResolver.resolveRouteChain}/{@code resolveParameterChain} directly.</li>
 *   <li>{@link Row#owner()}, {@link Row#method()}, {@link Row#parameterIndex()} carry a real annotated
 *       class/interface hierarchy, for {@code ReflectiveInvocationPoliciesTest} (TP-002) to drive
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
 * every other axis-less row. Every conflict row in this matrix (IP-14, IP-15, IP-17, IP-19) is
 * SANITIZE-only for that reason; {@link Row#canonicalize()} is never a conflict.
 */
public final class InvocationPolicyScenarios {

    private InvocationPolicyScenarios() {}

    /**
     * The IP-01..IP-19 scenario rows, for {@code @MethodSource(
     * "dev.vertique.input.processing.testkit.InvocationPolicyScenarios#rows")}.
     *
     * @return the matrix rows in IP-01..IP-19 order
     */
    public static Stream<Row> rows() {
        return ROWS.stream();
    }

    private static final List<Row> ROWS = List.of(
            ip01(), ip02(), ip03(), ip04(), ip05(), ip06(), ip07(), ip08(), ip09(), ip10(), ip11(), ip12(), ip13(),
            ip14(), ip15(), ip16(), ip17(), ip18(), ip19());

    // --- Row definitions ---

    private static Row ip01() {
        return new Row("IP-01", Ip01.class, method(Ip01.class, "bar", String.class), 0, emptyAxis(), emptyAxis());
    }

    private static Row ip02() {
        InvocationPolicySource<Class<?>> type = sources(List.of(A.class), "Ip02", false, null, "type Ip02");
        return new Row(
                "IP-02",
                Ip02.class,
                method(Ip02.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(none(), type, none(), Outcome.chain(A.class), Outcome.chain(A.class)));
    }

    private static Row ip03() {
        InvocationPolicySource<Class<?>> methodSrc =
                sources(List.of(B.class), "Ip03.bar", false, null, "method Ip03.bar");
        return new Row(
                "IP-03",
                Ip03.class,
                method(Ip03.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(methodSrc, none(), none(), Outcome.chain(B.class), Outcome.chain(B.class)));
    }

    private static Row ip04() {
        InvocationPolicySource<Class<?>> type = sources(List.of(A.class), "Ip04", false, null, "type Ip04");
        InvocationPolicySource<Class<?>> methodSrc =
                sources(List.of(B.class), "Ip04.bar", false, null, "method Ip04.bar");
        return new Row(
                "IP-04",
                Ip04.class,
                method(Ip04.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(methodSrc, type, none(), Outcome.chain(B.class), Outcome.chain(B.class)));
    }

    private static Row ip05() {
        InvocationPolicySource<Class<?>> type = sources(List.of(A.class), "Ip05", false, null, "type Ip05");
        InvocationPolicySource<Class<?>> methodSrc = sources(null, null, true, "Ip05.bar", "method Ip05.bar");
        return new Row(
                "IP-05",
                Ip05.class,
                method(Ip05.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(methodSrc, type, none(), Outcome.chain(), Outcome.chain()));
    }

    private static Row ip06() {
        InvocationPolicySource<Class<?>> methodSrc = sources(null, null, true, "Ip06.bar", "method Ip06.bar");
        return new Row(
                "IP-06",
                Ip06.class,
                method(Ip06.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(methodSrc, none(), none(), Outcome.chain(), Outcome.chain()));
    }

    private static Row ip07() {
        InvocationPolicySource<Class<?>> type = sources(List.of(A.class), "Ip07", false, null, "type Ip07");
        InvocationPolicySource<Class<?>> param =
                sources(List.of(C.class), "Ip07.bar", false, null, "parameter 0 of method Ip07.bar");
        return new Row(
                "IP-07",
                Ip07.class,
                method(Ip07.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(none(), type, param, Outcome.chain(A.class), Outcome.chain(C.class)));
    }

    private static Row ip08() {
        InvocationPolicySource<Class<?>> type = sources(List.of(A.class), "Ip08", false, null, "type Ip08");
        InvocationPolicySource<Class<?>> param =
                sources(null, null, true, "Ip08.bar", "parameter 0 of method Ip08.bar");
        return new Row(
                "IP-08",
                Ip08.class,
                method(Ip08.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(none(), type, param, Outcome.chain(A.class), Outcome.chain()));
    }

    private static Row ip09() {
        InvocationPolicySource<Class<?>> methodSrc =
                sources(List.of(B.class), "Ip09Iface.bar", false, null, "method Ip09.bar");
        return new Row(
                "IP-09",
                Ip09.class,
                method(Ip09.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(methodSrc, none(), none(), Outcome.chain(B.class), Outcome.chain(B.class)));
    }

    private static Row ip10() {
        InvocationPolicySource<Class<?>> type = sources(List.of(A.class), "Ip10Base", false, null, "type Ip10");
        return new Row(
                "IP-10",
                Ip10.class,
                method(Ip10.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(none(), type, none(), Outcome.chain(A.class), Outcome.chain(A.class)));
    }

    private static Row ip11() {
        InvocationPolicySource<Class<?>> methodSrc =
                sources(List.of(B.class), "Ip11.bar", false, null, "method Ip11.bar");
        return new Row(
                "IP-11",
                Ip11.class,
                method(Ip11.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(methodSrc, none(), none(), Outcome.chain(B.class), Outcome.chain(B.class)));
    }

    private static Row ip12() {
        InvocationPolicySource<Class<?>> param =
                sources(List.of(C.class), "Ip12Iface.bar", false, null, "parameter 0 of method Ip12.bar");
        return new Row(
                "IP-12",
                Ip12.class,
                method(Ip12.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(none(), none(), param, Outcome.chain(), Outcome.chain(C.class)));
    }

    private static Row ip13() {
        InvocationPolicySource<Class<?>> canonType = sources(List.of(K.class), "Ip13", false, null, "type Ip13");
        InvocationPolicySource<Class<?>> saniMethod =
                sources(List.of(B.class), "Ip13.bar", false, null, "method Ip13.bar");
        return new Row(
                "IP-13",
                Ip13.class,
                method(Ip13.class, "bar", String.class),
                0,
                axis(none(), canonType, none(), Outcome.chain(K.class), Outcome.chain(K.class)),
                axis(saniMethod, none(), none(), Outcome.chain(B.class), Outcome.chain(B.class)));
    }

    private static Row ip14() {
        InvocationPolicySource<Class<?>> methodSrc =
                sources(List.of(B.class), "Ip14.bar", true, "Ip14.bar", "method Ip14.bar");
        return new Row(
                "IP-14",
                Ip14.class,
                method(Ip14.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(
                        methodSrc,
                        none(),
                        none(),
                        Outcome.conflict("method Ip14.bar", "Ip14.bar", "Ip14.bar"),
                        Outcome.chain()));
    }

    private static Row ip15() {
        InvocationPolicySource<Class<?>> param =
                sources(List.of(C.class), "Ip15.bar", true, "Ip15.bar", "parameter 0 of method Ip15.bar");
        return new Row(
                "IP-15",
                Ip15.class,
                method(Ip15.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(
                        none(),
                        none(),
                        param,
                        Outcome.chain(),
                        Outcome.conflict("parameter 0 of method Ip15.bar", "Ip15.bar", "Ip15.bar")));
    }

    private static Row ip16() {
        InvocationPolicySource<Class<?>> methodSrc = sources(null, null, true, "Ip16Iface.bar", "method Ip16.bar");
        return new Row(
                "IP-16",
                Ip16.class,
                method(Ip16.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(methodSrc, none(), none(), Outcome.chain(), Outcome.chain()));
    }

    private static Row ip17() {
        InvocationPolicySource<Class<?>> methodSrc =
                sources(List.of(B.class), "IFoo.bar", true, "FooImpl.bar", "method FooImpl.bar");
        return new Row(
                "IP-17",
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

    private static Row ip18() {
        InvocationPolicySource<Class<?>> methodSrc =
                sources(List.of(A.class), "Ip18.bar", false, null, "method Ip18.bar");
        return new Row(
                "IP-18",
                Ip18.class,
                method(Ip18.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(methodSrc, none(), none(), Outcome.chain(A.class), Outcome.chain(A.class)));
    }

    private static Row ip19() {
        InvocationPolicySource<Class<?>> type = sources(List.of(A.class), "Ip19", true, "Ip19Base", "type Ip19");
        return new Row(
                "IP-19",
                Ip19.class,
                method(Ip19.class, "bar", String.class),
                0,
                emptyAxis(),
                axis(none(), type, none(), Outcome.conflict("type Ip19", "Ip19", "Ip19Base"), Outcome.chain()));
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
     * (contract L75-80): both proofs use this single formatter so the fixed tail is never restated as
     * a second literal that could silently drift.
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
     * on a <em>supertype</em> site removes a non-empty additive chain declared one level below
     * (security review R2): the precedence is unchanged — the skip still wins — but the removal is
     * announced instead of being silent.
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
     * One IP-01..IP-19 scenario row: stub sources for TP-001 (via {@link #canonicalize()} /
     * {@link #sanitize()}) and a real carrier for TP-002 (via {@link #owner()} / {@link #method()} /
     * {@link #parameterIndex()}).
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
     * One axis's expectation for a row: the stub sources TP-001 feeds to
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
    // shape omits the parameter list (contract L75-77), so this is safe even for the exact-message
    // rows (IP-14, IP-17).

    static class Ip01 {
        public void bar(String p) {}
    }

    @Sanitize(A.class)
    static class Ip02 {
        public void bar(String p) {}
    }

    static class Ip03 {
        @Sanitize(B.class)
        public void bar(String p) {}
    }

    @Sanitize(A.class)
    static class Ip04 {
        @Sanitize(B.class)
        public void bar(String p) {}
    }

    @Sanitize(A.class)
    static class Ip05 {
        @SkipSanitization
        public void bar(String p) {}
    }

    static class Ip06 {
        @SkipSanitization
        public void bar(String p) {}
    }

    @Sanitize(A.class)
    static class Ip07 {
        public void bar(@Sanitize(C.class) String p) {}
    }

    @Sanitize(A.class)
    static class Ip08 {
        public void bar(@SkipSanitization String p) {}
    }

    interface Ip09Iface {
        @Sanitize(B.class)
        void bar(String p);
    }

    static class Ip09 implements Ip09Iface {
        @Override
        public void bar(String p) {}
    }

    @Sanitize(A.class)
    static class Ip10Base {
        public void bar(String p) {}
    }

    static class Ip10 extends Ip10Base {}

    static class Ip11 {
        @ComposedSanitize
        public void bar(String p) {}
    }

    interface Ip12Iface {
        void bar(@Sanitize(C.class) String p);
    }

    static class Ip12 implements Ip12Iface {
        @Override
        public void bar(String p) {}
    }

    @Canonicalize(K.class)
    static class Ip13 {
        @Sanitize(B.class)
        public void bar(String p) {}
    }

    static class Ip14 {
        @Sanitize(B.class)
        @SkipSanitization
        public void bar(String p) {}
    }

    static class Ip15 {
        public void bar(@Sanitize(C.class) @SkipSanitization String p) {}
    }

    interface Ip16Iface {
        @SkipSanitization
        void bar(String p);
    }

    static class Ip16 implements Ip16Iface {
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

    static class Ip18Base {
        @Sanitize(B.class)
        public void bar(String p) {}
    }

    static class Ip18 extends Ip18Base {
        @Override
        @Sanitize(A.class)
        public void bar(String p) {}
    }

    @SkipSanitization
    static class Ip19Base {
        public void bar(String p) {}
    }

    @Sanitize(A.class)
    static class Ip19 extends Ip19Base {}
}
