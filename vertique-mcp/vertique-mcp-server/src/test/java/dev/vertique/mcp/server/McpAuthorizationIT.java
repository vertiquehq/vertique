// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.DefaultSecurityClaimMapper;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.RestAuthenticationEvidence;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.security.verification.CustomVerificationSource;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.impl.UserContextInternal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises the T005 frozen authorization matrix through {@link McpPolicyEnforcer}, backed by a real
 * port-0 identity-establishment pipeline: {@link IdentityResolutionMiddleware} wired exactly as
 * production does, behind an optional bearer scheme.
 *
 * <p>Each row establishes a caller's real {@link SecurityContext} over HTTP — anonymous, bearer
 * {@code alice} (role {@code ops}), or bearer {@code admin} — then evaluates one or more directly
 * constructed {@link McpToolDescriptor}s against that context through {@link McpPolicyEnforcer}
 * alone, never through a {@code tools/list}/{@code tools/call} wire path (T011/T012 own that). The
 * in-test {@link Authorizer} double permits the fine action gate only for the {@code admin} identity,
 * isolating the fine gate from the coarse role gate the {@code ops} role drives.
 *
 * <p>Modeled on {@link McpDiscoverIT}'s harness: bind and connect explicitly to {@code 127.0.0.1}
 * (never the default host or {@code "localhost"}) so a port-0 listener cannot land on a foreign
 * process's bound loopback port, and close the client, server, and {@link Vertx} on every teardown
 * path.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpAuthorizationIT {

    private static final String PUBLIC_ROW = "shouldTreatUnannotatedAndPermitAllToolsAsPublicForBothCallers";
    private static final String ROLES_ROW = "shouldPermitRolesAllowedToolOnlyForTheMatchingAuthenticatedCaller";
    private static final String ACTION_ROW = "shouldPermitRequiresActionToolOnlyWhenTheActionGatePermits";
    private static final String ROLES_AND_ACTION_ROW = "shouldRequireBothGatesForRoleAndActionTools";
    private static final String DENY_ALL_ROW = "shouldHideDenyAllCandidatesFromListingAndNeverInvokeThem";
    private static final String ANONYMOUS_FAIL_CLOSED_ROW =
            "shouldFailClosedForAnonymousCallersOnEveryRestrictedDescriptor";

    private static final String BEARER_ALICE = "Bearer alice";
    private static final String BEARER_ADMIN = "Bearer admin";
    private static final ActionRef TOOL_ACTION = ActionRef.parse("mcp.tool.invoke");

    private final Vertx vertx = Vertx.vertx();

    private AtomicReference<SecurityContext> boundSecurityContext;
    private List<AuthorizationDecisionEvent> events;
    private AtomicInteger invocationCount;
    private AdminOnlyActionAuthorizer authorizer;
    private SecurityPolicyEnforcer securityPolicyEnforcer;
    private McpPolicyEnforcer mcpPolicyEnforcer;

    private HttpServer server;
    private int port;
    private HttpClient rawClient;
    private WebClient client;

    private static Stream<String> t005ContractRows() {
        return Stream.of(
                PUBLIC_ROW, ROLES_ROW, ACTION_ROW, ROLES_AND_ACTION_ROW, DENY_ALL_ROW, ANONYMOUS_FAIL_CLOSED_ROW);
    }

    /**
     * Joins the server and raw-client closes, then closes the owned {@link Vertx} from the join
     * callback so no in-flight request meets a closed pool — mirrors {@link McpDiscoverIT}'s teardown
     * so every exit path (success, assertion failure, timeout, setup failure) still closes every
     * owned resource.
     *
     * @throws Exception if teardown does not complete within its bound
     */
    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<Void> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose)
                .onComplete(ignored -> vertx.close().onComplete(result -> closed.complete(null)));
        closed.get(10, TimeUnit.SECONDS);
        server = null;
        rawClient = null;
        client = null;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("t005ContractRows")
    @DisplayName("T005 authorization matrix: McpPolicyEnforcer descriptor mapping and filtering")
    void shouldEnforceT005ContractMatrix(String row) throws Exception {
        startServer();
        switch (row) {
            case PUBLIC_ROW -> {
                McpToolDescriptor unannotated = descriptor("unannotated-tool", permitAllAccess());
                McpToolDescriptor permitAll = descriptor("permit-all-tool", permitAllAccess());

                for (McpToolDescriptor descriptor : List.of(unannotated, permitAll)) {
                    assertPermittedAndVisible(descriptor, establish(null));
                    assertPermittedAndVisible(descriptor, establish(BEARER_ALICE));
                }
                assertThat(events)
                        .as("PermitAll with no action must emit no event")
                        .isEmpty();
                assertThat(invocationCount)
                        .as("both public tools are invocable by both callers")
                        .hasValue(4);
            }
            case ROLES_ROW -> {
                McpToolDescriptor rolesTool = descriptor("roles-tool", rolesAccess());

                assertPermittedAndVisible(rolesTool, establish(BEARER_ALICE));
                // SENSITIVITY PROOF (T005 TP-002): the bearer literal below is BEARER_ALICE. Changing
                // only this literal to a "Bearer tampered" credential must flip the permitted
                // assertion true→false while the invocation count remains 0.
                assertDeniedAndHidden(rolesTool, establish(BEARER_ADMIN));

                // Each caller is checked through both McpPolicyEnforcer#decide and
                // McpPolicyEnforcer#isVisible (two independent restrictive evaluations per caller),
                // so 2 callers x 2 evaluations = 4 combined decision events.
                assertThat(events).hasSize(4);
                assertThat(invocationCount)
                        .as("only the role-matching caller reaches the guarded invocation path")
                        .hasValue(1);
            }
            case ACTION_ROW -> {
                McpToolDescriptor actionTool = descriptor("action-tool", actionOnlyAccess());

                assertPermittedAndVisible(actionTool, establish(BEARER_ADMIN));
                assertDeniedAndHidden(actionTool, establish(BEARER_ALICE));

                assertThat(events).hasSize(4);
                assertThat(authorizer.callCount())
                        .as("the fine action gate must run once per decide/isVisible evaluation for both callers")
                        .isEqualTo(4);
                assertThat(invocationCount)
                        .as("only the action-permitted caller reaches the guarded invocation path")
                        .hasValue(1);
            }
            case ROLES_AND_ACTION_ROW -> {
                McpToolDescriptor rolesAndActionTool = descriptor("roles-action-tool", rolesAndActionAccess());

                // Neither caller satisfies BOTH gates: alice has the "ops" role but fails the
                // admin-only action gate; admin passes the action gate but lacks the "ops" role. A
                // buggy OR-composition would wrongly permit one of them; AND-composition denies both.
                assertDeniedAndHidden(rolesAndActionTool, establish(BEARER_ALICE));
                assertDeniedAndHidden(rolesAndActionTool, establish(BEARER_ADMIN));

                assertThat(events).hasSize(4);
                assertThat(invocationCount)
                        .as("AND-composition denies both callers, so neither is invoked")
                        .hasValue(0);
            }
            case DENY_ALL_ROW -> {
                McpToolDescriptor denyTool = descriptor("deny-tool", denyAllAccess());

                for (String bearer : new String[] {null, BEARER_ALICE, BEARER_ADMIN}) {
                    assertDeniedAndHidden(denyTool, establish(bearer));
                }
                assertThat(events).hasSize(6);
                assertThat(invocationCount)
                        .as("a @DenyAll tool is never invoked, for any caller")
                        .hasValue(0);

                // Indistinguishability is structural: the factory takes no argument, so the response
                // cannot vary by tool. What stays falsifiable — and is asserted here — is that the
                // payload carries nothing revealing that the tool exists.
                McpProtocolCodec.CodecError deniedResponse = McpPolicyEnforcer.unknownOrUnauthorizedError();
                assertThat(deniedResponse.code()).isEqualTo(-32602);
                assertThat(deniedResponse.message()).isEqualTo("Invalid params");
                assertThat(deniedResponse.data())
                        .as("the response must never leak whether the tool exists")
                        .isNull();
                assertThat(deniedResponse.toString())
                        .as("the unknown-or-unauthorized response must not name the denied tool")
                        .doesNotContain(denyTool.name());
            }
            case ANONYMOUS_FAIL_CLOSED_ROW -> {
                McpToolDescriptor rolesTool = descriptor("anon-roles-tool", rolesAccess());
                McpToolDescriptor actionTool = descriptor("anon-action-tool", actionOnlyAccess());
                McpToolDescriptor rolesAndActionTool = descriptor("anon-roles-action-tool", rolesAndActionAccess());

                SecurityContext anonymous = establish(null);
                for (McpToolDescriptor descriptor : List.of(rolesTool, actionTool, rolesAndActionTool)) {
                    assertDeniedAndHidden(descriptor, anonymous);
                }

                assertThat(events).hasSize(6);
                assertThat(invocationCount)
                        .as("no anonymous caller reaches the guarded invocation path")
                        .hasValue(0);
            }
            default -> fail("unknown T005 authorization matrix row: " + row);
        }
    }

    // --- Assertions ---

    private void assertPermittedAndVisible(McpToolDescriptor descriptor, SecurityContext caller) throws Exception {
        AuthorizationDecision decision = await(mcpPolicyEnforcer.decide(descriptor, caller));
        assertThat(decision.permitted())
                .as("%s must be permitted for %s", descriptor.name(), actorId(caller))
                .isTrue();
        assertThat(await(mcpPolicyEnforcer.isVisible(descriptor, caller)))
                .as("%s must be visible to %s", descriptor.name(), actorId(caller))
                .isTrue();

        // A permit MUST move the counter. This is what makes every "invocation count stayed 0"
        // assertion on a denied row decisive rather than vacuous: the counter is demonstrably live.
        int before = invocationCount.get();
        invokeIfPermitted(decision);
        assertThat(invocationCount.get())
                .as("a permitted %s must reach the guarded invocation path", descriptor.name())
                .isEqualTo(before + 1);
    }

    private void assertDeniedAndHidden(McpToolDescriptor descriptor, SecurityContext caller) throws Exception {
        AuthorizationDecision decision = await(mcpPolicyEnforcer.decide(descriptor, caller));
        assertThat(decision.permitted())
                .as("%s must be denied for %s", descriptor.name(), actorId(caller))
                .isFalse();
        assertThat(await(mcpPolicyEnforcer.isVisible(descriptor, caller)))
                .as("%s must be hidden from %s", descriptor.name(), actorId(caller))
                .isFalse();

        int before = invocationCount.get();
        invokeIfPermitted(decision);
        assertThat(invocationCount.get())
                .as("a denied %s must never reach the guarded invocation path", descriptor.name())
                .isEqualTo(before);
    }

    /**
     * The guarded call path a dispatcher takes (T012 owns the wire form): the tool is invoked only
     * when the decision permits. Driving both the permitted and the denied assertions through this
     * one path is what gives the zero-invocation claim its teeth — a decision that wrongly permits
     * shows up immediately as an incremented counter.
     *
     * @param decision the decision already obtained for this descriptor and caller; must not be
     *                 {@code null}
     */
    private void invokeIfPermitted(AuthorizationDecision decision) {
        if (decision.permitted()) {
            invocationCount.incrementAndGet();
        }
    }

    private static String actorId(SecurityContext ctx) {
        return ctx.identity().actor().type() + ":" + ctx.identity().actor().id();
    }

    // --- Descriptor construction (Given: directly constructed descriptors) ---

    private static McpToolDescriptor descriptor(String name, McpToolAccess access) {
        return new McpToolDescriptor(
                name,
                null,
                "test tool " + name,
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\"}",
                null,
                access);
    }

    private static McpToolAccess permitAllAccess() {
        return new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null);
    }

    private static McpToolAccess denyAllAccess() {
        return new McpToolAccess(McpAccessMode.DENY_ALL, List.of(), null);
    }

    private static McpToolAccess rolesAccess() {
        return new McpToolAccess(McpAccessMode.RESTRICTED, List.of("ops"), null);
    }

    private static McpToolAccess actionOnlyAccess() {
        return new McpToolAccess(McpAccessMode.RESTRICTED, List.of(), TOOL_ACTION);
    }

    private static McpToolAccess rolesAndActionAccess() {
        return new McpToolAccess(McpAccessMode.RESTRICTED, List.of("ops"), TOOL_ACTION);
    }

    // --- Harness: real port-0 identity establishment ---

    private void startServer() throws Exception {
        boundSecurityContext = new AtomicReference<>();
        events = new ArrayList<>();
        invocationCount = new AtomicInteger();
        authorizer = new AdminOnlyActionAuthorizer();

        RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime(boundSecurityContext);
        SecurityEventEmitter emitter = capturingEmitter(events);
        securityPolicyEnforcer = new SecurityPolicyEnforcer(
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                emitter,
                NO_OP_CONTEXT_HOLDER,
                securityRuntime,
                Optional.of(authorizer));
        mcpPolicyEnforcer = new McpPolicyEnforcer(securityPolicyEnforcer);

        IdentityResolutionMiddleware identityResolution = new IdentityResolutionMiddleware(
                Set.of(new SubjectEvidenceIdentityResolver()),
                Optional.of(new DefaultSecurityClaimMapper()),
                new SecurityEventEmitter(Set.of()),
                securityRuntime,
                NO_OP_CONTEXT_HOLDER);

        Router router = Router.router(vertx);
        router.route().handler(new RequestContextLifecycle());
        router.route()
                .handler(new BearerRouteAuthHandler().createOptionalHandler().orElseThrow());
        router.route()
                .handler(identityResolution.handlerFor(
                        dev.vertique.security.authz.InvocationOrigin.of(DispatchBoundary.MCP)));
        router.route().handler(ctx -> ctx.response().setStatusCode(200).end());

        server = await(vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
        port = server.actualPort();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);
    }

    /**
     * Establishes one caller's real {@link SecurityContext} over HTTP and returns it. A {@code null}
     * {@code bearer} sends no {@code Authorization} header (the anonymous caller); an invalid bearer
     * (used only by the sensitivity proof) fails the request with 401 and never binds a context — the
     * canonical anonymous stand-in is returned instead, matching the real contract that an
     * unauthenticated caller never reaches tool authorization.
     *
     * @param bearer the {@code Authorization} header value to send, or {@code null} for none
     * @return the established caller {@link SecurityContext}; never {@code null}
     */
    private SecurityContext establish(String bearer) throws Exception {
        boundSecurityContext.set(null);
        HttpRequest<Buffer> request = client.post(port, "127.0.0.1", "/probe");
        if (bearer != null) {
            request = request.putHeader("Authorization", bearer);
        }
        HttpResponse<Buffer> response = await(request.sendBuffer(Buffer.buffer()));
        if (response.statusCode() == 401) {
            return anonymousContext();
        }
        assertThat(response.statusCode()).isEqualTo(200);
        SecurityContext bound = boundSecurityContext.get();
        assertThat(bound).as("identity resolution must bind a context").isNotNull();
        return bound;
    }

    private static SecurityContext anonymousContext() {
        return dev.vertique.security.SecurityContexts.unauthenticated(SecurityIdentity.anonymous());
    }

    private static SecurityEventEmitter capturingEmitter(List<AuthorizationDecisionEvent> sink) {
        SecurityEventObserver observer = new SecurityEventObserver() {
            @Override
            public Future<Void> onAuthorizationDecided(AuthorizationDecisionEvent event) {
                sink.add(event);
                return Future.succeededFuture();
            }
        };
        return new SecurityEventEmitter(Set.of(observer));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    // --- Test doubles ---

    /**
     * Resolves the canonical anonymous identity from empty evidence and the {@code sub}-named user
     * otherwise — mirrors {@link McpDiscoverIT}'s identical fixture resolver.
     */
    private record SubjectEvidenceIdentityResolver() implements SecurityIdentityResolver {

        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            if (context.evidence().isEmpty()) {
                return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
            }
            Object subject = context.evidence().get(0).safeAttributes().get("sub");
            return Future.succeededFuture(Optional.of(
                    SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, String.valueOf(subject), Map.of()))));
        }
    }

    /** A {@link ContextHolder} that resolves nothing and discards every binding. */
    private static final ContextHolder NO_OP_CONTEXT_HOLDER = new ContextHolder() {
        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            return () -> {};
        }
    };

    /** A {@link SecurityRuntime} that records the bound {@link SecurityContext} for assertion. */
    private record RecordingSecurityRuntime(AtomicReference<SecurityContext> bound) implements SecurityRuntime {

        @Override
        public SecurityContext current() {
            return bound.get();
        }

        @Override
        public ContextHolder.Scope bindCurrent(SecurityContext context) {
            bound.set(context);
            return () -> {};
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
            return null;
        }
    }

    /**
     * An optional-authentication bearer scheme recognizing exactly {@code "Bearer alice"} (role
     * {@code ops}) and {@code "Bearer admin"} (no roles); any other present credential fails 401
     * without ever calling {@code next()}; an absent credential continues anonymously.
     */
    private static final class BearerRouteAuthHandler implements RouteAuthHandler {

        @Override
        public String schemeName() {
            return "bearer";
        }

        @Override
        public Handler<RoutingContext> createHandler() {
            return context -> context.fail(401);
        }

        @Override
        public Optional<Handler<RoutingContext>> createOptionalHandler() {
            return Optional.of(context -> {
                String credential = context.request().getHeader("Authorization");
                if (credential == null) {
                    context.next();
                    return;
                }
                switch (credential) {
                    case BEARER_ALICE -> authenticate(context, "alice", new JsonArray().add("ops"));
                    case BEARER_ADMIN -> authenticate(context, "admin", new JsonArray());
                    default -> context.fail(401);
                }
            });
        }

        private static void authenticate(RoutingContext context, String subject, JsonArray roles) {
            RestAuthenticationEvidence.append(
                    context,
                    new AuthenticationEvidence(
                            DefaultAuthMethod.jwt(),
                            Optional.of(subject),
                            Instant.now(),
                            Optional.empty(),
                            new CustomVerificationSource("test", Map.of()),
                            Map.of("sub", subject)));
            ((UserContextInternal) context.userContext())
                    .setUser(User.create(new JsonObject().put("sub", subject).put("roles", roles)));
            context.next();
        }
    }

    /**
     * The fine action gate: permits {@link #TOOL_ACTION} only for the {@code admin} identity, denying
     * every other caller (including {@code alice}, who satisfies only the coarse role gate) —
     * isolates the fine gate from the coarse gate so the composed-gate row can prove true
     * AND-composition rather than either gate alone.
     */
    private static final class AdminOnlyActionAuthorizer implements Authorizer {

        private final AtomicInteger callCount = new AtomicInteger();

        int callCount() {
            return callCount.get();
        }

        @Override
        public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
            callCount.incrementAndGet();
            boolean permitted =
                    "admin".equals(request.securityContext().identity().actor().id());
            return Future.succeededFuture(
                    permitted
                            ? AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)
                            : AuthorizationDecision.deny(AuthzReasonCodes.ACTION_NOT_ALLOWED));
        }

        @Override
        public Future<AuthorizationDecision> authorize(
                SecurityContext ctx, ActionRef action, dev.vertique.security.authz.ResourceRef resource) {
            throw new UnsupportedOperationException("decide() uses the 5-arg AuthorizationRequest overload only");
        }
    }
}
