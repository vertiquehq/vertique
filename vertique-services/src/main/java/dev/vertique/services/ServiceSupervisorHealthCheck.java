// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.core.health.HealthCheck;
import dev.vertique.core.health.HealthCheckResult;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Readiness health check that aggregates the supervision state of all
 * event bus services managed by {@link ServiceSupervisor}.
 *
 * <p>Reports {@link dev.vertique.core.health.HealthStatus#DOWN DOWN} if any supervised
 * service is unavailable (restarting, restart budget exhausted). Reports
 * {@link dev.vertique.core.health.HealthStatus#UP UP} when all services are available
 * or when no services are supervised.
 *
 * <p>Automatically contributed as a {@link dev.vertique.core.health.Readiness @Readiness}
 * health check by {@link DispatchModule}.
 */
public class ServiceSupervisorHealthCheck implements HealthCheck {

    private final ServiceSupervisor supervisor;

    /**
     * Creates a new service supervisor health check.
     *
     * @param supervisor the service supervisor to query for availability
     */
    @Inject
    public ServiceSupervisorHealthCheck(ServiceSupervisor supervisor) {
        this.supervisor = supervisor;
    }

    /**
     * Returns the health check name.
     *
     * @return {@code "services"}
     */
    @Override
    public String name() {
        return "services";
    }

    /**
     * Checks the availability of all supervised services.
     *
     * @return a future completing with UP if all services are available (or none
     *         are supervised), DOWN with per-service status otherwise
     */
    @Override
    public Future<HealthCheckResult> check() {
        Map<String, Boolean> statuses = supervisor.serviceAvailability();
        if (statuses.isEmpty()) {
            return Future.succeededFuture(HealthCheckResult.up());
        }
        boolean allUp = statuses.values().stream().allMatch(Boolean::booleanValue);
        Map<String, Object> data = new LinkedHashMap<>();
        statuses.forEach((name, available) -> data.put(name, available ? "UP" : "DOWN"));
        return Future.succeededFuture(allUp ? HealthCheckResult.up(data) : HealthCheckResult.down(data));
    }
}
