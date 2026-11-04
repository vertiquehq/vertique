// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.OutboxEntryState;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Proves the listing-index migration against a database that already holds outbox entries, that the script can be
 * run a second time, and that the indexes it creates have the predicates the listing and the discarded purge rely on.
 *
 * <p>The container starts without a migration so the test controls the target version: it migrates to the version
 * before the listing migration, seeds one entry per state, and then applies the rest.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class OutboxListingMigrationIT {

    private static final String LOCATION = "classpath:db/migration/inbox-outbox";
    private static final String SCRIPT = "/db/migration/inbox-outbox/V003__add_outbox_listing_indexes.sql";
    private static final String VERSION_BEFORE = "002";
    private static final Set<String> NEW_INDEXES =
            Set.of("idx_outbox_listing_updated", "idx_outbox_listing_created", "idx_outbox_discarded");

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("outbox_listing_migration");

    private long relfilenodeBefore;
    private List<Map<String, Object>> rowsBefore;

    @BeforeEach
    void givenVersionTwoWithARowInEveryState() throws SQLException {
        // every test starts from an empty database, so each one applies the migration for the first time
        try (Connection connection = jdbc();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
        flyway().target(MigrationVersion.fromVersion(VERSION_BEFORE)).load().migrate();
        for (OutboxEntryState state : OutboxEntryState.values()) {
            insertEntryIn(state);
        }
        relfilenodeBefore = relfilenode();
        rowsBefore = outboxRows();
    }

    @Test
    @DisplayName("existing rows are intact without a rewrite, and the three indexes exist")
    void migratesPopulatedTable() throws SQLException {
        assertEquals(Set.of(), existingNewIndexes(), "the indexes do not exist at version 002");

        // When: the listing migration is applied
        flyway().load().migrate();

        // Then: every row is exactly as it was, the table file is unchanged and the indexes exist
        assertEquals(OutboxEntryState.values().length, rowsBefore.size());
        assertEquals(rowsBefore, outboxRows());
        assertEquals(relfilenodeBefore, relfilenode());
        assertEquals(NEW_INDEXES, existingNewIndexes());
        assertEquals(
                "003",
                string("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1"));
        assertIndexesMatchContract();
    }

    @Test
    @DisplayName("the script can be run again and changes nothing")
    void rerunnable() throws Exception {
        flyway().load().migrate();
        String objectsBefore = objectInventory();
        String script;
        try (InputStream in = OutboxListingMigrationIT.class.getResourceAsStream(SCRIPT)) {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        // When: the script runs a second time, and a third, each in one transaction as Flyway runs it
        for (int run = 0; run < 2; run++) {
            try (Connection connection = jdbc()) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute(script);
                }
                connection.commit();
            }
        }

        // Then: nothing was added or changed, and the rows are untouched
        assertEquals(objectsBefore, objectInventory());
        assertEquals(rowsBefore, outboxRows());
        assertIndexesMatchContract();
    }

    @Test
    @DisplayName("an index the operator pre-built concurrently under the same name is accepted and kept")
    void acceptsPrebuiltIndex() throws Exception {
        try (Connection connection = jdbc();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE INDEX CONCURRENTLY idx_outbox_listing_updated ON outbox"
                            + " (updated_at DESC, id DESC) WHERE state IN ('PENDING', 'PROCESSING', 'DEAD_LETTER', 'DISCARDED')");
        }
        String prebuilt = string("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_outbox_listing_updated'");

        flyway().load().migrate();

        assertEquals(
                prebuilt, string("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_outbox_listing_updated'"));
        assertEquals(NEW_INDEXES, existingNewIndexes());
        assertIndexesMatchContract();
    }

    @Test
    @DisplayName(
            "the planner uses the listing indexes for a listing over the indexed states, and the purge index for discarded")
    void plannerUsesTheIndexes() throws Exception {
        flyway().load().migrate();

        try (Connection connection = jdbc();
                Statement statement = connection.createStatement()) {
            statement.execute("SET enable_seqscan = off");
            statement.execute("SET enable_bitmapscan = off");
            String updated = plan(
                    statement,
                    "SELECT id FROM outbox WHERE state IN ('DEAD_LETTER', 'DISCARDED')"
                            + " ORDER BY updated_at DESC, id DESC LIMIT 10");
            String created = plan(
                    statement,
                    "SELECT id FROM outbox WHERE state IN ('DEAD_LETTER', 'DISCARDED')"
                            + " ORDER BY created_at DESC, id DESC LIMIT 10");
            String purge = plan(
                    statement,
                    "SELECT id FROM outbox WHERE state = 'DISCARDED'"
                            + " AND updated_at < NOW() - interval '7 days' LIMIT 100");

            assertTrue(updated.contains("idx_outbox_listing_updated"), updated);
            assertTrue(created.contains("idx_outbox_listing_created"), created);
            assertTrue(purge.contains("idx_outbox_discarded"), purge);
        }
    }

    // --- Helpers ---

    private static void assertIndexesMatchContract() throws SQLException {
        for (String name : List.of("idx_outbox_listing_updated", "idx_outbox_listing_created")) {
            String definition = string("SELECT indexdef FROM pg_indexes WHERE indexname = '" + name + "'");
            String column = name.endsWith("updated") ? "updated_at" : "created_at";
            assertTrue(definition.contains("USING btree (" + column + " DESC, id DESC)"), definition);
            assertTrue(definition.contains(" WHERE "), definition);
            for (OutboxEntryState state : OutboxEntryState.values()) {
                assertEquals(
                        state != OutboxEntryState.PUBLISHED,
                        definition.contains("'" + state.name() + "'"),
                        name + " predicate for " + state);
            }
        }
        String discarded = string("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_outbox_discarded'");
        assertTrue(discarded.contains("USING btree (updated_at)"), discarded);
        assertTrue(discarded.contains("'DISCARDED'"), discarded);
        for (OutboxEntryState state : OutboxEntryState.values()) {
            if (state != OutboxEntryState.DISCARDED) {
                assertFalse(discarded.contains("'" + state.name() + "'"), discarded);
            }
        }
        assertEquals(
                "3",
                string("SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid"
                        + " WHERE c.relname IN ('idx_outbox_listing_updated', 'idx_outbox_listing_created',"
                        + " 'idx_outbox_discarded') AND i.indpred IS NOT NULL AND i.indisvalid"));
    }

    private static String plan(Statement statement, String sql) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (ResultSet rows = statement.executeQuery("EXPLAIN " + sql)) {
            while (rows.next()) {
                plan.append(rows.getString(1)).append('\n');
            }
        }
        return plan.toString();
    }

    private static Set<String> existingNewIndexes() throws SQLException {
        Set<String> found = new java.util.TreeSet<>();
        try (Connection connection = jdbc();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT indexname FROM pg_indexes WHERE indexname IN"
                        + " ('idx_outbox_listing_updated', 'idx_outbox_listing_created', 'idx_outbox_discarded')")) {
            while (rows.next()) {
                found.add(rows.getString(1));
            }
        }
        return found;
    }

    private static String objectInventory() throws SQLException {
        StringBuilder inventory = new StringBuilder();
        try (Connection connection = jdbc();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT 'index ' || indexname || ' ' || indexdef FROM pg_indexes WHERE schemaname = current_schema()"
                                + " UNION ALL SELECT 'column ' || table_name || '.' || column_name || ' ' || data_type"
                                + " || ' ' || is_nullable || ' ' || COALESCE(column_default, '') FROM"
                                + " information_schema.columns WHERE table_schema = current_schema()"
                                + " UNION ALL SELECT 'rows ' || count(*) FROM outbox ORDER BY 1")) {
            while (rows.next()) {
                inventory.append(rows.getString(1)).append('\n');
            }
        }
        return inventory.toString();
    }

    private static List<Map<String, Object>> outboxRows() throws SQLException {
        List<Map<String, Object>> found = new ArrayList<>();
        try (Connection connection = jdbc();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT * FROM outbox ORDER BY id")) {
            ResultSetMetaData meta = rows.getMetaData();
            while (rows.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(meta.getColumnName(i), rows.getObject(i));
                }
                found.add(row);
            }
        }
        return found;
    }

    private static FluentConfiguration flyway() {
        return Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations(LOCATION);
    }

    private static void insertEntryIn(OutboxEntryState state) throws SQLException {
        try (Connection connection = jdbc();
                PreparedStatement statement = connection.prepareStatement("INSERT INTO outbox "
                        + "(carrier_id, event_type, destination, destination_type, payload, state) "
                        + "VALUES (?, 'legacy.event', ?, 'SERVICE', '{}'::jsonb, ?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, "legacy-" + state.name());
            statement.setString(3, state.name());
            statement.executeUpdate();
        }
    }

    private static long relfilenode() throws SQLException {
        return Long.parseLong(string("SELECT relfilenode FROM pg_class WHERE oid = 'outbox'::regclass"));
    }

    private static String string(String sql) throws SQLException {
        try (Connection connection = jdbc();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static Connection jdbc() throws SQLException {
        return DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
    }
}
