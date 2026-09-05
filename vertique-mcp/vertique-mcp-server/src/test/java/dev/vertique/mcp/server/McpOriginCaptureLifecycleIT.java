// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.correlation.CorrelationContextFactory;
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
import dev.vertique.rest.security.CredentialRejectionReporter;
import dev.vertique.rest.security.DefaultCredentialRejectionReporter;
import dev.vertique.rest.security.DefaultSecurityClaimMapper;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.RequestOriginCapturer;
import dev.vertique.rest.security.RequestOriginConfig;
import dev.vertique.rest.security.RestAuthenticationEvidence;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.events.CredentialRejectedEvent;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.origin.RequestOrigin;
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
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
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

/** Proves trusted origin capture and terminal audit carriage through the real MCP HTTP mount. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpOriginCaptureLifecycleIT {
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String TRUSTED_CLIENT_IP = "198.51.100.25";

    private final Vertx vertx = Vertx.vertx();
    private HttpServer server;
    private HttpClient rawClient;

    @AfterEach
    void closeResources() throws Exception {
        Future<Void> serverClose = server == null ? Future.succeededFuture() : server.close();
        Future<Void> clientClose = rawClient == null ? Future.succeededFuture() : rawClient.close();
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Future.join(serverClose, clientClose)
                .onComplete(ignored -> vertx.close().onComplete(result -> {
                    if (result.failed()) {
                        closed.completeExceptionally(result.cause());
                    } else {
                        closed.complete(null);
                    }
                }));
        closed.get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("captures trusted origin before admission and carries the same value into security and terminal")
    void shouldCaptureOriginBeforeCheapAdmissionAndCarrySameValueIntoSecurityAndTerminal() throws Exception {
        Fixture fixture = startFixture(Set.of("127.0.0.1/32"));

        HttpResponse<Buffer> response = call(fixture.port(), "Bearer valid", TRUSTED_CLIENT_IP, new JsonObject());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(fixture.identityOrigin.get())
                .as("identity resolution runs after the mount's capture handler")
                .isNotNull();
        assertThat(fixture.terminals).hasSize(1);
        McpRequestTerminalEvent terminal = fixture.terminals.getFirst();
        assertThat(terminal.origin()).isSameAs(fixture.identityOrigin.get());
        assertThat(terminal.security()).isNotNull();
        assertThat(terminal.security().origin()).containsSame(terminal.origin());
        assertThat(terminal.origin().clientIp()).isEqualTo(TRUSTED_CLIENT_IP);
    }

    @Test
    @DisplayName("retains captured origin on authentication rejection without a security snapshot")
    void shouldRetainOriginOnAuthenticationRejectionWithoutSecuritySnapshot() throws Exception {
        Fixture fixture = startFixture(Set.of("127.0.0.1/32"));

        HttpResponse<Buffer> response = call(fixture.port(), "Bearer invalid", TRUSTED_CLIENT_IP, new JsonObject());

        assertThat(response.statusCode())
                .as("authentication rejection body: %s", response.bodyAsString())
                .isEqualTo(401);
        assertThat(fixture.rejections).hasSize(1);
        assertThat(fixture.terminals).hasSize(1);
        CredentialRejectedEvent rejection = fixture.rejections.getFirst();
        McpRequestTerminalEvent terminal = fixture.terminals.getFirst();
        assertThat(rejection.origin()).containsSame(terminal.origin());
        assertThat(terminal.origin()).isNotNull();
        assertThat(terminal.outcome()).isEqualTo(dev.vertique.mcp.lifecycle.McpOutcome.REJECTED);
        assertThat(terminal.errorType()).isEqualTo(dev.vertique.mcp.lifecycle.McpErrorType.AUTHENTICATION);
        assertThat(terminal.security()).isNull();
    }

    @Test
    @DisplayName("uses only the trusted capturer for untrusted forwarded and body Origin values")
    void shouldIgnoreBodyOriginAndUntrustedForwardedHeaders() throws Exception {
        Fixture fixture = startFixture(Set.of());

        HttpResponse<Buffer> response = call(
                fixture.port(),
                "Bearer valid",
                TRUSTED_CLIENT_IP,
                new JsonObject().put("Origin", "attacker-body-origin"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(fixture.terminals).hasSize(1);
        RequestOrigin origin = fixture.terminals.getFirst().origin();
        assertThat(origin.clientIp()).isEqualTo(origin.remoteIp());
        assertThat(origin.clientIp()).isNotEqualTo(TRUSTED_CLIENT_IP);
        assertThat(origin.forwardedFor()).containsExactly(TRUSTED_CLIENT_IP);
    }

    private Fixture startFixture(Set<String> trustedProxyCidrs) throws Exception {
        ContextHolder contextHolder = new DefaultContextHolder();
        RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
        List<McpRequestTerminalEvent> terminals = new CopyOnWriteArrayList<>();
        List<CredentialRejectedEvent> rejections = new CopyOnWriteArrayList<>();
        AtomicReference<RequestOrigin> identityOrigin = new AtomicReference<>();
        SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of(new SecurityEventObserver() {
            @Override
            public Future<Void> onCredentialRejected(CredentialRejectedEvent event) {
                rejections.add(event);
                return Future.succeededFuture();
            }
        }));
        CredentialRejectionReporter reporter = new DefaultCredentialRejectionReporter(contextHolder, emitter);
        IdentityResolutionMiddleware identity = new IdentityResolutionMiddleware(
                Set.of(new RecordingIdentityResolver(identityOrigin)),
                Optional.of(new DefaultSecurityClaimMapper()),
                emitter,
                securityRuntime,
                contextHolder);
        McpServerConfig config = McpServerConfig.builder()
                .enabled(true)
                .serverName("origin-capture-test")
                .serverVersion("1.0")
                .authenticationScheme("test")
                .build();
        HttpConfig httpConfig = HttpConfig.builder().idleTimeoutSeconds(60).build();
        McpToolRegistry registry = McpToolRegistry.build(Set.of(new TestTool()));
        McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                emitter,
                contextHolder,
                securityRuntime,
                Optional.empty()));
        McpRequestLifecycleObserver terminalObserver = startedAt -> new McpRequestObservation() {
            @Override
            public void onTerminal(McpRequestTerminalObservation observation) {
                terminals.add(observation.event());
            }
        };
        McpRequestDispatcher dispatcher = new McpRequestDispatcher(
                config,
                securityRuntime,
                Set.of(terminalObserver),
                Set.<McpRequestCompletedListener>of(),
                Set.of(),
                Set.of(),
                httpConfig,
                registry,
                policyEnforcer,
                contextHolder,
                new CorrelationContextFactory(Optional.empty()));
        McpRouterMount mount = new McpRouterMount(
                config,
                new McpServerConfigValidator(),
                dispatcher,
                Set.of(new TestAuthHandler(reporter)),
                identity,
                httpConfig,
                registry,
                new RequestOriginCapturer(new RequestOriginConfig(trustedProxyCidrs, 16, false, false)),
                Optional.empty());
        Router router = Router.router(vertx);
        router.route().handler(new RequestContextLifecycle());
        router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
        server = await(vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
        rawClient = vertx.createHttpClient();
        return new Fixture(server.actualPort(), terminals, rejections, identityOrigin);
    }

    private HttpResponse<Buffer> call(int port, String authorization, String forwardedFor, JsonObject extraParams)
            throws Exception {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params = new JsonObject().put("_meta", meta).mergeIn(extraParams);
        Buffer body = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "server/discover")
                .put("params", params)
                .toBuffer();
        return await(WebClient.wrap(rawClient)
                .post(port, "127.0.0.1", REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "server/discover")
                .putHeader("Mcp-Name", "server/discover")
                .putHeader("Authorization", authorization)
                .putHeader("X-Forwarded-For", forwardedFor)
                .sendBuffer(body));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private record Fixture(
            int port,
            List<McpRequestTerminalEvent> terminals,
            List<CredentialRejectedEvent> rejections,
            AtomicReference<RequestOrigin> identityOrigin) {}

    private record RecordingIdentityResolver(AtomicReference<RequestOrigin> origin)
            implements SecurityIdentityResolver {
        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            origin.set(context.origin().orElseThrow());
            return Future.succeededFuture(
                    Optional.of(SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "origin-test", Map.of()))));
        }
    }

    private static final class TestAuthHandler implements RouteAuthHandler {
        private final CredentialRejectionReporter reporter;

        private TestAuthHandler(CredentialRejectionReporter reporter) {
            this.reporter = reporter;
        }

        @Override
        public String schemeName() {
            return "test";
        }

        @Override
        public Handler<RoutingContext> createHandler() {
            return context -> context.fail(401);
        }

        @Override
        public Optional<Handler<RoutingContext>> createOptionalHandler() {
            return Optional.of(context -> {
                if ("Bearer invalid".equals(context.request().getHeader("Authorization"))) {
                    reporter.report(
                            context,
                            DefaultAuthMethod.jwt(),
                            Optional.empty(),
                            Optional.empty(),
                            "invalid_credential",
                            Map.of());
                    context.fail(401);
                    return;
                }
                RestAuthenticationEvidence.append(
                        context,
                        new AuthenticationEvidence(
                                DefaultAuthMethod.jwt(),
                                Optional.of("origin-test"),
                                Instant.now(),
                                Optional.empty(),
                                new CustomVerificationSource("test", Map.of()),
                                Map.of()));
                ((UserContextInternal) context.userContext()).setUser(User.create(new JsonObject()));
                context.next();
            });
        }
    }

    private static final class TestTool implements McpToolInvoker {
        private static final McpToolDescriptor DESCRIPTOR = new McpToolDescriptor(
                "origin.test",
                null,
                "Origin capture fixture tool.",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\",\"additionalProperties\":true}",
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));

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
                    return Future.succeededFuture(McpToolResult.text("ok"));
                }
            };
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
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
            return null;
        }
    }
}
