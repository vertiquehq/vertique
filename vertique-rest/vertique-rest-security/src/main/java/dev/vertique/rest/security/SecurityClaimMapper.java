// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.security.authz.AuthorizationClaims;
import java.util.Map;

/**
 * SPI for mapping raw JWT (or other auth provider) principal claims to the framework's typed
 * {@link AuthorizationClaims} model.
 *
 * <p>The default implementation ({@link DefaultSecurityClaimMapper}) handles the most common
 * JWT claim conventions:
 * <ul>
 *   <li>{@code "roles"} → {@link dev.vertique.security.authz.AuthorityKind#ROLE} claims</li>
 *   <li>{@code "scope"} / {@code "scp"} → {@link dev.vertique.security.authz.AuthorityKind#SCOPE}
 *       claims</li>
 *   <li>{@code "permissions"} → {@link dev.vertique.security.authz.AuthorityKind#PERMISSION}
 *       claims</li>
 * </ul>
 *
 * <p>Applications that use a different identity provider (e.g., Keycloak with nested
 * {@code realm_access.roles}) can provide a custom implementation and bind it via
 * Dagger's optional binding in {@link AuthModule}:
 * <pre>{@code
 * @Provides
 * SecurityClaimMapper myClaimMapper() {
 *     return claims -> { ... };
 * }
 * }</pre>
 *
 * <p>Implementations must be thread-safe; they are created as singletons and invoked on
 * each authenticated request.
 */
@FunctionalInterface
public interface SecurityClaimMapper {

    /**
     * Maps raw principal claims to the typed {@link AuthorizationClaims} model.
     *
     * <p>Implementations must not throw checked exceptions; unrecognized or missing claims
     * should produce empty claim sets rather than failures.
     *
     * @param claims the raw principal claims map (e.g., decoded JWT payload); never {@code null}
     * @return the extracted authorization claims; must not be {@code null}
     */
    AuthorizationClaims map(Map<String, Object> claims);
}
