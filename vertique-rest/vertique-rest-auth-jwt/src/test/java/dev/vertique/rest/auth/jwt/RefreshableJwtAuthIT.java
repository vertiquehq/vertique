// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
 *   <li>Retention of the documented default clock-skew leeway across a refresh tick — the delegate
 *       rebuilt by {@code onRefreshTick} must not silently fall back to a leeway of {@code 0}</li>
 *   <li>Retention of an <em>explicitly configured</em> clock-skew leeway across a refresh tick</li>
 * </ul>
 *
 * <p>Both leeway tests rotate the served key set before asserting, so the assertion can only be
 * satisfied by a delegate a refresh tick installed. Gating on a JWKS request count instead would
 * leave them satisfiable by the pre-swap delegate, and therefore unable to fail on a lost leeway.
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

    /** How long a JWKS request-count gate waits before failing the test. */
    private static final long GATE_TIMEOUT_MILLIS = 10_000;

    /** How often a JWKS request-count gate re-reads the WireMock request journal. */
    private static final long GATE_POLL_INTERVAL_MILLIS = 50;

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

                JWTAuth signerB = signerForSecret(vertx, SECRET_B);
                String tokenB = signerB.generateToken(new JsonObject().put("sub", "it-user-rotation-new"));

                // Gate on the rotation landing rather than sleeping past it: accepting a B-signed
                // token IS the property under test, so the gate succeeding is the assertion.
                awaitAuthenticationSuccess(
                        vertx,
                        refreshable,
                        tokenB,
                        () -> {
                            refreshable.close();
                            testContext.completeNow();
                        },
                        cause -> {
                            refreshable.close();
                            testContext.failNow(cause);
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
            // Make the JWKS endpoint return 500 to trigger a refresh failure. resetAll() also rewinds
            // the request journal, so the gate below counts only post-rotation requests.
            wireMock.resetAll();
            wireMock.stubFor(get(urlEqualTo(JWKS_PATH))
                    .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

            // Gate on a journaled request rather than sleeping: WireMock journals the request even
            // though the stub answers 500, so reaching 1 makes "a refresh attempt has failed" a fact.
            awaitJwksRequests(
                    vertx,
                    1,
                    () -> {
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
                    },
                    cause -> {
                        refreshable.close();
                        testContext.failNow(cause);
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
            // Gate on the second JWKS request rather than sleeping: @BeforeEach empties the journal,
            // so request 1 is the initial fetch and request 2 can only come from a periodic tick.
            // That is the premise this test needs — proving close() stops the ticks is worthless
            // unless a tick actually fired first.
            awaitJwksRequests(
                    vertx,
                    2,
                    () -> {
                        int countBeforeClose = wireMock.countRequestsMatching(
                                        getRequestedFor(urlEqualTo(JWKS_PATH)).build())
                                .getCount();
                        assertTrue(
                                countBeforeClose >= 2,
                                "Expected the initial JWKS fetch plus at least one periodic refresh before close");

                        refreshable.close();

                        // Wait to confirm no additional requests arrive after close. No gate can
                        // shortcut this one: it proves an ABSENCE of further requests, so the only
                        // evidence is elapsed time with the count unchanged.
                        vertx.setTimer(500, ignored2 -> {
                            int countAfterClose = wireMock.countRequestsMatching(getRequestedFor(urlEqualTo(JWKS_PATH))
                                            .build())
                                    .getCount();

                            // Allow at most one in-flight request that was already in progress at
                            // close() time; beyond that the timer must have stopped.
                            assertTrue(
                                    countAfterClose <= countBeforeClose + 1,
                                    "JWKS requests continued after close(): before=" + countBeforeClose + " after="
                                            + countAfterClose);
                            testContext.completeNow();
                        });
                    },
                    cause -> {
                        refreshable.close();
                        testContext.failNow(cause);
                    });
        }));
    }

    @Test
    @DisplayName("After a refresh tick, the default clock-skew leeway is still applied")
    void shouldRetainDefaultLeewayAfterRefreshTick(Vertx vertx, VertxTestContext testContext) {
        stubJwks(jwksJson(SECRET_A));
        String jwksUrl = wireMock.baseUrl() + JWKS_PATH;

        Duration refreshInterval = Duration.ofMillis(200);

        RefreshableJwtAuth.create(vertx, jwksUrl, refreshInterval).onComplete(testContext.succeeding(refreshable -> {
            // Rotate the served key set. A token signed with B can only be accepted by a delegate
            // that a refresh tick installed, so gating on that outcome proves the leeway assertion
            // below runs against the POST-SWAP delegate. Gating on a JWKS request count could not:
            // the served key would never change, so the pre-swap delegate would satisfy the
            // assertion just as well and the test could not fail on a lost leeway.
            wireMock.resetAll();
            stubJwks(jwksJson(SECRET_B));

            JWTAuth signerB = signerForSecret(vertx, SECRET_B);
            // No "exp" claim, so this probe is unaffected by leeway — it isolates the swap.
            String probeToken = signerB.generateToken(new JsonObject().put("sub", "it-user-post-tick-default-probe"));

            awaitAuthenticationSuccess(
                    vertx,
                    refreshable,
                    probeToken,
                    () -> {
                        // exp 10 s in the past — inside the documented 30 s default skew, so the
                        // post-swap delegate must still accept it. Assert on the outcome only: every
                        // time-claim rejection carries the identical "token expired" message.
                        String expiredToken = signerB.generateToken(new JsonObject()
                                .put("sub", "it-user-post-tick-leeway")
                                .put("exp", Instant.now().getEpochSecond() - 10));

                        refreshable
                                .authenticate(new TokenCredentials(expiredToken))
                                .onComplete(authResult -> {
                                    refreshable.close();
                                    if (authResult.failed()) {
                                        testContext.failNow(authResult.cause());
                                    } else {
                                        assertNotNull(authResult.result());
                                        testContext.completeNow();
                                    }
                                });
                    },
                    cause -> {
                        refreshable.close();
                        testContext.failNow(cause);
                    });
        }));
    }

    @Test
    @DisplayName("After a refresh tick, an explicitly configured clock-skew leeway is still applied")
    void shouldRetainExplicitLeewayAfterRefreshTick(Vertx vertx, VertxTestContext testContext) {
        stubJwks(jwksJson(SECRET_A));
        String jwksUrl = wireMock.baseUrl() + JWKS_PATH;

        // 300 s is an order of magnitude above the 30 s default, so a delegate that silently fell back
        // to the defaults cannot satisfy the assertion below.
        JwtValidationConfig config =
                JwtValidationConfig.builder().clockSkewSeconds(300).build();

        RefreshableJwtAuth.create(vertx, jwksUrl, Duration.ofMillis(200), config)
                .onComplete(testContext.succeeding(refreshable -> {
                    // Rotate the served key set. A token signed with B can only be accepted by a
                    // delegate that a refresh tick installed, so gating on that outcome proves the
                    // leeway assertion below runs against the POST-SWAP delegate rather than the one
                    // the initial fetch built. Gating on a request count could not prove that.
                    wireMock.resetAll();
                    stubJwks(jwksJson(SECRET_B));

                    JWTAuth signerB = signerForSecret(vertx, SECRET_B);
                    // No "exp" claim, so this probe is unaffected by leeway — it isolates the swap.
                    String probeToken = signerB.generateToken(new JsonObject().put("sub", "it-user-post-tick-probe"));

                    awaitAuthenticationSuccess(
                            vertx,
                            refreshable,
                            probeToken,
                            () -> {
                                // exp 120 s in the past: inside the configured 300 s skew, far outside
                                // the 30 s default. Assert on the outcome only — every time-claim
                                // rejection carries the identical "token expired" message.
                                String expiredToken = signerB.generateToken(new JsonObject()
                                        .put("sub", "it-user-post-tick-explicit-leeway")
                                        .put("exp", Instant.now().getEpochSecond() - 120));

                                refreshable
                                        .authenticate(new TokenCredentials(expiredToken))
                                        .onComplete(authResult -> {
                                            refreshable.close();
                                            if (authResult.failed()) {
                                                testContext.failNow(authResult.cause());
                                            } else {
                                                assertNotNull(authResult.result());
                                                testContext.completeNow();
                                            }
                                        });
                            },
                            cause -> {
                                refreshable.close();
                                testContext.failNow(cause);
                            });
                }));
    }

    // --- Helpers ---

    /**
     * Polls until {@code token} authenticates successfully against {@code auth}, then runs
     * {@code onReady}. Used to gate on a delegate swap having completed: a token signed with the
     * newly served key can only succeed once {@code onRefreshTick} has installed the refreshed
     * delegate, which a WireMock request count cannot establish.
     *
     * @param vertx     the Vert.x instance used to schedule the poll
     * @param auth      the instance under test
     * @param token     a token signed with the rotated-in key, carrying no {@code exp} claim so the
     *                  probe is independent of the leeway under test
     * @param onReady   run once authentication succeeds
     * @param onTimeout invoked with an {@link AssertionError} if the swap never lands
     */
    private static void awaitAuthenticationSuccess(
            Vertx vertx, RefreshableJwtAuth auth, String token, Runnable onReady, Handler<Throwable> onTimeout) {
        awaitAuthenticationSuccess(
                vertx, auth, token, System.currentTimeMillis() + GATE_TIMEOUT_MILLIS, onReady, onTimeout);
    }

    /**
     * Recursive body of the swap gate above; re-schedules itself on the Vert.x timer rather than
     * blocking a thread. Gives up after the deadline, which stays well inside the class-level 20 s
     * timeout so a stalled refresh reports as an assertion failure rather than a hang.
     *
     * @param vertx     the Vert.x instance used to schedule the poll
     * @param auth      the instance under test
     * @param token     a token signed with the rotated-in key
     * @param deadline  the absolute {@link System#currentTimeMillis()} value at which to give up
     * @param onReady   run once authentication succeeds
     * @param onTimeout invoked with an {@link AssertionError} once the deadline passes
     */
    private static void awaitAuthenticationSuccess(
            Vertx vertx,
            RefreshableJwtAuth auth,
            String token,
            long deadline,
            Runnable onReady,
            Handler<Throwable> onTimeout) {
        auth.authenticate(new TokenCredentials(token)).onComplete(result -> {
            if (result.succeeded()) {
                onReady.run();
            } else if (System.currentTimeMillis() >= deadline) {
                onTimeout.handle(new AssertionError(
                        "Timed out waiting for a refresh tick to install the rotated key set", result.cause()));
            } else {
                vertx.setTimer(
                        GATE_POLL_INTERVAL_MILLIS,
                        ignored -> awaitAuthenticationSuccess(vertx, auth, token, deadline, onReady, onTimeout));
            }
        });
    }

    /**
     * Polls the WireMock request journal until at least {@code minRequests} JWKS fetches have been
     * served, then runs {@code onReady}. Gives up after {@link #GATE_TIMEOUT_MILLIS}, which stays
     * well inside the class-level 20 s timeout so a stalled refresh reports as an assertion failure
     * rather than a hang.
     *
     * @param vertx       the Vert.x instance used to schedule the poll
     * @param minRequests the JWKS request count to wait for
     * @param onReady     run once the count is reached
     * @param onTimeout   invoked with an {@link AssertionError} if the count is never reached
     */
    private static void awaitJwksRequests(
            Vertx vertx, int minRequests, Runnable onReady, Handler<Throwable> onTimeout) {
        awaitJwksRequests(vertx, minRequests, System.currentTimeMillis() + GATE_TIMEOUT_MILLIS, onReady, onTimeout);
    }

    /**
     * Recursive body of the gate above; re-schedules itself on the Vert.x timer rather than blocking
     * a thread, so the poll never occupies the event loop between checks.
     *
     * @param vertx       the Vert.x instance used to schedule the poll
     * @param minRequests the JWKS request count to wait for
     * @param deadline    the absolute {@link System#currentTimeMillis()} value at which to give up
     * @param onReady     run once the count is reached
     * @param onTimeout   invoked with an {@link AssertionError} once the deadline passes
     */
    private static void awaitJwksRequests(
            Vertx vertx, int minRequests, long deadline, Runnable onReady, Handler<Throwable> onTimeout) {
        int count = wireMock.countRequestsMatching(
                        getRequestedFor(urlEqualTo(JWKS_PATH)).build())
                .getCount();
        if (count >= minRequests) {
            onReady.run();
        } else if (System.currentTimeMillis() >= deadline) {
            onTimeout.handle(
                    new AssertionError("Timed out waiting for " + minRequests + " JWKS requests; observed " + count));
        } else {
            vertx.setTimer(
                    GATE_POLL_INTERVAL_MILLIS,
                    ignored -> awaitJwksRequests(vertx, minRequests, deadline, onReady, onTimeout));
        }
    }

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
