// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.query.PageCursor;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.query.WorkflowInstanceQuery;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.tasks.TaskRecord;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Verifies the additive tenant key migration on a database that already holds workflow rows. */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class TenantKeyMigrationIT {

    private static final String PINNED_V1_SHA256 = "b444c9f5943de792243e7080d78531d9c1755d9413429980ac98322727889ae2";
    private static final String PINNED_V002_SHA256 = "656243f7dd3a353fdc1e7dbf797f01c2acd8ff9e02b3afadbc03bbdfe135d44b";
    private static final String PINNED_V003_SHA256 = "f973a5079052c74790d7d394988cb981ded29c1d4b9c706bcb299fe77cd0ed7c";

    private static final String V1_SCRIPT = "V1__create_workflow_tables.sql";
    private static final String V002_SCRIPT = "V002__create_workflow_definitions.sql";
    private static final String V003_SCRIPT = "V003__add_workflow_definition_digest.sql";
    private static final String V004_SCRIPT = "V004__add_workflow_tenant_key.sql";

    private static final String INSTANCE_ID = "00000000-0000-0000-0000-0000000000a1";
    private static final String OTHER_INSTANCE_ID = "00000000-0000-0000-0000-0000000000a2";
    private static final String TASK_ID = "00000000-0000-0000-0000-0000000000b1";

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("wf_tenant_key_migration_it");

    @Test
    void v004AddsNullableColumnsAndKeepsExistingRowsNull(Vertx vertx) throws Exception {
        // Given the earlier migrations are shipped byte for byte as they were released
        assertThat(shippedSha256(V1_SCRIPT)).isEqualTo(PINNED_V1_SHA256);
        assertThat(shippedSha256(V002_SCRIPT)).isEqualTo(PINNED_V002_SHA256);
        assertThat(shippedSha256(V003_SCRIPT)).isEqualTo(PINNED_V003_SHA256);

        // And the tenant key migration is packaged next to them
        assertThat(TenantKeyMigrationIT.class.getResource("/db/migration/workflow/" + V004_SCRIPT))
                .as("the tenant key migration must be packaged with the other workflow migrations")
                .isNotNull();

        // And a database migrated to the definition digest only, holding instance, task, history and timer rows
        Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations("classpath:db/migration/workflow")
                .target("3")
                .load()
                .migrate();
        int v1ChecksumBefore;
        int v002ChecksumBefore;
        int v003ChecksumBefore;
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO workflow_instances
                        (id, definition_id, definition_version, plan_hash, status, current_step_id, state_json,
                         created_at, updated_at)
                    VALUES ('%s', 'orders', 1, repeat('a', 64), 'WAITING', 'review', '{}'::jsonb,
                            '2026-05-10T00:00:00Z', '2026-05-10T00:00:00Z'),
                           ('%s', 'orders', 1, repeat('a', 64), 'COMPLETED', 'end', '{}'::jsonb,
                            '2026-05-10T00:01:00Z', '2026-05-10T00:01:00Z')
                    """.formatted(INSTANCE_ID, OTHER_INSTANCE_ID));
            statement.executeUpdate("""
                    INSERT INTO workflow_tasks
                        (task_id, workflow_id, step_id, status, assignee_type, assignee_key,
                         decisions_snapshot_json, created_at, updated_at)
                    VALUES ('%s', '%s', 'review', 'OPEN', 'ROLE', 'reviewers', '[]'::jsonb,
                            '2026-05-10T00:00:00Z', '2026-05-10T00:00:00Z')
                    """.formatted(TASK_ID, INSTANCE_ID));
            statement.executeUpdate("""
                    INSERT INTO workflow_history (workflow_id, sequence, entry_type, payload_json)
                    VALUES ('%s', 1, 'STARTED', '{}'::jsonb)
                    """.formatted(INSTANCE_ID));
            statement.executeUpdate("""
                    INSERT INTO workflow_timers
                        (timer_id, workflow_id, step_id, status, fire_at, delayed_job_execution_id, scheduled_at)
                    VALUES ('00000000-0000-0000-0000-0000000000c1', '%s', 'review', 'SCHEDULED',
                            '2026-05-11T00:00:00Z', '00000000-0000-0000-0000-0000000000d1', '2026-05-10T00:00:00Z')
                    """.formatted(INSTANCE_ID));
            v1ChecksumBefore = scriptChecksum(connection, V1_SCRIPT);
            v002ChecksumBefore = scriptChecksum(connection, V002_SCRIPT);
            v003ChecksumBefore = scriptChecksum(connection, V003_SCRIPT);
            assertThat(columnCount(connection, "workflow_instances", "tenant_key"))
                    .as("no tenant column before the migration")
                    .isZero();
            assertThat(columnCount(connection, "workflow_tasks", "tenant_key"))
                    .as("no tenant column before the migration")
                    .isZero();
        }

        // When the tenant key migration is applied through the real Flyway run
        Flyway latest = Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations("classpath:db/migration/workflow")
                .target("4")
                .load();
        assertThat(latest.info().pending())
                .extracting(MigrationInfo::getScript)
                .as("only the tenant key migration is pending after the digest migration")
                .containsExactly(V004_SCRIPT);
        int resolvedV004Checksum = latest.info().pending()[0].getChecksum();
        latest.migrate();

        try (Connection connection = connection()) {
            // Then the earlier migrations keep their recorded checksums and the new one records the shipped file's
            assertThat(scriptChecksum(connection, V1_SCRIPT)).isEqualTo(v1ChecksumBefore);
            assertThat(scriptChecksum(connection, V002_SCRIPT)).isEqualTo(v002ChecksumBefore);
            assertThat(scriptChecksum(connection, V003_SCRIPT)).isEqualTo(v003ChecksumBefore);
            assertThat(scriptChecksum(connection, V004_SCRIPT)).isEqualTo(resolvedV004Checksum);

            // And both tables have a nullable tenant_key VARCHAR(255) without a default
            for (String table : new String[] {"workflow_instances", "workflow_tasks"}) {
                assertThat(columnShape(connection, table))
                        .as("tenant_key on %s", table)
                        .isEqualTo("character varying|255|YES|null");
            }

            // And the rows inserted before the migration are untouched and hold a null tenant
            assertThat(scalar(connection, "SELECT count(*) FROM workflow_instances"))
                    .isEqualTo("2");
            assertThat(scalar(connection, "SELECT count(*) FROM workflow_tasks"))
                    .isEqualTo("1");
            assertThat(scalar(connection, "SELECT count(*) FROM workflow_history"))
                    .isEqualTo("1");
            assertThat(scalar(connection, "SELECT count(*) FROM workflow_timers"))
                    .isEqualTo("1");
            assertThat(scalar(connection, "SELECT count(*) FROM workflow_instances WHERE tenant_key IS NULL"))
                    .isEqualTo("2");
            assertThat(scalar(connection, "SELECT count(*) FROM workflow_tasks WHERE tenant_key IS NULL"))
                    .isEqualTo("1");
        }

        // And the repositories read those rows with a null tenant
        DbPoolConfig config = db.toPoolConfig();
        Pool pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(2))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
        try {
            PgDbExceptionMapper mapper = new PgDbExceptionMapper();
            PgWorkflowInstanceRepository instances = new PgWorkflowInstanceRepository(pool, mapper);
            PgTaskStore tasks = new PgTaskStore(pool, mapper);

            WorkflowInstance waiting = await(instances.findById(new WorkflowInstanceId(UUID.fromString(INSTANCE_ID))))
                    .orElseThrow();
            WorkflowInstance completed = await(
                            instances.findById(new WorkflowInstanceId(UUID.fromString(OTHER_INSTANCE_ID))))
                    .orElseThrow();
            TaskRecord task =
                    await(tasks.findById(UUID.fromString(TASK_ID), pool)).orElseThrow();

            assertThat(waiting.tenantKey()).isNull();
            assertThat(completed.tenantKey()).isNull();
            assertThat(task.tenantKey()).isNull();
            assertThat(await(instances.findFiltered(WorkflowInstanceQuery.none(), PageCursor.first(10)))
                            .items())
                    .extracting(WorkflowInstance::tenantKey)
                    .containsOnlyNulls()
                    .hasSize(2);
            assertThat(await(instances.findFiltered(
                                    WorkflowInstanceQuery.none().withTenantKey("t1"), PageCursor.first(10)))
                            .items())
                    .isEmpty();
        } finally {
            await(pool.close());
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
    }

    private static String shippedSha256(String script) throws Exception {
        try (var migration = TenantKeyMigrationIT.class.getResourceAsStream("/db/migration/workflow/" + script)) {
            if (migration == null) {
                throw new AssertionError("The shipped migration " + script + " is missing from the classpath");
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(migration.readAllBytes()));
        }
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

    /** Describes the tenant column as {@code type|length|nullable|default}. */
    private static String columnShape(Connection connection, String table) throws SQLException {
        return scalar(connection, """
                SELECT data_type || '|' || character_maximum_length || '|' || is_nullable || '|'
                       || COALESCE(column_default, 'null')
                FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = '%s' AND column_name = 'tenant_key'
                """.formatted(table));
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery(sql)) {
            assertThat(rows.next()).as("a row for %s", sql).isTrue();
            return rows.getString(1);
        }
    }
}
