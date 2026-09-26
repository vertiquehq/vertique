// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.rest.core.routing.SecurityRequirement;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.SecurityPolicy;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * TP-005 — proves {@link JaxRsRouteRegistrar#classifyExplicitPolicy(SecurityPolicy, List,
 * boolean)}'s C-POLICY classification directly, with constructed inputs and no component, mount,
 * or registrar involved (RL-7).
 *
 * <p>An operation restricts callers when its effective policy is {@code DenyAll},
 * {@code AuthenticatedOnly}, or {@code Constrained}; when {@code securityRequirementSets()} is
 * non-empty and contains no empty (anonymous) set; or when it has a resolved required action. It is
 * declared public when it does not restrict callers and its effective policy is {@code PermitAll} —
 * a restricting declaration takes precedence over {@code @PermitAll} (row 5, RL-8). Any other
 * operation is implicit.
 *
 * <p>The annotation scanner cannot itself produce an empty {@link SecurityRequirementSet} today
 * ({@code SecuritySchemeAnnotationScanner.java:55-65, 142-162}); row 3 (the defensive rule) exists
 * only because {@link SecurityRequirementSet}'s canonical constructor accepts one.
 */
class ExplicitPolicyClassificationTest {

    private static final SecurityRequirementSet ONE_SCHEME_SET =
            new SecurityRequirementSet(List.of(new SecurityRequirement("bearer", List.of())));
    private static final SecurityRequirementSet EMPTY_SET = new SecurityRequirementSet(List.of());

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    @DisplayName("anonymousAlternativeIsImplicit")
    void anonymousAlternativeIsImplicit(Row row) {
        JaxRsRouteRegistrar.ExplicitPolicy actual = JaxRsRouteRegistrar.classifyExplicitPolicy(
                row.policy(), row.requirementSets(), row.requiredActionResolved());
        assertEquals(row.expected(), actual, row.name());
    }

    // --- Rows, in the contract's order (TP-005 "Given"/"Then") ---

    static Stream<Row> rows() {
        return Stream.of(
                new Row(
                        "None, no sets",
                        new SecurityPolicy.None(),
                        List.of(),
                        false,
                        JaxRsRouteRegistrar.ExplicitPolicy.IMPLICIT),
                new Row(
                        "None, one-scheme set",
                        new SecurityPolicy.None(),
                        List.of(ONE_SCHEME_SET),
                        false,
                        JaxRsRouteRegistrar.ExplicitPolicy.RESTRICTS_CALLERS),
                new Row(
                        "None, one-scheme set + empty set",
                        new SecurityPolicy.None(),
                        List.of(ONE_SCHEME_SET, EMPTY_SET),
                        false,
                        JaxRsRouteRegistrar.ExplicitPolicy.IMPLICIT),
                new Row(
                        "PermitAll, no sets",
                        new SecurityPolicy.PermitAll(),
                        List.of(),
                        false,
                        JaxRsRouteRegistrar.ExplicitPolicy.DECLARED_PUBLIC),
                new Row(
                        "PermitAll, one-scheme set (precedence)",
                        new SecurityPolicy.PermitAll(),
                        List.of(ONE_SCHEME_SET),
                        false,
                        JaxRsRouteRegistrar.ExplicitPolicy.RESTRICTS_CALLERS),
                new Row(
                        "Constrained, no sets",
                        new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                        List.of(),
                        false,
                        JaxRsRouteRegistrar.ExplicitPolicy.RESTRICTS_CALLERS),
                new Row(
                        "AuthenticatedOnly, no sets",
                        new SecurityPolicy.AuthenticatedOnly(),
                        List.of(),
                        false,
                        JaxRsRouteRegistrar.ExplicitPolicy.RESTRICTS_CALLERS),
                new Row(
                        "DenyAll, no sets",
                        new SecurityPolicy.DenyAll(),
                        List.of(),
                        false,
                        JaxRsRouteRegistrar.ExplicitPolicy.RESTRICTS_CALLERS),
                new Row(
                        "None, no sets, required action resolved",
                        new SecurityPolicy.None(),
                        List.of(),
                        true,
                        JaxRsRouteRegistrar.ExplicitPolicy.RESTRICTS_CALLERS));
    }

    /** One TP-005 row: its name, the classifier's three inputs, and the expected classification. */
    private record Row(
            String name,
            SecurityPolicy policy,
            List<SecurityRequirementSet> requirementSets,
            boolean requiredActionResolved,
            JaxRsRouteRegistrar.ExplicitPolicy expected) {

        @Override
        public String toString() {
            return name;
        }
    }
}
