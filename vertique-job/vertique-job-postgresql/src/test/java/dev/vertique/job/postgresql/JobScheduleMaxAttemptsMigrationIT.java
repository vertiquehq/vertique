// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves the migration that drops the attempt limit of a cron schedule against a database that already holds
 * schedules.
 *
 * <p>The container starts without a migration so the test controls the target version: it migrates to the version
 * before the drop, seeds a schedule, and then applies the rest.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class JobScheduleMaxAttemptsMigrationIT {

    private static final String JOB_MIGRATIONS = "classpath:db/migration/job";
    private static final String VERSION_BEFORE_DROP = "004";

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("job_schedule_drop_migration");

    @BeforeEach
    void givenVersionFourWithAScheduleThatCarriesTheLimit() throws SQLException {
        // The container is shared by the tests of this class, so start each from an empty schema.
        flyway().cleanDisabled(false).load().clean();
        flyway().target(MigrationVersion.fromVersion(VERSION_BEFORE_DROP))
                .load()
                .migrate();
        execute("INSERT INTO job_schedules (job_id, cron_expression, target, max_attempts)"
                + " VALUES ('legacy-schedule', '0 0 8 * * *', 'service:legacy.op', 3)");
        assertTrue(scheduleHasColumn("max_attempts"));
    }

    @Test
    @DisplayName("the drop removes the limit and keeps every other column of the existing schedule")
    void dropRemovesTheLimitAndKeepsTheSchedule() throws SQLException {
        // When: the drop migration is applied
        flyway().load().migrate();

        // Then: the column is gone and the schedule reads as before
        assertFalse(scheduleHasColumn("max_attempts"));
        try (Connection connection = jdbc();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT cron_expression, target, execution_mode, overlap_policy, tracked FROM job_schedules"
                                + " WHERE job_id = 'legacy-schedule'");
                ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertEquals("0 0 8 * * *", rows.getString("cron_expression"));
            assertEquals("service:legacy.op", rows.getString("target"));
            assertEquals("EVERY_INSTANCE", rows.getString("execution_mode"));
            assertEquals("SKIP", rows.getString("overlap_policy"));
            assertTrue(rows.getBoolean("tracked"));
        }
    }

    @Test
    @DisplayName("the per-execution limit of job_executions is untouched")
    void executionLimitStays() throws SQLException {
        flyway().load().migrate();

        try (Connection connection = jdbc();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT count(*) FROM information_schema.columns"
                                + " WHERE table_name = 'job_executions' AND column_name = 'max_attempts'");
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            assertEquals(1, rows.getInt(1));
        }
    }

    @Test
    @DisplayName("a rerun of the migration script after the column is gone succeeds")
    void rerunSucceeds() throws SQLException {
        flyway().load().migrate();

        execute(readMigrationScript());

        assertFalse(scheduleHasColumn("max_attempts"));
    }

    // --- Helpers ---

    private static String readMigrationScript() throws SQLException {
        try (var in = JobScheduleMaxAttemptsMigrationIT.class.getResourceAsStream(
                "/db/migration/job/V005__drop_job_schedules_max_attempts.sql")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new SQLException(e);
        }
    }

    private static FluentConfiguration flyway() {
        return Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations(JOB_MIGRATIONS);
    }

    private static boolean scheduleHasColumn(String column) throws SQLException {
        try (Connection connection = jdbc();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT count(*) FROM information_schema.columns"
                                + " WHERE table_name = 'job_schedules' AND column_name = ?")) {
            statement.setString(1, column);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1) == 1;
            }
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = jdbc();
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Connection jdbc() throws SQLException {
        return DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
    }
}
