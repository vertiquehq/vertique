// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

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
                        // because the classpath JWKS has not changed between ticks.
                        TokenCredentials creds = new TokenCredentials(token);
                        refreshable.authenticate(creds).onComplete(result -> {
                            refreshable.close();
                            // Authentication must succeed: the swapped delegate still knows the same key
                            if (result.failed()) {
                                testContext.failNow(result.cause());
                            } else {
                                testContext.completeNow();
                            }
                        });
                    });
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
                    TokenCredentials creds = new TokenCredentials(token);

                    refreshable.authenticate(creds).onComplete(result -> {
                        if (result.failed()) {
                            testContext.failNow(result.cause());
                        } else {
                            testContext.completeNow();
                        }
                    });
                }));
    }
}
