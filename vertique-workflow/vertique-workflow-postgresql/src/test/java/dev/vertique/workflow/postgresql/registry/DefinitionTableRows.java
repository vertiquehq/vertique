// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;

/** Raw-SQL rows and assertions on {@code workflow_definitions} shared by the migration integration tests. */
final class DefinitionTableRows {

    private DefinitionTableRows() {}

    /** A database action that may fail with an {@link SQLException}. */
    @FunctionalInterface
    interface SqlAction {

        void run() throws SQLException;
    }

    /** Asserts that the action fails with the given SQLSTATE, so the intended constraint family rejected it. */
    static void assertSqlState(String expected, SqlAction action) {
        SQLException failure = assertThrows(SQLException.class, action::run);
        assertEquals(expected, failure.getSQLState(), "the row must be rejected by the intended relational constraint");
    }

    /** Returns the lowercase hexadecimal SHA-256 of the UTF-8 bytes of the text. */
    static String sha256Hex(String text) throws NoSuchAlgorithmException {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** Reads the stored digest of the definition row, or null when the row holds none. */
    static String storedDigest(Connection connection, String definitionId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT document_digest FROM workflow_definitions WHERE definition_id = ?")) {
            query.setString(1, definitionId);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).as("a row for %s", definitionId).isTrue();
                return rows.getString(1);
            }
        }
    }

    /** Inserts a code row without a digest. */
    static void insertCode(Connection connection, String definitionId, long version, String status)
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

    /** Inserts a document row that does not name the digest column at all. */
    static void insertDocumentOmittingDigest(Connection connection, String definitionId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_definitions
                    (tenant_key, definition_id, definition_version, kind, plan_hash, status,
                     document, document_format, source_type, loaded_at)
                VALUES (NULL, ?, 1, 'DOCUMENT', repeat('a', 64), 'CANDIDATE', '{}', 'YAML', 'migration-test', now())
                """)) {
            insert.setString(1, definitionId);
            insert.executeUpdate();
        }
    }

    /** Inserts a document row that names the digest column; a null digest is stored as SQL null. */
    static void insertDocumentWithDigest(Connection connection, String definitionId, String document, String digest)
            throws SQLException {
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
    static void insertCodeWithDigest(Connection connection, String definitionId, String digest) throws SQLException {
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
}
