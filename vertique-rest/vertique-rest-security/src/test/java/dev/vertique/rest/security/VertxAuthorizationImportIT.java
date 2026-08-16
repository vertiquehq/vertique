// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.exception.UnavailableException;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.security.verification.CustomVerificationSource;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authorization.Authorization;
import io.vertx.ext.auth.authorization.AuthorizationProvider;
import io.vertx.ext.auth.authorization.RoleBasedAuthorization;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.impl.UserContextInternal;
import io.vertx.ext.web.openapi.router.OpenAPIRoute;
import io.vertx.ext.web.openapi.router.RequestExtractor;
import io.vertx.ext.web.openapi.router.RouterBuilder;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.openapi.contract.OpenAPIContract;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end integration test proving that a Vert.x {@link AuthorizationProvider}'s grants, imported
 * through {@link VertxAuthorizationImporter} wired into {@link IdentityResolutionMiddleware}, satisfy
 * a {@link SecurityPolicy.Constrained} role requirement enforced by {@link SecurityPolicyEnforcer}.
 *
 * <p>Manual assembly mirrors {@link ActionOnlyRouteAuthIT}: a real Vert.x HTTP server, the OpenAPI
 * router built from a minimal fixture spec, and the security handler chain added per route in
 * production order — stub bearer auth → identity resolution middleware → the enforcer's
 * {@code Constrained(["team-lead"])} handler → a terminal 200 handler. No JAX-RS.
 *
 * <p>Every route is called with the same bearer token ({@code alice|viewer}), whose principal does
 * NOT carry the required {@code team-lead} role. Three scenarios:
 * <ul>
 *   <li><b>importer present</b> — a provider ({@code teams}) grants
 *       {@code RoleBasedAuthorization("team-lead")}: the imported claim satisfies the constraint
 *       (200);</li>
 *   <li><b>importer absent</b> — same assembly without an importer: the mapper-produced claims lack
 *       the role, so the enforcer denies (403) — the negative control;</li>
 *   <li><b>importer failing</b> — a provider that fails: the import fails the request with
 *       {@link UnavailableException}, surfaced as 503.</li>
 * </ul>
 *
 * <p>The root failure handler mirrors the production error pipeline's core semantic mapping
 * ({@code UnavailableException} → 503, per {@code DefaultExceptionMapper}); vertique-rest-security
 * has no dependency on the rest-jaxrs module that owns the real pipeline.
 *
 * <p>The client is a {@link WebClient} rather than a raw {@code HttpClient} deliberately: a raw
 * {@code HttpClientResponse} discards body buffers that arrive before a body handler is attached, so
 * under load a body read can succeed with zero bytes while the status code is correct (issue #167).
 * The 503 scenario asserts on the ProblemDetail body — both that it carries the generic detail and
 * that it never names the failing provider — and the second of those is an assertion an emptied body
 * would satisfy vacuously. A {@link WebClient} aggregates the body into its {@code HttpResponse}
 * before completing the send, so the body under assertion is the one the server actually wrote.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class VertxAuthorizationImportIT {

    /** Id of the provider wired behind {@code /failing-importer}; must never reach the client. */
    private static final String FAILING_PROVIDER_ID = "teams-down";

    private static int port;
    private static HttpServer server;
    private static WebClient client;

    /**
     * Builds and starts the shared HTTP server with the three constrained routes, each wired with a
     * different middleware assembly. One {@link WebClient} is shared across all tests.
     *
     * @param vertx the Vert.x instance injected by {@link VertxExtension}
     * @param ctx   the test context used for async startup assertion
     */
    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));

        HolderBackedSecurityRuntime securityRuntime = new HolderBackedSecurityRuntime((sc, secure) -> null);
        SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of());

        // No CorrelationContext is bound in this harness; the middleware tolerates its absence and
        // the enforcer builds its decision event with CorrelationContext.unbound().
        ContextHolder contextHolder = new ContextHolder() {
            @Override
            public <T> Optional<T> current(Class<T> type) {
                return Optional.empty();
            }

            @Override
            public <T extends ContextValue> Scope bind(Class<T> type, T value) {
                return () -> {};
            }
        };

        SecurityPolicy.Constrained policy = new SecurityPolicy.Constrained(List.of("team-lead"), List.of(), false);
        SecurityPolicyEnforcer enforcer = new SecurityPolicyEnforcer(
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                emitter,
                contextHolder,
                securityRuntime,
                Optional.empty());

        IdentityResolutionMiddleware withImporter = middleware(
                securityRuntime,
                emitter,
                contextHolder,
                Optional.of(new VertxAuthorizationImporter(
                        Set.of(grantingProvider("teams", RoleBasedAuthorization.create("team-lead"))), Set.of())));
        IdentityResolutionMiddleware withoutImporter =
                middleware(securityRuntime, emitter, contextHolder, Optional.empty());
        IdentityResolutionMiddleware failingImporter = middleware(
                securityRuntime,
                emitter,
                contextHolder,
                Optional.of(new VertxAuthorizationImporter(Set.of(failingProvider(FAILING_PROVIDER_ID)), Set.of())));

        OpenAPIContract.from(vertx, "vertx-authz-import-test-openapi.json")
                .compose(contract -> {
                    RouterBuilder routerBuilder =
                            RouterBuilder.create(vertx, contract, RequestExtractor.withBodyHandler());

                    wire(routerBuilder.getRoute("withImporter"), withImporter, enforcer, policy);
                    wire(routerBuilder.getRoute("withoutImporter"), withoutImporter, enforcer, policy);
                    wire(routerBuilder.getRoute("failingImporter"), failingImporter, enforcer, policy);

                    Router apiRouter = routerBuilder.createRouter();
                    Router root = Router.router(vertx);
                    // Stamps every response from this test's router so a failing status assertion can
                    // tell "this server answered but the outcome differed" (marker present) from "this
                    // response was not stamped by this test's root handler" (see #186).
                    root.route().order(Integer.MIN_VALUE).handler(rc -> {
                        rc.response().putHeader("x-vq-test-server", "vertx-authz-import");
                        rc.next();
                    });
                    // RequestContextLifecycle must run first so the per-request scope exists for the
                    // identity middleware to register its SecurityContext cleanup.
                    root.route("/*").handler(new RequestContextLifecycle());
                    root.route("/*").subRouter(apiRouter);
                    // Mirrors the production DefaultExceptionMapper's core semantic mapping for the
                    // one type this IT proves (UnavailableException -> ProblemDetail.of(503,
                    // ex.getMessage()) as application/problem+json); status-only failures
                    // (401/403 from the auth/authorization handlers) keep their status. The body
                    // carries the exception message verbatim, exactly as the production mapper
                    // does, so this IT can prove what a client actually gets to see.
                    root.route().failureHandler(rc -> {
                        if (rc.failure() instanceof UnavailableException unavailable) {
                            rc.response()
                                    .setStatusCode(503)
                                    .putHeader("content-type", "application/problem+json")
                                    .end(new JsonObject()
                                            .put("status", 503)
                                            .put("detail", unavailable.getMessage())
                                            .encode());
                            return;
                        }
                        rc.response()
                                .setStatusCode(rc.statusCode() > 0 ? rc.statusCode() : 500)
                                .end();
                    });

                    return vertx.createHttpServer().requestHandler(root).listen(0, "127.0.0.1");
                })
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    port = s.actualPort();
                    ctx.completeNow();
                }));
    }

    /**
     * Closes the shared {@link WebClient} and then the shared HTTP server.
     *
     * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns once
     * the underlying client has been asked to close, so there is no future to join here and the server
     * close alone carries the completion.
     *
     * @param ctx the test context used for async teardown assertion
     */
    @AfterAll
    static void tearDown(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        Future<Void> s = server != null ? server.close() : Future.succeededFuture();
        s.onComplete(ar -> ctx.completeNow());
    }

    // --- Tests ---

    /**
     * A provider-granted {@code team-lead} role imported into the bound claims satisfies the
     * constrained route even though the caller's principal lacks the role.
     *
     * @param ctx the test context
     */
    @Test
    @DisplayName("provider-granted role satisfies a Constrained route the principal alone would fail (200)")
    void providerGrantedRoleAuthorizesConstrainedRoute(VertxTestContext ctx) {
        get("/with-importer", "alice|viewer")
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertEquals(
                            200,
                            resp.status(),
                            () -> "the imported provider role must satisfy the team-lead constraint; got "
                                    + resp.status() + diagnosticSuffix(resp));
                    ctx.completeNow();
                })));
    }

    /**
     * Without an importer the same principal is denied — the negative control proving the permit
     * above comes from the imported claim, not from the principal or the enforcer wiring.
     *
     * @param ctx the test context
     */
    @Test
    @DisplayName("same assembly without an importer denies the Constrained route (403)")
    void withoutImporterConstrainedRouteDenies(VertxTestContext ctx) {
        get("/without-importer", "alice|viewer")
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertEquals(
                            403,
                            resp.status(),
                            () -> "without the importer the viewer principal must be denied; got " + resp.status()
                                    + diagnosticSuffix(resp));
                    ctx.completeNow();
                })));
    }

    /**
     * A failing provider fails the whole request with {@link UnavailableException}, surfaced as 503
     * — never a partially-authorized 200 or a misleading 403. The client-visible ProblemDetail
     * carries only the generic detail: the failing provider's id stays server-side.
     *
     * @param ctx the test context
     */
    @Test
    @DisplayName("provider failure yields 503 whose ProblemDetail detail is generic and names no provider")
    void providerFailureYieldsServiceUnavailable(VertxTestContext ctx) {
        get("/failing-importer", "alice|viewer")
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertEquals(
                            503,
                            resp.status(),
                            () -> "a failing authorization provider must surface as 503; got " + resp.status()
                                    + diagnosticSuffix(resp));
                    assertTrue(
                            resp.body().contains("Authorization is temporarily unavailable"),
                            () -> "the 503 ProblemDetail must carry the generic detail; got " + resp.body());
                    assertFalse(
                            resp.body().contains(FAILING_PROVIDER_ID),
                            () -> "the 503 body must never name the failing provider id [" + FAILING_PROVIDER_ID
                                    + "]; got " + resp.body());
                    ctx.completeNow();
                })));
    }

    // --- Assembly helpers ---

    /**
     * Builds the identity middleware under test with the shared runtime/emitter/holder and the given
     * importer, using the new 7-arg constructor (or the 6-arg one when the importer is absent, the
     * pre-existing production shape).
     *
     * @param runtime       the shared security runtime
     * @param emitter       the shared event emitter
     * @param contextHolder the shared (empty) context holder
     * @param importer      the importer to wire, or empty for the no-importer control
     * @return the middleware
     */
    private static IdentityResolutionMiddleware middleware(
            HolderBackedSecurityRuntime runtime,
            SecurityEventEmitter emitter,
            ContextHolder contextHolder,
            Optional<VertxAuthorizationImporter> importer) {
        if (importer.isEmpty()) {
            return new IdentityResolutionMiddleware(
                    Set.of(new EvidenceIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    emitter,
                    runtime,
                    contextHolder);
        }
        return new IdentityResolutionMiddleware(
                Set.of(new EvidenceIdentityResolver()),
                Optional.of(new DefaultSecurityClaimMapper()),
                emitter,
                runtime,
                contextHolder,
                Optional.empty(),
                importer);
    }

    /**
     * Adds the production-order security chain to one OpenAPI route: bearer auth → identity
     * middleware → the enforcer's Constrained handler → terminal 200.
     *
     * @param route      the OpenAPI route to wire
     * @param middleware the identity middleware variant for this route
     * @param enforcer   the shared policy enforcer
     * @param policy     the constrained policy every route enforces
     */
    private static void wire(
            OpenAPIRoute route,
            IdentityResolutionMiddleware middleware,
            SecurityPolicyEnforcer enforcer,
            SecurityPolicy.Constrained policy) {
        route.addHandler(VertxAuthorizationImportIT::bearerAuth);
        route.addHandler(middleware);
        route.addHandler(enforcer.createHandler(policy));
        route.addHandler(rc -> rc.response().setStatusCode(200).end("ok"));
    }

    /**
     * Stub bearer auth handler interpreting {@code Authorization: Bearer <sub>|<csv-roles>}: on
     * success it sets the Vert.x {@link User} (with a {@code roles} claim for the claim mapper) and
     * appends {@link AuthenticationEvidence}; a missing/malformed header fails with 401. Mirrors
     * {@link ActionOnlyRouteAuthIT}'s stub.
     *
     * @param ctx the routing context
     */
    private static void bearerAuth(RoutingContext ctx) {
        String authorization = ctx.request().getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            ctx.fail(401);
            return;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        int sep = token.indexOf('|');
        if (sep < 0) {
            ctx.fail(401);
            return;
        }
        String sub = token.substring(0, sep);
        String rolesStr = token.substring(sep + 1);
        List<String> roles = rolesStr.isEmpty() ? List.of() : List.of(rolesStr.split(","));
        JsonObject principal = new JsonObject().put("sub", sub).put("roles", roles);
        ((UserContextInternal) ctx.userContext()).setUser(User.create(principal));
        AuthenticationEvidence evidence = new AuthenticationEvidence(
                DefaultAuthMethod.jwt(),
                Optional.of("stub-" + sub),
                Instant.now(),
                Optional.empty(),
                new CustomVerificationSource("stub-bearer", Map.of()),
                Map.of("sub", sub));
        RestAuthenticationEvidence.append(ctx, evidence);
        ctx.next();
    }

    /**
     * Builds an {@link AuthorizationProvider} that grants the given authorizations into its own
     * provider bucket.
     *
     * @param id     the provider id
     * @param grants the authorizations to grant
     * @return the provider
     */
    private static AuthorizationProvider grantingProvider(String id, Authorization... grants) {
        return new AuthorizationProvider() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Future<Void> getAuthorizations(User user) {
                user.authorizations().put(id, Set.of(grants));
                return Future.succeededFuture();
            }
        };
    }

    /**
     * Builds an {@link AuthorizationProvider} whose resolution always fails.
     *
     * @param id the provider id
     * @return the failing provider
     */
    private static AuthorizationProvider failingProvider(String id) {
        return new AuthorizationProvider() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Future<Void> getAuthorizations(User user) {
                return Future.failedFuture(new IllegalStateException("provider down"));
            }
        };
    }

    // --- Client helpers ---

    /**
     * Issues a {@code GET} to the given path with an {@code Authorization: Bearer} header and resolves
     * with the response status, body, and marker-header presence.
     *
     * <p>The {@link WebClient} aggregates the body into its {@code HttpResponse} before completing the
     * send, so no explicit drain is needed. An empty body yields {@code null} from
     * {@code bodyAsString()} where the raw client yielded an empty {@code Buffer};
     * {@link String#valueOf(Object)} keeps {@link Resp#body()} never-null exactly as
     * {@code Buffer.toString()} did.
     *
     * @param path  the request path
     * @param token the bearer token value (format {@code <sub>|<csv-roles>})
     * @return a future resolving with the {@link Resp}
     */
    private Future<Resp> get(String path, String token) {
        return client.get(port, "127.0.0.1", path)
                .putHeader("Authorization", "Bearer " + token)
                .send()
                .map(resp -> new Resp(
                        resp.statusCode(),
                        String.valueOf(resp.bodyAsString()),
                        resp.getHeader("x-vq-test-server") != null));
    }

    /**
     * Response status, body text, and whether the {@code x-vq-test-server} marker header (stamped by
     * this test's root router) was present; the marker is used only to enrich failure messages (see
     * #186).
     *
     * @param status         the HTTP status code
     * @param body           the response body as text
     * @param fromThisServer whether the marker header was present on the response
     */
    private record Resp(int status, String body, boolean fromThisServer) {}

    /**
     * Builds the marker-diagnostic suffix appended to status-assertion failure messages (see #186).
     *
     * @param resp the response to describe
     * @return the diagnostic suffix text
     */
    private static String diagnosticSuffix(Resp resp) {
        return resp.fromThisServer()
                ? " (marker present: response from this test's router — auth/authz outcome differed)"
                : " (marker ABSENT: response was not stamped by this test's root handler — see #186)";
    }

    // --- Test doubles ---

    /**
     * In-test {@link SecurityIdentityResolver} mapping JWT evidence with a {@code sub} attribute to
     * a {@link PrincipalType#USER} actor; mirrors {@link ActionOnlyRouteAuthIT}'s resolver.
     */
    static class EvidenceIdentityResolver implements SecurityIdentityResolver {

        @Override
        public int priority() {
            return 100;
        }

        @Override
        public String id() {
            return "test-evidence-resolver";
        }

        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext ctx) {
            if (ctx.evidence().isEmpty()) {
                return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
            }
            AuthenticationEvidence primary = ctx.evidence().get(0);
            Object sub = primary.safeAttributes().get("sub");
            if (sub instanceof String subStr && !subStr.isBlank()) {
                PrincipalRef actor = new PrincipalRef(PrincipalType.USER, subStr, Map.of());
                SecurityIdentity identity =
                        new SecurityIdentity(actor, Optional.empty(), Optional.empty(), Optional.empty());
                return Future.succeededFuture(Optional.of(identity));
            }
            return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
        }
    }
}
