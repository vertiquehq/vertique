// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.resilience.Resilience;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import dev.vertique.rest.security.ActionGateAuthenticationContributor;
import dev.vertique.rest.security.AuthorizationContributor;
import dev.vertique.rest.security.HolderBackedSecurityRuntime;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves, through a real {@link JaxRsRouterMount}, where authentication sits relative to the request
 * gates for an <em>action-only</em> route: one whose only security policy is {@code @RequiresAction},
 * so it declares no security scheme.
 *
 * <p>Such a route is authenticated by an authentication-stage contributor
 * ({@link ActionGateAuthenticationContributor}). The contributor must run right after the (absent)
 * scheme handlers and ahead of the {@code @Consumes} check and the validation gate, so an
 * unauthenticated caller gets {@code 401} whatever the request carries, while an authenticated caller
 * with a malformed body or the wrong content type is still answered {@code 400} or {@code 415}.
 * Authorization stays after validation.
 *
 * <p>The validation gate is a recording strategy that fails {@code 400} for a body containing
 * {@code BAD}, standing in for a schema rejection; a handler count proves it was never reached for an
 * unauthenticated request. A second authentication-stage contributor stands in for a credential
 * claims check, to prove that the stage covers claims checks on a scheme-less route too.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class AuthenticationStageOrderIT {

    private static final ActionRef WRITE = ActionRef.parse("order.item.write");
    private static final String AUTHENTICATED = "Bearer alice";

    private static HttpServer server;
    private static WebClient client;
    private static final List<String> LOG = new CopyOnWriteArrayList<>();
    private static final AtomicInteger INVOKED = new AtomicInteger();
    private static final AtomicInteger GATE_REACHED = new AtomicInteger();

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));

        Authorizer authorizer = new Authorizer() {
            @Override
            public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
                LOG.add("authorization");
                String actor = request.securityContext().identity().actor().id();
                return Future.succeededFuture(
                        "mallory".equals(actor)
                                ? AuthorizationDecision.deny("TEST_DENY")
                                : AuthorizationDecision.permit("TEST_PERMIT"));
            }

            @Override
            public Future<AuthorizationDecision> authorize(
                    SecurityContext securityContext, ActionRef action, ResourceRef resource) {
                throw new AssertionError("the action gate must pass the ambient InvocationOrigin");
            }
        };
        HolderBackedSecurityRuntime securityRuntime = new HolderBackedSecurityRuntime((bound, secure) -> null);
        SecurityPolicyEnforcer enforcer = new SecurityPolicyEnforcer(
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                new SecurityEventEmitter(Set.of()),
                restOrigin(),
                securityRuntime,
                Optional.of(authorizer),
                Resilience.create(vertx));
        RecordingGateStrategy gate = new RecordingGateStrategy();
        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .authEnforcementCapability(Optional.of(AuthEnforcementCapability.INSTANCE))
                .actionRegistry(Optional.of(new TypedPolicyRoutingIT.KnownActions(WRITE)))
                .authorizer(Optional.of(authorizer))
                .validationStrategies(Set.of(gate))
                .jaxRsConfig(JaxRsConfig.builder().validationStrategy(gate.id()).build())
                .operationHandlerContributors(Set.of(
                        new ActionGateAuthenticationContributor(Set.of(new HeaderBearerAuthHandler())),
                        new ClaimsCheckContributor(),
                        new RecordingContributor(),
                        new AuthorizationContributor(enforcer)))
                .build();
        Router api = factory.create("/*", "openapi.json", Set.of(new ItemResource()))
                .createRouter(vertx)
                .result();
        Router root = Router.router(vertx);
        root.route("/*").handler(new RequestContextLifecycle());
        root.route("/*").handler(rc -> bindCaller(rc, securityRuntime));
        root.route("/*").subRouter(api);
        vertx.createHttpServer().requestHandler(root).listen(0, "127.0.0.1").onComplete(ctx.succeeding(s -> {
            server = s;
            ctx.completeNow();
        }));
    }

    @AfterAll
    static void tearDown(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        (server != null ? server.close() : Future.<Void>succeededFuture()).onComplete(ar -> ctx.completeNow());
    }

    @BeforeEach
    void reset() {
        LOG.clear();
        INVOKED.set(0);
        GATE_REACHED.set(0);
    }

    @Test
    @DisplayName("unauthenticated + body the gate would reject: 401, the gate is never reached")
    void unauthenticatedMalformedBodyIsUnauthorized(VertxTestContext ctx) {
        post(null, "application/json", "BAD", null)
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(401, r.statusCode(), r.bodyAsString());
                    assertEquals("Bearer", r.getHeader("WWW-Authenticate"));
                    assertEquals(0, GATE_REACHED.get(), "validation must not run for an unauthenticated caller");
                    assertFalse(String.valueOf(r.bodyAsString()).contains("BAD"), "the 401 must not echo the request");
                    assertEquals(List.of("authentication"), LOG);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("unauthenticated + wrong content type: 401, not 415")
    void unauthenticatedWrongContentTypeIsUnauthorized(VertxTestContext ctx) {
        post(null, "application/xml", "<a/>", null)
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(401, r.statusCode(), r.bodyAsString());
                    assertEquals(0, GATE_REACHED.get());
                    assertEquals(List.of("authentication"), LOG);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("authenticated + body the gate rejects: still 400")
    void authenticatedMalformedBodyIsBadRequest(VertxTestContext ctx) {
        post(AUTHENTICATED, "application/json", "BAD", "alice")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(400, r.statusCode(), r.bodyAsString());
                    assertEquals(1, GATE_REACHED.get());
                    assertEquals(0, INVOKED.get());
                    assertFalse(LOG.contains("authorization"), "authorization runs after validation");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("authenticated + wrong content type: still 415")
    void authenticatedWrongContentTypeIsUnsupported(VertxTestContext ctx) {
        post(AUTHENTICATED, "application/xml", "<a/>", "alice")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(415, r.statusCode(), r.bodyAsString());
                    assertEquals(0, GATE_REACHED.get());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("authenticated but not authorized + malformed body: 400, authorization stays after validation")
    void unauthorizedMalformedBodyIsStillBadRequest(VertxTestContext ctx) {
        post(AUTHENTICATED, "application/json", "BAD", "mallory")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(400, r.statusCode(), r.bodyAsString());
                    assertFalse(LOG.contains("authorization"));
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("authenticated but not authorized + valid body: 403")
    void unauthorizedValidBodyIsForbidden(VertxTestContext ctx) {
        post(AUTHENTICATED, "application/json", "\"ok\"", "mallory")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(403, r.statusCode(), r.bodyAsString());
                    assertEquals(0, INVOKED.get());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("authenticated and authorized: the handlers run in stage order, then the resource method")
    void authorizedRequestRunsStagesInOrder(VertxTestContext ctx) {
        post(AUTHENTICATED, "application/json", "\"ok\"", "alice")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(200, r.statusCode(), r.bodyAsString());
                    assertEquals(1, INVOKED.get());
                    assertEquals(
                            List.of("authentication", "claims", "gate", "contributor", "authorization"),
                            LOG,
                            "authentication stage, then the gates, then the other contributors, then authorization");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("a claims rejection on a scheme-less route is 401 ahead of the gates")
    void claimsRejectionPrecedesTheGates(VertxTestContext ctx) {
        post(AUTHENTICATED, "application/json", "BAD", "alice", "bad-claims")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(401, r.statusCode(), r.bodyAsString());
                    assertEquals(0, GATE_REACHED.get());
                    assertTrue(LOG.contains("claims"));
                    ctx.completeNow();
                })));
    }

    // --- harness ---

    private static Future<HttpResponse<String>> post(
            String authorization, String contentType, String body, String user) {
        return post(authorization, contentType, body, user, null);
    }

    private static Future<HttpResponse<String>> post(
            String authorization, String contentType, String body, String user, String claims) {
        var request = client.post(server.actualPort(), "127.0.0.1", "/items").as(BodyCodec.string());
        if (authorization != null) {
            request.putHeader("Authorization", authorization);
        }
        if (user != null) {
            request.putHeader("X-Test-User", user);
        }
        if (claims != null) {
            request.putHeader("X-Test-Claims", claims);
        }
        request.putHeader("Content-Type", contentType);
        return request.sendBuffer(io.vertx.core.buffer.Buffer.buffer(body));
    }

    private static void bindCaller(RoutingContext rc, HolderBackedSecurityRuntime securityRuntime) {
        String user = rc.request().getHeader("X-Test-User");
        if (user != null) {
            SecurityContext securityContext = SecurityContexts.assemble(
                    SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, user, java.util.Map.of())),
                    new AuthenticationState(
                            DefaultAuthMethod.custom("test"),
                            List.of(),
                            Optional.empty(),
                            Optional.empty(),
                            java.util.Map.of()),
                    new AuthorizationClaims(Set.of(), java.util.Map.of()),
                    Optional.empty());
            RequestContextLifecycle.fromRoutingContext(rc).onClose(securityRuntime.bindCurrent(securityContext));
        }
        rc.next();
    }

    private static ContextHolder restOrigin() {
        return new ContextHolder() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> Optional<T> current(Class<T> type) {
                return type == InvocationOrigin.class
                        ? (Optional<T>) Optional.of(InvocationOrigin.of("rest"))
                        : Optional.empty();
            }

            @Override
            public <T extends ContextValue> Scope bind(Class<T> type, T value) {
                return () -> {};
            }
        };
    }

    /** Action-only resource: no scheme, no role policy, only the action gate. */
    @Path("/items")
    public static class ItemResource {

        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @dev.vertique.security.authz.RequiresAction("order.item.write")
        public String create(String body) {
            INVOKED.incrementAndGet();
            return "created";
        }
    }

    /** Authenticates a {@code Bearer} header, records it, and challenges like a framework 401. */
    private static final class HeaderBearerAuthHandler implements RouteAuthHandler {

        @Override
        public String schemeName() {
            return "bearerAuth";
        }

        @Override
        public Handler<RoutingContext> createHandler() {
            return rc -> {
                LOG.add("authentication");
                String header = rc.request().getHeader("Authorization");
                if (header == null || !header.startsWith("Bearer ")) {
                    rc.response().putHeader("WWW-Authenticate", "Bearer");
                    rc.fail(401);
                    return;
                }
                rc.next();
            };
        }
    }

    /** Stands in for a credential claims check: an authentication-stage contributor after the handler. */
    private static final class ClaimsCheckContributor implements OperationHandlerContributor {

        @Override
        public boolean authenticationStage() {
            return true;
        }

        @Override
        public int priority() {
            return 50;
        }

        @Override
        public void contribute(OperationRegistrationContext context) {
            context.route().addHandler(rc -> {
                LOG.add("claims");
                if (rc.request().getHeader("X-Test-Claims") != null) {
                    rc.fail(401);
                    return;
                }
                rc.next();
            });
        }
    }

    /** A default (non-authentication-stage) contributor: must run after the gates. */
    private static final class RecordingContributor implements OperationHandlerContributor {

        @Override
        public int priority() {
            return 10;
        }

        @Override
        public void contribute(OperationRegistrationContext context) {
            context.route().addHandler(rc -> {
                LOG.add("contributor");
                rc.next();
            });
        }
    }

    /** Validation gate failing 400 for a body containing {@code BAD}. */
    private static final class RecordingGateStrategy implements RequestValidationStrategy {

        @Override
        public String id() {
            return "recording-gate";
        }

        @Override
        public Optional<Handler<RoutingContext>> gateFor(JaxRsOperationDescriptor op, OperationSchemas schemas) {
            return Optional.of(rc -> {
                GATE_REACHED.incrementAndGet();
                LOG.add("gate");
                String body = rc.body() == null ? "" : rc.body().asString();
                if (body != null && body.contains("BAD")) {
                    rc.fail(400);
                    return;
                }
                rc.next();
            });
        }
    }
}
