// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgSqlRepository;
import dev.vertique.db.postgresql.PgTableNames;
import dev.vertique.workflow.engine.spi.SignalWaitRecord;
import dev.vertique.workflow.engine.spi.WorkflowSignalWaitRepository;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL-backed repository for the open wait records in {@code workflow_signal_waits}.
 *
 * <p>A record is unique per {@code (instance_id, step_id, fork_step_id, branch_id)}; {@link #write} replaces the row
 * held at the address of the record it writes, so a wait entered again leaves one row with a new {@code wait_id}. The
 * columns {@code fork_step_id} and {@code branch_id} hold the empty string outside a branch, never {@code NULL}.
 *
 * <p>{@link #delete} removes the one row at an address and {@link #deleteAll} every row of an instance; neither is an
 * error when no row is held.
 *
 * <p>{@link #findOpen} joins {@code workflow_instances} and leaves out the records of instances that have ended. The
 * index on {@code (contract_name, signal_name, correlation_key)} serves it.
 */
@Singleton
public final class PgWorkflowSignalWaitRepository extends PgSqlRepository
        implements WorkflowSignalWaitRepository<SqlClient> {

    /** Name of the migration instance whose schema mapping places the workflow tables. */
    private static final String INSTANCE_NAME = "workflow";

    /** The most records {@link #findOpen} returns: enough to tell no match, one match and several matches apart. */
    private static final int MATCH_LIMIT = 2;

    // --- SQL statements ---
    //
    // The statements are built once at construction because every table reference is qualified with
    // the schema mapped for the workflow instance (when there is one), so the text depends on the
    // configured placement rather than being a compile-time constant.

    private static final String COLUMNS = "w.wait_id, w.instance_id, w.step_id, w.fork_step_id, w.branch_id,"
            + " w.scope_key, w.contract_name, w.definition_id, w.signal_name, w.correlation_key, w.created_at";

    private final String sqlWrite;
    private final String sqlDelete;
    private final String sqlDeleteAll;
    private final String sqlFindOpen;
    private final String sqlFindById;

    /**
     * Creates a new repository whose tables are not schema-qualified, so they resolve through the connection's
     * default schema.
     *
     * @param pool   the connection pool
     * @param mapper exception mapper for translating SQL errors
     */
    @Inject
    public PgWorkflowSignalWaitRepository(Pool pool, PgDbExceptionMapper mapper) {
        this(pool, mapper, DbSchemas.empty());
    }

    /**
     * Creates a new repository that qualifies every table reference with the schema mapped to the {@code workflow}
     * instance, when {@code schemas} holds one. Without a mapping the statements are identical to those of the
     * two-argument constructor.
     *
     * @param pool    the connection pool
     * @param mapper  exception mapper for translating SQL errors
     * @param schemas the schema placement; an empty mapping leaves table names unqualified
     * @throws IllegalArgumentException if the mapped schema name is not a valid single-segment PostgreSQL identifier
     */
    public PgWorkflowSignalWaitRepository(Pool pool, PgDbExceptionMapper mapper, DbSchemas schemas) {
        super(pool, mapper);

        String waits = PgTableNames.qualify(schemas, INSTANCE_NAME, "workflow_signal_waits");
        String instances = PgTableNames.qualify(schemas, INSTANCE_NAME, "workflow_instances");

        this.sqlWrite = "INSERT INTO " + waits
                + " (wait_id, instance_id, step_id, fork_step_id, branch_id, scope_key, contract_name,"
                + " definition_id, signal_name, correlation_key, created_at)"
                + " VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)"
                + " ON CONFLICT (instance_id, step_id, fork_step_id, branch_id) DO UPDATE SET"
                + " wait_id = EXCLUDED.wait_id, scope_key = EXCLUDED.scope_key,"
                + " contract_name = EXCLUDED.contract_name, definition_id = EXCLUDED.definition_id,"
                + " signal_name = EXCLUDED.signal_name, correlation_key = EXCLUDED.correlation_key,"
                + " created_at = EXCLUDED.created_at";

        this.sqlDelete = "DELETE FROM " + waits
                + " WHERE instance_id = $1 AND step_id = $2 AND fork_step_id = $3 AND branch_id = $4";

        this.sqlDeleteAll = "DELETE FROM " + waits + " WHERE instance_id = $1";

        this.sqlFindOpen =
                "SELECT " + COLUMNS + " FROM " + waits + " w JOIN " + instances + " i ON i.id = w.instance_id"
                        + " WHERE w.contract_name = $1 AND w.signal_name = $2 AND w.correlation_key = $3"
                        + " AND ($4::varchar IS NULL OR w.definition_id = $4)"
                        + " AND ($5::varchar IS NULL OR w.scope_key = $5)"
                        + " AND i.status NOT IN ('COMPLETED', 'COMPENSATED', 'CANCELLED', 'EXPIRED')"
                        + " ORDER BY w.created_at, w.wait_id LIMIT " + MATCH_LIMIT;

        this.sqlFindById = "SELECT " + COLUMNS + " FROM " + waits + " w WHERE w.wait_id = $1";
    }

    // --- Writes ---

    /**
     * Writes a record, replacing the row held at its address.
     *
     * @param wait the record to write
     * @param tx   the active transaction
     * @return a {@link Future} that completes when the record is written
     */
    @Override
    public Future<Void> write(SignalWaitRecord wait, SqlClient tx) {
        return tx.preparedQuery(sqlWrite)
                .execute(Tuple.from(Arrays.asList(
                        wait.waitId(),
                        wait.instanceId().value(),
                        wait.stepId(),
                        wait.forkStepId(),
                        wait.branchId(),
                        wait.scopeKey(),
                        wait.contractName(),
                        wait.definitionId(),
                        wait.signalName(),
                        wait.correlationKey(),
                        OffsetDateTime.ofInstant(wait.createdAt(), ZoneOffset.UTC))))
                .<Void>mapEmpty()
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_signal_waits write")));
    }

    /**
     * Deletes the record held at an address.
     *
     * @param instanceId the instance that owns the wait
     * @param stepId     the step of the wait
     * @param forkStepId the fork step of the branch, or the empty string for the main wait
     * @param branchId   the branch, or the empty string for the main wait
     * @param tx         the active transaction
     * @return a {@link Future} of {@code true} when a record was deleted, {@code false} when none was held
     */
    @Override
    public Future<Boolean> delete(
            WorkflowInstanceId instanceId, String stepId, String forkStepId, String branchId, SqlClient tx) {
        return tx.preparedQuery(sqlDelete)
                .execute(Tuple.of(instanceId.value(), stepId, forkStepId, branchId))
                .map(rs -> rs.rowCount() > 0)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_signal_waits delete")));
    }

    /**
     * Deletes every record of an instance.
     *
     * @param instanceId the instance whose records are deleted
     * @param tx         the active transaction
     * @return a {@link Future} that completes when the records are deleted
     */
    @Override
    public Future<Void> deleteAll(WorkflowInstanceId instanceId, SqlClient tx) {
        return tx.preparedQuery(sqlDeleteAll)
                .execute(Tuple.of(instanceId.value()))
                .<Void>mapEmpty()
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_signal_waits deleteAll")));
    }

    // --- Reads ---

    /**
     * Reads at most two open records of a contract, a signal name and a key.
     *
     * @param contractName the logical name of the contract
     * @param signalName   the name of the signal the wait is for
     * @param key          the correlation key the wait computed
     * @param definitionId when non-null, only records of instances pinned to this definition id
     * @param scopeKey     when non-null, only records of instances started in this scope
     * @param tx           the active transaction
     * @return a {@link Future} of at most two records
     */
    @Override
    public Future<List<SignalWaitRecord>> findOpen(
            String contractName,
            String signalName,
            String key,
            @Nullable String definitionId,
            @Nullable String scopeKey,
            SqlClient tx) {
        return tx.preparedQuery(sqlFindOpen)
                .execute(Tuple.from(Arrays.asList(contractName, signalName, key, definitionId, scopeKey)))
                .map(PgWorkflowSignalWaitRepository::mapAll)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_signal_waits findOpen")));
    }

    /**
     * Reads a record by its identity.
     *
     * @param waitId the wait id
     * @param tx     the active transaction
     * @return a {@link Future} of the record, or {@link Optional#empty()} when it is no longer held
     */
    @Override
    public Future<Optional<SignalWaitRecord>> findById(UUID waitId, SqlClient tx) {
        return tx.preparedQuery(sqlFindById)
                .execute(Tuple.of(waitId))
                .map(rs -> mapAll(rs).stream().findFirst())
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_signal_waits findById")));
    }

    // --- Row mapping ---

    private static List<SignalWaitRecord> mapAll(RowSet<Row> rows) {
        List<SignalWaitRecord> records = new ArrayList<>();
        for (Row row : rows) {
            records.add(new SignalWaitRecord(
                    row.getUUID("wait_id"),
                    new WorkflowInstanceId(row.getUUID("instance_id")),
                    row.getString("step_id"),
                    row.getString("fork_step_id"),
                    row.getString("branch_id"),
                    row.getString("scope_key"),
                    row.getString("contract_name"),
                    row.getString("definition_id"),
                    row.getString("signal_name"),
                    row.getString("correlation_key"),
                    row.getOffsetDateTime("created_at").toInstant()));
        }
        return records;
    }
}
