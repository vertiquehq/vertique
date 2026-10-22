// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.registry.store.ActivationClaim;
import dev.vertique.workflow.registry.store.ActivationOutcome;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionFilter.TenantScope;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.ReconcileOutcome;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Pool;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves that the durable definition registry reads and writes its table in the schema mapped for the
 * {@code workflow} instance, and that a store without a mapping keeps resolving the table through the
 * connection's default schema.
 *
 * <p>The mapped scenario starts from a decoy default-schema table with the real table name but an
 * incompatible shape, so a statement that ignores the mapping fails on its columns instead of silently
 * succeeding, and the decoy is compared with its state before the store ran. The recorded statements are
 * checked for any unqualified reference to the table.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgWorkflowDefinitionRegistrySchemaIT {

    private static final String TABLE = "workflow_definitions";
    private static final String MAPPED_SCHEMA = "orchestration";
    private static final String QUALIFIED = "\"" + MAPPED_SCHEMA + "\".\"" + TABLE + "\"";
    private static final String INSTANCES = "workflow_instances";
    private static final String QUALIFIED_INSTANCES = "\"" + MAPPED_SCHEMA + "\".\"" + INSTANCES + "\"";
    private static final String CODE_ID = "schema-placement-code";
    private static final String WORKFLOW_LOCATION = "classpath:db/migration/workflow";
    private static final String DEFINITION_ID = "schema-placement-order";
    private static final String PLAN_HASH = "a".repeat(64);
    private static final String OTHER_PLAN_HASH = "b".repeat(64);
    private static final int REGISTRY_STATEMENT_KINDS = 13;

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("wf_definition_schema_it");

    @AfterEach
    void resetDatabase() throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + MAPPED_SCHEMA + " CASCADE");
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
    }

    @Test
    @DisplayName("a mapped store keeps every definition row and statement in the mapped schema")
    void mappedStoreUsesOnlyTheMappedSchema(Vertx vertx) throws Exception {
        // Given: a decoy default-schema table with the real name, and a migration into a missing mapped schema
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public." + TABLE + " (decoy_key UUID PRIMARY KEY, sentinel TEXT NOT NULL)");
            statement.execute("INSERT INTO public." + TABLE + " VALUES (gen_random_uuid(), 'decoy')");
        }
        assertEquals(List.of(), schemaNames(MAPPED_SCHEMA), "the mapped schema does not exist before migration");
        List<String> decoyBefore = decoyState();
        Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .schemas(MAPPED_SCHEMA)
                .defaultSchema(MAPPED_SCHEMA)
                .locations(WORKFLOW_LOCATION)
                .load()
                .migrate();
        WorkflowDefinitionSqlObserver observer = new WorkflowDefinitionSqlObserver();
        Pool pool = observer.observe(ManagedDefinitionDatabase.openPool(vertx, db));
        try {
            PgWorkflowDefinitionRegistryStore store = new PgWorkflowDefinitionRegistryStore(
                    pool,
                    new PgDbExceptionMapper(),
                    Optional.empty(),
                    new DbSchemas(Map.of("workflow", MAPPED_SCHEMA)));

            // When: the store runs every public operation
            runLifecycle(store);

            // Then: the rows live in the mapped table and the decoy is exactly as before
            assertEquals(expectedStatuses(), statusesIn(QUALIFIED));
            assertEquals(decoyBefore, decoyState(), "the decoy table keeps its shape and rows");
            assertEquals(
                    List.of(MAPPED_SCHEMA + "." + TABLE),
                    tableLocations(TABLE).stream()
                            .filter(location -> location.startsWith(MAPPED_SCHEMA))
                            .toList());

            // Then: every statement that names the table qualified it with the mapped schema
            List<String> registrySql = registrySql(observer);
            assertEquals(
                    REGISTRY_STATEMENT_KINDS,
                    registrySql.stream().distinct().count(),
                    "the lifecycle reaches every registry statement: " + registrySql);
            for (String sql : registrySql) {
                assertTrue(sql.contains(QUALIFIED), sql);
                assertFalse(sql.replace(QUALIFIED, "").contains(TABLE), "no unqualified table reference: " + sql);
            }
        } finally {
            await(pool.close());
        }
    }

    @Test
    @DisplayName("code reconciliation, pin admission, resume and retirement guard run in the mapped schema")
    void mappedRetirementAndPinAdmissionUseTheMappedSchema(Vertx vertx) throws Exception {
        // Given: decoy default-schema tables with both real names and an incompatible shape, and the migration
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            for (String table : List.of(TABLE, INSTANCES)) {
                statement.execute(
                        "CREATE TABLE public." + table + " (decoy_key UUID PRIMARY KEY, sentinel TEXT NOT NULL)");
                statement.execute("INSERT INTO public." + table + " VALUES (gen_random_uuid(), 'decoy')");
            }
        }
        List<String> decoyBefore = decoyState(TABLE);
        List<String> instancesDecoyBefore = decoyState(INSTANCES);
        Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .schemas(MAPPED_SCHEMA)
                .defaultSchema(MAPPED_SCHEMA)
                .locations(WORKFLOW_LOCATION)
                .load()
                .migrate();
        DbSchemas schemas = new DbSchemas(Map.of("workflow", MAPPED_SCHEMA));
        WorkflowDefinitionSqlObserver observer = new WorkflowDefinitionSqlObserver();
        Pool observed = observer.observe(ManagedDefinitionDatabase.openPool(vertx, db));
        // Pin writes need the connection Vert.x began the transaction on, so they use an unwrapped pool.
        Pool plain = ManagedDefinitionDatabase.openPool(vertx, db);
        try {
            PgWorkflowDefinitionRegistryStore store = new PgWorkflowDefinitionRegistryStore(
                    observed, new PgDbExceptionMapper(), Optional.empty(), schemas);
            PgWorkflowInstanceRepository instances =
                    new PgWorkflowInstanceRepository(plain, new PgDbExceptionMapper(), schemas);
            DefinitionKey key = new DefinitionKey(null, CODE_ID, 1);

            // When: a code row is reconciled, the instance pin is admitted and its status moves through the
            // live-update, terminal-update and resume paths
            assertEquals(
                    ReconcileOutcome.INSERTED,
                    await(store.upsertCode(codeRecord(key, PLAN_HASH, DefinitionStatus.SUPERSEDED))));
            assertEquals(
                    ReconcileOutcome.UNCHANGED,
                    await(store.upsertCode(codeRecord(key, PLAN_HASH, DefinitionStatus.SUPERSEDED))));
            assertEquals(
                    ReconcileOutcome.REHASHED,
                    await(store.upsertCode(codeRecord(key, OTHER_PLAN_HASH, DefinitionStatus.SUPERSEDED))));
            await(store.recomputeActive(null, CODE_ID));
            assertEquals(
                    DefinitionStatus.ACTIVE,
                    await(store.find(key)).orElseThrow().status());

            WorkflowInstance running = instance(OTHER_PLAN_HASH, WorkflowStatus.RUNNING, 0);
            await(plain.withTransaction(tx -> instances.insert(running, tx)));
            assertEquals(
                    1,
                    update(
                            plain,
                            instances,
                            running.withStatus(WorkflowStatus.WAITING).withVersion(1),
                            0));
            assertEquals(
                    1,
                    update(
                            plain,
                            instances,
                            running.withStatus(WorkflowStatus.COMPLETED).withVersion(2),
                            1));
            assertEquals(1, update(plain, instances, running.withVersion(3), 2));
            assertEquals("RUNNING", instanceStatus(running.id()));

            // When: the code version is deprecated, past cooling, and retirement is tried while the pin is live
            await(store.transition(key, DefinitionStatus.ACTIVE, DefinitionStatus.DEPRECATED));
            backdateStatusChange();
            assertEquals(Reason.PINNED_INSTANCES, failureReason(store.retire(key)));
            assertEquals(Long.valueOf(1), await(instances.countNonTerminal(CODE_ID, 1)));

            // Then: once the instance finished the retirement and the reinstatement both run on the mapped rows
            assertEquals(
                    1,
                    update(
                            plain,
                            instances,
                            running.withStatus(WorkflowStatus.COMPLETED).withVersion(4),
                            3));
            await(store.retire(key));
            assertEquals(
                    DefinitionStatus.RETIRED,
                    await(store.find(key)).orElseThrow().status());
            await(store.transition(key, DefinitionStatus.RETIRED, DefinitionStatus.SUPERSEDED));
            assertEquals(
                    DefinitionStatus.ACTIVE,
                    await(store.find(key)).orElseThrow().status());
            await(store.resetStaleActivating(CODE_ID));

            // Then: the decoys are exactly as before
            assertEquals(decoyBefore, decoyState(TABLE), "the definitions decoy keeps its shape and rows");
            assertEquals(instancesDecoyBefore, decoyState(INSTANCES), "the instances decoy keeps its shape and rows");

            // Then: every store statement that names either table qualified it with the mapped schema
            List<String> sqlWithTables = observer.statements().stream()
                    .filter(sql -> sql.contains(TABLE) || sql.contains(INSTANCES))
                    .toList();
            assertTrue(
                    sqlWithTables.stream()
                            .anyMatch(sql -> sql.contains("count(*)") && sql.contains(QUALIFIED_INSTANCES)),
                    "the retirement guard counts the mapped instances table: " + sqlWithTables);
            for (String sql : sqlWithTables) {
                assertFalse(
                        sql.replace(QUALIFIED, "")
                                .replace(QUALIFIED_INSTANCES, "")
                                .contains(TABLE),
                        "no unqualified definitions table reference: " + sql);
                assertFalse(
                        sql.replace(QUALIFIED, "")
                                .replace(QUALIFIED_INSTANCES, "")
                                .contains(INSTANCES),
                        "no unqualified instances table reference: " + sql);
            }
        } finally {
            await(observed.close());
            await(plain.close());
        }
    }

    @Test
    @DisplayName("an unmapped store keeps resolving the table through the default schema")
    void unmappedStoreUsesTheDefaultSchema(Vertx vertx) throws Exception {
        // Given: the migration in the default schema
        Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations(WORKFLOW_LOCATION)
                .load()
                .migrate();
        WorkflowDefinitionSqlObserver observer = new WorkflowDefinitionSqlObserver();
        Pool pool = observer.observe(ManagedDefinitionDatabase.openPool(vertx, db));
        try {
            // When: the store built without a placement runs every public operation
            PgWorkflowDefinitionRegistryStore store =
                    new PgWorkflowDefinitionRegistryStore(pool, new PgDbExceptionMapper(), Optional.empty());
            runLifecycle(store);

            // Then: the rows live in the default schema and no statement is qualified or quoted
            assertEquals(expectedStatuses(), statusesIn("public." + TABLE));
            assertEquals(List.of("public." + TABLE), tableLocations(TABLE));
            List<String> registrySql = registrySql(observer);
            assertEquals(
                    REGISTRY_STATEMENT_KINDS, registrySql.stream().distinct().count());
            for (String sql : registrySql) {
                assertFalse(sql.contains("\"" + TABLE + "\""), sql);
                assertFalse(sql.contains(MAPPED_SCHEMA), sql);
            }

            // Then: a mapping that names only another instance leaves the table unqualified as well
            PgWorkflowDefinitionRegistryStore unrelated = new PgWorkflowDefinitionRegistryStore(
                    pool, new PgDbExceptionMapper(), Optional.empty(), new DbSchemas(Map.of("inbox-outbox", "other")));
            assertEquals(
                    DefinitionStatus.DEPRECATED,
                    await(unrelated.find(key(1))).orElseThrow().status());
        } finally {
            await(pool.close());
        }
    }

    @Test
    @DisplayName("an invalid mapped schema fails at construction, before any query")
    void invalidMappingFailsAtConstruction(Vertx vertx) throws Exception {
        Pool pool = ManagedDefinitionDatabase.openPool(vertx, db);
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new PgWorkflowDefinitionRegistryStore(
                            pool,
                            new PgDbExceptionMapper(),
                            Optional.empty(),
                            new DbSchemas(Map.of("workflow", "not.valid"))));
        } finally {
            await(pool.close());
        }
    }

    // --- Scenario ---

    private static Map<Long, String> expectedStatuses() {
        return Map.of(1L, "DEPRECATED", 2L, "SUPERSEDED", 3L, "ACTIVE", 4L, "CANDIDATE");
    }

    /** Exercises insert, find, document read, list, claim, complete, release, reconcile, transition and recompute. */
    private static void runLifecycle(WorkflowDefinitionRegistryStore store) throws Exception {
        await(store.insertCandidate(record(1, PLAN_HASH, DefinitionStatus.CANDIDATE), document(1)));
        assertEquals(
                DefinitionStatus.CANDIDATE,
                await(store.find(key(1))).orElseThrow().status());
        activate(store, 1);
        await(store.insertCandidate(record(2, PLAN_HASH, DefinitionStatus.CANDIDATE), document(2)));
        activate(store, 2);
        assertEquals(
                DefinitionStatus.SUPERSEDED,
                await(store.find(key(1))).orElseThrow().status());

        assertEquals(
                "{\"v\":2}", await(store.findDocument(key(2))).orElseThrow().document());
        assertEquals(
                2,
                await(store.list(new DefinitionFilter(TenantScope.ALL, null, null)))
                        .size());

        assertEquals(
                ReconcileOutcome.INSERTED,
                await(store.upsertDocument(record(3, PLAN_HASH, DefinitionStatus.SUPERSEDED), document(3))));
        assertEquals(
                ReconcileOutcome.UNCHANGED,
                await(store.upsertDocument(record(3, PLAN_HASH, DefinitionStatus.SUPERSEDED), document(3))));
        assertEquals(
                ReconcileOutcome.DRIFT,
                await(store.upsertDocument(record(3, OTHER_PLAN_HASH, DefinitionStatus.SUPERSEDED), document(3))));
        await(store.recomputeActive(null, DEFINITION_ID));
        assertEquals(
                DefinitionStatus.ACTIVE, await(store.find(key(3))).orElseThrow().status());

        await(store.transition(key(1), DefinitionStatus.SUPERSEDED, DefinitionStatus.DEPRECATED));

        await(store.insertCandidate(record(4, PLAN_HASH, DefinitionStatus.CANDIDATE), document(4)));
        ActivationClaim claim = await(store.claim(key(4)));
        await(store.release(claim));
        assertEquals(
                DefinitionStatus.CANDIDATE,
                await(store.find(key(4))).orElseThrow().status());
    }

    private static WorkflowDefinitionRecord codeRecord(DefinitionKey key, String planHash, DefinitionStatus status) {
        Instant now = Instant.now();
        return new WorkflowDefinitionRecord(
                key,
                DefinitionKind.CODE,
                planHash,
                status,
                new DefinitionProvenance("code", "memory://code", null, null, null, now),
                null,
                now,
                now);
    }

    private static WorkflowInstance instance(String planHash, WorkflowStatus status, long version) {
        Instant now = Instant.now();
        return new WorkflowInstance(
                new WorkflowInstanceId(UUID.randomUUID()),
                CODE_ID,
                1L,
                planHash,
                version,
                status,
                null,
                null,
                "start",
                null,
                null,
                null,
                "{}",
                null,
                null,
                now,
                now,
                null);
    }

    private static int update(Pool pool, PgWorkflowInstanceRepository repository, WorkflowInstance next, long expected)
            throws Exception {
        Integer rows = await(pool.withTransaction(tx -> repository.updateOptimistic(next, expected, tx)));
        return rows;
    }

    private static Reason failureReason(Future<?> future) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> await(future));
        return ((WorkflowDefinitionLifecycleException) failure.getCause()).reason();
    }

    /** Moves the last status change of the mapped definitions an hour back, so no cooling window is open. */
    private static void backdateStatusChange() throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute("UPDATE " + QUALIFIED + " SET status_changed_at = now() - interval '1 hour'");
        }
    }

    private static String instanceStatus(WorkflowInstanceId id) throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT status FROM " + QUALIFIED_INSTANCES + " WHERE id = '" + id.value() + "'")) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static void activate(WorkflowDefinitionRegistryStore store, long version) throws Exception {
        ActivationClaim claim = await(store.claim(key(version)));
        assertEquals(ActivationOutcome.ACTIVE, await(store.complete(claim)));
    }

    private static DefinitionKey key(long version) {
        return new DefinitionKey(null, DEFINITION_ID, version);
    }

    private static WorkflowDefinitionRecord record(long version, String planHash, DefinitionStatus status) {
        Instant now = Instant.now();
        return new WorkflowDefinitionRecord(
                key(version),
                DefinitionKind.DOCUMENT,
                planHash,
                status,
                new DefinitionProvenance("test", "memory://" + version, null, null, null, now),
                "JSON",
                now,
                now);
    }

    private static WorkflowDefinitionDocument document(long version) {
        return new WorkflowDefinitionDocument(key(version), "{\"v\":" + version + "}", "JSON");
    }

    // --- Observation ---

    private static List<String> registrySql(WorkflowDefinitionSqlObserver observer) {
        return observer.statements().stream().filter(sql -> sql.contains(TABLE)).toList();
    }

    private static Map<Long, String> statusesIn(String table) throws SQLException {
        Map<Long, String> statuses = new TreeMap<>();
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT definition_version, status FROM " + table)) {
            while (rows.next()) statuses.put(rows.getLong(1), rows.getString(2));
        }
        return statuses;
    }

    private static List<String> tableLocations(String table) throws SQLException {
        List<String> locations = new ArrayList<>();
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT table_schema || '.' || table_name FROM information_schema.tables WHERE table_name = '"
                                + table + "' ORDER BY 1")) {
            while (rows.next()) locations.add(rows.getString(1));
        }
        return locations;
    }

    private static List<String> schemaNames(String schema) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery("SELECT nspname FROM pg_namespace WHERE nspname = '" + schema + "'")) {
            while (rows.next()) names.add(rows.getString(1));
        }
        return names;
    }

    /** The decoy's columns and rows as sorted text. */
    private static List<String> decoyState() throws SQLException {
        return decoyState(TABLE);
    }

    private static List<String> decoyState(String table) throws SQLException {
        List<String> state = new ArrayList<>();
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public'"
                            + " AND table_name = '" + table + "'")) {
                while (rows.next()) state.add("column " + rows.getString(1));
            }
            try (ResultSet rows = statement.executeQuery("SELECT sentinel FROM public." + table)) {
                while (rows.next()) state.add("row " + rows.getString(1));
            }
        }
        state.sort(null);
        return state;
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
}
