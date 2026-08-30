// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.opentelemetry;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;

/**
 * Dagger module that contributes the OpenTelemetry MCP server-span observer via multibinding
 * (contract §4.10).
 *
 * <p>The only public type in {@code vertique-opentelemetry-mcp}; {@link McpServerSpanObserver} stays
 * package-private per the frozen type inventory.
 *
 * <p>Install this module alongside {@code McpServerModule} in the application's Dagger component. No
 * OpenTelemetry SDK, exporter, or additional binding is required — the module and its observer
 * compile and operate against the OpenTelemetry API only:
 *
 * <pre>{@code
 * @Component(modules = {
 *     VertxModule.class,
 *     McpServerModule.class,
 *     McpOpenTelemetryModule.class,
 *     ...
 * })
 * interface AppComponent { ... }
 * }</pre>
 *
 * <p>This module is observe-only: it contributes one {@link McpRequestLifecycleObserver} that
 * captures the current Vert.x HTTP server span at {@code open(...)} and enriches that exact retained
 * span at logical settlement, without creating or renaming a span and without modifying MCP request
 * or response processing. Omitting this module contributes nothing — MCP protocol behavior is
 * unchanged either way. Installing it without an OpenTelemetry SDK, or with a no-op {@code
 * OpenTelemetry} instance and no exporter configured, degrades safely: {@link io.opentelemetry.api.trace.Span#current()} then
 * resolves to the API's built-in no-op span, whose context is invalid, so the observer's {@code open}
 * returns a no-op session and every request completes with identical protocol behavior while
 * recording nothing.
 *
 * @see McpServerSpanObserver
 */
@Module
public abstract class McpOpenTelemetryModule {

    private McpOpenTelemetryModule() {}

    /**
     * Contributes {@link McpServerSpanObserver} into the {@link McpRequestLifecycleObserver}
     * multibinding set.
     *
     * @param observer the singleton observer; provided by Dagger via its {@code @Inject} constructor
     * @return the observer cast to the SPI type
     */
    @Provides
    @IntoSet
    static McpRequestLifecycleObserver mcpServerSpanObserver(McpServerSpanObserver observer) {
        return observer;
    }
}
