// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that {@link JwtValidationConfig#clockSkewSeconds()} reaches the Vert.x
 * {@link JWTOptions} as leeway on the <em>default</em> (no-config) {@link JwtAuthFactory}
 * construction paths.
 *
 * <p>{@link JWTAuth} exposes no accessor for the {@link JWTOptions} it was built with, so the
 * applied leeway can only be proven <em>behaviorally</em>: mint a token whose {@code exp},
 * {@code nbf}, or {@code iat} claim sits a known distance outside the valid window, then assert on
 * the {@code authenticate(...)} outcome. A token 10 s outside the window must be accepted because
 * the documented default skew is 30 s; a token 120 s outside it must still be rejected.
 *
 * <p>Assertions deliberately match the authentication <em>outcome</em> only — never the failure
 * message or exception type. Vert.x reports expired-, not-yet-valid-, and issued-in-the-future
 * rejections with the identical message {@code "Invalid JWT token: token expired."}, so those
 * carry no discriminating information.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class JwtValidationLeewayTest {

    /**
     * The symmetric key used for every test. It is exactly the key inside
     * {@code src/test/resources/test-jwks.json}, whose {@code k} member is this string
     * base64url-encoded.
     */
    private static final String SECRET = "super-secret-key-for-testing-minimum-256-bits-long!";

    private static final String ALGORITHM = "HS256";

    @Test
    @DisplayName("Symmetric-key path applies the documented 30 s default leeway to an expired token")
    void shouldApplyDocumentedDefaultLeewayOnSymmetricKeyPath(Vertx vertx, VertxTestContext testContext) {
        JWTAuth auth = JwtAuthFactory.fromSymmetricKey(vertx, ALGORITHM, SECRET);

        // exp 10 s in the past — inside the documented 30 s default skew, so it must be accepted.
        String token = sign(auth, new JsonObject().put("sub", "leeway-exp-user").put("exp", secondsFromNow(-10)));

        assertAuthenticationSucceeds(auth, token, testContext);
    }

    @Test
    @DisplayName("Symmetric-key path rejects a token expired beyond the default leeway")
    void shouldRejectTokenBeyondDefaultLeeway(Vertx vertx, VertxTestContext testContext) {
        JWTAuth auth = JwtAuthFactory.fromSymmetricKey(vertx, ALGORITHM, SECRET);

        // exp 120 s in the past — well outside the 30 s default skew, so it must be rejected.
        String token =
                sign(auth, new JsonObject().put("sub", "far-expired-user").put("exp", secondsFromNow(-120)));

        assertAuthenticationFails(auth, token, testContext);
    }

    @Test
    @DisplayName("JWKS path applies the documented 30 s default leeway to an expired token")
    void shouldApplyDocumentedDefaultLeewayOnJwksPath(Vertx vertx, VertxTestContext testContext) {
        // Sign and verify with the same instance: the JWKS entry carries "kid": "test-key", and a
        // separately built symmetric auth would sign without that kid.
        JWTAuth auth = JwtAuthFactory.fromJwks(vertx, "classpath:test-jwks.json");

        String token =
                sign(auth, new JsonObject().put("sub", "jwks-leeway-user").put("exp", secondsFromNow(-10)));

        assertAuthenticationSucceeds(auth, token, testContext);
    }

    @Test
    @DisplayName("Default leeway also covers a not-before claim slightly in the future")
    void shouldApplyDefaultLeewayToNotBeforeClaim(Vertx vertx, VertxTestContext testContext) {
        JWTAuth auth = JwtAuthFactory.fromSymmetricKey(vertx, ALGORITHM, SECRET);

        // nbf 10 s in the future — inside the documented 30 s default skew, so it must be accepted.
        String token = sign(auth, new JsonObject().put("sub", "nbf-user").put("nbf", secondsFromNow(10)));

        assertAuthenticationSucceeds(auth, token, testContext);
    }

    @Test
    @DisplayName("Default leeway also covers an issued-at claim slightly in the future")
    void shouldApplyDefaultLeewayToIssuedAtClaim(Vertx vertx, VertxTestContext testContext) {
        JWTAuth auth = JwtAuthFactory.fromSymmetricKey(vertx, ALGORITHM, SECRET);

        // iat 10 s in the future — inside the documented 30 s default skew, so it must be accepted.
        String token = sign(auth, new JsonObject().put("sub", "iat-user").put("iat", secondsFromNow(10)));

        assertAuthenticationSucceeds(auth, token, testContext);
    }

    // --- Helpers ---

    /** Returns the epoch-second value {@code offsetSeconds} away from now (negative = past). */
    private static long secondsFromNow(long offsetSeconds) {
        return Instant.now().getEpochSecond() + offsetSeconds;
    }

    /**
     * Signs the supplied claims. Time claims are placed explicitly in the claims object rather than
     * derived from {@code JWTOptions}, so each test controls the exact offset it needs.
     */
    private static String sign(JWTAuth auth, JsonObject claims) {
        return auth.generateToken(claims, new JWTOptions().setAlgorithm(ALGORITHM));
    }

    /** Completes the context when authentication succeeds; fails it with the cause otherwise. */
    private static void assertAuthenticationSucceeds(JWTAuth auth, String token, VertxTestContext testContext) {
        auth.authenticate(new TokenCredentials(token)).onComplete(result -> {
            if (result.succeeded()) {
                testContext.completeNow();
            } else {
                testContext.failNow(result.cause());
            }
        });
    }

    /** Completes the context when authentication fails; fails it when the token is accepted. */
    private static void assertAuthenticationFails(JWTAuth auth, String token, VertxTestContext testContext) {
        auth.authenticate(new TokenCredentials(token)).onComplete(result -> {
            if (result.failed()) {
                testContext.completeNow();
            } else {
                testContext.failNow(new AssertionError("Expected authentication to fail, but it succeeded"));
            }
        });
    }
}
