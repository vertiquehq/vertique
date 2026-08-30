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
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Framework wiring for {@link McpSchemaStartupTest}: composition, descriptors, and fixture invokers only. */
final class McpSchemaStartupTestFixture {

    private static final McpToolAnnotations ANNOTATIONS = new McpToolAnnotations(true, false, true, false);
    private static final McpToolAccess PERMIT_ALL = new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null);
    private static final McpToolAccess DENY_ALL = new McpToolAccess(McpAccessMode.DENY_ALL, List.of(), null);
    private static final McpToolAccess RESTRICTED =
            new McpToolAccess(McpAccessMode.RESTRICTED, List.of("operator"), null);

    /** A closed, argument-free object schema — valid and vertx-json-schema-compilable. */
    private static final String CLOSED_OBJECT_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false}";

    private McpSchemaStartupTestFixture() {}

    /** The outcome of one composition attempt: startup error count and observed mounted-route count. */
    record ComposeResult(int startupErrorCount, int mountedRouteCount) {}

    /**
     * Composes one server exactly once: builds the immutable tool registry, validates it against the
     * configuration and the registered handlers, and — only when both steps succeed — adds one
     * placeholder route to a real {@link Router}, standing in for the point at which the real mount
     * begins adding protocol routes. The returned mounted-route count therefore reflects whether
     * mounting was ever reached, not merely whether an exception was thrown.
     *
     * @param vertx the owning Vert.x instance backing the composed {@link Router}
     * @param invokers the contributed {@code @IntoSet} invoker set
     * @param config the bounded server configuration
     * @param routeAuthHandlers every registered optional-authentication-capable handler
     * @return the composition outcome
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
            return new ComposeResult(0, router.getRoutes().size());
        } catch (ConfigurationException failure) {
            return new ComposeResult(1, router.getRoutes().size());
        }
    }

    /** An enabled configuration naming a valid server identity and no authentication scheme. */
    static McpServerConfig enabledPublicOnlyConfig() {
        return McpServerConfig.builder()
                .enabled(true)
                .serverName("vertique-test")
                .serverVersion("1.0")
                .build();
    }

    /** Two distinct generated-invoker contributions that both publish tool name {@code duplicate.tool}. */
    static Set<McpToolInvoker> twoInvokersSharingOneName() {
        return contributions(
                invoker(descriptor("duplicate.tool", PERMIT_ALL)), invoker(descriptor("duplicate.tool", PERMIT_ALL)));
    }

    /**
     * One fixture invoker whose descriptor carries a syntactically invalid JSON Schema document, so
     * compiling it into the owned {@link McpSchemaRegistry} fails deterministically — the "or
     * validator" half of the frozen "invalid descriptor, unsupported schema/mapping contract, or
     * validator" startup rule (§4.2).
     */
    static McpToolInvoker invokerWithUncompilableSchema(String name) {
        McpToolDescriptor descriptor = new McpToolDescriptor(
                name,
                null,
                "Fixture tool with an uncompilable schema.",
                ANNOTATIONS,
                "not-json-at-all",
                null,
                PERMIT_ALL);
        return invoker(descriptor);
    }

    /** A public tool and a deny-all tool — both reachable-or-inert without authentication. */
    static Set<McpToolInvoker> publicAndDenyAllInvokers() {
        return contributions(
                invoker(descriptor("public.tool", PERMIT_ALL)), invoker(descriptor("closed.tool", DENY_ALL)));
    }

    /** One {@code @RolesAllowed}-equivalent restricted tool. */
    static Set<McpToolInvoker> restrictedInvokers() {
        return contributions(invoker(descriptor("restricted.tool", RESTRICTED)));
    }

    /** Two valid tools, contributed in an order the reverse of their sorted global name order. */
    static Set<McpToolInvoker> twoToolsReversedInsertionOrder() {
        return contributions(invoker(descriptor("zzz.tool", PERMIT_ALL)), invoker(descriptor("aaa.tool", PERMIT_ALL)));
    }

    private static McpToolDescriptor descriptor(String name, McpToolAccess access) {
        return new McpToolDescriptor(name, null, "Fixture tool.", ANNOTATIONS, CLOSED_OBJECT_SCHEMA, null, access);
    }

    private static McpToolInvoker invoker(McpToolDescriptor descriptor) {
        return new FixtureToolInvoker(descriptor);
    }

    /** Preserves the declared contribution order inside the {@code @IntoSet} contribution set. */
    private static Set<McpToolInvoker> contributions(McpToolInvoker... invokers) {
        return new LinkedHashSet<>(List.of(invokers));
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
}
