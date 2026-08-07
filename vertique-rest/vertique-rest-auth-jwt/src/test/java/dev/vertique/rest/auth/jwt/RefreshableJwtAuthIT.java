// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for {@link RefreshableJwtAuth} that exercise the full key-rotation lifecycle
 * against a WireMock JWKS endpoint.
 *
 * <p>Covers:
 * <ul>
 *   <li>Successful authentication with the initial key set</li>
 *   <li>Transparent key rotation — tokens signed with the new key are accepted after a refresh</li>
 *   <li>Resilience to refresh failures — the existing key set is preserved on HTTP error</li>
 *   <li>Timer cancellation on {@link RefreshableJwtAuth#close()} — no further JWKS fetches occur</li>
 * </ul>
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
@ExtendWith(VertxExtension.class)
public class RefreshableJwtAuthIT {

    // --- Shared WireMock server ---

    private static WireMockServer wireMock;

    /** Two distinct HS256 secrets used to generate separate JWKS key sets for rotation tests. */
    private static final String SECRET_A = "secret-key-A-for-testing-must-be-at-least-256-bits!";

    private static final String SECRET_B = "secret-key-B-for-testing-must-be-at-least-256-bits!";

    private static final String JWKS_PATH = "/.well-known/jwks.json";

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort().bindAddress("127.0.0.1"));
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    // --- Tests ---

    @Test
    @DisplayName("Should authenticate tokens signed with the initial JWKS key set")
    void shouldAuthenticateWithInitialKeys(Vertx vertx, VertxTestContext testContext) {
        stubJwks(jwksJson(SECRET_A));
        String jwksUrl = "http://127.0.0.1:" + wireMock.port() + JWKS_PATH;

        RefreshableJwtAuth.create(vertx, jwksUrl, Duration.ofMinutes(5))
                .onComplete(testContext.succeeding(refreshable -> {
                    JWTAuth signerA = signerForSecret(vertx, SECRET_A);
                    String token = signerA.generateToken(new JsonObject().put("sub", "it-user-initial"));

                    refreshable.authenticate(new TokenCredentials(token)).onComplete(authResult -> {
                        refreshable.close();
                        if (authResult.failed()) {
                            testContext.failNow(authResult.cause());
                        } else {
                            assertNotNull(authResult.result());
                            testContext.completeNow();
                        }
                    });
                }));
    }

    @Test
    @DisplayName("After key rotation, tokens signed with the new key should be accepted")
    void shouldRotateKeysTransparently(Vertx vertx, VertxTestContext testContext) {
        stubJwks(jwksJson(SECRET_A));
        String jwksUrl = "http://127.0.0.1:" + wireMock.port() + JWKS_PATH;

        // Use a short refresh interval so the rotation is picked up quickly.
        Duration refreshInterval = Duration.ofMillis(200);

        RefreshableJwtAuth.create(vertx, jwksUrl, refreshInterval).onComplete(testContext.succeeding(refreshable -> {
            // Verify initial key set works
            JWTAuth signerA = signerForSecret(vertx, SECRET_A);
            String tokenA = signerA.generateToken(new JsonObject().put("sub", "it-user-rotation"));

            refreshable.authenticate(new TokenCredentials(tokenA)).onComplete(testContext.succeeding(userA -> {
                // Rotate: change WireMock to serve key set B
                wireMock.resetAll();
                stubJwks(jwksJson(SECRET_B));

                // Wait long enough for at least one refresh (500 ms >> 200 ms interval)
                vertx.setTimer(500, ignored -> {
                    JWTAuth signerB = signerForSecret(vertx, SECRET_B);
                    String tokenB = signerB.generateToken(new JsonObject().put("sub", "it-user-rotation-new"));

                    refreshable.authenticate(new TokenCredentials(tokenB)).onComplete(authResult -> {
                        refreshable.close();
                        if (authResult.failed()) {
                            testContext.failNow(authResult.cause());
                        } else {
                            assertNotNull(authResult.result());
                            testContext.completeNow();
                        }
                    });
                });
            }));
        }));
    }

    @Test
    @DisplayName("On refresh failure, existing keys should be preserved")
    void shouldPreserveExistingKeysOnRefreshFailure(Vertx vertx, VertxTestContext testContext) {
        stubJwks(jwksJson(SECRET_A));
        String jwksUrl = "http://127.0.0.1:" + wireMock.port() + JWKS_PATH;

        Duration refreshInterval = Duration.ofMillis(200);

        RefreshableJwtAuth.create(vertx, jwksUrl, refreshInterval).onComplete(testContext.succeeding(refreshable -> {
            // Make the JWKS endpoint return 500 to trigger a refresh failure
            wireMock.resetAll();
            wireMock.stubFor(get(urlEqualTo(JWKS_PATH))
                    .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

            // Wait for at least one failed refresh attempt
            vertx.setTimer(500, ignored -> {
                // Old key set (A) must still be accepted
                JWTAuth signerA = signerForSecret(vertx, SECRET_A);
                String token = signerA.generateToken(new JsonObject().put("sub", "it-user-failure-resilience"));

                refreshable.authenticate(new TokenCredentials(token)).onComplete(authResult -> {
                    refreshable.close();
                    if (authResult.failed()) {
                        testContext.failNow(authResult.cause());
                    } else {
                        assertNotNull(authResult.result());
                        testContext.completeNow();
                    }
                });
            });
        }));
    }

    @Test
    @DisplayName("After close(), no further JWKS refresh requests should be made")
    void shouldStopRefreshingAfterClose(Vertx vertx, VertxTestContext testContext) {
        stubJwks(jwksJson(SECRET_A));
        String jwksUrl = "http://127.0.0.1:" + wireMock.port() + JWKS_PATH;

        Duration refreshInterval = Duration.ofMillis(200);

        RefreshableJwtAuth.create(vertx, jwksUrl, refreshInterval).onComplete(testContext.succeeding(refreshable -> {
            // Wait long enough for at least one periodic refresh (300 ms > 200 ms interval)
            vertx.setTimer(300, ignored -> {
                int countBeforeClose = wireMock.countRequestsMatching(
                                getRequestedFor(urlEqualTo(JWKS_PATH)).build())
                        .getCount();
                assertTrue(countBeforeClose >= 1, "Expected at least one JWKS request before close");

                refreshable.close();

                // Wait to confirm no additional requests arrive after close
                vertx.setTimer(500, ignored2 -> {
                    int countAfterClose = wireMock.countRequestsMatching(
                                    getRequestedFor(urlEqualTo(JWKS_PATH)).build())
                            .getCount();

                    // Allow at most one in-flight request that was already in progress at
                    // close() time; beyond that the timer must have stopped.
                    assertTrue(
                            countAfterClose <= countBeforeClose + 1,
                            "JWKS requests continued after close(): before=" + countBeforeClose + " after="
                                    + countAfterClose);
                    testContext.completeNow();
                });
            });
        }));
    }

    // --- Helpers ---

    /**
     * Builds an HS256 JWKS JSON document for the given secret.
     *
     * @param secret the symmetric key bytes encoded as a UTF-8 string
     * @return the JWKS JSON string
     */
    private static String jwksJson(String secret) {
        String encodedKey =
                Base64.getUrlEncoder().withoutPadding().encodeToString(secret.getBytes(StandardCharsets.UTF_8));
        return new JsonObject()
                .put(
                        "keys",
                        new JsonArray()
                                .add(new JsonObject()
                                        .put("kty", "oct")
                                        .put("k", encodedKey)
                                        .put("alg", "HS256")))
                .encode();
    }

    /**
     * Creates a {@link JWTAuth} instance that can sign tokens with the given symmetric secret.
     *
     * @param vertx  the Vert.x instance
     * @param secret the HS256 symmetric secret
     * @return a configured {@link JWTAuth} that signs with {@code secret}
     */
    private static JWTAuth signerForSecret(Vertx vertx, String secret) {
        return JwtAuthFactory.fromSymmetricKey(vertx, "HS256", secret);
    }

    /**
     * Registers a WireMock stub that returns the given JWKS document for {@code GET JWKS_PATH}.
     *
     * @param jwksBody the JWKS JSON string to serve
     */
    private void stubJwks(String jwksBody) {
        wireMock.stubFor(get(urlEqualTo(JWKS_PATH))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(jwksBody)));
    }
}
