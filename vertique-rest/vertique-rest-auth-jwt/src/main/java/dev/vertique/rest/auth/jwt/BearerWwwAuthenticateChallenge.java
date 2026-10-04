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
     * Appends this bearer challenge to {@code WWW-Authenticate} on the response.
     *
     * <p>Uses {@code headers().add} rather than {@code putHeader}: an OR chain walks every member's
     * {@code setAuthenticateHeader}, and Vert.x HTTP auth handlers append their own challenges. A
     * replace would drop earlier Bearer realms and any non-Bearer challenge (e.g. Basic) already on
     * the response. An identical value already present is left alone (idempotent for the
     * single-scheme / claims paths that call this once).
     *
     * @param ctx              the routing context whose response receives the challenge
     * @param validationConfig JWT validation config whose issuer (when present) becomes the realm
     * @return {@code true} after the header is ensured
     */
    static boolean apply(RoutingContext ctx, JwtValidationConfig validationConfig) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(validationConfig, "validationConfig");
        String value = challengeValue(validationConfig);
        if (!ctx.response().headers().getAll(HEADER).contains(value)) {
            ctx.response().headers().add(HEADER, value);
        }
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
        // RFC 9110 §5.6.4: escape backslash before quote so a trailing '\' cannot consume the
        // closing quote; refuse CR/LF (illegal in qdtext / quoted-pair).
        String realm = issuer.replace("\\", "\\\\").replace("\"", "\\\"");
        if (realm.indexOf('\r') != -1 || realm.indexOf('\n') != -1) {
            return "Bearer";
        }
        return "Bearer realm=\"" + realm + "\"";
    }
}
