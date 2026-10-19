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
import dev.vertique.workflow.ops.WorkflowInstanceId;
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
 * Verifies the single migration that adds the definition registry, its digest and the tenant key columns with their
 * partial indexes, on databases that already hold workflow rows. Every scenario uses a database of its own.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class TenantKeyMigrationIT {

    private static final String PINNED_V1_SHA256 = "b444c9f5943de792243e7080d78531d9c1755d9413429980ac98322727889ae2";

    private static final String V1_SCRIPT = "V1__create_workflow_tables.sql";
    private static final String V002_SCRIPT = "V002__create_workflow_definitions_and_tenant_key.sql";

    private static final String INSTANCE_INDEX = "idx_workflow_instances_tenant_updated_at";
    private static final String TASK_INDEX = "idx_workflow_tasks_tenant_updated_at";

    private static final String INSTANCE_ID = "00000000-0000-0000-0000-0000000000a1";
    private static final String TASK_ID = "00000000-0000-0000-0000-0000000000b1";

    /** Tenant whose rows the index scenario reads; each of the 40 tenants holds roughly one row in 41. */
    private static final String QUERIED_TENANT = "tenant-7";

    private static final int SEEDED_ROWS = 3200;
    private static final int PAGE_SIZE = 10;

    static final PostgresContainer upgradeDb = new PostgresContainer().withDatabaseName("wf_single_migration_it");

    static final PostgresContainer indexDb = new PostgresContainer().withDatabaseName("wf_tenant_index_it");

    static final PostgresContainer aheadDb = new PostgresContainer().withDatabaseName("wf_ahead_of_time_it");

    @Test
    void upgradeFromV1AppliesOneMigrationWithDefinitionsDigestTenantColumnsAndIndexes(Vertx vertx) throws Exception {
        // Given the first migration is shipped byte for byte as it was released
        assertThat(shippedSha256(V1_SCRIPT)).isEqualTo(PINNED_V1_SHA256);
        String shippedScript = shippedText(V002_SCRIPT);

        // And a database migrated to the first version only, holding one instance and one task
        flyway(upgradeDb).target("1").load().migrate();
        int v1ChecksumBefore;
        try (Connection connection = connection(upgradeDb);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO workflow_instances
                        (id, definition_id, definition_version, plan_hash, status, current_step_id, state_json,
                         created_at, updated_at)
                    VALUES ('%s', 'orders', 1, repeat('a', 64), 'WAITING', 'review', '{}'::jsonb,
                            '2026-05-10T00:00:00Z', '2026-05-10T00:00:00Z')
                    """.formatted(INSTANCE_ID));
            statement.executeUpdate("""
                    INSERT INTO workflow_tasks
                        (task_id, workflow_id, step_id, status, assignee_type, assignee_key,
                         decisions_snapshot_json, created_at, updated_at)
                    VALUES ('%s', '%s', 'review', 'OPEN', 'ROLE', 'reviewers', '[]'::jsonb,
                            '2026-05-10T00:00:00Z', '2026-05-10T00:00:00Z')
                    """.formatted(TASK_ID, INSTANCE_ID));
            v1ChecksumBefore = scriptChecksum(connection, V1_SCRIPT);
            assertThat(columnCount(connection, "workflow_instances", "tenant_key"))
                    .as("no tenant column before the migration")
                    .isZero();
            assertThat(columnCount(connection, "workflow_tasks", "tenant_key"))
                    .as("no tenant column before the migration")
                    .isZero();
        }

        // When the upgrade runs through the real Flyway run
        Flyway latest = flyway(upgradeDb).load();
        assertThat(latest.info().pending())
                .extracting(MigrationInfo::getScript)
                .as("only the single new migration is pending after the first one")
                .containsExactly(V002_SCRIPT);
        int resolvedChecksum = latest.info().pending()[0].getChecksum();
        latest.migrate();

        // Then the script bounds its lock wait with its very first statement
        String statements = withoutComments(shippedScript);
        assertThat(firstStatement(statements))
                .as("the first statement must bound the lock wait")
                .isEqualTo("SET LOCAL lock_timeout = '30s'");

        // And the script holds nothing that cannot run inside the single migration transaction
        assertThat(statements.toUpperCase(Locale.ROOT))
                .doesNotContain("CONCURRENTLY", "VACUUM", "CREATE DATABASE", "ALTER SYSTEM");

        // And exactly versions 1 and 2 are applied, and the location holds no other script
        assertThat(latest.info().applied())
                .extracting(MigrationInfo::getVersion)
                .containsExactly(MigrationVersion.fromVersion("1"), MigrationVersion.fromVersion("2"));
        assertThat(latest.info().all()).extracting(MigrationInfo::getScript).containsExactly(V1_SCRIPT, V002_SCRIPT);

        try (Connection connection = connection(upgradeDb)) {
            // And the first migration keeps its recorded checksum and the new one records the shipped file's
            assertThat(scriptChecksum(connection, V1_SCRIPT)).isEqualTo(v1ChecksumBefore);
            assertThat(scriptChecksum(connection, V002_SCRIPT)).isEqualTo(resolvedChecksum);

            // And the digest column is a 64-character string guarded by the lowercase hex constraint
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

            // And both tables have a nullable tenant_key VARCHAR(255) without a default
            for (String table : new String[] {"workflow_instances", "workflow_tasks"}) {
                assertThat(columnShape(connection, table, "tenant_key"))
                        .as("tenant_key on %s", table)
                        .isEqualTo("character varying|255|YES|null");
            }

            // And the rows inserted before the upgrade are untouched and hold a null tenant
            assertThat(scalar(connection, "SELECT count(*) FROM workflow_instances"))
                    .isEqualTo("1");
            assertThat(scalar(connection, "SELECT count(*) FROM workflow_tasks"))
                    .isEqualTo("1");
            assertThat(scalar(connection, "SELECT count(*) FROM workflow_instances WHERE tenant_key IS NULL"))
                    .isEqualTo("1");
            assertThat(scalar(connection, "SELECT count(*) FROM workflow_tasks WHERE tenant_key IS NULL"))
                    .isEqualTo("1");

            // And each tenant index exists once, is valid, is partial on the tenant and lists the keyset order
            assertThat(indexes(connection, INSTANCE_INDEX)).singleElement().satisfies(index -> {
                assertThat(index.valid()).isTrue();
                assertThat(index.definition())
                        .contains("workflow_instances USING btree (tenant_key, updated_at DESC, id)")
                        .endsWith("WHERE (tenant_key IS NOT NULL)");
            });
            assertThat(indexes(connection, TASK_INDEX)).singleElement().satisfies(index -> {
                assertThat(index.valid()).isTrue();
                assertThat(index.definition())
                        .contains("workflow_tasks USING btree (tenant_key, updated_at DESC, task_id)")
                        .endsWith("WHERE (tenant_key IS NOT NULL)");
            });
        }

        // And the repositories read the pre-existing rows with a null tenant
        Pool pool = pool(vertx, upgradeDb);
        try {
            PgDbExceptionMapper mapper = new PgDbExceptionMapper();
            PgWorkflowInstanceRepository instances = new PgWorkflowInstanceRepository(pool, mapper);
            PgTaskStore tasks = new PgTaskStore(pool, mapper);

            WorkflowInstance waiting = await(instances.findById(new WorkflowInstanceId(UUID.fromString(INSTANCE_ID))))
                    .orElseThrow();
            TaskRecord task =
                    await(tasks.findById(UUID.fromString(TASK_ID), pool)).orElseThrow();

            assertThat(waiting.tenantKey()).isNull();
            assertThat(task.tenantKey()).isNull();
            assertThat(await(instances.findFiltered(WorkflowInstanceQuery.none(), PageCursor.first(10)))
                            .items())
                    .extracting(WorkflowInstance::tenantKey)
                    .containsOnlyNulls()
                    .hasSize(1);
            assertThat(await(instances.findFiltered(
                                    WorkflowInstanceQuery.none().withTenantKey("t1"), PageCursor.first(10)))
                            .items())
                    .isEmpty();
        } finally {
            await(pool.close());
        }
    }

    @Test
    void tenantFilteredKeysetQueriesCanUseTheTenantIndexes(Vertx vertx) throws Exception {
        // Given a database upgraded to the latest version
        flyway(indexDb).load().migrate();

        // And the planner may not fall back to a sequential scan, so it must choose among the indexes
        try (Connection connection = connection(indexDb);
                Statement statement = connection.createStatement()) {
            String databaseName = scalar(connection, "SELECT current_database()");
            statement.execute("ALTER DATABASE \"" + databaseName + "\" SET enable_seqscan = off");

            // And instances and tasks spread over 40 tenants and the null tenant, some of them archived
            statement.executeUpdate("""
                    INSERT INTO workflow_instances
                        (id, definition_id, definition_version, plan_hash, status, current_step_id, state_json,
                         created_at, updated_at, archived_at, tenant_key)
                    SELECT md5('instance-' || g)::uuid, 'orders', 1, repeat('a', 64), 'RUNNING', 'start',
                           '{}'::jsonb,
                           timestamptz '2026-05-10T00:00:00Z' + g * interval '1 second',
                           timestamptz '2026-05-10T00:00:00Z' + g * interval '1 second',
                           CASE WHEN g %% 13 = 0 THEN timestamptz '2026-06-01T00:00:00Z' END,
                           CASE WHEN g %% 41 = 0 THEN NULL ELSE 'tenant-' || (g %% 41) END
                    FROM generate_series(1, %d) g
                    """.formatted(SEEDED_ROWS));
            statement.executeUpdate("""
                    INSERT INTO workflow_tasks
                        (task_id, workflow_id, step_id, status, assignee_type, assignee_key,
                         decisions_snapshot_json, created_at, updated_at, tenant_key)
                    SELECT md5('task-' || g)::uuid, md5('instance-' || g)::uuid, 'review', 'OPEN', 'ROLE',
                           'reviewers', '[]'::jsonb,
                           timestamptz '2026-05-10T00:00:00Z' + g * interval '1 second',
                           timestamptz '2026-05-10T00:00:00Z' + g * interval '1 second',
                           CASE WHEN g %% 41 = 0 THEN NULL ELSE 'tenant-' || (g %% 41) END
                    FROM generate_series(1, %d) g
                    """.formatted(SEEDED_ROWS));
            statement.execute("ANALYZE workflow_instances");
            statement.execute("ANALYZE workflow_tasks");
        }

        // And the keyset pages the queried tenant must return, read without touching any index
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
                    WHERE tenant_key = ? AND archived_at IS NULL
                    ORDER BY updated_at DESC, id ASC LIMIT 20
                    """, QUERIED_TENANT));
            expectedTaskIds.addAll(strings(reference, """
                    SELECT t.task_id::text FROM workflow_tasks t
                    WHERE t.tenant_key = ?
                      AND NOT EXISTS (SELECT 1 FROM workflow_instances i
                                      WHERE i.id = t.workflow_id AND i.archived_at IS NOT NULL)
                    ORDER BY t.updated_at DESC, t.task_id ASC LIMIT 20
                    """, QUERIED_TENANT));
        }
        assertThat(expectedInstanceIds).as("two full pages of live instances").hasSize(2 * PAGE_SIZE);
        assertThat(expectedTaskIds).as("two full pages of live tasks").hasSize(2 * PAGE_SIZE);

        // And the scan counters of both tenant indexes before any query ran
        long instanceScansBefore;
        long taskScansBefore;
        try (Connection stats = connection(indexDb)) {
            instanceScansBefore = indexScans(stats, INSTANCE_INDEX);
            taskScansBefore = indexScans(stats, TASK_INDEX);
        }

        // When the real repositories read the first page and the keyset continuation for the tenant
        // (the pool is built after the planner setting, so its sessions carry it)
        Pool pool = pool(vertx, indexDb);
        try {
            PgDbExceptionMapper mapper = new PgDbExceptionMapper();
            PgWorkflowInstanceRepository instances = new PgWorkflowInstanceRepository(pool, mapper);
            PgTaskStore tasks = new PgTaskStore(pool, mapper);

            WorkflowInstanceQuery instanceQuery = WorkflowInstanceQuery.none().withTenantKey(QUERIED_TENANT);
            PagedResult<WorkflowInstance> instancePage1 =
                    await(instances.findFiltered(instanceQuery, PageCursor.first(PAGE_SIZE)));
            PagedResult<WorkflowInstance> instancePage2 =
                    await(instances.findFiltered(instanceQuery, PageCursor.fromToken(instancePage1.nextCursorToken())));

            TaskFilter taskFilter = TaskFilter.empty().withTenantKey(QUERIED_TENANT);
            PagedResult<TaskRecord> taskPage1 =
                    await(tasks.findByFilter(taskFilter, PageCursor.first(PAGE_SIZE), pool));
            PagedResult<TaskRecord> taskPage2 =
                    await(tasks.findByFilter(taskFilter, PageCursor.fromToken(taskPage1.nextCursorToken()), pool));

            // Then the pages hold only the tenant's live rows, in keyset order across the page boundary
            assertThat(instancePage1.hasMore()).isTrue();
            assertThat(taskPage1.hasMore()).isTrue();
            List<WorkflowInstance> instanceRows = new ArrayList<>(instancePage1.items());
            instanceRows.addAll(instancePage2.items());
            List<TaskRecord> taskRows = new ArrayList<>(taskPage1.items());
            taskRows.addAll(taskPage2.items());
            assertThat(instanceRows).extracting(WorkflowInstance::tenantKey).containsOnly(QUERIED_TENANT);
            assertThat(taskRows).extracting(TaskRecord::tenantKey).containsOnly(QUERIED_TENANT);
            assertThat(instanceRows)
                    .extracting(row -> row.id().value().toString())
                    .containsExactlyElementsOf(expectedInstanceIds);
            assertThat(taskRows).extracting(row -> row.taskId().toString()).containsExactlyElementsOf(expectedTaskIds);

            // And the scan counter of each tenant index rises
            // (backend statistics are published asynchronously, so the counters are polled until the deadline)
            try (Connection stats = connection(indexDb)) {
                assertThat(awaitScansAbove(stats, INSTANCE_INDEX, instanceScansBefore))
                        .as("scans of %s", INSTANCE_INDEX)
                        .isGreaterThan(instanceScansBefore);
                assertThat(awaitScansAbove(stats, TASK_INDEX, taskScansBefore))
                        .as("scans of %s", TASK_INDEX)
                        .isGreaterThan(taskScansBefore);
            }
        } finally {
            await(pool.close());
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
            statement.execute("ALTER TABLE workflow_tasks     ADD COLUMN IF NOT EXISTS tenant_key VARCHAR(255)");
            statement.execute("ALTER TABLE workflow_instances ADD COLUMN IF NOT EXISTS tenant_key VARCHAR(255)");

            // And an instance and a task carrying tenant t1
            statement.executeUpdate("""
                    INSERT INTO workflow_instances
                        (id, definition_id, definition_version, plan_hash, status, current_step_id, state_json,
                         created_at, updated_at, tenant_key)
                    VALUES ('%s', 'orders', 1, repeat('a', 64), 'WAITING', 'review', '{}'::jsonb,
                            '2026-05-10T00:00:00Z', '2026-05-10T00:00:00Z', 't1')
                    """.formatted(INSTANCE_ID));
            statement.executeUpdate("""
                    INSERT INTO workflow_tasks
                        (task_id, workflow_id, step_id, status, assignee_type, assignee_key,
                         decisions_snapshot_json, created_at, updated_at, tenant_key)
                    VALUES ('%s', '%s', 'review', 'OPEN', 'ROLE', 'reviewers', '[]'::jsonb,
                            '2026-05-10T00:00:00Z', '2026-05-10T00:00:00Z', 't1')
                    """.formatted(TASK_ID, INSTANCE_ID));
            statement.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_workflow_tasks_tenant_updated_at"
                    + " ON workflow_tasks (tenant_key, updated_at DESC, task_id ASC) WHERE tenant_key IS NOT NULL");
            statement.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_workflow_instances_tenant_updated_at"
                    + " ON workflow_instances (tenant_key, updated_at DESC, id ASC) WHERE tenant_key IS NOT NULL");
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
            // And each table still has exactly one tenant_key column
            assertThat(columnCount(connection, "workflow_instances", "tenant_key"))
                    .isEqualTo(1);
            assertThat(columnCount(connection, "workflow_tasks", "tenant_key")).isEqualTo(1);

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

            // And the seeded tenant values are unchanged
            assertThat(scalar(connection, "SELECT tenant_key FROM workflow_instances WHERE id = '" + INSTANCE_ID + "'"))
                    .isEqualTo("t1");
            assertThat(scalar(connection, "SELECT tenant_key FROM workflow_tasks WHERE task_id = '" + TASK_ID + "'"))
                    .isEqualTo("t1");
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

    private static String shippedText(String script) throws Exception {
        try (var migration = TenantKeyMigrationIT.class.getResourceAsStream("/db/migration/workflow/" + script)) {
            if (migration == null) {
                throw new AssertionError("The shipped migration " + script + " is missing from the classpath");
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String shippedSha256(String script) throws Exception {
        try (var migration = TenantKeyMigrationIT.class.getResourceAsStream("/db/migration/workflow/" + script)) {
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long seen = indexScans(connection, indexName);
        while (seen <= above && System.nanoTime() < deadline) {
            Thread.onSpinWait();
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
