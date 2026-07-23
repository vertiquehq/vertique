// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.security.DefaultSecurityClaimMapper;
import dev.vertique.rest.security.HolderBackedSecurityRuntime;
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
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.security.verification.CustomVerificationSource;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.impl.UserContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test proving that a WebSocket endpoint carrying a class-level {@code @RequiresAction}
 * is enforced once at upgrade by the composed {@link SecurityPolicyEnforcer} action gate (FR-AUTHZ-048,
 * ADR-0115).
 *
 * <p>The endpoint {@code /ws/content} declares {@code @RolesAllowed("editor")} AND
 * {@code @RequiresAction("cms.content.read")}. The in-test {@link Authorizer} permits
 * {@code cms.content.read} for any actor holding the {@code editor} role <em>except</em> the actor
 * {@code carol}, who is denied at the action gate regardless of role. Three scenarios:
 * <ul>
 *   <li><b>permit</b> — a token with the {@code editor} role passes both the role gate and the action
 *       gate, so the upgrade succeeds and {@code @OnOpen} runs;</li>
 *   <li><b>role-gate deny</b> — a token with the {@code admin} role fails the {@code @RolesAllowed}
 *       role gate (admin is not editor), is rejected at upgrade, and emits exactly one
 *       {@link AuthorizationDecisionEvent} with reason {@link AuthzReasonCodes#ROLE_MISSING} whose
 *       action gate was <em>not</em> evaluated;</li>
 *   <li><b>action-gate deny (isolation)</b> — a token with the {@code editor} role <em>passes</em> the
 *       role gate, so the {@code @RequiresAction} action gate is the sole reason for denial: the
 *       in-test authorizer denies actor {@code carol}, the upgrade is rejected, and exactly one
 *       {@link AuthorizationDecisionEvent} with reason {@link AuthzReasonCodes#ACTION_NOT_ALLOWED}
 *       (the action gate's code, role gate satisfied) is emitted. This proves {@code @RequiresAction}
 *       independently denies, not the role gate.</li>
 * </ul>
 *
 * <p>The {@link Authorizer} is threaded into the real {@link SecurityPolicyEnforcer} (the slice-13
 * wiring under test) so the action gate is actually evaluated at upgrade.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class WebSocketRequiresActionIT {

    private static final ActionRef CONTENT_READ = ActionRef.parse("cms.content.read");

    private static int port;
    private static HttpServer server;
    private static WebSocketClient wsClient;

    /** Captures every emitted {@link AuthorizationDecisionEvent} for assertion. */
    private static final List<AuthorizationDecisionEvent> events = new CopyOnWriteArrayList<>();

    /**
     * Builds and starts the shared HTTP server with the {@code /ws/content} endpoint wired through
     * the real {@link SecurityPolicyEnforcer} action gate. One {@link WebSocketClient} is shared
     * across all test methods.
     *
     * @param vertx the Vert.x instance injected by {@link VertxExtension}
     * @param ctx   the test context used for async startup assertion
     */
    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        wsClient = vertx.createWebSocketClient();

        HolderBackedSecurityRuntime securityRuntime = new HolderBackedSecurityRuntime((sc, secure) -> null);

        // Capturing observer so the test can assert exactly-one-event on the deny path.
        SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of(new CapturingObserver()));

        CorrelationContextFactory correlationFactory = new CorrelationContextFactory(Optional.empty());
        CorrelationContext stubCorrelation = correlationFactory.create(
                new CorrelationIdentifier("test-req", "test"), new CorrelationIdentifier("test-cor", "test"));
        ContextHolder contextHolder = new ContextHolder() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> Optional<T> current(Class<T> type) {
                if (type == CorrelationContext.class) {
                    return (Optional<T>) Optional.of(stubCorrelation);
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

        // SecurityPolicyEnforcer wired with the real action Authorizer (slice-13 wiring under test).
        // A RoleCheckDecisionPoint handles the @RolesAllowed gate; the EditorActionAuthorizer handles
        // the @RequiresAction gate. Both must pass for the upgrade to succeed.
        SecurityPolicyEnforcer policyEnforcer = new SecurityPolicyEnforcer(
                Optional.of(new RoleCheckDecisionPoint()),
                Optional.empty(),
                Set.of(),
                emitter,
                contextHolder,
                securityRuntime,
                Optional.of(new EditorActionAuthorizer()));

        ActionRegistry actionRegistry = new StubActionRegistry(Set.of(CONTENT_READ));

        RouteAuthHandler stubAuth = new StubBearerAuthHandler();

        WebSocketEndpointRegistrar registrar = new WebSocketEndpointRegistrar(
                new WebSocketMessageCodec(),
                policyEnforcer,
                identityMiddleware,
                securityRuntime,
                Set.of(stubAuth),
                null,
                null,
                null,
                actionRegistry,
                // Authorizer present: the complete enforceable graph for a @RequiresAction endpoint.
                new EditorActionAuthorizer());

        Router router = Router.router(vertx);
        router.route("/*").handler(new RequestContextLifecycle());

        registrar.registerAll(Set.of(new ContentEndpoint()), router);

        vertx.createHttpServer().requestHandler(router).listen(0).onComplete(ctx.succeeding(s -> {
            server = s;
            port = s.actualPort();
            ctx.completeNow();
        }));
    }

    /**
     * Closes the shared HTTP server and {@link WebSocketClient}.
     *
     * @param ctx the test context used for async teardown assertion
     */
    @AfterAll
    static void tearDown(VertxTestContext ctx) {
        Future<?> s = server != null ? server.close() : Future.succeededFuture();
        Future<?> c = wsClient != null ? wsClient.close() : Future.succeededFuture();
        Future.join(s, c).onComplete(ar -> ctx.completeNow());
    }

    /**
     * Opens a WebSocket connection to the given path with an {@code Authorization: Bearer} header
     * carrying the supplied token.
     *
     * @param path  the request path
     * @param token the bearer token value (format: {@code <sub>|<csv-roles>})
     * @return a {@link Future} that succeeds with the open {@link WebSocket} or fails on handshake
     *         rejection
     */
    private Future<WebSocket> connectWithToken(String path, String token) {
        return wsClient.connect(new WebSocketConnectOptions()
                .setHost("localhost")
                .setPort(port)
                .setURI(path)
                .addHeader("Authorization", "Bearer " + token));
    }

    // --- Tests ---

    /**
     * Verifies that a client presenting the {@code editor} role passes both the {@code @RolesAllowed}
     * gate and the {@code @RequiresAction} action gate, so the upgrade succeeds and {@code @OnOpen}
     * captures a non-anonymous {@link SecurityContext}.
     *
     * @param ctx the test context
     */
    @Test
    @DisplayName("upgrade with editor role permitted: role gate + action gate both pass")
    void upgradeWithValidActionPermitted(VertxTestContext ctx) {
        ContentEndpoint.reset();

        connectWithToken("/ws/content", "alice|editor").onComplete(ctx.succeeding(ws -> {
            ctx.verify(() -> {
                SecurityContext sc = ContentEndpoint.onOpenSc.get();
                assertNotNull(sc, "@OnOpen must observe a SecurityContext after a permitted upgrade");
                assertEquals("alice", sc.identity().actor().id(), "actor id must be 'alice'");
                ws.close();
                ctx.completeNow();
            });
        }));
    }

    /**
     * Verifies that a client presenting the {@code admin} role is rejected at the {@code @RolesAllowed}
     * role gate (admin is not editor): the handshake fails and exactly one
     * {@link AuthorizationDecisionEvent} is emitted whose top-level reason is the role-gate failure
     * ({@link AuthzReasonCodes#ROLE_MISSING}) and whose action gate was <em>not</em> evaluated
     * ({@code actionEvaluated=false}). This pins the role-gate deny path; the action-isolation case is
     * proven separately by {@link #upgradeWithActionGateDenied(Vertx, VertxTestContext)}.
     *
     * @param vertx the Vert.x instance used for the post-failure timer
     * @param ctx   the test context
     */
    @Test
    @DisplayName("upgrade without editor role denied: role gate rejects, action not evaluated, one event emitted")
    void upgradeWithoutRoleDenied(Vertx vertx, VertxTestContext ctx) {
        ContentEndpoint.reset();
        events.clear();

        // "bob|admin": the admin role does not satisfy the @RolesAllowed("editor") gate, so the role
        // gate denies and the action gate is never evaluated (fail-fast).
        connectWithToken("/ws/content", "bob|admin").onComplete(ctx.failing(cause -> {
            assertNotNull(cause, "handshake must fail when the role gate denies");
            // Give the (synchronous) emission a beat to be observed, then assert one deny event.
            vertx.setTimer(
                    200,
                    id -> ctx.verify(() -> {
                        assertEquals(1, events.size(), "exactly one AuthorizationDecisionEvent must be emitted");
                        AuthorizationDecision decision = events.get(0).decision();
                        assertFalse(decision.permitted(), "the emitted decision must be a deny");
                        assertEquals(
                                AuthzReasonCodes.ROLE_MISSING,
                                decision.reasonCode(),
                                "the deny reason must be the role-gate failure code");
                        assertEquals(
                                Boolean.FALSE,
                                decision.safeAttributes().get("actionEvaluated"),
                                "the action gate must NOT be evaluated when the role gate denies");
                        ctx.completeNow();
                    }));
        }));
    }

    /**
     * Isolates the {@code @RequiresAction} action gate: the actor {@code carol} holds the
     * {@code editor} role, so the {@code @RolesAllowed("editor")} role gate is <em>satisfied</em> and
     * the action gate becomes the sole reason for denial. The in-test {@link EditorActionAuthorizer}
     * denies actor {@code carol} at the action gate independent of role.
     *
     * <p>Asserts the upgrade is denied, exactly one {@link AuthorizationDecisionEvent} is emitted with
     * the action gate's reason ({@link AuthzReasonCodes#ACTION_NOT_ALLOWED}), the role gate is recorded
     * as satisfied while the action gate denied ({@code rolesSatisfied=true},
     * {@code actionEvaluated=true}, {@code actionSatisfied=false}), and {@code @OnOpen} did not run.
     * This proves {@code @RequiresAction} independently denies — the role gate did not cause the
     * rejection.
     *
     * @param vertx the Vert.x instance used for the post-failure timer
     * @param ctx   the test context
     */
    @Test
    @DisplayName("action gate isolation: role passes, @RequiresAction denies — one ACTION_NOT_ALLOWED deny event")
    void upgradeWithActionGateDenied(Vertx vertx, VertxTestContext ctx) {
        ContentEndpoint.reset();
        events.clear();

        // "carol|editor": the editor role satisfies @RolesAllowed("editor"), so the role gate passes
        // and the action gate is the only gate that can deny. EditorActionAuthorizer denies carol.
        connectWithToken("/ws/content", "carol|editor").onComplete(ctx.failing(cause -> {
            assertNotNull(cause, "handshake must fail when the action gate denies");
            vertx.setTimer(
                    200,
                    id -> ctx.verify(() -> {
                        assertEquals(1, events.size(), "exactly one AuthorizationDecisionEvent must be emitted");
                        AuthorizationDecision decision = events.get(0).decision();
                        assertFalse(decision.permitted(), "the emitted decision must be a deny");
                        assertEquals(
                                AuthzReasonCodes.ACTION_NOT_ALLOWED,
                                decision.reasonCode(),
                                "the deny reason must be the action-gate code (proves @RequiresAction denied)");
                        assertEquals(
                                Boolean.TRUE,
                                decision.safeAttributes().get("rolesSatisfied"),
                                "the role gate must be satisfied (admin≠editor is NOT the reason)");
                        assertEquals(
                                Boolean.TRUE,
                                decision.safeAttributes().get("actionEvaluated"),
                                "the action gate must have been evaluated");
                        assertEquals(
                                Boolean.FALSE,
                                decision.safeAttributes().get("actionSatisfied"),
                                "the action gate must have denied");
                        assertNull(
                                ContentEndpoint.onOpenSc.get(),
                                "@OnOpen must NOT run when the action gate denies the upgrade");
                        ctx.completeNow();
                    }));
        }));
    }

    // --- Endpoint ---

    /**
     * WebSocket endpoint requiring both the {@code editor} role and the {@code cms.content.read}
     * action. Captures the {@link SecurityContext} visible in {@link OnOpen}.
     */
    @WebSocketEndpoint("/ws/content")
    @jakarta.annotation.security.RolesAllowed("editor")
    @dev.vertique.security.authz.RequiresAction("cms.content.read")
    static class ContentEndpoint {

        /** {@link SecurityContext} captured in {@link OnOpen}. */
        static final AtomicReference<SecurityContext> onOpenSc = new AtomicReference<>();

        /** Resets captured state before each test run. */
        static void reset() {
            onOpenSc.set(null);
        }

        /**
         * Captures the resolved {@link SecurityContext} at connection open time.
         *
         * @param session the WebSocket session
         * @param sc      the resolved security context
         */
        @OnOpen
        public void onOpen(WebSocketSession session, SecurityContext sc) {
            onOpenSc.set(sc);
        }
    }

    // --- Test doubles ---

    /**
     * Actor id explicitly denied at the action gate regardless of role, used to isolate the
     * {@code @RequiresAction} gate from the {@code @RolesAllowed} role gate.
     */
    private static final String ACTION_DENIED_ACTOR = "carol";

    /**
     * In-test {@link Authorizer} that evaluates the {@link #CONTENT_READ} action gate
     * <em>independently</em> of the role gate, so a role-passes/action-denies scenario is possible:
     * <ul>
     *   <li>permits {@link #CONTENT_READ} for any actor <em>except</em> {@link #ACTION_DENIED_ACTOR},
     *       who is denied with {@link AuthzReasonCodes#ACTION_NOT_ALLOWED};</li>
     *   <li>denies any other action with {@link AuthzReasonCodes#ACTION_NOT_ALLOWED};</li>
     *   <li>fails closed ({@link AuthzReasonCodes#INTERNAL_AUTHZ_ERROR}) on a {@code null} context.</li>
     * </ul>
     *
     * <p>The decision does <strong>not</strong> consult the actor's role: role enforcement is the
     * {@code @RolesAllowed} gate's responsibility (the {@link RoleCheckDecisionPoint}), and decoupling
     * the two is what lets {@code upgradeWithActionGateDenied} prove the action gate denies on its own.
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
            String actorId = ctx.identity().actor().id();
            boolean permit = CONTENT_READ.equals(action) && !ACTION_DENIED_ACTOR.equals(actorId);
            return Future.succeededFuture(
                    permit
                            ? AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)
                            : AuthorizationDecision.deny(AuthzReasonCodes.ACTION_NOT_ALLOWED));
        }
    }

    /**
     * In-test role/scope decision point evaluating {@link AuthorityKind#ROLE} claims, mirroring the
     * shared pattern used by the WebSocket security pipeline IT.
     */
    static class RoleCheckDecisionPoint implements dev.vertique.rest.security.AuthorizationDecisionPoint {

        @Override
        @SuppressWarnings("unchecked")
        public Future<AuthorizationDecision> decide(AuthorizationRequest request) {
            Map<String, Object> ctx = request.context();
            List<String> requiredRoles =
                    ctx.containsKey("requiredRoles") ? (List<String>) ctx.get("requiredRoles") : List.of();
            if (requiredRoles.isEmpty()) {
                return Future.succeededFuture(AuthorizationDecision.permit("PERMITTED"));
            }
            Set<String> grantedRoles = request.securityContext().authorization().valuesOf(AuthorityKind.ROLE);
            boolean hasRole = requiredRoles.stream().anyMatch(grantedRoles::contains);
            return Future.succeededFuture(
                    hasRole ? AuthorizationDecision.permit("PERMITTED") : AuthorizationDecision.deny("ROLE_MISSING"));
        }
    }

    /**
     * Stub {@link RouteAuthHandler} interpreting a {@code Authorization: Bearer <sub>|<csv-roles>}
     * header, identical in shape to the WebSocket security pipeline IT's handler.
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
     * {@link SecurityEventObserver} that records every {@link AuthorizationDecisionEvent} into
     * {@link #events} so the deny scenario can assert exactly-one emission.
     */
    static class CapturingObserver implements SecurityEventObserver {

        @Override
        public Future<Void> onAuthorizationDecided(AuthorizationDecisionEvent event) {
            events.add(event);
            return Future.succeededFuture();
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
}
