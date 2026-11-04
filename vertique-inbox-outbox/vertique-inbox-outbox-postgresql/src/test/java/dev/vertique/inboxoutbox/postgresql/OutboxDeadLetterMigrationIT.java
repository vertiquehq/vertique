// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
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
 * Proves the dead-letter administration migration against a database that already holds outbox entries, and that
 * the script can be run a second time.
 *
 * <p>The container starts without a migration so the test controls the target version: it migrates to the version
 * before the administration migration, seeds one entry per state, and then applies the rest.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class OutboxDeadLetterMigrationIT {

    private static final String LOCATION = "classpath:db/migration/inbox-outbox";
    private static final String SCRIPT = "/db/migration/inbox-outbox/V003__add_outbox_dead_letter_administration.sql";
    private static final String VERSION_BEFORE = "002";

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("outbox_dead_letter_migration");

    private long relfilenodeBefore;

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
    }

    @Test
    @DisplayName("existing rows start at revision zero without a rewrite, and the objects match the contract")
    void migratesPopulatedTable() throws SQLException {
        // When: the administration migration is applied
        flyway().load().migrate();

        // Then: every existing row reads revision 0 and the table file is unchanged
        Map<String, Long> revisions = new HashMap<>();
        try (Connection connection = jdbc();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT state, admin_revision FROM outbox")) {
            while (rows.next()) {
                revisions.put(rows.getString(1), rows.getLong(2));
            }
        }
        assertEquals(OutboxEntryState.values().length, revisions.size());
        assertTrue(revisions.values().stream().allMatch(revision -> revision == 0L), revisions.toString());
        assertEquals(relfilenodeBefore, relfilenode());
        assertEquals(
                "NO",
                string("SELECT is_nullable FROM information_schema.columns WHERE table_name = 'outbox'"
                        + " AND column_name = 'admin_revision'"));
        assertObjectsMatchContract();
    }

    @Test
    @DisplayName("the script can be run again and changes nothing")
    void rerunnable() throws Exception {
        flyway().load().migrate();
        String objectsBefore = objectInventory();
        String script;
        try (InputStream in = OutboxDeadLetterMigrationIT.class.getResourceAsStream(SCRIPT)) {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        // When: the script runs a second time in one transaction, as Flyway runs it
        try (Connection connection = jdbc()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(script);
            }
            connection.commit();
        }

        // Then: nothing was added or changed, and a row already in the table is untouched
        assertEquals(objectsBefore, objectInventory());
        assertObjectsMatchContract();
    }

    @Test
    @DisplayName("an index the operator pre-built concurrently under the same name is accepted")
    void acceptsPrebuiltPartialIndex() throws Exception {
        try (Connection connection = jdbc();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE INDEX CONCURRENTLY idx_outbox_dead_letter_listing ON outbox"
                    + " (updated_at DESC, id DESC) WHERE state = 'DEAD_LETTER'");
        }

        flyway().load().migrate();

        assertObjectsMatchContract();
    }

    // --- Helpers ---

    private static void assertObjectsMatchContract() throws SQLException {
        // receipt table: primary key on operation_id, no foreign key, intent columns required, completion optional
        assertEquals(
                "operation_id",
                string("SELECT a.attname FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid"
                        + " AND a.attnum = ANY (i.indkey) WHERE i.indrelid = 'outbox_admin_operations'::regclass"
                        + " AND i.indisprimary"));
        assertEquals(
                "0",
                string("SELECT count(*) FROM pg_constraint WHERE contype = 'f' AND"
                        + " (conrelid = 'outbox_admin_operations'::regclass OR confrelid = 'outbox'::regclass)"));
        Set<String> required = new HashSet<>();
        Set<String> optional = new HashSet<>();
        try (Connection connection = jdbc();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT column_name, is_nullable FROM"
                        + " information_schema.columns WHERE table_name = 'outbox_admin_operations'")) {
            while (rows.next()) {
                ("NO".equals(rows.getString(2)) ? required : optional).add(rows.getString(1));
            }
        }
        assertEquals(
                Set.of(
                        "operation_id",
                        "action",
                        "entry_id",
                        "expected_revision",
                        "reason",
                        "actor_type",
                        "actor_id",
                        "recorded_at"),
                required);
        assertEquals(
                Set.of(
                        "attempt",
                        "max_attempts",
                        "error_type",
                        "destination_type",
                        "destination",
                        "event_type",
                        "aggregate_type",
                        "aggregate_id",
                        "disposition",
                        "result_revision"),
                optional);
        assertEquals(
                "1024",
                string("SELECT character_maximum_length FROM information_schema.columns WHERE table_name ="
                        + " 'outbox_admin_operations' AND column_name = 'reason'"));
        assertEquals(
                "64",
                string("SELECT character_maximum_length FROM information_schema.columns WHERE table_name ="
                        + " 'outbox_admin_operations' AND column_name = 'actor_type'"));
        assertEquals(
                "255",
                string("SELECT character_maximum_length FROM information_schema.columns WHERE table_name ="
                        + " 'outbox_admin_operations' AND column_name = 'actor_id'"));

        // indexes
        String entryIndex =
                string("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_outbox_admin_operations_entry'");
        assertTrue(entryIndex.contains("(entry_id, recorded_at DESC, operation_id DESC)"), entryIndex);
        String recordedIndex =
                string("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_outbox_admin_operations_recorded_at'");
        assertTrue(recordedIndex.contains("(recorded_at)"), recordedIndex);
        String listing = string("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_outbox_dead_letter_listing'");
        assertTrue(listing.contains("(updated_at DESC, id DESC)"), listing);
        assertTrue(listing.contains("DEAD_LETTER"), listing);
        assertTrue(listing.contains("WHERE"), listing);
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
