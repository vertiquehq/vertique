// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authentication.Credentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import java.util.Objects;

/**
 * A {@link JWTAuth} that forwards every call to a delegate and carries the
 * {@link JwtValidationConfig} the framework applied when building that delegate.
 *
 * <p>{@link JwtAuthFactory} wraps every {@link JWTAuth} it returns in this type so
 * {@link JwtAuthModule} can compare what was applied against the effective configuration at startup
 * (see {@link ValidationAttested}). The wrapper adds no behavior: it never re-validates, never
 * re-applies the config, and never inspects the token.
 *
 * <p>Wrapping is safe through the framework's own consumption path — {@code JWTAuthHandler.create}
 * accepts the {@link JWTAuth} <em>interface</em> and never casts to a Vert.x implementation class.
 *
 * <p>All three {@link JWTAuth} methods are forwarded. Both {@code generateToken} overloads matter:
 * applications and tests sign tokens through the instance the factory returns, so dropping either
 * one would silently break signing rather than surface as a wiring error.
 */
final class AttestedJwtAuth implements JWTAuth, ValidationAttested {

    private final JWTAuth delegate;
    private final JwtValidationConfig validation;

    /**
     * Wraps {@code delegate}, attesting the validation constraints it was built with.
     *
     * @param delegate   the provider to forward to; must not be {@code null}
     * @param validation the validation constraints applied to {@code delegate}; must not be
     *                   {@code null}
     */
    AttestedJwtAuth(JWTAuth delegate, JwtValidationConfig validation) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.validation = Objects.requireNonNull(validation, "validation");
    }

    /**
     * {@inheritDoc}
     *
     * @return the validation constraints applied to the wrapped delegate; never {@code null}
     */
    @Override
    public JwtValidationConfig appliedValidation() {
        return validation;
    }

    /**
     * Returns the provider this wrapper forwards to.
     *
     * <p>Exists for {@link RefreshableJwtAuth}, which answers {@link #appliedValidation()} from its
     * own field and is therefore the sole attestation carrier on its path. Storing a wrapper it can
     * never consult would cost an allocation per refresh tick and a dispatch hop per authenticate,
     * so it unwraps what the factory hands it.
     *
     * @return the wrapped provider; never {@code null}
     */
    JWTAuth delegate() {
        return delegate;
    }

    // --- JWTAuth delegation ---

    /**
     * Authenticates the provided credentials against the wrapped delegate.
     *
     * @param credentials the credentials to authenticate
     * @return a future that completes with the authenticated {@link User}, or fails if
     *         authentication is rejected
     */
    @Override
    public Future<User> authenticate(Credentials credentials) {
        return delegate.authenticate(credentials);
    }

    /**
     * Generates a signed JWT token from the given claims and options.
     *
     * @param claims  the JWT payload claims as a {@link JsonObject}
     * @param options signing options such as algorithm and expiry
     * @return the signed JWT string
     */
    @Override
    public String generateToken(JsonObject claims, JWTOptions options) {
        return delegate.generateToken(claims, options);
    }

    /**
     * Generates a signed JWT token from the given claims using default options.
     *
     * @param claims the JWT payload claims as a {@link JsonObject}
     * @return the signed JWT string
     */
    @Override
    public String generateToken(JsonObject claims) {
        return delegate.generateToken(claims);
    }
}
