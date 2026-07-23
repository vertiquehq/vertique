// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import io.vertx.core.Future;
import io.vertx.core.Vertx;

/**
 * Contract for running database schema migrations. Implementations may use Flyway, Liquibase, or
 * custom migration logic.
 *
 * <p>Typically invoked during application startup before deploying verticles:
 *
 * <pre>{@code
 * migrationRunner.migrate(vertx)
 *     .compose(result -> vertx.deployVerticle(httpVerticle));
 * }</pre>
 */
public interface MigrationRunner {

    /**
     * Runs schema migrations.
     *
     * @param vertx the Vert.x instance (for {@code executeBlocking} if needed)
     * @return a future with the migration result
     */
    Future<MigrationResult> migrate(Vertx vertx);
}
