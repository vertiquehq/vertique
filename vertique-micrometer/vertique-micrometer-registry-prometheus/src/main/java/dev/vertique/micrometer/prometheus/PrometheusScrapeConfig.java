// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.prometheus;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * Configuration for the Prometheus scrape endpoint mounted on the management HTTP server.
 *
 * <p>Deserialized from the {@code metrics} section of the application configuration:
 *
 * <pre>{@code
 * {
 *   "metrics": {
 *     "scrape": {
 *       "path": "/metrics"
 *     },
 *     "prometheus": {
 *       "exemplars": {
 *         "enabled": false
 *       }
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>All fields have sensible defaults. The scrape path must match {@code ^/[A-Za-z0-9._/-]*$},
 * must not contain {@code *} or {@code :}, and must not equal or start with {@code /health}.
 * These constraints are validated in {@link MicrometerPrometheusModule} before the application
 * starts.
 *
 * @see MicrometerPrometheusModule
 * @see PrometheusScrapeEndpoint
 */
@Getter
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE)
public class PrometheusScrapeConfig {

    /** The path at which the Prometheus scrape endpoint is mounted (default {@code "/metrics"}). */
    @Builder.Default
    private final String path = "/metrics";

    /**
     * Whether exemplar support is enabled on the scrape endpoint.
     *
     * <p>When {@code true} and a {@link io.prometheus.metrics.tracer.common.SpanContext} binding
     * is present in the Dagger graph, the deferred span context is wired to enable exemplar
     * recording on Prometheus histograms and summaries. Defaults to {@code false}.
     */
    @Builder.Default
    private final boolean exemplarsEnabled = false;
}
