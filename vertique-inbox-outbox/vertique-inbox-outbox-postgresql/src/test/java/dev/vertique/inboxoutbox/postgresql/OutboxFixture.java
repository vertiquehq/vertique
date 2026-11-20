// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.test.PostgresContainer;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

/**
 * Shared plumbing of the outbox query and operation integration tests: a pool for the code under test, plain JDBC
 * for seeding, inspecting and for the connections that hold locks as transaction barriers.
 */
final class OutboxFixture {

    static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    private final PostgresContainer db;
    private final Vertx vertx;
    private final Pool pool;
    private final String jdbcSchema;

    OutboxFixture(PostgresContainer db, Vertx vertx) {
        this(db, vertx, null);
    }

    /**
     * @param jdbcSchema the schema the JDBC connections of the test resolve unqualified names in (the pool keeps
     *                   the connection default), or {@code null} to leave the default
     */
    OutboxFixture(PostgresContainer db, Vertx vertx, String jdbcSchema) {
        this.db = db;
        this.vertx = vertx;
        this.jdbcSchema = jdbcSchema;
        this.pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(10))
                .connectingTo(connectOptions())
                .using(vertx)
                .build();
    }

    Pool pool() {
        return pool;
    }

    PgConnectOptions connectOptions() {
        DbPoolConfig config = db.toPoolConfig();
        return new PgConnectOptions()
                .setHost(config.host())
                .setPort(config.port())
                .setDatabase(config.database())
                .setUser(config.user())
                .setPassword(config.password());
    }

    Vertx vertx() {
        return vertx;
    }

    void close() {
        pool.close();
    }

    // --- JDBC ---

    Connection connect() throws SQLException {
        String url = db.jdbcUrl();
        if (jdbcSchema != null) {
            url = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + jdbcSchema;
        }
        return DriverManager.getConnection(url, db.username(), db.password());
    }

    /** A connection with autocommit off: the caller owns the transaction, which is how tests hold a lock. */
    Connection begin() throws SQLException {
        Connection connection = connect();
        connection.setAutoCommit(false);
        return connection;
    }

    void exec(String sql, Object... params) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            statement.execute();
        }
    }

    void exec(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            statement.execute();
        }
    }

    long longValue(String sql, Object... params) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    String string(String sql, Object... params) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    /** Every column of one outbox row, by column name, in table order; {@code null} when there is no such row. */
    Map<String, Object> outboxRow(long id) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement("SELECT * FROM outbox WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                ResultSetMetaData meta = rows.getMetaData();
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(meta.getColumnName(i), rows.getObject(i));
                }
                return row;
            }
        }
    }

    boolean outboxExists(long id) throws SQLException {
        return longValue("SELECT count(*) FROM outbox WHERE id = ?", id) == 1;
    }

    String stateOf(long id) throws SQLException {
        return string("SELECT state FROM outbox WHERE id = ?", id);
    }

    void reset() throws SQLException {
        exec("TRUNCATE TABLE outbox RESTART IDENTITY");
    }

    /**
     * Waits until {@code count} sessions of this database are blocked on a lock, the barrier that proves the
     * statements under test have reached the lock the test holds.
     */
    void awaitLockWaiters(int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long waiting = -1;
        while (System.nanoTime() < deadline) {
            waiting = longValue("SELECT count(*) FROM pg_stat_activity"
                    + " WHERE datname = current_database() AND wait_event_type = 'Lock'");
            if (waiting == count) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("expected " + count + " sessions waiting for a lock, saw " + waiting);
    }

    /** Listens on a notification channel from its own session; every notification sent after it opened is kept. */
    Listener listen(String channel) throws SQLException {
        return new Listener(connect(), channel);
    }

    /** A session subscribed to one channel. */
    static final class Listener implements AutoCloseable {

        private final Connection connection;

        private Listener(Connection connection, String channel) throws SQLException {
            this.connection = connection;
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("LISTEN " + channel);
            }
        }

        /** Waits up to the given time for notifications and returns their payloads in arrival order. */
        List<String> poll(long millis) throws SQLException {
            List<String> payloads = new ArrayList<>();
            PGNotification[] notifications =
                    connection.unwrap(PGConnection.class).getNotifications((int) millis);
            if (notifications != null) {
                for (PGNotification notification : notifications) {
                    payloads.add(notification.getParameter());
                }
            }
            return payloads;
        }

        @Override
        public void close() throws SQLException {
            connection.close();
        }
    }

    // --- Seeding ---

    Seed seed() {
        return new Seed();
    }

    /** One outbox row; defaults describe a dead-lettered SERVICE entry with every nullable column filled. */
    final class Seed {
        String destinationType = "SERVICE";
        String destination = "orders.target";
        String eventType = "order.created";
        String aggregateType = "Order";
        String aggregateId = "order-1";
        String payload = "{\"orderId\": 1, \"note\": \"café\"}";
        String headers = "{\"traceparent\": \"abc\", \"tenant\": \"t-1\"}";
        String metadata = "{\"context\": {\"correlation\": {\"id\": \"c-1\"}}, \"delivery\": {}}";
        Instant scheduledAt = BASE.plusSeconds(5);
        String state = "DEAD_LETTER";
        int attempt = 5;
        int maxAttempts = 7;
        Instant claimedAt = BASE.plusSeconds(6);
        String claimedBy = "node-1";
        Instant publishedAt;
        String lastError = "boom: gateway refused";
        String errorType = "java.io.IOException";
        Instant availableAt = BASE.plusSeconds(7);
        Instant createdAt = BASE;
        Instant updatedAt = BASE.plusSeconds(100);
        String scopeKey = "scope-a";

        Seed state(String value) {
            this.state = value;
            return this;
        }

        Seed destinationType(String value) {
            this.destinationType = value;
            return this;
        }

        Seed destination(String value) {
            this.destination = value;
            return this;
        }

        Seed eventType(String value) {
            this.eventType = value;
            return this;
        }

        Seed aggregate(String type, String id) {
            this.aggregateType = type;
            this.aggregateId = id;
            return this;
        }

        Seed payload(String value) {
            this.payload = value;
            return this;
        }

        Seed headers(String value) {
            this.headers = value;
            return this;
        }

        Seed lastError(String value) {
            this.lastError = value;
            return this;
        }

        Seed errorType(String value) {
            this.errorType = value;
            return this;
        }

        Seed created(Instant value) {
            this.createdAt = value;
            return this;
        }

        Seed updated(Instant value) {
            this.updatedAt = value;
            return this;
        }

        Seed scheduled(Instant value) {
            this.scheduledAt = value;
            return this;
        }

        Seed scheduledNull() {
            this.scheduledAt = null;
            return this;
        }

        Seed available(Instant value) {
            this.availableAt = value;
            return this;
        }

        Seed published(Instant value) {
            this.publishedAt = value;
            return this;
        }

        Seed claimed(Instant at, String by) {
            this.claimedAt = at;
            this.claimedBy = by;
            return this;
        }

        Seed unclaimed() {
            this.claimedAt = null;
            this.claimedBy = null;
            return this;
        }

        Seed attempts(int attempt, int maxAttempts) {
            this.attempt = attempt;
            this.maxAttempts = maxAttempts;
            return this;
        }

        long insert() throws SQLException {
            try (Connection connection = connect();
                    PreparedStatement statement = connection.prepareStatement("INSERT INTO outbox (carrier_id,"
                            + " aggregate_type, aggregate_id, event_type, destination, destination_type, payload,"
                            + " headers, metadata, scheduled_at, available_at, state, attempt, max_attempts,"
                            + " claimed_at, claimed_by, published_at, last_error, error_type, created_at, updated_at,"
                            + " scope_key) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?,"
                            + " ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id")) {
                int i = 1;
                statement.setObject(i++, UUID.randomUUID());
                statement.setString(i++, aggregateType);
                statement.setString(i++, aggregateId);
                statement.setString(i++, eventType);
                statement.setString(i++, destination);
                statement.setString(i++, destinationType);
                statement.setString(i++, payload);
                statement.setString(i++, headers);
                statement.setString(i++, metadata);
                statement.setTimestamp(i++, timestamp(scheduledAt));
                statement.setTimestamp(i++, timestamp(availableAt));
                statement.setString(i++, state);
                statement.setInt(i++, attempt);
                statement.setInt(i++, maxAttempts);
                statement.setTimestamp(i++, timestamp(claimedAt));
                statement.setString(i++, claimedBy);
                statement.setTimestamp(i++, timestamp(publishedAt));
                statement.setString(i++, lastError);
                statement.setString(i++, errorType);
                statement.setTimestamp(i++, timestamp(createdAt));
                statement.setTimestamp(i++, timestamp(updatedAt));
                statement.setString(i, scopeKey);
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getLong(1);
                }
            }
        }
    }

    // --- Future helpers ---

    static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    static Throwable failureOf(Future<?> future) {
        try {
            await(future);
        } catch (java.util.concurrent.ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            throw new AssertionError("expected the future to fail but waiting failed: " + e, e);
        }
        throw new AssertionError("expected the future to fail");
    }

    private static void bind(PreparedStatement statement, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object param = params[i];
            if (param instanceof Instant instant) {
                statement.setTimestamp(i + 1, Timestamp.from(instant));
            } else {
                statement.setObject(i + 1, param);
            }
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
