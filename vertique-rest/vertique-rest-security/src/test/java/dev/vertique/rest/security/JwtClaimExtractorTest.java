// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JwtClaimExtractor}.
 *
 * <p>Verifies claim extraction from JSON array and space-delimited string formats
 * for roles, scope/scp (via extractScopes), and permissions claims.
 */
class JwtClaimExtractorTest {

    // --- Roles ---

    @Test
    @DisplayName("Should extract roles from JSON array")
    void shouldExtractRolesFromJsonArray() {
        JsonObject principal =
                new JsonObject().put("roles", new JsonArray().add("admin").add("user"));
        assertEquals(Set.of("admin", "user"), JwtClaimExtractor.extractRoles(principal));
    }

    @Test
    @DisplayName("Should extract roles from space-delimited string")
    void shouldExtractRolesFromSpaceDelimitedString() {
        JsonObject principal = new JsonObject().put("roles", "admin user");
        assertEquals(Set.of("admin", "user"), JwtClaimExtractor.extractRoles(principal));
    }

    @Test
    @DisplayName("Should return empty set for null principal")
    void shouldReturnEmptySetForNullPrincipal() {
        assertEquals(Set.of(), JwtClaimExtractor.extractRoles(null));
    }

    @Test
    @DisplayName("Should return empty set for missing claim")
    void shouldReturnEmptySetForMissingClaim() {
        JsonObject principal = new JsonObject();
        assertEquals(Set.of(), JwtClaimExtractor.extractRoles(principal));
    }

    @Test
    @DisplayName("Should skip non-string array elements")
    void shouldSkipNonStringArrayElements() {
        JsonObject principal = new JsonObject()
                .put("roles", new JsonArray().add("admin").add(123).addNull());
        assertEquals(Set.of("admin"), JwtClaimExtractor.extractRoles(principal));
    }

    @Test
    @DisplayName("Should skip blank string array elements")
    void shouldSkipBlankStringElements() {
        JsonObject principal = new JsonObject()
                .put("roles", new JsonArray().add("admin").add(" ").add(""));
        assertEquals(Set.of("admin"), JwtClaimExtractor.extractRoles(principal));
    }

    @Test
    @DisplayName("Should return empty set for blank string claim")
    void shouldReturnEmptySetForBlankString() {
        JsonObject principal = new JsonObject().put("roles", "  ");
        assertEquals(Set.of(), JwtClaimExtractor.extractRoles(principal));
    }

    @Test
    @DisplayName("Should handle leading whitespace in string claim")
    void shouldHandleLeadingWhitespaceInStringClaim() {
        JsonObject principal = new JsonObject().put("roles", " admin user ");
        assertEquals(Set.of("admin", "user"), JwtClaimExtractor.extractRoles(principal));
    }

    // --- Scopes (scope + scp claims) ---

    @Test
    @DisplayName("Should extract scopes from 'scope' claim as space-delimited string")
    void shouldExtractScopesFromScopeClaim() {
        JsonObject principal = new JsonObject().put("scope", "read write");
        assertEquals(Set.of("read", "write"), JwtClaimExtractor.extractScopes(principal));
    }

    @Test
    @DisplayName("Should extract scopes from 'scope' claim as JSON array")
    void shouldExtractScopesFromScopeClaimAsArray() {
        JsonObject principal =
                new JsonObject().put("scope", new JsonArray().add("read").add("write"));
        assertEquals(Set.of("read", "write"), JwtClaimExtractor.extractScopes(principal));
    }

    @Test
    @DisplayName("Should extract scopes from 'scp' claim as JSON array")
    void shouldExtractScopesFromScpClaimAsArray() {
        JsonObject principal =
                new JsonObject().put("scp", new JsonArray().add("read").add("write"));
        assertEquals(Set.of("read", "write"), JwtClaimExtractor.extractScopes(principal));
    }

    @Test
    @DisplayName("Should extract scopes from 'scp' claim as space-delimited string")
    void shouldExtractScopesFromScpClaimAsString() {
        JsonObject principal = new JsonObject().put("scp", "read write");
        assertEquals(Set.of("read", "write"), JwtClaimExtractor.extractScopes(principal));
    }

    @Test
    @DisplayName("Should combine scopes from both 'scope' and 'scp' claims")
    void shouldCombineScopeAndScpClaims() {
        JsonObject principal = new JsonObject().put("scope", "read").put("scp", new JsonArray().add("write"));
        assertEquals(Set.of("read", "write"), JwtClaimExtractor.extractScopes(principal));
    }

    // --- Permissions ---

    @Test
    @DisplayName("Should extract permissions from 'permissions' claim only")
    void shouldExtractPermissionsFromPermissionsClaim() {
        JsonObject principal = new JsonObject().put("permissions", new JsonArray().add("items:read"));
        assertEquals(Set.of("items:read"), JwtClaimExtractor.extractPermissions(principal));
    }

    @Test
    @DisplayName("extractPermissions should NOT include scope or scp claims")
    void shouldNotIncludeScopeInPermissions() {
        JsonObject principal = new JsonObject()
                .put("scope", "read")
                .put("scp", new JsonArray().add("write"))
                .put("permissions", new JsonArray().add("items:delete"));
        assertEquals(Set.of("items:delete"), JwtClaimExtractor.extractPermissions(principal));
    }
}
