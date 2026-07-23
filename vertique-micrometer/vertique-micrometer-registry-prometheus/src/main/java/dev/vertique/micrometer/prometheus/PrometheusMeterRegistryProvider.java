// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.prometheus;

import dev.vertique.micrometer.MeterRegistryBackend;
import dev.vertique.micrometer.MeterRegistryProvider;
import io.micrometer.core.instrument.Clock;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.vertx.core.json.JsonObject;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link MeterRegistryProvider} that creates a {@link PrometheusMeterRegistry} backend.
 *
 * <p>On {@link #create(JsonObject)}, this provider:
 * <ol>
 *   <li>Creates a {@link DeferredSpanContext} that can be wired to a live tracing integration
 *       later (enabling exemplars).</li>
 *   <li>Creates a {@link PrometheusMeterRegistry} using {@link PrometheusConfig#DEFAULT} and
 *       a fresh {@link PrometheusRegistry}.</li>
 *   <li>Publishes both to {@link PrometheusBackend} so that {@link PrometheusScrapeEndpoint}
 *       can find the registry when mounting the scrape route.</li>
 *   <li>Returns a {@link MeterRegistryBackend} whose {@link MeterRegistryBackend#close()} is
 *       idempotent and clears the static holder on first call.</li>
 * </ol>
 *
 * <p>The backend is registered as a {@link java.util.ServiceLoader} provider via
 * {@code META-INF/services/dev.vertique.micrometer.MeterRegistryProvider}.
 *
 * @see PrometheusBackend
 * @see PrometheusScrapeEndpoint
 * @see DeferredSpanContext
 */
public final class PrometheusMeterRegistryProvider implements MeterRegistryProvider {

    // --- MeterRegistryProvider ---

    /**
     * Returns {@code "prometheus"} as the unique backend name.
     *
     * @return {@code "prometheus"}
     */
    @Override
    public String backendName() {
        return "prometheus";
    }

    /**
     * Creates a {@link PrometheusMeterRegistry} backend and publishes it to {@link PrometheusBackend}.
     *
     * <p>The {@code backendConfig} parameter is accepted but currently unused — the Prometheus
     * backend uses {@link PrometheusConfig#DEFAULT}. Future versions may read configuration from it.
     *
     * @param backendConfig the backend-specific configuration subtree; never {@code null}
     * @return an idempotently-closeable {@link MeterRegistryBackend} wrapping the prometheus registry
     */
    @Override
    public MeterRegistryBackend create(JsonObject backendConfig) {
        DeferredSpanContext deferredSpanContext = new DeferredSpanContext();
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(
                PrometheusConfig.DEFAULT, new PrometheusRegistry(), Clock.SYSTEM, deferredSpanContext);

        PrometheusBackend.publish(registry, deferredSpanContext);

        return new PrometheusBackendHandle(registry);
    }

    // --- Inner class: backend handle ---

    /**
     * A {@link MeterRegistryBackend} wrapping a {@link PrometheusMeterRegistry}.
     *
     * <p>The {@link #close()} method is idempotent via an {@link AtomicBoolean} guard. On the
     * first call it closes the underlying registry and clears {@link PrometheusBackend}.
     */
    private static final class PrometheusBackendHandle implements MeterRegistryBackend {

        private final PrometheusMeterRegistry registry;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        /**
         * Creates a backend handle for the given registry.
         *
         * @param registry the Prometheus registry to wrap; must not be {@code null}
         */
        PrometheusBackendHandle(PrometheusMeterRegistry registry) {
            this.registry = registry;
        }

        /**
         * Returns the {@link PrometheusMeterRegistry} wrapped by this handle.
         *
         * @return the prometheus registry; never {@code null}
         */
        @Override
        public PrometheusMeterRegistry registry() {
            return registry;
        }

        /**
         * Clears the static {@link PrometheusBackend} holder, then closes the registry.
         *
         * <p>Idempotent: only the first call has effect; subsequent calls are no-ops.
         *
         * <p>The holder is cleared <em>before</em> the registry is closed so that a scrape arriving
         * during teardown resolves an empty {@link PrometheusBackend#registry()} and fails closed
         * (503) rather than scraping an already-closed registry.
         */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                PrometheusBackend.clear();
                registry.close();
            }
        }
    }
}
