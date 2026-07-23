// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link SecurityRequirementSet} record invariants: the defensive immutable copy of its
 * {@code schemes}, the {@link SecurityRequirementSet#isSingleScheme()} predicate, and the
 * {@link SecurityRequirementSet#hasScopes()} predicate, including the empty-set edge case.
 *
 * <p>The set models one alternative (an AND-group of schemes) within an operation's OR-of-AND
 * security model introduced for the rest-017 security hardening.
 */
class SecurityRequirementSetTest {

    @Test
    @DisplayName("schemes() is a defensive immutable copy — mutating the source list does not affect the set")
    void schemesIsDefensiveImmutableCopy() {
        List<SecurityRequirement> source = new ArrayList<>();
        source.add(new SecurityRequirement("jwt", List.of()));

        SecurityRequirementSet set = new SecurityRequirementSet(source);

        // Mutating the source after construction must not change the stored schemes.
        source.add(new SecurityRequirement("apiKey", List.of()));
        assertEquals(1, set.schemes().size(), "stored schemes must not reflect post-construction source mutation");

        // The stored list itself must be immutable.
        assertThrows(
                UnsupportedOperationException.class,
                () -> set.schemes().add(new SecurityRequirement("apiKey", List.of())),
                "schemes() must return an immutable copy");
    }

    @Test
    @DisplayName("isSingleScheme() is true for one scheme and false for two")
    void isSingleSchemeReflectsSchemeCount() {
        SecurityRequirementSet single = new SecurityRequirementSet(List.of(new SecurityRequirement("jwt", List.of())));
        SecurityRequirementSet multi = new SecurityRequirementSet(
                List.of(new SecurityRequirement("jwt", List.of()), new SecurityRequirement("apiKey", List.of())));

        assertTrue(single.isSingleScheme(), "a one-scheme set is single-scheme");
        assertFalse(multi.isSingleScheme(), "a two-scheme set is not single-scheme");
    }

    @Test
    @DisplayName("hasScopes() is true iff any scheme has a non-empty scopes list")
    void hasScopesReflectsAnyNonEmptyScopes() {
        SecurityRequirementSet scopeless =
                new SecurityRequirementSet(List.of(new SecurityRequirement("jwt", List.of())));
        SecurityRequirementSet scoped =
                new SecurityRequirementSet(List.of(new SecurityRequirement("jwt", List.of("write"))));
        SecurityRequirementSet mixed = new SecurityRequirementSet(List.of(
                new SecurityRequirement("jwt", List.of()), new SecurityRequirement("apiKey", List.of("admin"))));

        assertFalse(scopeless.hasScopes(), "a set whose schemes have no scopes has no scopes");
        assertTrue(scoped.hasScopes(), "a set with a scoped scheme has scopes");
        assertTrue(mixed.hasScopes(), "a set with any scoped scheme has scopes");
    }

    @Test
    @DisplayName("an empty-schemes set is allowed — not single-scheme and has no scopes")
    void emptySetIsAllowed() {
        SecurityRequirementSet empty = new SecurityRequirementSet(List.of());

        assertTrue(empty.schemes().isEmpty(), "empty set has no schemes");
        assertFalse(empty.isSingleScheme(), "empty set is not single-scheme");
        assertFalse(empty.hasScopes(), "empty set has no scopes");
    }
}
