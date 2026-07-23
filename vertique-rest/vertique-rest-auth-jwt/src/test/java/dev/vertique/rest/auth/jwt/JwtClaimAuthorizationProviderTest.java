// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authorization.PermissionBasedAuthorization;
import io.vertx.ext.auth.authorization.RoleBasedAuthorization;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtClaimAuthorizationProviderTest {

    private final JwtClaimAuthorizationProvider provider = new JwtClaimAuthorizationProvider();

    @Test
    @DisplayName("Provider ID should be 'jwt-claims'")
    void shouldReturnProviderId() {
        assertEquals("jwt-claims", provider.getId());
    }

    @Test
    @DisplayName("Should return succeeded future for null user")
    void shouldReturnSucceededFutureForNullUser() {
        var future = provider.getAuthorizations(null);
        assertTrue(future.succeeded());
        assertNull(future.result());
    }

    @Test
    @DisplayName("Should return succeeded future for user with null principal")
    void shouldReturnSucceededFutureForNullPrincipal() {
        User user = User.create(new JsonObject());
        var future = provider.getAuthorizations(user);
        assertTrue(future.succeeded());
    }

    @Test
    @DisplayName("Should extract roles from 'roles' claim as RoleBasedAuthorization")
    void shouldExtractRolesFromRolesClaim() {
        User user = User.create(
                new JsonObject().put("roles", new JsonArray().add("user").add("admin")));
        provider.getAuthorizations(user);

        Set<String> roles = collectRoles(user);
        assertEquals(Set.of("user", "admin"), roles);
    }

    @Test
    @DisplayName("Should extract scopes from 'scope' claim as PermissionBasedAuthorization")
    void shouldExtractScopesFromScopeClaim() {
        User user = User.create(new JsonObject().put("scope", "read write"));
        provider.getAuthorizations(user);

        Set<String> permissions = collectPermissions(user);
        assertEquals(Set.of("read", "write"), permissions);
    }

    @Test
    @DisplayName("Should extract scopes from 'scp' claim (Azure AD convention)")
    void shouldExtractScopesFromScpClaim() {
        User user = User.create(
                new JsonObject().put("scp", new JsonArray().add("read").add("write")));
        provider.getAuthorizations(user);

        Set<String> permissions = collectPermissions(user);
        assertEquals(Set.of("read", "write"), permissions);
    }

    @Test
    @DisplayName("Should extract permissions from 'permissions' claim (Auth0 convention)")
    void shouldExtractPermissionsFromPermissionsClaim() {
        User user = User.create(new JsonObject().put("permissions", new JsonArray().add("items:read")));
        provider.getAuthorizations(user);

        Set<String> permissions = collectPermissions(user);
        assertEquals(Set.of("items:read"), permissions);
    }

    @Test
    @DisplayName("Should combine authorizations from all four claim types")
    void shouldCombineAllClaimTypes() {
        User user = User.create(new JsonObject()
                .put("roles", new JsonArray().add("admin"))
                .put("scope", "read")
                .put("scp", new JsonArray().add("write"))
                .put("permissions", new JsonArray().add("items:delete")));
        provider.getAuthorizations(user);

        Set<String> roles = collectRoles(user);
        Set<String> permissions = collectPermissions(user);

        assertEquals(Set.of("admin"), roles);
        assertEquals(Set.of("read", "write", "items:delete"), permissions);
    }

    @Test
    @DisplayName("Should skip non-string elements in roles array")
    void shouldSkipNonStringArrayElements() {
        User user = User.create(new JsonObject()
                .put("roles", new JsonArray().add("user").add(123).addNull()));
        provider.getAuthorizations(user);

        Set<String> roles = collectRoles(user);
        assertEquals(Set.of("user"), roles);
    }

    @Test
    @DisplayName("Should add no authorizations for empty 'scope' claim")
    void shouldHandleEmptyScopeClaim() {
        User user = User.create(new JsonObject().put("scope", ""));
        provider.getAuthorizations(user);

        Set<String> permissions = collectPermissions(user);
        assertTrue(permissions.isEmpty());
    }

    @Test
    @DisplayName("Should add no authorizations for blank 'scope' claim")
    void shouldHandleBlankScopeClaim() {
        User user = User.create(new JsonObject().put("scope", "  "));
        provider.getAuthorizations(user);

        Set<String> permissions = collectPermissions(user);
        assertTrue(permissions.isEmpty());
    }

    @Test
    @DisplayName("Should extract scopes from 'scp' claim as space-delimited string")
    void shouldExtractScopesFromScpClaimAsString() {
        User user = User.create(new JsonObject().put("scp", "read write"));
        provider.getAuthorizations(user);

        Set<String> permissions = collectPermissions(user);
        assertTrue(permissions.contains("read"));
        assertTrue(permissions.contains("write"));
    }

    @Test
    @DisplayName("Should extract scopes from 'scope' claim as JSON array")
    void shouldExtractScopesFromScopeClaimAsArray() {
        User user = User.create(
                new JsonObject().put("scope", new JsonArray().add("read").add("write")));
        provider.getAuthorizations(user);

        Set<String> permissions = collectPermissions(user);
        assertTrue(permissions.contains("read"));
        assertTrue(permissions.contains("write"));
    }

    @Test
    @DisplayName("Should extract roles from 'roles' claim as space-delimited string")
    void shouldExtractRolesFromRolesClaimAsString() {
        User user = User.create(new JsonObject().put("roles", "admin user"));
        provider.getAuthorizations(user);

        Set<String> roles = collectRoles(user);
        assertTrue(roles.contains("admin"));
        assertTrue(roles.contains("user"));
    }

    // --- Helpers ---

    private Set<String> collectRoles(User user) {
        Set<String> roles = new HashSet<>();
        user.authorizations().forEach((id, authorization) -> {
            if (authorization instanceof RoleBasedAuthorization rba) {
                roles.add(rba.getRole());
            }
        });
        return roles;
    }

    private Set<String> collectPermissions(User user) {
        Set<String> permissions = new HashSet<>();
        user.authorizations().forEach((id, authorization) -> {
            if (authorization instanceof PermissionBasedAuthorization pba) {
                permissions.add(pba.getPermission());
            }
        });
        return permissions;
    }
}
