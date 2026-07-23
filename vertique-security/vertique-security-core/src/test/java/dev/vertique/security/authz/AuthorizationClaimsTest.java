// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuthorizationClaims}.
 *
 * <p>Verifies: {@code empty()} factory returns empty claims and attributes; defensive copies of
 * {@code claims} and {@code attributes}; {@link AuthorizationClaims#valuesOf(AuthorityKind)} returns
 * only values matching the requested kind; {@code valuesOf} with no matches returns empty set;
 * {@code valuesOf(null)} throws {@link NullPointerException}.
 */
class AuthorizationClaimsTest {

    // --- empty() factory ---

    @Test
    @DisplayName("empty() factory returns empty claims set")
    void emptyFactoryReturnsEmptyClaims() {
        AuthorizationClaims claims = AuthorizationClaims.empty();
        assertTrue(claims.claims().isEmpty());
    }

    @Test
    @DisplayName("empty() factory returns empty attributes map")
    void emptyFactoryReturnsEmptyAttributes() {
        AuthorizationClaims claims = AuthorizationClaims.empty();
        assertTrue(claims.attributes().isEmpty());
    }

    // --- null claims/attributes treated as empty ---

    @Test
    @DisplayName("null claims treated as empty set")
    void nullClaimsTreatedAsEmpty() {
        AuthorizationClaims claims = new AuthorizationClaims(null, Map.of());
        assertTrue(claims.claims().isEmpty());
    }

    @Test
    @DisplayName("null attributes treated as empty map")
    void nullAttributesTreatedAsEmpty() {
        AuthorizationClaims claims = new AuthorizationClaims(Set.of(), null);
        assertTrue(claims.attributes().isEmpty());
    }

    // --- defensive copies ---

    @Test
    @DisplayName("mutating source claims set after construction does not affect record")
    void defensivelyCopiesClaims() {
        AuthorityClaim claim = new AuthorityClaim(AuthorityKind.ROLE, "admin", "", "", "", Map.of());
        Set<AuthorityClaim> mutable = new HashSet<>();
        mutable.add(claim);
        AuthorizationClaims authzClaims = new AuthorizationClaims(mutable, Map.of());
        mutable.add(new AuthorityClaim(AuthorityKind.SCOPE, "read", "", "", "", Map.of()));
        assertEquals(1, authzClaims.claims().size(), "Claims must not reflect mutation of source set");
    }

    @Test
    @DisplayName("claims set returned by accessor is unmodifiable")
    void claimsIsUnmodifiable() {
        AuthorizationClaims claims = new AuthorizationClaims(Set.of(), Map.of());
        assertThrows(UnsupportedOperationException.class, () -> claims.claims()
                .add(new AuthorityClaim(AuthorityKind.ROLE, "admin", "", "", "", Map.of())));
    }

    @Test
    @DisplayName("mutating source attributes map after construction does not affect record")
    void defensivelyCopiesAttributes() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("k", "v");
        AuthorizationClaims claims = new AuthorizationClaims(Set.of(), mutable);
        mutable.put("injected", "evil");
        assertEquals(1, claims.attributes().size(), "Attributes must not reflect mutation of source map");
    }

    @Test
    @DisplayName("attributes map instance is not the original reference")
    void attributesIsDefensiveCopy() {
        Map<String, Object> original = new HashMap<>(Map.of("k", "v"));
        AuthorizationClaims claims = new AuthorizationClaims(Set.of(), original);
        assertNotSame(original, claims.attributes());
    }

    // --- valuesOf ---

    @Test
    @DisplayName("valuesOf(ROLE) returns only role values")
    void valuesOfRoleReturnsOnlyRoles() {
        Set<AuthorityClaim> claimSet = Set.of(
                new AuthorityClaim(AuthorityKind.ROLE, "admin", "", "", "", Map.of()),
                new AuthorityClaim(AuthorityKind.ROLE, "user", "", "", "", Map.of()),
                new AuthorityClaim(AuthorityKind.SCOPE, "read", "", "", "", Map.of()));
        AuthorizationClaims authzClaims = new AuthorizationClaims(claimSet, Map.of());

        Set<String> roles = authzClaims.valuesOf(AuthorityKind.ROLE);
        assertEquals(Set.of("admin", "user"), roles);
    }

    @Test
    @DisplayName("valuesOf(SCOPE) returns only scope values")
    void valuesOfScopeReturnsOnlyScopes() {
        Set<AuthorityClaim> claimSet = Set.of(
                new AuthorityClaim(AuthorityKind.ROLE, "admin", "", "", "", Map.of()),
                new AuthorityClaim(AuthorityKind.SCOPE, "read", "", "", "", Map.of()),
                new AuthorityClaim(AuthorityKind.SCOPE, "write", "", "", "", Map.of()));
        AuthorizationClaims authzClaims = new AuthorizationClaims(claimSet, Map.of());

        Set<String> scopes = authzClaims.valuesOf(AuthorityKind.SCOPE);
        assertEquals(Set.of("read", "write"), scopes);
    }

    @Test
    @DisplayName("valuesOf with no matching claims returns empty set")
    void valuesOfNoMatchReturnsEmpty() {
        Set<AuthorityClaim> claimSet = Set.of(new AuthorityClaim(AuthorityKind.ROLE, "admin", "", "", "", Map.of()));
        AuthorizationClaims authzClaims = new AuthorizationClaims(claimSet, Map.of());

        Set<String> entitlements = authzClaims.valuesOf(AuthorityKind.ENTITLEMENT);
        assertTrue(entitlements.isEmpty());
    }

    @Test
    @DisplayName("valuesOf(null) throws NullPointerException")
    void valuesOfNullThrowsNpe() {
        AuthorizationClaims claims = AuthorizationClaims.empty();
        assertThrows(NullPointerException.class, () -> claims.valuesOf(null));
    }

    @Test
    @DisplayName("valuesOf returns unmodifiable set")
    void valuesOfReturnsUnmodifiableSet() {
        AuthorizationClaims claims = new AuthorizationClaims(
                Set.of(new AuthorityClaim(AuthorityKind.ROLE, "admin", "", "", "", Map.of())), Map.of());
        Set<String> roles = claims.valuesOf(AuthorityKind.ROLE);
        assertThrows(UnsupportedOperationException.class, () -> roles.add("hacker"));
    }
}
