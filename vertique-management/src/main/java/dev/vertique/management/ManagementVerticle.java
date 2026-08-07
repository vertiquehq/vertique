// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.management;

import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.core.health.HealthCheck;
import dev.vertique.core.health.Liveness;
import dev.vertique.core.health.Readiness;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.ext.web.Router;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Verticle that runs a dedicated management HTTP server for health check endpoints.
 *
 * <p>The server is separate from the main application {@code HttpVerticle} and runs
 * on its own port (default 9090), allowing different network policies for health
 * probes (e.g., cluster-internal only).
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>{@code GET /health/live} — aggregated liveness status (200 if UP, 503 if DOWN)
 *   <li>{@code GET /health/ready} — aggregated readiness status (200 if UP, 503 if DOWN)
 * </ul>
 *
 * <p>Aggregation logic:
 *
 * <ul>
 *   <li>All checks run concurrently
 *   <li>Overall status is UP only if ALL individual checks are UP
 *   <li>Empty check set is considered UP (nothing to fail)
 *   <li>Individual check exceptions are caught and reported as DOWN
 *   <li>Each check has a configurable timeout (default 5 seconds) to prevent probe hangs
 * </ul>
 *
 * <p>When {@code management.enabled} is {@code false}, the verticle starts
 * successfully without binding a port and contributors are never invoked.
 */
@Slf4j
public class ManagementVerticle extends AbstractVerticle {

    private final Set<HealthCheck> livenessChecks;
    private final Set<HealthCheck> readinessChecks;
    private final int port;
    private final String host;
    private final boolean enabled;
    private final long healthCheckTimeoutSeconds;
    private final Set<ManagementEndpointContributor> endpointContributors;

    /**
     * Creates a new management verticle.
     *
     * @param livenessChecks       the set of liveness health checks
     * @param readinessChecks      the set of readiness health checks
     * @param config               the management server configuration
     * @param endpointContributors the set of contributors that mount additional routes on the
     *                             management router; may be empty
     */
    @Inject
    public ManagementVerticle(
            @Liveness Set<HealthCheck> livenessChecks,
            @Readiness Set<HealthCheck> readinessChecks,
            ManagementConfig config,
            Set<ManagementEndpointContributor> endpointContributors) {
        this.livenessChecks = livenessChecks;
        this.readinessChecks = readinessChecks;
        this.port = config.port();
        this.host = config.host();
        this.enabled = config.enabled();
        this.healthCheckTimeoutSeconds = config.healthCheckTimeoutSeconds();
        this.endpointContributors = endpointContributors;
    }

    /**
     * Starts the management HTTP server if enabled, otherwise completes immediately.
     *
     * <p>When enabled, health routes are mounted first, then endpoint contributors are invoked
     * in {@link OrderedExtension#comparator()} order. If any contributor throws, the start
     * promise is failed with that exception and no HTTP server port is bound.
     *
     * @param startPromise the promise to complete when the server is ready, or fail on error
     */
    @Override
    public void start(Promise<Void> startPromise) {
        if (!enabled) {
            log.info("Management server disabled");
            startPromise.complete();
            return;
        }

        Router router = Router.router(vertx);
        router.get("/health/live").handler(new HealthCheckHandler(livenessChecks, healthCheckTimeoutSeconds));
        router.get("/health/ready").handler(new HealthCheckHandler(readinessChecks, healthCheckTimeoutSeconds));

        // --- Invoke endpoint contributors ---
        Comparator<OrderedExtension> order = OrderedExtension.comparator();
        List<ManagementEndpointContributor> sorted =
                endpointContributors.stream().sorted(order).toList();
        for (ManagementEndpointContributor contributor : sorted) {
            try {
                contributor.contribute(router);
            } catch (RuntimeException ex) {
                log.error(
                        "ManagementEndpointContributor {} failed during startup",
                        contributor.getClass().getName(),
                        ex);
                startPromise.fail(ex);
                return;
            }
        }

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(port, host)
                .onSuccess(server -> {
                    vertx.sharedData().getLocalMap("vertique").put("management.port", server.actualPort());
                    log.info("Management server started on {}:{}", host, server.actualPort());
                    startPromise.complete();
                })
                .onFailure(cause -> {
                    log.error("Failed to start management server", cause);
                    startPromise.fail(cause);
                });
    }
}
