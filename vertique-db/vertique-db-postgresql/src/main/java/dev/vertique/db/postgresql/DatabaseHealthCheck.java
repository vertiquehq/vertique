// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import dev.vertique.core.health.HealthCheck;
import dev.vertique.core.health.HealthCheckResult;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import jakarta.inject.Inject;

/**
 * Readiness health check that verifies database connectivity by executing
 * {@code SELECT 1} against the PostgreSQL connection pool.
 *
 * <p>Automatically contributed as a {@link dev.vertique.core.health.Readiness @Readiness}
 * health check by {@link DbPostgresqlModule} when the module is included in the
 * application's Dagger component.
 *
 * @see DbPostgresqlModule
 */
public class DatabaseHealthCheck implements HealthCheck {

    private final Pool pool;

    /**
     * Creates a new database health check.
     *
     * @param pool the PostgreSQL connection pool to check
     */
    @Inject
    public DatabaseHealthCheck(Pool pool) {
        this.pool = pool;
    }

    /**
     * Returns the health check name.
     *
     * @return {@code "database"}
     */
    @Override
    public String name() {
        return "database";
    }

    /**
     * Executes {@code SELECT 1} against the pool to verify connectivity.
     *
     * @return a future completing with UP if the query succeeds, DOWN with
     *         the error message otherwise
     */
    @Override
    public Future<HealthCheckResult> check() {
        return pool.query("SELECT 1")
                .execute()
                .map(rs -> HealthCheckResult.up())
                .otherwise(cause -> HealthCheckResult.down(cause.getMessage()));
    }
}
