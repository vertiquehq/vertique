// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.support;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.test.PostgresContainer;
import io.vertx.core.json.JsonObject;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * A test database and the queries the integration tests ask of it.
 *
 * <p>The database comes from the shared PostgreSQL test container. Every query opens its own short connection under
 * the application name {@code it-probe}, so a probe is never mistaken for a node.
 */
public final class ItDatabase implements AutoCloseable {

    private final PostgresContainer container = new PostgresContainer().withDatabaseName("workflow_load");
    private DbPoolConfig pool;

    /**
     * Provisions the database.
     *
     * @return this database
     */
    public ItDatabase start() {
        container.start();
        pool = container.toPoolConfig();
        return this;
    }

    /**
     * Returns the {@code db} section a node or admin command needs to reach this database.
     *
     * @param applicationName the application name the connections report; {@code null} for none
     * @param maxPoolSize the pool size of a node
     * @return the section
     */
    public JsonObject dbSection(String applicationName, int maxPoolSize) {
        JsonObject db = new JsonObject()
                .put("host", pool.host())
                .put("port", pool.port())
                .put("database", pool.database())
                .put("user", pool.user())
                .put("password", pool.password())
                .put("maxPoolSize", maxPoolSize);
        if (applicationName != null) {
            db.put("properties", new JsonObject().put("application_name", applicationName));
        }
        return db;
    }

    /**
     * Returns a connection for a probe.
     *
     * @return an open connection; the caller closes it
     * @throws SQLException if the connection fails
     */
    public Connection connect() throws SQLException {
        return DriverManager.getConnection(
                "jdbc:postgresql://" + pool.host() + ":" + pool.port() + "/" + pool.database()
                        + "?ApplicationName=it-probe&sslmode=disable",
                pool.user(),
                pool.password());
    }

    /**
     * Runs a query that returns one number.
     *
     * @param sql the query
     * @return the first column of the first row
     * @throws SQLException if the query fails
     */
    public long count(String sql) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    /**
     * Runs a query and returns its first column as text.
     *
     * @param sql the query
     * @return the values in row order
     * @throws SQLException if the query fails
     */
    public List<String> strings(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    /**
     * Runs a statement that changes data.
     *
     * @param sql the statement
     * @return the number of rows it changed
     * @throws SQLException if the statement fails
     */
    public int update(String sql) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    /**
     * Polls a count until it reaches a value.
     *
     * @param sql the query that returns the count
     * @param atLeast the value to wait for
     * @param timeoutMs the longest wait in milliseconds
     * @return the last count read
     * @throws SQLException if a query fails
     * @throws InterruptedException if the wait is interrupted
     */
    public long awaitCount(String sql, long atLeast, long timeoutMs) throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        long current = count(sql);
        while (current < atLeast && System.nanoTime() < deadline) {
            Thread.sleep(50);
            current = count(sql);
        }
        return current;
    }

    @Override
    public void close() {
        container.close();
    }
}
