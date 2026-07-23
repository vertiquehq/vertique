// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * Configuration for JWT token validation constraints applied at authentication time.
 *
 * <p>All fields are optional (nullable). When a field is {@code null}, the corresponding
 * validation is skipped, which is permissive but convenient for development environments.
 * Production deployments should always configure {@link #issuer()} and {@link #audience()}.
 *
 * <p>Example JSON configuration:
 * <pre>{@code
 * {
 *   "issuer": "https://auth.example.com/",
 *   "audience": ["https://api.example.com"],
 *   "clockSkewSeconds": 30
 * }
 * }</pre>
 *
 * <p>Bind an instance in the application's Dagger module and pass it to
 * {@link JwtAuthFactory#fromJwks(io.vertx.core.Vertx, String, JwtValidationConfig)} (or the
 * relevant overload) when creating the {@link io.vertx.ext.auth.jwt.JWTAuth} instance.
 *
 * <h2>Where enforcement happens</h2>
 * Issuer/audience enforcement is <strong>two-layered</strong>:
 * <ul>
 *   <li>{@link io.vertx.ext.auth.jwt.JWTAuth} enforces them as {@code JWTOptions} <em>when this
 *       config is passed to {@link JwtAuthFactory}</em> (the {@code fromJwks(..., JwtValidationConfig)}
 *       overload); apps that build their {@link io.vertx.ext.auth.jwt.JWTAuth} another way do not get
 *       this layer.</li>
 *   <li>{@link JwtBearerSecuritySchemeHandler} additionally enforces {@link #issuer()} and
 *       {@link #audience()} post-authentication as <em>defense-in-depth</em>, so they are honored
 *       regardless of how the {@link io.vertx.ext.auth.jwt.JWTAuth} was built.</li>
 * </ul>
 * {@link #clockSkewSeconds()} applies only at {@link io.vertx.ext.auth.jwt.JWTAuth} construction
 * (leeway for the {@code exp}/{@code nbf} time-claim check); it is not re-applied in the handler.
 *
 * @see JwtAuthFactory
 * @see JwtBearerSecuritySchemeHandler
 */
@Getter
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public class JwtValidationConfig {

    /**
     * Expected issuer ({@code iss} claim). When non-null, tokens with a different or missing
     * issuer are rejected. Should be the full issuer URI of the identity provider.
     */
    private final String issuer;

    /**
     * Expected audience ({@code aud} claim). When non-null and non-empty, tokens that do not
     * include at least one of these audience values are rejected. RFC 7519 allows the audience
     * claim to be a string or an array of strings.
     */
    private final List<String> audience;

    /**
     * Permitted clock skew in seconds when validating time-based claims ({@code exp}, {@code nbf},
     * {@code iat}). Defaults to {@code 30} seconds to tolerate minor clock drift between services.
     */
    @Builder.Default
    private final int clockSkewSeconds = 30;
}
