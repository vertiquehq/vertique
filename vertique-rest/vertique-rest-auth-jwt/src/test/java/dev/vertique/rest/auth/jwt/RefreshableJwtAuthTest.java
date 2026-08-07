// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static dev.vertique.rest.auth.jwt.JwtAuthTestSupport.assertAuthenticationSucceeds;
import static dev.vertique.rest.auth.jwt.JwtAuthTestSupport.closeThenAssertAuthenticationSucceeds;
import static dev.vertique.rest.auth.jwt.JwtAuthTestSupport.secondsFromNow;
import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link RefreshableJwtAuth}.
 *
 * <p>Verifies delegation behavior to the wrapped {@link JWTAuth} instance,
 * the periodic key-swap mechanism, and correct close-and-stop semantics.
 *
 * <p>Also guards that the refreshing path keeps the documented default clock-skew leeway: the
 * initial fetch goes through {@link JwtAuthFactory}'s config-carrying async seam, so a change that
 * stopped it from applying the framework defaults would silently drop the refreshing path back to a
 * leeway of {@code 0}.
 *
 * <p>Finally, pins two contracts of the config-carrying refreshing surface: an explicitly supplied
 * {@link JwtValidationConfig} reaches the initial delegate, and
 * {@link JwtAuthFactory#fromJwksRefreshing(Vertx, String, java.time.Duration)} declares
 * {@link RefreshableJwtAuth} rather than erasing it to {@link JWTAuth}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class RefreshableJwtAuthTest {

    // --- Delegation tests ---

    @Test
    @DisplayName("authenticate() should delegate to the wrapped JWTAuth")
    void shouldDelegateAuthenticate(Vertx vertx, VertxTestContext testContext) {
        RefreshableJwtAuth.create(vertx, "classpath:test-jwks.json", Duration.ofMinutes(5))
                .onComplete(testContext.succeeding(refreshable -> {
                    refreshable.close();

                    // The constructor is private, so we test the public contract:
                    // authenticate() returns a future (not null), even for invalid tokens.
                    TokenCredentials badCreds = new TokenCredentials("bad.token.here");
                    Future<?> result = refreshable.authenticate(badCreds);
                    assertNotNull(result, "authenticate() must not return null");

                    testContext.completeNow();
                }));
    }

    @Test
    @DisplayName("generateToken(JsonObject) should delegate to the wrapped JWTAuth")
    void shouldDelegateGenerateToken(Vertx vertx, VertxTestContext testContext) {
        RefreshableJwtAuth.create(vertx, "classpath:test-jwks.json", Duration.ofMinutes(5))
                .onComplete(testContext.succeeding(refreshable -> {
                    JsonObject claims = new JsonObject().put("sub", "unit-test-user");
                    String token = refreshable.generateToken(claims);

                    assertNotNull(token, "generateToken(JsonObject) must not return null");
                    assertFalse(token.isBlank(), "generateToken(JsonObject) must not return a blank token");

                    refreshable.close();
                    testContext.completeNow();
                }));
    }

    @Test
    @DisplayName("generateToken(JsonObject, JWTOptions) should delegate to the wrapped JWTAuth")
    void shouldDelegateGenerateTokenWithOptions(Vertx vertx, VertxTestContext testContext) {
        RefreshableJwtAuth.create(vertx, "classpath:test-jwks.json", Duration.ofMinutes(5))
                .onComplete(testContext.succeeding(refreshable -> {
                    JsonObject claims = new JsonObject().put("sub", "unit-test-user");
                    JWTOptions options = new JWTOptions().setAlgorithm("HS256").setExpiresInMinutes(60);
                    String token = refreshable.generateToken(claims, options);

                    assertNotNull(token, "generateToken(JsonObject, JWTOptions) must not return null");
                    assertFalse(token.isBlank(), "generateToken(JsonObject, JWTOptions) must not return a blank token");

                    refreshable.close();
                    testContext.completeNow();
                }));
    }

    // --- Key refresh / swap test ---

    @Test
    @DisplayName("After a successful refresh tick, authentication should still work")
    void shouldSwapDelegateOnRefresh(Vertx vertx, VertxTestContext testContext) {
        // Use a very short refresh interval so at least one tick fires during the test.
        Duration refreshInterval = Duration.ofMillis(100);

        RefreshableJwtAuth.create(vertx, "classpath:test-jwks.json", refreshInterval)
                .onComplete(testContext.succeeding(refreshable -> {
                    // Generate a token before any refresh
                    JsonObject claims = new JsonObject().put("sub", "refresh-test-user");
                    String token = refreshable.generateToken(claims);

                    // Wait long enough for at least two refresh ticks (300 ms >> 100 ms interval)
                    vertx.setTimer(300, ignored -> {
                        // After refresh(es), tokens signed with the same key should still be accepted
                        // because the classpath JWKS has not changed between ticks: the swapped
                        // delegate still knows the same key.
                        closeThenAssertAuthenticationSucceeds(refreshable, token, testContext);
                    });
                }));
    }

    // --- Clock-skew leeway guard ---

    @Test
    @DisplayName("The refreshing path applies the documented 30 s default leeway to an expired token")
    void shouldApplyDefaultLeewayOnRefreshingPath(Vertx vertx, VertxTestContext testContext) {
        RefreshableJwtAuth.create(vertx, "classpath:test-jwks.json", Duration.ofMinutes(5))
                .onComplete(testContext.succeeding(refreshable -> {
                    // Close up front so no exit path can leak the refresh timer. close() only cancels
                    // that timer — the delegate built by the initial fetch stays usable, as
                    // shouldNotSwapAfterClose proves — so the assertion below still exercises exactly
                    // the JWTAuth that the initial fetch produced, via
                    // fromJwksAsync(vertx, location, defaultValidation(), false).
                    refreshable.close();

                    // exp 10 s in the past — inside the documented 30 s default skew, so the token must
                    // be accepted. Signing with the same instance carries the JWKS "kid" in the header.
                    String token = refreshable.generateToken(
                            new JsonObject().put("sub", "refresh-leeway-user").put("exp", secondsFromNow(-10)),
                            new JWTOptions().setAlgorithm("HS256"));

                    // Assert on the outcome only: Vert.x reports every time-claim rejection with the
                    // identical message "Invalid JWT token: token expired.".
                    assertAuthenticationSucceeds(refreshable, token, testContext);
                }));
    }

    @Test
    @DisplayName("The refreshing factory overload applies an explicitly configured leeway")
    void shouldApplyExplicitLeewayThroughRefreshingFactory(Vertx vertx, VertxTestContext testContext) {
        JwtValidationConfig config =
                JwtValidationConfig.builder().clockSkewSeconds(300).build();

        JwtAuthFactory.fromJwksRefreshing(vertx, "classpath:test-jwks.json", Duration.ofMinutes(5), config)
                .onComplete(testContext.succeeding(refreshable -> {
                    // Close up front so no exit path can leak the refresh timer; the delegate built by
                    // the initial fetch stays usable (see shouldNotSwapAfterClose).
                    refreshable.close();

                    // exp 120 s in the past — inside the configured 300 s skew, far outside the 30 s
                    // default, so only the supplied config can make this succeed.
                    String token = refreshable.generateToken(
                            new JsonObject()
                                    .put("sub", "refreshing-factory-leeway-user")
                                    .put("exp", secondsFromNow(-120)),
                            new JWTOptions().setAlgorithm("HS256"));

                    assertAuthenticationSucceeds(refreshable, token, testContext);
                }));
    }

    // --- Return-type contract ---

    @Test
    @DisplayName("fromJwksRefreshing returns RefreshableJwtAuth, so close() is reachable without a cast")
    void shouldReturnRefreshableTypeFromFactory(Vertx vertx, VertxTestContext testContext) {
        // The declared type is the assertion: this assignment does not compile if the factory still
        // erases its result to Future<JWTAuth>, which would hide the close() the caller must call.
        Future<RefreshableJwtAuth> refreshing =
                JwtAuthFactory.fromJwksRefreshing(vertx, "classpath:test-jwks.json", Duration.ofMinutes(5));

        refreshing.onComplete(testContext.succeeding(refreshable -> {
            assertNotNull(refreshable, "fromJwksRefreshing must not complete with null");
            refreshable.close();
            testContext.completeNow();
        }));
    }

    // --- Close behavior test ---

    @Test
    @DisplayName("After close(), authentication still works with the original delegate")
    void shouldNotSwapAfterClose(Vertx vertx, VertxTestContext testContext) {
        RefreshableJwtAuth.create(vertx, "classpath:test-jwks.json", Duration.ofMinutes(5))
                .onComplete(testContext.succeeding(refreshable -> {
                    // Close immediately so no refresh timer fires
                    refreshable.close();

                    // The original delegate is still intact; tokens must be verifiable
                    JsonObject claims = new JsonObject().put("sub", "closed-test-user");
                    String token = refreshable.generateToken(claims);

                    assertAuthenticationSucceeds(refreshable, token, testContext);
                }));
    }
}
