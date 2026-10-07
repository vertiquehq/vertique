// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpOutcome;
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
import dev.vertique.ratelimit.GreedyRateLimitRefill;
import dev.vertique.ratelimit.LocalRateLimitBackendFactory;
import dev.vertique.ratelimit.RateLimitFailureMode;
import dev.vertique.ratelimit.RateLimitMode;
import dev.vertique.ratelimit.RateLimitPolicy;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.TokenBucketRateLimit;
import dev.vertique.ratelimit.spi.AnonymousRateLimitPolicy;
import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendRequest;
import dev.vertique.ratelimit.spi.RateLimitBackendResult;
import dev.vertique.ratelimit.spi.RateLimitSubject;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.DefaultSecurityClaimMapper;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Admission-stage proof: a real mounted MCP dispatcher over a real LOCAL Bucket4j policy.
 *
 * <p>The configured-policy proof deliberately uses {@link RateLimitSubject#NONE}; the rate-limit
 * subject resolver is therefore not part of this walking skeleton's behavior. The no-policy control
 * passes no {@link RateLimiters} at all, rather than installing a spy for a backend that must be
 * absent.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpToolAdmissionWalkingSkeletonIT {

    private static final String POLICY_NAME = "mcp-write";
    private static final long CAPACITY = 1L;
    private static final String TOOL_NAME = "admission.walkingSkeleton";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SSE_PREFIX = "event: message\ndata: ";

    private final Vertx vertx = Vertx.vertx();

    private HttpServer server;
    private HttpClient rawClient;

    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<Void> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose).onComplete(joined -> vertx.close().onComplete(vertxResult -> {
            Throwable failure = joined.failed() ? joined.cause() : vertxResult.cause();
            if (failure == null) {
                closed.complete(null);
            } else {
                closed.completeExceptionally(failure);
            }
        }));
        closed.get(10, TimeUnit.SECONDS);
    }

    @Test
    void shouldAdmitThenDenyAcrossQuotaExhaustionForAConfigBoundTool() throws Exception {
        // Given: capacity one, an explicitly global subject, and a real LOCAL Bucket4j backend.
        RecordingLocalRateLimitBackend backend = new RecordingLocalRateLimitBackend();
        RateLimiters rateLimiters = rateLimiters(backend, CAPACITY);
        McpToolAdmissionWalkingSkeletonFixture fixture = startFixture(
                new McpRateLimitConfig(
                        POLICY_NAME, RateLimitSubject.NONE, AnonymousRateLimitPolicy.SHARED_BUCKET, List.of()),
                Optional.of(rateLimiters));

        // When: the same configured tool is called twice.
        HttpResponse<Buffer> permitted = callTool(fixture.port(), 1);
        HttpResponse<Buffer> quotaExceeded = callTool(fixture.port(), 2);

        // Then: only the first request consumes capacity and reaches the tool.
        assertThat(permitted.statusCode()).isEqualTo(200);
        assertThat(sseResult(permitted.bodyAsString())
                        .getJsonArray("content")
                        .getJsonObject(0)
                        .getString("text"))
                .isEqualTo("admitted");
        assertThat(backend.consumedTokenCount())
                .as("the admitted call must consume exactly the configured cost of one token")
                .isEqualTo(1L);

        assertThat(quotaExceeded.statusCode()).isEqualTo(429);
        assertThat(quotaExceeded.getHeader("Retry-After")).isNotNull();
        assertThat(Long.parseLong(quotaExceeded.getHeader("Retry-After"))).isGreaterThanOrEqualTo(1L);
        assertThat(quotaExceeded.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(new JsonObject(quotaExceeded.bodyAsString())
                        .getJsonObject("error")
                        .getInteger("code"))
                .isEqualTo(-32022);
        assertThat(fixture.terminalEvents())
                .extracting(McpRequestTerminalEvent::outcome, McpRequestTerminalEvent::errorType)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(McpOutcome.SUCCESS, McpErrorType.NONE),
                        org.assertj.core.groups.Tuple.tuple(McpOutcome.REJECTED, McpErrorType.RATE_LIMIT));
        assertThat(fixture.tool().invocationCount())
                .as("a quota-exceeded request must be rejected before tool invocation")
                .isEqualTo(1);
    }

    @Test
    void shouldBehaveIdenticallyToMcp001WhenNoPolicyIsConfigured() throws Exception {
        // Given: no default policy and no RateLimiters binding or backend of any kind.
        McpToolAdmissionWalkingSkeletonFixture fixture = startFixture(McpRateLimitConfig.defaults(), Optional.empty());

        // When: the registered tool is called through the ordinary dispatch path.
        HttpResponse<Buffer> response = callTool(fixture.port(), 1);

        // Then: no-policy composition and the existing tool result remain available without a backend.
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(sseResult(response.bodyAsString())
                        .getJsonArray("content")
                        .getJsonObject(0)
                        .getString("text"))
                .isEqualTo("admitted");
        assertThat(fixture.tool().invocationCount()).isEqualTo(1);
        assertThat(fixture.terminalEvents())
                .extracting(McpRequestTerminalEvent::outcome, McpRequestTerminalEvent::errorType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(McpOutcome.SUCCESS, McpErrorType.NONE));
    }

    private McpToolAdmissionWalkingSkeletonFixture startFixture(
            McpRateLimitConfig rateLimitConfig, Optional<RateLimiters> rateLimiters) throws Exception {
        McpToolAdmissionWalkingSkeletonFixture fixture =
                McpToolAdmissionWalkingSkeletonFixture.start(vertx, rateLimitConfig, rateLimiters);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        return fixture;
    }

    private HttpResponse<Buffer> callTool(int port, int requestId) throws Exception {
        return await(WebClient.wrap(rawClient)
                .post(port, "127.0.0.1", REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", TOOL_NAME)
                .sendBuffer(callBody(requestId)));
    }

    private RateLimiters rateLimiters(RecordingLocalRateLimitBackend backend, long capacity) {
        RateLimitPolicy policy = new RateLimitPolicy(
                POLICY_NAME,
                true,
                RateLimitMode.LOCAL,
                RateLimitFailureMode.OPEN,
                "r1",
                1L,
                new TokenBucketRateLimit(capacity, new GreedyRateLimitRefill(1L, Duration.ofMinutes(1))));
        return new RateLimiters(
                Set.of(policy), Map.of(RateLimitMode.LOCAL, backend), null, vertx, Set.of(), Optional::empty, true);
    }

    private static Buffer callBody(int requestId) {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", requestId)
                .put("method", "tools/call")
                .put("params", new JsonObject().put("_meta", meta).put("name", TOOL_NAME))
                .toBuffer();
    }

    private static JsonObject sseResult(String body) {
        assertThat(body).startsWith(SSE_PREFIX);
        JsonObject result = new JsonObject(body.substring(SSE_PREFIX.length()).stripTrailing()).getJsonObject("result");
        assertThat(result).isNotNull();
        return result;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /** Records only tokens a real local Bucket4j backend actually consumed. */
    private static final class RecordingLocalRateLimitBackend implements RateLimitBackend {
        private final RateLimitBackend delegate = LocalRateLimitBackendFactory.local(ignored -> 100L, 60_000L);
        private final AtomicLong consumedTokens = new AtomicLong();

        @Override
        public Future<RateLimitBackendResult> consume(RateLimitBackendRequest request) {
            return delegate.consume(request).onSuccess(result -> {
                if (result.consumed()) {
                    consumedTokens.addAndGet(request.cost());
                }
            });
        }

        long consumedTokenCount() {
            return consumedTokens.get();
        }
    }

    static final class McpToolAdmissionWalkingSkeletonFixture {
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

        private final HttpServer server;
        private final int port;
        private final CountingToolInvoker tool;
        private final List<McpRequestTerminalEvent> terminalEvents;

        private McpToolAdmissionWalkingSkeletonFixture(
                Vertx vertx, McpRateLimitConfig rateLimitConfig, Optional<RateLimiters> rateLimiters) throws Exception {
            this(vertx, rateLimitConfig, rateLimiters, new AnonymousIdentityResolver(), Optional.empty());
        }

        private McpToolAdmissionWalkingSkeletonFixture(
                Vertx vertx,
                McpRateLimitConfig rateLimitConfig,
                Optional<RateLimiters> rateLimiters,
                SecurityIdentityResolver identityResolver)
                throws Exception {
            this(vertx, rateLimitConfig, rateLimiters, identityResolver, Optional.empty());
        }

        private McpToolAdmissionWalkingSkeletonFixture(
                Vertx vertx,
                McpRateLimitConfig rateLimitConfig,
                Optional<RateLimiters> rateLimiters,
                SecurityIdentityResolver identityResolver,
                Optional<McpRequestLifecycleObserver> additionalObserver)
                throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName("vertique-test")
                    .serverVersion("1.0")
                    .rateLimit(rateLimitConfig)
                    .build();
            this.tool = new CountingToolInvoker();
            McpToolRegistry registry = McpToolRegistry.build(Set.of(tool));
            this.terminalEvents = new java.util.concurrent.CopyOnWriteArrayList<>();
            McpRequestLifecycleObserver lifecycleObserver = startedAt -> new McpRequestObservation() {
                @Override
                public void onTerminal(McpRequestTerminalObservation observation) {
                    terminalEvents.add(observation.event());
                }
            };
            Set<McpRequestLifecycleObserver> lifecycleObservers = new java.util.LinkedHashSet<>();
            lifecycleObservers.add(lifecycleObserver);
            additionalObserver.ifPresent(lifecycleObservers::add);
            RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
            McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                    Optional.empty(),
                    Optional.empty(),
                    Set.of(),
                    new SecurityEventEmitter(Set.of()),
                    NO_OP_CONTEXT_HOLDER,
                    securityRuntime,
                    Optional.empty()));
            HttpConfig httpConfig = HttpConfig.builder().idleTimeoutSeconds(60).build();
            McpToolAdmission admission = McpToolAdmission.create(config, registry, rateLimiters);
            McpRequestDispatcher dispatcher = new McpRequestDispatcher(
                    config,
                    securityRuntime,
                    lifecycleObservers,
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    httpConfig,
                    registry,
                    policyEnforcer,
                    admission,
                    NO_OP_CONTEXT_HOLDER,
                    new CorrelationContextFactory(Optional.empty()));
            McpRouterMount mount = new McpRouterMount(
                    config,
                    new McpServerConfigValidator(),
                    dispatcher,
                    Set.of(),
                    identityResolution(securityRuntime, identityResolver),
                    httpConfig,
                    registry);
            Router router = Router.router(vertx);
            router.route().handler(new RequestContextLifecycle());
            router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
            this.server = await(vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
            this.port = server.actualPort();
        }

        static McpToolAdmissionWalkingSkeletonFixture start(
                Vertx vertx, McpRateLimitConfig rateLimitConfig, Optional<RateLimiters> rateLimiters) throws Exception {
            return new McpToolAdmissionWalkingSkeletonFixture(vertx, rateLimitConfig, rateLimiters);
        }

        static McpToolAdmissionWalkingSkeletonFixture start(
                Vertx vertx,
                McpRateLimitConfig rateLimitConfig,
                Optional<RateLimiters> rateLimiters,
                SecurityIdentityResolver identityResolver)
                throws Exception {
            return new McpToolAdmissionWalkingSkeletonFixture(vertx, rateLimitConfig, rateLimiters, identityResolver);
        }

        static McpToolAdmissionWalkingSkeletonFixture start(
                Vertx vertx,
                McpRateLimitConfig rateLimitConfig,
                Optional<RateLimiters> rateLimiters,
                McpRequestLifecycleObserver additionalObserver)
                throws Exception {
            return new McpToolAdmissionWalkingSkeletonFixture(
                    vertx,
                    rateLimitConfig,
                    rateLimiters,
                    new AnonymousIdentityResolver(),
                    Optional.of(additionalObserver));
        }

        HttpServer server() {
            return server;
        }

        int port() {
            return port;
        }

        CountingToolInvoker tool() {
            return tool;
        }

        long toolInvocationCount() {
            return tool.invocationCount();
        }

        List<McpRequestTerminalEvent> terminalEvents() {
            return terminalEvents;
        }

        private static IdentityResolutionMiddleware identityResolution(
                SecurityRuntime securityRuntime, SecurityIdentityResolver identityResolver) {
            return new IdentityResolutionMiddleware(
                    Set.of(identityResolver),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    new SecurityEventEmitter(Set.of()),
                    securityRuntime,
                    NO_OP_CONTEXT_HOLDER);
        }
    }

    private static final class CountingToolInvoker implements McpToolInvoker {
        private static final McpToolDescriptor DESCRIPTOR = new McpToolDescriptor(
                TOOL_NAME,
                null,
                "Rate-limit admission fixture tool.",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\",\"additionalProperties\":false}",
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));

        private final AtomicLong invocationCount = new AtomicLong();

        @Override
        public McpToolDescriptor descriptor() {
            return DESCRIPTOR;
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
                    invocationCount.incrementAndGet();
                    return Future.succeededFuture(McpToolResult.text("admitted"));
                }
            };
        }

        long invocationCount() {
            return invocationCount.get();
        }
    }

    private record AnonymousIdentityResolver() implements SecurityIdentityResolver {
        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
        }
    }

    private static final class RecordingSecurityRuntime implements SecurityRuntime {
        private volatile SecurityContext current;

        @Override
        public SecurityContext current() {
            return current;
        }

        @Override
        public ContextHolder.Scope bindCurrent(SecurityContext context) {
            current = context;
            return () -> {};
        }

        @Override
        public void clearCurrent() {
            current = null;
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
            return null;
        }
    }
}
