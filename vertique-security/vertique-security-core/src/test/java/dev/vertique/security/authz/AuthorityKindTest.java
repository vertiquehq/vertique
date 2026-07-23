// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuthorityKind} enum completeness and round-trip value lookup.
 */
class AuthorityKindTest {

    @Test
    @DisplayName("all 6 constants are present")
    void valuesHasExpectedCount() {
        assertEquals(6, AuthorityKind.values().length);
    }

    @Test
    @DisplayName("ROLE constant is present")
    void roleConstantPresent() {
        assertTrue(Arrays.asList(AuthorityKind.values()).contains(AuthorityKind.ROLE));
    }

    @Test
    @DisplayName("GROUP constant is present")
    void groupConstantPresent() {
        assertTrue(Arrays.asList(AuthorityKind.values()).contains(AuthorityKind.GROUP));
    }

    @Test
    @DisplayName("SCOPE constant is present")
    void scopeConstantPresent() {
        assertTrue(Arrays.asList(AuthorityKind.values()).contains(AuthorityKind.SCOPE));
    }

    @Test
    @DisplayName("PERMISSION constant is present")
    void permissionConstantPresent() {
        assertTrue(Arrays.asList(AuthorityKind.values()).contains(AuthorityKind.PERMISSION));
    }

    @Test
    @DisplayName("ENTITLEMENT constant is present")
    void entitlementConstantPresent() {
        assertTrue(Arrays.asList(AuthorityKind.values()).contains(AuthorityKind.ENTITLEMENT));
    }

    @Test
    @DisplayName("CLAIM constant is present")
    void claimConstantPresent() {
        assertTrue(Arrays.asList(AuthorityKind.values()).contains(AuthorityKind.CLAIM));
    }

    @Test
    @DisplayName("valueOf round-trips ROLE")
    void valueOfRoleRoundTrips() {
        assertEquals(AuthorityKind.ROLE, AuthorityKind.valueOf("ROLE"));
    }

    @Test
    @DisplayName("valueOf round-trips SCOPE")
    void valueOfScopeRoundTrips() {
        assertEquals(AuthorityKind.SCOPE, AuthorityKind.valueOf("SCOPE"));
    }

    @Test
    @DisplayName("valueOf round-trips PERMISSION")
    void valueOfPermissionRoundTrips() {
        assertEquals(AuthorityKind.PERMISSION, AuthorityKind.valueOf("PERMISSION"));
    }
}
