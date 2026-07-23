// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.test;

import dev.vertique.db.DbPoolConfig;
import java.io.Closeable;
import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * Abstract base for database test containers. Wraps a Testcontainers {@link JdbcDatabaseContainer}
 * with optional schema migration support.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * static final PostgresContainer db = new PostgresContainer()
 *     .withDatabaseName("test_db")
 *     .withMigration();
 *
 * @BeforeAll
 * static void startDb() { db.start(); }
 *
 * @AfterAll
 * static void stopDb() { db.close(); }
 * }</pre>
 *
 * @param <SELF> the concrete container type (for fluent API)
 * @param <C>    the Testcontainers container type
 */
public abstract class DatabaseContainer<SELF extends DatabaseContainer<SELF, C>, C extends JdbcDatabaseContainer<?>>
        implements Closeable {

    /** The underlying Testcontainers container. */
    protected final C container;

    private String migrationLocations;

    /**
     * Constructs a new container wrapping the given Testcontainers container.
     *
     * @param container the Testcontainers container to wrap
     */
    protected DatabaseContainer(C container) {
        this.container = container;
    }

    /**
     * Starts the container and optionally runs migrations.
     *
     * @return this container for chaining
     */
    @SuppressWarnings("unchecked")
    public SELF start() {
        container.start();
        runMigrations();
        return (SELF) this;
    }

    /**
     * Enables Flyway migration with the default location {@code classpath:db/migration}. Requires
     * {@code db-flyway} on the classpath.
     *
     * @return this container for chaining
     * @throws IllegalStateException if Flyway is not on the classpath
     */
    @SuppressWarnings("unchecked")
    public SELF withMigration() {
        return withMigration("classpath:db/migration");
    }

    /**
     * Enables Flyway migration with custom locations. Requires {@code db-flyway} on the classpath.
     *
     * @param locations Flyway migration locations (e.g., {@code "classpath:db/migration"})
     * @return this container for chaining
     * @throws IllegalStateException if Flyway is not on the classpath
     */
    @SuppressWarnings("unchecked")
    public SELF withMigration(String locations) {
        checkFlywayAvailable();
        this.migrationLocations = locations;
        return (SELF) this;
    }

    /**
     * Builds a {@link DbPoolConfig} pointing to this container.
     *
     * @return a pool config configured for the running container
     */
    public abstract DbPoolConfig toPoolConfig();

    /**
     * Returns the JDBC URL for direct JDBC access (e.g., Flyway).
     *
     * @return the JDBC URL
     */
    public String jdbcUrl() {
        return container.getJdbcUrl();
    }

    /**
     * Returns the database username.
     *
     * @return the username
     */
    public String username() {
        return container.getUsername();
    }

    /**
     * Returns the database password.
     *
     * @return the password
     */
    public String password() {
        return container.getPassword();
    }

    /**
     * Returns the underlying Testcontainers container.
     *
     * @return the container
     */
    public C getContainer() {
        return container;
    }

    /**
     * Runs Flyway migrations against this container's current connection identity ({@link
     * #jdbcUrl()}, {@link #username()}, {@link #password()}) if {@link #withMigration()} / {@link
     * #withMigration(String)} was called; otherwise this is a no-op. Exposed as {@code protected}
     * so subclasses (e.g. a shared-server {@code PostgresContainer}) can invoke it after
     * establishing their own per-instance connection identity, rather than only from the base
     * {@link #start()} flow.
     */
    protected final void runMigrations() {
        if (migrationLocations != null) {
            FlywayContainerMigrationRunner.run(jdbcUrl(), username(), password(), migrationLocations);
        }
    }

    /** Stops the underlying container. */
    @Override
    public void close() {
        container.stop();
    }

    private static void checkFlywayAvailable() {
        try {
            Class.forName("org.flywaydb.core.Flyway");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Flyway is not on the classpath. Add db-flyway as a test dependency to use withMigration().", e);
        }
    }
}
