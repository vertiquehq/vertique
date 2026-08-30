// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.rest.core.security.RouteAuthHandler;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Framework wiring for {@link McpOptionalCapabilityStartupTest}: composition and doubles only. */
final class McpOptionalCapabilityStartupTestFixture {

    private static final McpToolAnnotations ANNOTATIONS = new McpToolAnnotations(true, false, true, false);
    private static final String CLOSED_OBJECT_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false}";

    private McpOptionalCapabilityStartupTestFixture() {}

    /** The outcome of one composition attempt, including the classified failure when one occurred. */
    record ComposeResult(
            int startupErrorCount,
            int mountedRouteCount,
            @Nullable ConfigurationException failure) {}

    /**
     * Composes one server exactly once, mirroring {@code McpSchemaStartupTestFixture#compose}: builds
     * the registry, validates it, and adds one placeholder "mount" route only after both steps
     * succeed, so the mounted-route count reflects whether mounting was ever reached.
     */
    static ComposeResult compose(
            Vertx vertx,
            Set<McpToolInvoker> invokers,
            McpServerConfig config,
            Set<RouteAuthHandler> routeAuthHandlers) {
        Router router = Router.router(vertx);
        try {
            McpToolRegistry registry = McpToolRegistry.build(invokers);
            new McpServerConfigValidator().validate(config, routeAuthHandlers, registry);
            router.route().handler(RoutingContext::next);
            return new ComposeResult(0, router.getRoutes().size(), null);
        } catch (ConfigurationException failure) {
            return new ComposeResult(1, router.getRoutes().size(), failure);
        }
    }

    /** One {@code @RolesAllowed}-equivalent restricted tool. */
    static Set<McpToolInvoker> oneRolesAllowedInvoker() {
        McpToolAccess rolesAllowed = new McpToolAccess(McpAccessMode.RESTRICTED, List.of("operator"), null);
        McpToolDescriptor descriptor = new McpToolDescriptor(
                "restricted.tool",
                null,
                "Fixture restricted tool.",
                ANNOTATIONS,
                CLOSED_OBJECT_SCHEMA,
                null,
                rolesAllowed);
        return Set.of(new FixtureToolInvoker(descriptor));
    }

    /** An enabled configuration selecting {@code scheme} as its authentication scheme. */
    static McpServerConfig enabledConfigWithScheme(String scheme) {
        return McpServerConfig.builder()
                .enabled(true)
                .serverName("vertique-test")
                .serverVersion("1.0")
                .authenticationScheme(scheme)
                .build();
    }

    /**
     * One registered handler for {@code scheme} that does not expose the optional-authentication
     * capability — it relies on {@link RouteAuthHandler#createOptionalHandler()}'s interface default
     * ({@code Optional.empty()}), exactly the "selected scheme lacks the optional capability" case.
     */
    static Set<RouteAuthHandler> nonOptionalSchemeHandlers(String scheme) {
        return Set.of(new NonOptionalRouteAuthHandler(scheme));
    }

    /** A generated-invoker double that publishes a fixed descriptor and is never prepared or invoked. */
    private static final class FixtureToolInvoker implements McpToolInvoker {

        private final McpToolDescriptor descriptor;

        private FixtureToolInvoker(McpToolDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public McpToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            throw new AssertionError("a T010 startup-validation fixture invoker must never be prepared");
        }
    }

    /** A required-authentication-only handler: {@code createOptionalHandler()} keeps the interface default. */
    private static final class NonOptionalRouteAuthHandler implements RouteAuthHandler {

        private final String scheme;

        private NonOptionalRouteAuthHandler(String scheme) {
            this.scheme = scheme;
        }

        @Override
        public String schemeName() {
            return scheme;
        }

        @Override
        public Handler<RoutingContext> createHandler() {
            return RoutingContext::next;
        }
    }
}
