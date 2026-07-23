// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link SecurityRequirement} record invariants: the defensive immutable copy of its
 * {@code scopes}, mirroring the {@link SecurityRequirementSet} hardening so a caller cannot mutate a
 * requirement's scopes through the source list it passed in.
 *
 * <p>{@link SecurityRequirement} is one named scheme (plus its scopes) within an operation's
 * OR-of-AND security model used by the rest-017 security hardening; its scopes feed the effective
 * {@link dev.vertique.rest.core.security.SecurityPolicy} fold, so they must be immutable.
 */
class SecurityRequirementTest {

    @Test
    @DisplayName("scopes() is a defensive immutable copy — mutating the source list does not affect the requirement")
    void scopesIsDefensiveImmutableCopy() {
        List<String> source = new ArrayList<>();
        source.add("write");

        SecurityRequirement requirement = new SecurityRequirement("jwt", source);

        // Mutating the source after construction must not change the stored scopes.
        source.add("admin");
        assertEquals(
                List.of("write"),
                requirement.scopes(),
                "stored scopes must not reflect post-construction source mutation");

        // The stored list itself must be immutable.
        assertThrows(
                UnsupportedOperationException.class,
                () -> requirement.scopes().add("admin"),
                "scopes() must return an immutable copy");
    }

    @Test
    @DisplayName("a null scopes list is rejected")
    void nullScopesRejected() {
        assertThrows(NullPointerException.class, () -> new SecurityRequirement("jwt", null));
    }
}
