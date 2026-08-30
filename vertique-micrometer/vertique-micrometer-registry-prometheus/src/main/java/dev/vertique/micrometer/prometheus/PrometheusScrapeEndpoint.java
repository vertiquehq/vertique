// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.prometheus;

import dev.vertique.codegen.RegisterIntoSet;
import dev.vertique.management.ManagementEndpointContributor;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.tracer.common.SpanContext;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link ManagementEndpointContributor} that mounts the Prometheus metrics scrape endpoint on the
 * management HTTP server.
 *
 * <p>The endpoint is only mounted when {@link PrometheusBackend#registry()} is non-empty at the
 * time {@link #contribute(Router)} is called. If the Prometheus backend was not initialized (e.g.
 * because the module is present but the backend was not configured), a single INFO log is emitted
 * and no route is added.
 *
 * <h2>Content-type negotiation</h2>
 * <p>When the request {@code Accept} header contains {@code "application/openmetrics-text"}, the
 * scrape body is rendered in OpenMetrics format
 * ({@code application/openmetrics-text; version=1.0.0; charset=utf-8}). Otherwise the default
 * Prometheus text format is used ({@code text/plain; version=0.0.4; charset=utf-8}).
 *
 * <h2>Exemplars</h2>
 * <p>When {@link PrometheusScrapeConfig#exemplarsEnabled()} is {@code true} and an optional
 * {@link SpanContext} is present in the Dagger graph, the {@link DeferredSpanContext} bundled
 * with the registry is wired to the real span context during {@link #contribute(Router)}.
 *
 * <h2>Worker-thread execution</h2>
 * <p>The scrape body is produced on a Vert.x worker thread via
 * {@link Vertx#executeBlocking(Callable, boolean)} with {@code ordered=false} so that the event
 * loop is never blocked by Prometheus registry serialization.
 *
 * @see PrometheusBackend
 * @see PrometheusScrapeConfig
 * @see DeferredSpanContext
 */
@Slf4j
@Singleton
@RegisterIntoSet(ManagementEndpointContributor.class)
public final class PrometheusScrapeEndpoint implements ManagementEndpointContributor {

    // --- Constants ---

    private static final String OPENMETRICS_CONTENT_TYPE = "application/openmetrics-text; version=1.0.0; charset=utf-8";

    private static final String PROMETHEUS_TEXT_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private static final String METRICS_UNAVAILABLE = "metrics unavailable";

    // --- Fields ---

    private final Vertx vertx;
    private final Optional<SpanContext> exemplarSpanContext;
    private final PrometheusScrapeConfig config;

    /**
     * Optional renderer factory used in tests. When {@code null} the standard
     * {@link PrometheusMeterRegistry#scrape(String)} path is used.
     */
    private final java.util.function.Function<String, Callable<String>> rendererFactory;

    // --- Constructors ---

    /**
     * Creates the scrape endpoint contributor.
     *
     * @param vertx               the Vert.x instance used to offload scrape work to a worker thread
     * @param exemplarSpanContext the optional span context for exemplar support; empty when no
     *                            tracing integration is on the classpath
     * @param config              the scrape endpoint configuration
     */
    @Inject
    public PrometheusScrapeEndpoint(
            Vertx vertx, Optional<SpanContext> exemplarSpanContext, PrometheusScrapeConfig config) {
        this.vertx = vertx;
        this.exemplarSpanContext = exemplarSpanContext;
        this.config = config;
        this.rendererFactory = null;
    }

    /**
     * Package-private test seam constructor that accepts an explicit renderer factory.
     *
     * <p>The renderer factory is a function from content-type string to a {@link Callable} that
     * produces the scrape body. Injecting it allows the failure path (500 response) to be tested
     * without relying on {@link PrometheusMeterRegistry} throwing from {@code scrape()}.
     *
     * @param vertx               the Vert.x instance
     * @param exemplarSpanContext the optional span context
     * @param config              the scrape endpoint configuration
     * @param rendererFactory     a factory returning a {@link Callable}{@code <String>} given the
     *                            chosen content-type string
     */
    PrometheusScrapeEndpoint(
            Vertx vertx,
            Optional<SpanContext> exemplarSpanContext,
            PrometheusScrapeConfig config,
            java.util.function.Function<String, Callable<String>> rendererFactory) {
        this.vertx = vertx;
        this.exemplarSpanContext = exemplarSpanContext;
        this.config = config;
        this.rendererFactory = rendererFactory;
    }

    // --- ManagementEndpointContributor ---

    /**
     * Mounts the Prometheus scrape route at the configured path, or logs and skips if the backend
     * is absent at contribution time.
     *
     * <p>If exemplars are enabled and both the optional span context and the published
     * {@link DeferredSpanContext} are present, the deferred span context is wired to the real
     * implementation before any scrape request arrives (one-time setup at contribution time).
     *
     * <p>Each incoming scrape request re-resolves the backend registry from {@link PrometheusBackend}
     * so that a backend cleared after contribution time (e.g. during shutdown or bootstrap rollback)
     * causes the handler to respond fail-closed with {@code 503 Service Unavailable} rather than
     * scraping a stale or closed registry.
     *
     * @param router the management server router on which the scrape route is mounted
     */
    @Override
    public void contribute(Router router) {
        Optional<PrometheusMeterRegistry> registryOpt = PrometheusBackend.registry();

        if (registryOpt.isEmpty()) {
            log.info("Prometheus backend not initialized — scrape endpoint not mounted");
            return;
        }

        // --- Wire exemplar delegate if configured (one-time setup at contribution time) ---
        if (config.exemplarsEnabled() && exemplarSpanContext.isPresent()) {
            PrometheusBackend.spanContext().ifPresent(dsc -> dsc.delegate(exemplarSpanContext.get()));
        }

        // --- Mount scrape route ---
        router.get(config.path()).handler(rc -> {
            // Re-resolve the registry on every request so that a cleared backend (shutdown/rollback)
            // causes a fail-closed 503 rather than scraping a stale or closed registry.
            Optional<PrometheusMeterRegistry> liveRegistry = PrometheusBackend.registry();
            if (liveRegistry.isEmpty()) {
                rc.response()
                        .putHeader("Content-Type", "text/plain")
                        .setStatusCode(503)
                        .end(METRICS_UNAVAILABLE);
                return;
            }

            String accept = rc.request().getHeader("Accept");
            String contentType = (accept != null && accept.contains("application/openmetrics-text"))
                    ? OPENMETRICS_CONTENT_TYPE
                    : PROMETHEUS_TEXT_CONTENT_TYPE;

            Callable<String> renderer = buildRenderer(liveRegistry.get(), contentType);

            vertx.<String>executeBlocking(renderer, false)
                    .onSuccess(body -> rc.response()
                            .putHeader("Content-Type", contentType)
                            .setStatusCode(200)
                            .end(body))
                    .onFailure(t -> {
                        log.warn("Prometheus scrape failed: {}", t.getClass().getName());
                        rc.response()
                                .putHeader("Content-Type", "text/plain")
                                .setStatusCode(500)
                                .end(METRICS_UNAVAILABLE);
                    });
        });
    }

    // --- Private helpers ---

    /**
     * Returns the renderer callable for the given registry and content-type.
     *
     * <p>When a test renderer factory is injected via the package-private constructor, that factory
     * is used instead of the real registry scrape. This allows the 500 failure path to be tested
     * without side-effects on the real registry.
     *
     * @param registry    the prometheus registry to scrape
     * @param contentType the chosen content-type string
     * @return a callable that produces the scrape body
     */
    private Callable<String> buildRenderer(PrometheusMeterRegistry registry, String contentType) {
        if (rendererFactory != null) {
            return rendererFactory.apply(contentType);
        }
        return () -> registry.scrape(contentType);
    }
}
