// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that the signal correlation migration upgrades a populated database, stores wait records that go with
 * their instance, leaves a node carrying only the previous migrations working, and keeps an index built ahead of the
 * upgrade.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class SignalCorrelationMigrationIT {

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("wf_signal_correlation_migration_it");

    private static final String MIGRATION_LOCATION = "db/migration/workflow";
    private static final List<String> PREVIOUS_RELEASE_SCRIPTS = List.of(
            "V1__create_workflow_tables.sql",
            "V002__workflow_definitions_and_scopes.sql",
            "V003__recorded_execution.sql");
    private static final String SIGNAL_CORRELATION_SCRIPT = "V004__signal_correlation.sql";

    private static final String INSTANCE_ID = "00000000-0000-0000-0000-0000000000a1";
    private static final String PENDING_INSTANCE_ID = "00000000-0000-0000-0000-0000000000a2";
    private static final String OTHER_INSTANCE_ID = "00000000-0000-0000-0000-0000000000a3";
    private static final String UNKNOWN_INSTANCE_ID = "00000000-0000-0000-0000-0000000000ff";
    private static final String WAIT_ID = "00000000-0000-0000-0000-0000000000e1";
    private static final String OTHER_WAIT_ID = "00000000-0000-0000-0000-0000000000e2";
    private static final String AVAILABLE_AT = "2026-10-23T10:00:00Z";
    private static final String NAMESPACE_HASH = "a".repeat(64);
    private static final String KEY_HASH = "b".repeat(64);

    /** Every column the command log has at version 3, in table order, as an older node lists them. */
    private static final String COMMAND_COLUMNS_AT_VERSION_3 = "id, workflow_id, seq, kind, timer_kind, timer_id,"
            + " task_id, target_json, payload_json, state, recorded_at, available_at, fire_at, attempts,"
            + " transient_count, deferral_count, claim_token, lease_until, claimed_by, context_json,"
            + " explicit_carrier_json, actor_kind, actor_id, actor_reason, on_behalf_of, attribution_status,"
            + " dedup_key, settled_at, last_error";

    @BeforeEach
    void resetSchema() throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
    }

    @Test
    @DisplayName("The migration upgrades a version 3 database, keeps its rows and accepts wait records and"
            + " command provenance")
    void upgradesPreviousSchemaAndAcceptsWaitRecordsAndProvenance() throws Exception {
        // Given a database migrated to version 3 holding a running instance, a pending instance and one SIGNAL
        // command with context_json set
        migrateTo("3");
        long signalCommandId;
        try (Connection connection = connection()) {
            insertRunningInstance(connection, INSTANCE_ID);
            insertPendingInstance(connection, PENDING_INSTANCE_ID);
            signalCommandId = insertSignalCommand(connection, PENDING_INSTANCE_ID, 1);
        }

        // When the database is migrated to the latest version
        Flyway latest = flyway().load();
        latest.migrate();

        // Then version 4 is applied with nothing pending
        assertThat(latest.info().applied())
                .extracting(MigrationInfo::getVersion)
                .contains(MigrationVersion.fromVersion("4"));
        assertThat(latest.info().pending()).isEmpty();

        try (Connection connection = connection()) {
            // And the seeded rows read unchanged
            assertThat(rows(connection, """
                            SELECT status || '|' || current_step_id || '|' || state_json::text
                            FROM workflow_instances WHERE id = '%s'
                            """.formatted(INSTANCE_ID))).containsExactly("RUNNING|start|{\"k\": 1}");
            assertThat(rows(connection, """
                            SELECT status || '|' || coalesce(current_step_id, 'null') || '|'
                                || coalesce(state_json::text, 'null')
                            FROM workflow_instances WHERE id = '%s'
                            """.formatted(PENDING_INSTANCE_ID))).containsExactly("PENDING|null|null");
            assertThat(rows(connection, """
                            SELECT kind || '|' || state || '|' || seq || '|' || context_json::text
                            FROM workflow_commands WHERE id = %d
                            """.formatted(signalCommandId))).containsExactly("SIGNAL|PENDING|1|{\"c\": 1}");

            // And the command's provenance_json is null
            assertThat(rows(connection, """
                            SELECT (provenance_json IS NULL)::text FROM workflow_commands WHERE id = %d
                            """.formatted(signalCommandId))).containsExactly("true");

            // And a wait record with every column inserts for the running instance
            insertWaitRecord(connection, WAIT_ID, INSTANCE_ID, "wait", "", "", "order-1");

            // And a second row with the same instance, step, fork step and branch but another wait id fails with a
            // unique violation
            assertSqlState(
                    "23505", () -> insertWaitRecord(connection, OTHER_WAIT_ID, INSTANCE_ID, "wait", "", "", "order-2"));

            // And a row with the first row's wait id fails with a unique violation
            assertSqlState("23505", () -> insertWaitRecord(connection, WAIT_ID, INSTANCE_ID, "other", "", "", "k"));

            // And a wait id of text that is not a UUID fails with an invalid text representation
            assertSqlState(
                    "22P02", () -> insertWaitRecord(connection, "not-a-uuid", INSTANCE_ID, "other", "", "", "k"));

            // And the wait id reads back as a UUID
            assertThat(waitIdsOf(connection, INSTANCE_ID)).containsExactly(UUID.fromString(WAIT_ID));

            // And the same step with another branch id inserts, as does the same step with another fork step id
            insertWaitRecord(connection, OTHER_WAIT_ID, INSTANCE_ID, "wait", "", "branch-1", "order-1");
            insertWaitRecord(
                    connection, "00000000-0000-0000-0000-0000000000e7", INSTANCE_ID, "wait", "fork", "", "order-1");

            // And a null fork step id fails with a not-null violation
            assertSqlState(
                    "23502",
                    () -> insertWaitRecord(
                            connection, "00000000-0000-0000-0000-0000000000e3", INSTANCE_ID, "n", null, "", "k"));

            // And a row for an unknown instance fails with a foreign key violation
            assertSqlState(
                    "23503",
                    () -> insertWaitRecord(
                            connection, "00000000-0000-0000-0000-0000000000e4", UNKNOWN_INSTANCE_ID, "u", "", "", "k"));

            // And a 255-character correlation key inserts while a 256-character one fails with a value too long
            insertWaitRecord(
                    connection, "00000000-0000-0000-0000-0000000000e5", INSTANCE_ID, "long", "", "", "k".repeat(255));
            assertSqlState(
                    "22001",
                    () -> insertWaitRecord(
                            connection,
                            "00000000-0000-0000-0000-0000000000e6",
                            INSTANCE_ID,
                            "too-long",
                            "",
                            "",
                            "k".repeat(256)));

            // And provenance_json accepts a JSON object
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        UPDATE workflow_commands SET provenance_json = '{"source": "migration-test"}'::jsonb
                        WHERE id = %d
                        """.formatted(signalCommandId));
            }
            assertThat(rows(connection, """
                            SELECT provenance_json::text FROM workflow_commands WHERE id = %d
                            """.formatted(signalCommandId)))
                    .containsExactly("{\"source\": \"migration-test\"}");
        }
    }

    @Test
    @DisplayName("A wait record rolls back with its transaction and goes with its instance")
    void waitRecordRollsBackWithItsTransactionAndGoesWithItsInstance() throws Exception {
        // Given a migrated database with a running instance
        flyway().load().migrate();
        try (Connection connection = connection()) {
            insertRunningInstance(connection, INSTANCE_ID);
        }

        try (Connection writer = connection();
                Connection observer = connection()) {
            writer.setAutoCommit(false);

            // When one transaction inserts a wait record and rolls back
            insertWaitRecord(writer, WAIT_ID, INSTANCE_ID, "wait", "", "", "order-1");
            writer.rollback();

            // Then no row exists
            assertThat(rows(observer, "SELECT count(*)::text FROM workflow_signal_waits"))
                    .containsExactly("0");

            // And a second transaction that inserts one and commits leaves one row
            insertWaitRecord(writer, WAIT_ID, INSTANCE_ID, "wait", "", "", "order-1");
            writer.commit();
            assertThat(rows(observer, "SELECT count(*)::text FROM workflow_signal_waits"))
                    .containsExactly("1");

            // And deleting the instance row leaves no wait record for that instance
            try (Statement statement = writer.createStatement()) {
                statement.executeUpdate("DELETE FROM workflow_instances WHERE id = '%s'".formatted(INSTANCE_ID));
            }
            writer.commit();
            assertThat(rows(observer, """
                            SELECT count(*)::text FROM workflow_signal_waits WHERE instance_id = '%s'
                            """.formatted(INSTANCE_ID))).containsExactly("0");
        }
    }

    @Test
    @DisplayName("Indexes serve key delivery on the wait table and business-key delivery on the instances")
    void lookupIndexesServeKeyAndBusinessKeyDelivery() throws Exception {
        // Given a migrated database
        flyway().load().migrate();

        // When the test reads the index catalogs of the wait table and the instance table
        try (Connection connection = connection()) {
            List<List<String>> waitIndexes = leadingColumns(connection, "workflow_signal_waits");
            List<List<String>> instanceIndexes = leadingColumns(connection, "workflow_instances");
            List<List<String>> uniqueInstanceIndexes = uniqueLeadingColumns(connection, "workflow_instances");

            // Then the wait table has an index led by the contract name, the signal name and the correlation key,
            // in that order
            assertThat(waitIndexes).anySatisfy(columns -> assertThat(columns)
                    .startsWith("contract_name", "signal_name", "correlation_key"));

            // And the instance table has a non-unique index led by the business key
            assertThat(instanceIndexes)
                    .anySatisfy(columns -> assertThat(columns).startsWith("business_key"));

            // besides the existing unique index per scope, definition and business key
            assertThat(uniqueInstanceIndexes).contains(List.of("scope_key", "definition_id", "business_key"));
        }
    }

    @Test
    @DisplayName("A correlated dedup row fits the existing table, is unique across instances and goes with its"
            + " instance")
    void correlatedDedupRowFitsTheExistingTableAndGoesWithItsInstance() throws Exception {
        // Given a migrated database with two running instances
        flyway().load().migrate();
        try (Connection connection = connection()) {
            insertRunningInstance(connection, INSTANCE_ID);
            insertRunningInstance(connection, OTHER_INSTANCE_ID);

            // When a correlated dedup row is inserted for the first instance
            insertCorrelatedDedupRow(connection, INSTANCE_ID);

            // Then the row exists
            assertThat(rows(connection, """
                            SELECT workflow_id::text FROM workflow_dedup
                            WHERE kind = 'correlated' AND scope_key = 'default'
                              AND dedup_namespace = '%s' AND key = '%s'
                            """.formatted(NAMESPACE_HASH, KEY_HASH)))
                    .containsExactly(INSTANCE_ID);

            // And the same kind, scope, namespace and key for the second instance is a unique violation
            assertSqlState("23505", () -> insertCorrelatedDedupRow(connection, OTHER_INSTANCE_ID));

            // And deleting the first instance removes the row
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM workflow_instances WHERE id = '%s'".formatted(INSTANCE_ID));
            }
            assertThat(rows(connection, "SELECT count(*)::text FROM workflow_dedup WHERE kind = 'correlated'"))
                    .containsExactly("0");
        }
    }

    @Test
    @DisplayName("A node carrying only the previous migrations validates and runs against a database at the new"
            + " version")
    void previousReleaseFlywayValidatesADatabaseAlreadyAtTheNewVersion() throws Exception {
        Path previousScripts = Files.createTempDirectory("previous-release-migrations");
        try {
            // Given a database migrated to the latest version through the classpath location
            Flyway latest = flyway().load();
            latest.migrate();
            assertThat(latest.info().applied())
                    .extracting(MigrationInfo::getVersion)
                    .contains(MigrationVersion.fromVersion("4"));

            // And a Flyway configuration over a directory holding only the previous migrations
            Flyway previousRelease = previousReleaseFlyway(previousScripts).load();

            // When the previous release validates and migrates
            assertDoesNotThrow(() -> {
                previousRelease.validate();
            });
            assertDoesNotThrow(() -> {
                previousRelease.migrate();
            });

            // Then nothing is pending and version 4 is still listed as applied
            assertThat(previousRelease.info().pending()).isEmpty();
            assertThat(previousRelease.info().applied())
                    .extracting(MigrationInfo::getVersion)
                    .contains(MigrationVersion.fromVersion("4"));

            // And a statement written for the previous schema inserts a SIGNAL command and selects its columns
            try (Connection connection = connection()) {
                insertRunningInstance(connection, INSTANCE_ID);
                long commandId = insertSignalCommand(connection, INSTANCE_ID, 1);
                assertThat(rows(connection, """
                                SELECT kind || '|' || state || '|' || context_json::text FROM (
                                    SELECT %s FROM workflow_commands WHERE id = %d) AS command
                                """.formatted(COMMAND_COLUMNS_AT_VERSION_3, commandId)))
                        .containsExactly("SIGNAL|PENDING|{\"c\": 1}");

                // And the inserted row's provenance_json is null
                assertThat(rows(connection, """
                                SELECT (provenance_json IS NULL)::text FROM workflow_commands WHERE id = %d
                                """.formatted(commandId))).containsExactly("true");
            }
        } finally {
            deleteRecursively(previousScripts);
        }
    }

    @Test
    @DisplayName("An index built ahead of the upgrade is kept and the script reruns cleanly")
    void indexBuiltAheadOfTheUpgradeIsKeptAndTheScriptRerunsCleanly() throws Exception {
        // Given a reference run that records the index the migration adds to the instance table and the instance
        // indexes after the latest migration
        String addedIndexDefinition = indexDefinitionAddedBy("4");
        flyway().load().migrate();
        List<String> referenceIndexes = indexNamesOf("workflow_instances");

        // And a fresh database at version 3 where the operator builds that index ahead of the upgrade, outside any
        // transaction
        resetSchema();
        migrateTo("3");
        try (Connection autocommit = connection();
                Statement statement = autocommit.createStatement()) {
            String buildAhead =
                    addedIndexDefinition.replaceFirst("^CREATE INDEX ", "CREATE INDEX CONCURRENTLY IF NOT EXISTS ");
            assertThat(buildAhead).startsWith("CREATE INDEX CONCURRENTLY IF NOT EXISTS ");
            statement.execute(buildAhead);
        }

        // When the database is migrated to the latest version
        Flyway latest = flyway().load();
        latest.migrate();

        // Then the migration succeeds and the instance table holds the reference index set, with no duplicate under
        // another name
        assertThat(latest.info().pending()).isEmpty();
        assertThat(indexNamesOf("workflow_instances")).containsExactlyElementsOf(referenceIndexes);

        // And running the text of the script a second time in one transaction raises no error and adds nothing
        List<String> catalogBefore = catalogSnapshot();
        assertDoesNotThrow(() -> executeScript(SIGNAL_CORRELATION_SCRIPT));
        assertThat(catalogSnapshot()).containsExactlyElementsOf(catalogBefore);
    }

    /**
     * Resets the schema, migrates to the version before the given one and then to the given one, and returns the
     * definition of the single index the given version added to the instance table.
     */
    private String indexDefinitionAddedBy(String version) throws SQLException {
        resetSchema();
        assertThat(flyway().load().info().all())
                .as("migrations the classpath resolves")
                .extracting(MigrationInfo::getVersion)
                .contains(MigrationVersion.fromVersion(version));
        migrateTo(String.valueOf(Integer.parseInt(version) - 1));
        List<String> before = indexNamesOf("workflow_instances");
        migrateTo(version);
        List<String> added = new ArrayList<>(indexNamesOf("workflow_instances"));
        added.removeAll(before);
        assertThat(added)
                .as("indexes added to workflow_instances by version " + version)
                .hasSize(1);
        try (Connection connection = connection()) {
            List<String> definitions = rows(connection, """
                    SELECT indexdef FROM pg_indexes
                    WHERE schemaname = current_schema() AND tablename = 'workflow_instances'
                      AND indexname = '%s'
                    """.formatted(added.get(0)));
            assertThat(definitions).hasSize(1);
            return definitions.get(0);
        }
    }

    /** Runs the text of a packaged migration script in one transaction, as Flyway does. */
    private static void executeScript(String script) throws SQLException, IOException {
        String text;
        try (InputStream stream =
                SignalCorrelationMigrationIT.class.getResourceAsStream("/" + MIGRATION_LOCATION + "/" + script)) {
            if (stream == null) {
                throw new IllegalStateException("migration script not on the classpath: " + script);
            }
            text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute(text);
            connection.commit();
        }
    }

    /** A Flyway configuration over a directory holding copies of the previous release's migrations only. */
    private static FluentConfiguration previousReleaseFlyway(Path directory) throws IOException {
        for (String script : PREVIOUS_RELEASE_SCRIPTS) {
            try (InputStream stream =
                    SignalCorrelationMigrationIT.class.getResourceAsStream("/" + MIGRATION_LOCATION + "/" + script)) {
                if (stream == null) {
                    throw new IllegalStateException("migration script not on the classpath: " + script);
                }
                Files.copy(stream, directory.resolve(script), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations("filesystem:" + directory.toAbsolutePath())
                .validateOnMigrate(true);
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** The column lists of the table's non-unique indexes, each in index order. */
    private static List<List<String>> leadingColumns(Connection connection, String table) throws SQLException {
        return indexColumns(connection, table, false);
    }

    /** The column lists of the table's unique indexes, each in index order. */
    private static List<List<String>> uniqueLeadingColumns(Connection connection, String table) throws SQLException {
        return indexColumns(connection, table, true);
    }

    private static List<List<String>> indexColumns(Connection connection, String table, boolean unique)
            throws SQLException {
        return rows(connection, """
                SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                FROM pg_index i
                JOIN pg_class t ON t.oid = i.indrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                CROSS JOIN LATERAL unnest(i.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
                WHERE t.relname = '%s' AND n.nspname = current_schema() AND i.indisunique = %b
                GROUP BY i.indexrelid
                """.formatted(table, unique)).stream()
                .map(columns -> List.of(columns.split(",")))
                .toList();
    }

    private static List<String> indexNamesOf(String table) throws SQLException {
        try (Connection connection = connection()) {
            return rows(connection, """
                    SELECT indexname FROM pg_indexes
                    WHERE schemaname = current_schema() AND tablename = '%s' ORDER BY indexname
                    """.formatted(table));
        }
    }

    /** Every table column, index and constraint of the current schema, so a rerun can be compared to the state before. */
    private static List<String> catalogSnapshot() throws SQLException {
        try (Connection connection = connection()) {
            return rows(connection, """
                    SELECT entry FROM (
                        SELECT 'column ' || table_name || '.' || column_name || ' ' || data_type
                               || ' ' || is_nullable AS entry
                        FROM information_schema.columns WHERE table_schema = current_schema()
                        UNION ALL
                        SELECT 'index ' || indexname || ' ' || indexdef
                        FROM pg_indexes WHERE schemaname = current_schema()
                        UNION ALL
                        SELECT 'constraint ' || c.conname || ' ' || pg_get_constraintdef(c.oid)
                        FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace
                        WHERE n.nspname = current_schema()) AS catalog
                    ORDER BY entry
                    """);
        }
    }

    private static void migrateTo(String version) {
        flyway().target(version).load().migrate();
    }

    private static FluentConfiguration flyway() {
        return Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations("classpath:" + MIGRATION_LOCATION);
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
    }

    private static void insertRunningInstance(Connection connection, String instanceId) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO workflow_instances
                        (id, definition_id, definition_version, plan_hash, status, current_step_id, state_json)
                    VALUES ('%s', 'orders', 1, repeat('a', 64), 'RUNNING', 'start', '{"k": 1}'::jsonb)
                    """.formatted(instanceId));
        }
    }

    private static void insertPendingInstance(Connection connection, String instanceId) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO workflow_instances
                        (id, definition_id, definition_version, plan_hash, status, current_step_id, state_json)
                    VALUES ('%s', 'orders', 1, repeat('a', 64), 'PENDING', NULL, NULL)
                    """.formatted(instanceId));
        }
    }

    /** Inserts a pending SIGNAL command with a context at the given sequence, listing only version 3 columns. */
    private static long insertSignalCommand(Connection connection, String workflowId, long seq) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        INSERT INTO workflow_commands
                            (workflow_id, seq, kind, state, recorded_at, available_at, context_json)
                        VALUES ('%s', %d, 'SIGNAL', 'PENDING', now(), '%s'::timestamptz, '{"c": 1}'::jsonb)
                        RETURNING id
                        """.formatted(workflowId, seq, AVAILABLE_AT))) {
            result.next();
            return result.getLong(1);
        }
    }

    /**
     * Inserts a wait record with every column set. The wait id is written as a literal so the column type decides
     * whether it parses; a null fork step id is written as SQL NULL.
     */
    private static void insertWaitRecord(
            Connection connection,
            String waitId,
            String instanceId,
            String stepId,
            String forkStepId,
            String branchId,
            String correlationKey)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO workflow_signal_waits
                        (wait_id, instance_id, step_id, fork_step_id, branch_id, scope_key, contract_name,
                         definition_id, signal_name, correlation_key, created_at)
                    VALUES ('%s', '%s', '%s', %s, '%s', 'default', 'example.Contract', 'orders', 'approve', '%s',
                            now())
                    """.formatted(
                            waitId,
                            instanceId,
                            stepId,
                            forkStepId == null ? "NULL" : "'" + forkStepId + "'",
                            branchId,
                            correlationKey));
        }
    }

    private static void insertCorrelatedDedupRow(Connection connection, String workflowId) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO workflow_dedup (kind, scope_key, dedup_namespace, key, workflow_id)
                    VALUES ('correlated', 'default', '%s', '%s', '%s')
                    """.formatted(NAMESPACE_HASH, KEY_HASH, workflowId));
        }
    }

    private static List<UUID> waitIdsOf(Connection connection, String instanceId) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT wait_id FROM workflow_signal_waits
                        WHERE instance_id = '%s' AND step_id = 'wait' AND branch_id = ''
                        """.formatted(instanceId))) {
            List<UUID> ids = new ArrayList<>();
            while (result.next()) {
                ids.add(assertInstanceOf(UUID.class, result.getObject(1), "wait_id must be a native uuid column"));
            }
            return ids;
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
