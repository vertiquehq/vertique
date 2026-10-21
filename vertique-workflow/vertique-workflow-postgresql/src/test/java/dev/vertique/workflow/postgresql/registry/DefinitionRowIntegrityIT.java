// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.registry.store.DefinitionActivation;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Pool;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies how the durable definition registry keeps its table sound: a row that cannot be mapped does not stop the
 * reads of the other rows, first-publication activation is limited to the default scope, activation writes require an
 * available target, the stored name binding is always an object, and a filtered listing is filtered by the database.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class DefinitionRowIntegrityIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_definition_row_integrity_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String GOOD_ID = "integrity-good";
    private static final String TENANT_ID = "integrity-tenant";
    private static final String ACME = "acme";
    private static final String BELL = String.valueOf((char) 7);

    @BeforeEach
    void emptyTables() throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM workflow_definition_activations");
            statement.execute("DELETE FROM workflow_definitions");
        }
    }

    @Test
    @DisplayName("a definition row with a control character in its id does not stop the listing of the other rows")
    void poisonDefinitionRowDoesNotStopListing(Vertx vertx) throws Exception {
        // Given a good code row and a code row whose id holds a control character, if the table admits one
        try (Connection connection = connection()) {
            DefinitionTableRows.insertCode(connection, GOOD_ID, 1, "AVAILABLE");
            boolean rejected = rejectedByCheck(
                    () -> DefinitionTableRows.insertCode(connection, "integrity-bad" + BELL + "id", 1, "AVAILABLE"));

            Pool pool = ManagedDefinitionDatabase.openPool(vertx, db);
            try {
                PgWorkflowDefinitionRegistryStore store = store(pool);

                // When every definition is listed
                List<WorkflowDefinitionRecord> listed = await(store.list(new DefinitionFilter(null, null, null)));

                // Then the good row is returned, and the bad one is either refused by the table or skipped
                assertThat(listed)
                        .extracting(record -> record.key().definitionId())
                        .contains(GOOD_ID)
                        .allSatisfy(id -> assertThat(id).doesNotContain(BELL));
                if (rejected)
                    assertThat(count(connection, "workflow_definitions")).isEqualTo(1);
            } finally {
                await(pool.close());
            }
        }
    }

    @Test
    @DisplayName("an activation row with a control character in its scope does not stop the listing of the others")
    void poisonActivationRowDoesNotStopListing(Vertx vertx) throws Exception {
        // Given a good activation and an activation whose scope holds a control character, if the table admits one
        try (Connection connection = connection()) {
            DefinitionTableRows.insertCode(connection, GOOD_ID, 1, "AVAILABLE");
            insertActivation(connection, WorkflowScopes.DEFAULT, GOOD_ID);
            boolean rejected = rejectedByCheck(() -> insertActivation(connection, "bad" + BELL + "scope", GOOD_ID));

            Pool pool = ManagedDefinitionDatabase.openPool(vertx, db);
            try {
                PgWorkflowDefinitionRegistryStore store = store(pool);

                // When every activation is listed
                List<DefinitionActivation> listed = await(store.listActivations(null));

                // Then the good record is returned, and the bad one is either refused by the table or skipped
                assertThat(listed)
                        .extracting(DefinitionActivation::scopeKey)
                        .contains(WorkflowScopes.DEFAULT)
                        .allSatisfy(scope -> assertThat(scope).doesNotContain(BELL));
                if (rejected)
                    assertThat(count(connection, "workflow_definition_activations"))
                            .isEqualTo(1);
            } finally {
                await(pool.close());
            }
        }
    }

    @Test
    @DisplayName("a first publication in a tenant scope never activates the default scope")
    void tenantFirstPublicationIsNotActivatedAsDefault(Vertx vertx) throws Exception {
        // Given a default-scope row of an id
        try (Connection connection = connection()) {
            DefinitionTableRows.insertCode(connection, TENANT_ID, 1, "AVAILABLE");
            Pool pool = ManagedDefinitionDatabase.openPool(vertx, db);
            try {
                PgWorkflowDefinitionRegistryStore store = store(pool);

                // When a tenant-scope document of the same id and version is published with activation of the default
                Throwable failure = failureOf(store.upsertDocument(tenantDocument(), tenantText(), true));

                // Then the request is refused as an illegal state, and no activation record was written
                assertThat(failure).isNotNull();
                assertThat(causeChain(failure)).anyMatch(IllegalStateException.class::isInstance);
                assertThat(count(connection, "workflow_definition_activations")).isZero();
            } finally {
                await(pool.close());
            }
        }
    }

    @Test
    @DisplayName("every statement that writes an activation record requires an available target")
    void activationWritesRequireAnAvailableTarget(Vertx vertx) throws Exception {
        // Given a store observed at the pool and an available default-scope row
        try (Connection connection = connection()) {
            DefinitionTableRows.insertCode(connection, GOOD_ID, 1, "AVAILABLE");
            WorkflowDefinitionSqlObserver observer = new WorkflowDefinitionSqlObserver();
            Pool pool = observer.observe(ManagedDefinitionDatabase.openPool(vertx, db));
            try {
                PgWorkflowDefinitionRegistryStore store = store(pool);

                // When an activation is written explicitly and a first publication writes the default record
                await(store.putActivation(new DefinitionActivation(
                        WorkflowScopes.DEFAULT, GOOD_ID, WorkflowScopes.DEFAULT, 1, Instant.now())));
                await(store.upsertCode(codeRecord("integrity-first", 1), true));

                // Then both writes were issued, and each states the available status in its own text
                List<String> writes = observer.statements().stream()
                        .map(sql -> sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "))
                        .filter(sql -> sql.startsWith("insert into") && sql.contains("workflow_definition_activations"))
                        .toList();
                assertThat(writes).hasSize(2).allSatisfy(sql -> assertThat(sql).contains("'available'"));
            } finally {
                await(pool.close());
            }
        }
    }

    @Test
    @DisplayName("a stored name binding that is not an object is refused by the table")
    void nameBindingMustBeAnObject() throws Exception {
        try (Connection connection = connection()) {
            // Given an object binding, which the table accepts
            insertBinding(connection, "integrity-binding-object", "{\"a.B\":\"b\"}");

            // When a binding that is an array, a string, a number or a null is inserted
            // Then the check constraint rejects each
            int index = 0;
            for (String json : List.of("[]", "\"text\"", "7", "null")) {
                String id = "integrity-binding-" + index++;
                DefinitionTableRows.assertSqlState("23514", () -> insertBinding(connection, id, json));
            }
        }
    }

    @Test
    @DisplayName("a listing by scope is a scoped statement and returns only that scope")
    void scopedListingIsFilteredByTheDatabase(Vertx vertx) throws Exception {
        // Given a default-scope row and an acme row
        try (Connection connection = connection()) {
            DefinitionTableRows.insertCode(connection, GOOD_ID, 1, "AVAILABLE");
            insertAvailableDocument(connection, ACME, TENANT_ID, 1);
            WorkflowDefinitionSqlObserver observer = new WorkflowDefinitionSqlObserver();
            Pool pool = observer.observe(ManagedDefinitionDatabase.openPool(vertx, db));
            try {
                PgWorkflowDefinitionRegistryStore store = store(pool);

                // When the acme scope is listed
                List<WorkflowDefinitionRecord> listed =
                        await(store.list(new DefinitionFilter(ACME, null, Set.of(DefinitionStatus.AVAILABLE))));

                // Then only the acme row is returned
                assertThat(listed).extracting(record -> record.key().scopeKey()).containsExactly(ACME);

                // And the listing statement restricts the rows by scope and by status in its own WHERE clause
                List<String> selects = observer.statements().stream()
                        .map(sql -> sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "))
                        .filter(sql -> sql.startsWith("select") && sql.contains("from workflow_definitions"))
                        .toList();
                assertThat(selects).hasSize(1);
                String sql = selects.getFirst();
                assertThat(sql).contains(" where ");
                String restriction = sql.substring(sql.indexOf(" where "));
                assertThat(restriction).contains("scope_key").contains("status");
            } finally {
                await(pool.close());
            }
        }
    }

    // --- Fixtures ---

    private static PgWorkflowDefinitionRegistryStore store(Pool pool) {
        return new PgWorkflowDefinitionRegistryStore(pool, new PgDbExceptionMapper(), Optional.empty());
    }

    private static WorkflowDefinitionRecord tenantDocument() {
        Instant now = Instant.now();
        DefinitionKey key = new DefinitionKey(ACME, TENANT_ID, 1);
        return new WorkflowDefinitionRecord(
                key,
                DefinitionKind.DOCUMENT,
                "example.Contract",
                "example.State",
                "a".repeat(64),
                DefinitionStatus.AVAILABLE,
                new DefinitionProvenance("test", "memory://tenant", null, null, null, now),
                "JSON",
                now,
                now,
                Map.of());
    }

    private static WorkflowDefinitionDocument tenantText() {
        return new WorkflowDefinitionDocument(new DefinitionKey(ACME, TENANT_ID, 1), "{\"v\":1}", "JSON");
    }

    private static WorkflowDefinitionRecord codeRecord(String id, long version) {
        Instant now = Instant.now();
        return new WorkflowDefinitionRecord(
                new DefinitionKey(WorkflowScopes.DEFAULT, id, version),
                DefinitionKind.CODE,
                "example.Contract",
                "example.State",
                "a".repeat(64),
                DefinitionStatus.AVAILABLE,
                new DefinitionProvenance("code", "memory://code", null, null, null, now),
                null,
                now,
                now,
                Map.of());
    }

    private static void insertActivation(Connection connection, String scopeKey, String definitionId)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_definition_activations
                    (scope_key, definition_id, target_scope_key, definition_version, activated_at)
                VALUES (?, ?, 'default', 1, now())
                """)) {
            insert.setString(1, scopeKey);
            insert.setString(2, definitionId);
            insert.executeUpdate();
        }
    }

    private static void insertBinding(Connection connection, String definitionId, String bindingJson)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_definitions
                    (scope_key, definition_id, definition_version, kind, contract_name, state_type_name, plan_hash,
                     status, source_type, loaded_at, name_binding)
                VALUES ('default', ?, 1, 'CODE', 'example.Contract', 'example.State', repeat('a', 64), 'AVAILABLE',
                        'code', now(), ?::jsonb)
                """)) {
            insert.setString(1, definitionId);
            insert.setString(2, bindingJson);
            insert.executeUpdate();
        }
    }

    private static void insertAvailableDocument(Connection connection, String scopeKey, String id, long version)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO workflow_definitions
                    (scope_key, definition_id, definition_version, kind, contract_name, state_type_name, plan_hash,
                     status, document, document_format, document_digest, source_type, loaded_at)
                VALUES (?, ?, ?, 'DOCUMENT', 'example.Contract', 'example.State', repeat('a', 64), 'AVAILABLE',
                        '{}', 'YAML', encode(sha256(convert_to('{}', 'UTF8')), 'hex'), 'integrity-test', now())
                """)) {
            insert.setString(1, scopeKey);
            insert.setString(2, id);
            insert.setLong(3, version);
            insert.executeUpdate();
        }
    }

    /** Runs the insert and reports whether a check constraint refused it; any other failure propagates. */
    private static boolean rejectedByCheck(DefinitionTableRows.SqlAction insert) throws SQLException {
        try {
            insert.run();
            return false;
        } catch (SQLException e) {
            if ("23514".equals(e.getSQLState())) return true;
            throw e;
        }
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    private static Throwable failureOf(Future<?> future) throws Exception {
        try {
            future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable cause = failure; cause != null && !chain.contains(cause); cause = cause.getCause())
            chain.add(cause);
        return chain;
    }
}
