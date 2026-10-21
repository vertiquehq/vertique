// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.DefinitionTableRows.assertSqlState;
import static dev.vertique.workflow.postgresql.registry.DefinitionTableRows.insertCode;
import static dev.vertique.workflow.postgresql.registry.DefinitionTableRows.insertCodeWithDigest;
import static dev.vertique.workflow.postgresql.registry.DefinitionTableRows.insertDocumentOmittingDigest;
import static dev.vertique.workflow.postgresql.registry.DefinitionTableRows.insertDocumentWithDigest;
import static dev.vertique.workflow.postgresql.registry.DefinitionTableRows.sha256Hex;
import static dev.vertique.workflow.postgresql.registry.DefinitionTableRows.storedDigest;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.query.WorkflowInstanceQuery;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.tasks.TaskFilter;
import dev.vertique.workflow.tasks.TaskRecord;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies the operational properties of the scope migration that its structural test does not pin: the digest
 * constraint of the definition table, the use of the scope keyset indexes by scope-filtered queries, and a migration
 * over columns and indexes that an operator created ahead of the upgrade. Every scenario uses a database of its own.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ScopeMigrationOperationsIT {

    private static final String INSTANCE_INDEX = "idx_workflow_instances_scope_updated_at";
    private static final String TASK_INDEX = "idx_workflow_tasks_scope_updated_at";

    private static final String INSTANCE_ID = "00000000-0000-0000-0000-0000000000a1";
    private static final String TASK_ID = "00000000-0000-0000-0000-0000000000b1";

    /** Scope whose rows the index scenario reads; each of the 40 scopes holds roughly one row in 41. */
    private static final String QUERIED_SCOPE = "scope-7";

    private static final int SEEDED_ROWS = 3200;
    private static final int PAGE_SIZE = 10;

    static final PostgresContainer digestDb = new PostgresContainer().withDatabaseName("wf_scope_digest_it");

    static final PostgresContainer indexDb = new PostgresContainer().withDatabaseName("wf_scope_index_it");

    static final PostgresContainer aheadDb = new PostgresContainer().withDatabaseName("wf_scope_ahead_it");

    @Test
    void digestConstraintAcceptsOnlyDocumentRowsWithALowercaseHexDigest() throws Exception {
        // Given a database migrated to the latest version
        flyway(digestDb).load().migrate();

        try (Connection connection = connection(digestDb)) {
            // Then the digest column is a 64-character string guarded by the lowercase hex constraint
            assertThat(columnShape(connection, "workflow_definitions", "document_digest"))
                    .startsWith("character varying|64|");
            assertThat(scalar(connection, """
                            SELECT pg_get_constraintdef(oid) FROM pg_constraint
                            WHERE conname = 'ck_workflow_definitions_digest'
                              AND conrelid = 'workflow_definitions'::regclass
                            """)).contains("document_digest").contains("[0-9a-f]{64}");

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

    @Test
    void scopeFilteredKeysetQueriesCanUseTheScopeIndexes(Vertx vertx) throws Exception {
        // Given a database upgraded to the latest version
        flyway(indexDb).load().migrate();

        // And the planner may not fall back to a sequential scan, so it must choose among the indexes
        try (Connection connection = connection(indexDb);
                Statement statement = connection.createStatement()) {
            String databaseName = scalar(connection, "SELECT current_database()");
            statement.execute("ALTER DATABASE \"" + databaseName + "\" SET enable_seqscan = off");

            // And instances and tasks spread over 40 scopes and the default scope, some of them archived
            statement.executeUpdate("""
                    INSERT INTO workflow_instances
                        (id, definition_id, definition_version, plan_hash, status, current_step_id, state_json,
                         created_at, updated_at, archived_at, scope_key)
                    SELECT md5('instance-' || g)::uuid, 'orders', 1, repeat('a', 64), 'RUNNING', 'start',
                           '{}'::jsonb,
                           timestamptz '2026-05-10T00:00:00Z' + g * interval '1 second',
                           timestamptz '2026-05-10T00:00:00Z' + g * interval '1 second',
                           CASE WHEN g %% 13 = 0 THEN timestamptz '2026-06-01T00:00:00Z' END,
                           CASE WHEN g %% 41 = 0 THEN 'default' ELSE 'scope-' || (g %% 41) END
                    FROM generate_series(1, %d) g
                    """.formatted(SEEDED_ROWS));
            statement.executeUpdate("""
                    INSERT INTO workflow_tasks
                        (task_id, workflow_id, step_id, status, assignee_type, assignee_key,
                         decisions_snapshot_json, created_at, updated_at, scope_key)
                    SELECT md5('task-' || g)::uuid, md5('instance-' || g)::uuid, 'review', 'OPEN', 'ROLE',
                           'reviewers', '[]'::jsonb,
                           timestamptz '2026-05-10T00:00:00Z' + g * interval '1 second',
                           timestamptz '2026-05-10T00:00:00Z' + g * interval '1 second',
                           CASE WHEN g %% 41 = 0 THEN 'default' ELSE 'scope-' || (g %% 41) END
                    FROM generate_series(1, %d) g
                    """.formatted(SEEDED_ROWS));
            statement.execute("ANALYZE workflow_instances");
            statement.execute("ANALYZE workflow_tasks");
        }

        // And the keyset pages the queried scope must return, read without touching any index
        List<String> expectedInstanceIds = new ArrayList<>();
        List<String> expectedTaskIds = new ArrayList<>();
        try (Connection reference = connection(indexDb);
                Statement settings = reference.createStatement()) {
            settings.execute("SET enable_seqscan = on");
            settings.execute("SET enable_indexscan = off");
            settings.execute("SET enable_indexonlyscan = off");
            settings.execute("SET enable_bitmapscan = off");
            expectedInstanceIds.addAll(strings(reference, """
                    SELECT id::text FROM workflow_instances
                    WHERE scope_key = ? AND archived_at IS NULL
                    ORDER BY updated_at DESC, id ASC LIMIT 20
                    """, QUERIED_SCOPE));
            expectedTaskIds.addAll(strings(reference, """
                    SELECT t.task_id::text FROM workflow_tasks t
                    WHERE t.scope_key = ?
                      AND NOT EXISTS (SELECT 1 FROM workflow_instances i
                                      WHERE i.id = t.workflow_id AND i.archived_at IS NOT NULL)
                    ORDER BY t.updated_at DESC, t.task_id ASC LIMIT 20
                    """, QUERIED_SCOPE));
        }
        assertThat(expectedInstanceIds).as("two full pages of live instances").hasSize(2 * PAGE_SIZE);
        assertThat(expectedTaskIds).as("two full pages of live tasks").hasSize(2 * PAGE_SIZE);

        // And the scan counters of both scope indexes before any query ran
        long instanceScansBefore;
        long taskScansBefore;
        try (Connection stats = connection(indexDb)) {
            instanceScansBefore = indexScans(stats, INSTANCE_INDEX);
            taskScansBefore = indexScans(stats, TASK_INDEX);
        }

        // When the real repositories read the first page and the keyset continuation for the scope
        // (the pool is built after the planner setting, so its sessions carry it)
        Pool pool = pool(vertx, indexDb);
        try {
            PgDbExceptionMapper mapper = new PgDbExceptionMapper();
            PgWorkflowInstanceRepository instances = new PgWorkflowInstanceRepository(pool, mapper);
            PgTaskStore tasks = new PgTaskStore(pool, mapper);

            WorkflowInstanceQuery instanceQuery = WorkflowInstanceQuery.none().withScopeKey(QUERIED_SCOPE);
            PagedResult<WorkflowInstance> instancePage1 =
                    await(instances.findFiltered(instanceQuery, PageCursor.first(PAGE_SIZE)));
            PagedResult<WorkflowInstance> instancePage2 =
                    await(instances.findFiltered(instanceQuery, PageCursor.fromToken(instancePage1.nextCursorToken())));

            TaskFilter taskFilter = TaskFilter.empty().withScopeKey(QUERIED_SCOPE);
            PagedResult<TaskRecord> taskPage1 =
                    await(tasks.findByFilter(taskFilter, PageCursor.first(PAGE_SIZE), pool));
            PagedResult<TaskRecord> taskPage2 =
                    await(tasks.findByFilter(taskFilter, PageCursor.fromToken(taskPage1.nextCursorToken()), pool));

            // Then the pages hold only the scope's live rows, in keyset order across the page boundary
            assertThat(instancePage1.hasMore()).isTrue();
            assertThat(taskPage1.hasMore()).isTrue();
            List<WorkflowInstance> instanceRows = new ArrayList<>(instancePage1.items());
            instanceRows.addAll(instancePage2.items());
            List<TaskRecord> taskRows = new ArrayList<>(taskPage1.items());
            taskRows.addAll(taskPage2.items());
            assertThat(instanceRows).extracting(WorkflowInstance::scopeKey).containsOnly(QUERIED_SCOPE);
            assertThat(taskRows).extracting(TaskRecord::scopeKey).containsOnly(QUERIED_SCOPE);
            assertThat(instanceRows)
                    .extracting(row -> row.id().value().toString())
                    .containsExactlyElementsOf(expectedInstanceIds);
            assertThat(taskRows).extracting(row -> row.taskId().toString()).containsExactlyElementsOf(expectedTaskIds);
        } finally {
            await(pool.close());
        }

        // And the scan counter of each scope index rises
        // (an idle backend publishes its statistics lazily but a closing backend publishes them at once, so the
        // pool is closed first and the counters are then polled until the deadline)
        try (Connection stats = connection(indexDb)) {
            assertThat(awaitScansAbove(stats, INSTANCE_INDEX, instanceScansBefore))
                    .as("scans of %s", INSTANCE_INDEX)
                    .isGreaterThan(instanceScansBefore);
            assertThat(awaitScansAbove(stats, TASK_INDEX, taskScansBefore))
                    .as("scans of %s", TASK_INDEX)
                    .isGreaterThan(taskScansBefore);
        }
    }

    @Test
    void migrationSucceedsWhenColumnsAndIndexesWereCreatedAheadOfTime() throws Exception {
        // Given a database at the first version
        flyway(aheadDb).target("1").load().migrate();
        long instanceIndexBefore;
        long taskIndexBefore;
        try (Connection connection = connection(aheadDb);
                Statement statement = connection.createStatement()) {
            // And the operator procedure applied by hand: the columns, then the indexes built concurrently
            assertThat(connection.getAutoCommit())
                    .as("concurrent index builds cannot run inside a transaction")
                    .isTrue();
            statement.execute(
                    "ALTER TABLE workflow_tasks ADD COLUMN IF NOT EXISTS scope_key VARCHAR(255) NOT NULL DEFAULT 'default'");
            statement.execute(
                    "ALTER TABLE workflow_instances ADD COLUMN IF NOT EXISTS scope_key VARCHAR(255) NOT NULL DEFAULT 'default'");

            // And an instance and a task carrying scope acme
            statement.executeUpdate("""
                    INSERT INTO workflow_instances
                        (id, definition_id, definition_version, plan_hash, status, current_step_id, state_json,
                         created_at, updated_at, scope_key)
                    VALUES ('%s', 'orders', 1, repeat('a', 64), 'WAITING', 'review', '{}'::jsonb,
                            '2026-05-10T00:00:00Z', '2026-05-10T00:00:00Z', 'acme')
                    """.formatted(INSTANCE_ID));
            statement.executeUpdate("""
                    INSERT INTO workflow_tasks
                        (task_id, workflow_id, step_id, status, assignee_type, assignee_key,
                         decisions_snapshot_json, created_at, updated_at, scope_key)
                    VALUES ('%s', '%s', 'review', 'OPEN', 'ROLE', 'reviewers', '[]'::jsonb,
                            '2026-05-10T00:00:00Z', '2026-05-10T00:00:00Z', 'acme')
                    """.formatted(TASK_ID, INSTANCE_ID));
            statement.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_workflow_tasks_scope_updated_at"
                    + " ON workflow_tasks (scope_key, updated_at DESC, task_id ASC)");
            statement.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_workflow_instances_scope_updated_at"
                    + " ON workflow_instances (scope_key, updated_at DESC, id ASC)");
            instanceIndexBefore = Long.parseLong(scalar(connection, "SELECT '" + INSTANCE_INDEX + "'::regclass::oid"));
            taskIndexBefore = Long.parseLong(scalar(connection, "SELECT '" + TASK_INDEX + "'::regclass::oid"));
        }

        // When the database is migrated to the latest version
        Flyway latest = flyway(aheadDb).load();
        latest.migrate();

        // Then the migration is applied as version 2 and nothing is left pending
        assertThat(latest.info().applied())
                .extracting(MigrationInfo::getVersion)
                .containsExactly(MigrationVersion.fromVersion("1"), MigrationVersion.fromVersion("2"));
        assertThat(latest.info().pending()).isEmpty();

        try (Connection connection = connection(aheadDb)) {
            // And each table still has exactly one scope_key column, and the instances also have the definition scope
            assertThat(columnCount(connection, "workflow_instances", "scope_key"))
                    .isEqualTo(1);
            assertThat(columnCount(connection, "workflow_tasks", "scope_key")).isEqualTo(1);
            assertThat(columnCount(connection, "workflow_instances", "definition_scope_key"))
                    .isEqualTo(1);

            // And each index exists once, is valid and is the one built ahead of time
            assertThat(indexes(connection, INSTANCE_INDEX))
                    .extracting(IndexInfo::valid)
                    .containsExactly(true);
            assertThat(indexes(connection, TASK_INDEX))
                    .extracting(IndexInfo::valid)
                    .containsExactly(true);
            assertThat(Long.parseLong(scalar(connection, "SELECT '" + INSTANCE_INDEX + "'::regclass::oid")))
                    .isEqualTo(instanceIndexBefore);
            assertThat(Long.parseLong(scalar(connection, "SELECT '" + TASK_INDEX + "'::regclass::oid")))
                    .isEqualTo(taskIndexBefore);

            // And the seeded scope values are unchanged, and the definition scope of the seeded instance is the default
            assertThat(scalar(connection, "SELECT scope_key FROM workflow_instances WHERE id = '" + INSTANCE_ID + "'"))
                    .isEqualTo("acme");
            assertThat(scalar(connection, "SELECT scope_key FROM workflow_tasks WHERE task_id = '" + TASK_ID + "'"))
                    .isEqualTo("acme");
            assertThat(scalar(
                            connection,
                            "SELECT definition_scope_key FROM workflow_instances WHERE id = '" + INSTANCE_ID + "'"))
                    .isEqualTo("default");
        }
    }

    // --- Fixtures ---

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static FluentConfiguration flyway(PostgresContainer container) {
        return Flyway.configure()
                .dataSource(container.jdbcUrl(), container.username(), container.password())
                .locations("classpath:db/migration/workflow");
    }

    private static Connection connection(PostgresContainer container) throws SQLException {
        return DriverManager.getConnection(container.jdbcUrl(), container.username(), container.password());
    }

    private static Pool pool(Vertx vertx, PostgresContainer container) {
        DbPoolConfig config = container.toPoolConfig();
        return PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(2))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
    }

    private static int columnCount(Connection connection, String table, String column) throws SQLException {
        return Integer.parseInt(scalar(connection, """
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = '%s' AND column_name = '%s'
                """.formatted(table, column)));
    }

    /** Describes a column as {@code type|length|nullable|default}. */
    private static String columnShape(Connection connection, String table, String column) throws SQLException {
        return scalar(connection, """
                SELECT data_type || '|' || character_maximum_length || '|' || is_nullable || '|'
                       || COALESCE(column_default, 'null')
                FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = '%s' AND column_name = '%s'
                """.formatted(table, column));
    }

    /** A catalog index as its validity flag and its {@code pg_get_indexdef} text. */
    private record IndexInfo(boolean valid, String definition) {}

    /** Lists the indexes of the given name in the current schema; a missing index yields an empty list. */
    private static List<IndexInfo> indexes(Connection connection, String indexName) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT i.indisvalid, pg_get_indexdef(i.indexrelid)
                FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                WHERE c.relname = ? AND c.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = current_schema())
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

    private static List<String> strings(Connection connection, String sql, String parameter) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, parameter);
            try (ResultSet rows = query.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
                return values;
            }
        }
    }

    /** Reads the number of scans the statistics collector attributes to the index; fails when the index is absent. */
    private static long indexScans(Connection connection, String indexName) throws SQLException {
        try (PreparedStatement query =
                connection.prepareStatement("SELECT idx_scan FROM pg_stat_user_indexes WHERE indexrelname = ?")) {
            query.setString(1, indexName);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new AssertionError("The index " + indexName + " does not exist, so it cannot be scanned");
                }
                return rows.getLong(1);
            }
        }
    }

    /** Polls the index scan counter until it exceeds the given value or a deadline passes; returns the last value. */
    private static long awaitScansAbove(Connection connection, String indexName, long above) throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        long seen = indexScans(connection, indexName);
        while (seen <= above && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
            seen = indexScans(connection, indexName);
        }
        return seen;
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery(sql)) {
            assertThat(rows.next()).as("a row for %s", sql).isTrue();
            return rows.getString(1);
        }
    }
}
