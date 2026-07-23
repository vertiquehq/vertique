// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlConnection;

/**
 * Handler for initializing newly established database connections.
 *
 * <p>Called once per new physical connection, before the connection is admitted to the pool. The
 * framework calls {@code conn.close()} automatically when the returned future completes —
 * implementations must <b>NOT</b> call it themselves.
 *
 * <p>Example: setting the session timezone:
 *
 * <pre>{@code
 * @Provides
 * static PoolConnectHandler timezoneHandler() {
 *     return conn -> conn.query("SET TIME ZONE 'UTC'").execute().mapEmpty();
 * }
 * }</pre>
 */
@FunctionalInterface
public interface PoolConnectHandler {

    /**
     * Initializes the connection. The framework closes the connection (signals pool admission)
     * automatically when the returned future completes. Implementations must not call
     * {@code conn.close()} themselves.
     *
     * @param conn the newly created connection
     * @return a future that completes when initialization is done
     */
    Future<Void> handle(SqlConnection conn);
}
