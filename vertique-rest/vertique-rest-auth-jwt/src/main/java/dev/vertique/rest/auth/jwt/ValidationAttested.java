// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.jwt.JWTAuth;

/**
 * Attests the {@link JwtValidationConfig} a framework factory applied to a {@link JWTAuth}.
 *
 * <p>Vert.x exposes no accessor for the {@link JWTOptions} a {@link JWTAuth} was built with, so
 * nothing downstream can <em>discover</em> whether the configured clock-skew leeway actually reached
 * the provider. Every {@link JWTAuth} the framework builds therefore reports it here, and
 * {@link JwtAuthModule} compares that report against the effective {@link JwtAuthConfig} at startup.
 *
 * <p>Deliberately package-private: attestation is an internal wiring check, not a public contract.
 * An application's {@code @Provides JWTAuth} binding keeps its plain {@link JWTAuth} signature, and
 * a provider the framework did not build simply carries no attestation — which the module treats as
 * "not the framework's business" rather than as an error.
 *
 * <p>The attestation is self-reported. It catches a wiring slip — a config set in one place and a
 * provider built in another — not a determined caller.
 *
 * @see AttestedJwtAuth
 * @see RefreshableJwtAuth
 */
interface ValidationAttested {

    /**
     * Returns the validation constraints the framework applied when building this provider.
     *
     * @return the applied validation config; never {@code null}
     */
    JwtValidationConfig appliedValidation();
}
