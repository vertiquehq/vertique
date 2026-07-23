// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility for extracting roles, scopes, and permissions from JWT claim conventions.
 *
 * <p>Supports four claim formats, each handling both JSON array and space-delimited string:
 * <ul>
 *   <li>{@code "roles"} — role names (used by most identity providers)</li>
 *   <li>{@code "scope"} — OAuth 2.0 delegation scopes (RFC 8693)</li>
 *   <li>{@code "scp"} — OAuth 2.0 delegation scopes (Azure AD convention)</li>
 *   <li>{@code "permissions"} — fine-grained action rights (Auth0 convention)</li>
 * </ul>
 *
 * <p>Both {@link dev.vertique.rest.auth.jwt.JwtClaimAuthorizationProvider} and
 * {@link DefaultSecurityClaimMapper} delegate to this class for consistent claim parsing.
 */
public final class JwtClaimExtractor {

    private JwtClaimExtractor() {}

    /**
     * Extracts role names from the {@code "roles"} claim.
     * Handles both JSON array ({@code ["admin", "user"]}) and space-delimited string
     * ({@code "admin user"}).
     *
     * @param principal the JWT principal {@link JsonObject}, or {@code null}
     * @return an unmodifiable set of role name strings; never {@code null}
     */
    public static Set<String> extractRoles(JsonObject principal) {
        if (principal == null) return Set.of();
        return Collections.unmodifiableSet(extractClaim(principal, "roles"));
    }

    /**
     * Extracts OAuth 2.0 scopes from the {@code "scope"} and {@code "scp"} claims only.
     * Each claim handles both JSON array and space-delimited string formats.
     *
     * <p>Use this method when you need only delegation scopes (RFC 6749 §3.3), distinct
     * from the {@code "permissions"} claim.
     *
     * @param principal the JWT principal {@link JsonObject}, or {@code null}
     * @return an unmodifiable set of scope strings; never {@code null}
     */
    public static Set<String> extractScopes(JsonObject principal) {
        if (principal == null) return Set.of();
        Set<String> scopes = new HashSet<>();
        scopes.addAll(extractClaim(principal, "scope"));
        scopes.addAll(extractClaim(principal, "scp"));
        return Collections.unmodifiableSet(scopes);
    }

    /**
     * Extracts permission names from the {@code "permissions"} claim only (Auth0 convention).
     * Handles both JSON array and space-delimited string formats.
     *
     * <p>Use this method when you need only fine-grained action rights, distinct from the
     * OAuth 2.0 delegation scopes in {@code "scope"}/{@code "scp"}.
     *
     * @param principal the JWT principal {@link JsonObject}, or {@code null}
     * @return an unmodifiable set of permission name strings; never {@code null}
     */
    public static Set<String> extractPermissions(JsonObject principal) {
        if (principal == null) return Set.of();
        return Collections.unmodifiableSet(extractClaim(principal, "permissions"));
    }

    /**
     * Extracts string values from a claim that may be either a JSON array or a
     * space-delimited string. Non-string array elements are skipped.
     *
     * @param principal the JWT principal {@link JsonObject}
     * @param claimName the name of the claim to extract
     * @return a mutable set of string values from the claim; empty if claim is absent or blank
     */
    static Set<String> extractClaim(JsonObject principal, String claimName) {
        // Try as JSON array first
        JsonArray array = null;
        try {
            array = principal.getJsonArray(claimName);
        } catch (ClassCastException ignored) {
            // Value exists but is not an array — fall through to string handling
        }

        if (array != null) {
            return array.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toSet());
        }

        // Try as space-delimited string
        String str = null;
        try {
            str = principal.getString(claimName);
        } catch (ClassCastException ignored) {
            // Value exists but is not a string
        }

        if (str != null && !str.isBlank()) {
            return Arrays.stream(str.split("\\s+")).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        }

        return Set.of();
    }
}
