// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import io.vertx.core.json.JsonObject;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
 * <p>The claims are read by {@link JwtClaimExtractor}, the parser
 * {@code JwtClaimAuthorizationProvider} uses too, so both read a token the same way. A claim may be
 * a JSON array (or {@link java.util.Collection}) of strings or a single space-delimited string
 * (e.g., {@code "read write"}); non-string elements and blank values are skipped, and a missing
 * claim, or a value of any other type, yields no entries for that kind.
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
        // Wraps the map without copying it; the extractor only reads.
        JsonObject principal = new JsonObject(claims);

        Set<AuthorityClaim> authorityClaims = new HashSet<>();
        for (String role : JwtClaimExtractor.extractRoles(principal)) {
            authorityClaims.add(new AuthorityClaim(AuthorityKind.ROLE, role, "", "", "", Map.of()));
        }
        for (String scope : JwtClaimExtractor.extractScopes(principal)) {
            authorityClaims.add(new AuthorityClaim(AuthorityKind.SCOPE, scope, "", "", "", Map.of()));
        }
        for (String permission : JwtClaimExtractor.extractPermissions(principal)) {
            authorityClaims.add(new AuthorityClaim(AuthorityKind.PERMISSION, permission, "", "", "", Map.of()));
        }

        return new AuthorizationClaims(authorityClaims, Map.of());
    }
}
