// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Application shutdown contribution that closes the PostgreSQL connection pool this module
 * provides, after the work that uses it.
 *
 * <p>The step owns only the pool {@link DbPostgresqlModule} creates. The application host owns
 * {@link io.vertx.core.Vertx}, so stopping this step never closes the host runtime.
 */
@Singleton
final class PgPoolShutdownStep implements ApplicationShutdownStep {

    /**
     * Lowest same-phase priority keeps the pool behind feature-owned infrastructure cleanup during
     * reverse-order teardown, so work that still uses the pool stops first.
     */
    static final int SHUTDOWN_PRIORITY = Integer.MIN_VALUE;

    private final Pool pool;

    /**
     * Constructs the pool shutdown step.
     *
     * @param pool the application's PostgreSQL connection pool
     */
    @Inject
    PgPoolShutdownStep(Pool pool) {
        this.pool = pool;
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
     * Returns the priority that places the pool close after same-phase consumers.
     *
     * @return {@link #SHUTDOWN_PRIORITY}
     */
    @Override
    public int priority() {
        return SHUTDOWN_PRIORITY;
    }

    /**
     * Closes the pool and its connections.
     *
     * @return a future completing when the pool is closed
     */
    @Override
    public Future<Void> stop() {
        return pool.close();
    }
}
