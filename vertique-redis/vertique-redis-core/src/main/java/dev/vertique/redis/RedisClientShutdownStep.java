// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.redis;

import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Application shutdown contribution that closes the shared Redis clients after their consumers.
 *
 * <p>The step owns only Redis clients. The application host owns {@link io.vertx.core.Vertx}, so
 * stopping this step never closes the host runtime.
 */
@Singleton
public final class RedisClientShutdownStep implements ApplicationShutdownStep {

    /**
     * Lowest same-phase priority keeps shared Redis clients behind feature-owned infrastructure
     * cleanup during reverse-order teardown.
     */
    public static final int SHUTDOWN_PRIORITY = Integer.MIN_VALUE;

    private final RedisClientRegistry registry;

    /**
     * Constructs the Redis client shutdown step.
     *
     * @param registry the application-scoped Redis client registry
     */
    @Inject
    public RedisClientShutdownStep(RedisClientRegistry registry) {
        this.registry = registry;
    }

    /**
     * Returns the infrastructure lifecycle phase.
     *
     * @return {@link LifecyclePhase#INFRA}
     */
    @Override
    public LifecyclePhase phase() {
        return LifecyclePhase.INFRA;
    }

    /**
     * Returns the priority that places shared-client close after same-phase consumers.
     *
     * @return {@link #SHUTDOWN_PRIORITY}
     */
    @Override
    public int priority() {
        return SHUTDOWN_PRIORITY;
    }

    /**
     * Closes the registry-owned Redis clients.
     *
     * @return the registry's ordered, idempotent close future
     */
    @Override
    public Future<Void> stop() {
        return registry.close();
    }
}
