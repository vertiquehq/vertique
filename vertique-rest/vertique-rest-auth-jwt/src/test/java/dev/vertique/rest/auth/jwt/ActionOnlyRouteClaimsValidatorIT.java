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
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.security.ActionGateAuthenticationContributor;
import dev.vertique.rest.security.AuthorizationContributor;
import dev.vertique.rest.security.DefaultCredentialRejectionReporter;
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
 * End-to-end integration test proving that a custom {@link JwtClaimsValidator} runs on an
 * <em>action-only</em> REST route ({@link SecurityPolicy.None} carrying a {@code @RequiresAction}
 * gate, declaring no OpenAPI security requirement) — closing the Phase-3 round-3 ordering bug where
 * the validator was silently bypassed on such routes.
 *
 * <h3>The bug this guards against</h3>
 * The {@link JwtClaimsValidatorContributor} runs at priority {@code 50} and skips when
 * {@code ctx.user() == null}. On a <em>normal</em> authenticated route Vert.x's OpenAPI security
 * handler authenticates the caller <em>before</em> any contributor runs, so the user is present when
 * the validator fires. But an action-only route declares no OpenAPI security requirement, so the
 * caller is authenticated by the contributor-installed {@link ActionGateAuthenticationContributor}.
 * When that contributor ran <em>after</em> the priority-50 validator, the chain was
 * {@code [50] validate (skips — no user) → auth (sets user) → identity → authorize}: a token a
 * custom validator would reject (tenant binding, token version/revocation, a required custom claim)
 * was let through on action-only routes while rejected on every normal authenticated route.
 *
 * <p>The fix lowers {@link ActionGateAuthenticationContributor} below the validator's priority so the
 * action-only chain becomes {@code auth → validate(50) → identity(80) → authorize(100)}, matching the
 * normal-route ordering: authentication → claims-validation → identity-resolution → authorization.
 *
 * <h3>How the chain is wired</h3>
 * The four security contributors are sorted with {@link OrderedExtension#comparator()} — exactly as
 * {@code JaxRsRouterMount} sorts them at runtime — and invoked in that order over the real
 * {@link OpenAPIRoute}, followed by a terminal 200-OK handler. Sorting (not hard-coded invocation
 * order) is what makes this test sensitive to the priority value under test: with the pre-fix
 * priority the validator sorts ahead of authentication and the bug reproduces; with the fixed
 * priority authentication sorts first and the validator runs against a populated user.
 *
 * <h3>Scenarios</h3>
 * <ul>
 *   <li><b>validator rejects</b> — a valid bearer token the custom {@link JwtClaimsValidator}
 *       rejects: the validator must run (after authentication) and fail the request with
 *       {@code 401}. With the bug it is bypassed and the request reaches the action gate (which, for
 *       an actor holding {@code editor}, would permit → {@code 200}).</li>
 *   <li><b>validator accepts</b> — a valid bearer token the validator accepts: the validator passes
 *       and the request reaches the action gate, which permits the {@code editor} actor
 *       ({@code 200}).</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ActionOnlyRouteClaimsValidatorIT {

    private static final ActionRef CONTENT_READ = ActionRef.parse("cms.content.read");

    private static int port;
    private static HttpServer server;
    private static HttpClient client;

    /**
     * Builds and starts the shared HTTP server with the action-only {@code /content} route wired
     * through the real, priority-sorted security contributor chain (including the JWT claims
     * validator). One {@link HttpClient} is shared across all tests.
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
                // CredentialRejectionReporter requires a CorrelationContext; return the
                // unbound sentinel so events are emitted without a real request-scoped ID.
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

        SecurityPolicyEnforcer policyEnforcer = new SecurityPolicyEnforcer(
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                emitter,
                contextHolder,
                securityRuntime,
                Optional.of(new EditorActionAuthorizer()));

        // The custom claims validator rejects any token carrying a "reject" claim set to true. This
        // models a real custom rejection (tenant mismatch, revoked token version, missing required
        // claim) that the standard signature/expiry checks do not catch.
        JwtClaimsValidator claimsValidator = claims -> {
            if (Boolean.TRUE.equals(claims.get("reject"))) {
                throw new SecurityException("custom claim validation rejected the token");
            }
        };
        DefaultCredentialRejectionReporter rejectionReporter =
                new DefaultCredentialRejectionReporter(contextHolder, emitter);
        JwtValidationConfig validationConfig = JwtValidationConfig.builder().build();

        // The four contributors that decorate an action-only route, registered in arbitrary order and
        // sorted by the canonical comparator — exactly as JaxRsRouterMount does at runtime. Sorting
        // (not the order they appear here) is what determines the handler-chain order.
        List<OperationHandlerContributor> contributors = List.of(
                new AuthorizationContributor(policyEnforcer),
                new JwtClaimsValidatorContributor(claimsValidator, rejectionReporter, validationConfig),
                new IdentityResolutionContributor(identityMiddleware),
                new ActionGateAuthenticationContributor(Set.of(new StubBearerAuthHandler())));
        List<OperationHandlerContributor> sortedContributors =
                contributors.stream().sorted(OrderedExtension.comparator()).toList();

        ActionRegistry actionRegistry = new StubActionRegistry(Set.of(CONTENT_READ));

        OpenAPIContract.from(vertx, "action-only-claims-validator-test-openapi.json")
                .compose(contract -> {
                    RouterBuilder routerBuilder =
                            RouterBuilder.create(vertx, contract, RequestExtractor.withBodyHandler());

                    OpenAPIRoute route = routerBuilder.getRoute("getContent");
                    RestOperationDescriptor descriptor =
                            new ActionOnlyDescriptor("getContent", "GET", "/content", new SecurityPolicy.None());
                    RouteRegistration routeReg = new OpenApiRouteRegistrationAdapter(route, descriptor);
                    OperationRegistrationContext registrationContext = new OperationRegistrationContext(
                            "getContent", new SecurityPolicy.None(), Optional.of(CONTENT_READ), descriptor, routeReg);

                    sortedContributors.forEach(c -> c.contribute(registrationContext));
                    route.addHandler(rc -> rc.response().setStatusCode(200).end("content"));

                    Router apiRouter = routerBuilder.createRouter();
                    Router root = Router.router(vertx);
                    root.route("/*").handler(new RequestContextLifecycle());
                    root.route("/*").subRouter(apiRouter);

                    assertEquals(true, actionRegistry.contains(CONTENT_READ));

                    return vertx.createHttpServer().requestHandler(root).listen(0, "127.0.0.1");
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
     * A valid bearer token whose actor holds the {@code editor} role but which the custom
     * {@link JwtClaimsValidator} rejects must be denied with {@code 401}: the validator must run on
     * an action-only route (after authentication) and reject the token. With the ordering bug the
     * validator is bypassed and the request reaches the action gate, which permits the {@code editor}
     * actor — so a pre-fix run returns {@code 200} and fails this assertion (RED).
     *
     * @param ctx the test context
     */
    @Test
    @DisplayName("validator-rejected token on action-only route: validator runs and rejects (401)")
    void rejectedTokenDeniedByValidator(VertxTestContext ctx) {
        get("/content", "alice|editor|reject")
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertEquals(
                            401,
                            status,
                            "the custom JwtClaimsValidator must run on an action-only route and reject the token; a "
                                    + "200 means the validator was bypassed (the ordering bug) and the action gate ran "
                                    + "instead");
                    ctx.completeNow();
                })));
    }

    /**
     * A valid bearer token whose actor holds the {@code editor} role and which the custom
     * {@link JwtClaimsValidator} accepts passes validation and reaches the action gate, which permits
     * the {@code editor} actor ({@code 200}). This rules out the validator failing every token and
     * proves it discriminates on the claims.
     *
     * @param ctx the test context
     */
    @Test
    @DisplayName("validator-accepted token on action-only route: validator passes, action gate permits (200)")
    void acceptedTokenReachesActionGate(VertxTestContext ctx) {
        get("/content", "alice|editor")
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertEquals(
                            200,
                            status,
                            "a token the validator accepts must pass validation and reach the action gate, which "
                                    + "permits the editor actor");
                    ctx.completeNow();
                })));
    }

    // --- Helpers ---

    /**
     * Issues a {@code GET} to the given path with an {@code Authorization: Bearer} header and resolves
     * with the HTTP status code.
     *
     * @param path  the request path
     * @param token the bearer token value (format {@code <sub>|<csv-roles>[|reject]}); never
     *              {@code null} in this test
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
     * Stub {@link RouteAuthHandler} interpreting an
     * {@code Authorization: Bearer <sub>|<csv-roles>[|reject]} header: on success it sets the Vert.x
     * {@link User} (with {@code roles} for the claim mapper and a {@code reject} flag for the claims
     * validator) and appends {@link AuthenticationEvidence}. A malformed header fails the request
     * with 401.
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
            String rolesStr = parts[1];
            boolean reject = parts.length >= 3 && "reject".equals(parts[2]);
            List<String> roles = rolesStr.isEmpty() ? List.of() : List.of(rolesStr.split(","));
            JsonObject principal =
                    new JsonObject().put("sub", sub).put("roles", roles).put("reject", reject);
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
     * Minimal {@link RestOperationDescriptor} for the action-only route under test. The four
     * contributors exercised here read only {@code operationId}/{@code securityPolicy}/
     * {@code requiredAction} from the context and {@code route().addHandler(...)}; none reads the
     * descriptor's parameter or annotation accessors, so empty collections suffice.
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
