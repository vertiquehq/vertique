// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import io.vertx.ext.web.RoutingContext;
import java.util.Objects;

/**
 * Builds and applies the RFC 6750 / RFC 9110 {@code WWW-Authenticate} challenge for JWT bearer
 * 401 responses.
 *
 * <p>The auth scheme is always {@code Bearer}. When {@link JwtValidationConfig#issuer()} is
 * configured it is used as the {@code realm}; otherwise the challenge is the bare scheme name,
 * which still satisfies RFC 9110 §11.6.1.
 */
final class BearerWwwAuthenticateChallenge {

    /** HTTP challenge header name (Vert.x {@code HttpHeaders} has no constant for it). */
    static final String HEADER = "WWW-Authenticate";

    private BearerWwwAuthenticateChallenge() {}

    /**
     * Sets {@code WWW-Authenticate} on the response from the configured validation metadata.
     *
     * @param ctx              the routing context whose response receives the challenge
     * @param validationConfig JWT validation config whose issuer (when present) becomes the realm
     * @return {@code true} after the header is set
     */
    static boolean apply(RoutingContext ctx, JwtValidationConfig validationConfig) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(validationConfig, "validationConfig");
        ctx.response().putHeader(HEADER, challengeValue(validationConfig));
        return true;
    }

    /**
     * Formats the challenge header value.
     *
     * @param validationConfig JWT validation config whose issuer (when present) becomes the realm
     * @return {@code Bearer} or {@code Bearer realm="<issuer>"}
     */
    static String challengeValue(JwtValidationConfig validationConfig) {
        Objects.requireNonNull(validationConfig, "validationConfig");
        String issuer = validationConfig.issuer();
        if (issuer == null || issuer.isBlank()) {
            return "Bearer";
        }
        // Match Vert.x HTTPAuthorizationHandler: escape quotes; refuse CR/LF in the realm.
        String realm = issuer.replace("\"", "\\\"");
        if (realm.indexOf('\r') != -1 || realm.indexOf('\n') != -1) {
            return "Bearer";
        }
        return "Bearer realm=\"" + realm + "\"";
    }
}
