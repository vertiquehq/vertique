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
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.ResourceRef;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Framework wiring for {@link McpActionAuthorizerStartupTest} (R01 TP-002, issue #421). */
final class McpActionAuthorizerStartupTestFixture {

    private static final McpToolAnnotations ANNOTATIONS = new McpToolAnnotations(true, false, true, false);
    private static final String CLOSED_OBJECT_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false}";
    private static final ActionRef SAMPLE_ACTION = ActionRef.parse("mcp.tool.invoke");
    private static final String SCHEME_NAME = "test-scheme";

    private McpActionAuthorizerStartupTestFixture() {}

    /** The outcome of one composition attempt, including the classified failure when one occurred. */
    record ComposeResult(
            int startupErrorCount,
            int mountedRouteCount,
            @Nullable ConfigurationException failure) {}

    /**
     * Composes one server exactly once, mirroring {@link McpOptionalCapabilityStartupTestFixture#compose}:
     * builds the registry, validates it against {@code authorizer}, and adds one placeholder "mount"
     * route only after both steps succeed, so the mounted-route count reflects whether mounting was
     * ever reached — not merely whether an exception was thrown.
     *
     * <p>{@code config} always selects {@value #SCHEME_NAME} with a matching optional-capable {@link
     * RouteAuthHandler} already installed, so the pre-existing registry-visibility rule (§4.5) — which
     * would otherwise reject <em>any</em> {@link McpAccessMode#RESTRICTED} tool (role- or action-gated)
     * under an unconfigured scheme — never fires here. This isolates the one concern this fixture
     * exists to test: the {@code @RequiresAction}-without-{@code Authorizer} gate (issue #421).
     */
    static ComposeResult compose(
            Vertx vertx, Set<McpToolInvoker> invokers, McpServerConfig config, Optional<Authorizer> authorizer) {
        Router router = Router.router(vertx);
        try {
            McpToolRegistry registry = McpToolRegistry.build(invokers);
            new McpServerConfigValidator()
                    .validate(config, Set.of(new OptionalRouteAuthHandler(SCHEME_NAME)), registry, authorizer);
            router.route().handler(RoutingContext::next);
            return new ComposeResult(0, router.getRoutes().size(), null);
        } catch (ConfigurationException failure) {
            return new ComposeResult(1, router.getRoutes().size(), failure);
        }
    }

    /** An enabled configuration selecting {@value #SCHEME_NAME} as its authentication scheme. */
    static McpServerConfig enabledConfig() {
        return McpServerConfig.builder()
                .enabled(true)
                .serverName("vertique-test")
                .serverVersion("1.0")
                .authenticationScheme(SCHEME_NAME)
                .build();
    }

    /** One {@code @RequiresAction}-equivalent tool (action-only, no roles). */
    static Set<McpToolInvoker> oneRequiresActionInvoker(String toolName) {
        McpToolAccess actionOnly = new McpToolAccess(McpAccessMode.RESTRICTED, List.of(), SAMPLE_ACTION);
        McpToolDescriptor descriptor = new McpToolDescriptor(
                toolName, null, "R01 TP-002 fixture tool.", ANNOTATIONS, CLOSED_OBJECT_SCHEMA, null, actionOnly);
        return Set.of(new FixtureToolInvoker(descriptor));
    }

    /** A trivial installed {@link Authorizer} that always permits — its decisions are never asserted on. */
    static Authorizer installedAuthorizer() {
        return new PermissiveAuthorizer();
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
            throw new AssertionError("an R01 TP-002 startup-validation fixture invoker must never be prepared");
        }
    }

    /** A {@value #SCHEME_NAME}-named handler exposing the optional-authentication capability. */
    private static final class OptionalRouteAuthHandler implements RouteAuthHandler {
        private final String scheme;

        private OptionalRouteAuthHandler(String scheme) {
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

        @Override
        public Optional<Handler<RoutingContext>> createOptionalHandler() {
            return Optional.of(RoutingContext::next);
        }
    }

    /** An {@link Authorizer} standing in for an installed authorization engine; never actually invoked. */
    private static final class PermissiveAuthorizer implements Authorizer {
        @Override
        public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
            throw new AssertionError("this fixture only tests mount-time presence, not runtime evaluation");
        }

        @Override
        public Future<AuthorizationDecision> authorize(SecurityContext ctx, ActionRef action, ResourceRef resource) {
            throw new AssertionError("this fixture only tests mount-time presence, not runtime evaluation");
        }
    }
}
