// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.router.MountMeta;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.security.authz.Authorizer;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/** Installs the one validated MCP Router mount owned by the server module. */
@Slf4j
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
            HttpConfig httpConfig,
            McpToolRegistry toolRegistry) {
        this(
                config,
                configValidator,
                dispatcher,
                routeAuthHandlers,
                identityResolutionMiddleware,
                httpConfig,
                toolRegistry,
                Optional.empty());
    }

    /**
     * As the seven-argument constructor above, but additionally threads the optional core {@link
     * Authorizer} so mount validation can reject a registry that publishes an {@code @RequiresAction}
     * tool with no engine installed (issue #421). {@link McpServerModule#routerMount} — the one real
     * production mount point — calls this overload; the seven-argument overload is the pre-existing
     * convenience form for callers (chiefly this module's own test fixtures) that never register an
     * action-gated tool, and is exactly equivalent to passing {@link Optional#empty()} here.
     *
     * @param authorizer the optional core {@link Authorizer}; empty when the authorization engine is
     *                   not installed
     */
    McpRouterMount(
            McpServerConfig config,
            McpServerConfigValidator configValidator,
            McpRequestDispatcher dispatcher,
            Set<RouteAuthHandler> routeAuthHandlers,
            IdentityResolutionMiddleware identityResolutionMiddleware,
            HttpConfig httpConfig,
            McpToolRegistry toolRegistry,
            Optional<Authorizer> authorizer) {
        this.config = config;
        this.configValidator = configValidator;
        this.dispatcher = dispatcher;
        this.httpConfig = httpConfig;
        // The three-argument (registry-visibility, §4.5), HttpConfig-liveness-gate, and
        // no-authorizer-for-@RequiresAction (issue #421) rules all matter only at the one real
        // production mount point: this constructor. Test fixtures that exercise a narrower slice of
        // McpServerConfigValidator call its narrower overloads directly.
        configValidator.validate(config, routeAuthHandlers, toolRegistry, httpConfig, authorizer);
        if (config.enabled() && toolRegistry.invokersByName().isEmpty()) {
            // Gated behind config.enabled() (repair task R48, S1): an empty registry is valid
            // composition (an unconfigured registry with no tools is allowed, see
            // McpServerConfigValidator), but it is never what an application publishing @McpTool
            // methods intended, so both likely root causes are named here rather than left for the
            // application developer to rediscover — but only when this mount actually runs. A disabled
            // mount (config.enabled() == false) never installs any route (see createRouter's own
            // early-return below) and its empty registry is therefore never reachable, so warning about
            // it here — as an earlier revision unconditionally did — was pure noise for the common
            // "MCP not turned on for this application" composition. Read once, from this constructor
            // parameter, so the warning still fires exactly once per enabled mount, regardless of how
            // many times createRouter is later called.
            log.warn(
                    "MCP tool registry at {} is empty: no tools are reachable. Likely causes: "
                            + "GeneratedMcpToolsModule is not installed in the application's Dagger component, "
                            + "or vertique-codegen-mcp is absent from the annotation-processor path (pre-facade "
                            + "setups that do not depend on vertique-codegen-all)",
                    config.mountPath());
        }
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
        // Always-on cleanup of request-scoped uploads, mounted first so it registers regardless of
        // whether cheap admission below goes on to admit or reject the request. This mount never
        // spools a file itself, but an application-composed ancestor BodyHandler with uploads enabled
        // runs before the sub-router and materialises multipart parts on disk; the cleanup call is
        // delegated to the root routing context, so it deletes those ancestor-spooled uploads too, even
        // for a request cheap admission goes on to reject. Routing context end handlers cover normal
        // completion, failures, and connection/stream resets (see JaxRsRouterMount) — a claim that was
        // false on this mount until R14 item 5: McpRequestDispatcher#registerSettlementHooks used to
        // overwrite the single-slot response close/exception handlers Vert.x Web's routing context
        // installs to drive those end handlers, so none of them fired on a disconnect or a reset, this
        // upload cleanup included. Settlement now uses the multicast addEndHandler instead, which
        // restores the coverage this comment claims; McpDisconnectCleanupIT pins it. This handler reads
        // no body itself, so mounting it ahead of cheap admission does not reopen the body-consumption
        // gap that admission ordering exists to close.
        router.route().order(Integer.MIN_VALUE).handler(context -> {
            context.addEndHandler(v -> context.cancelAndCleanupFileUploads());
            context.next();
        });
        // Cheap admission (method, Origin, Content-Type, Accept) runs strictly before BodyHandler: it
        // inspects only the request line and headers, never the body, so a disallowed Origin, an
        // unsupported method, or an unacceptable media type is rejected without the framework ever
        // aggregating the request body.
        router.route().order(Integer.MIN_VALUE + 1).handler(dispatcher::admitCheap);
        // create(false): the MCP contract carries only JSON bodies, so file uploads are never handled
        // and nothing is written to the default upload directory.
        router.route()
                .order(Integer.MIN_VALUE + 2)
                .handler(BodyHandler.create(false).setBodyLimit(httpConfig.maxBodySize()));
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
