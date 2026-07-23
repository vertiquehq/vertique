// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.test;

import dev.vertique.db.DbPoolConfig;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL test container wrapping a {@link PostgreSQLContainer}.
 *
 * <p>The no-arg constructor runs in <b>shared-server mode</b>: instead of starting a dedicated
 * {@code PostgreSQLContainer} per instance, it provisions its own database on one lazily-started
 * server shared by every no-arg {@code PostgresContainer} in the same JVM fork (see {@link
 * SharedPostgresServer}). This mirrors the shared-broker pattern in {@code KafkaTestContainers}
 * (vertique-kafka-test): one verified container per fork removes per-class container-start
 * churn (~2-4s + JVM wait each) and replaces it with a {@code CREATE DATABASE} (~50ms), with
 * per-instance isolation at the database level instead of the container level. The shared server
 * is intentionally never stopped — Testcontainers' Ryuk sidecar reaps it when the JVM exits;
 * callers of a shared-mode instance's {@link #close()} only drop that instance's own database.
 *
 * <p>{@link #PostgresContainer(String)} (custom image) keeps the original dedicated-container
 * behavior unchanged — the escape hatch for image-specific tests. Custom credentials via {@link
 * #withUsername(String)} / {@link #withPassword(String)} are only supported in dedicated mode; the
 * shared server's credentials are whatever it was started with (see {@link SharedPostgresServer}).
 *
 * <p>Usage:
 *
 * <pre>{@code
 * static final PostgresContainer db = new PostgresContainer()
 *     .withDatabaseName("test_db")
 *     .withMigration();
 * }</pre>
 */
public class PostgresContainer extends DatabaseContainer<PostgresContainer, PostgreSQLContainer> {

    private static final String DEFAULT_IMAGE = "postgres:16-alpine";

    private static final String DEFAULT_DATABASE_HINT = "test_db";

    private final boolean sharedMode;
    private String databaseNameHint = DEFAULT_DATABASE_HINT;
    private String provisionedDatabase;

    /** Creates a PostgreSQL container with the default image ({@code postgres:16-alpine}) in shared-server mode. */
    public PostgresContainer() {
        super(new PostgreSQLContainer(DEFAULT_IMAGE));
        this.sharedMode = true;
    }

    /**
     * Creates a PostgreSQL container with a custom Docker image, running its own dedicated
     * container (shared-server mode is only available via the no-arg constructor).
     *
     * @param dockerImageName the Docker image name (e.g., {@code "postgres:15"})
     */
    public PostgresContainer(String dockerImageName) {
        super(new PostgreSQLContainer(dockerImageName));
        this.sharedMode = false;
    }

    /**
     * Sets the database name. In shared-server mode this only records the hint used to derive the
     * per-instance database name at {@link #start()}; the shared server itself is never mutated. In
     * dedicated mode this delegates to the wrapped container as before.
     *
     * @param name the database name
     * @return this container for chaining
     */
    public PostgresContainer withDatabaseName(String name) {
        if (sharedMode) {
            this.databaseNameHint = name;
        } else {
            container.withDatabaseName(name);
        }
        return this;
    }

    /**
     * Sets the database username. Only supported in dedicated mode (custom-image constructor); the
     * shared server's credentials are not per-instance configurable.
     *
     * @param username the username
     * @return this container for chaining
     * @throws UnsupportedOperationException if this instance is in shared-server mode
     */
    public PostgresContainer withUsername(String username) {
        if (sharedMode) {
            throw new UnsupportedOperationException(
                    "Custom credentials are not supported in shared-server mode; use PostgresContainer(String) "
                            + "(custom image) for a dedicated container with custom credentials.");
        }
        container.withUsername(username);
        return this;
    }

    /**
     * Sets the database password. Only supported in dedicated mode (custom-image constructor); the
     * shared server's credentials are not per-instance configurable.
     *
     * @param password the password
     * @return this container for chaining
     * @throws UnsupportedOperationException if this instance is in shared-server mode
     */
    public PostgresContainer withPassword(String password) {
        if (sharedMode) {
            throw new UnsupportedOperationException(
                    "Custom credentials are not supported in shared-server mode; use PostgresContainer(String) "
                            + "(custom image) for a dedicated container with custom credentials.");
        }
        container.withPassword(password);
        return this;
    }

    /**
     * Starts this container. In shared-server mode: provisions a unique per-instance database on
     * the shared server (starting it first if needed), then runs Flyway migrations (if configured)
     * against that database. In dedicated mode: starts the wrapped container and runs migrations as
     * before. Idempotent in shared-server mode: calling {@code start()} again on an instance that
     * already has a provisioned database is a no-op that returns {@code this}, mirroring
     * Testcontainers' own idempotent {@code start()} and preventing a silent re-provision that would
     * orphan the previously provisioned database. If migration fails, the provisioned database is
     * dropped and this instance is reset so a retried {@code start()} provisions and migrates
     * afresh — matching dedicated mode, where a second {@code start()} after a migration failure
     * also retries migrations. Shared-mode {@code start()}/{@code close()} are synchronized so the
     * documented idempotency holds even under concurrent callers.
     *
     * @return this container for chaining
     */
    @Override
    public PostgresContainer start() {
        if (!sharedMode) {
            return super.start();
        }
        synchronized (this) {
            if (provisionedDatabase != null) {
                return this;
            }
            String database = SharedPostgresServer.provisionDatabase(databaseNameHint);
            provisionedDatabase = database;
            try {
                runMigrations();
            } catch (RuntimeException e) {
                // Failed start must stay retryable: drop the half-migrated database and reset so
                // connection identity is not readable and a later start() provisions afresh.
                provisionedDatabase = null;
                SharedPostgresServer.dropDatabaseQuietly(database);
                throw e;
            }
            return this;
        }
    }

    /**
     * Returns the JDBC URL for direct JDBC access with SSL disabled. In shared-server mode this
     * points at the shared server's host/port with the database swapped to this instance's
     * provisioned database.
     *
     * <p>PostgreSQL containers do not enable SSL, but the JDBC driver attempts SSL negotiation
     * by default. This can cause a {@link java.net.SocketTimeoutException} when the container
     * is ready at TCP level but has not yet completed SSL setup. Appending {@code sslmode=disable}
     * skips the SSL handshake entirely and prevents flaky connection failures in Flyway migrations.
     *
     * @return the JDBC URL with {@code sslmode=disable} appended
     * @throws IllegalStateException if called in shared-server mode before {@link #start()}
     */
    @Override
    public String jdbcUrl() {
        String url = sharedMode ? sharedModeJdbcUrl() : container.getJdbcUrl();
        if (url.contains("sslmode=")) {
            return url;
        }
        return url.contains("?") ? url + "&sslmode=disable" : url + "?sslmode=disable";
    }

    /**
     * Returns the database username: the shared server's superuser username in shared-server mode,
     * or the wrapped container's username in dedicated mode.
     *
     * @return the username
     * @throws IllegalStateException if called in shared-server mode before {@link #start()}
     */
    @Override
    public String username() {
        if (sharedMode) {
            requireStarted();
            return SharedPostgresServer.username();
        }
        return container.getUsername();
    }

    /**
     * Returns the database password: the shared server's superuser password in shared-server mode,
     * or the wrapped container's password in dedicated mode.
     *
     * @return the password
     * @throws IllegalStateException if called in shared-server mode before {@link #start()}
     */
    @Override
    public String password() {
        if (sharedMode) {
            requireStarted();
            return SharedPostgresServer.password();
        }
        return container.getPassword();
    }

    /**
     * Builds a {@link DbPoolConfig} pointing to this container's database.
     *
     * @return a pool config configured for the running container
     * @throws IllegalStateException if called in shared-server mode before {@link #start()}
     */
    @Override
    public DbPoolConfig toPoolConfig() {
        if (sharedMode) {
            requireStarted();
            return DbPoolConfig.builder()
                    .host(SharedPostgresServer.host())
                    .port(SharedPostgresServer.port())
                    .database(provisionedDatabase)
                    .user(SharedPostgresServer.username())
                    .password(SharedPostgresServer.password())
                    .build();
        }
        return DbPoolConfig.builder()
                .host(container.getHost())
                .port(container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT))
                .database(container.getDatabaseName())
                .user(container.getUsername())
                .password(container.getPassword())
                .build();
    }

    /**
     * Returns the underlying Testcontainers container. Only supported in dedicated mode, where it
     * returns the wrapped container as before.
     *
     * @return the wrapped container
     * @throws UnsupportedOperationException if this instance is in shared-server mode, since a
     *     shared-mode instance has no dedicated container of its own — use {@link #jdbcUrl()} or
     *     {@link #toPoolConfig()} for connection access, or {@link #PostgresContainer(String)}
     *     (custom image) for a dedicated container with raw-container access
     */
    @Override
    public PostgreSQLContainer getContainer() {
        if (sharedMode) {
            throw new UnsupportedOperationException(
                    "getContainer() is not supported in shared-server mode; this instance has no dedicated "
                            + "container. Use jdbcUrl() / toPoolConfig() for connection access, or "
                            + "PostgresContainer(String) (custom image) for a dedicated container with raw-container "
                            + "access.");
        }
        return super.getContainer();
    }

    /**
     * Closes this container. In shared-server mode this only best-effort drops this instance's
     * provisioned database; the shared server is never stopped (Ryuk reaps it at JVM exit). In
     * dedicated mode this stops the wrapped container as before. Idempotent: closing an
     * already-closed or never-started shared-mode instance is a no-op.
     */
    @Override
    public void close() {
        if (!sharedMode) {
            super.close();
            return;
        }
        synchronized (this) {
            if (provisionedDatabase == null) {
                return;
            }
            SharedPostgresServer.dropDatabaseQuietly(provisionedDatabase);
            provisionedDatabase = null;
        }
    }

    // --- Internal helpers ---

    private String sharedModeJdbcUrl() {
        requireStarted();
        return SharedPostgresServer.jdbcUrl(provisionedDatabase);
    }

    private void requireStarted() {
        if (provisionedDatabase == null) {
            throw new IllegalStateException("start() the container before reading its connection identity");
        }
    }
}
