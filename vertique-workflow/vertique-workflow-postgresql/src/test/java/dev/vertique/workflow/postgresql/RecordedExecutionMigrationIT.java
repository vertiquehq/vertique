// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

/** Verifies that the recorded-execution migration upgrades a populated database and notifies on runnable commands. */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class RecordedExecutionMigrationIT {

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("wf_recorded_execution_migration_it");

    private static final String INSTANCE_ID = "00000000-0000-0000-0000-0000000000a1";
    private static final String PENDING_INSTANCE_ID = "00000000-0000-0000-0000-0000000000a2";
    private static final String TASK_ID = "00000000-0000-0000-0000-0000000000b1";
    private static final String TIMER_ID = "00000000-0000-0000-0000-0000000000c1";
    private static final String NEW_TIMER_ID = "00000000-0000-0000-0000-0000000000c2";
    private static final String EXECUTION_ID = "00000000-0000-0000-0000-0000000000d1";
    private static final String AVAILABLE_AT = "2026-10-23T10:00:00Z";
    private static final String AVAILABLE_AT_EPOCH_MILLIS = "1792749600000";

    @BeforeEach
    void resetSchema() throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
    }

    @Test
    @DisplayName(
            "The migration upgrades a version 1 database, keeps its rows and accepts pending instances, commands and"
                    + " reservations")
    void upgradesVersion1AndAcceptsPendingInstancesCommandsAndReservations() throws Exception {
        // Given a database migrated to version 1 holding a running instance, an open task, a scheduled timer and a
        // history entry
        migrateTo("1");
        try (Connection connection = connection()) {
            insertRunningInstance(connection);
            insertOpenTask(connection);
            insertScheduledTimer(connection);
            insertHistoryEntry(connection);
        }

        // When the database is migrated to the latest version
        Flyway latest = flyway().load();
        latest.migrate();

        // Then version 2 is applied
        assertThat(latest.info().applied())
                .extracting(MigrationInfo::getVersion)
                .contains(MigrationVersion.fromVersion("2"));
        assertThat(latest.info().pending()).isEmpty();

        try (Connection connection = connection()) {
            // And the four existing rows read unchanged, the instance marked as not yet baselined and the history row
            // at its default entry version
            assertThat(rows(connection, """
                            SELECT status || '|' || current_step_id || '|' || state_json::text || '|' || baseline_logged
                            FROM workflow_instances WHERE id = '%s'
                            """.formatted(INSTANCE_ID))).containsExactly("RUNNING|start|{\"k\": 1}|false");
            assertThat(rows(connection, """
                            SELECT status || '|' || step_id FROM workflow_tasks WHERE task_id = '%s'
                            """.formatted(TASK_ID))).containsExactly("OPEN|approve");
            assertThat(rows(connection, """
                            SELECT status || '|' || delayed_job_execution_id FROM workflow_timers
                            WHERE timer_id = '%s'
                            """.formatted(TIMER_ID))).containsExactly("SCHEDULED|" + EXECUTION_ID);
            assertThat(rows(connection, """
                            SELECT entry_type || '|' || payload_json::text || '|' || entry_version
                            FROM workflow_history WHERE workflow_id = '%s' AND sequence = 1
                            """.formatted(INSTANCE_ID))).containsExactly("STARTED|{\"h\": 1}|1");

            // And the scheduled timer was converted: it links to one timer command and its instance has a queue row
            assertThat(rows(connection, """
                            SELECT c.kind || '|' || c.state FROM workflow_timers t
                            JOIN workflow_commands c ON c.id = t.command_id WHERE t.timer_id = '%s'
                            """.formatted(TIMER_ID))).containsExactly("TIMER|PENDING");

            // And an instance without state and without a current step inserts as PENDING
            insertPendingInstance(connection);

            // And a queue row and a START command at sequence 1 insert for it, the queue counting no open signals
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO workflow_command_queues (workflow_id, next_seq) VALUES ('%s', 2)"
                        .formatted(PENDING_INSTANCE_ID));
            }
            assertThat(rows(connection, "SELECT open_signals::text FROM workflow_command_queues"))
                    .containsExactly("0", "0");
            long startCommandId = insertCommand(connection, PENDING_INSTANCE_ID, 1, "START", "PENDING", null);

            // And a second command with the same workflow and sequence is a unique violation
            assertSqlState("23505", () -> insertCommand(connection, PENDING_INSTANCE_ID, 1, "SIGNAL", "PENDING", null));

            // And a state outside the eleven command states or a kind outside the six kinds fails its CHECK
            assertSqlState("23514", () -> insertCommand(connection, PENDING_INSTANCE_ID, 2, "SIGNAL", "RUNNING", null));
            assertSqlState("23514", () -> insertCommand(connection, PENDING_INSTANCE_ID, 2, "CANCEL", "PENDING", null));

            // And a timer kind is present exactly when the command kind is TIMER
            assertSqlState(
                    "23514", () -> insertCommand(connection, PENDING_INSTANCE_ID, 2, "SIGNAL", "PENDING", "FIRE"));
            assertSqlState("23514", () -> insertCommand(connection, PENDING_INSTANCE_ID, 2, "TIMER", "PENDING", null));
            insertCommand(connection, PENDING_INSTANCE_ID, 2, "TIMER", "PENDING", "FIRE");

            // And a command for an unknown instance is rejected by the foreign key
            assertSqlState(
                    "23503",
                    () -> insertCommand(
                            connection, "00000000-0000-0000-0000-0000000000ff", 1, "START", "PENDING", null));

            // And a task of the running instance updates to COMPLETING with a reservation owner
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "UPDATE workflow_tasks SET status = 'COMPLETING', reservation_command_id = %d WHERE task_id = '%s'"
                                .formatted(startCommandId, TASK_ID));
            }
            assertThat(rows(connection, "SELECT status || '|' || reservation_command_id FROM workflow_tasks"))
                    .containsExactly("COMPLETING|" + startCommandId);

            // And a status outside the task statuses still fails its CHECK
            assertSqlState("23514", () -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE workflow_tasks SET status = 'RESERVED'");
                }
            });

            // And a timer inserts without a delayed-job execution but with a command link
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO workflow_timers
                            (timer_id, workflow_id, step_id, status, fire_at, scheduled_at, command_id)
                        VALUES ('%s', '%s', 'wait', 'SCHEDULED', now(), now(), %d)
                        """.formatted(NEW_TIMER_ID, INSTANCE_ID, startCommandId));
            }
            assertThat(rows(connection, """
                            SELECT coalesce(delayed_job_execution_id::text, 'null') || '|' || command_id
                            FROM workflow_timers WHERE timer_id = '%s'
                            """.formatted(NEW_TIMER_ID))).containsExactly("null|" + startCommandId);

            // And a task accepts require_version_stability true, false and null
            for (String value : List.of("true", "false", "null")) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE workflow_tasks SET require_version_stability = " + value);
                }
                assertThat(rows(
                                connection,
                                "SELECT coalesce(require_version_stability::text, 'null') FROM workflow_tasks"))
                        .containsExactly(value);
            }

            // And a definition row accepts JSON in the signal names and the start schema
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO workflow_definitions
                            (scope_key, definition_id, definition_version, kind, contract_name, state_type_name,
                             plan_hash, status, source_type, loaded_at, signal_names_json, start_schema_json)
                        VALUES ('default', 'orders', 1, 'CODE', 'example.Contract', 'example.State',
                                repeat('a', 64), 'AVAILABLE', 'migration-test', now(),
                                '{"instance": ["approve"], "branches": {}}'::jsonb, '{"type": "object"}'::jsonb)
                        """);
            }
            assertThat(rows(
                            connection,
                            "SELECT signal_names_json::text || '|' || start_schema_json::text"
                                    + " FROM workflow_definitions"))
                    .containsExactly("{\"branches\": {}, \"instance\": [\"approve\"]}|{\"type\": \"object\"}");

            // And deleting the pending instance removes its commands and its queue row
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "DELETE FROM workflow_instances WHERE id = '%s'".formatted(PENDING_INSTANCE_ID));
            }
            assertThat(rows(connection, """
                            SELECT count(*)::text FROM workflow_commands WHERE workflow_id = '%s'
                            """.formatted(PENDING_INSTANCE_ID))).containsExactly("0");
            assertThat(rows(connection, """
                            SELECT count(*)::text FROM workflow_command_queues WHERE workflow_id = '%s'
                            """.formatted(PENDING_INSTANCE_ID))).containsExactly("0");
            // while the running instance keeps the converted timer command and its queue row
            assertThat(rows(connection, "SELECT count(*)::text FROM workflow_commands"))
                    .containsExactly("1");
            assertThat(rows(connection, "SELECT count(*)::text FROM workflow_command_queues"))
                    .containsExactly("1");
        }
    }

    @Test
    @DisplayName("A command notifies on commit only while it is runnable, with its availability as the payload")
    void notifiesOnCommitWhenACommandBecomesRunnable() throws Exception {
        // Given a migrated database, an instance and a connection listening on the command channel
        flyway().load().migrate();
        try (Connection connection = connection()) {
            insertPendingInstance(connection);
        }
        Connection listener = connection();
        Connection writer = connection();
        try {
            try (Statement statement = listener.createStatement()) {
                statement.execute("LISTEN vertique_workflow_commands");
            }
            writer.setAutoCommit(false);

            // When a second connection inserts a PENDING command, but has not committed
            long commandId = insertCommand(writer, PENDING_INSTANCE_ID, 1, "START", "PENDING", null);

            // Then nothing has been delivered yet
            assertThat(awaitNotifications(listener, 0, 500)).isEmpty();

            // And the commit delivers one notification carrying the availability in epoch milliseconds
            writer.commit();
            assertThat(awaitNotifications(listener, 1, 5000)).containsExactly(AVAILABLE_AT_EPOCH_MILLIS);

            // And claiming the command notifies nothing
            updateState(writer, commandId, "CLAIMED");
            writer.commit();
            assertThat(awaitNotifications(listener, 0, 500)).isEmpty();

            // And deferring it notifies once more with the same payload
            updateState(writer, commandId, "DEFERRED");
            writer.commit();
            assertThat(awaitNotifications(listener, 1, 5000)).containsExactly(AVAILABLE_AT_EPOCH_MILLIS);

            // And nothing else arrives
            assertThat(awaitNotifications(listener, 0, 500)).isEmpty();
        } finally {
            writer.close();
            listener.close();
        }
    }

    /** Drains the notifications that arrive within the timeout, returning early once the expected count arrived. */
    private static List<String> awaitNotifications(Connection listener, int expected, int timeoutMillis)
            throws SQLException {
        PGConnection pg = listener.unwrap(PGConnection.class);
        List<String> payloads = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline && (expected == 0 || payloads.size() < expected)) {
            PGNotification[] received = pg.getNotifications(100);
            if (received != null) {
                for (PGNotification notification : received) {
                    assertEquals("vertique_workflow_commands", notification.getName());
                    payloads.add(notification.getParameter());
                }
            }
        }
        return payloads;
    }

    private static void updateState(Connection connection, long commandId, String state) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "UPDATE workflow_commands SET state = '%s' WHERE id = %d".formatted(state, commandId));
        }
    }

    private static void migrateTo(String version) {
        flyway().target(version).load().migrate();
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
        return Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations("classpath:db/migration/workflow");
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
    }

    private static void insertRunningInstance(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO workflow_instances
                        (id, definition_id, definition_version, plan_hash, status, current_step_id, state_json)
                    VALUES ('%s', 'orders', 1, repeat('a', 64), 'RUNNING', 'start', '{"k": 1}'::jsonb)
                    """.formatted(INSTANCE_ID));
        }
    }

    private static void insertPendingInstance(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO workflow_instances
                        (id, definition_id, definition_version, plan_hash, status, current_step_id, state_json)
                    VALUES ('%s', 'orders', 1, repeat('a', 64), 'PENDING', NULL, NULL)
                    """.formatted(PENDING_INSTANCE_ID));
        }
    }

    private static void insertOpenTask(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO workflow_tasks
                        (task_id, workflow_id, step_id, status, assignee_type, assignee_key, decisions_snapshot_json)
                    VALUES ('%s', '%s', 'approve', 'OPEN', 'USER', 'alice', '[]'::jsonb)
                    """.formatted(TASK_ID, INSTANCE_ID));
        }
    }

    private static void insertScheduledTimer(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO workflow_timers
                        (timer_id, workflow_id, step_id, status, fire_at, delayed_job_execution_id, scheduled_at)
                    VALUES ('%s', '%s', 'wait', 'SCHEDULED', now(), '%s', now())
                    """.formatted(TIMER_ID, INSTANCE_ID, EXECUTION_ID));
        }
    }

    private static void insertHistoryEntry(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO workflow_history (workflow_id, sequence, entry_type, payload_json)
                    VALUES ('%s', 1, 'STARTED', '{"h": 1}'::jsonb)
                    """.formatted(INSTANCE_ID));
        }
    }

    /** Inserts a command at the given sequence and returns its generated id. */
    private static long insertCommand(
            Connection connection, String workflowId, long seq, String kind, String state, String timerKind)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_commands
                    (workflow_id, seq, kind, timer_kind, state, recorded_at, available_at)
                VALUES (?::uuid, ?, ?, ?, ?, now(), ?::timestamptz)
                RETURNING id
                """)) {
            insert.setString(1, workflowId);
            insert.setLong(2, seq);
            insert.setString(3, kind);
            insert.setString(4, timerKind);
            insert.setString(5, state);
            insert.setString(6, AVAILABLE_AT);
            try (ResultSet result = insert.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static List<String> rows(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (result.next()) {
                values.add(result.getString(1));
            }
            return values;
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    /** Asserts that the action fails with the given SQLSTATE, so the intended constraint family rejected it. */
    private static void assertSqlState(String expected, SqlAction action) {
        SQLException failure = assertThrows(SQLException.class, action::run);
        assertEquals(expected, failure.getSQLState(), "the row must be rejected by the intended constraint");
    }
}
