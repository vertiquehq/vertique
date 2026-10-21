// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.DefinitionTableRows.assertSqlState;
import static dev.vertique.workflow.postgresql.registry.DefinitionTableRows.insertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Verifies the additive definition table migration and preserves the shipped workflow schema. */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class DefinitionMigrationIT {

    private static final String PINNED_V1_SHA256 = "b444c9f5943de792243e7080d78531d9c1755d9413429980ac98322727889ae2";

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("wf_definition_migration_it");

    @Test
    @DisplayName("V2 adds the definition registry while preserving V1 and enforcing only relational invariants")
    void addsRegistryWithoutChangingV1() throws Exception {
        assertEquals(PINNED_V1_SHA256, shippedV1Sha256(), "the shipped V1 migration bytes must remain pinned");

        Flyway v1 = Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations("classpath:db/migration/workflow")
                .target("1")
                .load();
        v1.migrate();

        int v1Checksum;
        List<String> v1WorkflowRows;
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO workflow_instances
                        (id, definition_id, definition_version, plan_hash, status, current_step_id, state_json)
                    VALUES ('00000000-0000-0000-0000-000000000001', 'orders', 1, repeat('a', 64),
                            'RUNNING', 'start', '{}'::jsonb),
                           ('00000000-0000-0000-0000-000000000002', 'orders', 2, repeat('b', 64),
                            'RUNNING', 'start', '{}'::jsonb)
                    """);
            v1Checksum = checksum(connection, "1");
            v1WorkflowRows = workflowRows(connection);
        }

        Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations("classpath:db/migration/workflow")
                .load()
                .migrate();

        try (Connection connection = connection()) {
            assertEquals(v1Checksum, checksum(connection, "1"), "V1 checksum must remain unchanged");
            assertEquals(v1WorkflowRows, workflowRows(connection), "V1 workflow rows must be untouched");
            assertEquals("text", columnType(connection, "workflow_definitions", "document"));

            insertCode(connection, "orders", 1, "SUPERSEDED");
            insertCode(connection, "orders", 2, "ACTIVE");
            assertEquals(2, count(connection, "workflow_definitions"), "valid CODE rows remain supported");

            assertSqlState("23505", () -> insertCode(connection, "orders", 1, "CANDIDATE"));
            assertSqlState("23505", () -> insertCode(connection, "orders", 3, "ACTIVE"));
            assertSqlState("23514", () -> insertScopedCode(connection, "tenant-a", "tenant-code", 1));
            assertSqlState("23514", () -> insertCodeWithDocumentFields(connection, "code-with-body", "{}", null));
            assertSqlState("23514", () -> insertCodeWithDocumentFields(connection, "code-with-format", null, "JSON"));

            // These rows carry a valid digest, so only the document-shape constraint can reject them
            assertConstraintViolation(
                    "ck_workflow_definitions_document",
                    () -> insertDocument(connection, "document-without-body", null, "JSON"));
            assertConstraintViolation(
                    "ck_workflow_definitions_document",
                    () -> insertDocument(connection, "document-without-format", "{}", null));
            insertDocument(connection, "valid-document", "{}", "JSON");

            String largeText = "x".repeat(262_145);
            insertDocument(connection, "large-document", largeText, "YAML");
            assertEquals(262_145, documentSize(connection, "large-document"));
        }
    }

    private static String shippedV1Sha256() throws NoSuchAlgorithmException, java.io.IOException {
        try (var migration = DefinitionMigrationIT.class.getResourceAsStream(
                "/db/migration/workflow/V1__create_workflow_tables.sql")) {
            if (migration == null) {
                throw new AssertionError("The shipped V1 migration is missing from the classpath");
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(migration.readAllBytes()));
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
    }

    private static int checksum(Connection connection, String version) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT checksum FROM flyway_schema_history WHERE version = ? AND success")) {
            query.setString(1, version);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static List<String> workflowRows(Connection connection) throws SQLException {
        try (Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery("""
                        SELECT id::text, definition_id, definition_version::text, plan_hash, status,
                               current_step_id, state_json::text
                        FROM workflow_instances ORDER BY id
                        """)) {
            java.util.ArrayList<String> snapshot = new java.util.ArrayList<>();
            while (rows.next()) {
                snapshot.add(String.join(
                        "|",
                        rows.getString(1),
                        rows.getString(2),
                        rows.getString(3),
                        rows.getString(4),
                        rows.getString(5),
                        rows.getString(6),
                        rows.getString(7)));
            }
            return List.copyOf(snapshot);
        }
    }

    private static String columnType(Connection connection, String table, String column) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?
                """)) {
            query.setString(1, table);
            query.setString(2, column);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static void insertScopedCode(Connection connection, String scopeKey, String definitionId, long version)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_definitions
                    (scope_key, definition_id, definition_version, kind, contract_name, state_type_name, plan_hash, status,
                     source_type, loaded_at)
                VALUES (?, ?, ?, 'CODE', 'example.Contract', 'example.State', repeat('a', 64), 'CANDIDATE', 'migration-test', now())
                """)) {
            insert.setString(1, scopeKey);
            insert.setString(2, definitionId);
            insert.setLong(3, version);
            insert.executeUpdate();
        }
    }

    private static void insertCodeWithDocumentFields(
            Connection connection, String definitionId, String document, String format) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_definitions
                    (scope_key, definition_id, definition_version, kind, contract_name, state_type_name, plan_hash, status,
                     document, document_format, source_type, loaded_at)
                VALUES ('default', ?, 1, 'CODE', 'example.Contract', 'example.State', repeat('a', 64), 'CANDIDATE', ?, ?, 'migration-test', now())
                """)) {
            insert.setString(1, definitionId);
            insert.setString(2, document);
            insert.setString(3, format);
            insert.executeUpdate();
        }
    }

    /**
     * Inserts a document row whose digest is the SQL SHA-256 of its text; a missing text is digested as the empty
     * text, so that a missing body is rejected by the document-shape constraint alone.
     */
    private static void insertDocument(Connection connection, String definitionId, String document, String format)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_definitions
                    (scope_key, definition_id, definition_version, kind, contract_name, state_type_name, plan_hash, status,
                     document, document_format, document_digest, source_type, loaded_at)
                VALUES ('default', ?, 1, 'DOCUMENT', 'example.Contract', 'example.State', repeat('a', 64), 'CANDIDATE', ?, ?,
                        encode(sha256(convert_to(COALESCE(?::text, ''), 'UTF8')), 'hex'), 'migration-test', now())
                """)) {
            insert.setString(1, definitionId);
            insert.setString(2, document);
            insert.setString(3, format);
            insert.setString(4, document);
            insert.executeUpdate();
        }
    }

    private static void assertConstraintViolation(String constraint, DefinitionTableRows.SqlAction action) {
        SQLException failure = assertThrows(SQLException.class, action::run);
        assertEquals("23514", failure.getSQLState());
        assertThat(failure.getMessage()).as("the violated constraint").contains(constraint);
    }

    private static int documentSize(Connection connection, String definitionId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT octet_length(document) FROM workflow_definitions WHERE definition_id = ?")) {
            query.setString(1, definitionId);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }
}
