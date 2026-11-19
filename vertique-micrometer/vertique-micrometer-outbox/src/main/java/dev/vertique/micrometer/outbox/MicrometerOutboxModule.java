// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.outbox;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.inboxoutbox.OutboxQueueProbe;
import dev.vertique.micrometer.MetricsConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Optional;

/**
 * Dagger module that contributes Micrometer outbox metrics: a publish observer for the attempt
 * counters and a timer that reads the queue size for the gauges.
 *
 * <p>Install this module alongside the application's outbox modules and
 * {@link dev.vertique.micrometer.MicrometerModule}. The optional metrics configuration defaults to
 * enabled when the core Micrometer module is not installed. The application must provide an
 * {@link OutboxQueueProbe} (the PostgreSQL outbox module binds one) and a {@link Vertx}.
 */
@Module(includes = GeneratedRegistrationsModule.class)
public abstract class MicrometerOutboxModule {

    private MicrometerOutboxModule() {}

    /**
     * Declares {@link MetricsConfig} as an optional binding for the outbox observer and sampler.
     *
     * @return declared; never called directly
     */
    @BindsOptionalOf
    abstract MetricsConfig metricsConfig();

    /**
     * Provides the {@code metrics.outbox} settings, failing startup when the sampling interval is
     * below the minimum.
     *
     * @param config the application configuration root
     * @param parser the injected config parser
     * @return the outbox metrics settings
     */
    @Provides
    @Singleton
    static OutboxMetricsConfig outboxMetricsConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        OutboxMetricsConfig parsed = parser.parse(
                JsonConfigPaths.navigateObject(JsonConfigPaths.navigateObject(config, "metrics"), "outbox"),
                OutboxMetricsConfig.class);
        if (parsed.queueSampleIntervalMs() < OutboxMetricsConfig.MIN_QUEUE_SAMPLE_INTERVAL_MS) {
            throw new ConfigurationException("metrics.outbox.queueSampleIntervalMs must be at least "
                    + OutboxMetricsConfig.MIN_QUEUE_SAMPLE_INTERVAL_MS);
        }
        return parsed;
    }

    /**
     * Provides the sampler that reads the queue size on a timer, or one that does nothing when
     * {@code metrics.enabled} is {@code false}.
     *
     * @param vertx         the Vert.x instance that owns the timer
     * @param probe         the outbox queue probe
     * @param registry      the application-wide meter registry
     * @param metricsConfig optional metrics configuration; empty means enabled
     * @param config        the outbox metrics settings
     * @return the sampler, which is both a startup and a shutdown step
     */
    @Provides
    @Singleton
    static OutboxQueueSampler outboxQueueSampler(
            Vertx vertx,
            OutboxQueueProbe probe,
            MeterRegistry registry,
            Optional<MetricsConfig> metricsConfig,
            OutboxMetricsConfig config) {
        boolean enabled = metricsConfig.map(MetricsConfig::enabled).orElse(true);
        return new OutboxQueueSampler(
                vertx, probe, new OutboxQueueMetrics(registry), config.queueSampleIntervalMs(), enabled);
    }

    /**
     * Contributes the sampler's start into the startup steps.
     *
     * @param sampler the sampler
     * @return the sampler as a startup step
     */
    @Provides
    @IntoSet
    static ApplicationStartupStep outboxQueueSamplerStartup(OutboxQueueSampler sampler) {
        return sampler;
    }

    /**
     * Contributes the sampler's stop into the shutdown steps.
     *
     * @param sampler the sampler
     * @return the sampler as a shutdown step
     */
    @Provides
    @IntoSet
    static ApplicationShutdownStep outboxQueueSamplerShutdown(OutboxQueueSampler sampler) {
        return sampler;
    }
}
