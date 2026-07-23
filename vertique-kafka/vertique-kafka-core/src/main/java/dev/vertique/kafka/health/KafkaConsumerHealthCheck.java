// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.health;

import dev.vertique.core.health.HealthCheck;
import dev.vertique.core.health.HealthCheckResult;
import dev.vertique.kafka.KafkaConsumerRegistry;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;

/**
 * Readiness health check for Kafka consumers.
 *
 * <p>Reports {@link dev.vertique.core.health.HealthStatus#UP UP} when all enabled
 * consumers have been registered, listing each consumer's name and enabled state as
 * diagnostic data. Reports {@link dev.vertique.core.health.HealthStatus#DOWN DOWN} only
 * if the registry itself is absent (which cannot happen in normal operation).
 *
 * <p>Automatically contributed as a {@link dev.vertique.core.health.Readiness @Readiness}
 * health check by {@link dev.vertique.kafka.KafkaModule}.
 */
@Singleton
public class KafkaConsumerHealthCheck implements HealthCheck {

    private final KafkaConsumerRegistry registry;

    /**
     * Creates the health check.
     *
     * @param registry the Kafka consumer registry to inspect
     */
    @Inject
    public KafkaConsumerHealthCheck(KafkaConsumerRegistry registry) {
        this.registry = registry;
    }

    /**
     * Returns the health check name.
     *
     * @return {@code "kafka-consumers"}
     */
    @Override
    public String name() {
        return "kafka-consumers";
    }

    /**
     * Checks that all consumers in the registry were built successfully.
     *
     * <p>Reports the total and enabled consumer counts as diagnostic data. The result is always
     * {@link dev.vertique.core.health.HealthStatus#UP UP} when the registry was constructed
     * without error, since registration failures throw during startup.
     *
     * @return a future completing with an UP result and aggregate consumer count data
     */
    @Override
    public Future<HealthCheckResult> check() {
        Map<String, Object> data = Map.of(
                "total", registry.totalCount(),
                "enabled", registry.enabledCount());
        return Future.succeededFuture(HealthCheckResult.up(data));
    }
}
