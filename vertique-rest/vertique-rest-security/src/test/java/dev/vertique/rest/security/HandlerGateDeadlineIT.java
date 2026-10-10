// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.resilience.Resilience;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves, over a real {@link Router} and a real HTTP request, that an authorization gate that never
 * settles is answered rather than left hanging: the request gets {@code 503} once the gate deadline
 * elapses, the protected handler never runs, exactly one decision event is emitted, and a gate that
 * completes after the deadline changes nothing.
 *
 * <p>The unit tests in {@code SecurityPolicyEnforcerTest} drive the same handlers with a mocked
 * {@code RoutingContext}; this class adds the request's own Vert.x context and the response path.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class HandlerGateDeadlineIT {

    private static final long GATE_DEADLINE_MS = 150L;
    private static final long CLIENT_WAIT_MS = 5_000L;

    /** A running server plus the counters a scenario asserts on. */
    private static final class Fixture implements AutoCloseable {
        final List<AuthorizationDecisionEvent> events = new CopyOnWriteArrayList<>();
        final AtomicInteger protectedHandlerRuns = new AtomicInteger();
        final WebClient client;
        final HttpServer server;

        Fixture(Vertx vertx, AuthorizationPolicyGates gates) throws Exception {
            SecurityContext caller = alice();
            SecurityRuntime runtime = new SecurityRuntime() {
                @Override
                public SecurityContext current() {
                    return caller;
                }

                @Override
                public ContextHolder.Scope bindCurrent(SecurityContext context) {
                    return () -> {};
                }

                @Override
                public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
                    throw new UnsupportedOperationException();
                }
            };
            SecurityEventObserver observer = new SecurityEventObserver() {
                @Override
                public Future<Void> onAuthorizationDecided(AuthorizationDecisionEvent event) {
                    events.add(event);
                    return Future.succeededFuture();
                }
            };
            SecurityPolicyEnforcer enforcer = new SecurityPolicyEnforcer(
                    Optional.of(gates.decisionPoint()),
                    Optional.empty(),
                    Set.of(),
                    new SecurityEventEmitter(Set.of(observer)),
                    new ContextHolder() {
                        @Override
                        public <T> Optional<T> current(Class<T> type) {
                            return Optional.empty();
                        }

                        @Override
                        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
                            return () -> {};
                        }
                    },
                    runtime,
                    Optional.ofNullable(gates.authorizer()),
                    Optional.of(new AuthorizationGateConfig(GATE_DEADLINE_MS)),
                    Resilience.create(vertx));

            Router router = Router.router(vertx);
            router.get("/protected")
                    .handler(enforcer.createHandler(
                            new SecurityPolicy.Constrained(List.of("ops"), List.of(), false),
                            Optional.ofNullable(gates.action())))
                    .handler(rc -> {
                        protectedHandlerRuns.incrementAndGet();
                        rc.response().end("protected");
                    });
            // Mirrors the production exception mapper for the case this class proves: a failure object
            // that reaches the failure pipeline is rendered with its own message (an
            // IllegalArgumentException becomes a 400 carrying it); a status-only failure keeps its status.
            router.route().failureHandler(rc -> {
                if (rc.failure() instanceof IllegalArgumentException illegal) {
                    rc.response().setStatusCode(400).end(String.valueOf(illegal.getMessage()));
                    return;
                }
                if (rc.failure() instanceof dev.vertique.core.exception.UnavailableException unavailable) {
                    rc.response().setStatusCode(503).end(String.valueOf(unavailable.getMessage()));
                    return;
                }
                rc.response()
                        .setStatusCode(rc.statusCode() > 0 ? rc.statusCode() : 500)
                        .end();
            });
            server = vertx.createHttpServer()
                    .requestHandler(router)
                    .listen(0, "127.0.0.1")
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(CLIENT_WAIT_MS, TimeUnit.MILLISECONDS);
            client = WebClient.create(vertx);
        }

        int get() throws Exception {
            return response().statusCode();
        }

        io.vertx.ext.web.client.HttpResponse<io.vertx.core.buffer.Buffer> response() throws Exception {
            return client.get(server.actualPort(), "127.0.0.1", "/protected")
                    .send()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(CLIENT_WAIT_MS, TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() throws Exception {
            client.close();
            server.close().toCompletionStage().toCompletableFuture().get(CLIENT_WAIT_MS, TimeUnit.MILLISECONDS);
        }
    }

    private record AuthorizationPolicyGates(
            dev.vertique.rest.security.AuthorizationDecisionPoint decisionPoint,
            Authorizer authorizer,
            ActionRef action) {}

    private static SecurityContext alice() {
        Set<AuthorityClaim> claims = Set.of(new AuthorityClaim(AuthorityKind.ROLE, "ops", "", "", "test", Map.of()));
        return new AuthenticatedSecurityContext(
                SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "alice", Map.of())),
                new AuthenticationState(
                        DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of()),
                new AuthorizationClaims(claims, Map.of()),
                Optional.empty());
    }

    private static final AuthorizationDecision PERMIT = AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED);

    @Test
    @DisplayName("a decision point that never answers yields 503 once the deadline elapses; the handler never runs")
    void hungDecisionPointYieldsForbidden(Vertx vertx) throws Exception {
        Promise<AuthorizationDecision> hung = Promise.promise();
        try (Fixture fixture = new Fixture(vertx, new AuthorizationPolicyGates(request -> hung.future(), null, null))) {
            assertEquals(503, fixture.get());

            assertEquals(0, fixture.protectedHandlerRuns.get(), "the protected handler must never run");
            assertEquals(1, fixture.events.size(), "exactly one decision event");
            assertEquals(
                    AuthzReasonCodes.INTERNAL_AUTHZ_ERROR,
                    fixture.events.get(0).decision().reasonCode());
        }
    }

    @Test
    @DisplayName("an action authorizer that never answers yields 503 once the deadline elapses")
    void hungAuthorizerYieldsForbidden(Vertx vertx) throws Exception {
        Authorizer hungAuthorizer = new Authorizer() {
            @Override
            public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
                return Promise.<AuthorizationDecision>promise().future();
            }

            @Override
            public Future<AuthorizationDecision> authorize(
                    SecurityContext ctx, ActionRef action, ResourceRef resource) {
                throw new UnsupportedOperationException();
            }
        };
        try (Fixture fixture = new Fixture(
                vertx,
                new AuthorizationPolicyGates(
                        request -> Future.succeededFuture(PERMIT),
                        hungAuthorizer,
                        ActionRef.parse("orders.order.read")))) {
            assertEquals(503, fixture.get());

            assertEquals(0, fixture.protectedHandlerRuns.get());
            assertEquals(1, fixture.events.size());
        }
    }

    @Test
    @DisplayName(
            "a gate that completes after the deadline changes nothing: still 503, handler still not run, one event")
    void lateCompletionAfterTheDeadlineChangesNothing(Vertx vertx) throws Exception {
        Promise<AuthorizationDecision> late = Promise.promise();
        try (Fixture fixture = new Fixture(vertx, new AuthorizationPolicyGates(request -> late.future(), null, null))) {
            assertEquals(503, fixture.get());

            late.complete(PERMIT);
            Thread.sleep(300L);

            assertEquals(0, fixture.protectedHandlerRuns.get(), "a late permit must not run the handler");
            assertEquals(1, fixture.events.size(), "a late completion must not emit a second event");
        }
    }

    @Test
    @DisplayName(
            "a gate that fails with an IllegalArgumentException is 503 unavailable; its message is not in the response")
    void gateFailureMessageNeverReachesTheCaller(Vertx vertx) throws Exception {
        String secret = "pdp host db-7 refused the connection";
        try (Fixture fixture = new Fixture(
                vertx,
                new AuthorizationPolicyGates(
                        request -> Future.failedFuture(new IllegalArgumentException(secret)), null, null))) {
            var response = fixture.response();

            assertEquals(503, response.statusCode());
            assertEquals("1", response.getHeader("Retry-After"), "an unavailable answer tells callers to back off");
            assertFalse(
                    String.valueOf(response.bodyAsString()).contains(secret),
                    "a policy client's failure message must not be rendered to the caller");
            assertEquals(
                    "Authorization is temporarily unavailable",
                    response.bodyAsString(),
                    "the response carries only the generic, client-safe detail");
            assertEquals(0, fixture.protectedHandlerRuns.get());
            assertEquals(1, fixture.events.size());
        }
    }

    @Test
    @DisplayName("a gate that answers in time is untouched: 200 and the handler runs once")
    void gateThatAnswersInTimeIsUntouched(Vertx vertx) throws Exception {
        try (Fixture fixture = new Fixture(
                vertx, new AuthorizationPolicyGates(request -> Future.succeededFuture(PERMIT), null, null))) {
            assertEquals(200, fixture.get());

            assertEquals(1, fixture.protectedHandlerRuns.get());
            assertTrue(fixture.events.stream().allMatch(e -> e.decision().permitted()));
            assertFalse(fixture.events.isEmpty());
        }
    }
}
