// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.mcp.interceptor.McpRequestInterceptor;
import dev.vertique.mcp.interceptor.McpToolInterceptor;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.tool.McpToolInvoker;
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

    /**
     * Declares the zero-or-more ordered, rejective pre-dispatch request-interceptor extension set
     * (T016). {@link McpRequestDispatcher} sorts and validates the contributed set once at
     * construction, so a graph with no contributions resolves an empty ordered chain.
     */
    @Multibinds
    abstract Set<McpRequestInterceptor> requestInterceptors();

    /**
     * Declares the zero-or-more ordered, rejective post-validation tool-interceptor extension set
     * (T017). {@link McpRequestDispatcher} sorts and validates the contributed set once at
     * construction, so a graph with no contributions resolves an empty ordered chain.
     */
    @Multibinds
    abstract Set<McpToolInterceptor> toolInterceptors();

    /**
     * Declares the zero-or-more generated tool invoker extension point. {@code vertique-codegen-mcp}
     * contributes into this set; a graph with no generated tools resolves an empty set, so {@link
     * #toolRegistry} still builds (an empty registry) without a generated module present (T011).
     */
    @Multibinds
    abstract Set<McpToolInvoker> toolInvokers();

    /**
     * Builds the one immutable, global-name-ordered tool registry {@link McpRequestDispatcher}'s {@code
     * tools/list} listing scans (T010/T011).
     */
    @Provides
    @Singleton
    static McpToolRegistry toolRegistry(Set<McpToolInvoker> toolInvokers) {
        return McpToolRegistry.build(toolInvokers);
    }

    /** Contributes profile validation to the mandatory compose-validation phase. */
    @Provides
    @Singleton
    @IntoSet
    static ComposeValidator jsonProfileDefaultValidator(McpJsonProfileDefaultValidator validator) {
        return validator;
    }

    /**
     * Contributes the mandatory input-processing binding guard to the compose-validation phase
     * (T014, contract §4.7). Merely constructing {@link McpInputProcessingCompositionValidator}
     * requires a direct {@link dev.vertique.input.processing.InputObjectProcessor} binding, so a
     * composition that omits one fails Dagger compilation before any route mounts — regardless of
     * how many tools are registered.
     */
    @Provides
    @Singleton
    @IntoSet
    static ComposeValidator inputProcessingCompositionValidator(McpInputProcessingCompositionValidator validator) {
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
