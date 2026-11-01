// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.job.JobState;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
 * Proves the job scope migration against a database that already holds job executions.
 *
 * <p>The container starts without a migration so the test controls the target version: it migrates to the
 * version before the scope migration, seeds one execution per state, and then applies the rest.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class JobScopeMigrationIT {

    private static final String JOB_MIGRATIONS = "classpath:db/migration/job";
    private static final String VERSION_BEFORE_SCOPE = "002";

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("job_scope_migration");

    private long relfilenodeBefore;

    @BeforeEach
    void givenVersionTwoWithARowInEveryState() throws SQLException {
        flyway().target(MigrationVersion.fromVersion(VERSION_BEFORE_SCOPE))
                .load()
                .migrate();
        for (JobState state : JobState.values()) {
            insertExecutionIn(state);
        }
        relfilenodeBefore = relfilenode();
    }

    @Test
    @DisplayName("existing rows read the default scope without the table being rewritten")
    void existingRowsReadDefaultWithoutRewrite() throws SQLException {
        // When: the scope migration is applied
        flyway().load().migrate();

        // Then: every existing row reads the default scope and the table file is unchanged
        assertEquals(everyStateMappedTo("default"), scopeKeyByState());
        assertEquals(relfilenodeBefore, relfilenode());
    }

    // --- Helpers ---

    private static FluentConfiguration flyway() {
        return Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations(JOB_MIGRATIONS);
    }

    private static Map<String, String> everyStateMappedTo(String scopeKey) {
        Map<String, String> expected = new HashMap<>();
        for (JobState state : JobState.values()) {
            expected.put(state.name(), scopeKey);
        }
        return expected;
    }

    private static void insertExecutionIn(JobState state) throws SQLException {
        try (Connection connection = jdbc();
                PreparedStatement statement = connection.prepareStatement("INSERT INTO job_executions "
                        + "(id, job_id, job_type, handler, state) VALUES (?, ?, 'DELAYED', ?, ?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, "legacy-" + state.name());
            statement.setString(3, "job.delayed.legacy");
            statement.setString(4, state.name());
            statement.executeUpdate();
        }
    }

    private static Map<String, String> scopeKeyByState() throws SQLException {
        Map<String, String> byState = new HashMap<>();
        try (Connection connection = jdbc();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT state, scope_key FROM job_executions ORDER BY state");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                byState.put(rows.getString("state"), rows.getString("scope_key"));
            }
        }
        return byState;
    }

    private static long relfilenode() throws SQLException {
        try (Connection connection = jdbc();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT relfilenode FROM pg_class WHERE oid = 'job_executions'::regclass");
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static Connection jdbc() throws SQLException {
        return DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
    }
}
