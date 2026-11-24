// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
 * <p>{@link DefaultSecurityClaimMapper} and {@link dev.vertique.rest.auth.jwt.JwtClaimAuthorizationProvider}
 * both delegate to this class, so the framework reads a token's authority claims one way.
 *
 * <p>A claim value is read only when it is a JSON array or a {@link Collection} (its non-blank
 * string elements) or a string (split on whitespace). Any other value, such as a number, boolean,
 * JSON object or map, contributes nothing, so structured data is never read as authority names.
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
     * Extracts string values from a claim that is a JSON array, a {@link Collection}, or a
     * space-delimited string. Non-string elements and blank values are skipped; any other value
     * (a number, boolean, JSON object, map, or any other type) contributes nothing.
     *
     * @param principal the JWT principal {@link JsonObject}
     * @param claimName the name of the claim to extract
     * @return a mutable set of string values from the claim; empty if claim is absent, blank, or of
     *     another type
     */
    static Set<String> extractClaim(JsonObject principal, String claimName) {
        // The raw value: JsonObject#getString would turn any value into text.
        Object value = principal.getMap().get(claimName);
        Stream<?> items;
        if (value instanceof JsonArray array) {
            items = array.stream();
        } else if (value instanceof Collection<?> collection) {
            items = collection.stream();
        } else if (value instanceof String string) {
            items = Arrays.stream(string.split("\\s+"));
        } else {
            return new HashSet<>();
        }
        return items.filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(HashSet::new));
    }
}
