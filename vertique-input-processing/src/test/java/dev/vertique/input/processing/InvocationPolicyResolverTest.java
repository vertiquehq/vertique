// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.input.processing.testkit.A;
import dev.vertique.input.processing.testkit.InvocationPolicyScenarios;
import dev.vertique.input.processing.testkit.InvocationPolicyScenarios.AxisExpectation;
import dev.vertique.input.processing.testkit.InvocationPolicyScenarios.Outcome;
import dev.vertique.input.processing.testkit.InvocationPolicyScenarios.Row;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

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
 *
 * <p>The {@code inheritedSkip*} cases below cover the security review's R2 finding: a skip declared on
 * a supertype site removes a chain declared one level below without the element itself saying so, so
 * the resolver announces the removal with a WARN. The precedence itself is unchanged — every row of
 * the matrix above resolves to exactly the same chain with or without the warning.
 */
class InvocationPolicyResolverTest {

    private static final String OWN_SITE_SKIP = "Ip05.bar";
    private static final String INHERITED_SKIP_SITE = "IFoo.bar";
    private static final String METHOD_DESCRIBE = "method FooImpl.bar";
    private static final String PARAM_DESCRIBE = "parameter 0 of method FooImpl.bar";

    private Logger resolverLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureResolverLogs() {
        resolverLogger = (Logger) LoggerFactory.getLogger(InvocationPolicyResolver.class);
        previousLevel = resolverLogger.getLevel();
        resolverLogger.setLevel(Level.WARN);
        appender = new ListAppender<>();
        appender.start();
        resolverLogger.addAppender(appender);
    }

    @AfterEach
    void releaseResolverLogs() {
        resolverLogger.detachAppender(appender);
        appender.stop();
        resolverLogger.setLevel(previousLevel);
    }

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

    // --- R2 (security review): an inherited skip that silently removes a chain declared below it ---

    @Test
    @DisplayName("an inherited method skip over a class-level chain warns, naming both declaration sites")
    void inheritedMethodSkipOverClassChainWarns() {
        InvocationPolicySource<Class<?>> method =
                InvocationPolicyScenarios.sources(null, null, true, INHERITED_SKIP_SITE, METHOD_DESCRIBE);
        InvocationPolicySource<Class<?>> type =
                InvocationPolicyScenarios.sources(List.of(A.class), "FooImpl", false, null, "type FooImpl");

        List<Class<?>> chain = InvocationPolicyResolver.resolveRouteChain(method, type, PolicyAxis.SANITIZE);

        assertEquals(List.of(), chain, "the skip still wins — the warning changes no value");
        assertEquals(
                List.of(InvocationPolicyScenarios.expectedInheritedSkipWarning(
                        PolicyAxis.SANITIZE, INHERITED_SKIP_SITE, "FooImpl", METHOD_DESCRIBE)),
                warnings());
    }

    @Test
    @DisplayName("an own-site method skip over a class-level chain (IP-05) warns not at all")
    void ownSiteMethodSkipOverClassChainDoesNotWarn() {
        InvocationPolicySource<Class<?>> method =
                InvocationPolicyScenarios.sources(null, null, true, OWN_SITE_SKIP, "method Ip05.bar");
        InvocationPolicySource<Class<?>> type =
                InvocationPolicyScenarios.sources(List.of(A.class), "Ip05", false, null, "type Ip05");

        List<Class<?>> chain = InvocationPolicyResolver.resolveRouteChain(method, type, PolicyAxis.SANITIZE);

        assertEquals(List.of(), chain);
        assertEquals(List.of(), warnings(), "the element declares the skip itself — nothing is hidden");
    }

    @Test
    @DisplayName("an inherited parameter skip over a non-empty route chain warns")
    void inheritedParameterSkipOverRouteChainWarns() {
        InvocationPolicySource<Class<?>> parameter =
                InvocationPolicyScenarios.sources(null, null, true, INHERITED_SKIP_SITE, PARAM_DESCRIBE);

        List<Class<?>> chain =
                InvocationPolicyResolver.resolveParameterChain(parameter, List.of(A.class), PolicyAxis.SANITIZE);

        assertEquals(List.of(), chain);
        assertEquals(
                List.of(InvocationPolicyScenarios.expectedInheritedSkipWarning(
                        PolicyAxis.SANITIZE, INHERITED_SKIP_SITE, "the route", PARAM_DESCRIBE)),
                warnings());
    }

    @Test
    @DisplayName("an inherited skip with no chain to remove warns not at all")
    void inheritedSkipWithNothingToRemoveDoesNotWarn() {
        InvocationPolicySource<Class<?>> method =
                InvocationPolicyScenarios.sources(null, null, true, INHERITED_SKIP_SITE, METHOD_DESCRIBE);

        assertEquals(
                List.of(),
                InvocationPolicyResolver.resolveRouteChain(
                        method, InvocationPolicyScenarios.none(), PolicyAxis.SANITIZE));

        InvocationPolicySource<Class<?>> parameter =
                InvocationPolicyScenarios.sources(null, null, true, INHERITED_SKIP_SITE, PARAM_DESCRIBE);

        assertEquals(
                List.of(), InvocationPolicyResolver.resolveParameterChain(parameter, List.of(), PolicyAxis.SANITIZE));
        assertEquals(List.of(), warnings(), "no chain was removed, so there is nothing to announce");
    }

    private List<String> warnings() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
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
