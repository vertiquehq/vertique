// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import dev.vertique.rest.security.JwtClaimExtractor;
import io.vertx.core.Future;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authorization.Authorization;
import io.vertx.ext.auth.authorization.AuthorizationProvider;
import io.vertx.ext.auth.authorization.PermissionBasedAuthorization;
import io.vertx.ext.auth.authorization.RoleBasedAuthorization;
import java.util.HashSet;
import java.util.Set;

/**
 * Extracts roles and permissions from JWT claims and populates the Vert.x user's authorization
 * cache.
 *
 * <p>This provider reads the following well-known JWT claim conventions. Each claim supports
 * both a JSON array and a space-delimited string form:
 * <ul>
 *   <li>{@code "roles"} — converted to {@link RoleBasedAuthorization} for each string element;
 *       compatible with common identity providers</li>
 *   <li>{@code "scope"} (RFC 8693, OAuth 2.0) — converted to {@link PermissionBasedAuthorization}
 *       for each token; supports space-delimited string ({@code "read write"}) and JSON array
 *       ({@code ["read", "write"]})</li>
 *   <li>{@code "scp"} — converted to {@link PermissionBasedAuthorization} for each element;
 *       Azure AD convention; supports both JSON array and space-delimited string</li>
 *   <li>{@code "permissions"} — converted to {@link PermissionBasedAuthorization} for each
 *       element; Auth0 convention; supports both JSON array and space-delimited string</li>
 * </ul>
 *
 * <p>Scopes ({@code scope}/{@code scp}) and permissions ({@code permissions}) are both
 * stored as {@link PermissionBasedAuthorization}. The
 * {@link dev.vertique.rest.security.IdentityResolutionMiddleware} keeps them separate in the
 * framework's {@link dev.vertique.security.SecurityContext} via
 * {@link dev.vertique.rest.security.SecurityClaimMapper}.
 *
 * <p>Non-string array elements and blank values are silently skipped. All extracted authorizations
 * are stored under the provider ID {@code "jwt-claims"}.
 *
 * <p>Claim parsing is delegated to {@link JwtClaimExtractor} for consistent behaviour with
 * {@link dev.vertique.rest.security.DefaultSecurityClaimMapper}.
 *
 * <p>The extracted authorizations can be enforced via {@code @RolesAllowed} and
 * {@code @Authorized(scopes = ...)} annotations on JAX-RS resource methods.
 */
public class JwtClaimAuthorizationProvider implements AuthorizationProvider {

    private static final String PROVIDER_ID = "jwt-claims";

    /**
     * Returns the unique provider identifier used to store authorizations in the user's cache.
     *
     * @return {@code "jwt-claims"}
     */
    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    /**
     * Extracts authorizations from the user's JWT claims and stores them in the user's
     * authorization cache.
     *
     * <p>If {@code user} or {@code user.principal()} is {@code null}, returns a succeeded
     * future immediately without modifying state.
     *
     * @param user the authenticated user whose JWT claims are to be extracted
     * @return a future that completes when authorizations have been extracted and stored
     */
    @Override
    public Future<Void> getAuthorizations(User user) {
        if (user == null || user.principal() == null) {
            return Future.succeededFuture();
        }

        var principal = user.principal();
        Set<Authorization> authorizations = new HashSet<>();

        for (String role : JwtClaimExtractor.extractRoles(principal)) {
            authorizations.add(RoleBasedAuthorization.create(role));
        }

        for (String scope : JwtClaimExtractor.extractScopes(principal)) {
            authorizations.add(PermissionBasedAuthorization.create(scope));
        }

        for (String perm : JwtClaimExtractor.extractPermissions(principal)) {
            authorizations.add(PermissionBasedAuthorization.create(perm));
        }

        user.authorizations().put(PROVIDER_ID, authorizations);
        return Future.succeededFuture();
    }
}
