// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import jakarta.inject.Singleton;
import java.util.Set;

/** Dagger composition module for the optional MCP HTTP server. */
@Module
public abstract class McpServerModule {
    private McpServerModule() {}

    /** Declares the zero-or-more neutral lifecycle-observer extension set. */
    @Multibinds
    abstract Set<McpRequestLifecycleObserver> lifecycleObservers();

    /** Declares the zero-or-more post-transport completion-listener extension set. */
    @Multibinds
    abstract Set<McpRequestCompletedListener> completedListeners();

    /** Contributes profile validation to the mandatory compose-validation phase. */
    @Provides
    @Singleton
    @IntoSet
    static ComposeValidator jsonProfileDefaultValidator(McpJsonProfileDefaultValidator validator) {
        return validator;
    }

    /** Constructs the bounded configuration validator used before every MCP mount. */
    @Provides
    @Singleton
    static McpServerConfigValidator configValidator() {
        return new McpServerConfigValidator();
    }

    /** Contributes the validated MCP mount to the application's plain Vert.x router. */
    @Provides
    @Singleton
    @IntoSet
    static RouterMount routerMount(
            McpServerConfig config,
            McpServerConfigValidator configValidator,
            McpRequestDispatcher dispatcher,
            Set<RouteAuthHandler> routeAuthHandlers,
            IdentityResolutionMiddleware identityResolutionMiddleware,
            HttpConfig httpConfig) {
        return new McpRouterMount(
                config, configValidator, dispatcher, routeAuthHandlers, identityResolutionMiddleware, httpConfig);
    }
}
