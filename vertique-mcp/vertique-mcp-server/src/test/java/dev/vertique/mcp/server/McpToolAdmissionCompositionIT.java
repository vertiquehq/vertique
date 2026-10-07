// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.ratelimit.GreedyRateLimitRefill;
import dev.vertique.ratelimit.RateLimitFailureMode;
import dev.vertique.ratelimit.RateLimitMode;
import dev.vertique.ratelimit.RateLimitPolicy;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.TokenBucketRateLimit;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.RequestOriginConfig;
import dev.vertique.security.authz.Authorizer;
import io.vertx.core.Vertx;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** T002 TP-002 — production Dagger composition validates MCP rate-limit references before mounts. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpToolAdmissionCompositionIT {

    private static final String TOOL_NAME = "orders.create";
    private static final String POLICY_NAME = "mcp-write";

    private final Vertx vertx = Vertx.vertx();

    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close().onComplete(ignored -> closed.complete(null));
        closed.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("shouldFailCompositionOnEachOfTheFourNamedConditions")
    void shouldFailCompositionOnEachOfTheFourNamedConditions() {
        McpServerConfig unknownTool = config(tool("missing.tool", POLICY_NAME, 1L));
        assertThatThrownBy(() -> withRateLimiters(unknownTool, rateLimiters(POLICY_NAME, 10L))
                        .routerMounts())
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("mcp.rateLimit.tools[missing.tool]");

        McpServerConfig unknownPolicy = config(tool(TOOL_NAME, "unknown-policy", 1L));
        assertThatThrownBy(() -> withRateLimiters(unknownPolicy, rateLimiters(POLICY_NAME, 10L))
                        .routerMounts())
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("unknown-policy");

        McpServerConfig missingRuntime = config(tool(TOOL_NAME, POLICY_NAME, 1L));
        assertThatThrownBy(() -> withoutRateLimiters(missingRuntime).routerMounts())
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("mcp.rateLimit")
                .hasMessageContaining("RateLimitCoreModule");

        McpServerConfig excessiveCost = config(tool(TOOL_NAME, POLICY_NAME, 100L));
        assertThatThrownBy(() -> withRateLimiters(excessiveCost, rateLimiters(POLICY_NAME, 10L))
                        .routerMounts())
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("mcp.rateLimit.tools[orders.create].cost")
                .hasMessageContaining("100")
                .hasMessageContaining(POLICY_NAME)
                .hasMessageContaining("10");

        assertThat(withRateLimiters(config(tool(TOOL_NAME, POLICY_NAME, 1L)), rateLimiters(POLICY_NAME, 10L))
                        .routerMounts())
                .hasSize(1);
        assertThat(withoutRateLimiters(McpServerConfig.defaults()).routerMounts())
                .hasSize(1);
    }

    private WithRateLimitersComponent withRateLimiters(McpServerConfig config, RateLimiters rateLimiters) {
        return DaggerMcpToolAdmissionCompositionIT_WithRateLimitersComponent.factory()
                .create(config, rateLimiters);
    }

    private WithoutRateLimitersComponent withoutRateLimiters(McpServerConfig config) {
        return DaggerMcpToolAdmissionCompositionIT_WithoutRateLimitersComponent.factory()
                .create(config);
    }

    private static McpServerConfig config(McpToolRateLimitConfig tool) {
        return McpServerConfig.builder()
                .rateLimit(new McpRateLimitConfig(
                        null,
                        dev.vertique.ratelimit.spi.RateLimitSubject.EFFECTIVE_PRINCIPAL,
                        dev.vertique.ratelimit.spi.AnonymousRateLimitPolicy.SHARED_BUCKET,
                        List.of(tool)))
                .build();
    }

    private static McpToolRateLimitConfig tool(String toolName, String policyName, long cost) {
        return new McpToolRateLimitConfig(toolName, policyName, null, null, cost);
    }

    private RateLimiters rateLimiters(String policyName, long capacity) {
        RateLimitPolicy policy = new RateLimitPolicy(
                policyName,
                false,
                RateLimitMode.LOCAL,
                RateLimitFailureMode.OPEN,
                "r1",
                1L,
                new TokenBucketRateLimit(capacity, new GreedyRateLimitRefill(1L, Duration.ofMinutes(1))));
        return new RateLimiters(Set.of(policy), Map.of(), null, vertx, Set.of(), Optional::empty, false);
    }

    @Singleton
    @Component(modules = {McpServerModule.class, CompositionSupportModule.class})
    interface WithoutRateLimitersComponent {
        Set<RouterMount> routerMounts();

        @Component.Factory
        interface Factory {
            WithoutRateLimitersComponent create(@BindsInstance McpServerConfig config);
        }
    }

    @Singleton
    @Component(modules = {McpServerModule.class, CompositionSupportModule.class})
    interface WithRateLimitersComponent {
        Set<RouterMount> routerMounts();

        @Component.Factory
        interface Factory {
            WithRateLimitersComponent create(
                    @BindsInstance McpServerConfig config, @BindsInstance RateLimiters rateLimiters);
        }
    }

    @Module
    abstract static class CompositionSupportModule {
        private CompositionSupportModule() {}

        @Provides
        static McpRequestDispatcher dispatcher(McpToolAdmission admission) {
            McpRequestDispatcher dispatcher = mock(McpRequestDispatcher.class);
            when(dispatcher.securityRuntime()).thenReturn(mock(SecurityRuntime.class));
            return dispatcher;
        }

        @Provides
        static IdentityResolutionMiddleware identityResolutionMiddleware() {
            return mock(IdentityResolutionMiddleware.class);
        }

        @Provides
        static HttpConfig httpConfig() {
            return HttpConfig.builder().build();
        }

        @Provides
        static RequestOriginConfig requestOriginConfig() {
            return RequestOriginConfig.defaults();
        }

        @Provides
        static Optional<Authorizer> authorizer() {
            return Optional.empty();
        }

        @Provides
        static Set<RouteAuthHandler> routeAuthHandlers() {
            return Set.of();
        }

        @Provides
        @IntoSet
        static McpToolInvoker toolInvoker() {
            return new FixtureToolInvoker();
        }
    }

    private static final class FixtureToolInvoker implements McpToolInvoker {
        private static final McpToolDescriptor DESCRIPTOR = new McpToolDescriptor(
                TOOL_NAME,
                null,
                "T002 composition fixture tool.",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\",\"additionalProperties\":false}",
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));

        @Override
        public McpToolDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            throw new AssertionError("composition must fail before a fixture tool is prepared");
        }
    }
}
