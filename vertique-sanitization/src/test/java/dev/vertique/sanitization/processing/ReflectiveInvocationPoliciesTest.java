// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.sanitization.Sanitize;
import dev.vertique.sanitization.SkipSanitization;
import dev.vertique.sanitization.processing.testkit.A;
import dev.vertique.sanitization.processing.testkit.ComposedSanitize;
import dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios;
import dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios.Outcome;
import dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios.Row;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * {@code ReflectiveInvocationPolicies.resolveRoute}/{@code resolveParameter} resolve the same
 * invocation-policy matrix as {@link InvocationPolicyResolverTest}, from real annotated carrier classes
 * in {@link dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios} instead of hand-built
 * stubs.
 *
 * <p>Unlike {@code InvocationPolicyResolver.resolveRouteChain}/{@code resolveParameterChain},
 * {@code ReflectiveInvocationPolicies.resolveRoute}/{@code resolveParameter} resolve both axes in a
 * single call ({@code EffectiveInputPolicies} carries both chains). Consequently, a conflict on either
 * axis fails the whole call — the matrix has no row where both axes conflict, so this test resolves
 * the {@code SANITIZE}-axis outcome to decide whether the call is expected to throw, and asserts both
 * axes' chains together when it is not.
 *
 * <p>Only {@code same-method-conflict} (same-site) and {@code override-skip-conflict} (real
 * {@code IFoo}/{@code FooImpl} names) assert an exact message; {@code same-parameter-conflict}
 * (parameter-level) and {@code supertype-skip-conflict} (type-level) assert {@code axis()} and that
 * both real declaration sites appear in {@code getMessage()}, since no exact literal is pinned for
 * those element kinds.
 *
 * <p>Sensitivity: resolving {@code method.getAnnotation(Sanitize.class)} directly instead of through
 * {@code AnnotationResolver} in {@code resolveRoute} would make {@code interface-method-chain} and
 * {@code composed-annotation} resolve {@code []} instead of {@code [B]} —
 * {@link #resolvesRouteAndParameterFromRealClasses} would fail for those rows.
 */
class ReflectiveInvocationPoliciesTest {

    @ParameterizedTest(name = "{0}")
    @DisplayName("resolveRoute/resolveParameter match the matrix over real carriers")
    @MethodSource("dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios#rows")
    void resolvesRouteAndParameterFromRealClasses(Row row) {
        if (row.sanitize().route() instanceof Outcome.Conflict conflict) {
            InvocationPolicyConflictException ex = assertThrows(
                    InvocationPolicyConflictException.class,
                    () -> ReflectiveInvocationPolicies.resolveRoute(row.method(), row.owner()));
            assertConflict(row, conflict, ex);
            return;
        }

        EffectiveInputPolicies route = ReflectiveInvocationPolicies.resolveRoute(row.method(), row.owner());
        assertEquals(
                ((Outcome.Chain) row.canonicalize().route()).values(),
                route.canonicalizers(),
                row.id() + " route canonicalizers");
        assertEquals(
                ((Outcome.Chain) row.sanitize().route()).values(), route.sanitizers(), row.id() + " route sanitizers");

        if (row.sanitize().param() instanceof Outcome.Conflict conflict) {
            InvocationPolicyConflictException ex = assertThrows(
                    InvocationPolicyConflictException.class,
                    () -> ReflectiveInvocationPolicies.resolveParameter(row.method(), row.parameterIndex(), route));
            assertConflict(row, conflict, ex);
            return;
        }

        EffectiveInputPolicies param =
                ReflectiveInvocationPolicies.resolveParameter(row.method(), row.parameterIndex(), route);
        assertEquals(
                ((Outcome.Chain) row.canonicalize().param()).values(),
                param.canonicalizers(),
                row.id() + " parameter canonicalizers");
        assertEquals(
                ((Outcome.Chain) row.sanitize().param()).values(),
                param.sanitizers(),
                row.id() + " parameter sanitizers");
    }

    private void assertConflict(Row row, Outcome.Conflict expected, InvocationPolicyConflictException ex) {
        assertEquals(PolicyAxis.SANITIZE, ex.axis());
        switch (row.id()) {
            case "same-method-conflict" ->
                assertEquals(
                        dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios.expectedConflictMessage(
                                PolicyAxis.SANITIZE,
                                "SameMethodConflict.bar",
                                "SameMethodConflict.bar",
                                "method SameMethodConflict.bar"),
                        ex.getMessage());
            case "override-skip-conflict" ->
                assertEquals(
                        dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios.expectedConflictMessage(
                                PolicyAxis.SANITIZE, "IFoo.bar", "FooImpl.bar", "method FooImpl.bar"),
                        ex.getMessage());
            case "same-parameter-conflict" -> {
                assertFalse(ex.elementDescription() == null
                        || ex.elementDescription().isBlank());
                assertTrue(
                        ex.getMessage().contains("SameParameterConflict.bar"),
                        "message should name SameParameterConflict.bar: " + ex.getMessage());
            }
            case "supertype-skip-conflict" -> {
                assertFalse(ex.elementDescription() == null
                        || ex.elementDescription().isBlank());
                assertTrue(
                        ex.getMessage().contains("SupertypeSkipConflictBase"),
                        "message should name SupertypeSkipConflictBase: " + ex.getMessage());
                assertTrue(
                        ex.getMessage().contains("SupertypeSkipConflict"),
                        "message should name SupertypeSkipConflict: " + ex.getMessage());
            }
            default -> throw new AssertionError("unexpected conflict row " + row.id());
        }
    }
    /** An interface declaring the sanitizer directly; the implementation only composes one. */
    interface IDirect {
        @Sanitize(A.class)
        void bar(String p);
    }

    /**
     * The override composes {@code @Sanitize(B)} and skips: the value resolves to the interface's
     * direct {@code [A]} (direct anywhere beats composed), so the conflict message must name the
     * interface as the additive site, not the nearer composed declaration.
     */
    static class DirectImpl implements IDirect {
        @Override
        @ComposedSanitize
        @SkipSanitization
        public void bar(String p) {}
    }

    @Test
    @DisplayName("the declaration site follows the value: a direct interface annotation beats a nearer composed one")
    void declarationSiteFollowsTheDirectFirstValueLookup() throws Exception {
        Method bar = DirectImpl.class.getMethod("bar", String.class);

        InvocationPolicyConflictException ex = assertThrows(
                InvocationPolicyConflictException.class,
                () -> ReflectiveInvocationPolicies.resolveRoute(bar, DirectImpl.class));

        assertEquals(
                dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios.expectedConflictMessage(
                        PolicyAxis.SANITIZE, "IDirect.bar", "DirectImpl.bar", "method DirectImpl.bar"),
                ex.getMessage());
    }

    /** A generic interface declaring the sanitizer directly on a method whose parameter is the variable. */
    interface IGenericDirect<T> {
        @Sanitize(A.class)
        void baz(T p);
    }

    /** Binds the variable to {@code String}: {@code baz(String)} overrides {@code IGenericDirect.baz(T)}. */
    static class GenericDirectImpl implements IGenericDirect<String> {
        @Override
        @ComposedSanitize
        @SkipSanitization
        public void baz(String p) {}
    }

    @Test
    @DisplayName("an override of a generic declaration resolves its value and names that declaration as the site")
    void genericOverrideNamesTheDeclarationItInheritedFrom() throws Exception {
        Method baz = GenericDirectImpl.class.getMethod("baz", String.class);

        InvocationPolicyConflictException ex = assertThrows(
                InvocationPolicyConflictException.class,
                () -> ReflectiveInvocationPolicies.resolveRoute(baz, GenericDirectImpl.class));

        assertEquals(
                dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios.expectedConflictMessage(
                        PolicyAxis.SANITIZE,
                        "IGenericDirect.baz",
                        "GenericDirectImpl.baz",
                        "method GenericDirectImpl.baz"),
                ex.getMessage());
    }

    // --- An inherited skip that silently removes a class-level chain ---

    /** The interface method carries the skip; the implementation never mentions it. */
    interface ISkipDeclaringIface {
        @SkipSanitization
        void bar(String p);
    }

    /**
     * The implementing class declares the chain the inherited skip removes. Nothing on
     * {@code SkipInheritedImpl.bar} says the class-level {@code @Sanitize(A)} will not run, which is
     * exactly what the WARN exists to make visible.
     */
    @Sanitize(A.class)
    static class SkipInheritedImpl implements ISkipDeclaringIface {
        @Override
        public void bar(String p) {}
    }

    @Test
    @DisplayName("an interface-declared skip over a class-level chain resolves [] and warns over real carriers")
    void inheritedSkipOverClassChainResolvesEmptyAndWarns() throws Exception {
        Logger resolverLogger = (Logger) LoggerFactory.getLogger(InvocationPolicyResolver.class);
        Level previousLevel = resolverLogger.getLevel();
        resolverLogger.setLevel(Level.WARN);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        resolverLogger.addAppender(appender);
        try {
            Method bar = SkipInheritedImpl.class.getMethod("bar", String.class);

            EffectiveInputPolicies route = ReflectiveInvocationPolicies.resolveRoute(bar, SkipInheritedImpl.class);

            assertEquals(List.of(), route.sanitizers(), "the inherited skip still wins");
            assertEquals(
                    List.of(InvocationPolicyScenarios.expectedInheritedSkipWarning(
                            PolicyAxis.SANITIZE,
                            "ISkipDeclaringIface.bar",
                            "SkipInheritedImpl",
                            "method SkipInheritedImpl.bar")),
                    appender.list.stream()
                            .filter(event -> event.getLevel() == Level.WARN)
                            .map(ILoggingEvent::getFormattedMessage)
                            .toList());
        } finally {
            resolverLogger.detachAppender(appender);
            appender.stop();
            resolverLogger.setLevel(previousLevel);
        }
    }
}
