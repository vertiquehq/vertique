// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nullable;

/**
 * Typed configuration for JWT bearer authentication, parsed from the {@code "jwt"} section of the
 * application config.
 *
 * <p>This record collapses the JWT scheme name and the {@link JwtValidationConfig} into a single
 * typed-config object so {@link JwtAuthModule} has exactly one override seam (the app may bind its
 * own {@link JwtAuthConfig}) rather than two separate optional bindings.
 *
 * <p>Records are Jackson-deserialisable via the {@link #fromJson} {@code @JsonCreator}, which fills
 * defaults for any omitted property; the compact constructor additionally normalizes {@code null}
 * components to their defaults so a partial {@code jwt} object (e.g. {@code schemeName} only) is
 * safe. The framework loads this from the {@code "jwt"} section via
 * {@link dev.vertique.core.config.JsonConfigPaths#navigateObject} and
 * {@link dev.vertique.core.config.ConfigParser#parse}. Apps that need a fully programmatic override
 * can provide their own {@code @Provides JwtAuthConfig} — see {@link JwtAuthModule} for the lookup
 * logic.
 *
 * <p>Example JSON configuration:
 * <pre>{@code
 * {
 *   "jwt": {
 *     "schemeName": "bearerAuth",
 *     "validation": {
 *       "issuer": "https://auth.example.com/",
 *       "audience": ["https://api.example.com"],
 *       "clockSkewSeconds": 30
 *     }
 *   }
 * }
 * }</pre>
 *
 * @param schemeName the OpenAPI security scheme name the JWT handler registers under (default
 *                   {@code "bearerAuth"})
 * @param validation the JWT validation constraints (issuer / audience / clock skew); defaults to an
 *                   empty {@link JwtValidationConfig} when omitted. Its {@code issuer}/{@code audience}
 *                   drive post-authentication enforcement in {@link JwtBearerSecuritySchemeHandler}
 *                   (rejecting mismatched tokens), not merely evidence/rejection metadata
 */
public record JwtAuthConfig(String schemeName, JwtValidationConfig validation) {

    /** Default scheme name applied when no {@code jwt.schemeName} is configured. */
    private static final String DEFAULT_SCHEME_NAME = "bearerAuth";

    /**
     * Compact constructor: normalizes {@code null} components to their defaults so a partial
     * {@code jwt} object deserializes safely.
     */
    public JwtAuthConfig {
        if (schemeName == null) {
            schemeName = DEFAULT_SCHEME_NAME;
        }
        if (validation == null) {
            validation = JwtValidationConfig.builder().build();
        }
    }

    /**
     * Jackson-friendly factory that fills in defaults for any omitted JSON properties. Used by
     * {@link JwtAuthModule} when mapping the {@code "jwt"} section to this record.
     *
     * @param schemeName the scheme name; defaults to {@code "bearerAuth"} when {@code null}
     * @param validation the validation config; defaults to an empty {@link JwtValidationConfig} when
     *                   {@code null}
     * @return the deserialised config
     */
    @JsonCreator
    public static JwtAuthConfig fromJson(
            @JsonProperty("schemeName") @Nullable String schemeName,
            @JsonProperty("validation") @Nullable JwtValidationConfig validation) {
        JwtAuthConfig d = defaults();
        return new JwtAuthConfig(
                schemeName != null ? schemeName : d.schemeName, validation != null ? validation : d.validation);
    }

    /**
     * Default configuration: scheme name {@code "bearerAuth"} and an empty
     * {@link JwtValidationConfig} (no issuer/audience constraints, 30-second clock skew).
     *
     * @return the default config; never {@code null}
     */
    public static JwtAuthConfig defaults() {
        return new JwtAuthConfig(
                DEFAULT_SCHEME_NAME, JwtValidationConfig.builder().build());
    }
}
