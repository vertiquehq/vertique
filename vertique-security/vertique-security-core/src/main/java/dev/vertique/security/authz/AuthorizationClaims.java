// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable collection of {@link AuthorityClaim} entries held by a principal, together with
 * optional context attributes.
 *
 * <p>This record acts as the typed replacement for the flat role/scope/permission sets on
 * {@link dev.vertique.security.SecurityContext}. Authorization policy evaluators receive an
 * {@code AuthorizationClaims} instance and use {@link #valuesOf(AuthorityKind)} to query specific
 * categories without iterating the full claim set manually.
 *
 * <p>Construction rules:
 * <ul>
 *   <li>A null {@code claims} set is treated as {@link Set#of()} (empty)</li>
 *   <li>A null {@code attributes} map is treated as {@link Map#of()} (empty)</li>
 *   <li>Both collections are defensively copied</li>
 * </ul>
 *
 * @param claims     the full set of authority claims held by the principal
 * @param attributes optional context attributes associated with this claim collection
 */
public record AuthorizationClaims(Set<AuthorityClaim> claims, Map<String, Object> attributes) {

    /**
     * Compact constructor — defensively copies both collections.
     */
    public AuthorizationClaims {
        claims = Set.copyOf(claims == null ? Set.of() : claims);
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
    }

    /**
     * Returns an empty {@code AuthorizationClaims} instance with no claims and no attributes.
     *
     * @return empty {@code AuthorizationClaims}
     */
    public static AuthorizationClaims empty() {
        return new AuthorizationClaims(Set.of(), Map.of());
    }

    /**
     * Returns all claim values for the given {@link AuthorityKind}.
     *
     * <p>The returned set is unmodifiable. If no claims of the requested kind are present, an
     * empty set is returned.
     *
     * @param kind the authority category to filter on; must not be {@code null}
     * @return unmodifiable set of claim values matching the requested kind
     * @throws NullPointerException if {@code kind} is {@code null}
     */
    public Set<String> valuesOf(AuthorityKind kind) {
        Objects.requireNonNull(kind, "kind");
        return claims.stream()
                .filter(c -> c.kind() == kind)
                .map(AuthorityClaim::value)
                .collect(Collectors.toUnmodifiableSet());
    }
}
