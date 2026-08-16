// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.DeferredCredentialRejectionAuthHandler;
import dev.vertique.rest.security.DefaultCredentialRejectionReporter;
import dev.vertique.rest.security.RestAuthenticationEvidence;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.events.CredentialAcceptedEvent;
import dev.vertique.security.events.CredentialRejectedEvent;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.handler.AuthenticationHandler;
import io.vertx.ext.web.handler.ChainAuthHandler;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

/**
 * End-to-end proof for finding C2: two real {@link JwtBearerSecuritySchemeHandler}s — each backed by a
 * distinct symmetric key and issuer — compose into a Vert.x {@link ChainAuthHandler#any()} OR chain
 * and authenticate at request time, exactly as the registrar's multi-scheme {@code applySecurity}
 * path wires them.
 *
 * <p>The chain is built here precisely as {@code JaxRsRouteRegistrar.applySecurity} builds it for an
 * operation declaring two alternative bearer {@code @SecurityRequirement}s: {@code ChainAuthHandler.any()}
 * with each scheme's registered {@link AuthenticationHandler} added via {@code chain.add(...)}. Vert.x's
 * {@code ChainAuthHandlerImpl.add(...)} unconditionally casts each member to the internal
 * {@code AuthenticationHandlerInternal}; the framework's {@code DelegatingJwtAuthHandler} now implements
 * that interface, so the build no longer throws a {@code ClassCastException} and the chain invokes each
 * member's {@code authenticate(ctx)} at request time, advancing to the next alternative when one fails
 * with a {@code 401} {@code HttpException}.
 *
 * <h3>Scenarios</h3>
 * <ul>
 *   <li>a token valid for issuer A (signed with key A) → authorized, reaches the terminal handler (200);</li>
 *   <li>a token valid for issuer B (signed with key B) → authorized (200);</li>
 *   <li>an invalid/garbage token → neither alternative authenticates → 401;</li>
 *   <li>a missing {@code Authorization} header → 401.</li>
 * </ul>
 *
 * <p>Each handler enforces its own issuer via {@link JwtValidationConfig}; the distinct signing keys
 * already make only the matching {@link JWTAuth} able to verify a given token, so the OR chain
 * discriminates by both signature and issuer.
 *
 * <p>Requests are issued through a {@link WebClient} rather than a raw {@code HttpClient}
 * deliberately: a raw {@code HttpClientResponse} discards body buffers that arrive before a body
 * handler is attached, so under load {@code body()} can succeed with zero bytes while the status
 * code is correct. This class read the identity JSON that way and failed exactly so in CI — the
 * status assertion passed while every body-derived field read {@code null} (issue #167). A
 * {@link WebClient} aggregates the body into its {@code HttpResponse} before completing the send,
 * so the race is closed by construction rather than by every author remembering an idiom.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class DelegatingJwtAuthHandlerOrChainIT {

    private static final String ISSUER_A = "https://issuer-a.example.com";
    private static final String ISSUER_B = "https://issuer-b.example.com";
    private static final String KEY_A = "key-a-symmetric-secret-minimum-256-bits-long-padding-aaaaaaaa";
    private static final String KEY_B = "key-b-symmetric-secret-minimum-256-bits-long-padding-bbbbbbbb";

    private static int port;
    private static HttpServer server;
    private static WebClient client;
    private static JWTAuth jwtAuthA;
    private static JWTAuth jwtAuthB;

    /**
     * Captures every {@link CredentialRejectedEvent} emitted through the {@link SecurityEventEmitter}
     * shared by all scheme handlers in this test, so each scenario can assert on the exact number of
     * rejections recorded for a request. Cleared before every test in {@link #resetRejections()}.
     */
    private static final CapturingRejectionObserver REJECTIONS = new CapturingRejectionObserver();

    /**
     * Shared reporter used by post-authentication handlers (e.g. the claims-rejection handler on
     * {@code /or-post-auth-claims}) that emit a {@link CredentialRejectedEvent} after the OR chain has
     * already authenticated a request. Initialized in {@link #setUp(Vertx, VertxTestContext)}.
     */
    private static DefaultCredentialRejectionReporter sharedPostAuthReporter;

    /**
     * Builds the two real JWT scheme handlers, composes their registered handlers into a
     * {@code ChainAuthHandler.any()} OR chain (mirroring {@code applySecurity}), mounts the chain plus a
     * terminal 200 handler on {@code /or-secured}, and starts a shared HTTP server. One
     * {@link WebClient} is bound to a static field and shared across all tests: a client per
     * request accumulates netty channel pools that are never reclaimed, and an unbound client can
     * never be closed at all.
     *
     * @param vertx the Vert.x instance injected by {@link VertxExtension}
     * @param ctx   the test context used for async startup assertion
     */
    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));

        jwtAuthA = JwtAuthFactory.fromSymmetricKey(vertx, "HS256", KEY_A);
        jwtAuthB = JwtAuthFactory.fromSymmetricKey(vertx, "HS256", KEY_B);

        AuthenticationHandler handlerA = registeredHandler(
                vertx,
                "schemeA",
                jwtAuthA,
                JwtValidationConfig.builder().issuer(ISSUER_A).build());
        AuthenticationHandler handlerB = registeredHandler(
                vertx,
                "schemeB",
                jwtAuthB,
                JwtValidationConfig.builder().issuer(ISSUER_B).build());

        // Mirror exactly what JaxRsRouteRegistrar.applySecurity does for the multi-scheme OR path:
        // build the OR chain, then wrap it in DeferredCredentialRejectionAuthHandler so a failed
        // earlier alternative's CredentialRejected is buffered and only emitted if the whole chain
        // ultimately fails (no later alternative authenticated).
        ChainAuthHandler orChain = ChainAuthHandler.any();
        orChain.add(handlerA);
        orChain.add(handlerB);

        Router router = Router.router(vertx);
        router.route("/or-secured").handler(new DeferredCredentialRejectionAuthHandler(orChain));
        router.route("/or-secured")
                .handler(rc -> rc.response().setStatusCode(200).end("ok"));

        // Single-scheme route: NO wrapper, exactly as applySecurity's present.size() == 1 path mounts
        // it. A failed credential here must emit its rejection IMMEDIATELY (the deferral path is never
        // armed because the DEFER_CONTEXT_KEY flag is absent).
        router.route("/single-secured").handler(handlerA);
        router.route("/single-secured")
                .handler(rc -> rc.response().setStatusCode(200).end("ok"));

        // OR route with post-authentication claims rejection. The OR chain (wrapped in the deferred
        // handler) authenticates via handlerA; after it succeeds a post-auth handler simulates a
        // JwtClaimsValidatorContributor-style claims check that fails and calls reporter.report(...)
        // then ctx.fail(401). At report() time ctx.user() != null (the chain already authenticated),
        // so the reporter MUST emit immediately — not buffer for the end-handler — even though the
        // DEFER_CONTEXT_KEY flag is set. Proves the Codex review critical finding: the user-null guard
        // is what scopes deferral to chain-attempt rejections only.
        sharedPostAuthReporter = buildReporter();
        ChainAuthHandler orChainForPostAuth = ChainAuthHandler.any();
        AuthenticationHandler handlerA2 = registeredHandler(
                vertx,
                "schemeA2",
                jwtAuthA,
                JwtValidationConfig.builder().issuer(ISSUER_A).build());
        AuthenticationHandler handlerB2 = registeredHandler(
                vertx,
                "schemeB2",
                jwtAuthB,
                JwtValidationConfig.builder().issuer(ISSUER_B).build());
        orChainForPostAuth.add(handlerA2);
        orChainForPostAuth.add(handlerB2);
        DefaultCredentialRejectionReporter postAuthReporter = sharedPostAuthReporter;
        router.route("/or-post-auth-claims").handler(new DeferredCredentialRejectionAuthHandler(orChainForPostAuth));
        router.route("/or-post-auth-claims").handler(rc -> {
            // Post-authentication: chain succeeded (ctx.user() != null). Simulate a claims validator
            // that rejects the authenticated token on a custom claim — calls report() then ctx.fail(401).
            postAuthReporter.report(
                    rc, DefaultAuthMethod.jwt(), Optional.empty(), Optional.empty(), "JWT_CLAIMS_INVALID", Map.of());
            rc.fail(401);
        });

        // Second route: run the request through the REAL identity-resolution path after the OR
        // chain. The terminal handler mirrors what IdentityResolutionMiddleware does — read
        // RestAuthenticationEvidence.get(ctx), build a SecurityIdentityResolutionContext from it,
        // and run the framework's DefaultSecurityIdentityResolver — then writes the evidence count
        // and the resolved actor into the response so the test can assert on the RESOLVED IDENTITY,
        // not just the HTTP status. The OR chain runs each member's authenticate(ctx) and then the
        // winning member's postAuthentication(ctx); the framework evidence must be appended on that
        // path for the resolved identity to be the authenticated user (not anonymous).
        ChainAuthHandler identityChain = ChainAuthHandler.any();
        identityChain.add(handlerA);
        identityChain.add(handlerB);
        router.route("/or-identity").handler(identityChain);
        router.route("/or-identity").handler(DelegatingJwtAuthHandlerOrChainIT::resolveIdentity);

        vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").onComplete(ctx.succeeding(s -> {
            server = s;
            port = s.actualPort();
            ctx.completeNow();
        }));
    }

    /**
     * Closes the shared {@link WebClient} and then the shared HTTP server, before the
     * extension-owned {@link Vertx} instance is closed.
     *
     * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns
     * once the underlying client has been asked to close, so there is no future to join here.
     *
     * @param ctx the test context used for async teardown assertion
     */
    @AfterAll
    static void tearDown(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ar -> ctx.completeNow());
    }

    /**
     * Clears the shared rejection capture before each test so per-request assertions on the number of
     * emitted {@link CredentialRejectedEvent}s are isolated.
     */
    @org.junit.jupiter.api.BeforeEach
    void resetRejections() {
        REJECTIONS.events.clear();
    }

    // --- Tests ---

    /**
     * A token signed with key A and carrying {@code iss = ISSUER_A} authenticates via the first
     * alternative and reaches the terminal handler (200).
     *
     * @param ctx the test context
     */
    @Test
    @DisplayName("Token valid for issuer A authorizes via the OR chain (200)")
    void tokenForIssuerA_authorized(VertxTestContext ctx) {
        String token =
                jwtAuthA.generateToken(new JsonObject().put("sub", "alice").put("iss", ISSUER_A));
        statusFor(token)
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertEquals(200, status, "a token valid for issuer A must authorize via the OR chain");
                    ctx.completeNow();
                })));
    }

    /**
     * A token signed with key B and carrying {@code iss = ISSUER_B} authenticates via the second
     * alternative (the first alternative fails its signature/issuer check and the chain advances) and
     * reaches the terminal handler (200).
     *
     * @param ctx the test context
     */
    @Test
    @DisplayName("Token valid for issuer B authorizes via the OR chain (200)")
    void tokenForIssuerB_authorized(VertxTestContext ctx) {
        String token = jwtAuthB.generateToken(new JsonObject().put("sub", "bob").put("iss", ISSUER_B));
        statusFor(token)
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertEquals(200, status, "a token valid for issuer B must authorize via the OR chain");
                    ctx.completeNow();
                })));
    }

    /**
     * Proves the C2 OR-chain regression fix: a request authenticated by the FIRST OR alternative
     * (issuer A) resolves to a NON-anonymous framework identity. The {@code ChainAuthHandler.any()}
     * path calls only {@code authenticate(ctx)} on each member and then the winning member's
     * {@code postAuthentication(ctx)}; it never calls {@code handle()}. Before the fix the framework
     * evidence was appended only in {@code handle()}, so on the OR path
     * {@code RestAuthenticationEvidence.get(ctx)} was empty and {@link DefaultSecurityIdentityResolver}
     * resolved {@link SecurityIdentity#anonymous()}. The terminal handler runs the same evidence read
     * and resolver the framework's {@code IdentityResolutionMiddleware} runs, and reports the result.
     *
     * <p>Asserts: evidence is non-empty (exactly one entry) and the resolved actor is the
     * authenticated user {@code alice} with a non-anonymous type — RED before the fix
     * (evidenceCount=0, actorType=ANONYMOUS), GREEN after.
     *
     * @param ctx the test context
     */
    @Test
    @DisplayName("OR-authenticated request (issuer A) resolves to a non-anonymous identity")
    void tokenForIssuerA_resolvesAuthenticatedIdentity(VertxTestContext ctx) {
        String token =
                jwtAuthA.generateToken(new JsonObject().put("sub", "alice").put("iss", ISSUER_A));
        identityFor(token)
                .onComplete(ctx.succeeding(body -> ctx.verify(() -> {
                    assertEquals(200, body.getInteger("status"), "issuer-A token must authorize via the OR chain");
                    assertEquals(
                            1,
                            body.getInteger("evidenceCount"),
                            "the OR path must append framework authentication evidence exactly once");
                    assertNotEquals(
                            "ANONYMOUS",
                            body.getString("actorType"),
                            "an OR-authenticated request must NOT resolve to an anonymous identity");
                    assertEquals("USER", body.getString("actorType"), "issuer-A token carries sub=alice → USER actor");
                    assertEquals(
                            "alice", body.getString("actorId"), "the resolved actor must be the authenticated user");
                    ctx.completeNow();
                })));
    }

    /**
     * Proves the C2 OR-chain regression fix for the SECOND OR alternative: a request authenticated
     * by issuer B (the first alternative fails its signature/issuer check and the chain advances)
     * resolves to a NON-anonymous framework identity. Mirrors
     * {@link #tokenForIssuerA_resolvesAuthenticatedIdentity(VertxTestContext)} but exercises the
     * non-first winning member, confirming {@code postAuthentication(ctx)} appends evidence
     * regardless of which alternative wins.
     *
     * <p>Asserts: evidence non-empty (exactly one entry) and the resolved actor is {@code bob} with a
     * non-anonymous type — RED before the fix, GREEN after.
     *
     * @param ctx the test context
     */
    @Test
    @DisplayName("OR-authenticated request (issuer B) resolves to a non-anonymous identity")
    void tokenForIssuerB_resolvesAuthenticatedIdentity(VertxTestContext ctx) {
        String token = jwtAuthB.generateToken(new JsonObject().put("sub", "bob").put("iss", ISSUER_B));
        identityFor(token)
                .onComplete(ctx.succeeding(body -> ctx.verify(() -> {
                    assertEquals(200, body.getInteger("status"), "issuer-B token must authorize via the OR chain");
                    assertEquals(
                            1,
                            body.getInteger("evidenceCount"),
                            "the OR path must append framework authentication evidence exactly once");
                    assertNotEquals(
                            "ANONYMOUS",
                            body.getString("actorType"),
                            "an OR-authenticated request must NOT resolve to an anonymous identity");
                    assertEquals("USER", body.getString("actorType"), "issuer-B token carries sub=bob → USER actor");
                    assertEquals("bob", body.getString("actorId"), "the resolved actor must be the authenticated user");
                    ctx.completeNow();
                })));
    }

    /**
     * A garbage token verifies against neither key, so both alternatives fail with a {@code 401} and the
     * chain rejects the request (401).
     *
     * @param ctx the test context
     */
    @Test
    @DisplayName("Invalid token is rejected by the OR chain (401)")
    void invalidToken_rejected(VertxTestContext ctx) {
        statusFor("not-a-real-jwt-token")
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertEquals(401, status, "an invalid token must be rejected by the OR chain");
                    ctx.completeNow();
                })));
    }

    /**
     * A request with no {@code Authorization} header is rejected by the OR chain (401).
     *
     * @param ctx the test context
     */
    @Test
    @DisplayName("Missing Authorization header is rejected by the OR chain (401)")
    void missingToken_rejected(VertxTestContext ctx) {
        client.get(port, "127.0.0.1", "/or-secured")
                .send()
                .map(response -> response.statusCode())
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertEquals(401, status, "a request with no Authorization header must be rejected");
                    ctx.completeNow();
                })));
    }

    /**
     * Issue #154 — RED #1. An OR route where the token authenticates the SECOND alternative only
     * (issuer B): the first alternative (issuer A) rejects the credential, but because the request
     * ultimately authenticates, NO {@link CredentialRejectedEvent} must be recorded. Before the fix the
     * first alternative's reporter emits its rejection synchronously, so exactly one spurious event is
     * captured even though the request succeeds (200).
     *
     * <p>Drives the request to completion and awaits the response-end window so the deferred
     * end-handler (which decides whether to flush buffered rejections) has run, then asserts zero
     * rejections.
     *
     * @param vertx the Vert.x instance, used to await the asynchronous end-handler
     * @param ctx   the test context
     */
    @Test
    @DisplayName("OR route authenticated by a LATER alternative records zero credential rejections")
    void orRoute_laterAlternativeWins_recordsNoRejection(Vertx vertx, VertxTestContext ctx) {
        String token = jwtAuthB.generateToken(new JsonObject().put("sub", "bob").put("iss", ISSUER_B));
        statusForSecured(token)
                .compose(status -> awaitRejectionsSettled(vertx).map(status))
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertEquals(200, status, "a token valid for the second alternative must authorize");
                    assertEquals(
                            0,
                            REJECTIONS.events.size(),
                            "a request authenticated by a later OR alternative must record NO credential rejection");
                    ctx.completeNow();
                })));
    }

    /**
     * Issue #154 — an OR route where the token authenticates NEITHER alternative: both alternatives
     * reject the credential and the chain fails (401). When authentication ultimately fails, the
     * buffered rejection(s) MUST be flushed — at least one {@link CredentialRejectedEvent} is recorded.
     *
     * @param vertx the Vert.x instance, used to await the asynchronous end-handler
     * @param ctx   the test context
     */
    @Test
    @DisplayName("OR route authenticated by NO alternative records at least one credential rejection")
    void orRoute_noAlternativeWins_recordsRejection(Vertx vertx, VertxTestContext ctx) {
        statusForSecured("not-a-real-jwt-token")
                .compose(status -> awaitRejectionsSettled(vertx).map(status))
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertEquals(401, status, "an invalid token must be rejected by the OR chain");
                    assertTrue(
                            REJECTIONS.events.size() >= 1,
                            "a request rejected by every OR alternative must record at least one credential rejection");
                    ctx.completeNow();
                })));
    }

    /**
     * Issue #154 — regression: a SINGLE-scheme route (mounted with no deferral wrapper) with an invalid
     * token must emit exactly one {@link CredentialRejectedEvent} IMMEDIATELY. This proves the
     * single-scheme path is unchanged — the deferral flag is never set, so the reporter takes its
     * original immediate-emit branch.
     *
     * @param vertx the Vert.x instance, used to await teardown of any async emit
     * @param ctx   the test context
     */
    @Test
    @DisplayName("single-scheme route emits exactly one credential rejection immediately (unchanged)")
    void singleSchemeRoute_invalidToken_emitsOneRejection(Vertx vertx, VertxTestContext ctx) {
        statusForSingle("not-a-real-jwt-token")
                .compose(status -> awaitRejectionsSettled(vertx).map(status))
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertEquals(401, status, "an invalid token on the single-scheme route must be rejected");
                    assertEquals(
                            1,
                            REJECTIONS.events.size(),
                            "a single-scheme route must emit exactly one credential rejection, immediately");
                    ctx.completeNow();
                })));
    }

    /**
     * Issue #154 — Codex review critical finding: a post-authentication claims rejection on an OR route
     * MUST be emitted immediately (NOT buffered and then dropped). When the OR chain authenticates
     * ({@code ctx.user() != null} after the chain completes), a subsequent post-auth handler —
     * mirroring {@code JwtClaimsValidatorContributor} — calls
     * {@link dev.vertique.rest.security.CredentialRejectionReporter#report} with reason code
     * {@code JWT_CLAIMS_INVALID} and then {@code ctx.fail(401)}.
     *
     * <p>At the time {@code report()} is called, {@code ctx.user()} is non-null (the chain set it), so
     * the reporter's {@code ctx.user() == null} guard is false: the event bypasses the buffer and is
     * emitted immediately, even though {@link DeferredCredentialRejectionAuthHandler#DEFER_CONTEXT_KEY}
     * is still set on the context. Before the Codex-identified fix (which added the user-null guard),
     * the reporter checked only the flag and buffered the event, then the end-handler saw
     * {@code ctx.user() != null} and dropped it — zero rejections were recorded for a real 401.
     *
     * @param vertx the Vert.x instance, used to await the end-handler window
     * @param ctx   the test context
     */
    @Test
    @DisplayName("post-auth claims rejection on OR route is emitted immediately, not dropped")
    void postAuthRejectionOnOrRoute_isEmittedNotDropped(Vertx vertx, VertxTestContext ctx) {
        // Token authenticates via the first alternative (issuer A), so ctx.user() is non-null when the
        // post-auth claims handler runs. The claims handler calls reporter.report(..., "JWT_CLAIMS_INVALID")
        // then ctx.fail(401). The response is 401 and exactly one rejection must be captured.
        String token =
                jwtAuthA.generateToken(new JsonObject().put("sub", "alice").put("iss", ISSUER_A));
        statusFor("/or-post-auth-claims", token)
                .compose(status -> awaitRejectionsSettled(vertx).map(status))
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertEquals(401, status, "a post-auth claims failure on an OR route must return 401");
                    assertEquals(
                            1,
                            REJECTIONS.events.size(),
                            "the post-auth JWT_CLAIMS_INVALID rejection must be emitted immediately, "
                                    + "not buffered and dropped because ctx.user() != null");
                    assertEquals(
                            "JWT_CLAIMS_INVALID",
                            REJECTIONS.events.get(0).reasonCode(),
                            "the captured event must carry the post-auth claims-failure reason code");
                    ctx.completeNow();
                })));
    }

    // --- Helpers ---

    /**
     * Issues a {@code GET /or-secured} carrying the given bearer token and resolves with the response
     * status code, draining the body so the connection is released.
     *
     * @param token the raw JWT to send after {@code "Bearer "}
     * @return a future of the response status code
     */
    private Future<Integer> statusForSecured(String token) {
        return statusFor("/or-secured", token);
    }

    /**
     * Issues a {@code GET /single-secured} carrying the given bearer token and resolves with the
     * response status code, draining the body so the connection is released.
     *
     * @param token the raw JWT to send after {@code "Bearer "}
     * @return a future of the response status code
     */
    private Future<Integer> statusForSingle(String token) {
        return statusFor("/single-secured", token);
    }

    /**
     * Issues a {@code GET} against the given path carrying the bearer token and resolves with the
     * response status code. The {@link WebClient} aggregates the body before completing the send,
     * so the connection is released without a separate drain step.
     *
     * @param path  the request path
     * @param token the raw JWT to send after {@code "Bearer "}
     * @return a future of the response status code
     */
    private Future<Integer> statusFor(String path, String token) {
        return client.get(port, "127.0.0.1", path)
                .putHeader("Authorization", "Bearer " + token)
                .send()
                .map(response -> response.statusCode());
    }

    /**
     * Resolves on the next event-loop turn so an {@code addEndHandler} scheduled during request
     * processing has fired before the test inspects the captured rejections. The response body is
     * already drained when the caller invokes this, so the end-handler has been registered and (for
     * the response-end hook) run; a single timer tick guarantees the buffered-flush decision has
     * completed on the event loop.
     *
     * @param vertx the Vert.x instance used to schedule the tick
     * @return a future completing after one event-loop tick
     */
    private Future<Void> awaitRejectionsSettled(Vertx vertx) {
        return Future.future(promise -> vertx.setTimer(50, id -> promise.complete()));
    }

    /**
     * Issues a {@code GET /or-secured} carrying the given bearer token and resolves with the response
     * status code.
     *
     * @param token the raw JWT to send after {@code "Bearer "}
     * @return a future of the response status code
     */
    private Future<Integer> statusFor(String token) {
        return client.get(port, "127.0.0.1", "/or-secured")
                .putHeader("Authorization", "Bearer " + token)
                .send()
                .map(response -> response.statusCode());
    }

    /**
     * Issues a {@code GET /or-identity} carrying the given bearer token and resolves with the JSON
     * body the {@link #resolveIdentity(io.vertx.ext.web.RoutingContext)} terminal handler produced —
     * the HTTP status, accumulated framework-evidence count, and the resolved actor type/id.
     *
     * <p>The empty-body fallback is kept as it was: {@link WebClient#get} reports an empty body as
     * {@code null} where the raw client reported a zero-length buffer, and both collapse to an
     * empty {@link JsonObject} carrying only the status. It is now unreachable for a body the
     * server actually sent — aggregating the body inside the send is what removed the race that
     * made this fallback fire in CI and turned every body-derived assertion into {@code null}.
     *
     * @param token the raw JWT to send after {@code "Bearer "}
     * @return a future of the resolved-identity JSON body
     */
    private Future<JsonObject> identityFor(String token) {
        return client.get(port, "127.0.0.1", "/or-identity")
                .putHeader("Authorization", "Bearer " + token)
                .send()
                .map(response -> {
                    String body = response.bodyAsString();
                    JsonObject json = body != null && !body.isEmpty() ? new JsonObject(body) : new JsonObject();
                    return json.put("status", response.statusCode());
                });
    }

    /**
     * Terminal handler for {@code /or-identity} that runs the request through the REAL framework
     * identity-resolution computation and writes the result into the response.
     *
     * <p>This mirrors {@code IdentityResolutionMiddleware.handle(...)}: it reads the accumulated
     * {@link AuthenticationEvidence} via {@link RestAuthenticationEvidence#get}, builds a
     * {@link SecurityIdentityResolutionContext} from it (empty origin/correlation/attributes, exactly
     * as the middleware does for a request with no captured origin), and runs the
     * {@link EvidenceBasedIdentityResolver} — an in-test resolver mirroring the framework default
     * {@code DefaultSecurityIdentityResolver} the middleware's chain ends on (that class has a
     * package-private constructor and cannot be instantiated from this package). The resolved
     * {@link SecurityIdentity}'s actor type/id plus the evidence count are returned so the test
     * asserts on the resolved identity rather than the bare HTTP status.
     *
     * @param rc the routing context after the OR auth chain has run
     */
    private static void resolveIdentity(io.vertx.ext.web.RoutingContext rc) {
        List<AuthenticationEvidence> evidence = RestAuthenticationEvidence.get(rc);
        SecurityIdentityResolutionContext resolutionCtx =
                new SecurityIdentityResolutionContext(evidence, Optional.empty(), Optional.empty(), Map.of());
        new EvidenceBasedIdentityResolver().resolve(resolutionCtx).onComplete(ar -> {
            SecurityIdentity identity =
                    ar.succeeded() && ar.result().isPresent() ? ar.result().get() : SecurityIdentity.anonymous();
            JsonObject body = new JsonObject()
                    .put("evidenceCount", evidence.size())
                    .put("actorType", identity.actor().type().name())
                    .put("actorId", identity.actor().id());
            rc.response()
                    .putHeader("content-type", "application/json")
                    .setStatusCode(200)
                    .end(body.encode());
        });
    }

    /**
     * In-test {@link dev.vertique.security.resolver.SecurityIdentityResolver} mirroring the core
     * classification of {@code DefaultSecurityIdentityResolver}: JWT evidence carrying a {@code sub}
     * safe attribute produces a {@link PrincipalType#USER} actor; empty evidence falls back to
     * {@link SecurityIdentity#anonymous()}.
     *
     * <p>{@code DefaultSecurityIdentityResolver} has a package-private constructor and cannot be
     * instantiated from {@code dev.vertique.rest.auth.jwt}; this resolver reproduces the same rules
     * the framework default applies to the {@code sub}-bearing JWT evidence these tests append (the
     * same approach {@code WebSocketSecurityPipelineIT} uses for the identical reason).
     */
    private static final class EvidenceBasedIdentityResolver
            implements dev.vertique.security.resolver.SecurityIdentityResolver {

        /** {@inheritDoc} — runs at priority 100, the same slot as the framework default resolver. */
        @Override
        public int priority() {
            return 100;
        }

        /** {@inheritDoc} */
        @Override
        public String id() {
            return "test-evidence-resolver";
        }

        /**
         * Resolves a {@link SecurityIdentity} from the evidence in the given context: empty evidence
         * → anonymous; JWT evidence with a {@code sub} claim → a {@code USER} actor.
         *
         * @param ctx the resolution context
         * @return a completed future with the resolved identity
         */
        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext ctx) {
            if (ctx.evidence().isEmpty()) {
                return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
            }
            AuthenticationEvidence primary = ctx.evidence().get(0);
            Object sub = primary.safeAttributes().get("sub");
            if (sub instanceof String subStr && !subStr.isBlank()) {
                PrincipalRef actor = new PrincipalRef(PrincipalType.USER, subStr, Map.of());
                return Future.succeededFuture(
                        Optional.of(new SecurityIdentity(actor, Optional.empty(), Optional.empty(), Optional.empty())));
            }
            return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
        }
    }

    /**
     * Builds a {@link DefaultCredentialRejectionReporter} backed by the shared {@link #REJECTIONS}
     * observer, using a stub {@link ContextHolder} that always returns a valid {@link CorrelationContext}.
     * Used for reporters that live outside the {@link #registeredHandler} factory (e.g. the post-auth
     * claims handler on {@code /or-post-auth-claims}).
     *
     * @return a configured reporter whose events flow into {@link #REJECTIONS}
     */
    private static DefaultCredentialRejectionReporter buildReporter() {
        CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
        CorrelationContext correlation = factory.create(
                new CorrelationIdentifier("req-001", "test"), new CorrelationIdentifier("cor-001", "test"));
        ContextHolder holder = mock(ContextHolder.class);
        when(holder.current(CorrelationContext.class)).thenReturn(Optional.of(correlation));
        return new DefaultCredentialRejectionReporter(holder, new SecurityEventEmitter(Set.of(REJECTIONS)));
    }

    /**
     * Builds a real {@link JwtBearerSecuritySchemeHandler} for the given scheme/auth/config and returns
     * the {@link AuthenticationHandler} it registers (a {@code DelegatingJwtAuthHandler}) — exactly what
     * the framework collects and feeds to {@code applySecurity}.
     *
     * @param vertx      the Vert.x instance
     * @param schemeName the OpenAPI scheme name
     * @param jwtAuth    the JWT auth provider backing this scheme
     * @param config     the validation config (issuer enforcement) for this scheme
     * @return the registered authentication handler
     */
    private static AuthenticationHandler registeredHandler(
            Vertx vertx, String schemeName, JWTAuth jwtAuth, JwtValidationConfig config) {
        CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
        CorrelationContext correlation = factory.create(
                new CorrelationIdentifier("req-001", "test"), new CorrelationIdentifier("cor-001", "test"));
        ContextHolder holder = Mockito.mock(ContextHolder.class);
        Mockito.when(holder.current(CorrelationContext.class)).thenReturn(Optional.of(correlation));
        DefaultCredentialRejectionReporter reporter =
                new DefaultCredentialRejectionReporter(holder, new SecurityEventEmitter(Set.of(REJECTIONS)));

        JwtBearerSecuritySchemeHandler schemeHandler =
                new JwtBearerSecuritySchemeHandler(schemeName, jwtAuth, config, reporter);
        CapturingRegistry registry = new CapturingRegistry();
        schemeHandler.configure(registry);
        return registry.captured;
    }

    /**
     * Capturing {@link SecurityEventObserver} that records every {@link CredentialRejectedEvent} it
     * receives, so tests can count the rejections a request produced. Uses a thread-safe list because
     * events are emitted on the Vert.x event loop while assertions read on the test thread.
     */
    private static final class CapturingRejectionObserver implements SecurityEventObserver {
        private final List<CredentialRejectedEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public Future<Void> onCredentialRejected(CredentialRejectedEvent event) {
            events.add(event);
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> onCredentialAccepted(CredentialAcceptedEvent event) {
            return Future.succeededFuture();
        }
    }

    /** Capturing scheme-registry that records the {@link AuthenticationHandler} a handler registers. */
    private static final class CapturingRegistry implements SecuritySchemeRegistry {
        private AuthenticationHandler captured;

        @Override
        public void authenticationHandler(AuthenticationHandler handler) {
            this.captured = handler;
        }
    }
}
