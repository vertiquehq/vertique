// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.SkipSanitization;
import dev.vertique.input.processing.testkit.A;
import dev.vertique.input.processing.testkit.ComposedSanitize;
import dev.vertique.input.processing.testkit.InvocationPolicyScenarios;
import dev.vertique.input.processing.testkit.InvocationPolicyScenarios.Outcome;
import dev.vertique.input.processing.testkit.InvocationPolicyScenarios.Row;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * TP-002 (T016, issue #379): {@code ReflectiveInvocationPolicies.resolveRoute}/{@code resolveParameter}
 * resolve the same IP-01..IP-19 matrix as {@link InvocationPolicyResolverTest}, from real annotated
 * carrier classes in {@link dev.vertique.input.processing.testkit.InvocationPolicyScenarios} instead of
 * hand-built stubs.
 *
 * <p>Unlike {@code InvocationPolicyResolver.resolveRouteChain}/{@code resolveParameterChain},
 * {@code ReflectiveInvocationPolicies.resolveRoute}/{@code resolveParameter} resolve both axes in a
 * single call ({@code EffectiveInputPolicies} carries both chains). Consequently, a conflict on either
 * axis fails the whole call — the matrix has no row where both axes conflict, so this test resolves
 * the {@code SANITIZE}-axis outcome to decide whether the call is expected to throw, and asserts both
 * axes' chains together when it is not.
 *
 * <p>Only IP-14 (same-site) and IP-17 (real {@code IFoo}/{@code FooImpl} names) have a contract-frozen
 * exact message (L75-80); IP-15 (parameter-level) and IP-19 (type-level) assert {@code axis()} and that
 * both real declaration sites appear in {@code getMessage()}, since the contract does not pin an exact
 * literal for those element kinds (ruling, T016 L01 — see completion evidence).
 *
 * <p>Sensitivity (contract-required): resolving {@code method.getAnnotation(Sanitize.class)} directly
 * instead of through {@code AnnotationResolver} in {@code resolveRoute} would make IP-09 and IP-11
 * resolve {@code []} instead of {@code [B]} — {@link #resolvesRouteAndParameterFromRealClasses} would
 * fail for those rows.
 */
class ReflectiveInvocationPoliciesTest {

    @ParameterizedTest(name = "{0}")
    @DisplayName("resolveRoute/resolveParameter match the matrix over real carriers")
    @MethodSource("dev.vertique.input.processing.testkit.InvocationPolicyScenarios#rows")
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
            case "IP-14" ->
                assertEquals(
                        dev.vertique.input.processing.testkit.InvocationPolicyScenarios.expectedConflictMessage(
                                PolicyAxis.SANITIZE, "Ip14.bar", "Ip14.bar", "method Ip14.bar"),
                        ex.getMessage());
            case "IP-17" ->
                assertEquals(
                        dev.vertique.input.processing.testkit.InvocationPolicyScenarios.expectedConflictMessage(
                                PolicyAxis.SANITIZE, "IFoo.bar", "FooImpl.bar", "method FooImpl.bar"),
                        ex.getMessage());
            case "IP-15" -> {
                assertFalse(ex.elementDescription() == null
                        || ex.elementDescription().isBlank());
                assertTrue(ex.getMessage().contains("Ip15.bar"), "message should name Ip15.bar: " + ex.getMessage());
            }
            case "IP-19" -> {
                assertFalse(ex.elementDescription() == null
                        || ex.elementDescription().isBlank());
                assertTrue(ex.getMessage().contains("Ip19Base"), "message should name Ip19Base: " + ex.getMessage());
                assertTrue(ex.getMessage().contains("Ip19"), "message should name Ip19: " + ex.getMessage());
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
                dev.vertique.input.processing.testkit.InvocationPolicyScenarios.expectedConflictMessage(
                        PolicyAxis.SANITIZE, "IDirect.bar", "DirectImpl.bar", "method DirectImpl.bar"),
                ex.getMessage());
    }

    // --- R2 (security review): an inherited skip that silently removes a class-level chain ---

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
