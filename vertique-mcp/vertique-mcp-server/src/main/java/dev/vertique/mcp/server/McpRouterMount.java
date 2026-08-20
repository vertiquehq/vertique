// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.router.MountMeta;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import java.util.Set;

/** Installs the one validated MCP Router mount owned by the server module. */
final class McpRouterMount implements RouterMount {
    private final McpServerConfig config;
    private final McpServerConfigValidator configValidator;
    private final McpRequestDispatcher dispatcher;
    private final McpIdentityEstablisher identityEstablisher;
    private final HttpConfig httpConfig;

    McpRouterMount(
            McpServerConfig config,
            McpServerConfigValidator configValidator,
            McpRequestDispatcher dispatcher,
            Set<RouteAuthHandler> routeAuthHandlers,
            IdentityResolutionMiddleware identityResolutionMiddleware,
            HttpConfig httpConfig) {
        this.config = config;
        this.configValidator = configValidator;
        this.dispatcher = dispatcher;
        this.httpConfig = httpConfig;
        configValidator.validate(config, routeAuthHandlers);
        this.identityEstablisher = new McpIdentityEstablisher(config, routeAuthHandlers, identityResolutionMiddleware);
    }

    /** {@inheritDoc} */
    @Override
    public String mountPath() {
        return config.mountPath();
    }

    /** {@inheritDoc} */
    @Override
    public MountMeta meta() {
        return new MountMeta("mcp:" + config.mountPath(), config.mountPath(), null, Set.of());
    }

    /** {@inheritDoc} */
    @Override
    public Future<Router> createRouter(Vertx vertx) {
        Router router = Router.router(vertx);
        if (!config.enabled()) {
            return Future.succeededFuture(router);
        }
        // create(false): the MCP contract carries only JSON bodies, so file uploads are never handled
        // and nothing is written to the default upload directory.
        router.route()
                .order(Integer.MIN_VALUE)
                .handler(BodyHandler.create(false).setBodyLimit(httpConfig.maxBodySize()));
        // Always-on cleanup of request-scoped uploads. This mount never spools a file itself, but an
        // application-composed ancestor BodyHandler with uploads enabled runs before the sub-router and
        // materialises multipart parts on disk; the cleanup call is delegated to the root routing
        // context, so it deletes those ancestor-spooled uploads too. Routing context end handlers cover
        // normal completion, failures, and connection/stream resets (see JaxRsRouterMount).
        router.route().order(Integer.MIN_VALUE + 1).handler(context -> {
            context.addEndHandler(v -> context.cancelAndCleanupFileUploads());
            context.next();
        });
        router.route()
                .handler(dispatcher::begin)
                .handler(identityEstablisher::admit)
                .handler(identityEstablisher::authenticate)
                .handler(identityEstablisher::verifyPostAuthenticationState)
                .handler(identityEstablisher::resolveIdentity)
                .handler(dispatcher::dispatch);
        router.route().failureHandler(dispatcher::handleFailure);
        return Future.succeededFuture(router);
    }
}
