// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proof for the shared-Postgres-server contract: a no-arg {@link PostgresContainer} provisions its own
 * <em>database</em> on one shared server per JVM fork, rather than starting its own dedicated
 * container.
 *
 * <p>Every no-arg {@code PostgresContainer} instance in the same JVM fork shares one lazily-started
 * {@code PostgreSQLContainer} (same host + mapped port); each instance provisions its own uniquely
 * named database on that server and is isolated from every other instance at the database level.
 * Closing an instance drops only its own database — the shared server is never stopped and survives
 * for the rest of the fork.
 *
 * <p>This class manages its own {@link PostgresContainer} instances directly (no {@link
 * DatabaseExtension}) so each test can start/stop exactly the instances it needs.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
public class PostgresContainerSharedServerIT {

    private PostgresContainer containerA;
    private PostgresContainer containerB;

    @AfterEach
    void tearDown() {
        closeQuietly(containerA);
        closeQuietly(containerB);
        containerA = null;
        containerB = null;
    }

    @Test
    @DisplayName("two no-arg PostgresContainer instances share one server (same host+port, different database)")
    void twoContainersShareOneServer() {
        containerA = new PostgresContainer().withDatabaseName("shared_probe_db");
        containerB = new PostgresContainer().withDatabaseName("shared_probe_db");
        containerA.start();
        containerB.start();

        DbPoolConfig configA = containerA.toPoolConfig();
        DbPoolConfig configB = containerB.toPoolConfig();

        assertEquals(configA.host(), configB.host(), "shared-server instances must report the same host");
        assertEquals(configA.port(), configB.port(), "shared-server instances must report the same mapped port");
        assertNotEquals(
                configA.database(), configB.database(), "each instance must provision its own per-instance database");
    }

    @Test
    @DisplayName("migrations applied to one instance's database are invisible from the other's")
    void migrationsApplyPerDatabase() throws SQLException {
        containerA =
                new PostgresContainer().withDatabaseName("shared_probe_db").withMigration("classpath:db/testkit-probe");
        containerB =
                new PostgresContainer().withDatabaseName("shared_probe_db").withMigration("classpath:db/testkit-probe");
        containerA.start();
        containerB.start();

        try (Connection connA = connectTo(containerA);
                Connection connB = connectTo(containerB)) {
            assertTrue(tableExists(connA, "shared_probe"), "shared_probe table must exist in instance A's database");
            assertTrue(tableExists(connB, "shared_probe"), "shared_probe table must exist in instance B's database");

            try (Statement insert = connA.createStatement()) {
                insert.executeUpdate("INSERT INTO shared_probe (id, note) VALUES (1, 'from-a')");
            }

            assertEquals(1, countRows(connA, "shared_probe"), "instance A must see its own inserted row");
            assertEquals(0, countRows(connB, "shared_probe"), "instance B's database must not see instance A's row");
        }
    }

    @Test
    @DisplayName("closing one instance drops its database but the other instance's server/database survives")
    void closeDropsDatabaseButServerSurvives() throws SQLException {
        containerA = new PostgresContainer().withDatabaseName("shared_probe_db");
        containerB = new PostgresContainer().withDatabaseName("shared_probe_db");
        containerA.start();
        containerB.start();

        String jdbcUrlA = containerA.jdbcUrl();
        String usernameA = containerA.username();
        String passwordA = containerA.password();

        containerA.close();

        assertThrows(
                SQLException.class,
                () -> DriverManager.getConnection(jdbcUrlA, usernameA, passwordA),
                "connecting to a closed instance's database must fail");

        try (Connection connB = connectTo(containerB);
                Statement stmt = connB.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT 1")) {
            assertTrue(rs.next(), "the surviving instance must still answer a query");
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    @DisplayName("a database-name hint is sanitized to a Postgres-safe identifier and truncated to 63 characters")
    void databaseNameHintIsSanitizedAndTruncated() {
        containerA = new PostgresContainer().withDatabaseName("Weird Name!-XL");
        containerA.start();

        String database = containerA.toPoolConfig().database();
        assertTrue(
                database.matches("^weird_name__xl_\\d+$"),
                "hint must be lowercased, non [a-z0-9_] characters replaced with '_', and suffixed with a counter, "
                        + "but was: " + database);

        containerB = new PostgresContainer().withDatabaseName("x".repeat(80));
        containerB.start();

        String longDatabase = containerB.toPoolConfig().database();
        assertTrue(
                longDatabase.length() <= 63,
                "an 80-char hint must be truncated to fit the 63-character Postgres identifier limit, " + "but was "
                        + longDatabase.length() + " chars: " + longDatabase);
    }

    // --- Internal helpers ---

    private static Connection connectTo(PostgresContainer container) throws SQLException {
        return DriverManager.getConnection(container.jdbcUrl(), container.username(), container.password());
    }

    private static boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT to_regclass('" + tableName + "') IS NOT NULL")) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    private static int countRows(Connection conn, String tableName) throws SQLException {
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT count(*) FROM " + tableName)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private static void closeQuietly(PostgresContainer container) {
        if (container == null) {
            return;
        }
        try {
            container.close();
        } catch (RuntimeException ignored) {
            // best-effort teardown; a container that failed to start may throw on close
        }
    }
}
