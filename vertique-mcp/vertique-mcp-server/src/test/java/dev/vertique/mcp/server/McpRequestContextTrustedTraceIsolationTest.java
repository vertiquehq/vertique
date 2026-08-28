// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.interceptor.McpRequestContext;
import dev.vertique.mcp.interceptor.McpRequestInterceptor;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.DefaultSecurityClaimMapper;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * R51 (trace-reference consolidation) — no-leak pin: proves the request's <em>trusted</em>
 * correlation-identity path never observes MCP's client-supplied body trace reference.
 *
 * <p>D004 / repair task R47's adjudicated trust posture is that the body {@code
 * params._meta.traceparent}/{@code tracestate} reference is untrusted, link-only data — the same
 * trust posture as an inbound HTTP {@code traceparent} header, which an anonymous caller already
 * fully controls. R51's contract requires that once the body reference travels only as {@code
 * dev.vertique.mcp.lifecycle.McpRequestTerminalObservation#linkedTrace()} — the payload-free
 * lifecycle observation, never {@code CorrelationContextSnapshot}, which R51 deliberately does not
 * modify — "the correlation identity paths provably ignore the untrusted reference" — concretely,
 * {@link CorrelationContextSnapshot#trace()} (the trusted, identity-bearing trace slot mirrored
 * into MDC by {@code CorrelationContextMutator#setTrace}) must never be populated from the body
 * reference.
 *
 * <p>This is a plausible, easy implementation mistake precisely because both fields are named
 * "trace": a fix that threads the newly extracted {@code TraceReference} into {@code
 * CorrelationContextSnapshot.trace()} instead of the new {@code linkedTrace()} slot would satisfy
 * every existing body-trace-visibility assertion (which only ever inspected the old {@code
 * bodyTraceContext}/{@code McpTraceContext} side channel) while silently smuggling untrusted,
 * client-supplied trace data into the framework's trusted correlation-identity field — and, via
 * {@code CorrelationContextMutator#setTrace}, into log MDC.
 *
 * <p><strong>Full router, not a mocked {@code RoutingContext}.</strong> {@code
 * McpRequestDispatcher#begin} — which establishes the live {@link CorrelationContextSnapshot} this
 * test inspects — runs strictly before {@code dispatch} in the real request chain ({@link
 * McpRouterMount}'s route wiring), so a fixture that calls {@code dispatcher.dispatch(context)}
 * directly against a bare mock (as {@code McpRequestContextBodyTraceVisibilityTest} does for its
 * own, narrower purpose) never observes a bound correlation at all. This test drives one real HTTP
 * exchange through {@link McpRouterMount} instead, mirroring {@code McpCorrelationLifecycleIT}'s
 * harness, so {@code correlation()} is genuinely populated and the pin is meaningful.
 *
 * <p><strong>Green today.</strong> The dispatcher's live {@link CorrelationContextSnapshot} for an
 * MCP request is minted fresh by {@code correlationContextFactory.seed("mcp")} in {@code begin}
 * and stored on the routing context before the body is ever decoded — {@code trace()} is
 * unconditionally {@code null} regardless of what the body carries, since nothing in today's
 * dispatch path ever calls {@code CorrelationContextMutator#setTrace} for MCP. That already-true
 * fact is exactly what this test pins, so a future wiring mistake described above turns it red.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpRequestContextTrustedTraceIsolationTest {

    private static final String VALID_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String VALID_SPAN_ID = "00f067aa0ba902b7";
    private static final String VALID_TRACEPARENT = "00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-01";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String REQUEST_PATH = "/mcp/";

    private final Vertx vertx = Vertx.vertx();
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    @AfterEach
    void tearDown() throws Exception {
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
    @DisplayName("R51 no-leak pin: a valid body _meta.traceparent never populates "
            + "correlation().trace() (the trusted identity slot MDC mirrors)")
    void bodyTraceparentNeverPopulatesTheTrustedCorrelationTraceSlot() throws Exception {
        ContextHolder contextHolder = new DefaultContextHolder();
        CorrelationContextFactory correlationContextFactory = new CorrelationContextFactory(Optional.empty());

        AtomicReference<McpRequestContext> recorded = new AtomicReference<>();
        McpRequestInterceptor capturingInterceptor = new McpRequestInterceptor() {
            @Override
            public Future<Void> beforeRequest(McpRequestContext context) {
                recorded.set(context);
                return Future.succeededFuture();
            }
        };

        SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of());
        RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
        IdentityResolutionMiddleware identityResolutionMiddleware = new IdentityResolutionMiddleware(
                Set.<SecurityIdentityResolver>of(),
                Optional.of(new DefaultSecurityClaimMapper()),
                emitter,
                securityRuntime,
                contextHolder);

        McpServerConfig config = McpServerConfig.builder()
                .enabled(true)
                .serverName("r51-trusted-trace-isolation-test")
                .serverVersion("1.0")
                .build();
        HttpConfig httpConfig = HttpConfig.builder().idleTimeoutSeconds(60).build();

        SecurityPolicyEnforcer securityPolicyEnforcer = new SecurityPolicyEnforcer(
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                emitter,
                contextHolder,
                securityRuntime,
                Optional.empty());
        McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(securityPolicyEnforcer);

        McpRequestDispatcher dispatcher = new McpRequestDispatcher(
                config,
                securityRuntime,
                Set.of(),
                Set.of(),
                Set.of(capturingInterceptor),
                Set.of(),
                httpConfig,
                McpToolRegistry.build(Set.of()),
                policyEnforcer,
                contextHolder,
                correlationContextFactory);

        McpRouterMount mount = new McpRouterMount(
                config,
                new McpServerConfigValidator(),
                dispatcher,
                Set.of(),
                identityResolutionMiddleware,
                httpConfig,
                McpToolRegistry.build(Set.of()));

        Router router = Router.router(vertx);
        router.route().handler(new RequestContextLifecycle());
        router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
        server = await(vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
        int port = server.actualPort();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        HttpResponse<Buffer> response = await(client.post(port, "127.0.0.1", REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "server/discover")
                .putHeader("Mcp-Name", "server/discover")
                .sendBuffer(discoverBodyWithTraceparent(VALID_TRACEPARENT)));

        assertThat(response.statusCode())
                .as("a valid discover request must still be admitted")
                .isEqualTo(200);

        McpRequestContext observed = recorded.get();
        assertThat(observed)
                .as("the request interceptor must have observed the request")
                .isNotNull();
        CorrelationContextSnapshot correlation = observed.correlation();
        assertThat(correlation)
                .as("R09/R47: correlation is always established before dispatch on the real router path")
                .isNotNull();
        assertThat(correlation.trace())
                .as("DECISIVE: the trusted correlation trace slot must stay null — the body reference"
                        + " is untrusted, link-only data and must never reach the identity path")
                .isNull();
    }

    private static Buffer discoverBodyWithTraceparent(String traceparent) {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject())
                .put("traceparent", traceparent);
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "server/discover")
                .put("params", new JsonObject().put("_meta", meta))
                .toBuffer();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
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
}
