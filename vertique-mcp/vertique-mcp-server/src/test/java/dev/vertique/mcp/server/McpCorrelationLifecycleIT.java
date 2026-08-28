// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.core.correlation.CorrelationIdGenerator;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.interceptor.McpRequestContext;
import dev.vertique.mcp.interceptor.McpToolInterceptor;
import dev.vertique.mcp.interceptor.McpToolInvocationContext;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.mcp.tool.McpToolResult;
import dev.vertique.rest.core.config.HttpConfig;
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
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.CredentialAcceptedEvent;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * R09 (merge blocker 2) — proves that {@link McpRequestDispatcher#begin} binds one live {@code
 * CorrelationContext} onto the shared {@code ContextHolder} substrate through the real request
 * pipeline (identity establishment, authorization, tool interception, terminal-event construction),
 * rather than the pre-fix behaviour of a routing-context-only snapshot with an independently
 * generated id.
 *
 * <p>Every collaborator in this harness — {@link McpRequestDispatcher}, {@link
 * IdentityResolutionMiddleware}, and {@link SecurityPolicyEnforcer} — shares the <em>same</em> live
 * {@link DefaultContextHolder} instance, exactly as a production application graph composes them
 * (all three resolve the identical Dagger-bound {@code ContextHolder} singleton). The correlation-id
 * generator is an application-supplied {@link CorrelationIdGenerator} that mints a recognizable,
 * non-UUID-shaped id — a real {@code UUID.randomUUID()} value could never coincidentally match it —
 * so observing that literal id at a call site is decisive proof the generator was actually consulted,
 * not bypassed.
 *
 * <p>One authenticated, authorized {@code tools/call} request drives all five consequences through
 * the real path in a single exchange:
 * <ol>
 *   <li>{@link CredentialAcceptedEvent} is observed (was suppressed pre-fix: {@code
 *       IdentityResolutionMiddleware} found no bound correlation and skipped emission).</li>
 *   <li>The {@link AuthorizationDecisionEvent} carries a <em>bound</em> correlation whose request id
 *       matches the generator's id (was pre-fix {@code CorrelationContext.unbound()}, whose request
 *       id is the sentinel {@code "unavailable"}).</li>
 *   <li>The {@link McpToolInterceptor} observes a non-null correlation matching the same id (was
 *       pre-fix a hardcoded {@code null}).</li>
 *   <li>The generator's id is the one that reaches the terminal event (was pre-fix bypassed by a raw
 *       {@code UUID.randomUUID()} call in the dispatcher's own {@code establishCorrelation}).</li>
 *   <li>All four facts share the identical request id — terminal and audit correlation are reliably
 *       joinable to the same live request.</li>
 * </ol>
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpCorrelationLifecycleIT {

    private static final String TOOL_NAME = "call.restrictedTool";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String BEARER_ALICE = "Bearer alice";
    private static final String SSE_PREFIX = "event: message\ndata: ";

    private final Vertx vertx = Vertx.vertx();
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    @AfterEach
    void tearDown() throws Exception {
        // R48 W4: the raw HttpClient WebClient wraps must be retained and its close propagated too —
        // pre-fix, only the server's close was awaited and the wrapped client's underlying connections
        // were silently discarded, mirroring the R46 teardown discipline (McpToolsListDisconnectIT,
        // McpDisconnectBeforeInvocationIT) applied to every sibling IT in this package.
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<Void> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Future.join(serverClose, clientClose).onComplete(joined -> vertx.close().onComplete(vertxResult -> {
            Throwable failure = joined.failed() ? joined.cause() : vertxResult.cause();
            if (failure != null) {
                closed.completeExceptionally(failure);
            } else {
                closed.complete(null);
            }
        }));
        closed.get(10, TimeUnit.SECONDS);
        server = null;
        rawClient = null;
        client = null;
    }

    @Test
    @DisplayName("R09: one live bound CorrelationContext reaches identity, authorization, "
            + "the tool interceptor, and the terminal event — all with the same, generator-minted id")
    void shouldJoinCorrelationAcrossIdentityAuthorizationInterceptionAndTerminal() throws Exception {
        // --- Shared collaborators: one live ContextHolder, one app-supplied generator ---
        ContextHolder contextHolder = new DefaultContextHolder();
        RecordingCorrelationIdGenerator generator = new RecordingCorrelationIdGenerator();
        CorrelationContextFactory correlationContextFactory = new CorrelationContextFactory(Optional.of(generator));

        List<CredentialAcceptedEvent> acceptedEvents = new CopyOnWriteArrayList<>();
        List<AuthorizationDecisionEvent> decisionEvents = new CopyOnWriteArrayList<>();
        SecurityEventObserver observer = new SecurityEventObserver() {
            @Override
            public Future<Void> onCredentialAccepted(CredentialAcceptedEvent event) {
                acceptedEvents.add(event);
                return Future.succeededFuture();
            }

            @Override
            public Future<Void> onAuthorizationDecided(AuthorizationDecisionEvent event) {
                decisionEvents.add(event);
                return Future.succeededFuture();
            }
        };
        SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of(observer));
        RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();

        SecurityPolicyEnforcer securityPolicyEnforcer = new SecurityPolicyEnforcer(
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                emitter,
                contextHolder,
                securityRuntime,
                Optional.empty());
        McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(securityPolicyEnforcer);

        IdentityResolutionMiddleware identityResolutionMiddleware = new IdentityResolutionMiddleware(
                Set.of(new SubjectRoleIdentityResolver()),
                Optional.of(new DefaultSecurityClaimMapper()),
                emitter,
                securityRuntime,
                contextHolder);

        // --- The tool interceptor: captures the correlation it observed at the post-validation stage ---
        AtomicReference<CorrelationContextSnapshot> interceptorObservedCorrelation = new AtomicReference<>();
        McpToolInterceptor capturingInterceptor = new McpToolInterceptor() {
            @Override
            public Future<Void> beforeInvocation(McpToolInvocationContext context) {
                McpRequestContext requestContext = context.request();
                interceptorObservedCorrelation.set(requestContext.correlation());
                return Future.succeededFuture();
            }
        };

        // --- The terminal-event observer: captures the one terminal event this request settles as ---
        AtomicReference<McpRequestTerminalEvent> terminalEvent = new AtomicReference<>();
        McpRequestLifecycleObserver terminalObserver = startedAt -> new McpRequestObservation() {
            @Override
            public void onTerminal(McpRequestTerminalObservation observation) {
                terminalEvent.set(observation.event());
            }
        };

        McpToolDescriptor descriptor = new McpToolDescriptor(
                TOOL_NAME,
                null,
                "R09 fixture tool.",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\",\"additionalProperties\":false}",
                null,
                new McpToolAccess(McpAccessMode.RESTRICTED, List.of("ops"), null));
        McpToolInvoker invoker = new McpToolInvoker() {
            @Override
            public McpToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
                return new McpPreparedToolCall() {
                    @Override
                    public Map<String, Object> normalizedArguments() {
                        return Map.of();
                    }

                    @Override
                    public Future<McpToolResult<?>> invoke() {
                        return Future.succeededFuture(McpToolResult.text("ok"));
                    }
                };
            }
        };
        McpToolRegistry registry = McpToolRegistry.build(Set.of(invoker));

        McpServerConfig config = McpServerConfig.builder()
                .enabled(true)
                .serverName("correlation-lifecycle-test")
                .serverVersion("1.0")
                .authenticationScheme("bearer")
                .build();
        HttpConfig httpConfig = HttpConfig.builder().idleTimeoutSeconds(60).build();

        McpRequestDispatcher dispatcher = new McpRequestDispatcher(
                config,
                securityRuntime,
                Set.of(terminalObserver),
                Set.<McpRequestCompletedListener>of(),
                Set.of(),
                Set.of(capturingInterceptor),
                httpConfig,
                registry,
                policyEnforcer,
                contextHolder,
                correlationContextFactory);

        McpRouterMount mount = new McpRouterMount(
                config,
                new McpServerConfigValidator(),
                dispatcher,
                Set.of(new BearerRouteAuthHandler()),
                identityResolutionMiddleware,
                httpConfig,
                registry);

        Router router = Router.router(vertx);
        router.route().handler(new RequestContextLifecycle());
        router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
        server = await(vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
        int port = server.actualPort();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        // --- Drive one authenticated, authorized tools/call request ---
        HttpResponse<Buffer> response = await(callTool(port));

        assertThat(response.statusCode())
                .as("an authenticated caller with the required role must be permitted")
                .isEqualTo(200);
        JsonObject result = sseResult(response.bodyAsString());
        assertThat(result.getBoolean("isError")).isFalse();

        // The generator was consulted at all — otherwise every downstream assertion below is vacuous.
        assertThat(generator.generated())
                .as("CorrelationContextFactory#seed must consult the injected generator at least twice "
                        + "(requestId, then correlationId)")
                .hasSizeGreaterThanOrEqualTo(2);
        String expectedRequestId = generator.generated().get(0);

        // Consequence 1: authenticated CredentialAcceptedEvent emission is no longer suppressed.
        assertThat(acceptedEvents)
                .as("CONSEQUENCE 1: an authenticated request must emit CredentialAcceptedEvent — pre-fix "
                        + "this was suppressed because IdentityResolutionMiddleware found no bound "
                        + "CorrelationContext and logged a warning instead of emitting")
                .hasSize(1);
        assertThat(acceptedEvents.get(0).correlation().requestId().value())
                .as("the emitted event's correlation must be the live, generator-minted request id")
                .isEqualTo(expectedRequestId);

        // Consequence 2: authorization events receive a bound (not unbound) correlation.
        assertThat(decisionEvents)
                .as("the RESTRICTED role-gated tool must reach exactly one real policy evaluation")
                .hasSize(1);
        assertThat(decisionEvents.get(0).correlation().requestId().value())
                .as("CONSEQUENCE 2: the AuthorizationDecisionEvent's correlation must be bound to this "
                        + "request's live context, not CorrelationContext.unbound() (whose requestId is "
                        + "the sentinel \"unavailable\")")
                .isEqualTo(expectedRequestId)
                .isNotEqualTo("unavailable");

        // Consequence 3: tool interceptors receive a non-null correlation matching the live context.
        assertThat(interceptorObservedCorrelation.get())
                .as("CONSEQUENCE 3: a tool interceptor must observe a non-null correlation — pre-fix this "
                        + "was a hardcoded null in the McpToolInvocationContext construction")
                .isNotNull();
        assertThat(interceptorObservedCorrelation.get().requestId().value())
                .as("the interceptor-observed correlation must match the live request id")
                .isEqualTo(expectedRequestId);

        // Consequence 4: the application-provided CorrelationIdGenerator produces the id that reaches
        // the terminal event — pre-fix, McpRequestDispatcher#establishCorrelation minted its own id via
        // a raw UUID.randomUUID() call, bypassing the generator entirely.
        assertThat(terminalEvent.get())
                .as("the terminal-event observer must have fired")
                .isNotNull();
        assertThat(terminalEvent.get().correlation()).isNotNull();
        assertThat(terminalEvent.get().correlation().requestId().value())
                .as("CONSEQUENCE 4: the terminal event's correlation must be the id the injected "
                        + "generator produced, not an independently generated UUID")
                .isEqualTo(expectedRequestId);

        // Consequence 5: terminal and audit correlation are reliably joinable — all four facts captured
        // above (credential-accepted, authorization decision, interceptor, terminal) share one id.
        assertThat(List.of(
                        acceptedEvents.get(0).correlation().requestId().value(),
                        decisionEvents.get(0).correlation().requestId().value(),
                        interceptorObservedCorrelation.get().requestId().value(),
                        terminalEvent.get().correlation().requestId().value()))
                .as("CONSEQUENCE 5: every security and lifecycle fact for this request must join on the "
                        + "same single live correlation id")
                .containsOnly(expectedRequestId);
    }

    // --- Wire helpers ---

    private Future<HttpResponse<Buffer>> callTool(int port) {
        HttpRequest<Buffer> request = client.post(port, "127.0.0.1", REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", TOOL_NAME)
                .putHeader("Authorization", BEARER_ALICE);
        return request.sendBuffer(callBody());
    }

    private static Buffer callBody() {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params = new JsonObject().put("_meta", meta).put("name", TOOL_NAME);
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .put("params", params)
                .toBuffer();
    }

    private static JsonObject sseResult(String rawBody) {
        assertThat(rawBody).startsWith(SSE_PREFIX);
        JsonObject data = new JsonObject(rawBody.substring(SSE_PREFIX.length()).stripTrailing());
        JsonObject result = data.getJsonObject("result");
        assertThat(result)
                .as("a permitted call must settle with a result, not an error")
                .isNotNull();
        return result;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    // --- Test doubles ---

    /**
     * A {@link CorrelationIdGenerator} that mints recognizable, non-UUID-shaped ids and records every
     * one generated, in order. {@link CorrelationContextFactory#seed} calls {@link #generate()} twice
     * per invocation (requestId, then correlationId), so {@link #generated()}'s first two entries are
     * this request's live requestId/correlationId — a real {@code UUID.randomUUID()} value could never
     * coincidentally equal one of these literals, so observing one at a call site is decisive proof the
     * generator was actually consulted.
     */
    private static final class RecordingCorrelationIdGenerator implements CorrelationIdGenerator {
        private final List<String> generated = new CopyOnWriteArrayList<>();

        @Override
        public String generate() {
            String id = "r09-generator-id-" + generated.size();
            generated.add(id);
            return id;
        }

        List<String> generated() {
            return generated;
        }
    }

    /** Resolves the canonical anonymous identity from empty evidence and the {@code sub}-named user otherwise. */
    private record SubjectRoleIdentityResolver() implements SecurityIdentityResolver {

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

    /** A {@link SecurityRuntime} that records the bound {@link SecurityContext} without asserting on it. */
    private static final class RecordingSecurityRuntime implements SecurityRuntime {
        private volatile SecurityContext bound;

        @Override
        public SecurityContext current() {
            return bound;
        }

        @Override
        public ContextHolder.Scope bindCurrent(SecurityContext context) {
            bound = context;
            return () -> {};
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
            return null;
        }
    }

    /**
     * A hand-written optional-authentication bearer scheme recognizing exactly {@code "Bearer alice"}
     * (role {@code ops}); mirrors {@link McpToolCallIT}'s identical fixture handler.
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
                if (!BEARER_ALICE.equals(credential)) {
                    context.fail(401);
                    return;
                }
                RestAuthenticationEvidence.append(
                        context,
                        new AuthenticationEvidence(
                                DefaultAuthMethod.jwt(),
                                Optional.of("alice"),
                                Instant.now(),
                                Optional.empty(),
                                new CustomVerificationSource("test", Map.of()),
                                Map.of("sub", "alice")));
                ((UserContextInternal) context.userContext())
                        .setUser(User.create(
                                new JsonObject().put("sub", "alice").put("roles", new JsonArray().add("ops"))));
                context.next();
            });
        }
    }
}
