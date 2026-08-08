// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static dev.vertique.rest.auth.jwt.JwtAuthTestSupport.awaitReady;
import static dev.vertique.rest.auth.jwt.JwtAuthTestSupport.closeThenAssertAuthenticationSucceeds;
import static dev.vertique.rest.auth.jwt.JwtAuthTestSupport.secondsFromNow;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.vertx.core.Future;
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
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

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

    /**
     * One server for the whole class, per the project's WireMock lifecycle rule. The extension
     * clears both the stub registry and the request journal before every test method, so each test
     * still starts from the empty-journal baseline the request-count gates below assume — the
     * guarantee the previous {@code @BeforeEach resetAll()} provided.
     *
     * <p>The bind address is pinned to {@code 127.0.0.1} so the server cannot land on a loopback
     * port another process already holds.
     */
    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().bindAddress("127.0.0.1"))
            .build();

    /** Two distinct HS256 secrets used to generate separate JWKS key sets for rotation tests. */
    private static final String SECRET_A = "secret-key-A-for-testing-must-be-at-least-256-bits!";

    private static final String SECRET_B = "secret-key-B-for-testing-must-be-at-least-256-bits!";

    private static final String JWKS_PATH = "/.well-known/jwks.json";

    // --- Tests ---

    @Test
    @DisplayName("Should authenticate tokens signed with the initial JWKS key set")
    void shouldAuthenticateWithInitialKeys(Vertx vertx, VertxTestContext testContext) {
        stubJwks(jwksJson(SECRET_A));
        String jwksUrl = jwksUrl();

        RefreshableJwtAuth.create(vertx, jwksUrl, Duration.ofMinutes(5))
                .onComplete(testContext.succeeding(refreshable -> {
                    JWTAuth signerA = signerForSecret(vertx, SECRET_A);
                    String token = signerA.generateToken(new JsonObject().put("sub", "it-user-initial"));

                    closeThenAssertAuthenticationSucceeds(refreshable, token, testContext);
                }));
    }

    @Test
    @DisplayName("After key rotation, tokens signed with the new key should be accepted")
    void shouldRotateKeysTransparently(Vertx vertx, VertxTestContext testContext) {
        stubJwks(jwksJson(SECRET_A));
        String jwksUrl = jwksUrl();

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
        String jwksUrl = jwksUrl();

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

                        closeThenAssertAuthenticationSucceeds(refreshable, token, testContext);
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
        String jwksUrl = jwksUrl();

        Duration refreshInterval = Duration.ofMillis(200);

        RefreshableJwtAuth.create(vertx, jwksUrl, refreshInterval).onComplete(testContext.succeeding(refreshable -> {
            // Gate on the second JWKS request rather than sleeping: the extension empties the journal
            // before each test, so request 1 is the initial fetch and request 2 can only come from a
            // periodic tick. That is the premise this test needs — proving close() stops the ticks is
            // worthless unless a tick actually fired first.
            awaitJwksRequests(
                    vertx,
                    2,
                    () -> {
                        int countBeforeClose = jwksRequestCount();
                        assertTrue(
                                countBeforeClose >= 2,
                                "Expected the initial JWKS fetch plus at least one periodic refresh before close");

                        refreshable.close();

                        // Wait to confirm no additional requests arrive after close. No gate can
                        // shortcut this one: it proves an ABSENCE of further requests, so the only
                        // evidence is elapsed time with the count unchanged.
                        vertx.setTimer(500, ignored2 -> {
                            int countAfterClose = jwksRequestCount();

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
        String jwksUrl = jwksUrl();

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
                                .put("exp", secondsFromNow(-10)));

                        closeThenAssertAuthenticationSucceeds(refreshable, expiredToken, testContext);
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
        String jwksUrl = jwksUrl();

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
                                        .put("exp", secondsFromNow(-120)));

                                closeThenAssertAuthenticationSucceeds(refreshable, expiredToken, testContext);
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
        // Carries the most recent rejection into the timeout message, which is the only diagnostic
        // available when a swap never lands.
        AtomicReference<Throwable> lastRejection = new AtomicReference<>();
        awaitReady(
                vertx,
                () -> auth.authenticate(new TokenCredentials(token))
                        .map(user -> true)
                        .otherwise(cause -> {
                            lastRejection.set(cause);
                            return false;
                        }),
                () -> new AssertionError(
                        "Timed out waiting for a refresh tick to install the rotated key set", lastRejection.get()),
                onReady,
                onTimeout);
    }

    /**
     * Polls the WireMock request journal until at least {@code minRequests} JWKS fetches have been
     * served, then runs {@code onReady}.
     *
     * @param vertx       the Vert.x instance used to schedule the poll
     * @param minRequests the JWKS request count to wait for
     * @param onReady     run once the count is reached
     * @param onTimeout   invoked with an {@link AssertionError} if the count is never reached
     */
    private static void awaitJwksRequests(
            Vertx vertx, int minRequests, Runnable onReady, Handler<Throwable> onTimeout) {
        // Records the count each poll observed so the timeout message can name it; re-reading the
        // journal when the message is built could report a count that contradicts the failure.
        AtomicInteger observed = new AtomicInteger();
        awaitReady(
                vertx,
                () -> {
                    int count = jwksRequestCount();
                    observed.set(count);
                    return Future.succeededFuture(count >= minRequests);
                },
                () -> new AssertionError(
                        "Timed out waiting for " + minRequests + " JWKS requests; observed " + observed.get()),
                onReady,
                onTimeout);
    }

    /**
     * Reads the number of JWKS fetches WireMock has journaled for the current test.
     *
     * @return the journaled {@code GET JWKS_PATH} count
     */
    private static int jwksRequestCount() {
        return wireMock.countRequestsMatching(
                        getRequestedFor(urlEqualTo(JWKS_PATH)).build())
                .getCount();
    }

    /**
     * Builds the JWKS URL for the running WireMock server, pinned to the literal loopback address
     * the server binds to. Resolving {@code localhost} instead can select a different loopback
     * interface than the one bound, which surfaces as a connection failure rather than a JWKS error.
     *
     * @return the absolute JWKS URL
     */
    private static String jwksUrl() {
        return "http://127.0.0.1:" + wireMock.getPort() + JWKS_PATH;
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
