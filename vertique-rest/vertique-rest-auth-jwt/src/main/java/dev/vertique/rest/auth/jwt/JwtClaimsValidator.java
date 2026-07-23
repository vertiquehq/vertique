// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import java.util.Map;

/**
 * SPI for custom JWT claim validation beyond the standard signature, expiry, issuer, and audience
 * checks performed by {@link io.vertx.ext.auth.jwt.JWTAuth}.
 *
 * <p>Implement this interface to enforce business-specific token constraints, such as:
 * <ul>
 *   <li>Required custom claims (e.g., tenant ID, environment tag)</li>
 *   <li>Scope or permission allow-listing</li>
 *   <li>Token revocation checks against an external store</li>
 * </ul>
 *
 * <p>The validator is invoked by {@link JwtBearerSecuritySchemeHandler} after the standard JWT
 * authentication succeeds. If the validator throws a {@link SecurityException}, the request is
 * rejected with {@code 401 Unauthorized}.
 *
 * <p>Example — require a {@code tenant_id} claim:
 * <pre>{@code
 * @Provides
 * JwtClaimsValidator tenantValidator() {
 *     return claims -> {
 *         if (!claims.containsKey("tenant_id")) {
 *             throw new SecurityException("Missing required 'tenant_id' claim");
 *         }
 *     };
 * }
 * }</pre>
 *
 * <p>Applications that need multiple validators should compose them in a single implementation
 * rather than providing multiple bindings. Only one optional binding is supported by
 * {@link JwtAuthModule}.
 *
 * <p>Implementations must be thread-safe; the validator is a singleton and invoked concurrently.
 */
@FunctionalInterface
public interface JwtClaimsValidator {

    /**
     * Validates the decoded JWT claims after standard signature and expiry verification.
     *
     * <p>Throw {@link SecurityException} to reject the token; any other unchecked exception
     * will also cause the request to fail with {@code 401}.
     *
     * @param claims the decoded JWT payload as a raw map; never {@code null}
     * @throws SecurityException if the token fails custom validation
     */
    void validate(Map<String, Object> claims) throws SecurityException;
}
