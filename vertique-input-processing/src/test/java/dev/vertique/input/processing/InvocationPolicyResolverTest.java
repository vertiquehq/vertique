// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.input.processing.testkit.InvocationPolicyScenarios;
import dev.vertique.input.processing.testkit.InvocationPolicyScenarios.AxisExpectation;
import dev.vertique.input.processing.testkit.InvocationPolicyScenarios.Outcome;
import dev.vertique.input.processing.testkit.InvocationPolicyScenarios.Row;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * TP-001 (T016, issue #379): {@code InvocationPolicyResolver.resolveRouteChain}/
 * {@code resolveParameterChain} honor the IP-01..IP-19 precedence matrix and reject conflicts, driven
 * entirely by hand-built {@link dev.vertique.input.processing.testkit.InvocationPolicyScenarios}
 * stubs — no reflection, no real annotated carriers.
 *
 * <p>Every row is exercised on both axes, since {@code resolveRouteChain}/{@code resolveParameterChain}
 * take an explicit {@link PolicyAxis}: the {@code CANONICALIZE} axis is a real, separate call for every
 * row, not skipped just because a row's declarative description only mentions {@code @Sanitize}.
 *
 * <p>Sensitivity (contract-required): promoting {@code type.additive()} ahead of {@code method.skip()}
 * in {@code resolveRouteChain}'s precedence would make IP-05 resolve {@code [A]} instead of {@code []}
 * — {@link #resolvesSanitizeAxis} would fail.
 */
class InvocationPolicyResolverTest {

    @ParameterizedTest(name = "{0}")
    @DisplayName("CANONICALIZE axis matches the matrix")
    @MethodSource("dev.vertique.input.processing.testkit.InvocationPolicyScenarios#rows")
    void resolvesCanonicalizeAxis(Row row) {
        assertAxis(PolicyAxis.CANONICALIZE, row.canonicalize());
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("SANITIZE axis matches the matrix, conflicts are rejected")
    @MethodSource("dev.vertique.input.processing.testkit.InvocationPolicyScenarios#rows")
    void resolvesSanitizeAxis(Row row) {
        assertAxis(PolicyAxis.SANITIZE, row.sanitize());
    }

    private void assertAxis(PolicyAxis axis, AxisExpectation expectation) {
        if (expectation.route() instanceof Outcome.Conflict conflict) {
            InvocationPolicyConflictException ex = assertThrows(
                    InvocationPolicyConflictException.class,
                    () -> InvocationPolicyResolver.resolveRouteChain(
                            expectation.methodSource(), expectation.typeSource(), axis));
            assertConflict(axis, conflict, ex);
            return;
        }

        List<Class<?>> routeChain =
                InvocationPolicyResolver.resolveRouteChain(expectation.methodSource(), expectation.typeSource(), axis);
        assertEquals(((Outcome.Chain) expectation.route()).values(), routeChain, "route chain for " + axis);

        if (expectation.param() instanceof Outcome.Conflict conflict) {
            InvocationPolicyConflictException ex = assertThrows(
                    InvocationPolicyConflictException.class,
                    () -> InvocationPolicyResolver.resolveParameterChain(expectation.paramSource(), routeChain, axis));
            assertConflict(axis, conflict, ex);
            return;
        }

        List<Class<?>> paramChain =
                InvocationPolicyResolver.resolveParameterChain(expectation.paramSource(), routeChain, axis);
        assertEquals(((Outcome.Chain) expectation.param()).values(), paramChain, "parameter chain for " + axis);
    }

    private void assertConflict(PolicyAxis axis, Outcome.Conflict expected, InvocationPolicyConflictException ex) {
        assertEquals(axis, ex.axis());
        assertEquals(expected.elementDescription(), ex.elementDescription());
        assertEquals(
                InvocationPolicyScenarios.expectedConflictMessage(
                        axis, expected.additiveDeclaredAt(), expected.skipDeclaredAt(), expected.elementDescription()),
                ex.getMessage());
    }
}
