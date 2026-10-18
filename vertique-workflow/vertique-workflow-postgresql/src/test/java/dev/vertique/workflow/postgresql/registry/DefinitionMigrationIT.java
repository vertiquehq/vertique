// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Verifies the additive definition table migration and preserves the shipped workflow schema. */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class DefinitionMigrationIT {

    private static final String PINNED_V1_SHA256 = "b444c9f5943de792243e7080d78531d9c1755d9413429980ac98322727889ae2";

    private static final String PINNED_V002_SHA256 = "656243f7dd3a353fdc1e7dbf797f01c2acd8ff9e02b3afadbc03bbdfe135d44b";

    private static final String PINNED_V003_SHA256 = "f973a5079052c74790d7d394988cb981ded29c1d4b9c706bcb299fe77cd0ed7c";

    private static final String V1_SCRIPT = "V1__create_workflow_tables.sql";
    private static final String V002_SCRIPT = "V002__create_workflow_definitions.sql";
    private static final String V003_SCRIPT = "V003__add_workflow_definition_digest.sql";

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("wf_definition_migration_it");

    /** A database of its own, so the digest migration can be applied to a database that holds only the first two. */
    static final PostgresContainer digestDb = new PostgresContainer().withDatabaseName("wf_definition_digest_it");

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
            assertSqlState("23514", () -> insertTenantCode(connection, "tenant-a", "tenant-code", 1));
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

    @Test
    @DisplayName(
            "V3 adds a lowercase hex SHA-256 text digest, backfilled in SQL and required for exactly document rows")
    void pinsDigestMigrationShapeAndParity() throws Exception {
        // Given the first two migrations are shipped byte for byte as they were released
        assertEquals(PINNED_V1_SHA256, shippedSha256(V1_SCRIPT), "the shipped V1 migration bytes must remain pinned");
        assertEquals(
                PINNED_V002_SHA256, shippedSha256(V002_SCRIPT), "the shipped V002 migration bytes must remain pinned");
        assertEquals(
                PINNED_V003_SHA256, shippedSha256(V003_SCRIPT), "the shipped V003 migration bytes must remain pinned");

        // And the digest migration is shipped next to them
        assertThat(DefinitionMigrationIT.class.getResource("/db/migration/workflow/" + V003_SCRIPT))
                .as("the digest migration must be packaged with the other workflow migrations")
                .isNotNull();

        // And a database migrated to the definition table only, holding document texts of awkward shapes and code
        Flyway.configure()
                .dataSource(digestDb.jdbcUrl(), digestDb.username(), digestDb.password())
                .locations("classpath:db/migration/workflow")
                .target("2")
                .load()
                .migrate();
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put("digest-non-ascii", "name: caf\u00e9 \u2014 \u00fcber \u65e5\u672c\u8a9e\n");
        texts.put("digest-astral", "emoji: \uD83D\uDE00 and \uD835\uDD38\n");
        texts.put("digest-crlf", "first line\r\nsecond line\r\n");
        texts.put("digest-trailing-newline", "ends with a line break\n");
        texts.put("digest-no-trailing-newline", "ends without a line break");
        int v1ChecksumBefore;
        int v002ChecksumBefore;
        try (Connection connection = connection(digestDb)) {
            for (Map.Entry<String, String> seed : texts.entrySet()) {
                insertDocumentBeforeDigest(connection, seed.getKey(), seed.getValue());
            }
            insertCode(connection, "digest-code", 1, "SUPERSEDED");
            v1ChecksumBefore = scriptChecksum(connection, V1_SCRIPT);
            v002ChecksumBefore = scriptChecksum(connection, V002_SCRIPT);
        }

        // When the digest migration is applied
        Flyway latest = Flyway.configure()
                .dataSource(digestDb.jdbcUrl(), digestDb.username(), digestDb.password())
                .locations("classpath:db/migration/workflow")
                .load();
        assertThat(latest.info().pending())
                .extracting(MigrationInfo::getScript)
                .as("only the digest migration is pending after the definition table")
                .containsExactly(V003_SCRIPT);
        int resolvedV003Checksum = latest.info().pending()[0].getChecksum();
        latest.migrate();

        try (Connection connection = connection(digestDb)) {
            // Then the earlier migrations keep their recorded checksums and the new one records the shipped file's
            assertEquals(v1ChecksumBefore, scriptChecksum(connection, V1_SCRIPT), "V1 checksum must remain unchanged");
            assertEquals(
                    v002ChecksumBefore, scriptChecksum(connection, V002_SCRIPT), "V002 checksum must remain unchanged");
            assertEquals(
                    resolvedV003Checksum,
                    scriptChecksum(connection, V003_SCRIPT),
                    "the recorded V3 checksum must be the one computed from the packaged file");

            // And the column is a nullable 64-character string guarded by the lowercase hex constraint
            assertThat(columnType(connection, "workflow_definitions", "document_digest"))
                    .isEqualTo("character varying");
            assertThat(scalar(connection, """
                            SELECT character_maximum_length::text FROM information_schema.columns
                            WHERE table_schema = current_schema() AND table_name = 'workflow_definitions'
                              AND column_name = 'document_digest'
                            """)).isEqualTo("64");
            assertThat(scalar(connection, """
                            SELECT pg_get_constraintdef(oid) FROM pg_constraint
                            WHERE conname = 'ck_workflow_definitions_digest'
                              AND conrelid = 'workflow_definitions'::regclass
                            """)).contains("document_digest").contains("[0-9a-f]{64}");

            // And the SQL backfill equals the Java SHA-256 of every seeded text, and code rows carry none
            for (Map.Entry<String, String> seed : texts.entrySet()) {
                assertThat(storedDigest(connection, seed.getKey()))
                        .as("backfilled digest of %s", seed.getKey())
                        .isEqualTo(sha256Hex(seed.getValue()));
            }
            assertThat(storedDigest(connection, "digest-code")).isNull();

            // And a document row without a digest, with a malformed digest, or a code row with one is rejected
            String validDigest = sha256Hex("{}");
            assertSqlState("23514", () -> insertDocumentOmittingDigest(connection, "digest-omitted"));
            assertSqlState("23514", () -> insertDocumentWithDigest(connection, "digest-null", "{}", null));
            assertSqlState(
                    "23514", () -> insertDocumentWithDigest(connection, "digest-uppercase", "{}", "A".repeat(64)));
            assertSqlState("23514", () -> insertDocumentWithDigest(connection, "digest-not-hex", "{}", "z".repeat(64)));
            assertSqlState("23514", () -> insertDocumentWithDigest(connection, "digest-short", "{}", "a".repeat(63)));
            assertSqlState("23514", () -> insertCodeWithDigest(connection, "digest-on-code", validDigest));

            // And a document row with a correct lowercase digest and a code row without one are accepted
            insertDocumentWithDigest(connection, "digest-accepted", "{}", validDigest);
            insertCode(connection, "digest-code-accepted", 1, "SUPERSEDED");
            assertThat(storedDigest(connection, "digest-accepted")).isEqualTo(validDigest);
            assertThat(storedDigest(connection, "digest-code-accepted")).isNull();
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

    private static String shippedSha256(String script) throws NoSuchAlgorithmException, java.io.IOException {
        try (var migration = DefinitionMigrationIT.class.getResourceAsStream("/db/migration/workflow/" + script)) {
            if (migration == null) {
                throw new AssertionError("The shipped migration " + script + " is missing from the classpath");
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(migration.readAllBytes()));
        }
    }

    private static String sha256Hex(String text) throws NoSuchAlgorithmException {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static Connection connection(PostgresContainer container) throws SQLException {
        return DriverManager.getConnection(container.jdbcUrl(), container.username(), container.password());
    }

    private static int scriptChecksum(Connection connection, String script) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT checksum FROM flyway_schema_history WHERE script = ? AND success")) {
            query.setString(1, script);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).as("%s is recorded as applied", script).isTrue();
                return rows.getInt(1);
            }
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery(sql)) {
            assertThat(rows.next()).as("a row for %s", sql).isTrue();
            return rows.getString(1);
        }
    }

    private static String storedDigest(Connection connection, String definitionId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT document_digest FROM workflow_definitions WHERE definition_id = ?")) {
            query.setString(1, definitionId);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).as("a row for %s", definitionId).isTrue();
                return rows.getString(1);
            }
        }
    }

    /** Inserts a document row the way the table accepted it before the digest column existed. */
    private static void insertDocumentBeforeDigest(Connection connection, String definitionId, String document)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_definitions
                    (tenant_key, definition_id, definition_version, kind, plan_hash, status,
                     document, document_format, source_type, loaded_at)
                VALUES (NULL, ?, 1, 'DOCUMENT', repeat('a', 64), 'CANDIDATE', ?, 'YAML', 'migration-test', now())
                """)) {
            insert.setString(1, definitionId);
            insert.setString(2, document);
            insert.executeUpdate();
        }
    }

    /** Inserts a document row that does not name the digest column at all. */
    private static void insertDocumentOmittingDigest(Connection connection, String definitionId) throws SQLException {
        insertDocumentBeforeDigest(connection, definitionId, "{}");
    }

    /** Inserts a document row that names the digest column; a null digest is stored as SQL null. */
    private static void insertDocumentWithDigest(
            Connection connection, String definitionId, String document, String digest) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_definitions
                    (tenant_key, definition_id, definition_version, kind, plan_hash, status,
                     document, document_format, document_digest, source_type, loaded_at)
                VALUES (NULL, ?, 1, 'DOCUMENT', repeat('a', 64), 'CANDIDATE', ?, 'YAML', ?, 'migration-test', now())
                """)) {
            insert.setString(1, definitionId);
            insert.setString(2, document);
            insert.setString(3, digest);
            insert.executeUpdate();
        }
    }

    /** Inserts a code row that carries a digest. */
    private static void insertCodeWithDigest(Connection connection, String definitionId, String digest)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_definitions
                    (tenant_key, definition_id, definition_version, kind, plan_hash, status,
                     document_digest, source_type, loaded_at)
                VALUES (NULL, ?, 1, 'CODE', repeat('a', 64), 'CANDIDATE', ?, 'migration-test', now())
                """)) {
            insert.setString(1, definitionId);
            insert.setString(2, digest);
            insert.executeUpdate();
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

    private static void insertCode(Connection connection, String definitionId, long version, String status)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_definitions
                    (tenant_key, definition_id, definition_version, kind, plan_hash, status,
                     source_type, loaded_at)
                VALUES (NULL, ?, ?, 'CODE', repeat('a', 64), ?, 'migration-test', now())
                """)) {
            insert.setString(1, definitionId);
            insert.setLong(2, version);
            insert.setString(3, status);
            insert.executeUpdate();
        }
    }

    private static void insertTenantCode(Connection connection, String tenant, String definitionId, long version)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_definitions
                    (tenant_key, definition_id, definition_version, kind, plan_hash, status,
                     source_type, loaded_at)
                VALUES (?, ?, ?, 'CODE', repeat('a', 64), 'CANDIDATE', 'migration-test', now())
                """)) {
            insert.setString(1, tenant);
            insert.setString(2, definitionId);
            insert.setLong(3, version);
            insert.executeUpdate();
        }
    }

    private static void insertCodeWithDocumentFields(
            Connection connection, String definitionId, String document, String format) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_definitions
                    (tenant_key, definition_id, definition_version, kind, plan_hash, status,
                     document, document_format, source_type, loaded_at)
                VALUES (NULL, ?, 1, 'CODE', repeat('a', 64), 'CANDIDATE', ?, ?, 'migration-test', now())
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
                    (tenant_key, definition_id, definition_version, kind, plan_hash, status,
                     document, document_format, document_digest, source_type, loaded_at)
                VALUES (NULL, ?, 1, 'DOCUMENT', repeat('a', 64), 'CANDIDATE', ?, ?,
                        encode(sha256(convert_to(COALESCE(?::text, ''), 'UTF8')), 'hex'), 'migration-test', now())
                """)) {
            insert.setString(1, definitionId);
            insert.setString(2, document);
            insert.setString(3, format);
            insert.setString(4, document);
            insert.executeUpdate();
        }
    }

    private static void assertSqlState(String expected, SqlAction action) {
        SQLException failure = assertThrows(SQLException.class, action::run);
        assertEquals(expected, failure.getSQLState(), "the row must be rejected by the intended relational constraint");
    }

    private static void assertConstraintViolation(String constraint, SqlAction action) {
        SQLException failure = assertThrows(SQLException.class, action::run);
        assertEquals("23514", failure.getSQLState());
        assertThat(failure.getMessage()).as("the violated constraint").contains(constraint);
    }

    @FunctionalInterface
    private interface SqlAction {

        void run() throws SQLException;
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
