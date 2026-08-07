// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.vertique.core.exception.ConfigurationException;
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
 *   <li>{@link io.vertx.ext.auth.jwt.JWTAuth} enforces them as {@code JWTOptions} when this config
 *       is passed to a {@link JwtAuthFactory} overload that takes one; apps that build their
 *       {@link io.vertx.ext.auth.jwt.JWTAuth} another way do not get this layer.</li>
 *   <li>{@link JwtBearerSecuritySchemeHandler} additionally enforces {@link #issuer()} and
 *       {@link #audience()} post-authentication as <em>defense-in-depth</em>, so they are honored
 *       regardless of how the {@link io.vertx.ext.auth.jwt.JWTAuth} was built.</li>
 * </ul>
 *
 * <p>{@link #clockSkewSeconds()} has <strong>no such second layer</strong>, and cannot have one:
 * leeway is purely permissive, so once the underlying {@link io.vertx.ext.auth.jwt.JWTAuth} has
 * rejected a token whose time claims fall outside the window, no later check can un-reject it. It
 * therefore applies only at construction, as leeway for the {@code exp}, {@code nbf} and
 * {@code iat} checks.
 *
 * <p>Every {@link JwtAuthFactory} method applies a config for exactly that reason: the overloads
 * that take none apply {@code JwtValidationConfig.builder().build()}, so this type's documented
 * default clock skew reaches Vert.x rather than Vert.x's own default of {@code 0}. A provider built
 * by the factory also carries the config it applied, and {@link JwtAuthModule} fails startup when
 * that differs from {@code jwt.validation.clockSkewSeconds} — so a value set in configuration but
 * never passed to the factory is reported rather than silently ignored.
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
     * Maximum permitted clock skew, in seconds.
     *
     * <p>RFC 7519 §4.1.4 contemplates "some small leeway, usually no more than a few minutes" for
     * time-claim validation. Beyond five minutes a misconfiguration is likelier than an intent, and
     * the cost of accepting it silently is a permanently widened {@code exp}/{@code nbf}/{@code iat}
     * acceptance window.
     */
    public static final int MAX_CLOCK_SKEW_SECONDS = 300;

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
     * Must be between {@code 0} and {@link #MAX_CLOCK_SKEW_SECONDS} inclusive; a value outside that
     * range fails construction with a
     * {@link dev.vertique.core.exception.ConfigurationException}.
     */
    @Builder.Default
    private final int clockSkewSeconds = 30;

    /**
     * Validates the clock skew and assigns every field.
     *
     * <p>This type is a Lombok {@code @Builder} class rather than a record, so there is no compact
     * constructor to host the bounds check. Declaring the all-args constructor explicitly gives the
     * check a single choke point: the class-level {@code @Builder} reuses this constructor instead
     * of generating one, so both {@code builder().build()} and {@code @Jacksonized} deserialization
     * (which routes through the builder) are validated here.
     *
     * @param issuer           the expected issuer, or {@code null} to leave it unconstrained
     * @param audience         the expected audience values, or {@code null} to leave them
     *                         unconstrained
     * @param clockSkewSeconds the permitted clock skew in seconds
     * @throws dev.vertique.core.exception.ConfigurationException if {@code clockSkewSeconds} is
     *         negative or greater than {@link #MAX_CLOCK_SKEW_SECONDS}
     */
    private JwtValidationConfig(String issuer, List<String> audience, int clockSkewSeconds) {
        if (clockSkewSeconds < 0 || clockSkewSeconds > MAX_CLOCK_SKEW_SECONDS) {
            throw new ConfigurationException("jwt.validation.clockSkewSeconds must be between 0 and "
                    + MAX_CLOCK_SKEW_SECONDS + " seconds, but was " + clockSkewSeconds);
        }
        this.issuer = issuer;
        this.audience = audience;
        this.clockSkewSeconds = clockSkewSeconds;
    }
}
