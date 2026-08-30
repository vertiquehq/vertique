// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
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
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.security.verification.CustomVerificationSource;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.impl.UserContextInternal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

record McpGoClientInteropITFixture(
        HttpServer server,
        AtomicInteger publicInvocations,
        AtomicInteger restrictedInvocations,
        AtomicInteger absentCredentials,
        AtomicInteger validCredentials,
        AtomicInteger invalidCredentials) {

    static final String PUBLIC_TOOL = "interop.public";
    static final String RESTRICTED_TOOL = "interop.restricted";
    static final String PUBLIC_RESULT = "public tool called";
    static final String RESTRICTED_RESULT = "restricted tool called by alice";
    static final String BEARER_ALICE = "Bearer alice";

    private static final McpToolAnnotations ANNOTATIONS = new McpToolAnnotations(true, false, true, false);
    private static final String CLOSED_OBJECT_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false}";

    static Future<McpGoClientInteropITFixture> start(Vertx vertx) {
        AtomicInteger publicInvocations = new AtomicInteger();
        AtomicInteger restrictedInvocations = new AtomicInteger();
        AtomicInteger absentCredentials = new AtomicInteger();
        AtomicInteger validCredentials = new AtomicInteger();
        AtomicInteger invalidCredentials = new AtomicInteger();
        McpServerConfig config = McpServerConfig.builder()
                .enabled(true)
                .serverName("vertique-go-interop")
                .serverVersion("1.0")
                .authenticationScheme("bearer")
                .build();
        McpToolRegistry registry = McpToolRegistry.build(Set.of(
                tool(
                        PUBLIC_TOOL,
                        new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null),
                        PUBLIC_RESULT,
                        publicInvocations),
                tool(
                        RESTRICTED_TOOL,
                        new McpToolAccess(McpAccessMode.RESTRICTED, List.of("ops"), null),
                        RESTRICTED_RESULT,
                        restrictedInvocations)));
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
        McpRouterMount mount = new McpRouterMount(
                config,
                new McpServerConfigValidator(),
                new McpRequestDispatcher(
                        config,
                        securityRuntime,
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        httpConfig,
                        registry,
                        policyEnforcer,
                        NO_OP_CONTEXT_HOLDER,
                        new CorrelationContextFactory(Optional.empty())),
                Set.of(new BearerRouteAuthHandler(absentCredentials, validCredentials, invalidCredentials)),
                identityResolution(securityRuntime),
                httpConfig,
                registry);
        return mount.createRouter(vertx).compose(mcpRouter -> {
            Router router = Router.router(vertx);
            router.route().handler(new RequestContextLifecycle());
            router.route(config.mountPath()).subRouter(mcpRouter);
            return vertx.createHttpServer()
                    .requestHandler(router)
                    .listen(0, "127.0.0.1")
                    .map(server -> new McpGoClientInteropITFixture(
                            server,
                            publicInvocations,
                            restrictedInvocations,
                            absentCredentials,
                            validCredentials,
                            invalidCredentials));
        });
    }

    private static McpToolInvoker tool(
            String name, McpToolAccess access, String resultText, AtomicInteger invocationCount) {
        McpToolDescriptor descriptor = new McpToolDescriptor(
                name, null, "Go interop fixture tool " + name, ANNOTATIONS, CLOSED_OBJECT_SCHEMA, null, access);
        return new McpToolInvoker() {
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
                        invocationCount.incrementAndGet();
                        return Future.succeededFuture(McpToolResult.text(resultText));
                    }
                };
            }
        };
    }

    private static IdentityResolutionMiddleware identityResolution(SecurityRuntime securityRuntime) {
        return new IdentityResolutionMiddleware(
                Set.of(new SubjectRoleIdentityResolver()),
                Optional.of(new DefaultSecurityClaimMapper()),
                new SecurityEventEmitter(Set.of()),
                securityRuntime,
                NO_OP_CONTEXT_HOLDER);
    }

    String serverUrl() {
        return "http://127.0.0.1:" + server.actualPort() + "/mcp/";
    }

    void resetObservations() {
        publicInvocations.set(0);
        restrictedInvocations.set(0);
        absentCredentials.set(0);
        validCredentials.set(0);
        invalidCredentials.set(0);
    }

    private record SubjectRoleIdentityResolver() implements SecurityIdentityResolver {

        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            if (context.evidence().isEmpty()) {
                return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
            }
            Object subject = context.evidence().getFirst().safeAttributes().get("sub");
            return Future.succeededFuture(Optional.of(
                    SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, String.valueOf(subject), Map.of()))));
        }
    }

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

    private record BearerRouteAuthHandler(
            AtomicInteger absentCredentials, AtomicInteger validCredentials, AtomicInteger invalidCredentials)
            implements RouteAuthHandler {

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
                    absentCredentials.incrementAndGet();
                    context.next();
                    return;
                }
                if (!BEARER_ALICE.equals(credential)) {
                    invalidCredentials.incrementAndGet();
                    context.fail(401);
                    return;
                }
                validCredentials.incrementAndGet();
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
