// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.RouteRegistration;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.ResourceRef;
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
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.impl.UserContextInternal;
import io.vertx.ext.web.openapi.router.OpenAPIRoute;
import io.vertx.ext.web.openapi.router.RequestExtractor;
import io.vertx.ext.web.openapi.router.RouterBuilder;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.openapi.contract.OpenAPIContract;
import java.lang.annotation.Annotation;
import java.time.Instant;
import java.util.Collection;
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
 * End-to-end integration test proving that an <em>action-only</em> REST route
 * ({@link SecurityPolicy.None} carrying a {@code @RequiresAction} gate, declaring no OpenAPI security
 * requirement) authenticates the caller before the action gate runs, so the gate evaluates the real
 * identity rather than an anonymous one (Phase-3 review finding C — silent auth bypass).
 *
 * <p>The {@code /content} operation in the fixture spec declares <strong>no</strong> security
 * requirement, so the OpenAPI-security-scheme-driven auth path installs no handler for it. The fix —
 * {@link ActionGateAuthenticationContributor} — installs a {@link RouteAuthHandler} on such a route so
 * {@link AuthenticationEvidence} is appended, {@link IdentityResolutionMiddleware} resolves the real
 * identity, and the action gate ({@link AuthorizationContributor} → {@link SecurityPolicyEnforcer})
 * evaluates that identity.
 *
 * <p>The full action-only handler chain is wired exactly as {@code JaxRsRouteRegistrar} would, by
 * invoking the three security contributors in {@link dev.vertique.core.extension.OrderedExtension}
 * priority order over the real {@link OpenAPIRoute}:
 * {@link ActionGateAuthenticationContributor} (40) → {@link IdentityResolutionContributor} (80) →
 * {@link AuthorizationContributor} (100), followed by a terminal 200-OK handler.
 *
 * <p>The in-test {@link Authorizer} permits {@code cms.content.read} only for an actor holding the
 * {@code editor} role. Three scenarios:
 * <ul>
 *   <li><b>permit</b> — a valid bearer token with the {@code editor} role: the gate sees the real
 *       identity and permits, so the terminal handler runs (200);</li>
 *   <li><b>deny (wrong role)</b> — a valid bearer token without {@code editor}: the gate sees the real
 *       identity and denies (403) — proving the gate evaluated the real identity, not anonymous;</li>
 *   <li><b>deny (no token)</b> — no {@code Authorization} header: the auth handler rejects (401), so an
 *       unauthenticated caller is denied rather than silently passed through as anonymous.</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ActionOnlyRouteAuthIT {

    private static final ActionRef CONTENT_READ = ActionRef.parse("cms.content.read");

    private static int port;
    private static HttpServer server;
    private static HttpClient client;

    /**
     * Builds and starts the shared HTTP server with the action-only {@code /content} route wired
     * through the real security contributor chain. One {@link HttpClient} is shared across all tests.
     *
     * @param vertx the Vert.x instance injected by {@link VertxExtension}
     * @param ctx   the test context used for async startup assertion
     */
    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        client = vertx.createHttpClient();

        HolderBackedSecurityRuntime securityRuntime = new HolderBackedSecurityRuntime((sc, secure) -> null);
        SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of());

        // No CorrelationContext is bound in this harness; the middleware tolerates its absence
        // (CredentialAcceptedEvent is simply skipped) and the action gate does not require it.
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

        IdentityResolutionMiddleware identityMiddleware = new IdentityResolutionMiddleware(
                Set.of(new EvidenceBasedIdentityResolver()),
                Optional.of(new DefaultSecurityClaimMapper()),
                emitter,
                securityRuntime,
                contextHolder);
        IdentityResolutionContributor identityContributor = new IdentityResolutionContributor(identityMiddleware);

        // SecurityPolicyEnforcer wired with the real action Authorizer so the action gate is actually
        // evaluated (no AuthorizationDecisionPoint / AuthorizationPolicy → default decision point,
        // unused for an action-only None policy because the role/scope gate auto-passes).
        SecurityPolicyEnforcer policyEnforcer = new SecurityPolicyEnforcer(
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                emitter,
                contextHolder,
                securityRuntime,
                Optional.of(new EditorActionAuthorizer()));
        AuthorizationContributor authorizationContributor = new AuthorizationContributor(policyEnforcer);

        ActionGateAuthenticationContributor authContributor =
                new ActionGateAuthenticationContributor(Set.of(new StubBearerAuthHandler()));

        ActionRegistry actionRegistry = new StubActionRegistry(Set.of(CONTENT_READ));

        OpenAPIContract.from(vertx, "action-only-route-test-openapi.json")
                .compose(contract -> {
                    RouterBuilder routerBuilder =
                            RouterBuilder.create(vertx, contract, RequestExtractor.withBodyHandler());

                    OpenAPIRoute route = routerBuilder.getRoute("getContent");
                    // Action-only operation: SecurityPolicy.None + @RequiresAction("cms.content.read").
                    RestOperationDescriptor descriptor =
                            new ActionOnlyDescriptor("getContent", "GET", "/content", new SecurityPolicy.None());
                    RouteRegistration routeReg = new OpenApiRouteRegistrationAdapter(route, descriptor);
                    OperationRegistrationContext registrationContext = new OperationRegistrationContext(
                            "getContent", new SecurityPolicy.None(), Optional.of(CONTENT_READ), descriptor, routeReg);

                    // Chain in OrderedExtension priority order, exactly as JaxRsRouteRegistrar would:
                    // auth (70) → identity (80) → authorization (100), then the terminal handler.
                    authContributor.contribute(registrationContext);
                    identityContributor.contribute(registrationContext);
                    authorizationContributor.contribute(registrationContext);
                    route.addHandler(rc -> rc.response().setStatusCode(200).end("content"));

                    Router apiRouter = routerBuilder.createRouter();
                    Router root = Router.router(vertx);
                    // RequestContextLifecycle must run first so the per-request scope exists for the
                    // identity middleware to register its SecurityContext cleanup.
                    root.route("/*").handler(new RequestContextLifecycle());
                    root.route("/*").subRouter(apiRouter);

                    // actionRegistry referenced to mirror the real wiring contract (validated at
                    // startup by JaxRsRouteRegistrar); it has no runtime role on this path.
                    assertEquals(true, actionRegistry.contains(CONTENT_READ));

                    return vertx.createHttpServer().requestHandler(root).listen(0);
                })
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    port = s.actualPort();
                    ctx.completeNow();
                }));
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
     * A valid bearer token whose actor holds the {@code editor} role passes the action gate: the gate
     * evaluated the real identity (not anonymous), so the terminal handler runs and returns 200.
     *
     * @param ctx the test context
     */
    @Test
    @DisplayName("valid token with required role: action gate sees real identity and permits (200)")
    void validTokenWithRolePermitted(VertxTestContext ctx) {
        get("/content", "alice|editor")
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertEquals(
                            200,
                            status,
                            "an action-only route with a valid token must reach the handler — the gate must see the "
                                    + "real identity, not anonymous");
                    ctx.completeNow();
                })));
    }

    /**
     * A valid bearer token whose actor lacks the {@code editor} role is denied (403). This proves the
     * action gate evaluated the real identity — an anonymous identity would also be denied, but the
     * permit case above rules out the gate seeing anonymous for the editor token.
     *
     * @param ctx the test context
     */
    @Test
    @DisplayName("valid token without required role: action gate denies (403)")
    void validTokenWithoutRoleDenied(VertxTestContext ctx) {
        get("/content", "bob|viewer")
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertEquals(
                            403, status, "a valid token lacking the required role must be denied by the action gate");
                    ctx.completeNow();
                })));
    }

    /**
     * No {@code Authorization} header: the auth handler installed on the action-only route rejects with
     * 401, so an unauthenticated caller is denied rather than silently passed through as anonymous.
     *
     * @param ctx the test context
     */
    @Test
    @DisplayName("no token: auth handler denies (401), not silently anonymous")
    void noTokenDenied(VertxTestContext ctx) {
        get("/content", null)
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertEquals(
                            401,
                            status,
                            "an action-only route with no token must be denied (401), not anonymous-allowed");
                    ctx.completeNow();
                })));
    }

    // --- Helpers ---

    /**
     * Issues a {@code GET} to the given path, optionally with an {@code Authorization: Bearer} header,
     * and resolves with the HTTP status code.
     *
     * @param path  the request path
     * @param token the bearer token value (format {@code <sub>|<csv-roles>}), or {@code null} to send
     *              no {@code Authorization} header
     * @return a future resolving with the response status code
     */
    private Future<Integer> get(String path, String token) {
        return client.request(HttpMethod.GET, port, "localhost", path).compose(req -> {
            if (token != null) {
                req.putHeader("Authorization", "Bearer " + token);
            }
            return req.send().map(resp -> resp.statusCode());
        });
    }

    // --- Test doubles ---

    /**
     * In-test {@link Authorizer} that permits {@link #CONTENT_READ} only for an actor holding the
     * {@code editor} {@link AuthorityKind#ROLE} claim; everything else fails closed to a deny.
     */
    static class EditorActionAuthorizer implements Authorizer {

        @Override
        public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
            return authorize(request.securityContext(), ActionRef.parse(request.action()), request.resource());
        }

        @Override
        public Future<AuthorizationDecision> authorize(SecurityContext ctx, ActionRef action, ResourceRef resource) {
            if (ctx == null) {
                return Future.succeededFuture(AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR));
            }
            Set<String> roles = ctx.authorization().valuesOf(AuthorityKind.ROLE);
            boolean permit = CONTENT_READ.equals(action) && roles.contains("editor");
            return Future.succeededFuture(
                    permit
                            ? AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)
                            : AuthorizationDecision.deny(AuthzReasonCodes.ACTION_NOT_ALLOWED));
        }
    }

    /**
     * Stub {@link RouteAuthHandler} interpreting an {@code Authorization: Bearer <sub>|<csv-roles>}
     * header: on success it sets the Vert.x {@link User} (with a {@code roles} claim for the claim
     * mapper) and appends {@link AuthenticationEvidence}; a missing/malformed header fails the
     * request with 401.
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
    }

    /**
     * In-test {@link SecurityIdentityResolver} mapping JWT evidence with a {@code sub} attribute to a
     * {@link PrincipalType#USER} actor.
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
     * Minimal {@link ActionRegistry} backed by a fixed set of registered {@link ActionRef}s.
     *
     * @param registered the set of action references considered registered
     */
    record StubActionRegistry(Set<ActionRef> registered) implements ActionRegistry {

        @Override
        public Collection<ActionDefinition> actions() {
            return registered.stream().map(ActionDefinition::new).toList();
        }

        @Override
        public Optional<ActionDefinition> find(ActionRef action) {
            return registered.contains(action) ? Optional.of(new ActionDefinition(action)) : Optional.empty();
        }

        @Override
        public boolean contains(ActionRef action) {
            return registered.contains(action);
        }
    }

    /**
     * Minimal {@link RestOperationDescriptor} for the action-only route under test. The contributors
     * exercised here read only {@code operationId}/{@code securityPolicy}/{@code requiredAction} from
     * the context and {@code route().addHandler(...)}; none reads the descriptor's parameter or
     * annotation accessors, so empty collections suffice.
     *
     * @param operationId    the operationId
     * @param httpMethod     the HTTP method
     * @param routeTemplate  the route template
     * @param securityPolicy the resolved security policy
     */
    record ActionOnlyDescriptor(
            String operationId, String httpMethod, String routeTemplate, SecurityPolicy securityPolicy)
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
            return List.of();
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
     * Test-only {@link RouteRegistration} adapter wrapping a Vert.x OpenAPI {@link OpenAPIRoute}.
     * Mirrors the production {@code OpenApiRouteRegistration} (which lives in rest-jaxrs, not on this
     * module's classpath) so the migrated contributor chain can target the neutral contract while
     * handlers are still added to the underlying {@link OpenAPIRoute}.
     */
    static final class OpenApiRouteRegistrationAdapter implements RouteRegistration {

        private final OpenAPIRoute route;
        private final RestOperationDescriptor operation;

        OpenApiRouteRegistrationAdapter(OpenAPIRoute route, RestOperationDescriptor operation) {
            this.route = route;
            this.operation = operation;
        }

        @Override
        public RouteRegistration addHandler(Handler<RoutingContext> handler) {
            route.addHandler(handler);
            return this;
        }

        @Override
        public RestOperationDescriptor operation() {
            return operation;
        }
    }
}
