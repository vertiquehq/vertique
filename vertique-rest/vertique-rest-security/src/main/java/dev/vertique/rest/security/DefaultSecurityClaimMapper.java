// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import io.vertx.core.json.JsonArray;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Default {@link SecurityClaimMapper} that maps well-known JWT claim conventions to the
 * framework's typed {@link AuthorizationClaims} model.
 *
 * <p>Claim mappings:
 * <ul>
 *   <li>{@code "roles"} claim → {@link AuthorityKind#ROLE} claims; commonly set by enterprise
 *       identity providers such as Azure AD and Keycloak (via token-mapper rules)</li>
 *   <li>{@code "scope"} and {@code "scp"} claims → {@link AuthorityKind#SCOPE} claims; the
 *       standard OAuth 2.0 delegation scope (RFC 6749 §3.3); Azure AD uses {@code "scp"}</li>
 *   <li>{@code "permissions"} claim → {@link AuthorityKind#PERMISSION} claims; the Auth0
 *       resource-server permission convention</li>
 * </ul>
 *
 * <p>Each claim may be provided as a JSON array of strings or as a single space-delimited
 * string (e.g., {@code "read write"}). Non-string array elements and blank values are silently
 * skipped. A missing claim yields no entries for that kind.
 *
 * <p>All produced {@link AuthorityClaim} entries use empty strings for {@code issuer},
 * {@code audience}, and {@code source} — these fields are populated with real values by the
 * full evidence pipeline once {@code IdentityResolutionMiddleware} is wired in later slices.
 *
 * <p>Applications that use a non-standard identity provider should bind a custom
 * {@link SecurityClaimMapper} implementation via Dagger's optional binding mechanism.
 *
 * @see SecurityClaimMapper
 * @see AuthModule
 */
public class DefaultSecurityClaimMapper implements SecurityClaimMapper {

    /**
     * Creates a new {@code DefaultSecurityClaimMapper}.
     */
    public DefaultSecurityClaimMapper() {}

    /**
     * {@inheritDoc}
     *
     * <p>Extracts:
     * <ul>
     *   <li>roles from the {@code "roles"} claim → {@link AuthorityKind#ROLE}</li>
     *   <li>scopes from the {@code "scope"} and {@code "scp"} claims (combined) →
     *       {@link AuthorityKind#SCOPE}</li>
     *   <li>permissions from the {@code "permissions"} claim → {@link AuthorityKind#PERMISSION}</li>
     * </ul>
     *
     * @param claims the raw principal claims; must not be {@code null}
     * @return the extracted authorization claims; never {@code null}
     */
    @Override
    public AuthorizationClaims map(Map<String, Object> claims) {
        Set<String> roles = parseClaim(claims, "roles");

        Set<String> scopes = new HashSet<>();
        scopes.addAll(parseClaim(claims, "scope"));
        scopes.addAll(parseClaim(claims, "scp"));

        Set<String> permissions = parseClaim(claims, "permissions");

        Set<AuthorityClaim> authorityClaims = new HashSet<>();

        for (String role : roles) {
            authorityClaims.add(new AuthorityClaim(AuthorityKind.ROLE, role, "", "", "", Map.of()));
        }
        for (String scope : scopes) {
            authorityClaims.add(new AuthorityClaim(AuthorityKind.SCOPE, scope, "", "", "", Map.of()));
        }
        for (String permission : permissions) {
            authorityClaims.add(new AuthorityClaim(AuthorityKind.PERMISSION, permission, "", "", "", Map.of()));
        }

        return new AuthorizationClaims(authorityClaims, Map.of());
    }

    /**
     * Parses a single claim value that may be either a {@link List} of strings or a
     * space-delimited string. Non-string list elements and blank values are silently skipped.
     *
     * @param claims    the raw claims map
     * @param claimName the claim key to look up
     * @return a mutable set of extracted string values; empty if claim is absent or blank
     */
    private Set<String> parseClaim(Map<String, Object> claims, String claimName) {
        Object value = claims.get(claimName);
        if (value == null) {
            return Set.of();
        }

        if (value instanceof JsonArray jsonArray) {
            return jsonArray.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toSet());
        }

        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toSet());
        }

        if (value instanceof String str && !str.isBlank()) {
            return Arrays.stream(str.split("\\s+")).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        }

        return Set.of();
    }
}
