// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.RouteRegistration;
import dev.vertique.rest.core.routing.SecurityRequirement;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.EffectiveSecurityPolicy;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.security.AuthorizationContributor;
import dev.vertique.rest.security.DefaultSecurityClaimMapper;
import dev.vertique.rest.security.HolderBackedSecurityRuntime;
import dev.vertique.rest.security.IdentityResolutionContributor;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.RestAuthenticationEvidence;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
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
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.impl.UserContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.lang.annotation.Annotation;
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
 * End-to-end integration proof for finding C2 (SH-4): an operation declaring a single-scheme
 * {@code @SecurityRequirement(name="bearerAuth", scopes={"write"})} <strong>authorizes</strong> those
 * scopes, not just authenticates. The set's scopes are folded into the operation's effective
 * {@link SecurityPolicy} by {@link EffectiveSecurityPolicy#fold} at registration time, so the existing
 * {@code VertxProviderDecisionPoint}/{@code SecurityPolicyEnforcer} enforces them — one mechanism, one
 * {@code 403}.
 *
 * <h3>The bug this guards against</h3>
 * Before SH-4 the scoped requirement only contributed an authentication handler; its scopes were never
 * compared against the caller's claims. A caller with a valid token but lacking the {@code write} scope
 * reached the terminal handler ({@code 200}). The fold promotes the policy to
 * {@code Constrained([], ["write"], requireAllScopes=true)} so the decision point denies a caller
 * lacking the scope ({@code 403}).
 *
 * <h3>How the chain is wired</h3>
 * The security contributors are sorted with {@link OrderedExtension#comparator()} — exactly as the
 * runtime sorts them — and invoked over a plain Vert.x {@link Route}: a stub bearer auth handler
 * (authentication) is installed first, then the sorted contributors
 * ({@link IdentityResolutionContributor} at 80, {@link AuthorizationContributor} at 100), then a
 * terminal 200 handler. The {@link OperationRegistrationContext} carries the
 * <em>effective</em> policy computed by {@link EffectiveSecurityPolicy#fold} from the descriptor's
 * {@link RestOperationDescriptor#securityPolicy()} and
 * {@link RestOperationDescriptor#securityRequirementSets()} — the exact computation the registrar
 * performs.
 *
 * <h3>Scenarios</h3>
 * <ul>
 *   <li><b>scoped op, token WITH {@code write}</b> → authorized ({@code 200});</li>
 *   <li><b>scoped op, token WITHOUT {@code write}</b> → the decision point denies ({@code 403}) —
 *       RED before the fix (returns {@code 200}, scopes ignored);</li>
 *   <li><b>scopeless op</b> → no scope gate, authorized regardless of token scopes ({@code 200}).</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ScopedSecurityRequirementEnforcementIT {

    private static int port;
    private static HttpServer server;
    private static HttpClient client;

    /**
     * Builds and starts the shared HTTP server with two routes wired through the real, priority-sorted
     * security contributor chain: {@code /secured} (a scoped single-scheme {@code @SecurityRequirement}
     * folded into a scope-enforcing policy) and {@code /open} (a scopeless single-scheme requirement,
     * no scope gate). One {@link HttpClient} is shared across all tests.
     *
     * @param vertx the Vert.x instance injected by {@link VertxExtension}
     * @param ctx   the test context used for async startup assertion
     */
    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        client = vertx.createHttpClient();

        HolderBackedSecurityRuntime securityRuntime = new HolderBackedSecurityRuntime((sc, secure) -> null);
        SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of());

        ContextHolder contextHolder = new ContextHolder() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> Optional<T> current(Class<T> type) {
                if (type == CorrelationContext.class) {
                    return Optional.of((T) CorrelationContext.unbound());
                }
                return Optional.empty();
            }

            @Override
            public <T extends ContextValue> Scope bind(Class<T> type, T value) {
                return () -> {};
            }
        };

        IdentityResolutionMiddleware identityMiddleware = new IdentityResolutionMiddleware(
                Set.of(new EvidenceBasedIdentityResolver()),
                Optional.of(new DefaultSecurityClaimMapper()),
                emitter,
                securityRuntime,
                contextHolder);

        // Default decision point: no app override, no AuthorizationPolicy, no Authorizer (no
        // @RequiresAction here) — the scope gate is enforced by the framework VertxProviderDecisionPoint.
        SecurityPolicyEnforcer policyEnforcer = new SecurityPolicyEnforcer(
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                emitter,
                contextHolder,
                securityRuntime,
                Optional.empty());

        List<OperationHandlerContributor> sortedContributors = List.of(
                        new AuthorizationContributor(policyEnforcer),
                        new IdentityResolutionContributor(identityMiddleware))
                .stream()
                .sorted(OrderedExtension.comparator())
                .toList();

        Router router = Router.router(vertx);
        router.route("/*").handler(new RequestContextLifecycle());

        StubBearerAuthHandler authHandler = new StubBearerAuthHandler();

        // /secured — single-scheme @SecurityRequirement(name=bearerAuth, scopes={"write"}). The
        // descriptor's base policy is None (no Jakarta annotations); fold() promotes it to a scope-
        // enforcing Constrained, exactly as the registrar computes the effective policy.
        SecurityRequirementSet scopedSet =
                new SecurityRequirementSet(List.of(new SecurityRequirement("bearerAuth", List.of("write"))));
        mountOperation(vertx, router, "/secured", "getSecured", List.of(scopedSet), authHandler, sortedContributors);

        // /open — single-scheme scopeless @SecurityRequirement(name=bearerAuth). fold() leaves the
        // None policy unchanged, so NO scope gate is installed.
        SecurityRequirementSet scopelessSet =
                new SecurityRequirementSet(List.of(new SecurityRequirement("bearerAuth", List.of())));
        mountOperation(vertx, router, "/open", "getOpen", List.of(scopelessSet), authHandler, sortedContributors);

        vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").onComplete(ctx.succeeding(s -> {
            server = s;
            port = s.actualPort();
            ctx.completeNow();
        }));
    }

    /**
     * Mounts one operation on the router: installs the authentication handler first, then the
     * effective-policy-bearing contributors (sorted), then a terminal 200 handler — mirroring the
     * registrar's add-order. The {@link OperationRegistrationContext} carries the effective policy
     * produced by {@link EffectiveSecurityPolicy#fold} from the descriptor.
     *
     * @param vertx        the Vert.x instance
     * @param router       the router to mount on
     * @param path         the route path
     * @param operationId  the operationId
     * @param sets         the operation's security requirement sets
     * @param authHandler  the stub bearer authentication handler
     * @param contributors the sorted security contributors
     */
    private static void mountOperation(
            Vertx vertx,
            Router router,
            String path,
            String operationId,
            List<SecurityRequirementSet> sets,
            StubBearerAuthHandler authHandler,
            List<OperationHandlerContributor> contributors) {
        RestOperationDescriptor descriptor =
                new ScopedDescriptor(operationId, "GET", path, new SecurityPolicy.None(), sets);
        SecurityPolicy effective = EffectiveSecurityPolicy.fold(descriptor.securityPolicy(), sets);

        Route route = router.route(HttpMethod.GET, path);
        route.handler(authHandler.createHandler());

        RouteRegistration routeReg = new PlainRouteRegistrationAdapter(route, descriptor);
        OperationRegistrationContext registrationContext =
                new OperationRegistrationContext(operationId, effective, descriptor, routeReg);
        contributors.forEach(c -> c.contribute(registrationContext));

        route.handler(rc -> rc.response().setStatusCode(200).end("ok"));
    }

    /**
     * Closes the shared HTTP server and {@link HttpClient}.
     *
     * @param ctx the test context used for async teardown assertion
     */
    @AfterAll
    static void tearDown(VertxTestContext ctx) {
        Future<?> s = server != null ? server.close() : Future.succeededFuture();
        Future<?> c = client != null ? client.close() : Future.succeededFuture();
        Future.join(s, c).onComplete(ar -> ctx.completeNow());
    }

    // --- Tests ---

    /**
     * A token carrying the {@code write} scope satisfies the folded scope gate and reaches the terminal
     * handler ({@code 200}).
     *
     * @param ctx the test context
     */
    @Test
    @DisplayName("scoped op: token WITH the write scope is authorized (200)")
    void scopedOp_tokenWithScope_authorized(VertxTestContext ctx) {
        get("/secured", "alice|write")
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertEquals(200, status, "a token carrying the required write scope must be authorized");
                    ctx.completeNow();
                })));
    }

    /**
     * A token <em>without</em> the {@code write} scope is denied by the decision point ({@code 403}).
     * This is the C2 fix: before SH-4 the scopes were ignored and this returned {@code 200} (RED).
     *
     * @param ctx the test context
     */
    @Test
    @DisplayName("scoped op: token WITHOUT the write scope is denied (403)")
    void scopedOp_tokenWithoutScope_denied(VertxTestContext ctx) {
        get("/secured", "alice|read")
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertEquals(
                            403,
                            status,
                            "a token lacking the required write scope must be denied by the decision point; "
                                    + "a 200 means @SecurityRequirement scopes were not enforced (finding C2)");
                    ctx.completeNow();
                })));
    }

    /**
     * A scopeless single-scheme {@code @SecurityRequirement} installs no scope gate, so any
     * authenticated caller is authorized regardless of token scopes ({@code 200}) — proving the fold
     * leaves a scopeless set untouched.
     *
     * @param ctx the test context
     */
    @Test
    @DisplayName("scopeless op: authorized regardless of token scopes (200)")
    void scopelessOp_noScopeGate_authorized(VertxTestContext ctx) {
        get("/open", "alice|read")
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertEquals(200, status, "a scopeless requirement must not engage a scope gate");
                    ctx.completeNow();
                })));
    }

    // --- Helpers ---

    /**
     * Issues a {@code GET} to the given path with an {@code Authorization: Bearer} header and resolves
     * with the HTTP status code.
     *
     * @param path  the request path
     * @param token the bearer token value (format {@code <sub>|<csv-scopes>})
     * @return a future resolving with the response status code
     */
    private Future<Integer> get(String path, String token) {
        return client.request(HttpMethod.GET, port, "127.0.0.1", path).compose(req -> {
            req.putHeader("Authorization", "Bearer " + token);
            return req.send().map(resp -> resp.statusCode());
        });
    }

    // --- Test doubles ---

    /**
     * Stub {@link RouteAuthHandler} interpreting an {@code Authorization: Bearer <sub>|<csv-scopes>}
     * header: on success it sets the Vert.x {@link User} (with a {@code scope} claim for the claim
     * mapper) and appends {@link AuthenticationEvidence}. A malformed header fails the request with 401.
     */
    static class StubBearerAuthHandler implements RouteAuthHandler {

        @Override
        public String schemeName() {
            return "bearerAuth";
        }

        @Override
        public Handler<RoutingContext> createHandler() {
            return this::handle;
        }

        private void handle(RoutingContext ctx) {
            String authorization = ctx.request().getHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                ctx.fail(401);
                return;
            }
            String token = authorization.substring("Bearer ".length()).trim();
            String[] parts = token.split("\\|", -1);
            if (parts.length < 2) {
                ctx.fail(401);
                return;
            }
            String sub = parts[0];
            String scopeStr = parts[1].replace(',', ' ');
            // "scope" is a space-delimited string per RFC 6749 §3.3 — DefaultSecurityClaimMapper maps it.
            JsonObject principal = new JsonObject().put("sub", sub).put("scope", scopeStr);
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
    }

    /**
     * In-test {@link SecurityIdentityResolver} mapping JWT evidence with a {@code sub} attribute to a
     * {@link PrincipalType#USER} actor; empty evidence falls back to anonymous.
     */
    static class EvidenceBasedIdentityResolver implements SecurityIdentityResolver {

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

    /**
     * Minimal {@link RestOperationDescriptor} carrying a security policy and the operation's security
     * requirement sets. The contributors exercised here read {@code operationId}/{@code securityPolicy}
     * from the context and {@code route().addHandler(...)}; the descriptor's sets feed the effective
     * policy computation in {@link #mountOperation}.
     *
     * @param operationId    the operationId
     * @param httpMethod     the HTTP method
     * @param routeTemplate  the route template
     * @param securityPolicy the base (pre-fold) security policy
     * @param sets           the operation's security requirement sets
     */
    record ScopedDescriptor(
            String operationId,
            String httpMethod,
            String routeTemplate,
            SecurityPolicy securityPolicy,
            List<SecurityRequirementSet> sets)
            implements RestOperationDescriptor {

        @Override
        public List<String> consumes() {
            return List.of();
        }

        @Override
        public List<String> produces() {
            return List.of();
        }

        @Override
        public List<SecurityRequirementSet> securityRequirementSets() {
            return sets;
        }

        @Override
        public List<Annotation> methodAnnotations() {
            return List.of();
        }

        @Override
        public List<Annotation> classAnnotations() {
            return List.of();
        }

        @Override
        public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
            return Optional.empty();
        }
    }

    /**
     * Test-only {@link RouteRegistration} adapter over a plain Vert.x {@link Route}, mirroring the
     * production {@code PlainRouteRegistration} (which lives in rest-jaxrs, not on this module's
     * classpath).
     */
    static final class PlainRouteRegistrationAdapter implements RouteRegistration {

        private final Route route;
        private final RestOperationDescriptor operation;

        PlainRouteRegistrationAdapter(Route route, RestOperationDescriptor operation) {
            this.route = route;
            this.operation = operation;
        }

        @Override
        public RouteRegistration addHandler(Handler<RoutingContext> handler) {
            route.handler(handler);
            return this;
        }

        @Override
        public RestOperationDescriptor operation() {
            return operation;
        }
    }
}
