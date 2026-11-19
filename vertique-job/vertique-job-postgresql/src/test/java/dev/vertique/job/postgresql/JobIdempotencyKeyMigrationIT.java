// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.job.JobState;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * Proves the idempotency key migration against a database that already holds job executions.
 *
 * <p>The container starts without a migration so the test controls the target version: it migrates to the
 * version before the key migration, seeds one execution per state, and then applies the rest.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class JobIdempotencyKeyMigrationIT {

    private static final String JOB_MIGRATIONS = "classpath:db/migration/job";
    private static final String VERSION_BEFORE_KEY = "003";
    private static final String INDEX = "idx_job_executions_idempotency_key";

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("job_key_migration");

    private long relfilenodeBefore;

    @BeforeEach
    void givenVersionThreeWithARowInEveryState() throws SQLException {
        // The container is shared by the tests of this class, so start each from an empty schema.
        flyway().cleanDisabled(false).load().clean();
        flyway().target(MigrationVersion.fromVersion(VERSION_BEFORE_KEY)).load().migrate();
        for (JobState state : JobState.values()) {
            insertExecution("legacy-" + state.name(), state.name(), null);
        }
        relfilenodeBefore = relfilenode();
    }

    @Test
    @DisplayName("existing rows read no key without the table being rewritten, and the index is valid")
    void existingRowsReadNoKeyWithoutRewrite() throws SQLException {
        // When: the key migration is applied
        flyway().load().migrate();

        // Then: every existing row has no key, the table file is unchanged and the index is valid
        assertEquals(JobState.values().length, countRowsWithoutKey());
        assertEquals(relfilenodeBefore, relfilenode());
        assertTrue(indexIsValid());
    }

    @Test
    @DisplayName(
            "the index refuses a second unfinished execution with the key and accepts one after the first finished")
    void indexEnforcesUniquenessAmongUnfinishedExecutions() throws SQLException {
        // Given: the migrated schema and an unfinished execution holding a key
        flyway().load().migrate();
        insertExecution("first", "FAILED", "held");

        // When / Then: a second unfinished execution with the key is refused
        SQLException refused = assertThrows(SQLException.class, () -> insertExecution("second", "ENQUEUED", "held"));
        assertEquals("23505", refused.getSQLState());

        // And: a finished execution does not count, and a key may repeat once the holder finished
        insertExecution("finished", "SUCCEEDED", "held");
        setState("first", "DEAD_LETTER");
        insertExecution("third", "ENQUEUED", "held");
    }

    @Test
    @DisplayName("a rerun of the migration after an invalid index was left behind rebuilds a valid index")
    void rerunReplacesAnInvalidIndex() throws SQLException {
        // Given: the migrated schema, then an index of the name that is marked invalid (as a cancelled concurrent
        // build leaves it)
        flyway().load().migrate();
        execute("UPDATE pg_index SET indisvalid = false WHERE indexrelid = to_regclass('" + INDEX + "')");
        assertEquals(false, indexIsValid());

        // When: the migration script runs again
        execute(readMigrationScript());

        // Then: the index is valid again
        assertTrue(indexIsValid());
    }

    // --- Helpers ---

    private static String readMigrationScript() throws SQLException {
        try (var in = JobIdempotencyKeyMigrationIT.class.getResourceAsStream(
                "/db/migration/job/V004__add_job_execution_idempotency_key.sql")) {
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

    private static void insertExecution(String jobId, String state, String key) throws SQLException {
        boolean keyed = key != null;
        try (Connection connection = jdbc();
                PreparedStatement statement = connection.prepareStatement(
                        keyed
                                ? "INSERT INTO job_executions (id, job_id, job_type, handler, state, idempotency_key)"
                                        + " VALUES (?, ?, 'DELAYED', 'job.delayed.legacy', ?, ?)"
                                : "INSERT INTO job_executions (id, job_id, job_type, handler, state)"
                                        + " VALUES (?, ?, 'DELAYED', 'job.delayed.legacy', ?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, jobId);
            statement.setString(3, state);
            if (keyed) {
                statement.setString(4, key);
            }
            statement.executeUpdate();
        }
    }

    private static void setState(String jobId, String state) throws SQLException {
        try (Connection connection = jdbc();
                PreparedStatement statement =
                        connection.prepareStatement("UPDATE job_executions SET state = ? WHERE job_id = ?")) {
            statement.setString(1, state);
            statement.setString(2, jobId);
            statement.executeUpdate();
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = jdbc();
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int countRowsWithoutKey() throws SQLException {
        try (Connection connection = jdbc();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM job_executions WHERE idempotency_key IS NULL");
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static boolean indexIsValid() throws SQLException {
        try (Connection connection = jdbc();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT indisvalid FROM pg_index WHERE indexrelid = to_regclass('" + INDEX + "')");
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getBoolean(1);
        }
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
