// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared, lazily-started Testcontainers PostgreSQL server for {@link PostgresContainer}'s
 * shared-server mode. A single server is started once per JVM (i.e. once per Maven
 * Surefire/Failsafe fork) and reused by every no-arg {@code PostgresContainer} in that fork via
 * {@link #provisionDatabase(String)}, rather than each instance starting its own dedicated
 * container.
 *
 * <p><b>Why a shared server.</b> This mirrors the shared-broker pattern in {@code
 * KafkaTestContainers} (vertique-kafka-test): one verified container per fork removes
 * per-class container-start churn (~2-4s + JVM wait each) and replaces it with a {@code CREATE
 * DATABASE} (~50ms), with per-instance isolation moved from the container level to the database
 * level.
 *
 * <p>The shared server is intentionally never stopped: Testcontainers' Ryuk sidecar reaps it when
 * the JVM exits. Callers must therefore NOT stop it; only individual per-instance databases are
 * dropped via {@link #dropDatabaseQuietly(String)}.
 */
final class SharedPostgresServer {

    private static final String DEFAULT_IMAGE = "postgres:16-alpine";

    private static final String DEFAULT_DATABASE_HINT = "test_db";

    /** Startup-log wait timeout; the 60s Testcontainers default is tight under reactor/CI load. */
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(120);

    /** Disambiguates per-instance database names sharing the same sanitized hint. */
    private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();

    /** Max length Postgres allows for an unquoted/quoted identifier. */
    private static final int MAX_IDENTIFIER_LENGTH = 63;

    private static volatile PostgreSQLContainer sharedServer;

    private SharedPostgresServer() {}

    /**
     * Creates a uniquely-named database on the shared server derived from {@code hint}, starting
     * the shared server first if it is not already running.
     *
     * @param hint the raw database-name hint (e.g. from {@code PostgresContainer#withDatabaseName})
     * @return the generated, unique database name that was created
     */
    static String provisionDatabase(String hint) {
        PostgreSQLContainer server = sharedServer();
        String name = uniqueDatabaseName(hint);
        try (Connection conn =
                        DriverManager.getConnection(server.getJdbcUrl(), server.getUsername(), server.getPassword());
                Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE DATABASE \"" + name + "\"");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to provision shared-server database " + name, e);
        }
        return name;
    }

    /**
     * Best-effort drops {@code database} from the shared server via {@code DROP DATABASE ... WITH
     * (FORCE)}. Only {@link SQLException} is swallowed by design — this is hygiene only; the shared
     * server outlives every per-instance database and is reaped by Ryuk at JVM exit regardless. If no
     * shared server has been started yet, this is a no-op: there is nothing to drop, and booting a
     * server just to drop from it would be wasted work.
     *
     * @param database the database name to drop
     * @throws IllegalArgumentException if {@code database} is not a valid Postgres-safe identifier
     */
    static void dropDatabaseQuietly(String database) {
        requireValidIdentifier(database);
        if (sharedServer == null) {
            return;
        }
        PostgreSQLContainer server = sharedServer();
        try (Connection conn =
                        DriverManager.getConnection(server.getJdbcUrl(), server.getUsername(), server.getPassword());
                Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DROP DATABASE IF EXISTS \"" + database + "\" WITH (FORCE)");
        } catch (SQLException ignored) {
            // Best-effort teardown; the shared server survives regardless of drop success.
        }
    }

    /**
     * Builds the JDBC URL for {@code database} on the shared server.
     *
     * @param database the per-instance database name
     * @return the JDBC URL pointing at {@code database} on the shared server
     * @throws IllegalArgumentException if {@code database} is not a valid Postgres-safe identifier
     */
    static String jdbcUrl(String database) {
        requireValidIdentifier(database);
        return String.format("jdbc:postgresql://%s:%d/%s", host(), port(), database);
    }

    /**
     * Returns the shared server's host.
     *
     * @return the host
     */
    static String host() {
        return sharedServer().getHost();
    }

    /**
     * Returns the shared server's mapped port.
     *
     * @return the mapped port
     */
    static int port() {
        return sharedServer().getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
    }

    /**
     * Returns the shared server's superuser username, starting it first if it is not already
     * running.
     *
     * @return the username
     */
    static String username() {
        return sharedServer().getUsername();
    }

    /**
     * Returns the shared server's superuser password, starting it first if it is not already
     * running.
     *
     * @return the password
     */
    static String password() {
        return sharedServer().getPassword();
    }

    // --- Shared server lifecycle ---

    /**
     * Returns the shared PostgreSQL server for this JVM, starting it on first call. Subsequent
     * calls return the same instance. Callers must never stop it.
     *
     * @return the shared, started server
     */
    private static PostgreSQLContainer sharedServer() {
        PostgreSQLContainer local = sharedServer;
        if (local != null) {
            return local;
        }
        return initSharedServer();
    }

    private static synchronized PostgreSQLContainer initSharedServer() {
        if (sharedServer == null) {
            PostgreSQLContainer server = new PostgreSQLContainer(DEFAULT_IMAGE).withStartupTimeout(STARTUP_TIMEOUT);
            server.start();
            sharedServer = server;
        }
        return sharedServer;
    }

    // --- Per-instance database naming ---

    /**
     * Derives a unique, Postgres-safe database name from {@code hint}: lowercases it, replaces every
     * character outside {@code [a-z0-9_]} with {@code _}, appends a monotonically increasing counter
     * suffix, and truncates the base to fit the 63-character Postgres identifier limit.
     *
     * @param hint the raw database-name hint
     * @return a sanitized, unique database name no longer than 63 characters
     */
    private static String uniqueDatabaseName(String hint) {
        String sanitized = (hint == null ? DEFAULT_DATABASE_HINT : hint)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]", "_");
        String suffix = "_" + DATABASE_COUNTER.incrementAndGet();
        int maxBaseLength = MAX_IDENTIFIER_LENGTH - suffix.length();
        if (sanitized.length() > maxBaseLength) {
            sanitized = sanitized.substring(0, maxBaseLength);
        }
        return sanitized + suffix;
    }

    /**
     * Validates that {@code database} is a Postgres-safe identifier produced by {@link
     * #uniqueDatabaseName(String)}: lowercase letters, digits, and underscores only, 1-63
     * characters. Defense-in-depth at this package's public boundary — {@link
     * #provisionDatabase(String)}'s internally-derived name already satisfies this, but {@link
     * #dropDatabaseQuietly(String)} and {@link #jdbcUrl(String)} also accept a caller-supplied
     * database name that must be re-validated before being interpolated into SQL/URLs.
     *
     * @param database the database name to validate
     * @throws IllegalArgumentException if {@code database} is not a valid Postgres-safe identifier
     */
    private static void requireValidIdentifier(String database) {
        if (database == null || !database.matches("[a-z0-9_]{1,63}")) {
            throw new IllegalArgumentException("Invalid database identifier: " + database);
        }
    }
}
