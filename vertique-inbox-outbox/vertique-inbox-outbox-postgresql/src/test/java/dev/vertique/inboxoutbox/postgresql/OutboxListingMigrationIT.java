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
            statement.execute("CREATE INDEX CONCURRENTLY idx_outbox_listing_updated ON outbox"
                    + " (updated_at DESC, id DESC) WHERE state IN ('DEAD_LETTER', 'DISCARDED')");
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
            "the planner uses the listing indexes for dead-letter and discarded listings, in both sorts, and the purge index")
    void plannerUsesTheIndexes() throws Exception {
        flyway().load().migrate();
        // a table shaped like production: the relay's states and the published backlog dominate
        try (Connection connection = jdbc();
                Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO outbox (carrier_id, event_type, destination, destination_type, payload,"
                    + " state, created_at, updated_at) SELECT gen_random_uuid(), 'e', 'd', 'SERVICE', '{}'::jsonb,"
                    + " CASE WHEN n % 100 < 90 THEN 'PUBLISHED' WHEN n % 100 < 94 THEN 'PENDING'"
                    + " WHEN n % 100 < 97 THEN 'DEAD_LETTER' ELSE 'DISCARDED' END,"
                    + " NOW() - (n * interval '1 minute'), NOW() - (n * interval '1 minute')"
                    + " FROM generate_series(1, 20000) AS n");
            statement.execute("ANALYZE outbox");
        }

        List<String> stateSets = List.of("'DEAD_LETTER'", "'DISCARDED'", "'DEAD_LETTER', 'DISCARDED'");
        try (Connection connection = jdbc();
                Statement statement = connection.createStatement()) {
            // as the database plans them: an index scan, never a scan of the table or a full sort. The older
            // single-state indexes may legitimately win a single-state listing sorted by updated_at.
            for (String states : stateSets) {
                for (String column : List.of("updated", "created")) {
                    String plan = plan(statement, listing(states, column));

                    assertTrue(
                            plan.matches("(?s).*Index (Only )?Scan.*"),
                            "states " + states + " by " + column + ":\n" + plan);
                    assertFalse(plan.contains("Seq Scan"), "states " + states + " by " + column + ":\n" + plan);
                    if (column.equals("created") || states.contains(",")) {
                        assertTrue(
                                plan.contains("idx_outbox_listing_" + column),
                                "states " + states + " by " + column + ":\n" + plan);
                    }
                }
            }
        }
        // with the older single-state indexes out of the way (inside a transaction that is rolled back), the
        // listing indexes alone must serve every dead-letter and discarded listing
        try (Connection connection = jdbc();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("DROP INDEX idx_outbox_dead_letter, idx_outbox_discarded");
            for (String states : stateSets) {
                for (String column : List.of("updated", "created")) {
                    String plan = plan(statement, listing(states, column));

                    assertTrue(
                            plan.contains("idx_outbox_listing_" + column),
                            "states " + states + " by " + column + ":\n" + plan);
                    assertFalse(plan.contains("Sort"), "states " + states + " by " + column + ":\n" + plan);
                }
            }
            connection.rollback();
        }
        try (Connection connection = jdbc();
                Statement statement = connection.createStatement()) {
            String purge = plan(
                    statement,
                    "SELECT id FROM outbox WHERE state = 'DISCARDED'"
                            + " AND updated_at < NOW() - interval '7 days' LIMIT 100");

            assertTrue(purge.contains("idx_outbox_discarded"), purge);
        }
    }

    private static String listing(String states, String column) {
        return "SELECT id FROM outbox WHERE state IN (" + states + ") ORDER BY " + column
                + "_at DESC, id DESC LIMIT 51";
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
                        state == OutboxEntryState.DEAD_LETTER || state == OutboxEntryState.DISCARDED,
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
