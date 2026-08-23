// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.micrometer;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.micrometer.MetricsConfig;

/**
 * Dagger module that contributes the Micrometer MCP server metrics observer via multibinding
 * (T021, contract §4.10).
 *
 * <p>The only public type in {@code vertique-micrometer-mcp}; {@link McpServerMetricsObserver} stays
 * package-private per the frozen type inventory.
 *
 * <p>Install this module alongside {@code McpServerModule} and {@code
 * dev.vertique.micrometer.MicrometerModule} in the application's Dagger component:
 *
 * <pre>{@code
 * @Component(modules = {
 *     VertxModule.class,
 *     McpServerModule.class,
 *     MicrometerModule.class,
 *     McpMicrometerModule.class,
 *     ...
 * })
 * interface AppComponent { ... }
 * }</pre>
 *
 * <p>This module is observe-only: it contributes one {@link McpRequestLifecycleObserver} that
 * records Micrometer meters without modifying MCP request or response processing, and without
 * submitting audit records. Omitting this module contributes nothing — MCP protocol behavior is
 * unchanged either way.
 *
 * <p>{@link #metricsConfig()} declares a {@code @BindsOptionalOf} for {@link MetricsConfig} so that
 * {@link McpServerMetricsObserver} can inject {@code Optional<MetricsConfig>} without requiring
 * {@code MicrometerModule} to be present. When {@code MicrometerModule} is also installed its
 * {@code @Provides MetricsConfig} satisfies the optional binding; when it is absent the optional is
 * empty and the observer defaults to enabled.
 */
@Module
public abstract class McpMicrometerModule {

    private McpMicrometerModule() {}

    // --- Optional binding ---

    /**
     * Declares {@link MetricsConfig} as an optional binding.
     *
     * <p>This allows {@link McpServerMetricsObserver} to inject {@code Optional<MetricsConfig>}
     * without requiring {@code MicrometerModule} to be installed. When {@code MicrometerModule} is
     * also present its {@code @Provides MetricsConfig} method satisfies the optional; when absent
     * the optional is empty and the observer defaults to enabled.
     *
     * @return declared; never called directly
     */
    @BindsOptionalOf
    abstract MetricsConfig metricsConfig();

    // --- Multibinding contribution ---

    /**
     * Contributes {@link McpServerMetricsObserver} into the {@link McpRequestLifecycleObserver}
     * multibinding set.
     *
     * <p>The observer records {@value McpServerMetricsObserver#REQUESTS_TIMER}, {@value
     * McpServerMetricsObserver#ACTIVE_GAUGE}, {@value McpServerMetricsObserver#VALIDATION_FAILURES_COUNTER},
     * and {@value McpServerMetricsObserver#TOOL_CALLS_TIMER}.
     *
     * @param observer the singleton observer; provided by Dagger via its {@code @Inject} constructor
     * @return the observer cast to the SPI type
     */
    @Provides
    @IntoSet
    static McpRequestLifecycleObserver mcpServerMetricsObserver(McpServerMetricsObserver observer) {
        return observer;
    }
}
