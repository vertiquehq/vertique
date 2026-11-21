// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.rest.core.router.RouterCustomizer;
import io.vertx.ext.web.Router;

/**
 * Makes MCP answer every failure of a request it admitted.
 *
 * <p>A failure that a handler of the MCP router raises restarts failure-handler lookup at the main
 * router, so without this customizer the failure handler of an earlier mount that matches the MCP path,
 * or an application failure handler on the main router, takes the failure and writes its own error
 * response. MCP then never settles the request it admitted. This customizer registers MCP's failure
 * handler on the main router ahead of every mount and every application customizer, where it takes
 * only a failure of a request {@link McpRequestDispatcher#begin} admitted in the current routing pass.
 *
 * <p>It also registers, on the same path, the handler that starts each pass, so a request that was
 * rerouted out of the MCP mount, whose later failure is not MCP's, is not mistaken for an admitted one.
 */
final class McpFailureOwnership implements RouterCustomizer {
    private final McpServerConfig config;
    private final McpRequestDispatcher dispatcher;

    McpFailureOwnership(McpServerConfig config, McpRequestDispatcher dispatcher) {
        this.config = config;
        this.dispatcher = dispatcher;
    }

    /** {@inheritDoc} */
    @Override
    public ExtensionPhase phase() {
        return ExtensionPhase.SYSTEM_FIRST;
    }

    /** {@inheritDoc} */
    @Override
    public int priority() {
        return Integer.MIN_VALUE;
    }

    /** {@inheritDoc} */
    @Override
    public void customize(Router router) {
        if (!config.enabled()) {
            return;
        }
        // Explicit orders put both routes ahead of every route that takes its order from registration,
        // ROOT middleware included, so a pass is marked as started before anything else of the pass runs.
        router.route(config.mountPath()).order(Integer.MIN_VALUE).handler(dispatcher::startPass);
        router.route(config.mountPath()).order(Integer.MIN_VALUE + 1).failureHandler(dispatcher::handleAdmittedFailure);
    }
}
