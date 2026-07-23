// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.prometheus;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.management.ManagementEndpointContributor;
import io.prometheus.metrics.tracer.common.SpanContext;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Dagger module that provides Prometheus scrape endpoint bindings for the management server.
 *
 * <p>Install this module in the application's {@code @Component} alongside
 * {@link dev.vertique.micrometer.MicrometerModule} and
 * {@link dev.vertique.management.ManagementModule} to add the Prometheus scrape endpoint at
 * {@code /metrics} (or a custom path) on the management HTTP server:
 *
 * <pre>{@code
 * @Component(modules = {
 *     VertxModule.class,
 *     MicrometerModule.class,
 *     ManagementModule.class,
 *     MicrometerPrometheusModule.class,
 *     ...
 * })
 * interface AppComponent { ... }
 * }</pre>
 *
 * <h2>Optional exemplar SpanContext</h2>
 * <p>This module declares {@link SpanContext} as an optional binding via
 * {@link BindsOptionalOf}. Applications that have a tracing integration can contribute a
 * {@link SpanContext} via their own {@code @Provides} method; when none is provided the exemplar
 * machinery remains inactive regardless of the {@code metrics.prometheus.exemplars.enabled} flag.
 *
 * <h2>Scrape path validation</h2>
 * <p>The configured scrape path is validated at Dagger graph construction time. The path must:
 * <ul>
 *   <li>Match {@code ^/[A-Za-z0-9._/-]*$}</li>
 *   <li>Not contain {@code *} or {@code :}</li>
 *   <li>Not equal or start with {@code /health}</li>
 * </ul>
 * A {@link ConfigurationException} is thrown if any constraint is violated.
 *
 * @see PrometheusScrapeEndpoint
 * @see PrometheusScrapeConfig
 */
@Module
public abstract class MicrometerPrometheusModule {

    // --- Optional bindings ---

    /**
     * Declares {@link SpanContext} as an optional binding.
     *
     * <p>Applications with a tracing integration contribute a {@link SpanContext} instance by
     * adding a {@code @Provides SpanContext} method to their own module. When absent, the
     * {@link PrometheusScrapeEndpoint} receives an empty {@link java.util.Optional} and exemplars
     * remain disabled.
     *
     * @return declared as optional; Dagger generates the {@code Optional<SpanContext>} provider
     */
    @BindsOptionalOf
    abstract SpanContext exemplarSpanContext();

    // --- Config ---

    /**
     * Provides the {@link PrometheusScrapeConfig} from the application configuration and validates
     * the scrape path.
     *
     * <p>Reads:
     * <ul>
     *   <li>{@code metrics.scrape.path} — the scrape endpoint path (default {@code "/metrics"})</li>
     *   <li>{@code metrics.prometheus.exemplars.enabled} — whether exemplars are enabled
     *       (default {@code false})</li>
     * </ul>
     *
     * @param config the application configuration
     * @return the resolved and validated scrape configuration
     * @throws ConfigurationException if the scrape path is invalid
     */
    @Provides
    @Singleton
    static PrometheusScrapeConfig prometheusScrapeConfig(@VertxConfig JsonObject config) {
        JsonObject metricsSection = JsonConfigPaths.navigateObject(config, "metrics");

        // --- Read path ---
        JsonConfigPaths.LookupResult pathResult = JsonConfigPaths.resolve(metricsSection, "scrape.path");
        String path =
                switch (pathResult.status()) {
                    case PRESENT -> {
                        if (pathResult.value() instanceof String s) {
                            yield s;
                        }
                        throw new ConfigurationException(
                                "metrics.scrape.path is present but has wrong type: expected String, got "
                                        + (pathResult.value() == null
                                                ? "null"
                                                : pathResult.value().getClass().getSimpleName()));
                    }
                    case MISSING -> "/metrics";
                    case INVALID_SHAPE ->
                        throw new ConfigurationException("metrics."
                                + pathResult.path()
                                + ": an intermediate segment (metrics."
                                + pathUpTo(pathResult.path(), pathResult.failingSegment())
                                + ") is present but is not a JSON object");
                };

        // --- Read exemplarsEnabled ---
        JsonConfigPaths.LookupResult exemplarsResult =
                JsonConfigPaths.resolve(metricsSection, "prometheus.exemplars.enabled");
        boolean exemplarsEnabled =
                switch (exemplarsResult.status()) {
                    case PRESENT -> {
                        if (exemplarsResult.value() instanceof Boolean b) {
                            yield b;
                        }
                        throw new ConfigurationException(
                                "metrics.prometheus.exemplars.enabled is present but has wrong type: expected Boolean, got "
                                        + (exemplarsResult.value() == null
                                                ? "null"
                                                : exemplarsResult
                                                        .value()
                                                        .getClass()
                                                        .getSimpleName()));
                    }
                    case MISSING -> false;
                    case INVALID_SHAPE ->
                        throw new ConfigurationException("metrics."
                                + exemplarsResult.path()
                                + ": an intermediate segment (metrics."
                                + pathUpTo(exemplarsResult.path(), exemplarsResult.failingSegment())
                                + ") is present but is not a JSON object");
                };

        // --- Validate path ---
        validateScrapePath(path);

        return PrometheusScrapeConfig.builder()
                .path(path)
                .exemplarsEnabled(exemplarsEnabled)
                .build();
    }

    // --- ManagementEndpointContributor multibinding ---

    /**
     * Contributes the {@link PrometheusScrapeEndpoint} into the {@link ManagementEndpointContributor}
     * multibinding set.
     *
     * @param endpoint the endpoint to contribute; provided by Dagger via its {@code @Inject} ctor
     * @return the endpoint cast to {@link ManagementEndpointContributor}
     */
    @Provides
    @IntoSet
    static ManagementEndpointContributor prometheusScrapeEndpoint(PrometheusScrapeEndpoint endpoint) {
        return endpoint;
    }

    // --- Private helpers ---

    /**
     * Builds the dotted-path prefix from a full dotted path up to and including a given segment.
     *
     * <p>For example, given {@code path="prometheus.exemplars.enabled"} and
     * {@code segment="exemplars"}, returns {@code "prometheus.exemplars"}.
     *
     * <p>If the segment is not found in the path (which should not occur in practice since
     * {@link dev.vertique.core.config.JsonConfigPaths.LookupResult#failingSegment()} is always a
     * component of {@link dev.vertique.core.config.JsonConfigPaths.LookupResult#path()}), the full
     * path is returned as a safe fallback.
     *
     * @param path    the full dotted path (e.g. {@code "prometheus.exemplars.enabled"})
     * @param segment the failing segment to stop at (e.g. {@code "exemplars"})
     * @return the path prefix up to and including {@code segment}
     */
    private static String pathUpTo(String path, String segment) {
        String[] parts = path.split("\\.");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (!result.isEmpty()) {
                result.append('.');
            }
            result.append(part);
            if (part.equals(segment)) {
                return result.toString();
            }
        }
        // Fallback: return the full path if segment not found
        return path;
    }

    /**
     * Validates the scrape path against the allowed character set and reserved prefix rules.
     *
     * <p>The path must:
     * <ul>
     *   <li>Match {@code ^/[A-Za-z0-9._/-]*$} (starts with {@code /}, then only safe chars)</li>
     *   <li>Not contain {@code *} (wildcard)</li>
     *   <li>Not contain {@code :} (path parameter prefix)</li>
     *   <li>Not equal {@code /health} and not start with {@code /health/} (reserved prefix)</li>
     * </ul>
     *
     * @param path the path to validate
     * @throws ConfigurationException if the path violates any constraint
     */
    private static void validateScrapePath(String path) {
        if (!path.matches("^/[A-Za-z0-9._/\\-]*$")) {
            throw new ConfigurationException("metrics.scrape.path '" + path
                    + "' is invalid: must start with '/' and contain only [A-Za-z0-9._/-]");
        }
        if (path.contains("*")) {
            throw new ConfigurationException(
                    "metrics.scrape.path '" + path + "' is invalid: must not contain '*' (wildcard)");
        }
        if (path.contains(":")) {
            throw new ConfigurationException(
                    "metrics.scrape.path '" + path + "' is invalid: must not contain ':' (path parameter prefix)");
        }
        if (path.equals("/health") || path.startsWith("/health/")) {
            throw new ConfigurationException(
                    "metrics.scrape.path '" + path + "' is invalid: must not equal or start with '/health'");
        }
    }
}
