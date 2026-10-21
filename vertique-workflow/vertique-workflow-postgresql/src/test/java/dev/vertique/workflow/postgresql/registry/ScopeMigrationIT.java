// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies the single migration that adds the definition registry and the scope columns, converting a database that
 * already holds workflow rows, and the uniqueness and code-row rules the converted schema enforces. Every scenario
 * uses a database of its own.
 *
 * <p>The assertions pin only the columns, keys and indexes the scope conversion creates. Status values, the
 * activation token, the one-active index and the other registry columns are owned by their own tests.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ScopeMigrationIT {

    private static final String PINNED_V1_SHA256 = "b444c9f5943de792243e7080d78531d9c1755d9413429980ac98322727889ae2";

    private static final String V1_SCRIPT = "V1__create_workflow_tables.sql";
    private static final String V002_SCRIPT = "V002__workflow_definitions_and_scopes.sql";
    private static final String RETIRED_V002_SCRIPT = "V002__create_workflow_definitions_and_tenant_key.sql";

    private static final UUID FIRST_INSTANCE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SECOND_INSTANCE = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID TASK = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    static final PostgresContainer upgradeDb = new PostgresContainer().withDatabaseName("wf_scope_upgrade_it");

    static final PostgresContainer rulesDb = new PostgresContainer()
            .withDatabaseName("wf_scope_rules_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    void upgradeFromV1AppliesOneScopeMigration() throws Exception {
        // Given the first migration is shipped byte for byte as it was released
        assertThat(shippedSha256(V1_SCRIPT)).isEqualTo(PINNED_V1_SHA256);

        // And a database migrated to the first version only, holding two instances (one with a business key),
        // one task, a start dedup row of the old shape and a signal dedup row
        flyway(upgradeDb).target("1").load().migrate();
        int v1ChecksumBefore;
        try (Connection connection = connection(upgradeDb)) {
            insertReleasedInstance(connection, FIRST_INSTANCE, null);
            insertReleasedInstance(connection, SECOND_INSTANCE, "PO-42");
            insertReleasedTask(connection, TASK, FIRST_INSTANCE);
            insertReleasedDedup(connection, "start", "order-flow", "idem-1", FIRST_INSTANCE, "definitionVersion=1");
            insertReleasedDedup(connection, "signal", SECOND_INSTANCE.toString(), "sig-1", SECOND_INSTANCE, null);
            v1ChecksumBefore = scriptChecksum(connection, V1_SCRIPT);
        }

        // When the upgrade runs through the real Flyway run
        Flyway latest = flyway(upgradeDb).load();
        latest.migrate();

        try (Connection connection = connection(upgradeDb)) {
            Catalog catalog = new Catalog(connection);

            // Then instances and tasks carry a required scope that defaults to the default scope
            for (String column : new String[] {"scope_key", "definition_scope_key"}) {
                assertThat(catalog.columnShape("workflow_instances", column))
                        .as("%s on workflow_instances", column)
                        .isEqualTo("character varying|255|NO|'default'::character varying");
            }
            assertThat(catalog.columnShape("workflow_tasks", "scope_key"))
                    .isEqualTo("character varying|255|NO|'default'::character varying");

            // And the rows inserted before the upgrade read the default scope in every scope column
            assertThat(catalog.scalar("SELECT count(*) FROM workflow_instances"))
                    .isEqualTo("2");
            assertThat(catalog.scalar("""
                            SELECT count(*) FROM workflow_instances
                            WHERE scope_key = 'default' AND definition_scope_key = 'default'
                            """)).isEqualTo("2");
            assertThat(catalog.scalar("SELECT count(*) FROM workflow_tasks WHERE scope_key = 'default'"))
                    .isEqualTo("1");

            // And no tenant column and no tenant index exists on any table
            for (String table : new String[] {"workflow_instances", "workflow_tasks", "workflow_definitions"}) {
                assertThat(catalog.columnNames(table))
                        .as("columns of %s", table)
                        .doesNotContain("tenant_key");
            }
            assertThat(catalog.scalar("""
                            SELECT count(*) FROM pg_class
                            WHERE relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = current_schema())
                              AND relname LIKE '%tenant%'
                            """)).as("objects named after a tenant").isEqualTo("0");

            // And the business key is unique per start scope and definition id, for rows that have one
            assertThat(catalog.indexes("idx_workflow_instances_business_key"))
                    .singleElement()
                    .satisfies(index -> {
                        assertThat(index.valid()).isTrue();
                        assertThat(index.definition())
                                .startsWith("CREATE UNIQUE INDEX idx_workflow_instances_business_key ON ")
                                .contains("workflow_instances USING btree (scope_key, definition_id, business_key)")
                                .endsWith("WHERE (business_key IS NOT NULL)");
                    });

            // And both keyset indexes lead with the scope, are complete rather than partial and keep the keyset order
            assertThat(catalog.indexes("idx_workflow_instances_scope_updated_at"))
                    .singleElement()
                    .satisfies(index -> {
                        assertThat(index.valid()).isTrue();
                        assertThat(index.definition())
                                .contains("workflow_instances USING btree (scope_key, updated_at DESC, id)")
                                .doesNotContain(" WHERE ");
                    });
            assertThat(catalog.indexes("idx_workflow_tasks_scope_updated_at"))
                    .singleElement()
                    .satisfies(index -> {
                        assertThat(index.valid()).isTrue();
                        assertThat(index.definition())
                                .contains("workflow_tasks USING btree (scope_key, updated_at DESC, task_id)")
                                .doesNotContain(" WHERE ");
                    });

            // And the dedup namespace column replaced the scope column, and the scope key joined the primary key
            assertThat(catalog.columnNames("workflow_dedup"))
                    .contains("dedup_namespace", "scope_key")
                    .doesNotContain("scope");
            assertThat(catalog.columnShape("workflow_dedup", "scope_key"))
                    .isEqualTo("character varying|255|NO|'default'::character varying");
            assertThat(catalog.primaryKey("workflow_dedup"))
                    .isEqualTo("PRIMARY KEY (kind, scope_key, dedup_namespace, key)");

            // And the dedup rows kept their namespace, key and fingerprint and read the default scope
            assertThat(catalog.rows("""
                            SELECT kind || '|' || scope_key || '|' || dedup_namespace || '|' || key || '|'
                                   || COALESCE(fingerprint, 'null')
                            FROM workflow_dedup ORDER BY kind
                            """))
                    .containsExactly(
                            "signal|default|" + SECOND_INSTANCE + "|sig-1|null",
                            "start|default|order-flow|idem-1|definitionVersion=1");

            // And definitions are keyed by scope, id and version, with room for the kind and the plan hash
            assertThat(catalog.primaryKey("workflow_definitions"))
                    .isEqualTo("PRIMARY KEY (scope_key, definition_id, definition_version)");
            assertThat(catalog.columnShape("workflow_definitions", "scope_key"))
                    .isEqualTo("character varying|255|NO|null");
            assertThat(catalog.columnShape("workflow_definitions", "kind")).startsWith("character varying|16|NO");
            assertThat(catalog.columnShape("workflow_definitions", "plan_hash")).startsWith("character varying|128|NO");

            // And a definition names its contract and its state type
            assertThat(catalog.columnShape("workflow_definitions", "contract_name"))
                    .isEqualTo("character varying|255|NO|null");
            assertThat(catalog.columnShape("workflow_definitions", "state_type_name"))
                    .isEqualTo("character varying|255|NO|null");

            // And a definition stores the type names its plan hash used, empty unless set
            assertThat(catalog.rows("""
                            SELECT data_type || '|' || is_nullable || '|' || COALESCE(column_default, 'null')
                            FROM information_schema.columns
                            WHERE table_schema = current_schema() AND table_name = 'workflow_definitions'
                              AND column_name = 'name_binding'
                            """)).containsExactly("jsonb|NO|'{}'::jsonb");
            assertThat(catalog.columnNames("workflow_definitions")).contains("name_binding");

            // And activation records are keyed by scope and definition id, and name a version of the default scope or
            // of their own scope
            assertThat(catalog.columnNames("workflow_definition_activations"))
                    .containsExactly(
                            "scope_key", "definition_id", "target_scope_key", "definition_version", "activated_at");
            assertThat(catalog.primaryKey("workflow_definition_activations"))
                    .isEqualTo("PRIMARY KEY (scope_key, definition_id)");
            assertThat(catalog.columnShape("workflow_definition_activations", "target_scope_key"))
                    .isEqualTo("character varying|255|NO|null");
            assertThat(catalog.rows("""
                            SELECT column_name || '|' || data_type || '|' || is_nullable
                            FROM information_schema.columns
                            WHERE table_schema = current_schema() AND table_name = 'workflow_definition_activations'
                              AND column_name IN ('definition_version', 'activated_at')
                            ORDER BY column_name
                            """))
                    .containsExactly("activated_at|timestamp with time zone|NO", "definition_version|bigint|NO");

            // And exactly versions 1 and 2 are applied, and the location holds no other script
            assertThat(latest.info().applied())
                    .extracting(MigrationInfo::getVersion)
                    .containsExactly(MigrationVersion.fromVersion("1"), MigrationVersion.fromVersion("2"));
            assertThat(latest.info().all())
                    .extracting(MigrationInfo::getScript)
                    .containsExactly(V1_SCRIPT, V002_SCRIPT)
                    .doesNotContain(RETIRED_V002_SCRIPT);

            // And the first migration keeps its recorded checksum and the new one records the shipped file's
            assertThat(scriptChecksum(connection, V1_SCRIPT)).isEqualTo(v1ChecksumBefore);
            assertThat(scriptChecksum(connection, V002_SCRIPT))
                    .isEqualTo(latest.info().applied()[1].getChecksum());
        }

        // And the shipped script bounds its lock wait with its very first statement
        String statements = withoutComments(shippedText(V002_SCRIPT));
        assertThat(firstStatement(statements))
                .as("the first statement must bound the lock wait")
                .isEqualTo("SET LOCAL lock_timeout = '30s'");

        // And the script holds nothing that cannot run inside the single migration transaction
        assertThat(statements.toUpperCase(Locale.ROOT))
                .doesNotContain("CONCURRENTLY", "VACUUM", "CREATE DATABASE", "ALTER SYSTEM");
    }

    @Test
    void uniquenessIsPerScopeAndCodeRowsStayInTheDefaultScope() throws Exception {
        try (Connection connection = connection(rulesDb)) {
            // Given a freshly migrated database with no scope row, scope table or other setup

            // When instances with one business key and definition id are inserted in two scopes
            UUID acme = UUID.randomUUID();
            UUID globex = UUID.randomUUID();
            insertInstance(connection, acme, "purchase-approval", "PO-42", "acme");
            insertInstance(connection, globex, "purchase-approval", "PO-42", "globex");

            // Then a second one in the first scope is rejected as a duplicate
            assertThatThrownBy(
                            () -> insertInstance(connection, UUID.randomUUID(), "purchase-approval", "PO-42", "acme"))
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "23505");

            // When start dedup rows with one namespace and key are inserted in two scopes
            insertDedup(connection, "start", "acme", "purchase-approval", "k1", acme);
            insertDedup(connection, "start", "globex", "purchase-approval", "k1", globex);

            // Then a second one in the first scope is rejected as a duplicate
            assertThatThrownBy(() -> insertDedup(connection, "start", "acme", "purchase-approval", "k1", acme))
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "23505");

            // When definition rows are inserted: a code row in the default scope, a document row in another scope
            insertDefinition(connection, "default", "x", 1, "CODE");
            insertDefinition(connection, "acme", "x", 1, "DOCUMENT");

            // Then the same key again is rejected as a duplicate
            assertThatThrownBy(() -> insertDefinition(connection, "acme", "x", 1, "DOCUMENT"))
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "23505");

            // And a code row outside the default scope is rejected by the document check
            assertThatThrownBy(() -> insertDefinition(connection, "acme", "y", 1, "CODE"))
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "23514");

            // And every default-scope instance, task and dedup row inserts without any scope setup
            UUID standard = UUID.randomUUID();
            insertInstance(connection, standard, "purchase-approval", "PO-42", "default");
            insertTask(connection, UUID.randomUUID(), standard, "default");
            insertDedup(connection, "start", "default", "purchase-approval", "k1", standard);
            assertThat(new Catalog(connection).scalar("""
                            SELECT count(*) FROM workflow_instances
                            WHERE scope_key = 'default' AND definition_scope_key = 'default'
                            """)).isEqualTo("1");
        }
    }

    // --- Fixtures ---

    private static FluentConfiguration flyway(PostgresContainer container) {
        return Flyway.configure()
                .dataSource(container.jdbcUrl(), container.username(), container.password())
                .locations("classpath:db/migration/workflow");
    }

    private static Connection connection(PostgresContainer container) throws SQLException {
        return DriverManager.getConnection(container.jdbcUrl(), container.username(), container.password());
    }

    /** Inserts an instance in the shape the first migration created: no scope columns. */
    private static void insertReleasedInstance(Connection connection, UUID id, String businessKey) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_instances
                    (id, definition_id, definition_version, plan_hash, status, business_key, current_step_id,
                     state_json, created_at, updated_at)
                VALUES (?, 'orders', 1, repeat('a', 64), 'WAITING', ?, 'review', '{}'::jsonb,
                        '2026-05-10T00:00:00Z', '2026-05-10T00:00:00Z')
                """)) {
            insert.setObject(1, id);
            insert.setString(2, businessKey);
            insert.executeUpdate();
        }
    }

    /** Inserts a task in the shape the first migration created: no scope column. */
    private static void insertReleasedTask(Connection connection, UUID taskId, UUID instanceId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_tasks
                    (task_id, workflow_id, step_id, status, assignee_type, assignee_key, decisions_snapshot_json,
                     created_at, updated_at)
                VALUES (?, ?, 'review', 'OPEN', 'ROLE', 'reviewers', '[]'::jsonb,
                        '2026-05-10T00:00:00Z', '2026-05-10T00:00:00Z')
                """)) {
            insert.setObject(1, taskId);
            insert.setObject(2, instanceId);
            insert.executeUpdate();
        }
    }

    /** Inserts a dedup row in the shape the first migration created: a scope column and no scope key. */
    private static void insertReleasedDedup(
            Connection connection, String kind, String scope, String key, UUID instanceId, String fingerprint)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_dedup (kind, scope, key, workflow_id, fingerprint) VALUES (?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, kind);
            insert.setString(2, scope);
            insert.setString(3, key);
            insert.setObject(4, instanceId);
            insert.setString(5, fingerprint);
            insert.executeUpdate();
        }
    }

    /** Inserts an instance that starts and is defined in the given scope. */
    private static void insertInstance(
            Connection connection, UUID id, String definitionId, String businessKey, String scopeKey)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_instances
                    (id, definition_id, definition_version, plan_hash, status, business_key, current_step_id,
                     state_json, created_at, updated_at, scope_key, definition_scope_key)
                VALUES (?, ?, 1, repeat('a', 64), 'WAITING', ?, 'review', '{}'::jsonb,
                        '2026-05-10T00:00:00Z', '2026-05-10T00:00:00Z', ?, ?)
                """)) {
            insert.setObject(1, id);
            insert.setString(2, definitionId);
            insert.setString(3, businessKey);
            insert.setString(4, scopeKey);
            insert.setString(5, scopeKey);
            insert.executeUpdate();
        }
    }

    /** Inserts a task in the given scope. */
    private static void insertTask(Connection connection, UUID taskId, UUID instanceId, String scopeKey)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_tasks
                    (task_id, workflow_id, step_id, status, assignee_type, assignee_key, decisions_snapshot_json,
                     created_at, updated_at, scope_key)
                VALUES (?, ?, 'review', 'OPEN', 'ROLE', 'reviewers', '[]'::jsonb,
                        '2026-05-10T00:00:00Z', '2026-05-10T00:00:00Z', ?)
                """)) {
            insert.setObject(1, taskId);
            insert.setObject(2, instanceId);
            insert.setString(3, scopeKey);
            insert.executeUpdate();
        }
    }

    /** Inserts a dedup row; the instance row must exist because the foreign key is checked per statement here. */
    private static void insertDedup(
            Connection connection, String kind, String scopeKey, String namespace, String key, UUID instanceId)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_dedup (kind, scope_key, dedup_namespace, key, workflow_id)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, kind);
            insert.setString(2, scopeKey);
            insert.setString(3, namespace);
            insert.setString(4, key);
            insert.setObject(5, instanceId);
            insert.executeUpdate();
        }
    }

    /** Inserts a candidate definition row of the given kind; a document row carries a document and its digest. */
    private static void insertDefinition(
            Connection connection, String scopeKey, String definitionId, long version, String kind) throws Exception {
        boolean document = "DOCUMENT".equals(kind);
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_definitions
                    (scope_key, definition_id, definition_version, kind, contract_name, state_type_name, plan_hash,
                     status, document, document_format, document_digest, source_type, loaded_at)
                VALUES (?, ?, ?, ?, 'example.Contract', 'example.State', repeat('a', 64), 'CANDIDATE',
                        ?, ?, ?, 'migration-test', now())
                """)) {
            insert.setString(1, scopeKey);
            insert.setString(2, definitionId);
            insert.setLong(3, version);
            insert.setString(4, kind);
            insert.setString(5, document ? "{}" : null);
            insert.setString(6, document ? "YAML" : null);
            insert.setString(7, document ? sha256Hex("{}") : null);
            insert.executeUpdate();
        }
    }

    private static String sha256Hex(String text) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String shippedText(String script) throws Exception {
        try (var migration = ScopeMigrationIT.class.getResourceAsStream("/db/migration/workflow/" + script)) {
            if (migration == null) {
                throw new AssertionError("The shipped migration " + script + " is missing from the classpath");
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String shippedSha256(String script) throws Exception {
        try (var migration = ScopeMigrationIT.class.getResourceAsStream("/db/migration/workflow/" + script)) {
            if (migration == null) {
                throw new AssertionError("The shipped migration " + script + " is missing from the classpath");
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(migration.readAllBytes()));
        }
    }

    /** Returns the script text with every line comment removed. */
    private static String withoutComments(String script) {
        StringBuilder statements = new StringBuilder();
        script.lines()
                .forEach(line -> statements.append(line.replaceAll("--.*$", "")).append('\n'));
        return statements.toString();
    }

    /** Returns the first statement of comment-free script text, whitespace normalized and without its semicolon. */
    private static String firstStatement(String statements) {
        int end = statements.indexOf(';');
        if (end < 0) {
            throw new AssertionError("The shipped migration holds no statement");
        }
        return statements.substring(0, end).strip().replaceAll("\\s+", " ");
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

    /** A catalog index as its validity flag and its {@code pg_get_indexdef} text. */
    private record IndexInfo(boolean valid, String definition) {}

    /** Reads columns, indexes and constraints of the current schema by name. */
    private static final class Catalog {

        private final Connection connection;

        Catalog(Connection connection) {
            this.connection = connection;
        }

        /** Describes a column as {@code type|length|nullable|default}. */
        String columnShape(String table, String column) throws SQLException {
            return scalar("""
                    SELECT data_type || '|' || character_maximum_length || '|' || is_nullable || '|'
                           || COALESCE(column_default, 'null')
                    FROM information_schema.columns
                    WHERE table_schema = current_schema() AND table_name = '%s' AND column_name = '%s'
                    """.formatted(table, column));
        }

        /** Lists the column names of the table, empty when the table does not exist. */
        List<String> columnNames(String table) throws SQLException {
            return rows("""
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema = current_schema() AND table_name = '%s' ORDER BY ordinal_position
                    """.formatted(table));
        }

        /** Returns the primary key definition of the table as {@code pg_get_constraintdef} prints it. */
        String primaryKey(String table) throws SQLException {
            return scalar("""
                    SELECT pg_get_constraintdef(oid) FROM pg_constraint
                    WHERE contype = 'p' AND conrelid = '%s'::regclass
                    """.formatted(table));
        }

        /** Lists the indexes of the given name in the current schema; a missing index yields an empty list. */
        List<IndexInfo> indexes(String indexName) throws SQLException {
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT i.indisvalid, pg_get_indexdef(i.indexrelid)
                    FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                    WHERE c.relname = ?
                      AND c.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = current_schema())
                    """)) {
                query.setString(1, indexName);
                try (ResultSet rows = query.executeQuery()) {
                    List<IndexInfo> found = new ArrayList<>();
                    while (rows.next()) {
                        found.add(new IndexInfo(rows.getBoolean(1), rows.getString(2)));
                    }
                    return found;
                }
            }
        }

        List<String> rows(String sql) throws SQLException {
            try (Statement query = connection.createStatement();
                    ResultSet rows = query.executeQuery(sql)) {
                List<String> values = new ArrayList<>();
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
                return values;
            }
        }

        String scalar(String sql) throws SQLException {
            List<String> values = rows(sql);
            assertThat(values).as("one row for %s", sql).hasSize(1);
            return values.get(0);
        }
    }
}
